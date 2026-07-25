package dev.blazelight.p4oc.ui.components.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.blazelight.p4oc.R
import dev.blazelight.p4oc.data.remote.dto.AgentDto
import dev.blazelight.p4oc.data.remote.dto.ModelDto
import dev.blazelight.p4oc.data.remote.dto.ModelInput
import dev.blazelight.p4oc.data.remote.dto.reasoningEfforts
import dev.blazelight.p4oc.ui.theme.LocalOpenCodeTheme
import dev.blazelight.p4oc.ui.theme.SemanticColors
import dev.blazelight.p4oc.ui.theme.Sizing
import dev.blazelight.p4oc.ui.theme.Spacing

@Composable
private fun getAgentColor(agent: AgentDto?): Color {
    if (agent == null) return SemanticColors.AgentSelector.build

    agent.color?.let { hex ->
        try {
            return Color(android.graphics.Color.parseColor(hex))
        } catch (_: IllegalArgumentException) {
            // Server-provided colors are optional; fall back to the stable name-derived color.
        }
    }

    return SemanticColors.AgentSelector.forName(agent.name)
}

data class EnhancedModelInfo(
    val model: ModelInput,
    val name: String,
    val providerName: String,
    val contextWindow: Int?,
    val hasReasoning: Boolean,
    val hasTools: Boolean,
    val reasoningEfforts: List<String> = emptyList(),
    val isFavorite: Boolean = false,
    val isRecent: Boolean = false
)

@Composable
@Suppress("CyclomaticComplexMethod", "LongParameterList", "LongMethod", "FunctionNaming")
fun ModelAgentSelectorBar(
    availableAgents: List<AgentDto>,
    selectedAgent: String?,
    onAgentSelected: (String) -> Unit,
    availableModels: List<Pair<String, ModelDto>>,
    selectedModel: ModelInput?,
    onModelSelected: (ModelInput) -> Unit,
    selectedReasoningEffort: String?,
    onReasoningEffortSelected: (String?) -> Unit,
    favoriteModels: Set<ModelInput> = emptySet(),
    recentModels: List<ModelInput> = emptyList(),
    onToggleFavorite: (ModelInput) -> Unit = {},
    usedContextTokens: Int? = null,
    providerNames: Map<String, String> = emptyMap(),
    modifier: Modifier = Modifier
) {
    val theme = LocalOpenCodeTheme.current
    var showModelPicker by remember { mutableStateOf(false) }
    var showAgentPicker by remember { mutableStateOf(false) }

    val selectModelText = stringResource(R.string.select_model)
    val selectedModelDto = remember(selectedModel, availableModels) {
        if (selectedModel == null) {
            null
        } else {
            availableModels.find {
                it.first == selectedModel.providerID && it.second.id == selectedModel.modelID
            }?.second
        }
    }
    val selectedModelName = remember(selectedModel, selectedModelDto, selectModelText) {
        selectedModelDto?.name ?: selectedModel?.modelID ?: selectModelText
    }
    val selectedReasoningEfforts = remember(selectedModelDto) {
        selectedModelDto?.reasoningEfforts().orEmpty()
    }

    Surface(
        modifier = modifier.fillMaxWidth().testTag("agent_selector"),
        color = theme.background
    ) {
        Row(
            modifier = Modifier.padding(
                start = Spacing.md,
                end = Spacing.md,
                top = Spacing.md,
                bottom = Spacing.xs,
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
          Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (availableAgents.isNotEmpty()) {
                val currentAgent = availableAgents.find { it.name == selectedAgent }
                val agentColor = getAgentColor(currentAgent)
                val currentAgentName = (selectedAgent ?: availableAgents.first().name).lowercase()
                val agentSelectorDescription = stringResource(
                    R.string.agent_selector_current,
                    currentAgentName
                )

                Box {
                    Surface(
                        onClick = { showAgentPicker = true },
                        shape = RectangleShape,
                        color = agentColor.copy(alpha = 0.1f),
                        border = androidx.compose.foundation.BorderStroke(
                            Sizing.strokeMd,
                            agentColor.copy(alpha = 0.4f)
                        ),
                        modifier = Modifier
                            .height(Sizing.buttonHeightMd)
                            .semantics { contentDescription = agentSelectorDescription }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = Spacing.lg),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                        ) {
                            Text(
                                text = "@$currentAgentName",
                                style = MaterialTheme.typography.labelMedium,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                color = agentColor
                            )
                            Text(
                                text = "▾",
                                color = agentColor,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                        }
                    }
                    DropdownMenu(
                        expanded = showAgentPicker,
                        onDismissRequest = { showAgentPicker = false }
                    ) {
                        availableAgents.forEach { agent ->
                            DropdownMenuItem(
                                text = {
                                    Column(modifier = Modifier.widthIn(max = Sizing.panelWidthLg)) {
                                        Text(
                                            text = "@${agent.name.lowercase()}",
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                            style = MaterialTheme.typography.labelLarge
                                        )
                                        agent.description?.takeIf { it.isNotBlank() }?.let { description ->
                                            Text(
                                                text = description,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = theme.textMuted,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    onAgentSelected(agent.name)
                                    showAgentPicker = false
                                },
                                leadingIcon = if (agent.name == selectedAgent) {
                                    { Text("✓", color = getAgentColor(agent)) }
                                } else {
                                    null
                                }
                            )
                        }
                    }
                }
            }

            if (availableAgents.isNotEmpty() && availableModels.isNotEmpty()) {
                VerticalDivider(
                    modifier = Modifier.height(Sizing.iconLg),
                    color = theme.border
                )
            }

            if (availableModels.isNotEmpty()) {
                Surface(
                    onClick = { showModelPicker = true },
                    shape = androidx.compose.ui.graphics.RectangleShape,
                    color = theme.background,
                    border = androidx.compose.foundation.BorderStroke(Sizing.strokeMd, theme.border),
                    modifier = Modifier.height(Sizing.buttonHeightMd)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = Spacing.lg),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        Text(
                            text = selectedModelName,
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = theme.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = Sizing.panelWidthLg)
                        )
                        Text(
                            text = "▾",
                            color = theme.textMuted,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                }
            }

            if (selectedReasoningEfforts.size > 1) {
                ReasoningEffortSelect(
                    efforts = selectedReasoningEfforts,
                    selectedEffort = selectedReasoningEffort,
                    onEffortSelected = onReasoningEffortSelected,
                )
            }
          }
          val ctxWindow = selectedModelDto?.limit?.context
          if (usedContextTokens != null && ctxWindow != null && ctxWindow > 0) {
              Spacer(Modifier.width(Spacing.sm))
              ContextUsageMeter(
                  percent = (usedContextTokens.toLong() * 100 / ctxWindow).toInt().coerceIn(0, 100)
              )
          }
        }
    }

    if (showModelPicker) {
        ModelPickerDialog(
            availableModels = availableModels,
            selectedModel = selectedModel,
            favoriteModels = favoriteModels,
            recentModels = recentModels,
            providerNames = providerNames,
            onModelSelected = {
                onModelSelected(it)
                showModelPicker = false
            },
            onToggleFavorite = onToggleFavorite,
            onDismiss = { showModelPicker = false }
        )
    }
}

/**
 * Compact context-window usage meter for the composer control row — `▓▓▓░ 62%`.
 * Matches design 05; four cells, warning/error tint as usage climbs.
 */
@Composable
private fun ContextUsageMeter(percent: Int) {
    val theme = LocalOpenCodeTheme.current
    val filled = (percent * 4 + 99) / 100 // ceil to nearest of 4 cells
    val bar = buildString { repeat(4) { append(if (it < filled) '▓' else '░') } }
    val color = when {
        percent >= 90 -> theme.error
        percent >= 60 -> theme.warning
        else -> theme.textMuted
    }
    Text(
        text = "$bar $percent%",
        style = MaterialTheme.typography.labelSmall,
        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        color = color,
        maxLines = 1,
        modifier = Modifier.semantics {
            contentDescription = "Context usage $percent percent"
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerDialog(
    availableModels: List<Pair<String, ModelDto>>,
    selectedModel: ModelInput?,
    favoriteModels: Set<ModelInput>,
    recentModels: List<ModelInput>,
    onModelSelected: (ModelInput) -> Unit,
    onToggleFavorite: (ModelInput) -> Unit,
    onDismiss: () -> Unit,
    providerNames: Map<String, String> = emptyMap()
) {
    val theme = LocalOpenCodeTheme.current
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<String?>(null) }

    val enhancedModels = remember(availableModels, favoriteModels, recentModels, providerNames) {
        availableModels.map { (providerId, model) ->
            val modelInput = ModelInput(providerID = providerId, modelID = model.id)
            EnhancedModelInfo(
                model = modelInput,
                name = model.name,
                providerName = providerNames[providerId] ?: providerId,
                contextWindow = model.limit?.context,
                hasReasoning = model.capabilities?.reasoning == true,
                hasTools = model.capabilities?.toolcall == true,
                reasoningEfforts = model.reasoningEfforts(),
                isFavorite = modelInput in favoriteModels,
                isRecent = modelInput in recentModels
            )
        }
    }

    // Provider id paired with the label to show for it — the provider's own display
    // name when it has one, so config-defined providers read "LLM Proxy", not "llmproxy".
    val providers = remember(enhancedModels) {
        enhancedModels
            .map { it.model.providerID to it.providerName }
            .distinct()
            .sortedBy { it.second.lowercase() }
    }

    val filteredModels = remember(enhancedModels, searchQuery, selectedCategory) {
        enhancedModels.filter { model ->
            val matchesSearch = searchQuery.isBlank() ||
                model.name.contains(searchQuery, ignoreCase = true) ||
                model.providerName.contains(searchQuery, ignoreCase = true) ||
                model.model.providerID.contains(searchQuery, ignoreCase = true)
            val matchesCategory = selectedCategory == null || model.model.providerID == selectedCategory
            matchesSearch && matchesCategory
        }.sortedWith(
            compareByDescending<EnhancedModelInfo> { it.isFavorite }
                .thenByDescending { it.isRecent }
                .thenBy { it.name }
        )
    }

    val favorites = filteredModels.filter { it.isFavorite }
    val recents = filteredModels.filter { it.isRecent && !it.isFavorite }
    val others = filteredModels.filter { !it.isFavorite && !it.isRecent }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
      Box(modifier = Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.7f)
                .testTag("model_picker_sheet"),
            shape = RectangleShape,
            color = theme.backgroundPanel,
            border = androidx.compose.foundation.BorderStroke(Sizing.strokeMd, theme.border)
        ) {
            Column {
                // Header: "Select Model" title + close — flush on the sheet's panel bg.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = Spacing.md, end = Spacing.xs, top = Spacing.sm, bottom = Spacing.sm),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.select_model),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = theme.text
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(Sizing.iconButtonSm)
                    ) {
                        Text(
                            text = "×",
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = FontFamily.Monospace,
                            color = theme.textMuted
                        )
                    }
                }

                // Search field — inset element-bg box with `/` prompt prefix.
                // Height is pinned (not `heightIn`/intrinsic) so the box can't grow as text
                // is typed — BasicTextField's measured height otherwise nudges the container.
                Surface(
                    color = theme.backgroundElement,
                    shape = RectangleShape,
                    border = androidx.compose.foundation.BorderStroke(Sizing.strokeMd, theme.borderSubtle),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(Sizing.buttonHeightMd)
                        .padding(horizontal = Spacing.md, vertical = Spacing.xs)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = Spacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        Text(
                            text = "/",
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            color = theme.primary
                        )
                        androidx.compose.foundation.text.BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.weight(1f).testTag("model_search_field"),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = theme.text,
                                fontFamily = FontFamily.Monospace
                            ),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(theme.primary),
                            singleLine = true,
                            decorationBox = { innerTextField ->
                                Box {
                                    if (searchQuery.isEmpty()) {
                                        Text(
                                            text = stringResource(R.string.models_search_placeholder),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontFamily = FontFamily.Monospace,
                                            color = theme.textMuted
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        )
                        if (searchQuery.isNotEmpty()) {
                            IconButton(
                                onClick = { searchQuery = "" },
                                modifier = Modifier.size(Sizing.iconButtonSm)
                            ) {
                                Icon(
                                    Icons.Default.Clear,
                                    contentDescription = stringResource(R.string.clear),
                                    tint = theme.textMuted,
                                    modifier = Modifier.size(Sizing.iconXs)
                                )
                            }
                        }
                    }
                }

                // Provider filter chips
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = Spacing.md, vertical = Spacing.xs)
                        .selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    TuiFilterTab(
                        text = stringResource(R.string.all),
                        selected = selectedCategory == null,
                        onClick = { selectedCategory = null }
                    )
                    providers.forEach { (providerId, label) ->
                        TuiFilterTab(
                            text = label,
                            selected = selectedCategory == providerId,
                            onClick = {
                                selectedCategory = if (selectedCategory == providerId) null else providerId
                            }
                        )
                    }
                }

                HorizontalDivider(color = theme.border, thickness = Sizing.dividerThickness)

                // Model list
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .selectableGroup(),
                    contentPadding = PaddingValues(vertical = Spacing.xs)
                ) {
                    if (favorites.isNotEmpty()) {
                        item {
                            TuiSectionHeader(text = "★ favorites")
                        }
                        items(favorites, key = { "${it.model.providerID}/${it.model.modelID}" }) { model ->
                            TuiModelListItem(
                                model = model,
                                isSelected = model.model == selectedModel,
                                onSelect = { onModelSelected(model.model) },
                                onToggleFavorite = { onToggleFavorite(model.model) }
                            )
                        }
                    }

                    if (recents.isNotEmpty()) {
                        item {
                            TuiSectionHeader(text = "recent")
                        }
                        items(recents, key = { "${it.model.providerID}/${it.model.modelID}" }) { model ->
                            TuiModelListItem(
                                model = model,
                                isSelected = model.model == selectedModel,
                                onSelect = { onModelSelected(model.model) },
                                onToggleFavorite = { onToggleFavorite(model.model) }
                            )
                        }
                    }

                    if (others.isNotEmpty()) {
                        item {
                            TuiSectionHeader(
                                text = if (favorites.isEmpty() && recents.isEmpty()) "models" else "other"
                            )
                        }
                        items(others, key = { "${it.model.providerID}/${it.model.modelID}" }) { model ->
                            TuiModelListItem(
                                model = model,
                                isSelected = model.model == selectedModel,
                                onSelect = { onModelSelected(model.model) },
                                onToggleFavorite = { onToggleFavorite(model.model) }
                            )
                        }
                    }

                    if (filteredModels.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(Spacing.xl),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "-- no models found --",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace,
                                    color = theme.textMuted
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = theme.border, thickness = Sizing.dividerThickness)

                // Footer with count
                Text(
                    text = "${filteredModels.size} models",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = theme.textMuted,
                    modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)
                )
            }
        }
      }
    }
}

@Composable
private fun ReasoningEffortSelect(
    efforts: List<String>,
    selectedEffort: String?,
    onEffortSelected: (String?) -> Unit,
) {
    val theme = LocalOpenCodeTheme.current
    var expanded by remember { mutableStateOf(false) }
    val options = remember(efforts) { listOf<String?>(null) + efforts }
    val currentLabel = selectedEffort ?: "default"

    Box {
        Surface(
            onClick = { expanded = true },
            shape = RectangleShape,
            color = theme.background,
            border = androidx.compose.foundation.BorderStroke(Sizing.strokeMd, theme.border),
            modifier = Modifier
                .height(Sizing.buttonHeightMd)
                .testTag("reasoning_effort_select")
        ) {
            Box(
                modifier = Modifier.padding(horizontal = Spacing.lg),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = currentLabel,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = if (selectedEffort == null) theme.textMuted else theme.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = theme.backgroundPanel,
        ) {
            options.forEach { effort ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = effort ?: "default",
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = if (effort == selectedEffort) theme.accent else theme.text,
                        )
                    },
                    onClick = {
                        onEffortSelected(effort)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun TuiFilterTab(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val theme = LocalOpenCodeTheme.current
    Surface(
        color = if (selected) theme.backgroundElement else Color.Transparent,
        shape = RectangleShape,
        modifier = Modifier.selectable(
            selected = selected,
            onClick = onClick,
            role = Role.Tab,
        ),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) theme.primary else theme.textMuted,
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs)
        )
    }
}

/** Uppercase, letter-spaced section label — `RECENT` / `OTHER`, matching design 06. */
@Composable
private fun TuiSectionHeader(text: String) {
    val theme = LocalOpenCodeTheme.current
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 1.sp,
        color = theme.textMuted,
        modifier = Modifier.padding(
            start = Spacing.md,
            end = Spacing.md,
            top = Spacing.md,
            bottom = Spacing.xs
        )
    )
}

@Composable
private fun TuiModelListItem(
    model: EnhancedModelInfo,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    val theme = LocalOpenCodeTheme.current
    val addFavoriteDescription = stringResource(R.string.cd_add_to_favorites)
    val removeFavoriteDescription = stringResource(R.string.cd_remove_from_favorites)
    val providerColor = SemanticColors.Provider.forName(model.model.providerID).first

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(if (isSelected) theme.backgroundElement else Color.Transparent)
            .selectable(
                selected = isSelected,
                onClick = onSelect,
                role = Role.RadioButton,
            )
    ) {
        // Left accent strip on the selected row.
        Box(
            modifier = Modifier
                .width(Sizing.strokeThick)
                .fillMaxHeight()
                .background(if (isSelected) theme.success else Color.Transparent)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Selection chevron (reserves width so squares stay aligned).
            Text(
                text = if (isSelected) ">" else " ",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = theme.primary
            )

            // Provider color square.
            Text(
                text = "■",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = providerColor
            )

            // Model info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = model.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    color = theme.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // Metadata row: provider · context
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = model.providerName,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = theme.textMuted
                    )
                    model.contextWindow?.let { ctx ->
                        if (ctx > 0) {
                            Text(
                                text = "· ${formatContextWindow(ctx)}",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = theme.textMuted
                            )
                        }
                    }
                }
            }

            // Capability badges + favorite, right-aligned.
            if (model.hasReasoning) {
                Text(
                    text = "[R]",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = theme.textMuted
                )
            }
            if (model.hasTools) {
                Text(
                    text = "[T]",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = theme.textMuted
                )
            }
            IconButton(
                onClick = onToggleFavorite,
                modifier = Modifier
                    .size(Sizing.iconButtonSm)
                    .semantics {
                        contentDescription = if (model.isFavorite) {
                            removeFavoriteDescription
                        } else {
                            addFavoriteDescription
                        }
                    }
            ) {
                Text(
                    text = if (model.isFavorite) "★" else "☆",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (model.isFavorite) theme.warning else theme.textMuted
                )
            }
        }
    }
}

private const val CONTEXT_MILLION = 1_000_000
private const val CONTEXT_THOUSAND = 1_000

/** `200K`, `1M`, `64K` — uppercase, matching design 06. */
private fun formatContextWindow(ctx: Int): String = when {
    ctx >= CONTEXT_MILLION -> "${ctx / CONTEXT_MILLION}M"
    ctx >= CONTEXT_THOUSAND -> "${ctx / CONTEXT_THOUSAND}K"
    else -> ctx.toString()
}
