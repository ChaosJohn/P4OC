package dev.blazelight.p4oc.ui.screens.chat

import dev.blazelight.p4oc.core.datastore.SettingsDataStore
import dev.blazelight.p4oc.core.log.AppLog
import dev.blazelight.p4oc.core.network.ConnectionState
import dev.blazelight.p4oc.core.network.OpenCodeApi
import dev.blazelight.p4oc.data.remote.dto.AgentDto
import dev.blazelight.p4oc.data.remote.dto.ModelDto
import dev.blazelight.p4oc.data.remote.dto.ModelInput
import dev.blazelight.p4oc.data.remote.dto.ModelRefDto
import dev.blazelight.p4oc.data.remote.dto.ProviderDto
import dev.blazelight.p4oc.data.remote.dto.ProvidersResponseDto
import dev.blazelight.p4oc.data.server.ActiveServerApiProvider
import dev.blazelight.p4oc.data.workspace.WorkspaceClient
import dev.blazelight.p4oc.domain.server.ServerGeneration
import dev.blazelight.p4oc.domain.server.ServerRef
import dev.blazelight.p4oc.domain.workspace.Workspace
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ModelAgentManagerTest {

    private val settingsDataStore: SettingsDataStore = mockk(relaxed = true)
    private val api: OpenCodeApi = mockk()
    private val workspaceClient = WorkspaceClient(
        workspace = Workspace(ServerRef.fromEndpointKey("http://test.local"), "/test"),
        generation = ServerGeneration(1L),
        apiProvider = ActiveServerApiProvider { _, _ -> api },
        connectionState = MutableStateFlow(ConnectionState.Disconnected),
    )

    @Before
    fun setUp() {
        mockkObject(AppLog)
        every { AppLog.d(any(), any<String>()) } returns Unit
        every { AppLog.d(any(), any<() -> String>()) } returns Unit
        every { AppLog.e(any(), any<String>()) } returns Unit
        every { AppLog.e(any(), any<String>(), any()) } returns Unit
        every { settingsDataStore.favoriteModels } returns flowOf(emptySet())
        every { settingsDataStore.recentModels } returns flowOf(emptyList())
    }

    @After
    fun tearDown() {
        unmockkObject(AppLog)
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun makeAgent(
        name: String,
        mode: String? = "primary",
        hidden: Boolean? = null,
        model: ModelRefDto? = null
    ) = AgentDto(name = name, description = "desc", mode = mode, hidden = hidden, model = model)

    private fun makeModel(id: String, providerId: String) = ModelDto(
        id = id,
        providerId = providerId,
        name = "Model $id"
    )

    // ── loadAgents ──────────────────────────────────────────────────────────

    @Test
    fun `loadAgents includes primary and all non-hidden agents`() = runTest {
        val agents = listOf(
            makeAgent("build", mode = "primary"),
            makeAgent("code", mode = "primary"),
            makeAgent("general", mode = "all"),
            makeAgent("hidden-agent", mode = "primary", hidden = true),
            makeAgent("subagent", mode = "subagent")
        )
        coEvery { api.getAgents(any(), null) } returns agents

        val manager = ModelAgentManager(workspaceClient, settingsDataStore, this)
        manager.loadAgents()
        advanceUntilIdle()

        val result = manager.availableAgents.value
        assertEquals(3, result.size)
        assertEquals("build", result[0].name)
        assertEquals("code", result[1].name)
        assertEquals("general", result[2].name)
    }

    @Test
    fun `loadAgents selects first primary agent from server order`() = runTest {
        val agents = listOf(
            makeAgent("code", mode = "primary"),
            makeAgent("build", mode = "primary"),
            makeAgent("ask", mode = "primary")
        )
        coEvery { api.getAgents(any(), null) } returns agents

        val manager = ModelAgentManager(workspaceClient, settingsDataStore, this)
        manager.loadAgents()
        advanceUntilIdle()

        assertEquals("code", manager.selectedAgent.value)
    }

    @Test
    fun `loadAgents selects first agent when build not available`() = runTest {
        val agents = listOf(
            makeAgent("code", mode = "primary"),
            makeAgent("ask", mode = "primary")
        )
        coEvery { api.getAgents(any(), null) } returns agents

        val manager = ModelAgentManager(workspaceClient, settingsDataStore, this)
        manager.loadAgents()
        advanceUntilIdle()

        assertEquals("code", manager.selectedAgent.value)
    }

    @Test
    fun `loadAgents selects configured model for default agent`() = runTest {
        val agents = listOf(
            makeAgent(
                "build",
                mode = "primary",
                model = ModelRefDto(providerID = "anthropic", modelID = "claude-3")
            )
        )
        coEvery { api.getAgents(any(), null) } returns agents

        val manager = ModelAgentManager(workspaceClient, settingsDataStore, this)
        manager.loadAgents()
        advanceUntilIdle()

        assertEquals(ModelInput(providerID = "anthropic", modelID = "claude-3"), manager.selectedModel.value)
    }

    @Test
    fun `selectAgent selects configured model`() = runTest {
        val agents = listOf(
            makeAgent(
                "code",
                mode = "primary",
                model = ModelRefDto(providerID = "openai", modelID = "gpt-4")
            ),
            makeAgent(
                "build",
                mode = "primary",
                model = ModelRefDto(providerID = "anthropic", modelID = "claude-3")
            )
        )
        coEvery { api.getAgents(any(), null) } returns agents

        val manager = ModelAgentManager(workspaceClient, settingsDataStore, this)
        manager.loadAgents()
        advanceUntilIdle()

        manager.selectAgent("code")

        assertEquals(ModelInput(providerID = "openai", modelID = "gpt-4"), manager.selectedModel.value)
    }

    @Test
    fun `loadAgents agent model is not overwritten by loadModels default`() = runTest {
        val agents = listOf(
            makeAgent(
                "build",
                mode = "primary",
                model = ModelRefDto(providerID = "anthropic", modelID = "claude-3")
            )
        )
        val providersResponse = ProvidersResponseDto(
            all = listOf(
                ProviderDto(
                    id = "openai",
                    name = "OpenAI",
                    source = "env",
                    models = mapOf(
                        "gpt-4" to makeModel("gpt-4", "openai")
                    )
                )
            ),
            default = mapOf("openai" to "gpt-4"),
            connected = listOf("openai")
        )
        coEvery { api.getAgents(any(), null) } returns agents
        coEvery { api.getProviders(any(), null) } returns providersResponse

        val manager = ModelAgentManager(workspaceClient, settingsDataStore, this)
        manager.loadAgents()
        advanceUntilIdle()
        manager.loadModels()
        advanceUntilIdle()

        assertEquals(ModelInput(providerID = "anthropic", modelID = "claude-3"), manager.selectedModel.value)
    }

    @Test
    fun `loadAgents handles API error gracefully`() = runTest {
        coEvery { api.getAgents(any(), null) } throws RuntimeException("Network error")

        val manager = ModelAgentManager(workspaceClient, settingsDataStore, this)
        manager.loadAgents()
        advanceUntilIdle()

        assertTrue(manager.availableAgents.value.isEmpty())
        assertNull(manager.selectedAgent.value)
    }

    // ── loadModels ──────────────────────────────────────────────────────────

    @Test
    fun `loadModels selects last used model when available`() = runTest {
        val recentModel = ModelInput(providerID = "anthropic", modelID = "claude-3")
        every { settingsDataStore.recentModels } returns flowOf(listOf(recentModel))

        val providersResponse = ProvidersResponseDto(
            all = listOf(
                ProviderDto(
                    id = "anthropic",
                    name = "Anthropic",
                    source = "env",
                    models = mapOf(
                        "claude-3" to makeModel("claude-3", "anthropic")
                    )
                )
            ),
            default = mapOf("openai" to "gpt-4"),
            connected = listOf("anthropic")
        )
        coEvery { api.getProviders(any(), null) } returns providersResponse

        val manager = ModelAgentManager(workspaceClient, settingsDataStore, this)
        advanceUntilIdle()

        manager.loadModels()
        advanceUntilIdle()

        val selected = manager.selectedModel.value
        assertNotNull(selected)
        assertEquals("anthropic", selected!!.providerID)
        assertEquals("claude-3", selected.modelID)
    }

    @Test
    fun `loadModels prefers server default over app recent model`() = runTest {
        val recentModel = ModelInput(providerID = "anthropic", modelID = "claude-3")
        every { settingsDataStore.recentModels } returns flowOf(listOf(recentModel))

        val providersResponse = ProvidersResponseDto(
            all = listOf(
                ProviderDto(
                    id = "anthropic",
                    name = "Anthropic",
                    source = "env",
                    models = mapOf(
                        "claude-3" to makeModel("claude-3", "anthropic")
                    )
                ),
                ProviderDto(
                    id = "openai",
                    name = "OpenAI",
                    source = "env",
                    models = mapOf(
                        "gpt-4" to makeModel("gpt-4", "openai")
                    )
                )
            ),
            default = mapOf("openai" to "gpt-4"),
            connected = listOf("anthropic", "openai")
        )
        coEvery { api.getProviders(any(), null) } returns providersResponse

        val manager = ModelAgentManager(workspaceClient, settingsDataStore, this)
        advanceUntilIdle()

        manager.loadModels()
        advanceUntilIdle()

        assertEquals(ModelInput(providerID = "openai", modelID = "gpt-4"), manager.selectedModel.value)
    }

    @Test
    fun `loadModels keeps explicit user selected model when still available`() = runTest {
        val selectedModel = ModelInput(providerID = "anthropic", modelID = "claude-3")
        val providersResponse = ProvidersResponseDto(
            all = listOf(
                ProviderDto(
                    id = "anthropic",
                    name = "Anthropic",
                    source = "env",
                    models = mapOf(
                        "claude-3" to makeModel("claude-3", "anthropic")
                    )
                ),
                ProviderDto(
                    id = "openai",
                    name = "OpenAI",
                    source = "env",
                    models = mapOf(
                        "gpt-4" to makeModel("gpt-4", "openai")
                    )
                )
            ),
            default = mapOf("openai" to "gpt-4"),
            connected = listOf("anthropic", "openai")
        )
        coEvery { api.getProviders(any(), null) } returns providersResponse

        val manager = ModelAgentManager(workspaceClient, settingsDataStore, this)
        manager.selectModel(selectedModel)
        advanceUntilIdle()

        manager.loadModels()
        advanceUntilIdle()

        assertEquals(selectedModel, manager.selectedModel.value)
    }

    @Test
    fun `loadModels reconciles unavailable explicit model to server default`() = runTest {
        val staleModel = ModelInput(providerID = "anthropic", modelID = "claude-3")
        val providersResponse = ProvidersResponseDto(
            all = listOf(
                ProviderDto(
                    id = "openai",
                    name = "OpenAI",
                    source = "env",
                    models = mapOf(
                        "gpt-4" to makeModel("gpt-4", "openai")
                    )
                )
            ),
            default = mapOf("openai" to "gpt-4"),
            connected = listOf("openai")
        )
        coEvery { api.getProviders(any(), null) } returns providersResponse

        val manager = ModelAgentManager(workspaceClient, settingsDataStore, this)
        manager.selectModel(staleModel)
        advanceUntilIdle()

        manager.loadModels()
        advanceUntilIdle()

        assertEquals(ModelInput(providerID = "openai", modelID = "gpt-4"), manager.selectedModel.value)
    }

    @Test
    fun `loadModels does not infer reasoning effort from first available variant without explicit default`() = runTest {
        every { settingsDataStore.recentModels } returns flowOf(emptyList())

        val providersResponse = ProvidersResponseDto(
            all = listOf(
                ProviderDto(
                    id = "anthropic",
                    name = "Anthropic",
                    source = "env",
                    models = mapOf(
                        "claude-3" to ModelDto(
                            id = "claude-3",
                            providerId = "anthropic",
                            name = "Claude 3",
                            variants = kotlinx.serialization.json.JsonObject(
                                mapOf(
                                    "low" to kotlinx.serialization.json.JsonObject(emptyMap()),
                                    "high" to kotlinx.serialization.json.JsonObject(emptyMap())
                                )
                            )
                        )
                    )
                )
            ),
            default = mapOf("anthropic" to "claude-3"),
            connected = listOf("anthropic")
        )
        coEvery { api.getProviders(any(), null) } returns providersResponse

        val manager = ModelAgentManager(workspaceClient, settingsDataStore, this)
        advanceUntilIdle()

        manager.loadModels()
        advanceUntilIdle()

        assertEquals(ModelInput(providerID = "anthropic", modelID = "claude-3"), manager.selectedModel.value)
        assertNull(
            "A provider/model default is not a reasoning-effort default; absent explicit upstream/user effort, " +
                "do not silently choose the first representable variant.",
            manager.currentReasoningEffort()
        )
    }

    @Test
    fun `loadModels selects default model when no recent`() = runTest {
        every { settingsDataStore.recentModels } returns flowOf(emptyList())

        val providersResponse = ProvidersResponseDto(
            all = listOf(
                ProviderDto(
                    id = "openai",
                    name = "OpenAI",
                    source = "env",
                    models = mapOf(
                        "gpt-4" to makeModel("gpt-4", "openai")
                    )
                )
            ),
            default = mapOf("openai" to "gpt-4"),
            connected = listOf("openai")
        )
        coEvery { api.getProviders(any(), null) } returns providersResponse

        val manager = ModelAgentManager(workspaceClient, settingsDataStore, this)
        advanceUntilIdle()

        manager.loadModels()
        advanceUntilIdle()

        val selected = manager.selectedModel.value
        assertNotNull(selected)
        assertEquals("openai", selected!!.providerID)
        assertEquals("gpt-4", selected.modelID)
    }

    @Test
    fun `active model change updates selection when no agent or explicit model override`() = runTest {
        val coordinator = ModelSelectionCoordinator()
        val manager = ModelAgentManager(workspaceClient, settingsDataStore, backgroundScope, null, coordinator)
        advanceUntilIdle()
        runCurrent()

        val publishedModel = ModelInput(providerID = "anthropic", modelID = "claude-3")
        coordinator.publishActiveModel(publishedModel)
        runCurrent()

        assertEquals(publishedModel, manager.selectedModel.value)
    }

    @Test
    fun `active model change does not replace explicit user selection`() = runTest {
        val coordinator = ModelSelectionCoordinator()
        val manager = ModelAgentManager(workspaceClient, settingsDataStore, backgroundScope, null, coordinator)
        val explicitModel = ModelInput(providerID = "openai", modelID = "gpt-4")
        manager.selectModel(explicitModel)
        advanceUntilIdle()
        runCurrent()

        coordinator.publishActiveModel(ModelInput(providerID = "anthropic", modelID = "claude-3"))
        runCurrent()

        assertEquals(explicitModel, manager.selectedModel.value)
    }

    @Test
    fun `active model change does not replace agent model selection`() = runTest {
        val coordinator = ModelSelectionCoordinator()
        val agents = listOf(
            makeAgent(
                "code",
                mode = "primary",
                model = ModelRefDto(providerID = "openai", modelID = "gpt-4")
            )
        )
        coEvery { api.getAgents(any(), null) } returns agents
        val manager = ModelAgentManager(workspaceClient, settingsDataStore, backgroundScope, null, coordinator)
        manager.loadAgents()
        advanceUntilIdle()
        runCurrent()

        coordinator.publishActiveModel(ModelInput(providerID = "anthropic", modelID = "claude-3"))
        runCurrent()

        assertEquals(ModelInput(providerID = "openai", modelID = "gpt-4"), manager.selectedModel.value)
    }

    // ── selectModel ─────────────────────────────────────────────────────────

    @Test
    fun `selectModel adds to recent models`() = runTest {
        val model = ModelInput(providerID = "anthropic", modelID = "claude-3")

        val manager = ModelAgentManager(workspaceClient, settingsDataStore, this)
        manager.selectModel(model)
        advanceUntilIdle()

        assertEquals(model, manager.selectedModel.value)
        coVerify { settingsDataStore.addRecentModel(model) }
    }
}
