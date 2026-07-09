package dev.blazelight.p4oc.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import dev.blazelight.p4oc.domain.server.WorkspaceKey
import dev.blazelight.p4oc.ui.tabs.StartWorkSelection
import dev.blazelight.p4oc.ui.tabs.StartWorkTarget
import dev.blazelight.p4oc.ui.theme.LocalOpenCodeTheme
import dev.blazelight.p4oc.ui.theme.Spacing
import dev.blazelight.p4oc.ui.theme.TuiShapes

private const val HOME_SERVER_CARD_LIMIT = 2
private const val HOME_WORKSPACE_LIMIT = 4

data class HomeActions(
    val onBrowseSessions: (StartWorkTarget) -> Unit,
    val onOpenFiles: (StartWorkTarget) -> Unit,
    val onOpenTerminal: (StartWorkTarget) -> Unit,
    val onChooseTarget: () -> Unit,
    val onWorkspaceSelected: (WorkspaceSummary) -> Unit = {},
    val onWorkspaceDetailChanged: (StartWorkSelection) -> Unit = {},
)

private data class WorkspaceDetailActions(
    val onBack: () -> Unit,
    val onBrowseSessions: () -> Unit,
    val onOpenFiles: () -> Unit,
    val onOpenTerminal: () -> Unit,
)

@Composable
fun homeScreen(
    summary: HomeSummaryState,
    actions: HomeActions,
    modifier: Modifier = Modifier,
) {
    var selectedWorkspace by remember { mutableStateOf<WorkspaceSummary?>(null) }
    val selected = selectedWorkspace
    if (selected != null) {
        workspaceDetail(
            workspace = selected,
            openWork = summary.openWork.filter {
                it.serverRef == selected.serverRef && it.workspaceKey == selected.workspaceKey
            },
            actions = WorkspaceDetailActions(
                onBack = {
                    selectedWorkspace = null
                    actions.onWorkspaceDetailChanged(StartWorkSelection.NeedsSelection)
                },
                onBrowseSessions = { actions.onBrowseSessions(selected.toStartWorkTarget()) },
                onOpenFiles = { actions.onOpenFiles(selected.toStartWorkTarget()) },
                onOpenTerminal = { actions.onOpenTerminal(selected.toStartWorkTarget()) },
            ),
            modifier = modifier,
        )
        return
    }
    homeOverview(
        summary = summary,
        onWorkspaceClick = { workspace ->
            selectedWorkspace = workspace
            actions.onWorkspaceSelected(workspace)
            actions.onWorkspaceDetailChanged(
                StartWorkSelection.Selected(workspace.toStartWorkTarget()),
            )
        },
        onChooseTarget = actions.onChooseTarget,
        modifier = modifier,
    )
}

@Composable
private fun homeOverview(
    summary: HomeSummaryState,
    onWorkspaceClick: (WorkspaceSummary) -> Unit,
    onChooseTarget: () -> Unit,
    modifier: Modifier,
) {
    val theme = LocalOpenCodeTheme.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(Spacing.md)
            .testTag("home_screen"),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        homeHeader(summary.servers.size, summary.openWork.size)
        serverOverview(summary.servers)
        workspaceOverview(summary.workspaces, onWorkspaceClick)
        sectionLabel("Browse")
        HomeActionRow(
            label = "Sessions",
            description = "Find previous chats and workspace history.",
            icon = { Icon(Icons.Default.ViewList, contentDescription = null, tint = theme.textMuted) },
            onClick = onChooseTarget,
            testTag = "home_browse_sessions",
        )
        browseActions(onChooseTarget)
    }
}

@Composable
private fun serverOverview(servers: List<ServerSummary>) {
    if (servers.isEmpty()) return
    sectionLabel("Servers")
    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        modifier = Modifier.fillMaxWidth(),
    ) {
        servers.take(HOME_SERVER_CARD_LIMIT).forEach { server ->
            serverCard(server, Modifier.weight(1f))
        }
    }
}

@Composable
private fun workspaceOverview(
    workspaces: List<WorkspaceSummary>,
    onWorkspaceClick: (WorkspaceSummary) -> Unit,
) {
    sectionLabel("Resume")
    if (workspaces.isEmpty()) {
        emptyHomeCard()
    } else {
        workspaces.take(HOME_WORKSPACE_LIMIT).forEach { workspace ->
            workspaceRow(workspace) { onWorkspaceClick(workspace) }
        }
    }
}

@Composable
private fun browseActions(onChooseTarget: () -> Unit) {
    val theme = LocalOpenCodeTheme.current
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        HomeActionRow(
            label = "Files",
            description = "Open file browser.",
            icon = { Icon(Icons.Default.Folder, contentDescription = null, tint = theme.textMuted) },
            onClick = onChooseTarget,
            testTag = "home_open_files",
            modifier = Modifier.weight(1f),
        )
        HomeActionRow(
            label = "Terminal",
            description = "Open shell.",
            icon = { Icon(Icons.Default.Terminal, contentDescription = null, tint = theme.textMuted) },
            onClick = onChooseTarget,
            testTag = "home_open_terminal",
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun homeHeader(serverCount: Int, openWorkCount: Int) {
    val theme = LocalOpenCodeTheme.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RectangleShape,
        color = theme.backgroundElement,
    ) {
        Row(
            modifier = Modifier.padding(Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Home, contentDescription = null, tint = theme.accent)
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                Text("Home", style = MaterialTheme.typography.titleMedium, color = theme.text)
                Text(
                    "$openWorkCount open · $serverCount server${if (serverCount == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.textMuted,
                )
            }
        }
    }
}

@Composable
private fun sectionLabel(text: String) {
    val theme = LocalOpenCodeTheme.current
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = theme.textMuted,
    )
}

@Composable
private fun serverCard(server: ServerSummary, modifier: Modifier = Modifier) {
    val theme = LocalOpenCodeTheme.current
    Surface(
        modifier = modifier,
        shape = RectangleShape,
        color = theme.backgroundElement,
    ) {
        Column(
            modifier = Modifier.padding(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
        ) {
            Text(
                "${server.serverRef.badgeLabel}  ${server.displayName}",
                style = MaterialTheme.typography.labelMedium,
                color = theme.text,
            )
            Text(
                "${server.openTabCount} open",
                style = MaterialTheme.typography.bodySmall,
                color = theme.textMuted,
            )
        }
    }
}

@Composable
private fun workspaceRow(workspace: WorkspaceSummary, onClick: () -> Unit) {
    HomeActionRow(
        label = workspace.workspaceKey.displayLabel(),
        description = "${workspace.serverRef.badgeLabel}  ${workspace.serverRef.displayName} · " +
            "${workspace.openTabCount} open",
        icon = {
            Icon(
                Icons.Default.Folder,
                contentDescription = null,
                tint = LocalOpenCodeTheme.current.textMuted,
            )
        },
        onClick = onClick,
        testTag = "home_workspace_${workspace.serverRef.endpointKey}_${workspace.workspaceKey.displayLabel()}",
    )
}

@Composable
private fun emptyHomeCard() {
    HomeSection(
        title = "No open work",
        body = "Browse sessions to resume work, or use + to start something new.",
    )
}

@Composable
private fun HomeSection(title: String, body: String) {
    val theme = LocalOpenCodeTheme.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = TuiShapes.medium,
        color = theme.backgroundElement,
    ) {
        Column(
            modifier = Modifier.padding(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
        ) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = theme.text)
            Text(body, style = MaterialTheme.typography.bodySmall, color = theme.textMuted)
        }
    }
}

@Composable
private fun HomeActionRow(
    label: String,
    description: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    val theme = LocalOpenCodeTheme.current
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().testTag(testTag),
        shape = RectangleShape,
        color = theme.background,
    ) {
        Row(
            modifier = Modifier.padding(Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = theme.text)
                Text(description, style = MaterialTheme.typography.bodySmall, color = theme.textMuted)
            }
        }
    }
}

@Composable
private fun workspaceDetail(
    workspace: WorkspaceSummary,
    openWork: List<OpenWorkSummary>,
    actions: WorkspaceDetailActions,
    modifier: Modifier = Modifier,
) {
    val theme = LocalOpenCodeTheme.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(Spacing.md)
            .testTag("home_workspace_detail"),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        HomeActionRow(
            label = "← Home",
            description = "Back to all workspaces",
            icon = { Icon(Icons.Default.Home, contentDescription = null, tint = theme.textMuted) },
            onClick = actions.onBack,
            testTag = "home_workspace_detail_back",
        )
        HomeSection(
            title = workspace.workspaceKey.displayLabel(),
            body = "${workspace.serverRef.badgeLabel}  ${workspace.serverRef.displayName} · " +
                workspace.workspaceKey.detailLabel(),
        )
        HomeSection(
            title = "Open in this workspace",
            body = openWork.joinToString { it.route }.ifBlank { "No open work in this workspace yet." },
        )
        HomeActionRow(
            label = "Browse filtered sessions",
            description = "Use Sessions search/actions scoped to this workspace.",
            icon = { Icon(Icons.Default.ViewList, contentDescription = null, tint = theme.textMuted) },
            onClick = actions.onBrowseSessions,
            testTag = "home_workspace_detail_sessions",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            HomeActionRow(
                label = "+ Files",
                description = "Focus or create Files for this workspace.",
                icon = { Icon(Icons.Default.Folder, contentDescription = null, tint = theme.textMuted) },
                onClick = actions.onOpenFiles,
                testTag = "home_workspace_detail_files",
                modifier = Modifier.weight(1f),
            )
            HomeActionRow(
                label = "+ Terminal",
                description = "Create Terminal here.",
                icon = { Icon(Icons.Default.Terminal, contentDescription = null, tint = theme.textMuted) },
                onClick = actions.onOpenTerminal,
                testTag = "home_workspace_detail_terminal",
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private fun WorkspaceSummary.toStartWorkTarget(): StartWorkTarget = StartWorkTarget(
    serverRef = serverRef,
    workspaceKey = workspaceKey,
)

private fun WorkspaceKey.displayLabel(): String = when (this) {
    WorkspaceKey.Global -> "Global workspace"
    is WorkspaceKey.Directory -> value.trimEnd('/').substringAfterLast('/').ifBlank { value }
    is WorkspaceKey.SessionScoped -> "Session ${sessionId.value}"
}

private fun WorkspaceKey.detailLabel(): String = when (this) {
    WorkspaceKey.Global -> "No project context"
    is WorkspaceKey.Directory -> value
    is WorkspaceKey.SessionScoped -> "Session-scoped workspace"
}
