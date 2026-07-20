package dev.blazelight.p4oc.data.session

import dev.blazelight.p4oc.core.network.ConnectionState
import dev.blazelight.p4oc.core.network.OpenCodeApi
import dev.blazelight.p4oc.data.remote.dto.MessageInfoDto
import dev.blazelight.p4oc.data.remote.dto.MessageTimeDto
import dev.blazelight.p4oc.data.remote.dto.MessageWrapperDto
import dev.blazelight.p4oc.data.remote.dto.PartDto
import dev.blazelight.p4oc.data.remote.mapper.MessageMapper
import dev.blazelight.p4oc.data.server.ActiveServerApiProvider
import dev.blazelight.p4oc.data.workspace.WorkspaceClient
import dev.blazelight.p4oc.domain.model.Message
import dev.blazelight.p4oc.domain.model.OpenCodeEvent
import dev.blazelight.p4oc.domain.model.Part
import dev.blazelight.p4oc.domain.model.TokenUsage
import dev.blazelight.p4oc.domain.server.ServerGeneration
import dev.blazelight.p4oc.domain.server.ServerRef
import dev.blazelight.p4oc.domain.session.SessionId
import dev.blazelight.p4oc.domain.workspace.Workspace
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionRepositoryRecoveryTest {

    private val sessionId = SessionId("s1")
    private val json = Json { ignoreUnknownKeys = true }
    private val mapper = MessageMapper(json)

    @Test
    fun `reconnect recovers messages for actively leased session with bounded default limit`() = runTest {
        val (repo, api, _) = repository()

        repo.acceptEvent(OpenCodeEvent.MessageUpdated(assistantMessage("m1", createdAt = 1)))
        val lease = repo.acquireSession(sessionId)
        advanceUntilIdle()

        coEvery { api.getMessages("s1", 100, null, "/test", null) } returns listOf(
            assistantWrapper("m1", createdAt = 1, partText = "recovered"),
        )

        repo.acceptEvent(OpenCodeEvent.Connected)
        advanceUntilIdle()

        coVerify(exactly = 1) { api.getMessages("s1", 100, null, "/test", null) }
        val part = repo.messages(sessionId).value.single().parts.single() as Part.Text
        assertEquals("recovered", part.text)
        lease.close()
    }

    @Test
    fun `reconnect does not fetch sessions without an active lease`() = runTest {
        val (repo, api, _) = repository()

        repo.messages(sessionId)
        repo.acceptEvent(OpenCodeEvent.MessageUpdated(assistantMessage("m1", createdAt = 1)))
        advanceUntilIdle()

        repo.acceptEvent(OpenCodeEvent.Connected)
        advanceUntilIdle()

        coVerify(exactly = 0) { api.getMessages(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `reconnect recovery repairs authoritative deletion`() = runTest {
        val (repo, api, _) = repository()

        repo.acceptEvent(OpenCodeEvent.MessageUpdated(assistantMessage("m1", createdAt = 1)))
        val lease = repo.acquireSession(sessionId)
        advanceUntilIdle()

        coEvery { api.getMessages("s1", 100, null, "/test", null) } returns emptyList()

        repo.acceptEvent(OpenCodeEvent.Connected)
        advanceUntilIdle()

        assertTrue(repo.messages(sessionId).value.isEmpty())
        lease.close()
    }

    @Test
    fun `reconnect recovery repairs stale part authoritatively`() = runTest {
        val (repo, api, _) = repository()

        repo.acceptEvent(OpenCodeEvent.MessageUpdated(assistantMessage("m1", createdAt = 1)))
        repo.acceptEvent(
            OpenCodeEvent.MessagePartUpdated(
                textPart("p1", "m1", "stale"),
                delta = null,
            )
        )
        val lease = repo.acquireSession(sessionId)
        advanceUntilIdle()

        coEvery { api.getMessages("s1", 100, null, "/test", null) } returns listOf(
            assistantWrapper("m1", createdAt = 1, partText = "authoritative"),
        )

        repo.acceptEvent(OpenCodeEvent.Connected)
        advanceUntilIdle()

        val part = repo.messages(sessionId).value.single().parts.single() as Part.Text
        assertEquals("authoritative", part.text)
        lease.close()
    }

    @Test
    fun `reconnect uses largest loaded history window`() = runTest {
        val (repo, api, _) = repository()

        repo.acquireSession(sessionId)
        coEvery { api.getMessages("s1", 200, null, "/test", null) } returns (1L..200L)
            .map { assistantWrapper("m$it", createdAt = it, partText = "x") }
        repo.loadMessages(sessionId, 200)
        advanceUntilIdle()

        coEvery { api.getMessages("s1", 200, null, "/test", null) } returns (1L..200L)
            .map { assistantWrapper("m$it", createdAt = it, partText = "y") }

        repo.acceptEvent(OpenCodeEvent.Connected)
        advanceUntilIdle()

        coVerify(exactly = 1) { api.getMessages("s1", 200, null, "/test", null) }
        assertEquals(200, repo.messages(sessionId).value.size)
    }

    @Test
    fun `reconnect recovery rejects a REST result racing a newer SSE mutation`() = runTest {
        val (repo, api, _) = repository()
        val lease = repo.acquireSession(sessionId)

        // First recovery fetch is gated so an SSE mutation can land mid-flight.
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        coEvery { api.getMessages("s1", 100, null, "/test", null) } coAnswers {
            calls += 1
            if (calls == 1) {
                gate.await()
                listOf(assistantWrapper("m1", createdAt = 1, partText = "first-fetch"))
            } else {
                listOf(assistantWrapper("m2", createdAt = 2, partText = "latest"))
            }
        }

        repo.acceptEvent(OpenCodeEvent.Connected)
        advanceUntilIdle()

        // A newer SSE mutation arrives while the first fetch is still in flight.
        repo.acceptEvent(OpenCodeEvent.MessageUpdated(assistantMessage("m2", createdAt = 2)))
        gate.complete(Unit)
        advanceUntilIdle()

        // The committed state must reflect the newest server truth, not the gated first fetch.
        assertEquals(listOf("m2"), repo.messages(sessionId).value.map { it.message.id })
        lease.close()
    }

    @Test
    fun `reconnect recovery runs on every reconnect`() = runTest {
        val (repo, api, _) = repository()
        repo.acquireSession(sessionId)
        coEvery { api.getMessages("s1", 100, null, "/test", null) } returns emptyList()

        repo.acceptEvent(OpenCodeEvent.Connected)
        advanceUntilIdle()
        repo.acceptEvent(OpenCodeEvent.Connected)
        advanceUntilIdle()

        coVerify(exactly = 2) { api.getMessages("s1", 100, null, "/test", null) }
    }

    private fun repository(): Triple<SessionRepositoryImpl, OpenCodeApi, WorkspaceClient> {
        val api = mockk<OpenCodeApi>(relaxed = true)
        val client = clientFor(api)
        val repo = SessionRepositoryImpl(
            client,
            messageMapper = mapper,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        return Triple(repo, api, client)
    }

    private fun clientFor(api: OpenCodeApi): WorkspaceClient = WorkspaceClient(
        workspace = Workspace(
            server = ServerRef.fromEndpointKey("http://test.local"),
            directory = "/test",
        ),
        generation = ServerGeneration(0L),
        apiProvider = ActiveServerApiProvider { _, _ -> api },
        connectionState = MutableStateFlow(ConnectionState.Disconnected),
    )

    private fun assistantWrapper(
        id: String,
        createdAt: Long,
        partText: String? = null,
    ): MessageWrapperDto = MessageWrapperDto(
        info = MessageInfoDto(
            id = id,
            sessionID = "s1",
            time = MessageTimeDto(created = createdAt),
            role = "assistant",
            parentID = "",
            providerID = "provider",
            modelID = "model",
            agent = "assistant",
            mode = "chat",
        ),
        parts = listOfNotNull(
            partText?.let {
                PartDto(
                    id = "p-$id",
                    sessionID = "s1",
                    messageID = id,
                    type = "text",
                    text = it,
                )
            }
        ),
    )

    private fun assistantMessage(id: String, createdAt: Long): Message.Assistant = Message.Assistant(
        id = id,
        sessionID = "s1",
        createdAt = createdAt,
        parentID = "",
        providerID = "provider",
        modelID = "model",
        mode = "chat",
        agent = "assistant",
        cost = 0.0,
        tokens = TokenUsage(input = 0, output = 0),
    )

    private fun textPart(id: String, messageId: String, text: String): Part.Text = Part.Text(
        id = id,
        sessionID = "s1",
        messageID = messageId,
        text = text,
    )
}
