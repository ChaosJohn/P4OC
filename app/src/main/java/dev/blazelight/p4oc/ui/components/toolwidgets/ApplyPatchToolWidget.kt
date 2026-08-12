package dev.blazelight.p4oc.ui.components.toolwidgets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.blazelight.p4oc.domain.model.Part
import dev.blazelight.p4oc.ui.theme.LocalOpenCodeTheme
import dev.blazelight.p4oc.ui.theme.SemanticColors
import dev.blazelight.p4oc.ui.theme.Spacing
import dev.blazelight.p4oc.ui.theme.TuiCodeFontSize
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal enum class PatchFileAction(val marker: String) {
    ADD("A"),
    UPDATE("M"),
    DELETE("D"),
}

internal data class PatchFileChange(
    val path: String,
    val action: PatchFileAction,
)

internal fun patchText(tool: Part.Tool): String? =
    tool.state.input["patchText"]?.jsonPrimitive?.contentOrNull
        ?: tool.state.input["patch"]?.jsonPrimitive?.contentOrNull

internal fun parseApplyPatchFiles(patch: String): List<PatchFileChange> = patch.lineSequence()
    .mapNotNull { line ->
        PATCH_FILE_HEADERS.firstNotNullOfOrNull { (prefix, action) ->
            line.removePrefix(prefix).takeIf { it != line && it.isNotBlank() }
                ?.let { PatchFileChange(path = it.trim(), action = action) }
        }
    }
    .distinctBy { it.action to it.path }
    .toList()

internal fun applyPatchCompactDescription(tool: Part.Tool): String {
    val count = patchText(tool)?.let(::parseApplyPatchFiles)?.size ?: 0
    return if (count > 0) "Patch: $count file(s)" else "Apply patch"
}

@Composable
@Suppress("FunctionNaming")
internal fun ApplyPatchWidgetExpanded(
    tool: Part.Tool,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val patch = patchText(tool)
    val files = patch?.let(::parseApplyPatchFiles).orEmpty()
    if (patch == null || files.isEmpty()) {
        DefaultWidgetExpanded(
            tool = tool,
            onClick = onClick,
            showApprovalActions = false,
            onToolApprove = {},
            onToolDeny = {},
            modifier = modifier,
        )
        return
    }

    val theme = LocalOpenCodeTheme.current
    Column(
        modifier = modifier
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .background(theme.backgroundPanel.copy(alpha = 0.5f))
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(
            text = applyPatchCompactDescription(tool),
            style = MaterialTheme.typography.labelMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = TuiCodeFontSize.lg,
            ),
            color = theme.text,
        )
        files.forEach { change -> PatchFileRow(change) }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp)
                .background(theme.backgroundElement)
                .horizontalScroll(rememberScrollState())
                .verticalScroll(rememberScrollState())
                .padding(Spacing.sm),
        ) {
            Column {
                patch.lineSequence()
                    .filterNot { it == "*** Begin Patch" || it == "*** End Patch" }
                    .forEach { line -> PatchLine(line) }
            }
        }
    }
}

@Composable
@Suppress("FunctionNaming")
private fun PatchFileRow(change: PatchFileChange) {
    val theme = LocalOpenCodeTheme.current
    val color = when (change.action) {
        PatchFileAction.ADD -> theme.success
        PatchFileAction.UPDATE -> theme.accent
        PatchFileAction.DELETE -> theme.error
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(change.action.marker, color = color, fontFamily = FontFamily.Monospace)
        Text(
            text = change.path,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall,
            color = theme.textMuted,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
@Suppress("FunctionNaming")
private fun PatchLine(line: String) {
    val theme = LocalOpenCodeTheme.current
    val (background, foreground) = when {
        line.startsWith("*** Add File:") -> theme.success.copy(alpha = 0.12f) to theme.success
        line.startsWith("*** Update File:") -> theme.accent.copy(alpha = 0.12f) to theme.accent
        line.startsWith("*** Delete File:") -> theme.error.copy(alpha = 0.12f) to theme.error
        line.startsWith("+") -> SemanticColors.Diff.addedBackground to SemanticColors.Diff.addedText
        line.startsWith("-") -> SemanticColors.Diff.removedBackground to SemanticColors.Diff.removedText
        line.startsWith("@@") -> theme.accent.copy(alpha = 0.1f) to theme.accent
        else -> Color.Transparent to theme.textMuted
    }
    Text(
        text = line,
        modifier = Modifier.background(background).padding(horizontal = Spacing.xs),
        style = MaterialTheme.typography.bodySmall,
        color = foreground,
        fontFamily = FontFamily.Monospace,
        fontSize = TuiCodeFontSize.sm,
    )
}

private val PATCH_FILE_HEADERS = listOf(
    "*** Add File:" to PatchFileAction.ADD,
    "*** Update File:" to PatchFileAction.UPDATE,
    "*** Delete File:" to PatchFileAction.DELETE,
)
