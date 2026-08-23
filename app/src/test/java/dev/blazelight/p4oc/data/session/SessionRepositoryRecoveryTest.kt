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
        val (repo, api, _) = repository(testScheduler)

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
        val (repo, api, _) = repository(testScheduler)

        repo.messages(sessionId)
        repo.acceptEvent(OpenCodeEvent.MessageUpdated(assistantMessage("m1", createdAt = 1)))
        advanceUntilIdle()

        repo.acceptEvent(OpenCodeEvent.Connected)
        advanceUntilIdle()

        coVerify(exactly = 0) { api.getMessages(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `reconnect recovery repairs authoritative deletion`() = runTest {
        val (repo, api, _) = repository(testScheduler)

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
        val (repo, api, _) = repository(testScheduler)

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
        val (repo, api, _) = repository(testScheduler)

        repo.acquireSession(sessionId)
        coEvery { api.getMessages("s1", 200, null, "/test", null) } returns (1L..200L)
            .map { assistantWrapper("m$it", createdAt = it, partText = "x") }
        repo.loadMessages(sessionId, 200)
        advanceUntilIdle()

        coEvery { api.getMessages("s1", 200, null, "/test", null) } returns (1L..200L)
            .map { assistantWrapper("m$it", createdAt = it, partText = "y") }

        repo.acceptEvent(OpenCodeEvent.Connected)
        advanceUntilIdle()

        // One call from the initial loadMessages(200) and one from reconnect recovery reusing the
        // largest loaded window — never a default-100 call that would drop history.
        coVerify(exactly = 2) { api.getMessages("s1", 200, null, "/test", null) }
        coVerify(exactly = 0) { api.getMessages("s1", 100, null, "/test", null) }
        assertEquals(200, repo.messages(sessionId).value.size)
    }

    @Test
    fun `reconnect recovery rejects a REST result racing a newer SSE mutation`() = runTest {
        val (repo, api, _) = repository(testScheduler)
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
        val (repo, api, _) = repository(testScheduler)
        repo.acquireSession(sessionId)
        coEvery { api.getMessages("s1", 100, null, "/test", null) } returns emptyList()

        repo.acceptEvent(OpenCodeEvent.Connected)
        advanceUntilIdle()
        repo.acceptEvent(OpenCodeEvent.Connected)
        advanceUntilIdle()

        coVerify(exactly = 2) { api.getMessages("s1", 100, null, "/test", null) }
    }

    @Test
    fun `repeated race falls back to non destructive merge preserving live SSE while importing missed REST`() =
        runTest {
            val (repo, api, _) = repository(testScheduler)
            repo.acquireSession(sessionId)
            // Live SSE state for m1 that a stale REST snapshot must never overwrite.
            repo.acceptEvent(OpenCodeEvent.MessageUpdated(assistantMessage("m1", createdAt = 1)))
            repo.acceptEvent(
                OpenCodeEvent.MessagePartUpdated(
                    textPart("p1", "m1", "live"),
                    delta = null,
                )
            )

            // Every recovery fetch is raced by a fresh live SSE mutation, forcing all three attempts
            // to Raced and finally the merge fallback.
            var calls = 0
            coEvery { api.getMessages("s1", 100, null, "/test", null) } coAnswers {
                calls += 1
                // A newer SSE mutation lands before the fetch result is committed.
                repo.acceptEvent(OpenCodeEvent.MessageUpdated(assistantMessage("live-$calls", createdAt = 10L + calls)))
                listOf(
                    assistantWrapper("m1", createdAt = 1, partText = "stale"),
                    assistantWrapper("m2", createdAt = 2, partText = "missed"),
                )
            }

            repo.acceptEvent(OpenCodeEvent.Connected)
            advanceUntilIdle()

            val messages = repo.messages(sessionId).value
            val m1 = messages.first { it.message.id == "m1" }
            // Live SSE part (p1) must retain its live text; the stale REST part (p-m1) is merged in
            // alongside it but must not replace p1.
            val livePart = m1.parts.single { it.id == "p1" } as Part.Text
            assertEquals("live", livePart.text)
            assertTrue(m1.parts.any { it.id == "p-m1" })
            // The missed REST-only message is imported.
            assertTrue(messages.any { it.message.id == "m2" })
            assertTrue(messages.any { it.message.id == "live-3" })
        }

    @Test
    fun `previous recovery job is cancelled when a new reconnect starts`() = runTest {
        val (repo, api, _) = repository(testScheduler)
        repo.acquireSession(sessionId)
        val firstGate = CompletableDeferred<Unit>()
        var calls = 0
        coEvery { api.getMessages("s1", 100, null, "/test", null) } coAnswers {
            calls += 1
            if (calls == 1) {
                firstGate.await()
                listOf(assistantWrapper("m-stale", createdAt = 1, partText = "stale"))
            } else {
                listOf(assistantWrapper("m-current", createdAt = 2, partText = "current"))
            }
        }

        repo.acceptEvent(OpenCodeEvent.Connected)
        advanceUntilIdle()
        // A second reconnect supersedes the first before its gated fetch resolves.
        repo.acceptEvent(OpenCodeEvent.Connected)
        firstGate.complete(Unit)
        advanceUntilIdle()

        // The superseded job's fetch must never commit; only the current job's result is visible.
        assertEquals(listOf("m-current"), repo.messages(sessionId).value.map { it.message.id })
    }

    @Test
    fun `lease release and reacquire rejects a fetch from the old lease`() = runTest {
        val (repo, api, _) = repository(testScheduler)
        val firstLease = repo.acquireSession(sessionId)
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        coEvery { api.getMessages("s1", 100, null, "/test", null) } coAnswers {
            calls += 1
            if (calls == 1) {
                gate.await()
                listOf(assistantWrapper("m-old", createdAt = 1, partText = "old-lease"))
            } else {
                listOf(assistantWrapper("m-current", createdAt = 2, partText = "current-lease"))
            }
        }

        repo.acceptEvent(OpenCodeEvent.Connected)
        advanceUntilIdle()

        firstLease.close()
        repo.acquireSession(sessionId)
        gate.complete(Unit)
        advanceUntilIdle()

        // The old-lease fetch (m-old) must be rejected; the retry reflects current server state.
        assertTrue(repo.messages(sessionId).value.none { it.message.id == "m-old" })
        assertTrue(repo.messages(sessionId).value.any { it.message.id == "m-current" })
    }

    @Test
    fun `session deleted prevents recovery while lease remains`() = runTest {
        val (repo, api, _) = repository(testScheduler)
        repo.acquireSession(sessionId)
        repo.acceptEvent(OpenCodeEvent.MessageUpdated(assistantMessage("m1", createdAt = 1)))
        advanceUntilIdle()

        repo.acceptEvent(OpenCodeEvent.SessionDeleted(session("s1")))
        advanceUntilIdle()

        repo.acceptEvent(OpenCodeEvent.Connected)
        advanceUntilIdle()

        // The deleted session must never be refetched or repopulated even while its lease is held.
        coVerify(exactly = 0) { api.getMessages(any(), any(), any(), any(), any()) }
        assertTrue(repo.messages(sessionId).value.isEmpty())
    }

    private fun session(id: String) = dev.blazelight.p4oc.domain.model.Session(
        id = id,
        projectID = "project-$id",
        directory = "/workspace",
        title = id,
        version = "1",
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun repository(
        scheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
    ): Triple<SessionRepositoryImpl, OpenCodeApi, WorkspaceClient> {
        val api = mockk<OpenCodeApi>(relaxed = true)
        val client = clientFor(api)
        val repo = SessionRepositoryImpl(
            client,
            messageMapper = mapper,
            dispatcher = StandardTestDispatcher(scheduler),
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
