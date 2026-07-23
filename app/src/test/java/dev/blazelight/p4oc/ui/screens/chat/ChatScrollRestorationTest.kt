package dev.blazelight.p4oc.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatScrollRestorationTest {

    @Test
    fun sameSessionRestoresAwayFromTailWithNewContentInsteadOfForcingTail() {
        val state = ChatScrollRestorationState()

        state.onContentReady(hasRenderableTail = true)
        state.onScrollSettled(isAtBottom = false)
        state.onTailContentChanged(hasRenderableTail = true)

        assertFalse(state.shouldFollowTail)
        assertTrue(state.didInitialTailScroll)
        assertTrue(state.hasNewContentWhileAway)
    }

    @Test
    fun differentSessionsDoNotShareScrollRestorationState() {
        val sessionA = ChatScrollRestorationState()
        val sessionB = ChatScrollRestorationState()

        sessionA.onContentReady(hasRenderableTail = true)
        sessionA.onScrollSettled(isAtBottom = false)
        sessionA.onTailContentChanged(hasRenderableTail = true)

        assertTrue(sessionB.shouldFollowTail)
        assertFalse(sessionB.didInitialTailScroll)
        assertFalse(sessionB.hasNewContentWhileAway)
    }

    @Test
    fun searchNavigationDisablesFollowTailAndRestoresForSameSessionOnly() {
        val state = ChatScrollRestorationState()
        val otherSession = ChatScrollRestorationState()

        state.onContentReady(hasRenderableTail = true)
        state.shouldFollowTail = false
        state.onTailContentChanged(hasRenderableTail = true)

        assertFalse(state.shouldFollowTail)
        assertTrue(state.hasNewContentWhileAway)
        assertTrue(otherSession.shouldFollowTail)
        assertFalse(otherSession.hasNewContentWhileAway)
    }

    @Test
    fun returningToOlderPositionDoesNotForceTailOnNextContentChange() {
        val state = ChatScrollRestorationState()

        state.onContentReady(hasRenderableTail = true)
        state.onScrollSettled(isAtBottom = false)
        state.onTailContentChanged(hasRenderableTail = true)

        assertFalse(state.shouldFollowTail)
        assertTrue(state.hasNewContentWhileAway)
    }

    @Test
    fun jumpToBottomResumesFollowTailAndClearsNewContentAffordance() {
        val state = ChatScrollRestorationState()

        state.onContentReady(hasRenderableTail = true)
        state.onScrollSettled(isAtBottom = false)
        state.onTailContentChanged(hasRenderableTail = true)
        state.onJumpToBottom()

        assertTrue(state.shouldFollowTail)
        assertFalse(state.hasNewContentWhileAway)
    }

    @Test
    fun contentNotReadyDoesNotConsumeInitialTailRestoration() {
        val state = ChatScrollRestorationState()

        assertEquals(InitialTailDecision.NoContent, state.onContentReady(hasRenderableTail = false))
        assertFalse(state.didInitialTailScroll)

        assertEquals(InitialTailDecision.ScrollToTail, state.onContentReady(hasRenderableTail = true))
        assertTrue(state.didInitialTailScroll)
    }

    @Test
    fun initialTailRestorationHappensOnceAndDoesNotOverrideRestoredAwayPosition() {
        val state = ChatScrollRestorationState()

        assertEquals(InitialTailDecision.ScrollToTail, state.onContentReady(hasRenderableTail = true))
        state.onScrollSettled(isAtBottom = false)

        assertEquals(InitialTailDecision.KeepRestoredPosition, state.onContentReady(hasRenderableTail = true))
        assertFalse(state.shouldFollowTail)
    }
}
