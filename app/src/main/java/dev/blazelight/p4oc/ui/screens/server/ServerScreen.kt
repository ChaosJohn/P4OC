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
import dev.blazelight.p4oc.core.datastore.RecentServer
import dev.blazelight.p4oc.core.datastore.SavedServer
import dev.blazelight.p4oc.core.network.DiscoveredServer
import dev.blazelight.p4oc.core.network.DiscoveryState
import dev.blazelight.p4oc.ui.components.TuiConfirmDialog
import dev.blazelight.p4oc.ui.components.TuiLoadingIndicator
import dev.blazelight.p4oc.ui.tabs.TabManager
import dev.blazelight.p4oc.ui.theme.LocalOpenCodeTheme
import dev.blazelight.p4oc.ui.theme.Sizing
import dev.blazelight.p4oc.ui.theme.Spacing
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerScreen(
    viewModel: ServerViewModel = koinViewModel(),
    onNavigateToSessions: () -> Unit,
    onNavigateToProjects: () -> Unit,
    onSettings: () -> Unit
) {
    val theme = LocalOpenCodeTheme.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val tabManager: TabManager = koinInject()
    val tabs by tabManager.tabs.collectAsState()
    val openTabEndpointKeys = tabs.mapNotNull { it.serverEndpointKey }.toSet()

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "[ ${stringResource(R.string.server_connect_title)} ]",
                        fontFamily = FontFamily.Monospace,
                        color = theme.text
                    )
                },
                actions = {
                    IconButton(
                        onClick = onSettings,
                        modifier = Modifier.testTag("server_settings_button")
                    ) {
                        Text(
                            text = "⚙",
                            color = theme.textMuted,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = theme.backgroundElement
                )
            )
        },
        containerColor = theme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            // Discovered servers section (mDNS)
            if (uiState.discoveredServers.isNotEmpty() || uiState.discoveryState == DiscoveryState.SCANNING) {
                DiscoveredServersSection(
                    servers = uiState.discoveredServers,
                    discoveryState = uiState.discoveryState,
                    isConnecting = uiState.isConnecting,
                    onServerClick = viewModel::connectToDiscoveredServer
                )
            }

            if (uiState.savedServers.isNotEmpty()) {
                SavedServersSection(
                    servers = uiState.savedServers,
                    isConnecting = uiState.isConnecting,
                    openTabEndpointKeys = openTabEndpointKeys,
                    onServerClick = { saved ->
                        viewModel.setRemoteUrl(saved.endpoint)
                        viewModel.setUsername(saved.username ?: "opencode")
                        viewModel.setAllowInsecure(saved.allowInsecure)
                    },
                    onRemoveServer = viewModel::removeSavedServer,
                )
            }

            if (uiState.recentServers.isNotEmpty()) {
                RecentServersSection(
                    servers = uiState.recentServers,
                    isConnecting = uiState.isConnecting,
                    onServerClick = viewModel::connectToRecentServer,
                    onRemoveServer = viewModel::removeRecentServer
                )
            }

            RemoteServerSection(
                url = uiState.remoteUrl,
                username = uiState.username,
                password = uiState.password,
                allowInsecure = uiState.allowInsecure,
                isConnecting = uiState.isConnecting,
                onUrlChange = viewModel::setRemoteUrl,
                onUsernameChange = viewModel::setUsername,
                onPasswordChange = viewModel::setPassword,
                onAllowInsecureChange = viewModel::setAllowInsecure,
                onConnect = viewModel::connectToRemote
            )

            uiState.error?.let { error ->
                Surface(
                    color = theme.error.copy(alpha = 0.1f),
                    shape = RectangleShape,
                    modifier = Modifier.border(Sizing.strokeMd, theme.error.copy(alpha = 0.3f), RectangleShape)
                ) {
                    Row(
                        modifier = Modifier.padding(Spacing.md),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "✗",
                            color = theme.error,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = error,
                            color = theme.error,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            ServerSetupHelpSection()
        }
    }
}

internal fun serverUrlTextFieldValue(url: String): TextFieldValue = TextFieldValue(
    text = url,
    selection = TextRange(url.length),
)

@Composable
private fun RemoteServerSection(
    url: String,
    username: String,
    password: String,
    allowInsecure: Boolean,
    isConnecting: Boolean,
    onUrlChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onAllowInsecureChange: (Boolean) -> Unit,
    onConnect: () -> Unit
) {
    val theme = LocalOpenCodeTheme.current
    var passwordVisible by remember { mutableStateOf(false) }
    var urlFieldValue by remember { mutableStateOf(serverUrlTextFieldValue(url)) }

    LaunchedEffect(url) {
        if (url != urlFieldValue.text) {
            urlFieldValue = serverUrlTextFieldValue(url)
        }
    }

    Surface(
        color = theme.backgroundElement,
        shape = RectangleShape
    ) {
        Column(
            modifier = Modifier.padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Text(
                text = "[ ${stringResource(R.string.server_remote_title)} ]",
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                color = theme.text
            )

            Text(
                text = stringResource(R.string.server_remote_description),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = theme.textMuted
            )

            OutlinedTextField(
                value = urlFieldValue,
                onValueChange = { value ->
                    urlFieldValue = value
                    onUrlChange(value.text)
                },
                label = { Text(stringResource(R.string.field_server_url), fontFamily = FontFamily.Monospace) },
                placeholder = { Text(
                    stringResource(R.string.field_server_url_placeholder),
                    fontFamily = FontFamily.Monospace
                ) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("server_url_input"),
                shape = RectangleShape,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = theme.accent,
                    unfocusedBorderColor = theme.border
                )
            )

            OutlinedTextField(
                value = username,
                onValueChange = onUsernameChange,
                label = { Text(stringResource(R.string.field_username), fontFamily = FontFamily.Monospace) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RectangleShape,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = theme.accent,
                    unfocusedBorderColor = theme.border
                )
            )

            OutlinedTextField(
                value = password,
                onValueChange = onPasswordChange,
                label = { Text(stringResource(R.string.field_password), fontFamily = FontFamily.Monospace) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (passwordVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                shape = RectangleShape,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = theme.accent,
                    unfocusedBorderColor = theme.border
                ),
                trailingIcon = {
                    Text(
                        text = if (passwordVisible) "◉" else "○",
                        color = theme.textMuted,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.clickable(role = Role.Button) { passwordVisible = !passwordVisible }
                    )
                }
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Checkbox) { onAllowInsecureChange(!allowInsecure) }
                    .padding(vertical = Spacing.xs)
                    .testTag("server_allow_insecure_toggle"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                Text(
                    text = if (allowInsecure) "[x]" else "[ ]",
                    fontFamily = FontFamily.Monospace,
                    color = theme.accent
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.field_allow_insecure),
                        fontFamily = FontFamily.Monospace,
                        color = theme.text,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = stringResource(R.string.field_allow_insecure_desc),
                        fontFamily = FontFamily.Monospace,
                        color = theme.textMuted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Button(
                onClick = onConnect,
                enabled = url.isNotBlank() && !isConnecting,
                modifier = Modifier.fillMaxWidth().testTag("server_connect_button"),
                shape = RectangleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = theme.accent,
                    contentColor = theme.background
                )
            ) {
                if (isConnecting) {
                    TuiLoadingIndicator()
                    Spacer(Modifier.width(Spacing.md))
                    Text(stringResource(R.string.button_connecting), fontFamily = FontFamily.Monospace)
                } else {
                    Text("→ ${stringResource(R.string.button_connect)}", fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
private fun ServerSetupHelpSection() {
    val theme = LocalOpenCodeTheme.current
    var expanded by remember { mutableStateOf(false) }

    Surface(
        color = theme.backgroundElement,
        shape = RectangleShape
    ) {
        Column(
            modifier = Modifier.padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            // Header — always visible, acts as toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "[ ? ${stringResource(R.string.server_setup_title)} ]",
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    color = theme.text
                )
                Text(
                    text = if (expanded) "▾" else "▸",
                    fontFamily = FontFamily.Monospace,
                    color = theme.textMuted
                )
            }

            Text(
                text = stringResource(R.string.server_setup_subtitle),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = theme.textMuted
            )

            AnimatedVisibility(visible = expanded) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
                    Spacer(Modifier.height(Spacing.xs))

                    SetupStep(
                        number = "1",
                        title = stringResource(R.string.server_setup_step1_title),
                        command = stringResource(R.string.server_setup_step1_cmd)
                    )

                    SetupStep(
                        number = "2",
                        title = stringResource(R.string.server_setup_step2_title),
                        command = stringResource(R.string.server_setup_step2_cmd)
                    )

                    SetupStep(
                        number = "3",
                        title = stringResource(R.string.server_setup_step3_title),
                        command = stringResource(R.string.server_setup_step3_cmd)
                    )

                    // Tip box
                    Surface(
                        color = theme.accent.copy(alpha = 0.08f),
                        shape = RectangleShape,
                        modifier = Modifier.border(
                            Sizing.strokeThin,
                            theme.accent.copy(alpha = 0.3f),
                            RectangleShape
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(Spacing.md),
                            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                        ) {
                            Text(
                                text = "── ${stringResource(R.string.server_setup_tip_label)} ──",
                                fontFamily = FontFamily.Monospace,
                                color = theme.accent,
                                style = MaterialTheme.typography.labelMedium
                            )
                            Text(
                                text = stringResource(R.string.server_setup_tip_text),
                                fontFamily = FontFamily.Monospace,
                                color = theme.textMuted,
                                style = MaterialTheme.typography.bodySmall
                            )
                            SetupCodeBlock(
                                command = stringResource(R.string.server_setup_find_ip)
                            )
                            Text(
                                text = stringResource(R.string.server_setup_test_hint),
                                fontFamily = FontFamily.Monospace,
                                color = theme.textMuted,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupStep(number: String, title: String, command: String) {
    val theme = LocalOpenCodeTheme.current
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(
            text = "$number. $title",
            fontFamily = FontFamily.Monospace,
            color = theme.text,
            style = MaterialTheme.typography.bodyMedium
        )
        SetupCodeBlock(command = command)
    }
}

@Composable
private fun SetupCodeBlock(command: String) {
    val theme = LocalOpenCodeTheme.current
    Surface(
        color = theme.background,
        shape = RectangleShape,
        modifier = Modifier.border(Sizing.strokeThin, theme.border, RectangleShape)
    ) {
        Text(
            text = command,
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.sm),
            fontFamily = FontFamily.Monospace,
            color = theme.accent,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun SavedServersSection(
    servers: List<SavedServer>,
    isConnecting: Boolean,
    openTabEndpointKeys: Set<String>,
    onServerClick: (SavedServer) -> Unit,
    onRemoveServer: (SavedServer) -> Unit,
) {
    val theme = LocalOpenCodeTheme.current
    var pendingForget by remember { mutableStateOf<Pair<SavedServer, Int>?>(null) }
    Surface(
        color = theme.backgroundElement,
        shape = RectangleShape,
    ) {
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
            servers.forEach { server ->
                key(server.id) {
                    var menuExpanded by remember { mutableStateOf(false) }
                    val tlsLabel = if (server.allowInsecure) {
                        stringResource(R.string.server_tls_checks_off)
                    } else {
                        stringResource(R.string.server_tls_checks_on)
                    }
                    val openTabCount = openTabEndpointKeys.count { it == server.endpointKey }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isConnecting, role = Role.Button) { onServerClick(server) }
                            .padding(vertical = Spacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
                    ) {
                        Text("●", color = theme.success, fontFamily = FontFamily.Monospace)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = server.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                color = theme.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${server.endpoint} · $tlsLabel",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = theme.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (openTabCount > 0) {
                                Text(
                                    text = stringResource(R.string.server_open_tabs_count, openTabCount),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = theme.warning,
                                )
                            }
                        }
                        Box {
                            IconButton(
                                onClick = { menuExpanded = true },
                                enabled = !isConnecting,
                                modifier = Modifier.testTag("saved_server_actions_${server.id}"),
                            ) {
                                Icon(
                                    Icons.Default.MoreVert,
                                    contentDescription = stringResource(
                                        R.string.server_actions_for,
                                        server.displayName,
                                    ),
                                    tint = theme.textMuted,
                                )
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.server_forget)) },
                                    onClick = {
                                        menuExpanded = false
                                        pendingForget = server to openTabCount
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = null,
                                            tint = theme.error,
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
            pendingForget?.let { (server, openTabCount) ->
                TuiConfirmDialog(
                    onDismissRequest = { pendingForget = null },
                    onConfirm = {
                        pendingForget = null
                        onRemoveServer(server)
                    },
                    title = stringResource(R.string.server_forget_title, server.displayName),
                    message = if (openTabCount > 0) {
                        stringResource(R.string.server_forget_message, openTabCount)
                    } else {
                        stringResource(R.string.server_forget_message_no_tabs)
                    },
                    confirmText = stringResource(R.string.server_forget),
                    dismissText = stringResource(R.string.button_cancel),
                    isDestructive = true,
                    modifier = Modifier.testTag("saved_server_forget_dialog"),
                )
            }
        }
    }
}

@Composable
private fun RecentServersSection(
    servers: List<RecentServer>,
    isConnecting: Boolean,
    onServerClick: (RecentServer) -> Unit,
    onRemoveServer: (RecentServer) -> Unit
) {
    val theme = LocalOpenCodeTheme.current

    Surface(
        color = theme.backgroundElement,
        shape = RectangleShape
    ) {
        Column(
            modifier = Modifier.padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Text(
                text = "[ ${stringResource(R.string.server_recent_servers)} ]",
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                color = theme.text
            )

            servers.forEach { server ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isConnecting, role = Role.Button) { onServerClick(server) }
                        .padding(vertical = Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.lg)
                ) {
                    Text(
                        text = "◇",
                        color = theme.textMuted,
                        fontFamily = FontFamily.Monospace
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = server.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            color = theme.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = server.url,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = theme.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = "×",
                        color = theme.textMuted,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.clickable(role = Role.Button) { onRemoveServer(server) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DiscoveredServersSection(
    servers: List<DiscoveredServer>,
    discoveryState: DiscoveryState,
    isConnecting: Boolean,
    onServerClick: (DiscoveredServer) -> Unit
) {
    val theme = LocalOpenCodeTheme.current

    Surface(
        color = theme.backgroundElement,
        shape = RectangleShape
    ) {
        Column(
            modifier = Modifier.padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "[ ${stringResource(R.string.discovery_section_title)} ]",
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    color = theme.text
                )
                if (discoveryState == DiscoveryState.SCANNING) {
                    ScanningIndicator()
                }
            }

            if (servers.isEmpty() && discoveryState == DiscoveryState.SCANNING) {
                Text(
                    text = stringResource(R.string.discovery_scanning_hint),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = theme.textMuted
                )
            }

            servers.forEach { server ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isConnecting, role = Role.Button) {
                            onServerClick(server)
                        }
                        .testTag("discovered_server_${server.serviceName}")
                        .padding(vertical = Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.lg)
                ) {
                    Text(
                        text = "●",
                        color = theme.success,
                        fontFamily = FontFamily.Monospace
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = server.serviceName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            color = theme.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${server.host}:${server.port}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = theme.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = "→",
                        color = theme.textMuted,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun ScanningIndicator() {
    val theme = LocalOpenCodeTheme.current
    val infiniteTransition = rememberInfiniteTransition(label = "scanning")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scanPulse"
    )

    Text(
        text = "● ${stringResource(R.string.discovery_scanning)}",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = theme.accent.copy(alpha = alpha)
    )
}
