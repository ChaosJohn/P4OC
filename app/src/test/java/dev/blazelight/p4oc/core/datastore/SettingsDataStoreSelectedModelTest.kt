package dev.blazelight.p4oc.core.datastore

import dev.blazelight.p4oc.data.remote.dto.ModelInput
import dev.blazelight.p4oc.domain.server.ServerRef
import dev.blazelight.p4oc.domain.workspace.Workspace
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsDataStoreSelectedModelTest {
    @Test
    fun `composer selection round trips explicit variant and default`() {
        val model = ModelInput(providerID = "openai", modelID = "gpt-5")
        val high = SessionComposerSelection(model = model, variant = "high", pendingServerSync = true)
        val selectionKey = "workspace-session"

        val withHigh = updatedSessionComposerSelections(null, selectionKey, high)
        assertEquals(high, composerSelectionForSession(withHigh, selectionKey))

        val explicitDefault = high.copy(variant = null)
        val withDefault = updatedSessionComposerSelections(withHigh, selectionKey, explicitDefault)
        assertEquals(explicitDefault, composerSelectionForSession(withDefault, selectionKey))
    }

    @Test
    fun `composer selection key isolates server workspace and session`() {
        val serverA = ServerRef.fromEndpointKey("http://server-a")
        val serverB = ServerRef.fromEndpointKey("http://server-b")

        val keys = setOf(
            sessionComposerSelectionKey(Workspace(serverA, "/repo-a"), "session-1"),
            sessionComposerSelectionKey(Workspace(serverA, "/repo-b"), "session-1"),
            sessionComposerSelectionKey(Workspace(serverB, "/repo-a"), "session-1"),
            sessionComposerSelectionKey(Workspace(serverA, "/repo-a"), "session-2"),
        )

        assertEquals(4, keys.size)
    }

    @Test
    fun `malformed composer state recovers for lookup and next write`() {
        assertNull(composerSelectionForSession("not-json", "session"))
        val selection = SessionComposerSelection(
            model = ModelInput(providerID = "anthropic", modelID = "claude-3"),
            variant = "high",
        )

        val recovered = updatedSessionComposerSelections("not-json", "session", selection)

        assertEquals(selection, composerSelectionForSession(recovered, "session"))
    }

    @Test
    fun `lookup returns stored model and null for unknown session`() {
        val stored = """{"session-1":"anthropic/claude-3","session-2":"openai/gpt-4"}"""

        assertEquals(
            ModelInput(providerID = "anthropic", modelID = "claude-3"),
            selectedModelForSession(stored, "session-1"),
        )
        assertNull(selectedModelForSession(stored, "unknown"))
    }

    @Test
    fun `model id containing slash round trips`() {
        val model = ModelInput(providerID = "openrouter", modelID = "anthropic/claude-sonnet")

        val stored = updatedSessionModelSelections(null, "session", model)

        assertEquals(model, selectedModelForSession(stored, "session"))
    }

    @Test
    fun `oldest selection is evicted when cap is exceeded`() {
        var stored: String? = null
        repeat(MAX_SESSION_MODEL_SELECTIONS + 1) { index ->
            stored = updatedSessionModelSelections(
                stored,
                "session-$index",
                ModelInput(providerID = "provider", modelID = "model-$index"),
            )
        }

        val selections = Json.decodeFromString<LinkedHashMap<String, String>>(checkNotNull(stored))
        assertEquals(MAX_SESSION_MODEL_SELECTIONS, selections.size)
        assertFalse("session-0" in selections)
        assertEquals("provider/model-1", selections["session-1"])
    }

    @Test
    fun `malformed state recovers for lookup and next write`() {
        assertNull(selectedModelForSession("not-json", "session"))
        val model = ModelInput(providerID = "anthropic", modelID = "claude-3")

        val recovered = updatedSessionModelSelections("not-json", "session", model)

        assertEquals(model, selectedModelForSession(recovered, "session"))
    }
}
