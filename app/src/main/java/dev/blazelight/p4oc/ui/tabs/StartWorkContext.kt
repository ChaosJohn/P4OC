package dev.blazelight.p4oc.ui.tabs

import dev.blazelight.p4oc.domain.server.ServerRef
import dev.blazelight.p4oc.domain.server.WorkspaceKey

data class StartWorkTarget(
    val serverRef: ServerRef,
    val workspaceKey: WorkspaceKey,
)

sealed interface StartWorkSelection {
    data class Selected(val target: StartWorkTarget) : StartWorkSelection
    data object NeedsSelection : StartWorkSelection
}

data class StartWorkContext(
    val source: StartWorkSource,
    val selection: StartWorkSelection,
    val defaultAction: StartWorkAction? = null,
) {
    val selectedTarget: StartWorkTarget?
        get() = (selection as? StartWorkSelection.Selected)?.target
}
fun StartWorkSelection.validatedAgainst(availableServers: Collection<ServerRef>): StartWorkSelection = when (this) {
    StartWorkSelection.NeedsSelection -> this
    is StartWorkSelection.Selected -> if (
        availableServers.any { it.endpointKey == target.serverRef.endpointKey }
    ) {
        this
    } else {
        StartWorkSelection.NeedsSelection
    }
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

private fun sourceForRoute(route: String): StartWorkSource = when {
    route.startsWith("chat/") -> StartWorkSource.ChatTab
    route.startsWith("files") -> StartWorkSource.FilesTab
    route.startsWith("terminal/") -> StartWorkSource.TerminalTab
    else -> StartWorkSource.OtherTab
}
