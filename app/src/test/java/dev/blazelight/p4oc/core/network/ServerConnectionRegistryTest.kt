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
import kotlinx.coroutines.test.advanceUntilIdle
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
        val registry = registryFor(this) { config ->
            when (config.url) {
                alpha.endpoint -> alphaManager
                beta.endpoint -> betaManager
                else -> error("unexpected config $config")
            }
        }

        registry.connect(alpha)
        registry.connect(beta)
        advanceUntilIdle()

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
        val registry = registryFor(this) { config ->
            when (config.url) {
                alpha.endpoint -> alphaManager
                beta.endpoint -> betaManager
                else -> error("unexpected config $config")
            }
        }

        registry.connect(alpha)
        registry.connect(beta)
        advanceUntilIdle()

        assertEquals(ConnectionState.Connected, registry.connectionState(alpha.toServerRef()).value)
        assertEquals(ConnectionState.Error("auth failed"), registry.connectionState(beta.toServerRef()).value)
    }

    @Test
    fun `disconnect only clears the targeted server`() = runTest {
        val alpha = SavedServerRegistry.fromConnection("http://alpha.example.com", "Alpha")
        val beta = SavedServerRegistry.fromConnection("http://beta.example.com", "Beta")
        val alphaManager = successfulManager(alpha)
        val betaManager = successfulManager(beta)
        val registry = registryFor(this) { config ->
            when (config.url) {
                alpha.endpoint -> alphaManager
                beta.endpoint -> betaManager
                else -> error("unexpected config $config")
            }
        }
        registry.connect(alpha)
        registry.connect(beta)
        advanceUntilIdle()

        registry.disconnect(alpha.toServerRef())

        assertEquals(ConnectionState.Disconnected, registry.connectionState(alpha.toServerRef()).value)
        assertEquals(ConnectionState.Connected, registry.connectionState(beta.toServerRef()).value)
        coVerify(exactly = 1) { alphaManager.disconnect() }
        coVerify(exactly = 0) { betaManager.disconnect() }
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
        }, this)

        registry.reconnectAll(setOf(alpha.toServerRef(), missing))
        advanceUntilIdle()

        assertEquals(ConnectionState.Connected, registry.connectionState(alpha.toServerRef()).value)
        assertEquals(ConnectionState.Disconnected, registry.connectionState(beta.toServerRef()).value)
        coVerify(exactly = 1) { alphaManager.connect(alpha.toServerConfig(), "alpha-pass") }
        coVerify(exactly = 0) { betaManager.connect(any(), any()) }
    }

    private fun registryFor(
        scope: CoroutineScope,
        factory: (ServerConfig) -> ConnectionManager,
    ): ServerConnectionRegistry = ServerConnectionRegistry(mockk(relaxed = true), factory, scope)

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
