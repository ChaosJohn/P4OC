package dev.blazelight.p4oc.ui.tabs

import dev.blazelight.p4oc.data.session.RepoState
import dev.blazelight.p4oc.data.session.Snapshot
import dev.blazelight.p4oc.domain.model.Session
import dev.blazelight.p4oc.domain.server.ServerRef
import dev.blazelight.p4oc.domain.server.WorkspaceKey
import dev.blazelight.p4oc.domain.session.SessionId
import dev.blazelight.p4oc.domain.session.WorkspaceSession
import dev.blazelight.p4oc.domain.workspace.Workspace
import dev.blazelight.p4oc.ui.navigation.Screen
import dev.blazelight.p4oc.ui.screens.home.ScopedHomeRepositoryState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StartWorkContextTest {
    @Test
    fun `picker targets derive distinct workspace ownership from repository snapshots`() {
        val alpha = ServerRef.fromEndpointKey("http://alpha.test", "Alpha")
        val beta = ServerRef.fromEndpointKey("http://beta.test", "Beta")
        val repositories = listOf(
            scopedRepository(alpha, session(alpha, "a1", "/repo"), session(alpha, "a2", "/repo")),
            scopedRepository(beta, session(beta, "b1", "/repo")),
        )

        val targets = deriveStartWorkPickerTargets(repositories)

        assertEquals(
            listOf(
                StartWorkTarget(alpha, WorkspaceKey.Directory("/repo")),
                StartWorkTarget(beta, WorkspaceKey.Directory("/repo")),
            ),
            targets,
        )
    }

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
    fun `removed server preserves exact target and pending action without retargeting`() {
        val target = StartWorkTarget(alpha, workspace)
        val context = StartWorkContext(
            source = StartWorkSource.FilesTab,
            selection = StartWorkSelection.Selected(target),
            defaultAction = StartWorkAction.Files,
        )

        val resolved = context.resolve(
            availableServers = listOf(beta),
            availableWorkspaces = listOf(StartWorkTarget(beta, workspace)),
            connectionStates = mapOf(beta.endpointKey to StartWorkConnectionState.Online),
        )

        assertEquals(StartWorkAvailability.ServerRemoved, resolved.availability)
        assertEquals(context, resolved.context)
        assertEquals(target, resolved.target)
        assertEquals(StartWorkAction.Files, resolved.pendingAction)
    }

    @Test
    fun `missing workspace preserves exact target and pending action`() {
        val target = StartWorkTarget(alpha, workspace)
        val context = StartWorkContext(
            source = StartWorkSource.ChatTab,
            selection = StartWorkSelection.Selected(target),
            defaultAction = StartWorkAction.NewChat,
        )

        val resolved = context.resolve(
            availableServers = listOf(alpha),
            availableWorkspaces = emptyList(),
            connectionStates = mapOf(alpha.endpointKey to StartWorkConnectionState.Online),
        )

        assertEquals(StartWorkAvailability.WorkspaceMissing, resolved.availability)
        assertEquals(context, resolved.context)
        assertEquals(target, resolved.target)
        assertEquals(StartWorkAction.NewChat, resolved.pendingAction)
    }

    @Test
    fun `availability distinguishes no servers offline and authentication recovery`() {
        val target = StartWorkTarget(alpha, workspace)
        val context = StartWorkContext(
            source = StartWorkSource.TerminalTab,
            selection = StartWorkSelection.Selected(target),
            defaultAction = StartWorkAction.Terminal,
        )
        val cases = listOf(
            Triple(emptyList<ServerRef>(), emptyMap(), StartWorkAvailability.NoServers),
            Triple(
                listOf(alpha),
                mapOf(alpha.endpointKey to StartWorkConnectionState.Offline),
                StartWorkAvailability.Offline,
            ),
            Triple(
                listOf(alpha),
                mapOf(alpha.endpointKey to StartWorkConnectionState.AuthRequired),
                StartWorkAvailability.AuthRequired,
            ),
        )

        cases.forEach { (servers, states, expectedAvailability) ->
            val resolved = context.resolve(
                availableServers = servers,
                availableWorkspaces = listOf(target),
                connectionStates = states,
            )

            assertEquals(expectedAvailability, resolved.availability)
            assertEquals(context, resolved.context)
            assertEquals(target, resolved.target)
            assertEquals(StartWorkAction.Terminal, resolved.pendingAction)
        }
    }

    @Test
    fun `explicit global target is ready without a directory workspace entry`() {
        val target = StartWorkTarget(alpha, WorkspaceKey.Global)
        val context = StartWorkContext(
            source = StartWorkSource.HomeWorkspaceDetail,
            selection = StartWorkSelection.Selected(target),
            defaultAction = StartWorkAction.BrowseSessions,
        )

        val resolved = context.resolve(
            availableServers = listOf(alpha),
            availableWorkspaces = emptyList(),
            connectionStates = mapOf(alpha.endpointKey to StartWorkConnectionState.Online),
        )

        assertEquals(StartWorkAvailability.Ready, resolved.availability)
        assertEquals(target, resolved.target)
        assertEquals(StartWorkAction.BrowseSessions, resolved.pendingAction)
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

    @Test
    fun `picker groups put exact global target first and include unopened Home workspaces`() {
        val unopened = StartWorkTarget(alpha, WorkspaceKey.Directory("/unopened"))
        val groups = buildStartWorkPickerGroups(
            servers = listOf(Triple(alpha.endpointKey, "Alpha", "A")),
            openTargets = emptyList(),
            knownHomeTargets = listOf(unopened),
        )

        assertEquals(alpha.endpointKey, groups.single().server.endpointKey)
        assertEquals(WorkspaceKey.Global, groups.single().targets.first().workspaceKey)
        assertEquals(unopened.workspaceKey, groups.single().targets[1].workspaceKey)
    }

    @Test
    fun `picker deduplicates within a server without merging same path across servers`() {
        val alphaTarget = StartWorkTarget(alpha, workspace)
        val betaTarget = StartWorkTarget(beta, workspace)
        val groups = buildStartWorkPickerGroups(
            servers = listOf(
                Triple(alpha.endpointKey, "Alpha", "A"),
                Triple(beta.endpointKey, "Beta", "B"),
            ),
            openTargets = listOf(alphaTarget, betaTarget),
            knownHomeTargets = listOf(alphaTarget),
        )

        assertEquals(listOf(WorkspaceKey.Global, workspace), groups[0].targets.map { it.workspaceKey })
        assertEquals(listOf(WorkspaceKey.Global, workspace), groups[1].targets.map { it.workspaceKey })
        assertTrue(groups[0].targets[1].serverRef != groups[1].targets[1].serverRef)
    }

    @Test
    fun `picker filter scopes directories to selected server and pins global`() {
        val groups = buildStartWorkPickerGroups(
            servers = listOf(
                Triple(alpha.endpointKey, "Alpha", "A"),
                Triple(beta.endpointKey, "Beta", "B"),
            ),
            openTargets = listOf(
                StartWorkTarget(alpha, WorkspaceKey.Directory("/repo/needle-alpha")),
                StartWorkTarget(beta, WorkspaceKey.Directory("/repo/needle-beta")),
            ),
            knownHomeTargets = emptyList(),
        )

        val filtered = StartWorkPickerState(alpha.endpointKey, "needle").filteredTargets(groups)

        assertEquals(WorkspaceKey.Global, filtered.first().workspaceKey)
        assertEquals(
            listOf(WorkspaceKey.Global, WorkspaceKey.Directory("/repo/needle-alpha")),
            filtered.map { it.workspaceKey },
        )
        assertTrue(filtered.all { it.serverRef.endpointKey == alpha.endpointKey })
    }

    @Test
    fun `picker filter matches workspace path case insensitively`() {
        val groups = buildStartWorkPickerGroups(
            servers = listOf(Triple(alpha.endpointKey, "Alpha", "A")),
            openTargets = listOf(
                StartWorkTarget(alpha, WorkspaceKey.Directory("/Projects/Android/P4OC")),
                StartWorkTarget(alpha, WorkspaceKey.Directory("/Projects/Other")),
            ),
            knownHomeTargets = emptyList(),
        )

        val filtered = StartWorkPickerState(alpha.endpointKey, "p4oc").filteredTargets(groups)

        assertEquals(
            listOf(WorkspaceKey.Global, WorkspaceKey.Directory("/Projects/Android/P4OC")),
            filtered.map { it.workspaceKey },
        )
    }

    @Test
    fun `scoped actions retain new chat files terminal order`() {
        assertEquals(
            listOf(StartWorkAction.NewChat, StartWorkAction.Files, StartWorkAction.Terminal),
            startWorkScopedActionOrder,
        )
    }

    private fun scopedRepository(
        serverRef: ServerRef,
        vararg sessions: WorkspaceSession,
    ) = ScopedHomeRepositoryState(
        serverRef,
        RepoState.Live(Snapshot(sessions = sessions.associateBy { it.id.value })),
    )

    private fun session(
        serverRef: ServerRef,
        id: String,
        directory: String,
    ) = WorkspaceSession(
        id = SessionId(id),
        workspace = Workspace(serverRef, directory),
        session = Session(
            id = id,
            projectID = "project-$id",
            directory = directory,
            title = id,
            version = "1",
            createdAt = 1L,
            updatedAt = 1L,
        ),
    )
}
