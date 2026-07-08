package dev.blazelight.p4oc.ui.tabs

import dev.blazelight.p4oc.domain.server.ServerRef
import dev.blazelight.p4oc.domain.server.WorkspaceKey

data class StartWorkContext(
    val source: StartWorkSource,
    val defaultServer: ServerRef?,
    val defaultWorkspace: WorkspaceKey?,
    val defaultAction: StartWorkAction? = null,
) {
    val hasExplicitTarget: Boolean get() = defaultServer != null && defaultWorkspace != null
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
            defaultServer = null,
            defaultWorkspace = null,
            defaultAction = StartWorkAction.ChooseAnotherTarget,
        )
    }
    return StartWorkContext(
        source = sourceForRoute(tab.startRoute),
        defaultServer = tab.serverRef,
        defaultWorkspace = tab.workspaceKey,
    )
}

private fun sourceForRoute(route: String): StartWorkSource = when {
    route.startsWith("chat/") -> StartWorkSource.ChatTab
    route.startsWith("files") -> StartWorkSource.FilesTab
    route.startsWith("terminal/") -> StartWorkSource.TerminalTab
    else -> StartWorkSource.OtherTab
}
