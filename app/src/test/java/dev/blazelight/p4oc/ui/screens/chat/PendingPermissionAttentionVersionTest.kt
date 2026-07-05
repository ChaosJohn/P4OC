package dev.blazelight.p4oc.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PendingPermissionAttentionVersionTest {

    @Test
    fun `permission arrival changes the attention version`() {
        val before = pendingPermissionAttentionVersion(emptySet())
        val after = pendingPermissionAttentionVersion(setOf("call_abc"))

        assertNotEquals(
            "Pending permission arrival must change the tail-attention version so the chat scrolls to show it",
            before,
            after
        )
    }

    @Test
    fun `permission resolution changes the attention version`() {
        val pending = pendingPermissionAttentionVersion(setOf("call_abc"))
        val resolved = pendingPermissionAttentionVersion(emptySet())

        assertNotEquals("Resolving a permission must change the version", pending, resolved)
    }

    @Test
    fun `same permissions produce same version`() {
        val a = pendingPermissionAttentionVersion(setOf("call_abc", "call_def"))
        val b = pendingPermissionAttentionVersion(setOf("call_def", "call_abc"))

        assertEquals("Same permission set must produce same version regardless of order", a, b)
    }
}
