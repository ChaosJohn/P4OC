package dev.blazelight.p4oc.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.blazelight.p4oc.core.network.ConnectionManager
import dev.blazelight.p4oc.data.remote.dto.ModelInput
import dev.blazelight.p4oc.data.remote.dto.ProviderDto
import dev.blazelight.p4oc.ui.screens.chat.ModelSelectionCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProviderConfigUiState(
    val isLoading: Boolean = true,
    val error: String? = null,
    val providers: List<ProviderDto> = emptyList(),
    val connectedProviderIds: List<String> = emptyList(),
    val currentModel: String? = null,
    val selectedProviderId: String? = null
)

class ProviderConfigViewModel constructor(
    private val connectionManager: ConnectionManager,
    private val modelSelectionCoordinator: ModelSelectionCoordinator = ModelSelectionCoordinator()
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProviderConfigUiState())
    val uiState: StateFlow<ProviderConfigUiState> = _uiState.asStateFlow()

    init {
        loadProviders()
    }

    fun loadProviders() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val api = connectionManager.getApi() ?: run {
                _uiState.update { it.copy(isLoading = false, error = "Not connected") }
                return@launch
            }
            try {
                val providersResponse = api.getProviders()
                val config = api.getConfig()

                _uiState.update { state ->
                    state.copy(
                        isLoading = false,
                        providers = providersResponse.all,
                        connectedProviderIds = providersResponse.connected,
                        currentModel = config.model,
                        error = null
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to load providers"
                    )
                }
            }
        }
    }

    fun selectProvider(providerId: String) {
        _uiState.update { it.copy(selectedProviderId = providerId) }
    }

    fun setModel(providerId: String, modelId: String) {
        viewModelScope.launch {
            val api = connectionManager.getApi() ?: run {
                _uiState.update { it.copy(error = "Not connected") }
                return@launch
            }
            try {
                val currentConfig = api.getConfig()
                val newModel = "$providerId/$modelId"
                val updatedConfig = currentConfig.copy(model = newModel)
                val savedConfig = api.updateConfig(updatedConfig)
                val savedModel = savedConfig.model ?: newModel
                _uiState.update { it.copy(currentModel = savedModel, error = null) }
                parseModelInput(savedModel)?.let(modelSelectionCoordinator::publishActiveModel)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message ?: "Failed to set model") }
            }
        }
    }

    private fun parseModelInput(value: String): ModelInput? {
        val separator = value.indexOf('/')
        if (separator <= 0 || separator == value.lastIndex) return null
        return ModelInput(
            providerID = value.substring(0, separator),
            modelID = value.substring(separator + 1)
        )
    }
}
