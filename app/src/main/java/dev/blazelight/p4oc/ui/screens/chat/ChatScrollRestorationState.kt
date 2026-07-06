package dev.blazelight.p4oc.ui.screens.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue

internal enum class InitialTailDecision {
    ScrollToTail,
    KeepRestoredPosition,
    NoContent
}

internal class ChatScrollRestorationState(
    shouldFollowTail: Boolean = true,
    didInitialTailScroll: Boolean = false,
    hasNewContentWhileAway: Boolean = false,
    showSearch: Boolean = false,
    searchQuery: String = "",
    currentMatchIndex: Int = 0,
) {
    var shouldFollowTail by mutableStateOf(shouldFollowTail)
    var didInitialTailScroll by mutableStateOf(didInitialTailScroll)
    var hasNewContentWhileAway by mutableStateOf(hasNewContentWhileAway)
    var showSearch by mutableStateOf(showSearch)
    var searchQuery by mutableStateOf(searchQuery)
    var currentMatchIndex by mutableIntStateOf(currentMatchIndex)

    fun onScrollSettled(isAtBottom: Boolean) {
        shouldFollowTail = isAtBottom
        if (isAtBottom) {
            hasNewContentWhileAway = false
        }
    }

    fun onTailContentChanged(hasRenderableTail: Boolean): Boolean {
        val shouldScrollToTail = didInitialTailScroll && hasRenderableTail && shouldFollowTail
        if (didInitialTailScroll && hasRenderableTail && !shouldFollowTail) {
            hasNewContentWhileAway = true
        }
        return shouldScrollToTail
    }

    fun onJumpToBottom() {
        shouldFollowTail = true
        hasNewContentWhileAway = false
    }

    fun onContentReady(hasRenderableTail: Boolean): InitialTailDecision {
        val decision = when {
            !hasRenderableTail -> InitialTailDecision.NoContent
            didInitialTailScroll -> InitialTailDecision.KeepRestoredPosition
            shouldFollowTail -> InitialTailDecision.ScrollToTail
            else -> InitialTailDecision.KeepRestoredPosition
        }
        if (hasRenderableTail && !didInitialTailScroll) {
            didInitialTailScroll = true
        }
        return decision
    }

    companion object {
        val Saver: Saver<ChatScrollRestorationState, Any> = listSaver(
            save = {
                listOf(
                    it.shouldFollowTail,
                    it.didInitialTailScroll,
                    it.hasNewContentWhileAway,
                    it.showSearch,
                    it.searchQuery,
                    it.currentMatchIndex,
                )
            },
            restore = {
                ChatScrollRestorationState(
                    shouldFollowTail = it[0] as Boolean,
                    didInitialTailScroll = it[1] as Boolean,
                    hasNewContentWhileAway = it[2] as Boolean,
                    showSearch = it[3] as Boolean,
                    searchQuery = it[4] as String,
                    currentMatchIndex = it[5] as Int,
                )
            }
        )
    }
}
