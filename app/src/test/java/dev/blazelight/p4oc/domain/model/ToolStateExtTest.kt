package dev.blazelight.p4oc.domain.model

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolStateExtTest {

    @Test
    fun `question data preserves custom=false flag`() {
        val input = buildJsonObject {
            putJsonArray("questions") {
                add(
                    buildJsonObject {
                        put("header", "Pick option")
                        put("question", "Which?")
                        put("custom", false)
                        putJsonArray("options") {
                            add(
                                buildJsonObject {
                                    put("label", "A")
                                    put("description", "desc-a")
                                }
                            )
                            add(
                                buildJsonObject {
                                    put("label", "B")
                                    put("description", "desc-b")
                                }
                            )
                        }
                    }
                )
            }
        }
        val pending = ToolState.Pending(input = input, rawInput = "")

        val data = pending.asQuestionData()

        assertNotNull(data)
        assertEquals(1, data!!.questions.size)
        val q = data.questions[0]
        assertEquals("Pick option", q.header)
        assertEquals("Which?", q.question)
        assertEquals(2, q.options.size)
        assertEquals("A", q.options[0].label)
        assertEquals("desc-a", q.options[0].description)
        assertFalse("custom flag must be preserved as false", q.custom)
    }

    @Test
    fun `question data defaults custom to true when absent`() {
        val input = buildJsonObject {
            putJsonArray("questions") {
                add(
                    buildJsonObject {
                        put("header", "Pick")
                        put("question", "Which?")
                        putJsonArray("options") {
                            add(
                                buildJsonObject {
                                    put("label", "A")
                                    put("description", "desc-a")
                                }
                            )
                        }
                    }
                )
            }
        }
        val pending = ToolState.Pending(input = input, rawInput = "")

        val data = pending.asQuestionData()

        assertNotNull(data)
        assertTrue(data!!.questions[0].custom)
    }

    @Test
    fun `isQuestionTool returns true only for question tool`() {
        val questionTool = Part.Tool(
            id = "p1",
            sessionID = "s1",
            messageID = "m1",
            callID = "c1",
            toolName = "question",
            state = ToolState.Pending(buildJsonObject {}, "")
        )
        val otherTool = questionTool.copy(toolName = "bash")

        assertTrue(questionTool.isQuestionTool())
        assertFalse(otherTool.isQuestionTool())
    }
}
