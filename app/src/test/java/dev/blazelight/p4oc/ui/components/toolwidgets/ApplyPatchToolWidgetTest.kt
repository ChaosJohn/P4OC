package dev.blazelight.p4oc.ui.components.toolwidgets

import dev.blazelight.p4oc.domain.model.Part
import dev.blazelight.p4oc.domain.model.ToolState
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class ApplyPatchToolWidgetTest {
    @Test
    fun `parses add update and delete file headers`() {
        val files = parseApplyPatchFiles(
            """
            *** Begin Patch
            *** Update File: src/Main.kt
            @@
            -old
            +new
            *** Add File: src/New.kt
            +content
            *** Delete File: src/Old.kt
            *** End Patch
            """.trimIndent()
        )

        assertEquals(
            listOf(
                PatchFileChange("src/Main.kt", PatchFileAction.UPDATE),
                PatchFileChange("src/New.kt", PatchFileAction.ADD),
                PatchFileChange("src/Old.kt", PatchFileAction.DELETE),
            ),
            files,
        )
    }

    @Test
    fun `compact description reports changed file count`() {
        val tool = Part.Tool(
            id = "part",
            sessionID = "session",
            messageID = "message",
            callID = "call",
            toolName = "apply_patch",
            state = ToolState.Completed(
                input = buildJsonObject {
                    put(
                        "patchText",
                        "*** Begin Patch\n*** Update File: a.kt\n*** Add File: b.kt\n*** End Patch"
                    )
                },
                output = "Success",
                title = "Applied patch",
                startedAt = 1,
                endedAt = 2,
            ),
        )

        assertEquals("Patch: 2 file(s)", applyPatchCompactDescription(tool))
    }
}
