package dev.blazelight.p4oc.data.session

import dev.blazelight.p4oc.core.network.ServerConnectionRegistry
import dev.blazelight.p4oc.core.log.AppLog
import dev.blazelight.p4oc.core.network.ConnectionState
import dev.blazelight.p4oc.data.remote.mapper.MessageMapper
import dev.blazelight.p4oc.data.server.ActiveServerApiProvider
import dev.blazelight.p4oc.data.workspace.WorkspaceClient
import dev.blazelight.p4oc.domain.model.OpenCodeEvent
import dev.blazelight.p4oc.domain.server.ServerGeneration
import dev.blazelight.p4oc.domain.server.WorkspaceKey
import dev.blazelight.p4oc.domain.workspace.Workspace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SessionRepositoryProvider(
    private val activeServerApiProvider: ActiveServerApiProvider,
    private val messageMapper: MessageMapper,
    private val serverConnectionRegistry: ServerConnectionRegistry,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    data class Lease(
        val workspaceClient: WorkspaceClient,
        val repository: SessionRepositoryImpl,
    )

    private data class Key(
        val serverKey: String,
        val generation: Long,
        val workspaceKey: String,
    )

    private data class Entry(
        val workspaceClient: WorkspaceClient,
        val repository: SessionRepositoryImpl,
        val eventJob: Job,
        val reconnectJob: Job,
        var refCount: Int,
    )

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val entries = mutableMapOf<Key, Entry>()

    private companion object {
        const val TAG = "SessionRepositoryProvider"
    }

    fun acquire(workspace: Workspace, generation: ServerGeneration): Lease = synchronized(this) {
        val key = workspace.toProviderKey(generation)
        val entry = entries.getOrPut(key) {
            val workspaceClient = WorkspaceClient(
                workspace = workspace,
                generation = generation,
                apiProvider = activeServerApiProvider,
                connectionState = serverConnectionRegistry.connectionState(workspace.server, generation),
            )
            val repository = SessionRepositoryImpl(workspaceClient, messageMapper, dispatcher = dispatcher)
            Entry(
                workspaceClient = workspaceClient,
                repository = repository,
                eventJob = collectWorkspaceEvents(workspace, generation, repository),
                reconnectJob = collectReconnects(workspace, generation, repository),
                refCount = 0,
            )
        }
        entry.refCount += 1
        Lease(entry.workspaceClient, entry.repository)
    }

    fun release(workspace: Workspace, generation: ServerGeneration) {
        val repositoryToClose = synchronized(this) {
            val key = workspace.toProviderKey(generation)
            val entry = entries[key] ?: return
            entry.refCount -= 1
            if (entry.refCount > 0) return
            entries.remove(key)
            entry
        }
        repositoryToClose.eventJob.cancel()
        repositoryToClose.reconnectJob.cancel()
        repositoryToClose.repository.close()
    }

    @Suppress("TooGenericExceptionCaught") // resilience guard: one bad event must not kill the collector
    private fun collectWorkspaceEvents(
        workspace: Workspace,
        generation: ServerGeneration,
        repository: SessionRepositoryImpl,
    ): Job = scope.launch {
        serverConnectionRegistry.events(workspace.server).collect { scopedEvent ->
            // The raw OpenCodeEvent.Connected carries no directory and never reaches the
            // per-workspace fan-out. Reconnect is delivered exclusively via the registry's exact
            // server-and-generation connection-state transition in [collectReconnects]; ignoring
            // it here prevents a duplicate signal on global workspaces.
            val isReconnectMarker = scopedEvent.event is OpenCodeEvent.Connected
            if (!isReconnectMarker &&
                scopedEvent.serverRef.endpointKey == workspace.server.endpointKey &&
                scopedEvent.generation == generation &&
                scopedEvent.workspaceKey == workspace.key
            ) {
                try {
                    repository.acceptEvent(scopedEvent.event)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    // A single malformed/unexpected event must never permanently kill this
                    // workspace's live event delivery (issue #14: chat froze until re-entry).
                    AppLog.e(
                        TAG,
                        "Dropping event ${scopedEvent.event::class.simpleName} for ${workspace.key}: ${e.message}",
                        e,
                    )
                }
            }
        }
    }

    /**
     * Delivers the canonical reconnect signal to this workspace repository from the exact
     * server-and-generation connection state, so open conversations self-heal after an SSE
     * reconnect without navigation (issue #14: had to leave and re-enter to see updates).
     *
     * The synthetic [OpenCodeEvent.Connected] carries no directory, so it never reaches the
     * per-workspace event fan-out; the raw event from the scoped stream is deliberately ignored
     * in [collectWorkspaceEvents] to avoid a duplicate on global workspaces. The registry's exact
     * non-Connected -> Connected transition is the single authoritative reconnect signal.
     */
    @Suppress("TooGenericExceptionCaught") // resilience guard: one bad repo must not stop a repo's delivery
    private fun collectReconnects(
        workspace: Workspace,
        generation: ServerGeneration,
        repository: SessionRepositoryImpl,
    ): Job = scope.launch {
        val connectionState = serverConnectionRegistry.connectionState(workspace.server, generation)
        // Seed from the current value so an already-Connected registry (e.g. the workspace was
        // acquired mid-connection) does not emit a redundant reconnect on collection start.
        var wasConnected = connectionState.value is ConnectionState.Connected
        connectionState.collect { state ->
            val nowConnected = state is ConnectionState.Connected
            if (nowConnected && !wasConnected) {
                try {
                    repository.acceptEvent(OpenCodeEvent.Connected)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    AppLog.e(
                        TAG,
                        "Reconnect delivery failed for ${workspace.key}: ${e.message}",
                        e,
                    )
                }
            }
            wasConnected = nowConnected
        }
    }

    private fun Workspace.toProviderKey(generation: ServerGeneration): Key = Key(
        serverKey = server.endpointKey,
        generation = generation.value,
        workspaceKey = key.stableKey(),
    )

    private fun WorkspaceKey.stableKey(): String = when (this) {
        WorkspaceKey.Global -> "global"
        is WorkspaceKey.Directory -> "directory:$value"
        is WorkspaceKey.SessionScoped -> "session:${sessionId.value}"
    }
}
