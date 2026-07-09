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
import dev.blazelight.p4oc.ui.theme.LocalOpenCodeTheme
import dev.blazelight.p4oc.domain.server.WorkspaceKey
import dev.blazelight.p4oc.ui.theme.Spacing
import dev.blazelight.p4oc.ui.theme.TuiShapes

@Composable
fun HomeScreen(
    summary: HomeSummaryState,
    onBrowseSessions: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenTerminal: () -> Unit,
    modifier: Modifier = Modifier,
    onWorkspaceSelected: (WorkspaceSummary) -> Unit = {},
) {
    val theme = LocalOpenCodeTheme.current

    var selectedWorkspace by remember { mutableStateOf<WorkspaceSummary?>(null) }
    val selected = selectedWorkspace
    if (selected != null) {
        WorkspaceDetail(
            workspace = selected,
            openWork = summary.openWork.filter {
                it.serverRef == selected.serverRef && it.workspaceKey == selected.workspaceKey
            },
            onBack = { selectedWorkspace = null },
            onOpenFiles = onOpenFiles,
            onOpenTerminal = onOpenTerminal,
            modifier = modifier,
        )
        return
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(Spacing.md)
            .testTag("home_screen"),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Home,
                contentDescription = null,
                tint = theme.text,
            )
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                Text(
                    text = "Home",
                    style = MaterialTheme.typography.titleMedium,
                    color = theme.text,
                )
                Text(
                    text = "Resume existing work, browse sessions, and reopen workspaces.",
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.textMuted,
                )
            }
        }

        HomeSection(
            title = "Open work",
            body = homeOpenWorkSummary(summary.openWork.size, summary.workspaces.size),
        )

        HomeSection(
            title = "Servers",
            body = summary.servers.joinToString { "${it.displayName}: ${it.openTabCount} tabs" }
                .ifBlank { "No saved servers yet." },
        )

        summary.workspaces.take(6).forEach { workspace ->
            HomeActionRow(
                label = workspace.workspaceKey.displayLabel(),
                description = "${workspace.serverRef.displayName} · ${workspace.openTabCount} open item${if (workspace.openTabCount == 1) "" else "s"}",
                icon = { Icon(Icons.Default.Folder, contentDescription = null, tint = theme.textMuted) },
                onClick = {
                    selectedWorkspace = workspace
                    onWorkspaceSelected(workspace)
                },
                testTag = "home_workspace_${workspace.serverRef.endpointKey}_${workspace.workspaceKey.displayLabel()}",
            )
        }

        HomeActionRow(
            label = "Browse sessions",
            description = "Search, resume, rename, share, summarize, delete, or view changes.",
            icon = { Icon(Icons.Default.ViewList, contentDescription = null, tint = theme.textMuted) },
            onClick = onBrowseSessions,
            testTag = "home_browse_sessions",
        )

        HomeActionRow(
            label = "Open files",
            description = "Open the current workspace files tab.",
            icon = { Icon(Icons.Default.Folder, contentDescription = null, tint = theme.textMuted) },
            onClick = onOpenFiles,
            testTag = "home_open_files",
        )

        HomeActionRow(
            label = "Open terminal",
            description = "Create a terminal in the current workspace context.",
            icon = { Icon(Icons.Default.Terminal, contentDescription = null, tint = theme.textMuted) },
            onClick = onOpenTerminal,
            testTag = "home_open_terminal",
        )

        if (summary.openWork.isEmpty() && summary.workspaces.isEmpty()) {
            HomeSection(
                title = "Tip",
                body = "Use + to start a new chat, files tab, or terminal when there is nothing to resume.",
            )
        }
    }
}

private fun homeOpenWorkSummary(openWorkCount: Int, workspaceCount: Int): String = when {
    openWorkCount == 0 && workspaceCount == 0 ->
        "No open work yet. Browse sessions or use + to start something new."
    openWorkCount == 0 ->
        "$workspaceCount recent workspace${if (workspaceCount == 1) "" else "s"} ready to reopen."
    else ->
        "$openWorkCount open item${if (openWorkCount == 1) "" else "s"} ready to resume " +
            "across $workspaceCount workspace${if (workspaceCount == 1) "" else "s"}."
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
private fun WorkspaceDetail(
    workspace: WorkspaceSummary,
    openWork: List<OpenWorkSummary>,
    onBack: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenTerminal: () -> Unit,
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
            onClick = onBack,
            testTag = "home_workspace_detail_back",
        )
        HomeSection(
            title = workspace.workspaceKey.displayLabel(),
            body = "${workspace.serverRef.displayName} · ${workspace.workspaceKey.detailLabel()}",
        )
        HomeSection(
            title = "Open in this workspace",
            body = openWork.joinToString { it.route }.ifBlank { "No open work in this workspace yet." },
        )
        HomeActionRow(
            label = "Browse filtered sessions",
            description = "Use Sessions search/actions scoped to this workspace.",
            icon = { Icon(Icons.Default.ViewList, contentDescription = null, tint = theme.textMuted) },
            onClick = onBack,
            testTag = "home_workspace_detail_sessions",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            HomeActionRow(
                label = "+ Files",
                description = "Focus or create Files for this workspace.",
                icon = { Icon(Icons.Default.Folder, contentDescription = null, tint = theme.textMuted) },
                onClick = onOpenFiles,
                testTag = "home_workspace_detail_files",
                modifier = Modifier.weight(1f),
            )
            HomeActionRow(
                label = "+ Terminal",
                description = "Create Terminal here.",
                icon = { Icon(Icons.Default.Terminal, contentDescription = null, tint = theme.textMuted) },
                onClick = onOpenTerminal,
                testTag = "home_workspace_detail_terminal",
                modifier = Modifier.weight(1f),
            )
        }
    }
}

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
