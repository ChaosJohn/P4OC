package dev.blazelight.p4oc.ui.screens.settings

import dev.blazelight.p4oc.core.network.ConnectionManager
import dev.blazelight.p4oc.core.network.OpenCodeApi
import dev.blazelight.p4oc.data.remote.dto.ConfigDto
import dev.blazelight.p4oc.data.remote.dto.ModelInput
import dev.blazelight.p4oc.data.remote.dto.ProviderDto
import dev.blazelight.p4oc.data.remote.dto.ProvidersResponseDto
import dev.blazelight.p4oc.ui.screens.chat.ModelSelectionCoordinator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderConfigViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val connectionManager: ConnectionManager = mockk()
    private val api: OpenCodeApi = mockk()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { connectionManager.getApi() } returns api
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun setModel_updatesCurrentModelOnlyAfterUpdateConfigSucceeds() = runTest(dispatcher) {
        coEvery { api.getProviders() } returns providersResponse()
        coEvery { api.getConfig() } returns ConfigDto(model = "openai/gpt-4")
        coEvery { api.updateConfig(ConfigDto(model = "anthropic/claude-3")) } returns
            ConfigDto(model = "anthropic/claude-3")
        val viewModel = ProviderConfigViewModel(connectionManager)
        advanceUntilIdle()

        viewModel.setModel("anthropic", "claude-3")
        assertEquals("openai/gpt-4", viewModel.uiState.value.currentModel)
        advanceUntilIdle()

        assertEquals("anthropic/claude-3", viewModel.uiState.value.currentModel)
        assertNull(viewModel.uiState.value.error)
        coVerify { api.updateConfig(ConfigDto(model = "anthropic/claude-3")) }
    }

    @Test
    fun setModel_publishesCoordinatorChangeOnlyAfterUpdateConfigSucceeds() = runTest(dispatcher) {
        val coordinator = ModelSelectionCoordinator()
        val publishedModels = mutableListOf<ModelInput>()
        val collectJob = backgroundScope.launch {
            coordinator.activeModelChanges.collect(publishedModels::add)
        }
        runCurrent()
        coEvery { api.getProviders() } returns providersResponse()
        coEvery { api.getConfig() } returns ConfigDto(model = "openai/gpt-4")
        coEvery { api.updateConfig(ConfigDto(model = "anthropic/claude-3")) } throws
            IllegalStateException("config write failed")
        val viewModel = ProviderConfigViewModel(connectionManager, coordinator)
        advanceUntilIdle()

        viewModel.setModel("anthropic", "claude-3")
        runCurrent()
        assertEquals(emptyList<ModelInput>(), publishedModels)

        coEvery { api.updateConfig(ConfigDto(model = "anthropic/claude-3")) } returns
            ConfigDto(model = "anthropic/claude-3")
        viewModel.setModel("anthropic", "claude-3")
        runCurrent()

        assertEquals(listOf(ModelInput(providerID = "anthropic", modelID = "claude-3")), publishedModels)
        collectJob.cancel()
    }

    @Test
    fun setModel_updateConfigExceptionPreservesPreviousModelAndShowsMessage() = runTest(dispatcher) {
        coEvery { api.getProviders() } returns providersResponse()
        coEvery { api.getConfig() } returns ConfigDto(model = "openai/gpt-4")
        coEvery { api.updateConfig(ConfigDto(model = "anthropic/claude-3")) } throws
            IllegalStateException("config write failed")
        val viewModel = ProviderConfigViewModel(connectionManager)
        advanceUntilIdle()

        viewModel.setModel("anthropic", "claude-3")
        advanceUntilIdle()

        assertEquals("openai/gpt-4", viewModel.uiState.value.currentModel)
        assertEquals("config write failed", viewModel.uiState.value.error)
    }

    private fun providersResponse() = ProvidersResponseDto(
        all = listOf(
            ProviderDto(
                id = "openai",
                name = "OpenAI",
                source = "env"
            ),
            ProviderDto(
                id = "anthropic",
                name = "Anthropic",
                source = "env"
            )
        ),
        default = mapOf("openai" to "gpt-4"),
        connected = listOf("openai", "anthropic")
    )
}
