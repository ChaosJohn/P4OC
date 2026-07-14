package dev.blazelight.p4oc.core.network

import dev.blazelight.p4oc.core.datastore.SavedServerRegistry
import dev.blazelight.p4oc.core.datastore.SettingsDataStore
import dev.blazelight.p4oc.domain.server.ServerRef
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServerConnectionRegistryTest {

    @Test
    fun `two saved servers keep independent connection states`() = runTest {
        val alpha = SavedServerRegistry.fromConnection("http://alpha.example.com", "Alpha")
        val beta = SavedServerRegistry.fromConnection("http://beta.example.com", "Beta")
        val alphaManager = successfulManager(alpha)
        val betaManager = successfulManager(beta)
        val registry = registryFor(backgroundScope) { config ->
            when (config.url) {
                alpha.endpoint -> alphaManager
                beta.endpoint -> betaManager
                else -> error("unexpected config $config")
            }
        }

        registry.connect(alpha)
        registry.connect(beta)
        runCurrent()

        assertEquals(ConnectionState.Connected, registry.connectionState(alpha.toServerRef()).value)
        assertEquals(ConnectionState.Connected, registry.connectionState(beta.toServerRef()).value)
        coVerify(exactly = 1) { alphaManager.connect(alpha.toServerConfig(), null) }
        coVerify(exactly = 1) { betaManager.connect(beta.toServerConfig(), null) }
    }

    @Test
    fun `one server failure does not overwrite another server state`() = runTest {
        val alpha = SavedServerRegistry.fromConnection("http://alpha.example.com", "Alpha")
        val beta = SavedServerRegistry.fromConnection("http://beta.example.com", "Beta")
        val alphaManager = successfulManager(alpha)
        val betaManager = failingManager(beta, "auth failed")
        val registry = registryFor(backgroundScope) { config ->
            when (config.url) {
                alpha.endpoint -> alphaManager
                beta.endpoint -> betaManager
                else -> error("unexpected config $config")
            }
        }

        registry.connect(alpha)
        registry.connect(beta)
        runCurrent()

        assertEquals(ConnectionState.Connected, registry.connectionState(alpha.toServerRef()).value)
        assertEquals(ConnectionState.Error("auth failed"), registry.connectionState(beta.toServerRef()).value)
    }

    @Test
    fun `registry follows manager recovery after connect returns`() = runTest {
        val server = SavedServerRegistry.fromConnection("http://recovering.example.com", "Recovering")
        val managerState = MutableStateFlow<ConnectionState>(ConnectionState.Error("network unavailable"))
        val manager = mockk<ConnectionManager>(relaxed = true)
        every { manager.connection } returns MutableStateFlow(null)
        every { manager.connectionState } returns managerState
        coEvery { manager.connect(server.toServerConfig(), any()) } returns Result.success(emptyList())
        val registry = registryFor(backgroundScope) { manager }

        registry.connect(server)
        runCurrent()
        assertEquals(
            ConnectionState.Error("network unavailable"),
            registry.connectionState(server.toServerRef()).value,
        )

        managerState.value = ConnectionState.Connected
        runCurrent()

        assertEquals(ConnectionState.Connected, registry.connectionState(server.toServerRef()).value)
    }

    @Test
    fun `disconnect only clears the targeted server`() = runTest {
        val alpha = SavedServerRegistry.fromConnection("http://alpha.example.com", "Alpha")
        val beta = SavedServerRegistry.fromConnection("http://beta.example.com", "Beta")
        val alphaManager = successfulManager(alpha)
        val betaManager = successfulManager(beta)
        val registry = registryFor(backgroundScope) { config ->
            when (config.url) {
                alpha.endpoint -> alphaManager
                beta.endpoint -> betaManager
                else -> error("unexpected config $config")
            }
        }
        registry.connect(alpha)
        registry.connect(beta)
        runCurrent()

        registry.disconnect(alpha.toServerRef())

        assertEquals(ConnectionState.Disconnected, registry.connectionState(alpha.toServerRef()).value)
        assertEquals(ConnectionState.Connected, registry.connectionState(beta.toServerRef()).value)
        coVerify(exactly = 1) { alphaManager.disconnect() }
        coVerify(exactly = 0) { betaManager.disconnect() }
    }

    @Test
    fun `connect saved server uses persisted password when caller omits one`() = runTest {
        val server = SavedServerRegistry.fromConnection("http://authenticated.example.com", "Authenticated")
        val settings = mockk<SettingsDataStore>()
        coEvery { settings.getSavedServerPassword(server) } returns "persisted-password"
        val manager = successfulManager(server)
        val registry = ServerConnectionRegistry(settings, { manager }, backgroundScope)

        registry.connect(server)
        runCurrent()

        coVerify(exactly = 1) { manager.connect(server.toServerConfig(), "persisted-password") }
    }

    @Test
    fun `connect saved server keeps explicit password authoritative`() = runTest {
        val server = SavedServerRegistry.fromConnection("http://authenticated.example.com", "Authenticated")
        val settings = mockk<SettingsDataStore>()
        val manager = successfulManager(server)
        val registry = ServerConnectionRegistry(settings, { manager }, backgroundScope)

        registry.connect(server, "explicit-password")
        runCurrent()

        coVerify(exactly = 1) { manager.connect(server.toServerConfig(), "explicit-password") }
        coVerify(exactly = 0) { settings.getSavedServerPassword(any()) }
    }

    @Test
    fun `reconnectAll reconnects only saved open-tab servers`() = runTest {
        val alpha = SavedServerRegistry.fromConnection("http://alpha.example.com", "Alpha")
        val beta = SavedServerRegistry.fromConnection("http://beta.example.com", "Beta")
        val missing = ServerRef.fromEndpoint("http://missing.example.com")
        val settings = mockk<SettingsDataStore>()
        coEvery { settings.getSavedServers() } returns listOf(alpha, beta)
        coEvery { settings.getSavedServerPassword(alpha) } returns "alpha-pass"
        coEvery { settings.getSavedServerPassword(beta) } returns "beta-pass"
        val alphaManager = successfulManager(alpha)
        val betaManager = successfulManager(beta)
        val registry = ServerConnectionRegistry(settings, { config ->
            when (config.url) {
                alpha.endpoint -> alphaManager
                beta.endpoint -> betaManager
                else -> error("unexpected config $config")
            }
        }, backgroundScope)

        registry.reconnectAll(setOf(alpha.toServerRef(), missing))
        runCurrent()

        assertEquals(ConnectionState.Connected, registry.connectionState(alpha.toServerRef()).value)
        assertEquals(ConnectionState.Disconnected, registry.connectionState(beta.toServerRef()).value)
        coVerify(exactly = 1) { alphaManager.connect(alpha.toServerConfig(), "alpha-pass") }
        coVerify(exactly = 0) { betaManager.connect(any(), any()) }
    }

    private fun registryFor(
        scope: CoroutineScope,
        factory: (ServerConfig) -> ConnectionManager,
    ): ServerConnectionRegistry {
        val settings = mockk<SettingsDataStore>()
        coEvery { settings.getSavedServerPassword(any()) } returns null
        return ServerConnectionRegistry(settings, factory, scope)
    }

    private fun successfulManager(server: dev.blazelight.p4oc.core.datastore.SavedServer): ConnectionManager {
        val manager = mockk<ConnectionManager>(relaxed = true)
        every { manager.connection } returns MutableStateFlow(null)
        every { manager.connectionState } returns MutableStateFlow(ConnectionState.Connected)
        coEvery { manager.connect(server.toServerConfig(), any()) } returns Result.success(emptyList())
        return manager
    }

    private fun failingManager(
        server: dev.blazelight.p4oc.core.datastore.SavedServer,
        message: String,
    ): ConnectionManager {
        val manager = mockk<ConnectionManager>(relaxed = true)
        every { manager.connection } returns MutableStateFlow(null)
        every { manager.connectionState } returns MutableStateFlow(ConnectionState.Error(message))
        coEvery { manager.connect(server.toServerConfig(), any()) } returns Result.failure(
            IllegalStateException(message),
        )
        return manager
    }
}
