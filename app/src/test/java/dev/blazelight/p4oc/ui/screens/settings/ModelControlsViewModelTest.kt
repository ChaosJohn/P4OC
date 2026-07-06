package dev.blazelight.p4oc.ui.screens.settings

import dev.blazelight.p4oc.core.network.ConnectionManager
import dev.blazelight.p4oc.core.network.OpenCodeApi
import dev.blazelight.p4oc.data.remote.dto.ModelDto
import dev.blazelight.p4oc.data.remote.dto.ModelInput
import dev.blazelight.p4oc.data.remote.dto.ProviderDto
import dev.blazelight.p4oc.data.remote.dto.ProvidersResponseDto
import dev.blazelight.p4oc.data.remote.dto.SetActiveModelRequest
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
class ModelControlsViewModelTest {
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
    fun selectModel_successUpdatesSelectedStateAndClearsPreviousError() = runTest(dispatcher) {
        coEvery { api.getProviders() } returns providersResponse()
        coEvery { api.setActiveModel(activeModelRequest("anthropic", "claude-3")) } returns false
        val viewModel = ModelControlsViewModel(connectionManager)
        advanceUntilIdle()
        viewModel.selectModel("claude-3")
        advanceUntilIdle()
        assertEquals("Failed to set active model", viewModel.state.value.error)

        coEvery { api.setActiveModel(activeModelRequest("openai", "gpt-4")) } returns true
        viewModel.selectModel("gpt-4")
        advanceUntilIdle()

        assertEquals("gpt-4", viewModel.state.value.selectedModelId)
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun selectModel_publishesCoordinatorChangeOnlyAfterSuccessfulApiUpdate() = runTest(dispatcher) {
        val coordinator = ModelSelectionCoordinator()
        val publishedModels = mutableListOf<ModelInput>()
        val collectJob = backgroundScope.launch {
            coordinator.activeModelChanges.collect(publishedModels::add)
        }
        runCurrent()
        coEvery { api.getProviders() } returns providersResponse()
        coEvery { api.setActiveModel(activeModelRequest("anthropic", "claude-3")) } returns false
        coEvery { api.setActiveModel(activeModelRequest("openai", "gpt-4")) } returns true
        val viewModel = ModelControlsViewModel(connectionManager, coordinator)
        advanceUntilIdle()

        viewModel.selectModel("claude-3")
        runCurrent()
        assertEquals(emptyList<ModelInput>(), publishedModels)

        viewModel.selectModel("gpt-4")
        runCurrent()

        assertEquals(listOf(ModelInput(providerID = "openai", modelID = "gpt-4")), publishedModels)
        collectJob.cancel()
    }

    @Test
    fun selectModel_apiErrorPreservesPreviousSelectionAndShowsMessage() = runTest(dispatcher) {
        coEvery { api.getProviders() } returns providersResponse()
        coEvery { api.setActiveModel(activeModelRequest("openai", "gpt-4")) } returns true
        coEvery { api.setActiveModel(activeModelRequest("anthropic", "claude-3")) } throws
            IllegalStateException("server rejected model")
        val viewModel = ModelControlsViewModel(connectionManager)
        advanceUntilIdle()
        viewModel.selectModel("gpt-4")
        advanceUntilIdle()

        viewModel.selectModel("claude-3")
        advanceUntilIdle()

        assertEquals("gpt-4", viewModel.state.value.selectedModelId)
        assertEquals("server rejected model", viewModel.state.value.error)
    }

    @Test
    fun selectModel_falseSuccessPreservesPreviousSelectionAndShowsMessage() = runTest(dispatcher) {
        coEvery { api.getProviders() } returns providersResponse()
        coEvery { api.setActiveModel(activeModelRequest("openai", "gpt-4")) } returns true
        coEvery { api.setActiveModel(activeModelRequest("anthropic", "claude-3")) } returns false
        val viewModel = ModelControlsViewModel(connectionManager)
        advanceUntilIdle()
        viewModel.selectModel("gpt-4")
        advanceUntilIdle()

        viewModel.selectModel("claude-3")
        advanceUntilIdle()

        assertEquals("gpt-4", viewModel.state.value.selectedModelId)
        assertEquals("Failed to set active model", viewModel.state.value.error)
    }

    @Test
    fun selectModel_missingApiDoesNotLeaveOptimisticSelection() = runTest(dispatcher) {
        coEvery { api.getProviders() } returns providersResponse()
        coEvery { api.setActiveModel(activeModelRequest("openai", "gpt-4")) } returns true
        val viewModel = ModelControlsViewModel(connectionManager)
        advanceUntilIdle()
        viewModel.selectModel("gpt-4")
        advanceUntilIdle()
        every { connectionManager.getApi() } returns null

        viewModel.selectModel("claude-3")
        advanceUntilIdle()

        assertEquals("gpt-4", viewModel.state.value.selectedModelId)
        assertEquals("Not connected", viewModel.state.value.error)
    }

    @Test
    fun selectModel_missingModelDoesNotLeaveOptimisticSelection() = runTest(dispatcher) {
        coEvery { api.getProviders() } returns providersResponse()
        coEvery { api.setActiveModel(activeModelRequest("openai", "gpt-4")) } returns true
        val viewModel = ModelControlsViewModel(connectionManager)
        advanceUntilIdle()
        viewModel.selectModel("gpt-4")
        advanceUntilIdle()

        viewModel.selectModel("missing-model")
        advanceUntilIdle()

        assertEquals("gpt-4", viewModel.state.value.selectedModelId)
        assertEquals("Model not available", viewModel.state.value.error)
        coVerify(exactly = 0) {
            api.setActiveModel(activeModelRequest("anthropic", "missing-model"))
        }
    }

    private fun providersResponse() = ProvidersResponseDto(
        all = listOf(
            ProviderDto(
                id = "openai",
                name = "OpenAI",
                source = "env",
                models = mapOf("gpt-4" to model("gpt-4", "openai"))
            ),
            ProviderDto(
                id = "anthropic",
                name = "Anthropic",
                source = "env",
                models = mapOf("claude-3" to model("claude-3", "anthropic"))
            )
        ),
        default = mapOf("openai" to "gpt-4"),
        connected = listOf("openai", "anthropic")
    )

    private fun model(id: String, providerId: String) = ModelDto(
        id = id,
        providerId = providerId,
        name = "Model $id"
    )

    private fun activeModelRequest(providerId: String, modelId: String) = SetActiveModelRequest(
        model = ModelInput(providerID = providerId, modelID = modelId)
    )
}
