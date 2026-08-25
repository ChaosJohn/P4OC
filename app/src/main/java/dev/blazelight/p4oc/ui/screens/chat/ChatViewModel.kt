package dev.blazelight.p4oc.ui.screens.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.blazelight.p4oc.core.datastore.ChatSettings
import dev.blazelight.p4oc.core.datastore.NotificationSettings
import dev.blazelight.p4oc.core.datastore.SettingsDataStore
import dev.blazelight.p4oc.core.haptic.HapticFeedback
import dev.blazelight.p4oc.core.log.AppLog
import dev.blazelight.p4oc.core.mime.FilenameMimeType
import dev.blazelight.p4oc.core.network.ApiResult
import dev.blazelight.p4oc.core.network.ConnectionState
import dev.blazelight.p4oc.core.network.ServerConnectionRegistry
import dev.blazelight.p4oc.core.network.safeApiCall
import dev.blazelight.p4oc.data.remote.dto.ExecuteCommandRequest
import dev.blazelight.p4oc.data.remote.dto.PartInputDto
import dev.blazelight.p4oc.data.remote.dto.PermissionResponseRequest
import dev.blazelight.p4oc.data.remote.dto.QuestionReplyRequest
import dev.blazelight.p4oc.data.remote.dto.RevertSessionRequest
import dev.blazelight.p4oc.data.remote.dto.SendMessageRequest
import dev.blazelight.p4oc.data.remote.mapper.CommandMapper
import dev.blazelight.p4oc.data.remote.mapper.SessionMapper
import dev.blazelight.p4oc.data.remote.mapper.TodoMapper
import dev.blazelight.p4oc.data.session.SessionRepositoryImpl
import dev.blazelight.p4oc.data.session.SessionUiState
import dev.blazelight.p4oc.data.session.presence
import dev.blazelight.p4oc.data.workspace.WorkspaceClient
import dev.blazelight.p4oc.domain.model.*
import dev.blazelight.p4oc.domain.session.SessionId
import dev.blazelight.p4oc.ui.components.chat.SelectedFile
import dev.blazelight.p4oc.ui.navigation.Screen
import dev.blazelight.p4oc.ui.screens.files.upload.UploadCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URI

/**
 * Slim coordinator — delegates to sub-managers for message state,
 * dialogs, model/agent selection, and file picking. Retains session
 * lifecycle, message sending, command execution, and SSE event routing.
 */
@Suppress("LargeClass", "LongParameterList")
class ChatViewModel constructor(
    private val savedStateHandle: SavedStateHandle,
    private val workspaceClient: WorkspaceClient,
    private val sessionRepository: SessionRepositoryImpl,
    private val uploadCoordinator: UploadCoordinator,
    private val settingsDataStore: SettingsDataStore,
    private val hapticFeedback: HapticFeedback,
    private val modelSelectionCoordinator: ModelSelectionCoordinator = ModelSelectionCoordinator(),
    private val serverConnectionRegistry: ServerConnectionRegistry? = null,
) : ViewModel() {
    private val sessionId: String = savedStateHandle.get<String>(Screen.Chat.ARG_SESSION_ID)
        ?: throw IllegalArgumentException("sessionId is required for ChatViewModel")
    private val sessionLease = sessionRepository.acquireSession(SessionId(sessionId))

    // JSON serializer for SavedStateHandle persistence
    private val json = Json { ignoreUnknownKeys = true }

    // --- Sub-managers ---
    val dialogManager = DialogQueueManager(savedStateHandle, json, viewModelScope)
    val modelAgentManager = ModelAgentManager(
        workspaceClient,
        settingsDataStore,
        viewModelScope,
        sessionId,
        modelSelectionCoordinator,
        serverConnectionRegistry,
    )
    val filePickerManager = FilePickerManager(workspaceClient, viewModelScope, uploadCoordinator, settingsDataStore)

    // --- Core state ---
    private val _uiState = MutableStateFlow(ChatUiState(inputText = restoredInputText()))
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()
    private val _sessionMissing = MutableSharedFlow<Unit>(replay = 1)
    val sessionMissing: SharedFlow<Unit> = _sessionMissing.asSharedFlow()

    /** Convenience alias — ChatScreen reads this directly. */
    val messages: StateFlow<List<MessageWithParts>> = sessionRepository.messages(SessionId(sessionId))
    private val repositorySessionState: StateFlow<dev.blazelight.p4oc.data.session.SessionUiState> =
        sessionRepository.sessionUiState(SessionId(sessionId))

    val connectionState: StateFlow<ConnectionState> = workspaceClient.connectionState

    private val _branchName = MutableStateFlow<String?>(null)
    val branchName: StateFlow<String?> = _branchName.asStateFlow()

    // Track whether this tab has unread responses (LLM finished but user hasn't viewed)
    private val _hasUnreadResponse = MutableStateFlow(false)
    val hasUnreadResponse: StateFlow<Boolean> = _hasUnreadResponse.asStateFlow()
    private val _isActiveTab = MutableStateFlow(false)

    init {
        serverConnectionRegistry?.let(::observeCommandCatalogEvents)
    }

    @OptIn(FlowPreview::class)
    private fun observeCommandCatalogEvents(registry: ServerConnectionRegistry) {
        viewModelScope.launch {
            registry.events(workspaceClient.workspace.server)
                .filter { scopedEvent ->
                    val event = scopedEvent.event
                    val refreshesCommands = event is OpenCodeEvent.ModelsRefreshed ||
                        event is OpenCodeEvent.CatalogUpdated ||
                        event is OpenCodeEvent.McpToolsChanged
                    scopedEvent.generation == workspaceClient.generation &&
                        scopedEvent.workspaceKey == workspaceClient.workspace.key &&
                        refreshesCommands
                }
                .debounce(COMMAND_CATALOG_REFRESH_DEBOUNCE_MS)
                .collect { refreshCommandsInBackground() }
        }
    }

    /**
     * UI presence for tab indicators. Awaiting input is reserved for real
     * permission/question prompts; unread responses are a separate state.
     */
    val sessionConnectionState: StateFlow<SessionPresence> = combine(
        repositorySessionState,
        dialogManager.pendingQuestion,
        dialogManager.pendingPermissionsByCallId,
        _hasUnreadResponse,
        messages
    ) { repositoryState: SessionUiState,
        pendingQuestion: QuestionRequest?,
        pendingPermissionsByCallId: Map<String, Permission>,
        hasUnread: Boolean,
        msgs: List<MessageWithParts> ->
        val hasRunningTools = msgs.any { msg ->
            msg.parts.any { part -> part is Part.Tool && part.state is ToolState.Running }
        }
        val hasStreamingText = msgs.any { msg ->
            msg.parts.any { part -> part is Part.Text && part.isStreaming }
        }

        repositoryState.copy(
            pendingQuestion = pendingQuestion,
            pendingPermissionsByCallId = pendingPermissionsByCallId,
        ).presence(
            hasUnread = hasUnread,
            hasStreamingText = hasStreamingText,
            hasRunningTools = hasRunningTools,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SessionPresence.IDLE)

    val visualSettings = settingsDataStore.visualSettings
        .stateIn(viewModelScope, SharingStarted.Eagerly, dev.blazelight.p4oc.core.datastore.VisualSettings())

    val chatSettings = settingsDataStore.chatSettings
        .stateIn(viewModelScope, SharingStarted.Eagerly, ChatSettings())

    private val notificationSettings: StateFlow<NotificationSettings> =
        settingsDataStore.notificationSettings
            .stateIn(viewModelScope, SharingStarted.Eagerly, NotificationSettings())

    private fun beginLoadStep(step: String) {
        _uiState.update { it.copy(loadingSteps = it.loadingSteps + step) }
    }

    private fun endLoadStep(step: String) {
        _uiState.update { it.copy(loadingSteps = it.loadingSteps - step) }
    }

    private companion object {
        const val TAG = "ChatViewModel"
        private const val INITIAL_HISTORY_LIMIT = 100
        private const val HISTORY_PAGE_SIZE = 100
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val KEY_DRAFT_TEXT = "chat_draft_text"
        private const val KEY_ATTACHED_FILES = "chat_attached_files"
        private const val COMMAND_CATALOG_REFRESH_DEBOUNCE_MS = 150L
        private val RESPONSE_RECONCILIATION_DELAYS_MS = listOf(2_000L, 5_000L, 10_000L, 20_000L)
        private const val RUN_STALLED_NOTICE = "No completion update was received. " +
            "The run may still be active; stop it before retrying."

        // SavedState shares Android's Binder transaction budget with the rest of the Activity.
        private const val MAX_PERSISTED_DRAFT_CHARS = 64 * 1024
        private const val MAX_PERSISTED_ATTACHMENTS_JSON_CHARS = 64 * 1024
        private const val UNAVAILABLE_ATTACHMENTS_ERROR =
            "Remove unavailable attachments before sending."

        /**
         * Built-in OpenCode commands that aren't returned by the /command API endpoint.
         * Localized descriptions are resolved at Compose display boundaries.
         */
        private val BUILTIN_COMMANDS = listOf(
            Command(name = "compact", source = CommandSource.BuiltIn),
            Command(name = "clear", source = CommandSource.BuiltIn),
            Command(name = "new", source = CommandSource.BuiltIn),
            Command(name = "undo", source = CommandSource.BuiltIn),
            Command(name = "redo", source = CommandSource.BuiltIn),
            Command(name = "share", source = CommandSource.BuiltIn),
            Command(name = "init", source = CommandSource.BuiltIn),
            Command(name = "help", source = CommandSource.BuiltIn),
            Command(name = "connect", source = CommandSource.BuiltIn),
            Command(name = "bug", source = CommandSource.BuiltIn),
        )
    }

    private fun restoredInputText(): String = savedStateHandle.get<String>(KEY_DRAFT_TEXT).orEmpty()

    private fun restoredAttachedFiles(): List<SelectedFile> {
        val jsonString = savedStateHandle.get<String>(KEY_ATTACHED_FILES) ?: return emptyList()
        return try {
            json.decodeFromString<List<SelectedFile>>(jsonString)
        } catch (e: SerializationException) {
            AppLog.e(TAG, "Failed to restore attached files")
            savedStateHandle.remove<String>(KEY_ATTACHED_FILES)
            emptyList()
        } catch (e: IllegalArgumentException) {
            AppLog.e(TAG, "Failed to restore attached files")
            savedStateHandle.remove<String>(KEY_ATTACHED_FILES)
            emptyList()
        }
    }

    private fun persistInputText(text: String) {
        if (text.isEmpty() || text.length > MAX_PERSISTED_DRAFT_CHARS) {
            savedStateHandle.remove<String>(KEY_DRAFT_TEXT)
        } else {
            savedStateHandle[KEY_DRAFT_TEXT] = text
        }
    }

    private fun persistAttachedFiles(files: List<SelectedFile>) {
        if (files.isEmpty()) {
            savedStateHandle.remove<String>(KEY_ATTACHED_FILES)
        } else {
            val encoded = json.encodeToString(files)
            if (encoded.length <= MAX_PERSISTED_ATTACHMENTS_JSON_CHARS) {
                savedStateHandle[KEY_ATTACHED_FILES] = encoded
            } else {
                savedStateHandle.remove<String>(KEY_ATTACHED_FILES)
            }
        }
    }

    private fun observeComposerAttachments() {
        viewModelScope.launch {
            filePickerManager.attachedFiles.collect(::persistAttachedFiles)
        }
    }

    init {
        val restoredFiles = restoredAttachedFiles()
        if (restoredFiles.isNotEmpty()) filePickerManager.restoreAttachedFiles(restoredFiles)
        if (restoredFiles.isNotEmpty()) validateRestoredAttachments()
        observeComposerAttachments()
        loadSession()
        loadMessages()
        modelAgentManager.loadAgents()
        modelAgentManager.loadModels()
        observeEvents()
        loadVcsInfo()
    }

    private fun validateRestoredAttachments() {
        viewModelScope.launch {
            filePickerManager.validateAttachedFiles()
        }
    }

    // --- Public API (delegating) ---

    fun markAsRead() {
        _isActiveTab.value = true
        _hasUnreadResponse.value = false
    }

    fun markInactive() {
        _isActiveTab.value = false
    }

    fun updateInput(text: String) {
        persistInputText(text)
        _uiState.update { it.copy(inputText = text) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    // --- Session lifecycle ---

    private fun loadSession() {
        viewModelScope.launch {
            beginLoadStep("Loading session metadata")
            val result = safeApiCall { workspaceClient.getSession(sessionId) }
            endLoadStep("Loading session metadata")
            when (result) {
                is ApiResult.Success -> {
                    val session = SessionMapper.mapToDomain(result.data)
                    _uiState.update { it.copy(session = session) }
                    // Reload VCS now that we have the canonical session directory
                    loadVcsInfo()
                }
                is ApiResult.Error -> {
                    if (result.code == 404) {
                        _sessionMissing.emit(Unit)
                    } else {
                        _uiState.update { it.copy(error = "Failed to load session") }
                    }
                }
            }
        }
    }

    private fun loadMessages() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            beginLoadStep("Loading session messages")
            AppLog.d(TAG, "loadMessages() called")

            val result = safeApiCall {
                sessionRepository.loadMessages(SessionId(sessionId), limit = INITIAL_HISTORY_LIMIT)
            }
            endLoadStep("Loading session messages")

            when (result) {
                is ApiResult.Success -> {
                    AppLog.d(TAG, "Loaded ${messages.value.size} messages")
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            historyLimit = INITIAL_HISTORY_LIMIT,
                            hasOlderMessages = result.data >= INITIAL_HISTORY_LIMIT,
                        )
                    }
                }
                is ApiResult.Error -> {
                    AppLog.e(TAG, "Failed to load messages")
                    if (result.code == 404) {
                        _sessionMissing.emit(Unit)
                    } else {
                        _uiState.update {
                            it.copy(isLoading = false, error = "Failed to load messages")
                        }
                    }
                }
            }
        }
    }

    fun loadOlderMessages() {
        val current = _uiState.value
        if (current.isLoading || current.isLoadingOlderMessages || !current.hasOlderMessages) return

        val nextLimit = current.historyLimit + HISTORY_PAGE_SIZE
        _uiState.update { it.copy(isLoadingOlderMessages = true) }
        viewModelScope.launch {
            when (
                val result = safeApiCall {
                    sessionRepository.loadMessages(SessionId(sessionId), limit = nextLimit)
                }
            ) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(
                        isLoadingOlderMessages = false,
                        historyLimit = nextLimit,
                        hasOlderMessages = result.data >= nextLimit,
                    )
                }
                is ApiResult.Error -> {
                    AppLog.e(TAG, "Failed to load older messages")
                    _uiState.update {
                        it.copy(
                            isLoadingOlderMessages = false,
                            error = "Failed to load older messages",
                        )
                    }
                }
            }
        }
    }

    private fun loadVcsInfo() {
        viewModelScope.launch {
            beginLoadStep("Loading workspace status")
            when (val result = safeApiCall { workspaceClient.getVcsInfo() }) {
                is ApiResult.Success -> _branchName.value = result.data.branch
                is ApiResult.Error -> AppLog.w(TAG, "Failed to load VCS info")
            }
            endLoadStep("Loading workspace status")
        }
    }

    // --- Repository-owned session event state ---

    private fun observeEvents() {
        viewModelScope.launch {
            repositorySessionState.collect { state -> applyRepositorySessionState(state) }
        }
    }

    private var lastResponseCompletedToken = 0L
    private var hasResponseTokenBaseline = false
    private var responseReconciliationJob: Job? = null

    // True from the moment a send clears the previous run's UI error until the run is confirmed
    // active (Busy/Retry) or reaches a genuine terminal boundary. While set, repository emissions
    // that still carry the previous run's error (todos, permissions, session updates arriving
    // before the synthetic Busy clears it) must not flicker that stale error back into the UI.
    private var suppressStaleRunErrors = false

    private fun applyRepositorySessionState(state: dev.blazelight.p4oc.data.session.SessionUiState) {
        dialogManager.setPermissionsByCallId(state.pendingPermissionsByCallId)
        dialogManager.setPendingQuestion(state.pendingQuestion)

        val isBusy = state.status is SessionStatus.Busy || state.status is SessionStatus.Retry
        val isTerminalTransition = hasResponseTokenBaseline &&
            state.responseCompletedToken > lastResponseCompletedToken
        if (!hasResponseTokenBaseline) {
            // The first collected repository state is the subscription snapshot, not a fresh
            // completion: adopt its token as the baseline so a token accumulated before this
            // ViewModel attached (e.g. a run completed in another tab holding the same session)
            // can never fire a spurious completion haptic/unread badge or act as a false
            // terminal boundary for notices and the bounded poll.
            hasResponseTokenBaseline = true
            lastResponseCompletedToken = state.responseCompletedToken
        }
        if (isBusy || isTerminalTransition) {
            // The run is confirmed active (its Busy transition already cleared the previous run's
            // repository error) or has genuinely completed (a fresh terminal error is real, not
            // stale). Either way, stale-error suppression for the in-flight send ends now, before
            // the error below is computed.
            suppressStaleRunErrors = false
        }
        val errorMessage = state.error?.takeUnless { it.isAborted() }?.toHumanMessage()
        val retryNotice = (state.status as? SessionStatus.Retry)?.toHumanMessage()
        _uiState.update {
            it.copy(
                session = state.session ?: it.session,
                isBusy = isBusy,
                isSending = if (state.status != null) false else it.isSending,
                todos = state.todos,
                error = if (suppressStaleRunErrors) it.error else errorMessage ?: it.error,
                runNotice = resolveRunNotice(it.runNotice, retryNotice, isTerminalTransition, isBusy),
            )
        }

        if (isTerminalTransition) {
            responseReconciliationJob?.cancel()
            responseReconciliationJob = null
            lastResponseCompletedToken = state.responseCompletedToken
            if (state.error?.isAborted() != true) {
                _hasUnreadResponse.value = !_isActiveTab.value
                handleResponseCompleted()
            }
        }
    }

    private fun handleResponseCompleted() {
        val settings = notificationSettings.value
        hapticFeedback.vibrate(settings.vibrationPattern)
    }

    private fun resolveRunNotice(
        current: String?,
        retryNotice: String?,
        isTerminalTransition: Boolean,
        isBusy: Boolean,
    ): String? = when {
        // A terminal boundary (completion, stop, or terminal error) retires any notice.
        isTerminalTransition -> null
        retryNotice != null -> retryNotice
        // The bounded poll's stalled-run warning stays visible across unrelated repository
        // emissions (todos, permissions, session updates) while the run remains busy; only
        // send/stop/terminal boundaries clear it. A non-busy emission also retires it: the
        // warning ("the run may still be active") is meaningless once the run is not running.
        isBusy && current == RUN_STALLED_NOTICE -> current
        else -> null
    }

    // --- Message sending ---

    fun sendMessage() {
        val text = _uiState.value.inputText.trim()
        val attachedFiles = filePickerManager.attachedFiles.value
        if (text.isEmpty() && attachedFiles.isEmpty()) return
        if (attachedFiles.isEmpty() && text.startsWith("/")) {
            sendSlashCommand(text)
            return
        }

        // A replacement send must supersede any in-flight reconciliation from a prior send so the
        // old poll can never reconcile the wrong assistant/status against the new run.
        responseReconciliationJob?.cancel()
        responseReconciliationJob = null
        suppressStaleRunErrors = true
        _uiState.update { it.copy(isSending = true, error = null, runNotice = null) }
        viewModelScope.launch {
            val validatedFiles = filePickerManager.validateAttachedFiles()
            if (validatedFiles.any { !it.available }) {
                suppressStaleRunErrors = false
                _uiState.update { it.copy(error = UNAVAILABLE_ATTACHMENTS_ERROR, isSending = false) }
                return@launch
            }

            sendValidatedMessage(text, validatedFiles)
        }
    }

    private suspend fun sendValidatedMessage(text: String, attachedFiles: List<SelectedFile>) {
        val knownMessageIds = messages.value.mapTo(mutableSetOf()) { it.message.id }
        val selectedAgent = modelAgentManager.selectedAgent.value
        val selectedModel = modelAgentManager.selectedModel.value
        val selectedVariant = modelAgentManager.currentReasoningEffort()
        updateInput("")
        filePickerManager.clearAttachedFiles()

        val parts = buildPartInputs(text, attachedFiles)
        val request = SendMessageRequest(
            parts = parts,
            agent = selectedAgent,
            model = selectedModel,
            variant = selectedVariant
        )

        val result = sessionRepository.sendMessageAsync(SessionId(sessionId), request).await().toApiResult()
        when (result) {
            is ApiResult.Success -> {
                sessionRepository.acceptEvent(
                    OpenCodeEvent.SessionStatusChanged(sessionId, SessionStatus.Busy)
                )
                _uiState.update { it.copy(isSending = false, isBusy = true, runNotice = null) }
                startResponseReconciliation(knownMessageIds)
                AppLog.d(TAG, "sendMessage: Async call succeeded, waiting for SSE events")
            }
            is ApiResult.Error -> {
                suppressStaleRunErrors = false
                _uiState.update {
                    it.copy(
                        isSending = false,
                        error = "Could not send the message. Check the connection and try again."
                    )
                }
                updateInput(text)
                filePickerManager.restoreAttachedFiles(attachedFiles)
            }
        }
    }

    private fun startResponseReconciliation(knownMessageIds: Set<String>) {
        responseReconciliationJob?.cancel()
        responseReconciliationJob = viewModelScope.launch {
            var lastNewAssistant: MessageWithParts? = null
            var observedRetry = false

            RESPONSE_RECONCILIATION_DELAYS_MS.forEach { delayMs ->
                delay(delayMs)
                if (!_uiState.value.isBusy) return@launch

                val status = runCatching {
                    workspaceClient.getSessionStatuses(workspaceClient.workspace.directory)[sessionId]
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                }.getOrNull()?.let(SessionMapper::mapStatusToDomain)
                observedRetry = observedRetry || status is SessionStatus.Retry

                // Reconcile canonical messages BEFORE publishing any status. A terminal Idle
                // published first bumps responseCompletedToken, whose collector cancels this very
                // job while the recovery fetch is still in flight — the run then ends with the
                // completed assistant reachable only via REST and no user-facing explanation.
                // The repository's canonical active-lease, revision-safe recovery primitive is
                // reused rather than a second message buffer or an unsafe overwrite path.
                runCatching {
                    sessionRepository.reconcileMessages(SessionId(sessionId))
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                }
                lastNewAssistant = messages.value.asReversed().firstOrNull { messageWithParts ->
                    messageWithParts.message.id !in knownMessageIds &&
                        messageWithParts.message is Message.Assistant
                } ?: lastNewAssistant

                if (reconcileCompletedAssistant(lastNewAssistant, status)) return@launch

                // No assistant completed yet: only non-terminal statuses may be published. A REST
                // Idle with no new assistant must not terminate the run (or cancel this poll);
                // keep polling until an assistant appears or the bounded window is exhausted.
                if (status is SessionStatus.Busy || status is SessionStatus.Retry) {
                    sessionRepository.acceptEvent(OpenCodeEvent.SessionStatusChanged(sessionId, status))
                }
            }

            if (!_uiState.value.isBusy) return@launch
            if (!observedRetry) {
                _uiState.update { it.copy(runNotice = RUN_STALLED_NOTICE) }
            }
        }
    }

    private fun reconcileCompletedAssistant(
        messageWithParts: MessageWithParts?,
        status: SessionStatus?,
    ): Boolean {
        val assistant = messageWithParts?.message as? Message.Assistant
        return when {
            assistant == null -> false
            // An authoritative Busy/Retry just fetched from REST means nothing terminal was
            // missed: multi-step runs emit one assistant message per step, so a completed
            // intermediate assistant mid-run must never synthesize a terminal Idle/Error (false
            // completion haptic, unread badge, poll cancellation). The caller republishes the
            // non-terminal status and keeps polling.
            status is SessionStatus.Busy || status is SessionStatus.Retry -> false
            assistant.error != null -> {
                sessionRepository.acceptEvent(OpenCodeEvent.SessionError(sessionId, assistant.error))
                true
            }
            assistant.completedAt == null && status !is SessionStatus.Idle -> false
            messageWithParts.parts.isEmpty() -> {
                sessionRepository.acceptEvent(
                    OpenCodeEvent.SessionError(
                        sessionId,
                        MessageError(
                            name = "EmptyResponseError",
                            message = "The model completed without returning content",
                        ),
                    )
                )
                true
            }
            else -> {
                sessionRepository.acceptEvent(
                    OpenCodeEvent.SessionStatusChanged(sessionId, SessionStatus.Idle)
                )
                true
            }
        }
    }

    private fun sendSlashCommand(text: String) {
        val commandText = text.removePrefix("/")
        val commandName = commandText.substringBefore(" ").trim()
        if (commandName.isEmpty()) return
        val arguments = commandText.substringAfter(" ", "").trim()
        updateInput("")
        executeCommand(commandName, arguments)
    }

    private fun buildPartInputs(text: String, files: List<SelectedFile>): List<PartInputDto> {
        val parts = mutableListOf<PartInputDto>()
        if (text.isNotEmpty()) {
            parts.add(PartInputDto(type = "text", text = text))
        }
        files.forEach { file ->
            parts.add(
                PartInputDto(
                    type = "file",
                    filename = file.name,
                    mime = file.mimeType ?: FilenameMimeType.resolveOrOctetStream(file.name),
                    url = file.toOpenCodeFileUrl()
                )
            )
        }
        return parts
    }

    private fun SelectedFile.toOpenCodeFileUrl(): String {
        val workspaceDirectory = workspaceClient.workspace.directory
            ?: throw IllegalStateException("Cannot attach workspace file without a workspace directory")
        val absolutePath = File(workspaceDirectory, path).normalize().path
        return URI("file", null, absolutePath, null).toASCIIString()
    }

    // --- Permission / question responses ---

    fun respondToPermission(permissionId: String, response: String) {
        viewModelScope.launch {
            val request = PermissionResponseRequest(reply = response)
            when (val result = safeApiCall { workspaceClient.respondToPermission(sessionId, permissionId, request) }) {
                is ApiResult.Success -> {
                    dialogManager.clearPermission(permissionId)
                    sessionRepository.clearPermission(SessionId(sessionId), permissionId)
                }
                is ApiResult.Error -> _uiState.update {
                    if (result.message.contains("bad request", ignoreCase = true)) {
                        dialogManager.clearPermission(permissionId)
                        sessionRepository.clearPermission(SessionId(sessionId), permissionId)
                        it
                    } else {
                        it.copy(error = "Could not respond to the permission request. Try again.")
                    }
                }
            }
        }
    }

    fun respondToQuestion(requestId: String, answers: List<List<String>>) {
        viewModelScope.launch {
            val request = QuestionReplyRequest(answers = answers)
            when (val result = safeApiCall { workspaceClient.respondToQuestion(sessionId, requestId, request) }) {
                is ApiResult.Success -> sessionRepository.clearQuestion(SessionId(sessionId), requestId)
                is ApiResult.Error -> _uiState.update {
                    it.copy(
                        error = "Could not answer the question. Try again."
                    )
                }
            }
        }
    }

    fun dismissQuestion(requestId: String) {
        viewModelScope.launch {
            // Reject the question server-side so the agent's pending request is
            // resolved (otherwise it stays pending forever and the session never
            // goes idle). The local modal is cleared optimistically; the matching
            // question.rejected SSE event (handled in SessionRepositoryImpl) will
            // also reconcile any other attached client.
            when (val result = safeApiCall { workspaceClient.rejectQuestion(sessionId, requestId) }) {
                is ApiResult.Success -> sessionRepository.clearQuestion(SessionId(sessionId), requestId)
                is ApiResult.Error -> {
                    // A NotFound here means it was already resolved elsewhere — clear
                    // locally anyway so the user is not stuck on a dead modal.
                    sessionRepository.clearQuestion(SessionId(sessionId), requestId)
                    AppLog.w(TAG, "Question rejection failed; clearing resolved prompt locally")
                }
            }
        }
    }

    // --- Commands & Todos ---

    fun loadCommands() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoadingCommands = true,
                    commandLoadError = null,
                    commands = it.commands.ifEmpty { BUILTIN_COMMANDS }
                )
            }
            beginLoadStep("Loading slash commands")
            val result = safeApiCall { workspaceClient.listCommands() }
            endLoadStep("Loading slash commands")
            when (result) {
                is ApiResult.Success -> {
                    AppLog.d(TAG, "loadCommands: Got ${result.data.size} commands from API")
                    val apiCommands = result.data.map { CommandMapper.mapToDomain(it) }
                    val allCommands = (BUILTIN_COMMANDS + apiCommands).distinctBy { it.name }
                    _uiState.update {
                        it.copy(
                            commands = allCommands,
                            isLoadingCommands = false,
                            hasLoadedWorkspaceCommands = true,
                            commandLoadError = null
                        )
                    }
                }
                is ApiResult.Error -> {
                    AppLog.e(TAG, "loadCommands failed")
                    _uiState.update {
                        it.copy(
                            commands = it.commands.ifEmpty { BUILTIN_COMMANDS },
                            isLoadingCommands = false,
                            hasLoadedWorkspaceCommands = false,
                            commandLoadError = "Could not load workspace commands. Try again."
                        )
                    }
                }
            }
        }
    }

    private fun refreshCommandsInBackground() {
        viewModelScope.launch {
            when (val result = safeApiCall { workspaceClient.listCommands() }) {
                is ApiResult.Success -> {
                    val apiCommands = result.data.map(CommandMapper::mapToDomain)
                    _uiState.update {
                        it.copy(
                            commands = (BUILTIN_COMMANDS + apiCommands).distinctBy(Command::name),
                            hasLoadedWorkspaceCommands = true,
                        )
                    }
                }
                is ApiResult.Error -> AppLog.d(TAG, "Background command refresh failed")
            }
        }
    }

    fun refreshCommandsIfNeeded(force: Boolean = false) {
        val state = _uiState.value
        if (state.isLoadingCommands) return
        if (force || !state.hasLoadedWorkspaceCommands) {
            loadCommands()
        }
    }

    fun executeCommand(commandName: String, arguments: String) {
        when (commandName.trim().lowercase()) {
            "undo" -> undoSessionCommand()
            "redo" -> redoSessionCommand()
            else -> executeServerCommand(commandName, arguments)
        }
    }

    private fun executeServerCommand(commandName: String, arguments: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSending = true) }
            val request = ExecuteCommandRequest(
                command = commandName,
                arguments = arguments
            )
            val result = safeApiCall { workspaceClient.executeCommand(sessionId, request) }
            when (result) {
                is ApiResult.Success -> {
                    _uiState.update { it.copy(isSending = false, isBusy = true) }
                }
                is ApiResult.Error -> {
                    _uiState.update {
                        it.copy(isSending = false, error = "Could not execute the command. Try again.")
                    }
                }
            }
        }
    }

    fun loadTodos() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingTodos = true) }
            beginLoadStep("Loading todos")
            val result = safeApiCall { workspaceClient.getSessionTodos(sessionId) }
            endLoadStep("Loading todos")
            when (result) {
                is ApiResult.Success -> {
                    val todos = result.data.map { TodoMapper.mapToDomain(it) }
                    _uiState.update { it.copy(todos = todos, isLoadingTodos = false) }
                }
                is ApiResult.Error -> {
                    _uiState.update { it.copy(isLoadingTodos = false) }
                }
            }
        }
    }

    // --- Revert / Unrevert ---

    private fun undoSessionCommand() {
        val targetMessageId = previousUserMessageBoundary()
        if (targetMessageId == null) {
            _uiState.update { it.copy(error = "Nothing to undo") }
            return
        }
        revertSessionTo(targetMessageId, "undo")
    }

    private fun redoSessionCommand() {
        val targetMessageId = nextUserMessageBoundary()
        if (targetMessageId == null) {
            _uiState.update { it.copy(error = "Nothing to redo") }
            return
        }
        revertSessionTo(targetMessageId, "redo")
    }

    private fun previousUserMessageBoundary(): String? {
        val userMessages = orderedUserMessages()
        val activeRevertIndex = activeRevertIndex(userMessages) ?: userMessages.size
        return userMessages.getOrNull(activeRevertIndex - 1)?.id
    }

    private fun nextUserMessageBoundary(): String? {
        val userMessages = orderedUserMessages()
        val activeRevertIndex = activeRevertIndex(userMessages) ?: return null
        return userMessages.getOrNull(activeRevertIndex + 1)?.id
    }

    private fun orderedUserMessages(): List<Message.User> = messages.value
        .mapNotNull { it.message as? Message.User }
        .sortedBy { it.createdAt }

    private fun activeRevertIndex(userMessages: List<Message.User>): Int? {
        val activeRevertMessageId = _uiState.value.session?.revert?.messageID ?: return null
        return userMessages.indexOfFirst { it.id == activeRevertMessageId }.takeIf { it >= 0 }
    }

    private fun revertSessionTo(messageId: String, action: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSending = true) }
            val request = RevertSessionRequest(messageID = messageId)
            val result = safeApiCall { workspaceClient.revertSession(sessionId, request) }
            when (result) {
                is ApiResult.Success -> {
                    _uiState.update { it.copy(isSending = false) }
                    loadSession()
                }
                is ApiResult.Error -> {
                    _uiState.update { it.copy(isSending = false, error = "Could not $action. Try again.") }
                }
            }
        }
    }

    fun revertMessage(messageId: String) {
        viewModelScope.launch {
            val request = RevertSessionRequest(messageID = messageId)
            val result = safeApiCall { workspaceClient.revertSession(sessionId, request) }
            when (result) {
                is ApiResult.Success -> {
                    loadSession() // Refresh to get updated revert state
                }
                is ApiResult.Error -> {
                    _uiState.update { it.copy(error = "Could not revert the session. Try again.") }
                }
            }
        }
    }

    fun unrevertSession() {
        viewModelScope.launch {
            val result = safeApiCall { workspaceClient.unrevertSession(sessionId) }
            when (result) {
                is ApiResult.Success -> {
                    loadSession() // Refresh to clear revert state
                }
                is ApiResult.Error -> {
                    _uiState.update { it.copy(error = "Could not restore the session. Try again.") }
                }
            }
        }
    }

    // --- Abort ---

    fun abortSession() {
        viewModelScope.launch {
            when (val result = sessionRepository.abortSession(SessionId(sessionId)).await().toApiResult()) {
                is ApiResult.Success -> {
                    responseReconciliationJob?.cancel()
                    responseReconciliationJob = null
                    sessionRepository.clearStreamingFlags(SessionId(sessionId))
                    _uiState.update { it.copy(isBusy = false, isSending = false, runNotice = null) }
                }
                is ApiResult.Error -> _uiState.update {
                    it.copy(error = "Could not stop the run. Try again.")
                }
            }
        }
    }

    override fun onCleared() {
        responseReconciliationJob?.cancel()
        sessionLease.close()
        super.onCleared()
    }

    private fun dev.blazelight.p4oc.domain.model.MessageError.toHumanMessage(): String = when {
        name == "ProviderAuthError" -> "Provider authentication required"
        isUsageLimit() -> "Model usage limit reached. Try again later or choose another model."
        name == "EmptyResponseError" ->
            "The model returned no response. The provider may be unavailable or rate-limited."
        isRetryable -> "The request failed temporarily. Try again."
        else -> "The run failed. Try again."
    }

    private fun dev.blazelight.p4oc.domain.model.MessageError.isUsageLimit(): Boolean =
        statusCode == HTTP_TOO_MANY_REQUESTS ||
            message.containsUsageLimitMarker() ||
            responseBody.containsUsageLimitMarker()

    private fun SessionStatus.Retry.toHumanMessage(): String =
        if (message.containsUsageLimitMarker()) {
            "Model usage limit reached. OpenCode is retrying${attemptLabel()}."
        } else {
            "The model request failed temporarily. OpenCode is retrying${attemptLabel()}."
        }

    private fun SessionStatus.Retry.attemptLabel(): String =
        if (attempt > 0) " (attempt $attempt)" else ""

    private fun String?.containsUsageLimitMarker(): Boolean {
        val normalized = this?.lowercase().orEmpty()
        return "rate limit" in normalized || "rate-limit" in normalized ||
            "too many requests" in normalized || "status 429" in normalized ||
            "free usage exceeded" in normalized || "free limit" in normalized
    }

    private fun <T> Result<T>.toApiResult(): ApiResult<T> = fold(
        onSuccess = { ApiResult.Success(it) },
        onFailure = { ApiResult.Error(message = it.message ?: "Unknown error", throwable = it) }
    )
}

/**
 * Core UI state — only session lifecycle, sending state, commands, and todos.
 * Model/agent, file picker, and dialog state are exposed via sub-manager StateFlows.
 */
data class ChatUiState(
    val session: Session? = null,
    val inputText: String = "",
    val isLoading: Boolean = false,
    val isLoadingOlderMessages: Boolean = false,
    val hasOlderMessages: Boolean = false,
    val historyLimit: Int = 0,
    val loadingSteps: Set<String> = emptySet(),
    val isSending: Boolean = false,
    val isBusy: Boolean = false,
    val error: String? = null,
    val runNotice: String? = null,
    val commands: List<Command> = emptyList(),
    val isLoadingCommands: Boolean = false,
    val hasLoadedWorkspaceCommands: Boolean = false,
    val commandLoadError: String? = null,
    val todos: List<Todo> = emptyList(),
    val isLoadingTodos: Boolean = false
)
