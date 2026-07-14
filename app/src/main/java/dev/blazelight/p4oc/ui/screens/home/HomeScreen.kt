@file:Suppress("TooManyFunctions")

package dev.blazelight.p4oc.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import dev.blazelight.p4oc.core.network.ConnectionState
import dev.blazelight.p4oc.domain.model.SessionPresence
import dev.blazelight.p4oc.domain.server.WorkspaceKey
import dev.blazelight.p4oc.ui.screens.server.ServerConnectionStatus
import dev.blazelight.p4oc.ui.tabs.StartWorkSelection
import dev.blazelight.p4oc.ui.tabs.StartWorkTarget
import dev.blazelight.p4oc.ui.theme.LocalOpenCodeTheme
import dev.blazelight.p4oc.ui.theme.ProjectColors
import dev.blazelight.p4oc.ui.theme.Sizing
import dev.blazelight.p4oc.ui.theme.Spacing
import dev.blazelight.p4oc.ui.theme.opencode.OpenCodeTheme
import java.util.concurrent.TimeUnit

private const val RECENT_DAY_LIMIT = 30
private const val HOME_WORKSPACE_SHORTCUT_LIMIT = 3
private const val PERSISTENT_SERVER_CARD_LIMIT = 3
private const val ALL_SERVERS_CARD_WEIGHT = 0.72f

data class HomeActions(
    val onBrowseSessions: (StartWorkTarget) -> Unit,
    val onBrowseAllSessions: () -> Unit = {},
    val onOpenFiles: (StartWorkTarget) -> Unit,
    val onOpenTerminal: (StartWorkTarget) -> Unit,
    val onChooseTarget: () -> Unit,
    val onManageServers: () -> Unit = {},
    val onFocusTab: (String) -> Unit = {},
    val onResumeSession: (SessionPreview) -> Unit = {},
    val onWorkspaceSelected: (WorkspaceSummary) -> Unit = {},
    val onWorkspaceDetailChanged: (StartWorkSelection) -> Unit = {},
    val onStartScopedWork: (StartWorkTarget) -> Unit = {},
)

private data class HomeOverviewInput(
    val summary: HomeSummaryState,
    val filterEndpointKey: String?,
    val searchQuery: String,
    val onFilter: (String?) -> Unit,
    val onSearchQueryChange: (String) -> Unit,
    val showAllWorkspaces: Boolean,
    val onShowAllWorkspacesChange: (Boolean) -> Unit,
    val onWorkspaceClick: (WorkspaceSummary) -> Unit,
    val actions: HomeActions,
    val listState: LazyListState,
)

private data class WorkspaceDetailInput(
    val workspace: WorkspaceSummary,
    val openWork: List<OpenWorkSummary>,
    val sessions: List<SessionPreview>,
    val actions: HomeActions,
    val onBack: () -> Unit,
)

private data class ServerFilterHeaderState(
    val active: ServerSummary?,
    val totalCount: Int,
    val allSelected: Boolean,
    val expandable: Boolean,
    val expanded: Boolean,
)

@Composable
fun homeScreen(
    summary: HomeSummaryState,
    actions: HomeActions,
    modifier: Modifier = Modifier,
) {
    var selectedWorkspace by remember { mutableStateOf<WorkspaceSummary?>(null) }
    var filterEndpointKey by remember { mutableStateOf<String?>(null) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var showAllWorkspaces by rememberSaveable { mutableStateOf(false) }
    val selected = selectedWorkspace
    if (selected == null) {
        homeOverview(
            input = HomeOverviewInput(
                summary = summary,
                filterEndpointKey = filterEndpointKey,
                searchQuery = searchQuery,
                onFilter = { filterEndpointKey = it },
                onSearchQueryChange = { searchQuery = it },
                showAllWorkspaces = showAllWorkspaces,
                onShowAllWorkspacesChange = { showAllWorkspaces = it },
                onWorkspaceClick = {
                    selectedWorkspace = it
                    actions.onWorkspaceSelected(it)
                    actions.onWorkspaceDetailChanged(
                        StartWorkSelection.Selected(StartWorkTarget(it.serverRef, it.workspaceKey)),
                    )
                },
                actions = actions,
                listState = rememberLazyListState(),
            ),
            modifier = modifier,
        )
    } else {
        workspaceDetail(
            input = WorkspaceDetailInput(
                workspace = selected,
                openWork = summary.openWork.filter {
                    it.serverRef.endpointKey == selected.serverRef.endpointKey &&
                        it.workspaceKey == selected.workspaceKey
                },
                sessions = summary.sessions.filter {
                    it.serverRef.endpointKey == selected.serverRef.endpointKey &&
                        it.workspaceKey == selected.workspaceKey
                },
                actions = actions,
                onBack = {
                    selectedWorkspace = null
                    actions.onWorkspaceDetailChanged(StartWorkSelection.NeedsSelection)
                },
            ),
            modifier = modifier,
        )
    }
}

@Composable
private fun homeOverview(input: HomeOverviewInput, modifier: Modifier) {
    val summary = input.summary
    val results by remember(summary, input.filterEndpointKey, input.searchQuery) {
        derivedStateOf { summary.filteredHomeResults(input.filterEndpointKey, input.searchQuery) }
    }
    LazyColumn(
        state = input.listState,
        modifier = modifier.fillMaxSize().testTag("home_screen"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        homeOverviewContent(input, results)
    }
}

private fun LazyListScope.homeOverviewContent(
    input: HomeOverviewInput,
    results: FilteredHomeResults,
) {
    val summary = input.summary
    item { homeHeader(summary, input.actions.onManageServers) }
    item { homeSearchField(input.searchQuery, input.onSearchQueryChange) }
    item { serverFilters(summary.servers, input.filterEndpointKey, input.onFilter) }
    if (input.searchQuery.isNotBlank() && input.filterEndpointKey != null) {
        item {
            Text(
                "Search results include every server · clear search to return to the selected server",
                style = MaterialTheme.typography.labelSmall,
                color = LocalOpenCodeTheme.current.textMuted,
                maxLines = 2,
            )
        }
    }
    if (summary.isLoading) {
        item {
            infoCard("Loading existing work", "Sessions already loaded remain available while Home refreshes.")
        }
    }
    if (summary.partialFailures.isNotEmpty()) {
        item { infoCard("Some work is unavailable", summary.partialFailures.joinToString(" · ")) }
    }
    homeWorkspaces(input, results.workspaces)
    HomeSessions(input, results.sessions)
}

@Composable
private fun homeSearchField(query: String, onQueryChange: (String) -> Unit) {
    val theme = LocalOpenCodeTheme.current
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = theme.text),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(theme.accent),
        modifier = Modifier
            .fillMaxWidth()
            .height(Sizing.buttonHeightSm)
            .border(Sizing.strokeThin, theme.border, RectangleShape)
            .testTag("home_search_field"),
        decorationBox = { field ->
            Row(
                Modifier.fillMaxSize().padding(horizontal = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (query.isEmpty()) {
                    Text(
                        "/ Search every server, session, or workspace…",
                        style = MaterialTheme.typography.labelMedium,
                        color = theme.textMuted,
                        maxLines = 1,
                    )
                }
                field()
            }
        },
    )
}

private val HomeSessions: LazyListScope.(HomeOverviewInput, List<SessionPreview>) -> Unit = { input, sessions ->
    item { sectionLabel("Newest sessions · all ${sessions.size}") }
    if (sessions.isEmpty()) {
        item {
            infoCard(
                if (input.searchQuery.isNotBlank()) "No matching sessions" else "No sessions here",
                if (input.searchQuery.isNotBlank()) {
                    "Try another search or server filter."
                } else {
                    "Sessions with history will appear here for quick resume."
                },
            )
        }
    } else {
        items(
            items = sessions,
            key = { "${it.serverRef.endpointKey}:${it.sessionId.value}" },
        ) { session ->
            sessionRow(
                session = session,
                onResume = { input.actions.onResumeSession(session) },
                onWorkspace = {
                    input.onWorkspaceClick(
                        WorkspaceSummary(
                            serverRef = session.serverRef,
                            workspaceKey = session.workspaceKey,
                            sessionCount = 0,
                            openTabCount = 0,
                            mostRecentAt = session.updatedAt,
                        ),
                    )
                },
            )
        }
    }
}

@Suppress("LongMethod")
private fun LazyListScope.homeWorkspaces(input: HomeOverviewInput, filteredWorkspaces: List<WorkspaceSummary>) {
    item { sectionLabel(if (input.showAllWorkspaces) "All workspaces" else "Recent workspaces") }
    if (filteredWorkspaces.isEmpty()) {
        item {
            infoCard(
                if (input.summary.isLoading) {
                    "Looking for workspaces"
                } else if (input.searchQuery.isNotBlank()) {
                    "No matching workspaces"
                } else {
                    "No resumable work"
                },
                if (input.searchQuery.isNotBlank()) {
                    "Try another search or server filter."
                } else {
                    "Choose another server filter, or use + to start something new."
                },
            )
        }
    } else if (input.showAllWorkspaces || input.searchQuery.isNotBlank()) {
        items(filteredWorkspaces, key = { "${it.serverRef.endpointKey}:${it.workspaceKey}" }) { workspace ->
            workspaceShortcut(workspace, input.onWorkspaceClick, Modifier.fillMaxWidth())
        }
        if (input.searchQuery.isBlank()) {
            item {
                textAction(
                    "Show recent only",
                    "Collapse the workspace list",
                    { input.onShowAllWorkspacesChange(false) },
                )
            }
        }
    } else {
        item {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
            ) {
                filteredWorkspaces.take(HOME_WORKSPACE_SHORTCUT_LIMIT).forEach { workspace ->
                    workspaceShortcut(workspace, input.onWorkspaceClick, Modifier.fillMaxWidth())
                }
            }
        }
        if (filteredWorkspaces.size > HOME_WORKSPACE_SHORTCUT_LIMIT) {
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${HOME_WORKSPACE_SHORTCUT_LIMIT.coerceAtMost(filteredWorkspaces.size)} most recently used",
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalOpenCodeTheme.current.textMuted,
                    )
                    textAction(
                        "All ${filteredWorkspaces.size} workspaces ›",
                        "",
                        { input.onShowAllWorkspacesChange(true) },
                        Modifier.testTag("home_all_workspaces_action"),
                    )
                }
            }
        }
    }
}

@Composable
private fun homeHeader(summary: HomeSummaryState, onServers: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            Text("[ Home ]", style = MaterialTheme.typography.titleMedium, color = LocalOpenCodeTheme.current.text)
            Text(
                "${summary.sessions.size} sessions · ${summary.workspaces.size} workspaces",
                style = MaterialTheme.typography.labelSmall,
                color = LocalOpenCodeTheme.current.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        textAction("Servers ›", "", onServers, Modifier.testTag("home_servers_action"))
    }
}

@Composable
private fun serverFilters(
    servers: List<ServerSummary>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val active = servers.firstOrNull { it.serverRef.endpointKey == selected }
    val useCompactSelector = servers.size > PERSISTENT_SERVER_CARD_LIMIT
    Column(
        Modifier.fillMaxWidth().testTag("home_server_filters"),
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        if (useCompactSelector) {
            serverFilterHeader(
                ServerFilterHeaderState(
                    active = active,
                    totalCount = servers.sumOf { it.sessionCount },
                    allSelected = selected == null,
                    expandable = true,
                    expanded = expanded,
                ),
            ) { expanded = !expanded }
            if (expanded) {
                expandedServerSelector(servers, selected) { endpointKey ->
                    onSelect(endpointKey)
                    expanded = false
                }
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                allServersRailCard(
                    count = servers.sumOf { it.sessionCount },
                    selected = selected == null,
                    modifier = Modifier.weight(ALL_SERVERS_CARD_WEIGHT),
                ) { onSelect(null) }
                servers.forEach { server ->
                    serverRailCard(
                        server = server,
                        selected = selected == server.serverRef.endpointKey,
                        modifier = Modifier.weight(1f),
                    ) { onSelect(server.serverRef.endpointKey) }
                }
            }
        }
    }
}

@Composable
private fun allServersRailCard(count: Int, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val theme = LocalOpenCodeTheme.current
    Surface(
        onClick = onClick,
        shape = RectangleShape,
        color = if (selected) theme.backgroundElement else theme.backgroundPanel,
        modifier = modifier.then(
            if (selected) Modifier.border(Sizing.strokeMd, theme.accent, RectangleShape) else Modifier,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.sm, vertical = Spacing.xs)) {
            Text(
                "All",
                style = MaterialTheme.typography.labelMedium,
                color = theme.text,
                maxLines = 1,
            )
            Text(
                "$count sessions",
                style = MaterialTheme.typography.labelSmall,
                color = theme.textMuted,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun serverFilterHeader(
    state: ServerFilterHeaderState,
    onClick: () -> Unit,
) {
    val theme = LocalOpenCodeTheme.current
    Surface(
        onClick = onClick,
        shape = RectangleShape,
        color = if (state.allSelected || state.active != null) theme.backgroundElement else theme.backgroundPanel,
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.sm, vertical = Spacing.xs)) {
            Text(
                state.active?.displayName ?: "All servers",
                style = MaterialTheme.typography.labelMedium,
                color = theme.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${state.active?.sessionCount ?: state.totalCount}",
                style = MaterialTheme.typography.labelSmall,
                color = theme.textMuted,
            )
            if (state.expandable) {
                Text(
                    if (state.expanded) "▴" else "▾",
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.accent,
                    modifier = Modifier.padding(start = Spacing.xs),
                )
            }
        }
    }
}

@Composable
private fun expandedServerSelector(
    servers: List<ServerSummary>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    serverSelectorItem("All servers", servers.sumOf { it.sessionCount }, selected == null) {
        onSelect(null)
    }
    servers.forEach { server ->
        serverSelectorItem(
            label = server.displayName,
            count = server.sessionCount,
            selected = selected == server.serverRef.endpointKey,
            status = server.connectionState.toServerStatus(),
        ) { onSelect(server.serverRef.endpointKey) }
    }
}

@Composable
private fun serverSelectorItem(
    label: String,
    count: Int,
    selected: Boolean,
    status: ServerConnectionStatus? = null,
    onClick: () -> Unit,
) {
    val theme = LocalOpenCodeTheme.current
    Surface(
        onClick = onClick,
        shape = RectangleShape,
        color = if (selected) theme.backgroundElement else theme.backgroundPanel,
        modifier = if (selected) Modifier.border(Sizing.strokeMd, theme.accent, RectangleShape) else Modifier,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            if (status != null) {
                Box(Modifier.size(Sizing.indicatorDot).background(status.dotColor(theme), CircleShape))
            }
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = theme.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text("$count", style = MaterialTheme.typography.labelSmall, color = theme.textMuted)
        }
    }
}

@Composable
private fun serverRailCard(
    server: ServerSummary,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val theme = LocalOpenCodeTheme.current
    Surface(
        onClick = onClick,
        shape = RectangleShape,
        color = if (selected) theme.backgroundElement else theme.backgroundPanel,
        modifier = modifier.then(
            if (selected) Modifier.border(Sizing.strokeMd, theme.accent, RectangleShape) else Modifier,
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(Sizing.strokeThick)
                    .height(Sizing.buttonHeightMd)
                    .background(ProjectColors.colorForProject("server:${server.serverRef.endpointKey}")),
            )
            Spacer(Modifier.width(Spacing.xs))
            serverRailCardContent(server, theme, Modifier.weight(1f))
        }
    }
}

@Composable
private fun serverRailCardContent(server: ServerSummary, theme: OpenCodeTheme, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Box(
                Modifier.size(Sizing.indicatorDot)
                    .background(server.connectionState.toServerStatus().dotColor(theme), CircleShape),
            )
            Text(
                server.displayName,
                style = MaterialTheme.typography.labelMedium,
                color = theme.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val endpointDetail = serverEndpointDetail(server)
        if (endpointDetail != server.displayName) {
            Text(
                endpointDetail,
                style = MaterialTheme.typography.labelSmall,
                color = theme.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            "${server.sessionCount} sessions",
            style = MaterialTheme.typography.labelSmall,
            color = theme.textMuted,
            maxLines = 1,
        )
    }
}

private fun serverEndpointDetail(server: ServerSummary): String =
    server.serverRef.endpointKey.removePrefix("http://").removePrefix("https://")

private fun ConnectionState.toServerStatus(): ServerConnectionStatus = when (this) {
    ConnectionState.Connected -> ServerConnectionStatus.CONNECTED
    ConnectionState.Connecting -> ServerConnectionStatus.CONNECTING
    ConnectionState.Disconnected -> ServerConnectionStatus.DISCONNECTED
    is ConnectionState.Error -> ServerConnectionStatus.ERROR
}

@Composable
private fun workspaceShortcut(
    workspace: WorkspaceSummary,
    onOpen: (WorkspaceSummary) -> Unit,
    modifier: Modifier,
) {
    val theme = LocalOpenCodeTheme.current
    Surface(
        onClick = { onOpen(workspace) },
        shape = RectangleShape,
        color = theme.backgroundPanel,
        modifier = modifier.border(Sizing.strokeThin, theme.border, RectangleShape),
    ) {
        Row(
            Modifier.height(Sizing.listItemHeightSm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(Sizing.strokeThick)
                    .height(Sizing.listItemHeightSm)
                    .background(ProjectColors.colorForProject("server:${workspace.serverRef.endpointKey}")),
            )
            Column(
                Modifier.weight(1f).padding(horizontal = Spacing.xs),
                verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                    Text(
                        workspace.workspaceKey.displayLabel(),
                        style = MaterialTheme.typography.labelMedium,
                        color = theme.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    workspace.workspaceKey.detailLabel(),
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                "${workspace.sessionCount} ›",
                style = MaterialTheme.typography.labelSmall,
                color = theme.textMuted,
                maxLines = 1,
                modifier = Modifier.padding(end = Spacing.xxs),
            )
        }
    }
}

@Composable
@Suppress("LongMethod")
private fun sessionRow(
    session: SessionPreview,
    onResume: () -> Unit,
    onWorkspace: (() -> Unit)? = null,
) {
    val theme = LocalOpenCodeTheme.current
    Surface(
        onClick = onResume,
        shape = RectangleShape,
        color = theme.backgroundElement,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                Text(
                    session.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = theme.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "● ${session.status.label()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = session.status.statusColor(theme),
                    )
                    Text(
                        recency(session.updatedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = theme.textMuted,
                    )
                    if (session.childCount > 0) {
                        Text(
                            "[${session.childCount} sub]",
                            style = MaterialTheme.typography.labelSmall,
                            color = theme.info,
                        )
                    }
                    if (session.additions > 0) {
                        Text(
                            "+${session.additions}",
                            style = MaterialTheme.typography.labelSmall,
                            color = theme.success,
                        )
                    }
                    if (session.deletions > 0) {
                        Text("-${session.deletions}", style = MaterialTheme.typography.labelSmall, color = theme.error)
                    }
                    if (session.isShared) {
                        Text("◈ Shared", style = MaterialTheme.typography.labelSmall, color = theme.info)
                    }
                }
            }
            SessionWorkspaceLabel(session, onWorkspace)
        }
    }
}

private val SessionWorkspaceLabel: @Composable (SessionPreview, (() -> Unit)?) -> Unit = { session, onWorkspace ->
    val projectKey = "${session.serverRef.endpointKey}:${session.workspaceKey}"
    Surface(
        shape = RectangleShape,
        color = ProjectColors.colorForProject(projectKey),
        modifier = Modifier
            .widthIn(max = Sizing.chipMaxWidth)
            .then(
                if (onWorkspace == null) Modifier else Modifier.clickable(role = Role.Button, onClick = onWorkspace),
            ),
    ) {
        Text(
            session.workspaceKey.displayLabel(),
            style = MaterialTheme.typography.labelSmall,
            color = ProjectColors.textColorForProject(projectKey),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        )
    }
}

private fun SessionPresence.statusColor(
    theme: dev.blazelight.p4oc.ui.theme.opencode.OpenCodeTheme,
) = when (this) {
    SessionPresence.ERROR, SessionPresence.RETRYING -> theme.error
    SessionPresence.AWAITING_INPUT -> theme.warning
    SessionPresence.BUSY, SessionPresence.UNREAD -> theme.success
    SessionPresence.IDLE, SessionPresence.BACKGROUND -> theme.textMuted
}

private fun ServerConnectionStatus.dotColor(
    theme: OpenCodeTheme,
): Color = when (this) {
    ServerConnectionStatus.CONNECTED -> theme.success
    ServerConnectionStatus.CONNECTING -> theme.accent
    ServerConnectionStatus.AVAILABLE -> theme.success
    ServerConnectionStatus.DISCONNECTED -> theme.textMuted
    ServerConnectionStatus.ERROR -> theme.error
}

@Composable
private fun workspaceDetail(
    input: WorkspaceDetailInput,
    modifier: Modifier,
) {
    val workspace = input.workspace
    val target = StartWorkTarget(workspace.serverRef, workspace.workspaceKey)
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("home_workspace_detail"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        item {
            textAction(
                label = "← Home",
                description = "Back to the previous Home filter and position",
                onClick = input.onBack,
                modifier = Modifier.testTag("home_workspace_detail_back"),
            )
        }
        item {
            infoCard(
                workspace.workspaceKey.displayLabel(),
                "${workspace.workspaceKey.detailLabel()}\n" +
                    "${workspace.serverRef.badgeLabel}  ${workspace.serverRef.displayName}",
            )
        }
        item { sectionLabel("Open work") }
        workspaceOpenWork(input)
        item { sectionLabel("Sessions in this workspace") }
        workspaceSessions(input)
        item { sectionLabel("Start new work") }
        item {
            textAction(
                label = "New chat, Files, or Terminal",
                description = "Create work through the shared coordinator in this exact workspace",
                onClick = { input.actions.onStartScopedWork(target) },
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.workspaceOpenWork(input: WorkspaceDetailInput) {
    if (input.openWork.isEmpty()) {
        item {
            infoCard("Nothing open", "Existing tabs for this exact workspace appear here.")
        }
    } else {
        items(input.openWork, key = { it.tabId }) { work ->
            openWorkCard(work) { input.actions.onFocusTab(work.tabId) }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.workspaceSessions(input: WorkspaceDetailInput) {
    if (input.sessions.isEmpty()) {
        item {
            infoCard(
                "No sessions",
                "No resumable sessions were found for this exact server and workspace.",
            )
        }
    } else {
        items(input.sessions, key = { it.sessionId.value }) { session ->
            sessionRow(session = session, onResume = { input.actions.onResumeSession(session) })
        }
    }
}

@Composable
private fun openWorkCard(work: OpenWorkSummary, onFocus: () -> Unit) {
    val icon = when (work.type) {
        OpenWorkType.Chat -> Icons.Default.Chat
        OpenWorkType.Files -> Icons.Default.Folder
        OpenWorkType.Terminal -> Icons.Default.Terminal
    }
    val status = work.status?.label() ?: "Open"
    val theme = LocalOpenCodeTheme.current
    Surface(shape = RectangleShape, color = theme.backgroundElement, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Icon(icon, contentDescription = work.type.name, tint = theme.textMuted)
            Column(Modifier.weight(1f)) {
                Text(work.title, style = MaterialTheme.typography.labelMedium, color = theme.text)
                Text(
                    "${work.type.name} · $status",
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.textMuted,
                )
            }
            compactAction("Focus", onFocus)
        }
    }
}

@Composable
private fun infoCard(title: String, body: String) {
    val theme = LocalOpenCodeTheme.current
    Surface(shape = RectangleShape, color = theme.backgroundElement, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = theme.text)
            Text(body, style = MaterialTheme.typography.bodySmall, color = theme.textMuted)
        }
    }
}

@Composable
private fun textAction(label: String, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalOpenCodeTheme.current
    Surface(onClick = onClick, shape = RectangleShape, color = theme.background, modifier = modifier) {
        Column(Modifier.padding(Spacing.xs)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = theme.accent)
            if (description.isNotEmpty()) {
                Text(description, style = MaterialTheme.typography.labelSmall, color = theme.textMuted)
            }
        }
    }
}

@Composable
private fun compactAction(label: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RectangleShape, color = LocalOpenCodeTheme.current.background) {
        Text(
            label,
            modifier = Modifier.padding(Spacing.xs),
            style = MaterialTheme.typography.labelMedium,
            color = LocalOpenCodeTheme.current.accent
        )
    }
}

@Composable
private fun sectionLabel(text: String) = Text(
    text.uppercase(),
    style = MaterialTheme.typography.labelMedium,
    color = LocalOpenCodeTheme.current.textMuted,
)

internal data class FilteredHomeResults(
    val workspaces: List<WorkspaceSummary>,
    val sessions: List<SessionPreview>,
)

/** Applies browse scope when idle; a query searches every saved server. */
internal fun HomeSummaryState.filteredHomeResults(endpointKey: String?, query: String): FilteredHomeResults {
    val needle = query.trim()
    val browseEndpointKey = endpointKey.takeIf { needle.isEmpty() }
    val matchingWorkspaces = ArrayList<WorkspaceSummary>(workspaces.size)
    for (workspace in workspaces) {
        if (browseEndpointKey != null && workspace.serverRef.endpointKey != browseEndpointKey) continue
        if (needle.isEmpty() || workspace.matchesSearch(needle)) matchingWorkspaces += workspace
    }
    val matchingSessions = ArrayList<SessionPreview>(sessions.size)
    for (session in sessions) {
        if (browseEndpointKey != null && session.serverRef.endpointKey != browseEndpointKey) continue
        if (needle.isEmpty() || session.matchesSearch(needle)) matchingSessions += session
    }
    matchingSessions.sortByDescending { it.updatedAt }
    return FilteredHomeResults(matchingWorkspaces, matchingSessions)
}

private fun WorkspaceSummary.matchesSearch(query: String): Boolean =
    serverRef.displayName.contains(query, ignoreCase = true) ||
        workspaceKey.displayLabel().contains(query, ignoreCase = true) ||
        workspaceKey.detailLabel().contains(query, ignoreCase = true)

private fun SessionPreview.matchesSearch(query: String): Boolean =
    title.contains(query, ignoreCase = true) ||
        directory.contains(query, ignoreCase = true) ||
        serverRef.displayName.contains(query, ignoreCase = true) ||
        workspaceKey.displayLabel().contains(query, ignoreCase = true) ||
        workspaceKey.detailLabel().contains(query, ignoreCase = true)

private fun WorkspaceKey.displayLabel(): String = when (this) {
    WorkspaceKey.Global -> "No project context"
    is WorkspaceKey.Directory -> value.trimEnd('/').substringAfterLast('/').ifBlank { value }
    is WorkspaceKey.SessionScoped -> "Session ${sessionId.value}"
}
private fun WorkspaceKey.detailLabel(): String = when (this) {
    WorkspaceKey.Global -> "No project context"
    is WorkspaceKey.Directory -> value
    is WorkspaceKey.SessionScoped -> "Session-scoped workspace"
}

private fun SessionPresence.label() = name.lowercase().replaceFirstChar { it.uppercase() }
private fun recency(timestamp: Long, now: Long = System.currentTimeMillis()): String {
    if (timestamp <= 0) return "No recent session"
    val elapsed = (now - timestamp).coerceAtLeast(0)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(elapsed)
    val hours = TimeUnit.MILLISECONDS.toHours(elapsed)
    val days = TimeUnit.MILLISECONDS.toDays(elapsed)
    return when {
        minutes < 1 -> "Just now"
        hours < 1 -> "${minutes}m ago"
        days < 1 -> "${hours}h ago"
        days < RECENT_DAY_LIMIT -> "${days}d ago"
        else -> "Older"
    }
}
