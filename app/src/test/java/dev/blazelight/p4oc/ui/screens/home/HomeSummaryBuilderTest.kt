package dev.blazelight.p4oc.ui.screens.home

import dev.blazelight.p4oc.core.datastore.SavedServerRegistry
import dev.blazelight.p4oc.core.network.ConnectionState
import dev.blazelight.p4oc.domain.server.ServerRef
import dev.blazelight.p4oc.domain.server.WorkspaceKey
import dev.blazelight.p4oc.ui.navigation.Screen
import dev.blazelight.p4oc.ui.tabs.TabInstance
import dev.blazelight.p4oc.ui.tabs.TabState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeSummaryBuilderTest {
    @Test
    fun `bounded summary uses open tabs without chat histories`() {
        val server = SavedServerRegistry.fromConnection("http://alpha.example.com", "Alpha")
        val serverRef = ServerRef.fromEndpointKey(server.endpointKey, server.displayName)
        val tabs = (1..30).map { index ->
            TabInstance(
                state = TabState(
                    id = "tab-$index",
                    workspaceKey = WorkspaceKey.Directory("/repo-$index"),
                    serverRef = serverRef,
                    sessionId = "session-$index",
                    sessionTitle = "Session $index",
                ),
                startRoute = Screen.Chat.createRoute("session-$index"),
            )
        }

        val summary = HomeSummaryBuilder.build(
            savedServers = listOf(server),
            connectionStates = mapOf(server.endpointKey to ConnectionState.Connected),
            tabs = tabs,
            workspaceLimit = 5,
            openWorkLimit = 7,
        )

        assertEquals(1, summary.servers.size)
        assertEquals(30, summary.servers.single().openTabCount)
        assertEquals(7, summary.openWork.size)
        assertEquals(5, summary.workspaces.size)
        assertEquals("tab-1", summary.openWork.first().tabId)
    }

    @Test
    fun `offline server summary does not block connected server summary`() {
        val connected = SavedServerRegistry.fromConnection("http://alpha.example.com", "Alpha")
        val offline = SavedServerRegistry.fromConnection("http://beta.example.com", "Beta")
        val summary = HomeSummaryBuilder.build(
            savedServers = listOf(connected, offline),
            connectionStates = mapOf(connected.endpointKey to ConnectionState.Connected),
            tabs = emptyList(),
        )

        assertEquals(2, summary.servers.size)
        assertEquals(ConnectionState.Connected, summary.servers.first { it.displayName == "Alpha" }.connectionState)
        assertEquals(ConnectionState.Disconnected, summary.servers.first { it.displayName == "Beta" }.connectionState)
        assertTrue(summary.partialFailures.isEmpty())
    }
}
