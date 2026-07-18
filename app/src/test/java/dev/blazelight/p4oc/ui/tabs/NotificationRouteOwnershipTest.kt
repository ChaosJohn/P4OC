package dev.blazelight.p4oc.ui.tabs

import dev.blazelight.p4oc.core.datastore.PersistedTab
import dev.blazelight.p4oc.core.datastore.PersistedTabState
import dev.blazelight.p4oc.core.datastore.PersistedWorkspaceKey
import dev.blazelight.p4oc.core.datastore.SavedServer
import dev.blazelight.p4oc.core.notification.NotificationRoute
import dev.blazelight.p4oc.domain.server.ServerRef
import dev.blazelight.p4oc.domain.server.WorkspaceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationRouteOwnershipTest {
    private val route = NotificationRoute(
        sessionId = "session",
        serverRef = ServerRef.fromEndpointKey("https://saved.example"),
        workspaceKey = WorkspaceKey.Global,
    )

    @Test
    fun `removed server notification route is rejected`() {
        assertNull(findSavedServerForNotification(route, emptyList()))
        assertNull(findSavedServerForNotification(route, listOf(server("https://other.example"))))
    }

    @Test
    fun `current saved server notification route resolves authenticated owner`() {
        val saved = server("https://saved.example")

        assertEquals(saved, findSavedServerForNotification(route, listOf(saved)))
    }

    @Test
    fun `equal session ids only match exact server and workspace tab`() {
        val manager = TabManager()
        manager.restoreState(
            state = PersistedTabState(
                serverEndpointKey = "https://saved.example",
                activeTabId = null,
                tabs = listOf(
                    PersistedTab(
                        id = "other",
                        startRoute = "chat/session",
                        sessionId = "session",
                        serverEndpointKey = "https://other.example",
                        workspaceKey = PersistedWorkspaceKey(PersistedWorkspaceKey.Type.GLOBAL),
                    ),
                    PersistedTab(
                        id = "exact",
                        startRoute = "chat/session",
                        sessionId = "session",
                        serverEndpointKey = "https://saved.example",
                        workspaceKey = PersistedWorkspaceKey(PersistedWorkspaceKey.Type.GLOBAL),
                    ),
                ),
            ),
            availableServers = mapOf(
                "https://other.example" to ServerRef.fromEndpointKey("https://other.example"),
                "https://saved.example" to ServerRef.fromEndpointKey("https://saved.example"),
            ),
        )

        assertEquals("exact", manager.findTabByNotificationRoute(route)?.id)
        assertNull(manager.findTabByNotificationRoute(route.copy(workspaceKey = WorkspaceKey.Directory("/other"))))
    }

    private fun server(endpointKey: String) = SavedServer(
        id = endpointKey,
        endpoint = endpointKey,
        endpointKey = endpointKey,
        displayName = endpointKey,
    )
}
