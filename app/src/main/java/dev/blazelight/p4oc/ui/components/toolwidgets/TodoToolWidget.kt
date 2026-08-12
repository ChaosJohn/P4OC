package dev.blazelight.p4oc.ui.components.toolwidgets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import dev.blazelight.p4oc.domain.model.Part
import dev.blazelight.p4oc.domain.model.Todo
import dev.blazelight.p4oc.ui.components.todo.TODO_STATUS_CANCELLED
import dev.blazelight.p4oc.ui.components.todo.TODO_STATUS_COMPLETED
import dev.blazelight.p4oc.ui.components.todo.TODO_STATUS_IN_PROGRESS
import dev.blazelight.p4oc.ui.theme.LocalOpenCodeTheme
import dev.blazelight.p4oc.ui.theme.SemanticColors
import dev.blazelight.p4oc.ui.theme.Spacing
import dev.blazelight.p4oc.ui.theme.TuiCodeFontSize
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal fun todosFromToolInput(input: JsonObject): List<Todo>? {
    val values = input["todos"] as? JsonArray ?: return null
    return values.mapIndexedNotNull { index, element ->
        val item = element as? JsonObject ?: return@mapIndexedNotNull null
        val content = item["content"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: return@mapIndexedNotNull null
        Todo(
            id = item["id"]?.jsonPrimitive?.contentOrNull ?: "tool-todo-$index",
            content = content,
            status = item["status"]?.jsonPrimitive?.contentOrNull ?: "pending",
            priority = item["priority"]?.jsonPrimitive?.contentOrNull ?: "medium",
        )
    }
}

internal fun todoCompactDescription(tool: Part.Tool): String {
    val todos = todosFromToolInput(tool.state.input) ?: return tool.toolName
    val completed = todos.count { it.status == TODO_STATUS_COMPLETED || it.status == TODO_STATUS_CANCELLED }
    return "Todos $completed/${todos.size}"
}

@Composable
@Suppress("FunctionNaming")
internal fun TodoWidgetExpanded(
    tool: Part.Tool,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val todos = todosFromToolInput(tool.state.input)
    if (todos == null) {
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
            text = todoCompactDescription(tool),
            style = MaterialTheme.typography.labelMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = TuiCodeFontSize.lg,
            ),
            color = theme.text,
        )
        todos.forEach { todo -> TodoToolRow(todo) }
    }
}

@Composable
@Suppress("FunctionNaming")
private fun TodoToolRow(todo: Todo) {
    val theme = LocalOpenCodeTheme.current
    val completed = todo.status == TODO_STATUS_COMPLETED
    val cancelled = todo.status == TODO_STATUS_CANCELLED
    val statusColor = when (todo.status) {
        TODO_STATUS_IN_PROGRESS -> SemanticColors.Todo.inProgress
        TODO_STATUS_COMPLETED -> SemanticColors.Todo.completed
        TODO_STATUS_CANCELLED -> SemanticColors.Todo.cancelled
        else -> SemanticColors.Todo.pending
    }
    val marker = when (todo.status) {
        TODO_STATUS_IN_PROGRESS -> "▶"
        TODO_STATUS_COMPLETED -> "✓"
        TODO_STATUS_CANCELLED -> "×"
        else -> "○"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(theme.backgroundElement)
            .padding(Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        Text(marker, color = statusColor, style = MaterialTheme.typography.labelMedium)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            Text(
                text = todo.content,
                style = MaterialTheme.typography.bodySmall,
                color = if (completed || cancelled) theme.textMuted else theme.text,
                textDecoration = if (completed || cancelled) TextDecoration.LineThrough else null,
            )
            Text(
                text = "[${todo.priority.uppercase()}]",
                style = MaterialTheme.typography.labelSmall,
                color = SemanticColors.Todo.forPriority(todo.priority),
            )
        }
    }
}
