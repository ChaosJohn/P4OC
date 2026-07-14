package dev.blazelight.p4oc.core.network

import dev.blazelight.p4oc.core.datastore.SavedServer
import dev.blazelight.p4oc.core.datastore.SettingsDataStore
import dev.blazelight.p4oc.domain.server.ServerRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns connection state per saved server so multi-server tabs do not depend on a
 * mutable app-global current server. The existing [ConnectionManager] remains the
 * single-server implementation for the active legacy flow; this registry is the
 * multi-server coordination surface used by the pinned Home architecture.
 */
class ServerConnectionRegistry constructor(
    private val settingsDataStore: SettingsDataStore,
    private val connectionManagerFactory: (ServerConfig) -> ConnectionManager,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val states = ConcurrentHashMap<String, MutableStateFlow<ConnectionState>>()
    private val managers = ConcurrentHashMap<String, ConnectionManager>()
    private val connections = ConcurrentHashMap<String, StateFlow<Connection?>>()
    private val stateCollectors = ConcurrentHashMap<String, Job>()

    fun connectionState(serverRef: ServerRef): StateFlow<ConnectionState> = stateFlow(
        serverRef.endpointKey
    ).asStateFlow()

    fun connection(serverRef: ServerRef): StateFlow<Connection?> = connections.getOrPut(serverRef.endpointKey) {
        MutableStateFlow(null).asStateFlow()
    }

    fun api(serverRef: ServerRef): OpenCodeApi? = managers[serverRef.endpointKey]?.getApi()

    fun generation(serverRef: ServerRef): dev.blazelight.p4oc.domain.server.ServerGeneration? =
        managers[serverRef.endpointKey]?.currentGeneration

    fun connect(server: SavedServer, password: String? = null) {
        val serverRef = server.toServerRef()
        val state = stateFlow(server.endpointKey)
        state.value = ConnectionState.Connecting
        val manager = managers.getOrPut(server.endpointKey) {
            connectionManagerFactory(server.toServerConfig())
        }
        stateCollectors.computeIfAbsent(server.endpointKey) {
            scope.launch {
                manager.connectionState.collect { managerState ->
                    state.value = managerState
                }
            }
        }
        connections[server.endpointKey] = manager.connection
        scope.launch {
            val resolvedPassword = password ?: settingsDataStore.getSavedServerPassword(server)
            val result = manager.connect(server.toServerConfig(), resolvedPassword)
            state.value = result.fold(
                onSuccess = { manager.connectionState.value },
                onFailure = { ConnectionState.Error(it.message ?: "Connection failed") },
            )
            if (state.value is ConnectionState.Connecting) {
                state.value = ConnectionState.Connected
            }
        }
    }

    suspend fun connect(serverId: String, password: String? = null) {
        val server = settingsDataStore.findSavedServer(serverId) ?: return
        connect(server, password ?: settingsDataStore.getSavedServerPassword(server))
    }

    fun disconnect(serverRef: ServerRef) {
        stateCollectors.remove(serverRef.endpointKey)?.cancel()
        managers.remove(serverRef.endpointKey)?.disconnect()
        connections.remove(serverRef.endpointKey)
        stateFlow(serverRef.endpointKey).value = ConnectionState.Disconnected
    }

    suspend fun reconnectAll(openTabServers: Set<ServerRef>) {
        val savedByKey = settingsDataStore.getSavedServers().associateBy { it.endpointKey }
        openTabServers.forEach { serverRef ->
            val saved = savedByKey[serverRef.endpointKey] ?: return@forEach
            connect(saved, settingsDataStore.getSavedServerPassword(saved))
        }
    }

    private fun stateFlow(endpointKey: String): MutableStateFlow<ConnectionState> =
        states.getOrPut(endpointKey) { MutableStateFlow(ConnectionState.Disconnected) }
}

fun SavedServer.toServerRef(): ServerRef = ServerRef.fromEndpointKey(endpointKey, displayName)

fun SavedServer.toServerConfig(): ServerConfig = ServerConfig(
    url = endpoint,
    name = displayName,
    isLocal = endpoint.contains("localhost") || endpoint.contains("127.0.0.1"),
    username = username,
    allowInsecure = allowInsecure,
)
