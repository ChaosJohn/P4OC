package dev.blazelight.p4oc.ui.screens.server

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.blazelight.p4oc.R
import dev.blazelight.p4oc.core.datastore.SavedServer
import dev.blazelight.p4oc.core.network.DiscoveredServer
import dev.blazelight.p4oc.core.network.DiscoveryState
import dev.blazelight.p4oc.core.network.ServerConnectionRegistry
import dev.blazelight.p4oc.core.network.toServerRef
import dev.blazelight.p4oc.ui.components.TuiConfirmDialog
import dev.blazelight.p4oc.ui.components.TuiLoadingIndicator
import dev.blazelight.p4oc.ui.components.status.serverStatusIndicator
import dev.blazelight.p4oc.ui.tabs.TabManager
import dev.blazelight.p4oc.ui.theme.LocalOpenCodeTheme
import dev.blazelight.p4oc.ui.theme.Sizing
import dev.blazelight.p4oc.ui.theme.Spacing
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongMethod")
@Composable
fun serverScreen(
    onNavigateToSessions: () -> Unit,
    onNavigateToProjects: () -> Unit,
    onSettings: () -> Unit,
    autoReconnect: Boolean = true,
    onConnectSavedServer: ((SavedServer) -> Unit)? = null,
) {
    val viewModel: ServerViewModel = koinViewModel()
    val theme = LocalOpenCodeTheme.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val serverConnectionRegistry: ServerConnectionRegistry = koinInject()
    val registryStates = uiState.savedServers.associate { saved ->
        val state by serverConnectionRegistry.connectionState(saved.toServerRef()).collectAsStateWithLifecycle()
        saved.endpointKey to state
    }
    val tabManager: TabManager = koinInject()
    val tabs by tabManager.tabs.collectAsState()
    val openTabsByEndpoint = tabs.filterNot { it.isPinnedHome }.groupBy { it.serverEndpointKey }
    val inventory = remember(uiState, registryStates) { buildServerInventory(uiState, registryStates) }
    var showManualForm by rememberSaveable {
        mutableStateOf(uiState.savedServers.isEmpty() && uiState.discoveredServers.isEmpty())
    }
    var editingServerId by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(autoReconnect) {
        viewModel.start(autoReconnect)
    }

    // Start/stop mDNS discovery with screen lifecycle
    DisposableEffect(Unit) {
        viewModel.startDiscovery()
        onDispose {
            viewModel.stopDiscovery()
        }
    }

    LaunchedEffect(uiState.navigationDestination) {
        when (uiState.navigationDestination) {
            is NavigationDestination.Sessions -> {
                viewModel.clearNavigationDestination()
                onNavigateToSessions()
            }
            is NavigationDestination.Projects -> {
                viewModel.clearNavigationDestination()
                onNavigateToProjects()
            }
            null -> { /* waiting for connection */ }
        }
    }

    serverScaffold(
        presentation = ServerPresentation(
            uiState = uiState,
            inventory = inventory,
            openTabsByEndpoint = openTabsByEndpoint,
            tabManager = tabManager,
            viewModel = viewModel,
            showManualForm = showManualForm,
            onShowManualForm = { showManualForm = true },
            editingServerId = editingServerId,
            onEditServer = { saved ->
                viewModel.prepareSavedServer(saved)
                editingServerId = saved.id
            },
            onDismissEdit = { editingServerId = null },
            onNavigateToSessions = onNavigateToSessions,
            onConnectSavedServer = onConnectSavedServer,
        ),
        onSettings = onSettings,
    )
}

private data class ServerPresentation(
    val uiState: ServerUiState,
    val inventory: ServerInventory,
    val openTabsByEndpoint: Map<String?, List<dev.blazelight.p4oc.ui.tabs.TabInstance>>,
    val tabManager: TabManager,
    val viewModel: ServerViewModel,
    val showManualForm: Boolean,
    val onShowManualForm: () -> Unit,
    val editingServerId: String?,
    val onEditServer: (SavedServer) -> Unit,
    val onDismissEdit: () -> Unit,
    val onNavigateToSessions: () -> Unit,
    val onConnectSavedServer: ((SavedServer) -> Unit)?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun serverScaffold(
    presentation: ServerPresentation,
    onSettings: () -> Unit,
) {
    val theme = LocalOpenCodeTheme.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "[ ${stringResource(R.string.server_connect_title)} ]",
                        fontFamily = FontFamily.Monospace,
                        color = theme.text,
                    )
                },
                actions = {
                    IconButton(onClick = onSettings, modifier = Modifier.testTag("server_settings_button")) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = stringResource(R.string.server_settings_cd),
                            tint = theme.textMuted,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = theme.backgroundElement),
            )
        },
        containerColor = LocalOpenCodeTheme.current.background,
    ) { padding ->
        serverContent(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(Spacing.md),
            presentation,
        )
    }
}

private val serverContent: @Composable (Modifier, ServerPresentation) -> Unit = { modifier, presentation ->
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        val openTabsByEndpoint = presentation.openTabsByEndpoint
        if (presentation.inventory.saved.isNotEmpty()) {
            savedServersSection(
                state = SavedServersState(
                    presentation.inventory.saved,
                    presentation.uiState.isConnecting,
                    openTabsByEndpoint,
                ),
                actions = SavedServerActions(
                    onServerClick = presentation.onConnectSavedServer ?: presentation.viewModel::connectToSavedServer,
                    onEditServer = presentation.onEditServer,
                    onReviewTabs = { saved ->
                        openTabsByEndpoint[saved.endpointKey]?.firstOrNull()?.let {
                            presentation.tabManager.focusTab(it.id)
                        }
                        presentation.onNavigateToSessions()
                    },
                    onCloseTabsAndRemove = { saved ->
                        openTabsByEndpoint[saved.endpointKey].orEmpty().forEach {
                            presentation.tabManager.closeTab(it.id)
                        }
                        presentation.viewModel.removeSavedServer(saved)
                    },
                    onRemoveServer = presentation.viewModel::removeSavedServer,
                ),
            )
        }
        presentation.editingServerId?.let { id ->
            presentation.inventory.saved.firstOrNull { it.server.id == id }?.server?.let { server ->
                savedServerEditor(
                    SavedServerEditorPresentation(
                        server = server,
                        uiState = presentation.uiState,
                        viewModel = presentation.viewModel,
                        openTabCount = openTabsByEndpoint[server.endpointKey].orEmpty().size,
                        onReviewTabs = {
                            openTabsByEndpoint[server.endpointKey]?.firstOrNull()?.let {
                                presentation.tabManager.focusTab(it.id)
                            }
                            presentation.onNavigateToSessions()
                        },
                        onCloseTabsAndRemove = {
                            openTabsByEndpoint[server.endpointKey].orEmpty().forEach {
                                presentation.tabManager.closeTab(it.id)
                            }
                            presentation.viewModel.removeSavedServer(server)
                            presentation.onDismissEdit()
                        },
                        onDismiss = presentation.onDismissEdit,
                    ),
                )
            }
        }
        val showDiscovery = presentation.inventory.nearby.isNotEmpty() ||
            presentation.uiState.discoveryState == DiscoveryState.SCANNING
        if (showDiscovery) {
            discoveredServersSection(
                presentation.inventory.nearby,
                presentation.uiState.discoveryState,
                presentation.uiState.isConnecting,
                presentation.viewModel::connectToDiscoveredServer,
            )
        }
        manualServerSection(
            presentation.uiState,
            presentation.viewModel,
            presentation.showManualForm,
            presentation.onShowManualForm,
        )
        serverFooter(presentation.uiState.error)
    }
}

@Composable
private fun serverFooter(error: String?) {
    serverError(error)
    serverSetupHelpSection()
}

@Composable
private fun manualServerSection(
    uiState: ServerUiState,
    viewModel: ServerViewModel,
    showManualForm: Boolean,
    onShowManualForm: () -> Unit,
) {
    if (!showManualForm) {
        OutlinedButton(
            onClick = onShowManualForm,
            modifier = Modifier.fillMaxWidth().testTag("server_add_button"),
            shape = RectangleShape,
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(Spacing.sm))
            Text(stringResource(R.string.server_add), fontFamily = FontFamily.Monospace)
        }
    } else {
        remoteServerSection(
            state = RemoteServerState(
                uiState.remoteUrl,
                uiState.username,
                uiState.password,
                uiState.allowInsecure,
                uiState.isConnecting,
            ),
            actions = RemoteServerActions(
                viewModel::setRemoteUrl,
                viewModel::setUsername,
                viewModel::setPassword,
                viewModel::setAllowInsecure,
                viewModel::connectToRemote,
            ),
        )
    }
}

private val serverError: @Composable (String?) -> Unit = { error ->
    val theme = LocalOpenCodeTheme.current
    error?.let {
        Surface(
            color = theme.error.copy(alpha = 0.1f),
            shape = RectangleShape,
            modifier = Modifier.border(Sizing.strokeMd, theme.error.copy(alpha = 0.3f), RectangleShape),
        ) {
            Row(
                modifier = Modifier.padding(Spacing.md),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Error, stringResource(R.string.server_status_cd_error), tint = theme.error)
                Text(it, color = theme.error, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

internal fun serverUrlTextFieldValue(url: String): TextFieldValue =
    TextFieldValue(
        text = url,
        selection = TextRange(url.length),
    )

private data class RemoteServerState(
    val url: String,
    val username: String,
    val password: String,
    val allowInsecure: Boolean,
    val isConnecting: Boolean,
)

private data class RemoteServerActions(
    val onUrlChange: (String) -> Unit,
    val onUsernameChange: (String) -> Unit,
    val onPasswordChange: (String) -> Unit,
    val onAllowInsecureChange: (Boolean) -> Unit,
    val onConnect: () -> Unit,
)

@Composable
private fun remoteServerSection(
    state: RemoteServerState,
    actions: RemoteServerActions,
) {
    val theme = LocalOpenCodeTheme.current
    var passwordVisible by remember { mutableStateOf(false) }
    var showCredentials by rememberSaveable { mutableStateOf(false) }
    var urlFieldValue by remember { mutableStateOf(serverUrlTextFieldValue(state.url)) }

    LaunchedEffect(state.url) {
        if (state.url != urlFieldValue.text) {
            urlFieldValue = serverUrlTextFieldValue(state.url)
        }
    }

    Surface(
        color = theme.backgroundElement,
        shape = RectangleShape,
    ) {
        Column(
            modifier = Modifier.padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(
                text = "[ ${stringResource(R.string.server_remote_title)} ]",
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                color = theme.text,
            )
            Text(
                text = stringResource(R.string.server_remote_description),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = theme.textMuted,
            )
            remoteUrlField(
                value = urlFieldValue,
                onValueChange = { value ->
                    urlFieldValue = value
                    actions.onUrlChange(value.text)
                },
            )
            credentialsSection(
                state = state,
                actions = actions,
                controls = CredentialControls(
                    expanded = showCredentials,
                    passwordVisible = passwordVisible,
                    onToggleExpanded = { showCredentials = !showCredentials },
                    onTogglePassword = { passwordVisible = !passwordVisible },
                ),
            )
            connectButton(state = state, onConnect = actions.onConnect)
        }
    }
}

@Composable
private fun remoteUrlField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
) {
    val theme = LocalOpenCodeTheme.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(R.string.field_server_url), fontFamily = FontFamily.Monospace) },
        placeholder = {
            Text(
                stringResource(R.string.field_server_url_placeholder),
                fontFamily = FontFamily.Monospace,
            )
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("server_url_input"),
        shape = RectangleShape,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = theme.accent,
            unfocusedBorderColor = theme.border,
        ),
    )
}

private data class CredentialControls(
    val expanded: Boolean,
    val passwordVisible: Boolean,
    val onToggleExpanded: () -> Unit,
    val onTogglePassword: () -> Unit,
)

@Composable
private fun credentialsSection(
    state: RemoteServerState,
    actions: RemoteServerActions,
    controls: CredentialControls,
) {
    TextButton(
        onClick = controls.onToggleExpanded,
        modifier = Modifier.testTag("server_credentials_toggle"),
    ) {
        Icon(
            if (controls.expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = null,
        )
        Text(stringResource(R.string.server_credentials), fontFamily = FontFamily.Monospace)
    }
    AnimatedVisibility(controls.expanded) {
        credentialsFields(state, actions, controls.passwordVisible, controls.onTogglePassword)
    }
}

@Composable
private fun credentialsFields(
    state: RemoteServerState,
    actions: RemoteServerActions,
    passwordVisible: Boolean,
    onTogglePassword: () -> Unit,
) {
    val theme = LocalOpenCodeTheme.current
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        OutlinedTextField(
            value = state.username,
            onValueChange = actions.onUsernameChange,
            label = { Text(stringResource(R.string.field_username), fontFamily = FontFamily.Monospace) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("server_username_input"),
            shape = RectangleShape,
        )
        passwordField(state.password, actions.onPasswordChange, passwordVisible, onTogglePassword)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Checkbox) {
                    actions.onAllowInsecureChange(!state.allowInsecure)
                }
                .padding(vertical = Spacing.xs)
                .testTag("server_allow_insecure_toggle"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Checkbox(
                checked = state.allowInsecure,
                onCheckedChange = actions.onAllowInsecureChange,
                colors = CheckboxDefaults.colors(checkedColor = theme.accent),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.field_allow_insecure),
                    fontFamily = FontFamily.Monospace,
                    color = theme.text,
                )
                Text(
                    stringResource(R.string.field_allow_insecure_desc),
                    fontFamily = FontFamily.Monospace,
                    color = theme.textMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun passwordField(
    password: String,
    onPasswordChange: (String) -> Unit,
    passwordVisible: Boolean,
    onTogglePassword: () -> Unit,
) {
    val theme = LocalOpenCodeTheme.current
    OutlinedTextField(
        value = password,
        onValueChange = onPasswordChange,
        label = { Text(stringResource(R.string.field_password), fontFamily = FontFamily.Monospace) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("server_password_input"),
        visualTransformation = if (passwordVisible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        shape = RectangleShape,
        trailingIcon = {
            IconButton(
                onClick = onTogglePassword,
                modifier = Modifier.testTag("server_password_visibility"),
            ) {
                Icon(
                    if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = stringResource(R.string.server_password_visibility_cd),
                    tint = theme.textMuted,
                )
            }
        },
    )
}

@Composable
private fun connectButton(state: RemoteServerState, onConnect: () -> Unit) {
    val theme = LocalOpenCodeTheme.current
    Button(
        onClick = onConnect,
        enabled = state.url.isNotBlank() && !state.isConnecting,
        modifier = Modifier.fillMaxWidth().testTag("server_connect_button"),
        shape = RectangleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = theme.accent,
            contentColor = theme.background,
        ),
    ) {
        if (state.isConnecting) {
            TuiLoadingIndicator()
            Spacer(Modifier.width(Spacing.md))
            Text(stringResource(R.string.button_connecting), fontFamily = FontFamily.Monospace)
        } else {
            Icon(Icons.Default.Login, contentDescription = null)
            Spacer(Modifier.width(Spacing.sm))
            Text(stringResource(R.string.button_connect), fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun serverSetupHelpSection() {
    val theme = LocalOpenCodeTheme.current
    var expanded by remember { mutableStateOf(false) }

    Surface(
        color = theme.backgroundElement,
        shape = RectangleShape,
    ) {
        Column(
            modifier = Modifier.padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            // Header — always visible, acts as toggle
            Row(
                modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "[ ? ${stringResource(R.string.server_setup_title)} ]",
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    color = theme.text,
                )
                Text(
                    text = if (expanded) "▾" else "▸",
                    fontFamily = FontFamily.Monospace,
                    color = theme.textMuted,
                )
            }

            setupHelpContent(expanded)
        }
    }
}

@Composable
private fun setupHelpContent(expanded: Boolean) {
    val theme = LocalOpenCodeTheme.current
    Text(
        text = stringResource(R.string.server_setup_subtitle),
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = theme.textMuted,
    )
    AnimatedVisibility(visible = expanded) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Spacer(Modifier.height(Spacing.xs))
            setupStep(
                number = "1",
                title = stringResource(R.string.server_setup_step1_title),
                command = stringResource(R.string.server_setup_step1_cmd),
            )
            setupStep(
                number = "2",
                title = stringResource(R.string.server_setup_step2_title),
                command = stringResource(R.string.server_setup_step2_cmd),
            )
            setupStep(
                number = "3",
                title = stringResource(R.string.server_setup_step3_title),
                command = stringResource(R.string.server_setup_step3_cmd),
            )
            setupHelpTip()
        }
    }
}

private val setupHelpTip: @Composable () -> Unit = {
    val theme = LocalOpenCodeTheme.current
    Surface(
        color = theme.accent.copy(alpha = 0.08f),
        shape = RectangleShape,
        modifier = Modifier.border(
            Sizing.strokeThin,
            theme.accent.copy(alpha = 0.3f),
            RectangleShape,
        ),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(
                text = "── ${stringResource(R.string.server_setup_tip_label)} ──",
                fontFamily = FontFamily.Monospace,
                color = theme.accent,
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                text = stringResource(R.string.server_setup_tip_text),
                fontFamily = FontFamily.Monospace,
                color = theme.textMuted,
                style = MaterialTheme.typography.bodySmall,
            )
            Surface(
                color = theme.background,
                shape = RectangleShape,
                modifier = Modifier.border(Sizing.strokeThin, theme.border, RectangleShape),
            ) {
                Text(
                    text = stringResource(R.string.server_setup_find_ip),
                    modifier = Modifier.fillMaxWidth().padding(Spacing.sm),
                    fontFamily = FontFamily.Monospace,
                    color = theme.accent,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                text = stringResource(R.string.server_setup_test_hint),
                fontFamily = FontFamily.Monospace,
                color = theme.textMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun setupStep(
    number: String,
    title: String,
    command: String,
) {
    val theme = LocalOpenCodeTheme.current
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(
            text = "$number. $title",
            fontFamily = FontFamily.Monospace,
            color = theme.text,
            style = MaterialTheme.typography.bodyMedium,
        )
        Surface(
            color = theme.background,
            shape = RectangleShape,
            modifier = Modifier.border(Sizing.strokeThin, theme.border, RectangleShape),
        ) {
            Text(
                text = command,
                modifier = Modifier.fillMaxWidth().padding(Spacing.sm),
                fontFamily = FontFamily.Monospace,
                color = theme.accent,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private data class SavedServersState(
    val servers: List<ServerInventoryEntry>,
    val isConnecting: Boolean,
    val openTabsByEndpoint: Map<String?, List<dev.blazelight.p4oc.ui.tabs.TabInstance>>,
)

private data class SavedServerActions(
    val onServerClick: (SavedServer) -> Unit,
    val onEditServer: (SavedServer) -> Unit,
    val onReviewTabs: (SavedServer) -> Unit,
    val onCloseTabsAndRemove: (SavedServer) -> Unit,
    val onRemoveServer: (SavedServer) -> Unit,
)

@Composable
private fun savedServersSection(
    state: SavedServersState,
    actions: SavedServerActions,
) {
    val theme = LocalOpenCodeTheme.current
    var pendingForget by remember { mutableStateOf<Pair<SavedServer, Int>?>(null) }
    Surface(color = theme.backgroundElement, shape = RectangleShape) {
        Column(
            modifier = Modifier.padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(
                text = "[ ${stringResource(R.string.server_saved_servers)} ]",
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                color = theme.text,
            )
            Text(
                text = stringResource(R.string.server_saved_servers_desc),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = theme.textMuted,
            )
            state.servers.forEach { entry ->
                key(entry.server.id) {
                    savedServerRow(
                        entry = entry,
                        isConnecting = state.isConnecting,
                        openTabCount = state.openTabsByEndpoint[entry.server.endpointKey].orEmpty().size,
                        actions = actions,
                        onForget = { server, count -> pendingForget = server to count },
                    )
                }
            }
        }
    }
    pendingForget?.let { (server, count) ->
        forgetServerDialog(
            server = server,
            openTabCount = count,
            actions = actions,
            onDismiss = { pendingForget = null },
        )
    }
}

@Composable
private fun savedServerRow(
    entry: ServerInventoryEntry,
    isConnecting: Boolean,
    openTabCount: Int,
    actions: SavedServerActions,
    onForget: (SavedServer, Int) -> Unit,
) {
    val theme = LocalOpenCodeTheme.current
    val server = entry.server
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isConnecting, role = Role.Button) {
                actions.onServerClick(server)
            }
            .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Text("[${server.badgeLabel}]", fontFamily = FontFamily.Monospace, color = theme.textMuted)
        savedServerDetails(entry, openTabCount, Modifier.weight(1f))
        Box {
            IconButton(
                onClick = { menuExpanded = true },
                modifier = Modifier.testTag("saved_server_actions_${server.id}"),
            ) {
                Icon(
                    Icons.Default.MoreVert,
                    stringResource(R.string.server_actions_for, server.displayName),
                    tint = theme.textMuted,
                )
            }
            DropdownMenu(menuExpanded, { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.server_edit)) },
                    onClick = {
                        menuExpanded = false
                        actions.onEditServer(server)
                    },
                    leadingIcon = { Icon(Icons.Default.Edit, null) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.server_forget)) },
                    onClick = {
                        menuExpanded = false
                        onForget(server, openTabCount)
                    },
                    leadingIcon = { Icon(Icons.Default.Delete, null, tint = theme.error) },
                )
            }
        }
    }
}

@Composable
private fun savedServerDetails(
    entry: ServerInventoryEntry,
    openTabCount: Int,
    modifier: Modifier,
) {
    val theme = LocalOpenCodeTheme.current
    val server = entry.server
    Column(modifier) {
        Text(server.displayName, fontFamily = FontFamily.Monospace, color = theme.text, maxLines = 1)
        Text(
            server.endpoint,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = theme.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        serverStatusIndicator(entry.status)
        Text(
            if (server.username.isNullOrBlank()) {
                stringResource(R.string.server_auth_default)
            } else {
                stringResource(R.string.server_auth_configured)
            },
            style = MaterialTheme.typography.bodySmall,
            color = theme.textMuted,
        )
        Text(
            if (server.allowInsecure) {
                stringResource(R.string.server_tls_checks_off)
            } else {
                stringResource(R.string.server_tls_checks_on)
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (server.allowInsecure) theme.warning else theme.textMuted,
        )
        if (openTabCount > 0) {
            Text(
                stringResource(R.string.server_open_tabs_count, openTabCount),
                style = MaterialTheme.typography.bodySmall,
                color = theme.warning,
            )
        }
    }
}

@Composable
private fun savedServerEditor(presentation: SavedServerEditorPresentation) {
    val server = presentation.server
    val uiState = presentation.uiState
    val viewModel = presentation.viewModel
    val theme = LocalOpenCodeTheme.current
    var confirmRemoval by remember { mutableStateOf(false) }
    Surface(color = theme.backgroundElement, shape = RectangleShape) {
        savedServerEditorForm(presentation) { confirmRemoval = true }
    }
    if (confirmRemoval) {
        savedServerRemovalDialog(presentation) { confirmRemoval = false }
    }
}

@Composable
private fun savedServerEditorForm(presentation: SavedServerEditorPresentation, onRemove: () -> Unit) {
    val server = presentation.server
    val uiState = presentation.uiState
    val viewModel = presentation.viewModel
    val theme = LocalOpenCodeTheme.current
    Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text("[ ${server.displayName} ]", fontFamily = FontFamily.Monospace, color = theme.text)
            IconButton(onClick = presentation.onDismiss) { Icon(Icons.Default.Close, "Close server details") }
        }
        remoteServerSection(
            RemoteServerState(
                uiState.remoteUrl,
                uiState.username,
                uiState.password,
                uiState.allowInsecure,
                uiState.isConnecting,
            ),
            RemoteServerActions(
                viewModel::setRemoteUrl,
                viewModel::setUsername,
                viewModel::setPassword,
                viewModel::setAllowInsecure,
                viewModel::connectToRemote,
            ),
        )
        OutlinedButton(
            onClick = { viewModel.saveSavedServer(server) },
            enabled = uiState.remoteUrl.isNotBlank() && !uiState.isConnecting,
            modifier = Modifier.fillMaxWidth().testTag("saved_server_save"),
            shape = RectangleShape,
        ) {
            Icon(Icons.Default.Save, null)
            Spacer(Modifier.width(Spacing.sm))
            Text("Save", fontFamily = FontFamily.Monospace)
        }
        TextButton(onRemove, Modifier.fillMaxWidth().testTag("saved_server_detail_forget")) {
            Text(stringResource(R.string.server_forget), color = theme.error)
        }
    }
}

@Composable
private fun savedServerRemovalDialog(presentation: SavedServerEditorPresentation, onDismiss: () -> Unit) {
    val server = presentation.server
    val viewModel = presentation.viewModel
    forgetServerDialog(
        server = server,
        openTabCount = presentation.openTabCount,
        actions = SavedServerActions(
            onServerClick = {},
            onEditServer = {},
            onReviewTabs = { presentation.onReviewTabs() },
            onCloseTabsAndRemove = { presentation.onCloseTabsAndRemove() },
            onRemoveServer = {
                viewModel.removeSavedServer(server)
                presentation.onDismiss()
            },
        ),
        onDismiss = onDismiss,
    )
}

private data class SavedServerEditorPresentation(
    val server: SavedServer,
    val uiState: ServerUiState,
    val viewModel: ServerViewModel,
    val openTabCount: Int,
    val onReviewTabs: () -> Unit,
    val onCloseTabsAndRemove: () -> Unit,
    val onDismiss: () -> Unit,
)

@Composable
private fun forgetServerDialog(
    server: SavedServer,
    openTabCount: Int,
    actions: SavedServerActions,
    onDismiss: () -> Unit,
) {
    val theme = LocalOpenCodeTheme.current
    if (openTabCount == 0) {
        TuiConfirmDialog(
            onDismissRequest = onDismiss,
            onConfirm = {
                onDismiss()
                actions.onRemoveServer(server)
            },
            title = stringResource(R.string.server_forget_title, server.displayName),
            message = stringResource(R.string.server_forget_message_no_tabs),
            confirmText = stringResource(R.string.server_forget),
            dismissText = stringResource(R.string.button_cancel),
            isDestructive = true,
            modifier = Modifier.testTag("saved_server_forget_dialog"),
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.server_forget_title, server.displayName)) },
            text = { Text(stringResource(R.string.server_forget_message, openTabCount)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDismiss()
                        actions.onReviewTabs(server)
                    },
                    modifier = Modifier.testTag("server_review_tabs"),
                ) { Text(stringResource(R.string.server_review_tabs)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        onDismiss()
                        actions.onCloseTabsAndRemove(server)
                    },
                    modifier = Modifier.testTag("server_close_tabs_forget"),
                ) { Text(stringResource(R.string.server_close_tabs_forget), color = theme.error) }
            },
            modifier = Modifier.testTag("saved_server_forget_dialog"),
        )
    }
}

@Composable
private fun discoveredServersSection(
    servers: List<DiscoveredServer>,
    discoveryState: DiscoveryState,
    isConnecting: Boolean,
    onServerClick: (DiscoveredServer) -> Unit,
) {
    val theme = LocalOpenCodeTheme.current

    Surface(
        color = theme.backgroundElement,
        shape = RectangleShape,
    ) {
        Column(
            modifier = Modifier.padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "[ ${stringResource(R.string.discovery_section_title)} ]",
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    color = theme.text,
                )
                if (discoveryState == DiscoveryState.SCANNING) {
                    scanningIndicator()
                }
            }

            if (servers.isEmpty() && discoveryState == DiscoveryState.SCANNING) {
                Text(
                    text = stringResource(R.string.discovery_scanning_hint),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = theme.textMuted,
                )
            }

            servers.forEach { server ->
                discoveredServerRow(server, isConnecting, onServerClick)
            }
        }
    }
}

@Composable
private fun discoveredServerRow(
    server: DiscoveredServer,
    isConnecting: Boolean,
    onServerClick: (DiscoveredServer) -> Unit,
) {
    val theme = LocalOpenCodeTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isConnecting, role = Role.Button) {
                onServerClick(server)
            }
            .testTag("discovered_server_${server.serviceName}")
            .padding(vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        serverStatusIndicator(ServerConnectionStatus.AVAILABLE)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = server.serviceName,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = theme.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${server.host}:${server.port}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = theme.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = stringResource(R.string.button_connect),
            tint = theme.textMuted,
        )
    }
}

private val scanningIndicator: @Composable () -> Unit = {
    val theme = LocalOpenCodeTheme.current
    val infiniteTransition = rememberInfiniteTransition(label = "scanning")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec =
        infiniteRepeatable(
            animation = tween(durationMillis = 800),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "scanPulse",
    )

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        CircularProgressIndicator(
            Modifier.size(Sizing.indicatorDotActive),
            color = theme.accent.copy(alpha = alpha),
            strokeWidth = Sizing.strokeMd,
        )
        Text(
            stringResource(R.string.discovery_scanning),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = theme.accent.copy(alpha = alpha),
        )
    }
}
