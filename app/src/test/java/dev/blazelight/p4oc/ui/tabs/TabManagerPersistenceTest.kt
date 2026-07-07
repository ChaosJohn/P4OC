package dev.blazelight.p4oc.ui.tabs

import dev.blazelight.p4oc.core.datastore.PersistedTab
import dev.blazelight.p4oc.core.datastore.PersistedTabState
import dev.blazelight.p4oc.core.datastore.PersistedWorkspaceKey
import dev.blazelight.p4oc.domain.server.ServerRef
import dev.blazelight.p4oc.domain.server.WorkspaceKey
import dev.blazelight.p4oc.ui.navigation.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TabManagerPersistenceTest {
    private val server = ServerRef.fromEndpointKey("http://localhost:4096")

    @Test
    fun `saveState writes versioned tabs with server endpoint key`() {
        val manager = TabManager()
        val tab = manager.createTab(
            startRoute = Screen.Sessions.route,
            workspaceKey = WorkspaceKey.Global,
            focus = true,
        )
        manager.updateTabWorkspace(tab.id, WorkspaceKey.Directory("/repo/a"))
        manager.updateTabSession(tab.id, "s1", "Title")

        val saved = manager.saveState(server)!!

        assertEquals(PersistedTabState.CURRENT_VERSION, saved.version)
        assertEquals(server.endpointKey, saved.serverEndpointKey)
        assertEquals(tab.id, saved.activeTabId)
        assertEquals("s1", saved.tabs.single().sessionId)
        assertEquals(PersistedWorkspaceKey.Type.DIRECTORY, saved.tabs.single().workspaceKey?.type)
        assertEquals("/repo/a", saved.tabs.single().workspaceKey?.value)
    }

    @Test
    fun `restoreState restores session tab as chat route in same workspace`() {
        val manager = TabManager()
        val state = PersistedTabState(
            serverEndpointKey = server.endpointKey,
            activeTabId = "tab-1",
            tabs = listOf(
                PersistedTab(
                    id = "tab-1",
                    startRoute = Screen.Sessions.route,
                    sessionId = "session with space",
                    sessionTitle = "Chat",
                    workspaceKey = PersistedWorkspaceKey(PersistedWorkspaceKey.Type.DIRECTORY, "/repo/a b"),
                ),
            ),
        )

        val result = manager.restoreState(state, server)

        assertTrue(result is RestoreResult.Restored)
        assertEquals("tab-1", manager.activeTabId.value)
        assertEquals("session with space", manager.tabs.value.single().sessionId)
        assertEquals("/repo/a b", manager.tabs.value.single().workspaceDirectory)
        assertEquals("chat/session%20with%20space", manager.tabs.value.single().startRoute)
    }

    @Test
    fun `restoreState drops tab with missing workspace key instead of restoring as global`() {
        val manager = TabManager()
        val state = PersistedTabState(
            serverEndpointKey = server.endpointKey,
            activeTabId = "ambiguous-tab",
            tabs = listOf(
                PersistedTab(
                    id = "ambiguous-tab",
                    startRoute = Screen.Sessions.route,
                    workspaceKey = null,
                ),
            ),
        )

        val result = manager.restoreState(state, server)

        assertTrue(result is RestoreResult.Empty)
        assertFalse(manager.hasTabs())
    }

    @Test
    fun `restoreState keeps valid tabs and falls back active tab when ambiguous active tab is dropped`() {
        val manager = TabManager()
        val state = PersistedTabState(
            serverEndpointKey = server.endpointKey,
            activeTabId = "ambiguous-tab",
            tabs = listOf(
                PersistedTab(
                    id = "ambiguous-tab",
                    startRoute = Screen.Sessions.route,
                    workspaceKey = null,
                ),
                PersistedTab(
                    id = "directory-tab",
                    startRoute = Screen.Sessions.route,
                    workspaceKey = PersistedWorkspaceKey(PersistedWorkspaceKey.Type.DIRECTORY, "/repo/valid"),
                ),
                PersistedTab(
                    id = "global-tab",
                    startRoute = Screen.Sessions.route,
                    workspaceKey = PersistedWorkspaceKey(PersistedWorkspaceKey.Type.GLOBAL),
                ),
            ),
        )

        val result = manager.restoreState(state, server)

        assertTrue(result is RestoreResult.Restored)
        assertEquals(2, (result as RestoreResult.Restored).count)
        assertEquals(listOf("directory-tab", "global-tab"), manager.tabs.value.map { it.id })
        assertEquals("directory-tab", manager.activeTabId.value)
        assertEquals("/repo/valid", manager.tabs.value[0].workspaceDirectory)
        assertEquals(WorkspaceKey.Global, manager.tabs.value[1].workspaceKey)
    }

    @Test
    fun `restoreState rejects mismatched active server without tabs`() {
        val manager = TabManager()
        val state = PersistedTabState(
            serverEndpointKey = "http://old.example",
            activeTabId = "tab-1",
            tabs = listOf(PersistedTab(id = "tab-1", startRoute = Screen.Sessions.route)),
        )

        val result = manager.restoreState(state, server)

        assertTrue(result is RestoreResult.ServerMismatch)
        assertFalse(manager.hasTabs())
    }

    @Test
    fun `restoreState rejects unsupported version`() {
        val manager = TabManager()
        val state = PersistedTabState(
            version = PersistedTabState.CURRENT_VERSION + 1,
            serverEndpointKey = server.endpointKey,
            activeTabId = "tab-1",
            tabs = listOf(PersistedTab(id = "tab-1", startRoute = Screen.Sessions.route)),
        )

        val result = manager.restoreState(state, server)

        assertTrue(result is RestoreResult.VersionMismatch)
        assertFalse(manager.hasTabs())
    }

    @Test
    fun `terminal routes are not persisted as resurrectable tabs`() {
        val manager = TabManager()
        manager.createTab(
            startRoute = Screen.Terminal.createRoute("pty-1"),
            workspaceKey = WorkspaceKey.Global,
            focus = true,
        )

        val saved = manager.saveState(server)!!

        assertEquals(Screen.Sessions.route, saved.tabs.single().startRoute)
    }
}
