package dev.blazelight.p4oc.ui.tabs

import dev.blazelight.p4oc.domain.server.ServerRef
import dev.blazelight.p4oc.domain.server.WorkspaceKey
import dev.blazelight.p4oc.ui.navigation.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StartWorkContextTest {
    private val alpha = ServerRef.fromEndpointKey("http://alpha.example:4096")
    private val beta = ServerRef.fromEndpointKey("http://beta.example:4096")
    private val workspace = WorkspaceKey.Directory("/repo")

    @Test
    fun `Home with no project context requires explicit selection`() {
        val context = startWorkContextFor(TabInstance.home())

        assertEquals(StartWorkSource.HomeTopLevel, context.source)
        assertSame(StartWorkSelection.NeedsSelection, context.selection)
        assertNull(context.selectedTarget)
        assertEquals(StartWorkAction.ChooseAnotherTarget, context.defaultAction)
    }

    @Test
    fun `missing owner is rejected instead of partially defaulting`() {
        val missingServer = TabInstance(
            state = TabState(workspaceKey = workspace, serverRef = null),
            startRoute = Screen.Files.route,
        )
        val missingWorkspace = TabInstance(
            state = TabState(workspaceKey = null, serverRef = alpha),
            startRoute = Screen.Files.route,
        )

        listOf(missingServer, missingWorkspace).forEach { tab ->
            val context = startWorkContextFor(tab)
            assertSame(StartWorkSelection.NeedsSelection, context.selection)
            assertNull(context.selectedTarget)
            assertEquals(StartWorkAction.ChooseAnotherTarget, context.defaultAction)
        }
    }

    @Test
    fun `same directory on different servers remains differently owned`() {
        val alphaContext = startWorkContextFor(
            TabInstance(TabState(workspaceKey = workspace, serverRef = alpha), Screen.Files.route),
        )
        val betaContext = startWorkContextFor(
            TabInstance(TabState(workspaceKey = workspace, serverRef = beta), Screen.Files.route),
        )

        assertEquals(StartWorkTarget(alpha, workspace), alphaContext.selectedTarget)
        assertEquals(StartWorkTarget(beta, workspace), betaContext.selectedTarget)
        assertTrue(alphaContext.selectedTarget != betaContext.selectedTarget)
    }

    @Test
    fun `explicit no-project context is preserved as selected global target`() {
        val noProjectTarget = StartWorkTarget(alpha, WorkspaceKey.Global)

        val context = startWorkContextForHomeDetail(noProjectTarget)

        assertEquals(StartWorkSource.HomeWorkspaceDetail, context.source)
        assertEquals(noProjectTarget, context.selectedTarget)
        assertNull(context.defaultAction)
    }

    @Test
    fun `removed server invalidates selection instead of retargeting same directory`() {
        val selection = StartWorkSelection.Selected(StartWorkTarget(alpha, workspace))

        val validated = selection.validatedAgainst(listOf(beta))

        assertSame(StartWorkSelection.NeedsSelection, validated)
    }

    @Test
    fun `explicit no-project selection survives validation when its server remains saved`() {
        val selection = StartWorkSelection.Selected(StartWorkTarget(alpha, WorkspaceKey.Global))

        val validated = selection.validatedAgainst(listOf(alpha, beta))

        assertEquals(selection, validated)
    }

    @Test
    fun `chat files and terminal preserve their exact immutable owner`() {
        val chat = startWorkContextFor(
            TabInstance(
                state = TabState(workspaceKey = workspace, serverRef = alpha, sessionId = "s1"),
                startRoute = Screen.Chat.createRoute("s1"),
            ),
        )
        val files = startWorkContextFor(
            TabInstance(TabState(workspaceKey = workspace, serverRef = alpha), Screen.Files.route),
        )
        val terminal = startWorkContextFor(
            TabInstance(
                TabState(workspaceKey = workspace, serverRef = alpha),
                Screen.Terminal.createRoute("pty-1"),
            ),
        )

        val target = StartWorkTarget(alpha, workspace)
        assertEquals(StartWorkSource.ChatTab, chat.source)
        assertEquals(target, chat.selectedTarget)
        assertEquals(StartWorkSource.FilesTab, files.source)
        assertEquals(target, files.selectedTarget)
        assertEquals(StartWorkSource.TerminalTab, terminal.source)
        assertEquals(target, terminal.selectedTarget)
    }
}
