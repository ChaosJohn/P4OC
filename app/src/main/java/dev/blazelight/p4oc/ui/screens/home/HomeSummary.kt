package dev.blazelight.p4oc.ui.screens.home

import dev.blazelight.p4oc.core.datastore.SavedServer
import dev.blazelight.p4oc.core.network.ConnectionState
import dev.blazelight.p4oc.domain.server.ServerRef
import dev.blazelight.p4oc.domain.server.WorkspaceKey
import dev.blazelight.p4oc.ui.tabs.TabInstance

data class ServerSummary(
    val serverRef: ServerRef,
    val displayName: String,
    val connectionState: ConnectionState,
    val openTabCount: Int,
)

data class WorkspaceSummary(
    val serverRef: ServerRef,
    val workspaceKey: WorkspaceKey,
    val openTabCount: Int,
)

data class OpenWorkSummary(
    val tabId: String,
    val serverRef: ServerRef,
    val workspaceKey: WorkspaceKey,
    val route: String,
)

data class HomeSummaryState(
    val servers: List<ServerSummary>,
    val workspaces: List<WorkspaceSummary>,
    val openWork: List<OpenWorkSummary>,
    val partialFailures: List<String> = emptyList(),
)

object HomeSummaryBuilder {
    fun build(
        savedServers: List<SavedServer>,
        connectionStates: Map<String, ConnectionState>,
        tabs: List<TabInstance>,
        workspaceLimit: Int = 12,
        openWorkLimit: Int = 24,
    ): HomeSummaryState {
        val workTabs = tabs.filterNot { it.isPinnedHome }
        val failures = mutableListOf<String>()
        val serverSummaries = savedServers.mapNotNull { saved ->
            runCatching {
                val serverRef = ServerRef.fromEndpointKey(saved.endpointKey, saved.displayName)
                ServerSummary(
                    serverRef = serverRef,
                    displayName = saved.displayName,
                    connectionState = connectionStates[saved.endpointKey] ?: ConnectionState.Disconnected,
                    openTabCount = workTabs.count { it.serverEndpointKey == saved.endpointKey },
                )
            }.getOrElse {
                failures += saved.endpointKey
                null
            }
        }
        val openWork = workTabs.mapNotNull { tab ->
            val serverRef = tab.serverRef ?: return@mapNotNull null
            val workspaceKey = tab.workspaceKey ?: return@mapNotNull null
            OpenWorkSummary(
                tabId = tab.id,
                serverRef = serverRef,
                workspaceKey = workspaceKey,
                route = tab.startRoute,
            )
        }.take(openWorkLimit)
        val workspaces = openWork
            .groupBy { it.serverRef.endpointKey to it.workspaceKey }
            .values
            .map { grouped ->
                val first = grouped.first()
                WorkspaceSummary(
                    serverRef = first.serverRef,
                    workspaceKey = first.workspaceKey,
                    openTabCount = grouped.size,
                )
            }
            .take(workspaceLimit)
        return HomeSummaryState(
            servers = serverSummaries,
            workspaces = workspaces,
            openWork = openWork,
            partialFailures = failures,
        )
    }
}
