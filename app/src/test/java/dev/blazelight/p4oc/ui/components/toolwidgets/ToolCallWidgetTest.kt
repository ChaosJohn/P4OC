package dev.blazelight.p4oc.ui.components.toolwidgets

import dev.blazelight.p4oc.domain.model.Part
import dev.blazelight.p4oc.domain.model.ToolState
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolCallWidgetTest {

    @Test
    fun shellFamilyWithoutCommand_usesActualToolName() {
        assertEquals("shell", getToolCompactDescription(tool("shell")))
        assertEquals("Execute", getToolCompactDescription(tool("Execute")))
        assertEquals(
            "bash",
            getToolCompactDescription(
                tool("bash", command = "   "),
            ),
        )
    }

    @Test
    fun shellFamilyWithCommand_keepsCompactBashLabel() {
        assertEquals(
            "bash echo hello",
            getToolCompactDescription(tool("shell", command = " echo hello ")),
        )
    }

    private fun tool(name: String, command: String? = null): Part.Tool {
        val input = buildJsonObject {
            command?.let { put("command", it) }
        }
        return Part.Tool(
            id = "part",
            sessionID = "session",
            messageID = "message",
            callID = "call",
            toolName = name,
            state = ToolState.Pending(input = input, rawInput = ""),
        )
    }
}
