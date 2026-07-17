@file:Suppress("TooManyFunctions")

package dev.blazelight.p4oc.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
    val enabledEndpointKeys: Set<String>,
    val searchQuery: String,
    val onToggleServer: (String) -> Unit,
    val onEnableAllServers: () -> Unit,
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

@Composable
@Suppress("LongMethod")
fun homeScreen(
    summary: HomeSummaryState,
    actions: HomeActions,
    modifier: Modifier = Modifier,
) {
    var selectedWorkspace by remember { mutableStateOf<WorkspaceSummary?>(null) }
    var disabledEndpointKeys by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var showAllWorkspaces by rememberSaveable { mutableStateOf(false) }
    val selected = selectedWorkspace
    if (selected != null) {
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
    } else if (showAllWorkspaces) {
        allWorkspacesScreen(
            workspaces = summary.filteredHomeResults(
                enabledEndpointKeys = summary.enabledEndpointKeys(disabledEndpointKeys),
                query = "",
            ).workspaces,
            onWorkspaceClick = {
                selectedWorkspace = it
                actions.onWorkspaceSelected(it)
            },
            onBack = { showAllWorkspaces = false },
            modifier = modifier,
        )
    } else {
        homeOverview(
            input = HomeOverviewInput(
                summary = summary,
                enabledEndpointKeys = summary.enabledEndpointKeys(disabledEndpointKeys),
                searchQuery = searchQuery,
                onToggleServer = { endpointKey ->
                    disabledEndpointKeys = disabledEndpointKeys.toggleMembership(endpointKey)
                },
                onEnableAllServers = { disabledEndpointKeys = emptyList() },
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
    }
}

@Composable
private fun allWorkspacesScreen(
    workspaces: List<WorkspaceSummary>,
    onWorkspaceClick: (WorkspaceSummary) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("home_all_workspaces"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        item { textAction("‹ Home", "Back to recent workspaces", onBack) }
        item { sectionLabel("All workspaces · ${workspaces.size}") }
        item {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
            ) {
                workspaces.forEach { workspace ->
                    workspaceShortcut(workspace, onWorkspaceClick, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun homeOverview(input: HomeOverviewInput, modifier: Modifier) {
    val summary = input.summary
    val results by remember(summary, input.enabledEndpointKeys, input.searchQuery) {
        derivedStateOf { summary.filteredHomeResults(input.enabledEndpointKeys, input.searchQuery) }
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
    item {
        serverFilters(
            servers = summary.servers,
            enabledEndpointKeys = input.enabledEndpointKeys,
            searchActive = input.searchQuery.isNotBlank(),
            onToggle = input.onToggleServer,
        )
    }
    if (input.searchQuery.isNotBlank() && input.enabledEndpointKeys.size < summary.servers.size) {
        item {
            Text(
                globalSearchOverrideLabel(input.enabledEndpointKeys.size),
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
    if (input.searchQuery.isBlank() && input.enabledEndpointKeys.isEmpty()) {
        item {
            infoCard(
                "No servers enabled",
                "Turn on one or more servers above to browse existing work.",
            )
        }
        item {
            textAction(
                label = "Select all servers",
                description = "Include every saved server in Home browsing",
                onClick = input.onEnableAllServers,
                modifier = Modifier.testTag("home_enable_all_servers"),
            )
        }
    } else {
        homeWorkspaces(input, results.workspaces)
        HomeSessions(input, results.sessions)
    }
}

private fun globalSearchOverrideLabel(enabledCount: Int): String =
    "Global search includes every server · clear search to resume " +
        "$enabledCount enabled server${if (enabledCount == 1) "" else "s"}"

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
                Text(
                    "/",
                    style = MaterialTheme.typography.labelMedium,
                    color = theme.textMuted,
                )
                Spacer(Modifier.width(Spacing.xs))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    field()
                    if (query.isEmpty()) {
                        Text(
                            "Search every server, session, or workspace…",
                            style = MaterialTheme.typography.labelMedium,
                            color = theme.textMuted,
                            maxLines = 1,
                        )
                    }
                }
            }
        },
    )
}

private val HomeSessions: LazyListScope.(HomeOverviewInput, List<SessionPreview>) -> Unit = { input, sessions ->
    item {
        val scope = if (input.searchQuery.isNotBlank()) {
            "all servers"
        } else {
            "${input.enabledEndpointKeys.size} server${if (input.enabledEndpointKeys.size == 1) "" else "s"}"
        }
        sectionLabel("Newest sessions · $scope · ${sessions.size}")
    }
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
        item {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
            ) {
                filteredWorkspaces.forEach { workspace ->
                    workspaceShortcut(workspace, input.onWorkspaceClick, Modifier.fillMaxWidth())
                }
            }
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
    enabledEndpointKeys: Set<String>,
    searchActive: Boolean,
    onToggle: (String) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().testTag("home_server_filters"),
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "Servers · ${enabledEndpointKeys.size}/${servers.size} on",
                style = MaterialTheme.typography.labelSmall,
                color = LocalOpenCodeTheme.current.textMuted,
            )
            if (servers.size > 1) {
                Text(
                    "Swipe ›",
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalOpenCodeTheme.current.primary,
                )
            }
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            contentPadding = PaddingValues(end = Spacing.xl),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(servers, key = { it.serverRef.endpointKey }) { server ->
                serverToggleCard(
                    server = server,
                    enabled = server.serverRef.endpointKey in enabledEndpointKeys,
                    searchActive = searchActive,
                    onToggle = { onToggle(server.serverRef.endpointKey) },
                )
            }
        }
    }
}

@Composable
private fun serverToggleCard(
    server: ServerSummary,
    enabled: Boolean,
    searchActive: Boolean,
    onToggle: () -> Unit,
) {
    val theme = LocalOpenCodeTheme.current
    val status = server.connectionState.toServerStatus()
    val accessibilityDescription = remember(server, status) {
        "${server.displayName}, ${status.visualLabel}, ${server.sessionCount} sessions, " +
            serverEndpointDetail(server)
    }
    Surface(
        shape = RectangleShape,
        color = theme.backgroundPanel,
        modifier = Modifier
            .width(Sizing.serverFilterCardWidth)
            .then(
                if (enabled) Modifier.border(Sizing.strokeMd, theme.primary, RectangleShape) else Modifier,
            )
            .toggleable(
                value = enabled,
                role = Role.Checkbox,
                onValueChange = { if (!searchActive) onToggle() },
            )
            .semantics {
                stateDescription = if (searchActive) {
                    "${if (enabled) "On" else "Off"}; saved filter paused during global search"
                } else if (enabled) {
                    "On"
                } else {
                    "Off"
                }
                contentDescription = accessibilityDescription
            }
            .testTag("home_server_toggle_${server.serverRef.endpointKey}"),
    ) {
        serverToggleCardContent(server, status, enabled, theme)
    }
}

@Composable
private fun serverToggleCardContent(
    server: ServerSummary,
    status: ServerConnectionStatus,
    enabled: Boolean,
    theme: OpenCodeTheme,
) {
    Column(Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(Sizing.indicatorDot)
                    .background(status.dotColor(theme), CircleShape)
                    .semantics { contentDescription = status.contentDescription },
            )
            Spacer(Modifier.width(Spacing.xs))
            Text(
                server.displayName,
                style = MaterialTheme.typography.labelMedium,
                color = if (enabled) theme.text else theme.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (enabled) "ON" else "OFF",
                style = MaterialTheme.typography.labelSmall,
                color = if (enabled) theme.primary else theme.textMuted,
            )
        }
        Row {
            Text(
                serverEndpointDetail(server),
                style = MaterialTheme.typography.labelSmall,
                color = theme.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text("${server.sessionCount}", style = MaterialTheme.typography.labelSmall, color = theme.textMuted)
        }
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
            Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(Sizing.strokeThick)
                    .fillMaxHeight()
                    .background(ProjectColors.colorForProject("server:${session.serverRef.endpointKey}")),
            )
            Row(
                Modifier.weight(1f).padding(horizontal = Spacing.sm, vertical = Spacing.xs),
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
                            Text(
                                "-${session.deletions}",
                                style = MaterialTheme.typography.labelSmall,
                                color = theme.error,
                            )
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

private val ServerConnectionStatus.contentDescription: String
    get() = when (this) {
        ServerConnectionStatus.CONNECTED -> "Connected server"
        ServerConnectionStatus.CONNECTING -> "Server connecting"
        ServerConnectionStatus.AVAILABLE -> "Server available"
        ServerConnectionStatus.DISCONNECTED -> "Server disconnected"
        ServerConnectionStatus.ERROR -> "Server connection error"
    }

private val ServerConnectionStatus.visualLabel: String
    get() = when (this) {
        ServerConnectionStatus.CONNECTED -> "online"
        ServerConnectionStatus.CONNECTING -> "connecting"
        ServerConnectionStatus.AVAILABLE -> "available"
        ServerConnectionStatus.DISCONNECTED -> "offline"
        ServerConnectionStatus.ERROR -> "error"
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

/** Applies enabled server filters when idle; a query searches every saved server. */
internal fun HomeSummaryState.filteredHomeResults(
    enabledEndpointKeys: Set<String>,
    query: String,
): FilteredHomeResults {
    val needle = query.trim()
    val applyBrowseFilter = needle.isEmpty()
    val matchingWorkspaces = ArrayList<WorkspaceSummary>(workspaces.size)
    for (workspace in workspaces) {
        if (applyBrowseFilter && workspace.serverRef.endpointKey !in enabledEndpointKeys) continue
        if (needle.isEmpty() || workspace.matchesSearch(needle)) matchingWorkspaces += workspace
    }
    val matchingSessions = ArrayList<SessionPreview>(sessions.size)
    for (session in sessions) {
        if (applyBrowseFilter && session.serverRef.endpointKey !in enabledEndpointKeys) continue
        if (needle.isEmpty() || session.matchesSearch(needle)) matchingSessions += session
    }
    matchingSessions.sortByDescending { it.updatedAt }
    return FilteredHomeResults(matchingWorkspaces, matchingSessions)
}

private fun HomeSummaryState.enabledEndpointKeys(disabledEndpointKeys: Collection<String>): Set<String> =
    servers.mapTo(LinkedHashSet(servers.size)) { it.serverRef.endpointKey }.apply {
        removeAll(disabledEndpointKeys)
    }

private fun List<String>.toggleMembership(value: String): List<String> =
    if (value in this) this - value else this + value

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
