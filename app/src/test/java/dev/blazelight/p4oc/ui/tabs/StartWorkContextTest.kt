package dev.blazelight.p4oc.ui.tabs

import dev.blazelight.p4oc.domain.server.ServerRef
import dev.blazelight.p4oc.domain.server.WorkspaceKey
import dev.blazelight.p4oc.ui.navigation.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StartWorkContextTest {
    private val server = ServerRef.fromEndpointKey("http://alpha.example:4096")
    private val workspace = WorkspaceKey.Directory("/repo")

    @Test
    fun `Home defaults to target picker with no implicit server or workspace`() {
        val context = startWorkContextFor(TabInstance.home())

        assertEquals(StartWorkSource.HomeTopLevel, context.source)
        assertNull(context.defaultServer)
        assertNull(context.defaultWorkspace)
        assertEquals(StartWorkAction.ChooseAnotherTarget, context.defaultAction)
        assertFalse(context.hasExplicitTarget)
    }

    @Test
    fun `chat tab defaults to the tab server and workspace`() {
        val tab = TabInstance(
            state = TabState(workspaceKey = workspace, serverRef = server, sessionId = "s1"),
            startRoute = Screen.Chat.createRoute("s1"),
        )

        val context = startWorkContextFor(tab)

        assertEquals(StartWorkSource.ChatTab, context.source)
        assertEquals(server, context.defaultServer)
        assertEquals(workspace, context.defaultWorkspace)
        assertTrue(context.hasExplicitTarget)
    }

    @Test
    fun `files and terminal tabs default to their tab target`() {
        val files = startWorkContextFor(
            TabInstance(TabState(workspaceKey = workspace, serverRef = server), Screen.Files.route),
        )
        val terminal = startWorkContextFor(
            TabInstance(TabState(workspaceKey = workspace, serverRef = server), Screen.Terminal.createRoute("pty-1")),
        )

        assertEquals(StartWorkSource.FilesTab, files.source)
        assertEquals(server, files.defaultServer)
        assertEquals(workspace, files.defaultWorkspace)
        assertEquals(StartWorkSource.TerminalTab, terminal.source)
        assertEquals(server, terminal.defaultServer)
        assertEquals(workspace, terminal.defaultWorkspace)
    }
}
