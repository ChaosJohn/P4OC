package dev.blazelight.p4oc.ui.screens.chat

import dev.blazelight.p4oc.core.datastore.SettingsDataStore
import dev.blazelight.p4oc.core.log.AppLog
import dev.blazelight.p4oc.core.network.ApiResult
import dev.blazelight.p4oc.core.network.ServerConnectionRegistry
import dev.blazelight.p4oc.core.network.safeApiCall
import dev.blazelight.p4oc.data.remote.dto.AgentDto
import dev.blazelight.p4oc.data.remote.dto.ModelDto
import dev.blazelight.p4oc.data.remote.dto.ModelInput
import dev.blazelight.p4oc.data.remote.dto.reasoningEfforts
import dev.blazelight.p4oc.data.workspace.WorkspaceClient
import dev.blazelight.p4oc.domain.model.OpenCodeEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Manages model/agent loading, selection, favorites, and recents.
 */
class ModelAgentManager(
    private val workspaceClient: WorkspaceClient,
    private val settingsDataStore: SettingsDataStore,
    private val scope: CoroutineScope,
    private val sessionId: String? = null,
    modelSelectionCoordinator: ModelSelectionCoordinator? = null,
    serverConnectionRegistry: ServerConnectionRegistry? = null,
) {
    private val _availableAgents = MutableStateFlow<List<AgentDto>>(emptyList())
    val availableAgents: StateFlow<List<AgentDto>> = _availableAgents.asStateFlow()

    private val _selectedAgent = MutableStateFlow<String?>(null)
    val selectedAgent: StateFlow<String?> = _selectedAgent.asStateFlow()

    private val _availableModels = MutableStateFlow<List<Pair<String, ModelDto>>>(emptyList())
    val availableModels: StateFlow<List<Pair<String, ModelDto>>> = _availableModels.asStateFlow()

    /** Provider id -> the provider's own display name ("llmproxy" -> "LLM Proxy"). */
    private val _providerNames = MutableStateFlow<Map<String, String>>(emptyMap())
    val providerNames: StateFlow<Map<String, String>> = _providerNames.asStateFlow()

    private val _selectedModel = MutableStateFlow<ModelInput?>(null)
    val selectedModel: StateFlow<ModelInput?> = _selectedModel.asStateFlow()

    private val _selectedReasoningEffort = MutableStateFlow<String?>(null)
    val selectedReasoningEffort: StateFlow<String?> = _selectedReasoningEffort.asStateFlow()

    private var selectedModelFromAgent = false
    private var selectedModelExplicitly = false

    val favoriteModels: StateFlow<Set<ModelInput>> = settingsDataStore.favoriteModels
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    val recentModels: StateFlow<List<ModelInput>> = settingsDataStore.recentModels
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        modelSelectionCoordinator?.let { coordinator ->
            scope.launch {
                coordinator.activeModelChanges.collect { model ->
                    reconcileActiveModel(model)
                }
            }
        }
        serverConnectionRegistry?.let(::observeCatalogEvents)
    }

    @OptIn(FlowPreview::class)
    private fun observeCatalogEvents(registry: ServerConnectionRegistry) {
        scope.launch {
            registry.events(workspaceClient.workspace.server)
                .filter { scopedEvent ->
                    val event = scopedEvent.event
                    val refreshesCatalog = event is OpenCodeEvent.ModelsRefreshed ||
                        event is OpenCodeEvent.CatalogUpdated ||
                        event is OpenCodeEvent.McpToolsChanged
                    scopedEvent.generation == workspaceClient.generation &&
                        scopedEvent.workspaceKey == workspaceClient.workspace.key &&
                        refreshesCatalog
                }
                .debounce(EVENT_REFRESH_DEBOUNCE_MS)
                .collect { event ->
                    if (event.event !is OpenCodeEvent.McpToolsChanged) loadModels()
                    loadAgents()
                }
        }
    }

    fun loadAgents() {
        scope.launch {
            val result = safeApiCall { workspaceClient.getAgents() }
            when (result) {
                is ApiResult.Success -> {
                    AppLog.d(TAG, "loadAgents: Got ${result.data.size} agents")
                    val primaryAgents = result.data.filter {
                        it.mode in PRIMARY_COMPOSER_MODES && it.hidden != true
                    }
                    AppLog.d(TAG, "loadAgents: ${primaryAgents.size} primary agents")
                    _availableAgents.value = primaryAgents
                    val persistedAgent = sessionId?.let { settingsDataStore.getSelectedAgentForSession(it) }
                    val selectedAgent = persistedAgent?.let { agentName ->
                        primaryAgents.find { it.name == agentName }
                    } ?: primaryAgents.firstOrNull()
                    selectedAgent?.name?.let { selectAgent(it, persist = false) }
                }
                is ApiResult.Error -> {
                    AppLog.e(TAG, "loadAgents failed")
                }
            }
        }
    }

    fun selectAgent(agentName: String) {
        selectAgent(agentName, persist = true)
    }

    private fun selectAgent(agentName: String, persist: Boolean) {
        _selectedAgent.value = agentName
        if (persist) {
            sessionId?.let { currentSessionId ->
                scope.launch {
                    settingsDataStore.setSelectedAgentForSession(currentSessionId, agentName)
                }
            }
        }
        val agentModel = _availableAgents.value.find { it.name == agentName }?.model ?: return
        _selectedModel.value = ModelInput(
            providerID = agentModel.providerID,
            modelID = agentModel.modelID
        )
        selectedModelFromAgent = true
        selectedModelExplicitly = false
    }

    fun loadModels() {
        scope.launch {
            val result = safeApiCall { workspaceClient.getProviders() }
            when (result) {
                is ApiResult.Success -> {
                    val models = mutableListOf<Pair<String, ModelDto>>()
                    val names = mutableMapOf<String, String>()
                    result.data.connected.forEach { providerId ->
                        val provider = result.data.all.find { it.id == providerId }
                        provider?.name?.takeIf { it.isNotBlank() }?.let { names[providerId] = it }
                        provider?.models?.values?.forEach { model ->
                            models.add(providerId to model)
                        }
                    }
                    val defaultModel = result.data.default.entries
                        .map { (provider, modelId) -> ModelInput(providerID = provider, modelID = modelId) }
                        .firstOrNull { candidate ->
                            models.any { (providerId, model) ->
                                providerId == candidate.providerID && model.id == candidate.modelID
                            }
                        }
                    val lastUsedModel = recentModels.value.firstOrNull { candidate ->
                        models.any { (providerId, model) ->
                            providerId == candidate.providerID && model.id == candidate.modelID
                        }
                    }
                    val fallbackModel = defaultModel ?: lastUsedModel
                    val currentSelectionIsAvailable = _selectedModel.value?.let { selected ->
                        models.any { (providerId, model) ->
                            providerId == selected.providerID && model.id == selected.modelID
                        }
                    } == true
                    _availableModels.value = models
                    _providerNames.value = names
                    if (!selectedModelFromAgent && (!selectedModelExplicitly || !currentSelectionIsAvailable)) {
                        _selectedModel.value = fallbackModel
                        selectedModelExplicitly = false
                    }
                }
                is ApiResult.Error -> {}
            }
        }
    }

    fun selectModel(model: ModelInput) {
        if (_selectedModel.value != model) {
            _selectedReasoningEffort.value = null
        }
        _selectedModel.value = model
        selectedModelFromAgent = false
        selectedModelExplicitly = true
        scope.launch {
            settingsDataStore.addRecentModel(model)
        }
    }

    fun selectReasoningEffort(reasoningEffort: String?) {
        _selectedReasoningEffort.value = reasoningEffort
    }

    fun currentReasoningEffort(): String? {
        val model = _selectedModel.value
        val effort = _selectedReasoningEffort.value
        val modelDto = model?.let { selected ->
            _availableModels.value.find { (providerId, dto) ->
                providerId == selected.providerID && dto.id == selected.modelID
            }?.second
        }
        return effort?.takeIf { it in modelDto?.reasoningEfforts().orEmpty() }
    }

    fun toggleFavoriteModel(model: ModelInput) {
        scope.launch {
            settingsDataStore.toggleFavoriteModel(model)
        }
    }

    private fun reconcileActiveModel(model: ModelInput) {
        if (selectedModelFromAgent || selectedModelExplicitly) return
        if (_selectedModel.value != model) {
            _selectedReasoningEffort.value = null
        }
        _selectedModel.value = model
    }

    private companion object {
        const val TAG = "ModelAgentManager"
        val PRIMARY_COMPOSER_MODES = setOf("primary", "all")
        const val EVENT_REFRESH_DEBOUNCE_MS = 150L
    }
}
