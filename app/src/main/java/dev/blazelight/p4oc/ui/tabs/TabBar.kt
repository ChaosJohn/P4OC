package dev.blazelight.p4oc.ui.tabs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import dev.blazelight.p4oc.R
import dev.blazelight.p4oc.domain.model.SessionConnectionState
import dev.blazelight.p4oc.domain.model.SessionPresence
import dev.blazelight.p4oc.domain.server.WorkspaceKey
import dev.blazelight.p4oc.ui.components.status.SessionStatusDot
import dev.blazelight.p4oc.ui.theme.LocalOpenCodeTheme
import dev.blazelight.p4oc.ui.theme.Sizing
import dev.blazelight.p4oc.ui.theme.Spacing

/**
 * Tab bar showing all open tabs with indicators and close buttons.
 * Reuses visual language from SessionStatusBar.
 */
@Composable
fun TabBar(
    tabs: List<TabInstance>,
    activeTabId: String?,
    tabTitles: Map<String, String>,
    tabIcons: Map<String, ImageVector>,
    tabConnectionStates: Map<String, SessionConnectionState>,
    onTabClick: (String) -> Unit,
    onTabClose: (String) -> Unit,
    onAddClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val theme = LocalOpenCodeTheme.current
    val listState = rememberLazyListState()

    // Auto-scroll to active tab when it changes
    LaunchedEffect(activeTabId) {
        val activeIndex = tabs.indexOfFirst { it.id == activeTabId }
        if (activeIndex >= 0) {
            listState.animateScrollToItem(activeIndex)
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth().testTag("tab_bar"),
        color = theme.background,
        tonalElevation = Spacing.none
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(Sizing.chipHeight)
                .padding(horizontal = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LazyRow(
                state = listState,
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(
                    items = tabs,
                    key = { it.id }
                ) { tab ->
                    val isActive = tab.id == activeTabId
                    val title = tabTitles[tab.id] ?: "Tab"
                    val icon = tabIcons[tab.id] ?: Icons.Default.Tab
                    val connectionState = tabConnectionStates[tab.id]

                    TabIndicator(
                        title = title,
                        icon = icon,
                        connectionState = connectionState,
                        isActive = isActive,
                        closeable = !tab.isPinnedHome,
                        onClick = { onTabClick(tab.id) },
                        onClose = { onTabClose(tab.id) }
                    )
                }
            }

            // Add button
            IconButton(
                onClick = onAddClick,
                modifier = Modifier.size(Sizing.iconLg).testTag("tab_bar_add_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "New tab",
                    modifier = Modifier.size(Sizing.iconSm),
                    tint = theme.textMuted
                )
            }
        }
    }
}

/**
 * Individual tab indicator showing icon, title, state, and close button.
 */
@Composable
private fun TabIndicator(
    title: String,
    icon: ImageVector,
    connectionState: SessionConnectionState?,
    isActive: Boolean,
    closeable: Boolean = true,
    onClick: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val theme = LocalOpenCodeTheme.current
    val needsAttention = connectionState == SessionPresence.AWAITING_INPUT

    val backgroundColor = when {
        needsAttention && !isActive -> theme.warning.copy(alpha = 0.15f)
        isActive -> theme.backgroundElement
        else -> theme.background
    }

    Surface(
        modifier = modifier
            .height(Sizing.tabHeight)
            .clickable(onClick = onClick, role = Role.Tab),
        shape = RectangleShape,
        color = backgroundColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xxs)
        ) {
            if (connectionState != null) {
                SessionStatusDot(
                    presence = connectionState,
                    size = if (isActive) Sizing.indicatorDotActive else Sizing.indicatorDot,
                )
            } else {
                // Icon for non-chat tabs
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    modifier = Modifier.size(Sizing.iconXs),
                    tint = if (isActive) theme.text else theme.textMuted
                )
            }

            // Truncated title
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    needsAttention -> theme.warning
                    isActive -> theme.text
                    else -> theme.textMuted
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = Sizing.panelWidthSm)
            )

            // Close button only shows on the active closeable tab.
            if (isActive && closeable) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close tab",
                    modifier = Modifier
                        .size(Sizing.iconXs)
                        .clickable(onClick = onClose, role = Role.Button),
                    tint = theme.textMuted
                )
            }
        }
    }
}

/**
 * Helper to get appropriate icon for a screen route.
 */
fun getIconForRoute(route: String?): ImageVector {
    return when {
        route == null -> Icons.Default.Tab
        route == "sessions" -> Icons.AutoMirrored.Filled.List
        route.startsWith("chat/") -> Icons.AutoMirrored.Filled.Chat
        route == "files" || route.startsWith("files/") -> Icons.Default.Folder
        route.startsWith("terminal/") -> Icons.Default.Terminal
        route.startsWith("settings") -> Icons.Default.Settings
        route == "projects" -> Icons.Default.FolderOpen
        else -> Icons.Default.Tab
    }
}

/**
 * Helper to get appropriate title for a screen route.
 */
data class TabTitleLabels(
    val fallbackTab: String,
    val sessions: String,
    val chat: String,
    val files: String,
    val file: String,
    val terminal: String,
    val settings: String,
    val projects: String,
    val globalWorkspace: String,
    val sessionWorkspace: String,
)

@Composable
fun rememberTabTitleLabels(): TabTitleLabels = TabTitleLabels(
    fallbackTab = stringResource(R.string.tab_title_fallback),
    sessions = stringResource(R.string.sessions_title),
    chat = stringResource(R.string.tab_title_chat),
    files = stringResource(R.string.tab_title_files),
    file = stringResource(R.string.tab_title_file),
    terminal = stringResource(R.string.terminal_title),
    settings = stringResource(R.string.settings_title),
    projects = stringResource(R.string.tab_title_projects),
    globalWorkspace = stringResource(R.string.tab_workspace_global),
    sessionWorkspace = stringResource(R.string.tab_workspace_session),
)

fun getTitleForRoute(
    route: String?,
    labels: TabTitleLabels,
    sessionTitle: String? = null,
    workspaceKey: WorkspaceKey? = null,
): String {
    return when {
        route == null -> labels.fallbackTab
        route == "sessions" -> withWorkspaceSuffix(labels.sessions, workspaceKey, labels)
        route.startsWith("sessions?") -> withWorkspaceSuffix(labels.sessions, workspaceKey, labels)
        route.startsWith("chat/") -> withWorkspaceSuffix(sessionTitle ?: labels.chat, workspaceKey, labels)
        route == "files" -> workspaceLabel(workspaceKey, labels) ?: labels.files
        route.startsWith("files/") -> workspaceLabel(workspaceKey, labels) ?: labels.file
        route.startsWith("terminal/") -> withWorkspaceSuffix(sessionTitle ?: labels.terminal, workspaceKey, labels)
        route == "settings" -> labels.settings
        route.startsWith("settings/") -> labels.settings
        route == "projects" -> labels.projects
        else -> labels.fallbackTab
    }
}

private fun withWorkspaceSuffix(title: String, workspaceKey: WorkspaceKey?, labels: TabTitleLabels): String {
    val workspace = workspaceLabel(workspaceKey, labels) ?: return title
    return "$title · $workspace"
}

fun workspaceLabel(workspaceKey: WorkspaceKey?, labels: TabTitleLabels): String? = when (workspaceKey) {
    is WorkspaceKey.Directory -> workspaceKey.value.trimEnd('/').substringAfterLast('/').ifBlank { workspaceKey.value }
    WorkspaceKey.Global -> labels.globalWorkspace
    is WorkspaceKey.SessionScoped -> labels.sessionWorkspace
    null -> null
}
