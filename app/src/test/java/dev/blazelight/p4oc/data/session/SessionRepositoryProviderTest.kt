package dev.blazelight.p4oc.data.session

import dev.blazelight.p4oc.core.network.ConnectionState
import dev.blazelight.p4oc.core.network.OpenCodeApi
import dev.blazelight.p4oc.core.network.ServerConnectionRegistry
import dev.blazelight.p4oc.data.remote.mapper.MessageMapper
import dev.blazelight.p4oc.data.server.ActiveServerApiProvider
import dev.blazelight.p4oc.domain.model.Message
import dev.blazelight.p4oc.domain.model.MessageWithParts
import dev.blazelight.p4oc.domain.model.OpenCodeEvent
import dev.blazelight.p4oc.domain.model.Session
import dev.blazelight.p4oc.domain.model.TokenUsage
import dev.blazelight.p4oc.domain.server.ScopedEvent
import dev.blazelight.p4oc.domain.server.ServerGeneration
import dev.blazelight.p4oc.domain.server.ServerRef
import dev.blazelight.p4oc.domain.session.SessionId
import dev.blazelight.p4oc.domain.workspace.Workspace
import io.mockk.any
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRepositoryProviderTest {
    private val server = ServerRef.fromEndpointKey("http://fake.test")
    private val workspace = Workspace(server = server, directory = "/repo")
    private val generation = ServerGeneration(1)

    @Test
    fun `acquire reuses repository for same workspace generation`() {
        val provider = provider()

        val first = provider.acquire(workspace, generation)
        val second = provider.acquire(workspace, generation)

        assertSame(first.repository, second.repository)
        assertSame(first.workspaceClient, second.workspaceClient)
    }

    @Test
    fun `repository remains retained until final matching release`() {
        val provider = provider()
        val first = provider.acquire(workspace, generation)
        provider.acquire(workspace, generation)

        provider.release(workspace, generation)
        val afterSingleRelease = provider.acquire(workspace, generation)

        assertSame(first.repository, afterSingleRelease.repository)
    }

    @Test
    fun `final release closes repository and next acquire creates replacement`() {
        val provider = provider()
        val first = provider.acquire(workspace, generation)

        provider.release(workspace, generation)
        val second = provider.acquire(workspace, generation)

        assertNotSame(first.repository, second.repository)
        assertNotSame(first.workspaceClient, second.workspaceClient)
    }

    @Test
    fun `different generation gets separate repository`() {
        val provider = provider()

        val first = provider.acquire(workspace, generation)
        val second = provider.acquire(workspace, ServerGeneration(2))

        assertNotSame(first.repository, second.repository)
        assertNotSame(first.workspaceClient, second.workspaceClient)
    }

    @Test
    fun `same directory on different servers gets separate repositories`() {
        val provider = provider()
        val otherServer = ServerRef.fromEndpoint("http://other.test:4096")
        val otherWorkspace = Workspace(server = otherServer, directory = workspace.directory.orEmpty())

        val first = provider.acquire(workspace, generation)
        val second = provider.acquire(otherWorkspace, generation)

        assertNotSame(first.repository, second.repository)
        assertNotSame(first.workspaceClient, second.workspaceClient)
    }

    @Test
    fun `reconnect generation recreates only affected server workspace owner`() {
        val provider = provider()
        val otherServer = ServerRef.fromEndpoint("http://other.test:4096")
        val otherWorkspace = Workspace(server = otherServer, directory = workspace.directory.orEmpty())
        val first = provider.acquire(workspace, generation)
        val other = provider.acquire(otherWorkspace, generation)

        provider.release(workspace, generation)
        val afterReconnect = provider.acquire(workspace, ServerGeneration(2))
        val otherAgain = provider.acquire(otherWorkspace, generation)

        assertNotSame(first.repository, afterReconnect.repository)
        assertSame(other.repository, otherAgain.repository)
    }

    @Test
    fun `provider routes scoped events to shared repository`() = runTest {
        val event = sessionCreatedEvent("s1")
        val provider = provider(
            scopedEvents = flowOf(
                ScopedEvent(
                    serverRef = server,
                    generation = generation,
                    workspaceKey = workspace.key,
                    event = event,
                ),
            ),
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        val lease = provider.acquire(workspace, generation)
        testScheduler.advanceUntilIdle()

        assertTrue(lease.repository.state.value is RepoState.Hydrating)
    }

    @Test
    fun `scoped events from a different server are not delivered`() = runTest {
        val otherServer = ServerRef.fromEndpoint("http://other.test:4096")
        val provider = provider(
            scopedEvents = flowOf(
                ScopedEvent(
                    serverRef = otherServer,
                    generation = generation,
                    workspaceKey = workspace.key,
                    event = OpenCodeEvent.MessageUpdated(assistantMessage("m1")),
                ),
            ),
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        val lease = provider.acquire(workspace, generation)
        testScheduler.advanceUntilIdle()

        // The event targets another server's repository; this workspace must not ingest it.
        assertEquals(
            emptyList<MessageWithParts>(),
            lease.repository.messages(SessionId("s1")).value,
        )
    }

    @Test
    fun `throwing event does not permanently kill workspace event collection`() = runTest {
        val bad = mockk<Message> { every { sessionID } throws RuntimeException("boom") }
        val provider = provider(
            scopedEvents = flowOf(
                scoped(OpenCodeEvent.MessageUpdated(bad)),
                scoped(OpenCodeEvent.MessageUpdated(assistantMessage("m1"))),
            ),
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        val lease = provider.acquire(workspace, generation)
        testScheduler.advanceUntilIdle()

        assertEquals(
            listOf("m1"),
            lease.repository.messages(SessionId("s1")).value.map { it.message.id },
        )
    }

    @Test
    fun `initial already-Connected registry state does not emit a redundant reconnect`() = runTest {
        val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Connected)
        val api = mockk<OpenCodeApi>(relaxed = true)
        val provider = provider(
            api = api,
            connectionState = connectionState,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        val lease = provider.acquire(workspace, generation)
        lease.repository.acquireSession(SessionId("s1"))
        testScheduler.advanceUntilIdle()

        // The registry is already Connected when the collector starts: there is no non-Connected ->
        // Connected transition, so the repository must not run reconnect message recovery.
        coVerify(exactly = 0) { api.getMessages("s1", 100, null, "/repo", null) }
    }

    @Test
    fun `reconnect delivers message recovery on exact non-Connected to Connected transition`() = runTest {
        val api = mockk<OpenCodeApi>(relaxed = true)
        val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
        val provider = provider(
            api = api,
            connectionState = connectionState,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        val lease = provider.acquire(workspace, generation)
        lease.repository.acquireSession(SessionId("s1"))
        testScheduler.advanceUntilIdle()

        connectionState.value = ConnectionState.Connected
        testScheduler.advanceUntilIdle()

        coVerify(exactly = 1) { api.getMessages("s1", 100, null, "/repo", null) }
    }

    @Test
    fun `reconnect recovery repeats on every non-Connected to Connected transition`() = runTest {
        val api = mockk<OpenCodeApi>(relaxed = true)
        val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
        val provider = provider(
            api = api,
            connectionState = connectionState,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        val lease = provider.acquire(workspace, generation)
        lease.repository.acquireSession(SessionId("s1"))
        testScheduler.advanceUntilIdle()

        connectionState.value = ConnectionState.Connected
        testScheduler.advanceUntilIdle()
        connectionState.value = ConnectionState.Error("dropped")
        testScheduler.advanceUntilIdle()
        connectionState.value = ConnectionState.Connected
        testScheduler.advanceUntilIdle()

        // Message recovery must not be suppressed after the first reconnect (issue #14).
        coVerify(atLeast = 2) { api.getMessages("s1", 100, null, "/repo", null) }
    }

    @Test
    fun `release cancels event and reconnect delivery for the repository`() = runTest {
        val api = mockk<OpenCodeApi>(relaxed = true)
        val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
        val provider = provider(
            api = api,
            connectionState = connectionState,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        val lease = provider.acquire(workspace, generation)
        lease.repository.acquireSession(SessionId("s1"))
        provider.release(workspace, generation)
        testScheduler.advanceUntilIdle()

        connectionState.value = ConnectionState.Connected
        testScheduler.advanceUntilIdle()

        // The released repository's reconnect job was cancelled; it must not recover messages.
        coVerify(exactly = 0) { api.getMessages(any(), any(), any(), any(), any()) }
    }

    private fun provider(
        scopedEvents: Flow<ScopedEvent> = emptyFlow(),
        connectionState: StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.Disconnected),
        api: OpenCodeApi = mockk<OpenCodeApi>(relaxed = true),
        dispatcher: CoroutineDispatcher = StandardTestDispatcher(),
    ): SessionRepositoryProvider = SessionRepositoryProvider(
        activeServerApiProvider = ActiveServerApiProvider { _, _ -> api },
        messageMapper = MessageMapper(Json { ignoreUnknownKeys = true }),
        serverConnectionRegistry = mockk<ServerConnectionRegistry> {
            every { events(any()) } returns scopedEvents
            every { connectionState(any(), ServerGeneration(1)) } returns connectionState
            every { connectionState(any(), ServerGeneration(2)) } returns connectionState
        },
        dispatcher = dispatcher,
    )

    private fun scoped(event: OpenCodeEvent): ScopedEvent = ScopedEvent(
        serverRef = server,
        generation = generation,
        workspaceKey = workspace.key,
        event = event,
    )

    private fun sessionCreatedEvent(id: String): OpenCodeEvent.SessionCreated = OpenCodeEvent.SessionCreated(
        session = Session(
            id = id,
            projectID = "project-$id",
            directory = workspace.directory.orEmpty(),
            title = id,
            version = "1",
            createdAt = 1L,
            updatedAt = 1L,
        ),
    )

    private fun assistantMessage(id: String): Message.Assistant = Message.Assistant(
        id = id,
        sessionID = "s1",
        createdAt = 1L,
        parentID = "",
        providerID = "provider",
        modelID = "model",
        mode = "chat",
        agent = "assistant",
        cost = 0.0,
        tokens = TokenUsage(input = 0, output = 0),
    )

}
