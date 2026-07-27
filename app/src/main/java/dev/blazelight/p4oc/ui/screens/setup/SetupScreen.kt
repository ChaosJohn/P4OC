package dev.blazelight.p4oc.ui.screens.setup

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.blazelight.p4oc.R
import dev.blazelight.p4oc.core.network.DiscoveredServer
import dev.blazelight.p4oc.core.network.DiscoveryState
import dev.blazelight.p4oc.ui.components.TuiSwitch
import dev.blazelight.p4oc.ui.screens.server.ServerUiState
import dev.blazelight.p4oc.ui.screens.server.ServerViewModel
import dev.blazelight.p4oc.ui.screens.server.serverSetupHelpContent
import dev.blazelight.p4oc.ui.screens.server.serverSetupHelpToggle
import dev.blazelight.p4oc.ui.theme.LocalOpenCodeTheme
import dev.blazelight.p4oc.ui.theme.Sizing
import dev.blazelight.p4oc.ui.theme.Spacing
import dev.blazelight.p4oc.ui.theme.TuiCodeFontSize
import org.koin.androidx.compose.koinViewModel

private const val SCAN_PULSE_MIN_ALPHA = 0.35f
private const val SCAN_PULSE_DURATION_MS = 700

/**
 * First-run setup (design 01) — the P4OC hero plus an inline connect form:
 * server URL, collapsible credentials/security, CONNECT, nearby-server scan,
 * and a "Server Setup" help footer. Backed by [ServerViewModel]; navigates to
 * [onConnected] once a connection succeeds.
 */
@Composable
fun SetupScreen(
    onConnected: () -> Unit
) {
    val theme = LocalOpenCodeTheme.current
    val viewModel: ServerViewModel = koinViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.start(autoReconnect = true) }

    DisposableEffect(Unit) {
        viewModel.startDiscovery()
        onDispose { viewModel.stopDiscovery() }
    }

    LaunchedEffect(uiState.navigationDestination) {
        if (uiState.navigationDestination != null) {
            viewModel.clearNavigationDestination()
            onConnected()
        }
    }

    Scaffold(containerColor = theme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.xl),
                verticalArrangement = Arrangement.Center,
            ) {
                setupHero()
                Spacer(Modifier.height(Spacing.xl + Spacing.sm))
                serverUrlField(uiState.remoteUrl, viewModel::setRemoteUrl)
                Spacer(Modifier.height(Spacing.lg))
                credentialsPanel(uiState, viewModel)
                Spacer(Modifier.height(Spacing.lg))
                connectButton(uiState, viewModel::connectToRemote)
                setupError(uiState.error)
                Spacer(Modifier.height(Spacing.xl))
                discoverySection(uiState, viewModel)
            }
            setupFooter()
        }
    }
}

@Composable
private fun setupHero() {
    val theme = LocalOpenCodeTheme.current
    Text(
        text = ">_",
        style = MaterialTheme.typography.displayLarge,
        color = theme.primary,
        fontFamily = FontFamily.Monospace,
    )
    Spacer(Modifier.height(Spacing.xl))
    Text(
        text = stringResource(R.string.setup_brand_title),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        color = theme.text,
    )
    Spacer(Modifier.height(Spacing.xs))
    Text(
        text = stringResource(R.string.setup_brand_tagline),
        style = MaterialTheme.typography.bodyMedium,
        color = theme.textMuted,
    )
    Spacer(Modifier.height(Spacing.xs))
    Text(
        text = stringResource(R.string.server_connect_hint),
        fontFamily = FontFamily.Monospace,
        fontSize = TuiCodeFontSize.lg,
        color = theme.secondary,
    )
}

@Composable
private fun serverUrlField(value: String, onValueChange: (String) -> Unit) {
    val theme = LocalOpenCodeTheme.current
    Text(
        text = stringResource(R.string.setup_server_url_label),
        fontFamily = FontFamily.Monospace,
        fontSize = TuiCodeFontSize.md,
        color = theme.textMuted,
    )
    Spacer(Modifier.height(Spacing.sm))
    setupInputBox(
        value = value,
        onValueChange = onValueChange,
        options = SetupFieldOptions(
            placeholder = stringResource(R.string.field_server_url_placeholder),
            keyboardType = KeyboardType.Uri,
            testTag = "setup_url_input",
        ),
        leading = {
            Text(
                text = "/",
                color = theme.primary,
                fontFamily = FontFamily.Monospace,
                fontSize = TuiCodeFontSize.xl,
            )
        },
    )
}

@Composable
private fun credentialsPanel(uiState: ServerUiState, viewModel: ServerViewModel) {
    val theme = LocalOpenCodeTheme.current
    // Credentials are required to reach an OpenCode server, so the panel starts open.
    var expanded by rememberSaveable { mutableStateOf(true) }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(Sizing.buttonHeightLg)
                .background(theme.backgroundPanel, RectangleShape)
                .border(Sizing.strokeMd, theme.backgroundElement, RectangleShape)
                .clickable(role = Role.Button) { expanded = !expanded }
                .padding(horizontal = Spacing.lg)
                .testTag("setup_credentials_toggle"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(
                text = if (expanded) "▾" else "▸",
                color = theme.secondary,
                fontFamily = FontFamily.Monospace,
                fontSize = TuiCodeFontSize.lg,
            )
            Text(
                text = stringResource(R.string.server_credentials),
                color = theme.text,
                fontFamily = FontFamily.Monospace,
                fontSize = TuiCodeFontSize.lg,
                modifier = Modifier.weight(1f),
            )
            if (!expanded) {
                Text(
                    text = stringResource(R.string.setup_credentials_hint),
                    color = theme.border,
                    fontFamily = FontFamily.Monospace,
                    fontSize = TuiCodeFontSize.sm,
                )
            }
        }
        AnimatedVisibility(expanded) {
            credentialsFields(uiState, viewModel)
        }
    }
}

@Composable
private fun credentialsFields(uiState: ServerUiState, viewModel: ServerViewModel) {
    val theme = LocalOpenCodeTheme.current
    var passwordVisible by remember { mutableStateOf(false) }
    Column(Modifier.padding(top = Spacing.lg)) {
        fieldLabel(stringResource(R.string.setup_username_label))
        Spacer(Modifier.height(Spacing.sm))
        setupInputBox(
            value = uiState.username,
            onValueChange = viewModel::setUsername,
            options = SetupFieldOptions(
                height = Sizing.textFieldHeightSm,
                testTag = "setup_username_input",
            ),
        )
        Spacer(Modifier.height(Spacing.lg))

        fieldLabel(stringResource(R.string.setup_password_label))
        Spacer(Modifier.height(Spacing.sm))
        setupInputBox(
            value = uiState.password,
            onValueChange = viewModel::setPassword,
            options = SetupFieldOptions(
                height = Sizing.textFieldHeightSm,
                keyboardType = KeyboardType.Password,
                visualTransformation = if (passwordVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                testTag = "setup_password_input",
            ),
            trailing = {
                Text(
                    text = if (passwordVisible) {
                        stringResource(R.string.setup_password_hide)
                    } else {
                        stringResource(R.string.setup_password_show)
                    },
                    color = theme.secondary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = TuiCodeFontSize.lg,
                    modifier = Modifier
                        .clickable(role = Role.Button) { passwordVisible = !passwordVisible }
                        .testTag("setup_password_visibility"),
                )
            },
        )
        if (uiState.showTlsOptions) {
            Spacer(Modifier.height(Spacing.xl))
            credentialsTlsSection(uiState.allowInsecure, viewModel::setAllowInsecure)
        } else {
            Spacer(Modifier.height(Spacing.lg))
        }
    }
}

@Composable
private fun credentialsTlsSection(allowInsecure: Boolean, onAllowInsecureChange: (Boolean) -> Unit) {
    val theme = LocalOpenCodeTheme.current
    Text(
        text = stringResource(R.string.setup_tls_label),
        fontFamily = FontFamily.Monospace,
        fontSize = TuiCodeFontSize.sm,
        color = theme.textMuted,
        letterSpacing = 1.sp,
    )
    Spacer(Modifier.height(Spacing.md))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.field_allow_insecure),
                color = theme.text,
                fontSize = TuiCodeFontSize.xl,
            )
            Text(
                text = stringResource(R.string.setup_tls_skip_verification),
                color = theme.textMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = TuiCodeFontSize.md,
            )
        }
        TuiSwitch(
            checked = allowInsecure,
            onCheckedChange = onAllowInsecureChange,
            modifier = Modifier.testTag("setup_allow_insecure_toggle"),
        )
    }
    Row(
        modifier = Modifier.padding(top = Spacing.sm, bottom = Spacing.xl),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Text(
            text = "⚠",
            color = theme.warning,
            fontFamily = FontFamily.Monospace,
            fontSize = TuiCodeFontSize.md,
        )
        Text(
            text = stringResource(R.string.setup_tls_warning),
            color = theme.textMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = TuiCodeFontSize.md,
        )
    }
}

@Composable
private fun fieldLabel(label: String) {
    val theme = LocalOpenCodeTheme.current
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            fontSize = TuiCodeFontSize.md,
            color = theme.textMuted,
        )
        Text(
            text = stringResource(R.string.setup_field_required),
            fontFamily = FontFamily.Monospace,
            fontSize = TuiCodeFontSize.md,
            color = theme.textMuted,
        )
    }
}

@Composable
private fun connectButton(uiState: ServerUiState, onConnect: () -> Unit) {
    val theme = LocalOpenCodeTheme.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(Sizing.buttonHeightLg)
            .background(theme.primary, RectangleShape)
            .clickable(enabled = !uiState.isConnecting, role = Role.Button) { onConnect() }
            .testTag("setup_connect_button"),
        contentAlignment = Alignment.Center,
    ) {
        if (uiState.isConnecting) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(Sizing.iconXs),
                    color = theme.background,
                    strokeWidth = Sizing.strokeThick,
                )
                Text(
                    text = stringResource(R.string.button_connecting),
                    color = theme.background,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = TuiCodeFontSize.xxl,
                )
            }
        } else {
            Text(
                text = stringResource(R.string.button_connect).uppercase(),
                color = theme.background,
                fontWeight = FontWeight.SemiBold,
                fontSize = TuiCodeFontSize.xxl,
                letterSpacing = 0.5.sp,
            )
        }
    }
}

@Composable
private fun setupError(error: String?) {
    val theme = LocalOpenCodeTheme.current
    if (error == null) return
    Spacer(Modifier.height(Spacing.sm))
    Text(
        text = error,
        color = theme.error,
        fontFamily = FontFamily.Monospace,
        fontSize = TuiCodeFontSize.md,
    )
}

@Composable
private fun discoverySection(uiState: ServerUiState, viewModel: ServerViewModel) {
    val scanning = uiState.discoveryState == DiscoveryState.SCANNING
    if (scanning) {
        scanningRow()
        Spacer(Modifier.height(Spacing.md))
    }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        uiState.discoveredServers.forEach { server ->
            discoveredRow(server, uiState.isConnecting) {
                viewModel.connectToDiscoveredServer(server)
            }
        }
    }
}

@Composable
private fun scanningRow() {
    val theme = LocalOpenCodeTheme.current
    val transition = rememberInfiniteTransition(label = "scanning")
    val alpha by transition.animateFloat(
        initialValue = SCAN_PULSE_MIN_ALPHA,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(SCAN_PULSE_DURATION_MS), RepeatMode.Reverse),
        label = "scanPulse",
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(Sizing.iconXxs),
            color = theme.warning.copy(alpha = alpha),
            strokeWidth = Sizing.strokeThick,
        )
        Text(
            text = stringResource(R.string.setup_scanning_nearby),
            color = theme.warning,
            fontFamily = FontFamily.Monospace,
            fontSize = TuiCodeFontSize.md,
        )
    }
}

@Composable
private fun discoveredRow(
    server: DiscoveredServer,
    isConnecting: Boolean,
    onClick: () -> Unit,
) {
    val theme = LocalOpenCodeTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(theme.backgroundPanel, RectangleShape)
            .clickable(enabled = !isConnecting, role = Role.Button, onClick = onClick)
            .padding(horizontal = Spacing.lg, vertical = Spacing.md)
            .testTag("setup_discovered_${server.serviceName}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.mdLg),
    ) {
        Box(
            modifier = Modifier
                .size(Sizing.indicatorDotActive)
                .background(theme.info, RectangleShape),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = server.serviceName,
                color = theme.text,
                fontSize = TuiCodeFontSize.xl,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${server.host}:${server.port}",
                color = theme.textMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = TuiCodeFontSize.md,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text("›", color = theme.textMuted, fontSize = TuiCodeFontSize.xxl)
    }
}

/**
 * Bottom-anchored "Server Setup" help. The toggle row stays pinned and the shared
 * [serverSetupHelpContent] expands upward above it, so the first-run screen shows the
 * same steps and networking tip as the connect-to-server screen.
 */
@Composable
private fun setupFooter() {
    val theme = LocalOpenCodeTheme.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.lg)) {
        AnimatedVisibility(expanded) {
            serverSetupHelpContent(
                Modifier
                    .padding(bottom = Spacing.md)
                    .background(theme.backgroundElement, RectangleShape)
                    .border(Sizing.strokeMd, theme.backgroundPanel, RectangleShape)
                    .verticalScroll(rememberScrollState())
                    .heightIn(max = Sizing.embeddedScrollMaxHeight)
                    .padding(Spacing.md),
            )
        }
        serverSetupHelpToggle(
            expanded = expanded,
            testTag = "setup_server_help_toggle",
        ) { expanded = !expanded }
    }
}

private data class SetupFieldOptions(
    val placeholder: String = "",
    val height: Dp = Sizing.buttonHeightLg,
    val keyboardType: KeyboardType = KeyboardType.Text,
    val visualTransformation: VisualTransformation = VisualTransformation.None,
    val testTag: String = "",
)

@Composable
private fun setupInputBox(
    value: String,
    onValueChange: (String) -> Unit,
    options: SetupFieldOptions = SetupFieldOptions(),
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val theme = LocalOpenCodeTheme.current
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .height(options.height)
            .background(theme.backgroundPanel, RectangleShape)
            .border(Sizing.strokeMd, theme.borderSubtle, RectangleShape)
            .then(if (options.testTag.isNotEmpty()) Modifier.testTag(options.testTag) else Modifier),
        textStyle = TextStyle(
            color = theme.text,
            fontFamily = FontFamily.Monospace,
            fontSize = TuiCodeFontSize.xl,
        ),
        singleLine = true,
        cursorBrush = SolidColor(theme.primary),
        visualTransformation = options.visualTransformation,
        keyboardOptions = KeyboardOptions(keyboardType = options.keyboardType),
        decorationBox = { inner ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = Spacing.lg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (leading != null) {
                    leading()
                    Spacer(Modifier.width(Spacing.md))
                }
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty() && options.placeholder.isNotEmpty()) {
                        Text(
                            text = options.placeholder,
                            color = theme.textMuted,
                            fontFamily = FontFamily.Monospace,
                            fontSize = TuiCodeFontSize.xl,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    inner()
                }
                if (trailing != null) {
                    Spacer(Modifier.width(Spacing.md))
                    trailing()
                }
            }
        },
    )
}
