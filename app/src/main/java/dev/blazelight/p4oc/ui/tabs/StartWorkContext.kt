package dev.blazelight.p4oc.ui.tabs

import dev.blazelight.p4oc.domain.server.ServerRef
import dev.blazelight.p4oc.domain.server.WorkspaceKey
import dev.blazelight.p4oc.ui.screens.home.ScopedHomeRepositoryState

data class StartWorkTarget(
    val serverRef: ServerRef,
    val workspaceKey: WorkspaceKey,
)

sealed interface StartWorkSelection {
    data class Selected(val target: StartWorkTarget) : StartWorkSelection
    data object NeedsSelection : StartWorkSelection
}

enum class StartWorkConnectionState {
    Online,
    Offline,
    AuthRequired,
}

enum class StartWorkAvailability {
    Ready,
    NoServers,
    ServerRemoved,
    WorkspaceMissing,
    Offline,
    AuthRequired,
}

data class StartWorkResolvedContext(
    val context: StartWorkContext,
    val target: StartWorkTarget?,
    val pendingAction: StartWorkAction?,
    val availability: StartWorkAvailability,
)

data class StartWorkContext(
    val source: StartWorkSource,
    val selection: StartWorkSelection,
    val defaultAction: StartWorkAction? = null,
) {
    val selectedTarget: StartWorkTarget?
        get() = (selection as? StartWorkSelection.Selected)?.target
}

fun StartWorkContext.resolve(
    availableServers: Collection<ServerRef>,
    availableWorkspaces: Collection<StartWorkTarget>,
    connectionStates: Map<String, StartWorkConnectionState>,
): StartWorkResolvedContext {
    val target = selectedTarget
    val availability = when {
        availableServers.isEmpty() -> StartWorkAvailability.NoServers
        target == null -> StartWorkAvailability.Ready
        availableServers.none { it.endpointKey == target.serverRef.endpointKey } ->
            StartWorkAvailability.ServerRemoved
        target.workspaceKey != WorkspaceKey.Global && target !in availableWorkspaces ->
            StartWorkAvailability.WorkspaceMissing
        connectionStates[target.serverRef.endpointKey] == StartWorkConnectionState.AuthRequired ->
            StartWorkAvailability.AuthRequired
        connectionStates[target.serverRef.endpointKey] != StartWorkConnectionState.Online ->
            StartWorkAvailability.Offline
        else -> StartWorkAvailability.Ready
    }
    return StartWorkResolvedContext(
        context = this,
        target = target,
        pendingAction = defaultAction,
        availability = availability,
    )
}

enum class StartWorkSource {
    HomeTopLevel,
    HomeWorkspaceDetail,
    ChatTab,
    FilesTab,
    TerminalTab,
    OtherTab,
}

enum class StartWorkAction {
    NewChat,
    Files,
    Terminal,
    BrowseSessions,
    ChooseAnotherTarget,
}

fun startWorkContextFor(tab: TabInstance?): StartWorkContext {
    if (tab == null || tab.isPinnedHome) {
        return StartWorkContext(
            source = StartWorkSource.HomeTopLevel,
            selection = StartWorkSelection.NeedsSelection,
            defaultAction = StartWorkAction.ChooseAnotherTarget,
        )
    }
    val serverRef = tab.serverRef
    val workspaceKey = tab.workspaceKey
    return StartWorkContext(
        source = sourceForRoute(tab.startRoute),
        selection = if (serverRef != null && workspaceKey != null) {
            StartWorkSelection.Selected(StartWorkTarget(serverRef, workspaceKey))
        } else {
            StartWorkSelection.NeedsSelection
        },
        defaultAction = if (serverRef == null || workspaceKey == null) {
            StartWorkAction.ChooseAnotherTarget
        } else {
            null
        },
    )
}

fun startWorkContextForHomeDetail(target: StartWorkTarget): StartWorkContext = StartWorkContext(
    source = StartWorkSource.HomeWorkspaceDetail,
    selection = StartWorkSelection.Selected(target),
)

internal fun deriveStartWorkPickerTargets(
    repositories: List<ScopedHomeRepositoryState>,
): List<StartWorkTarget> = repositories.flatMap { repository ->
    repository.state.snapshot.sessions.values.map { session ->
        StartWorkTarget(repository.serverRef, session.workspace.key)
    }
}.distinct()

internal data class StartWorkPickerState(
    val selectedEndpointKey: String?,
    val query: String = "",
)

internal fun StartWorkPickerState.filteredTargets(
    groups: List<StartWorkPickerGroup>,
): List<StartWorkTarget> {
    val group = groups.firstOrNull { it.server.endpointKey == selectedEndpointKey } ?: return emptyList()
    val needle = query.trim()
    return group.targets.filter { target ->
        target.workspaceKey == WorkspaceKey.Global || needle.isEmpty() ||
            target.workspaceKey.pickerSearchText().contains(needle, ignoreCase = true)
    }
}

private fun WorkspaceKey.pickerSearchText(): String = when (this) {
    is WorkspaceKey.Directory -> "$value ${value.trimEnd('/').substringAfterLast('/')}"
    WorkspaceKey.Global -> "global server root no directory"
    is WorkspaceKey.SessionScoped -> sessionId.value
}

private fun sourceForRoute(route: String): StartWorkSource = when {
    route.startsWith("chat/") -> StartWorkSource.ChatTab
    route.startsWith("files") -> StartWorkSource.FilesTab
    route.startsWith("terminal/") -> StartWorkSource.TerminalTab
    else -> StartWorkSource.OtherTab
}
