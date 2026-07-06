package dev.blazelight.p4oc.ui.screens.chat

import dev.blazelight.p4oc.data.remote.dto.ModelInput
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Shares successful active-model writes with live chat model managers.
 */
class ModelSelectionCoordinator {
    private val _activeModelChanges = MutableSharedFlow<ModelInput>(extraBufferCapacity = 1)
    val activeModelChanges: SharedFlow<ModelInput> = _activeModelChanges.asSharedFlow()

    fun publishActiveModel(model: ModelInput) {
        _activeModelChanges.tryEmit(model)
    }
}
