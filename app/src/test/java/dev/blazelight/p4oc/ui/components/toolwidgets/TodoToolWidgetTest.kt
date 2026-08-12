package dev.blazelight.p4oc.ui.components.toolwidgets

import dev.blazelight.p4oc.domain.model.Part
import dev.blazelight.p4oc.domain.model.ToolState
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TodoToolWidgetTest {
    @Test
    fun `parses todo write input without ids and applies safe defaults`() {
        val input = buildJsonObject {
            put(
                "todos",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("content", "Check persistence")
                            put("status", "in_progress")
                            put("priority", "high")
                        }
                    )
                    add(
                        buildJsonObject {
                            put("content", "Run tests")
                            put("status", "completed")
                        }
                    )
                }
            )
        }

        val todos = todosFromToolInput(input).orEmpty()

        assertEquals(2, todos.size)
        assertEquals("tool-todo-0", todos.first().id)
        assertEquals("medium", todos.last().priority)
        assertEquals("Todos 1/2", todoCompactDescription(tool(input)))
    }

    @Test
    fun `returns null when input does not contain todo array`() {
        assertNull(todosFromToolInput(buildJsonObject { put("content", "not a list") }))
    }

    private fun tool(input: kotlinx.serialization.json.JsonObject) = Part.Tool(
        id = "part",
        sessionID = "session",
        messageID = "message",
        callID = "call",
        toolName = "todowrite",
        state = ToolState.Completed(
            input = input,
            output = "[]",
            title = "Updated todo list",
            startedAt = 1,
            endedAt = 2,
        ),
    )
}
