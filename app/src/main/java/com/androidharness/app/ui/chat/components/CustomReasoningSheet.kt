package com.androidharness.app.ui.chat.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.androidharness.app.agent.*
import com.androidharness.app.llm.ProviderConfig
import com.androidharness.app.llm.ReasoningMapping
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CustomReasoningSheet(
    config: ProviderConfig,
    saved: CustomReasoningConfig?,
    spec: ThinkingSpecs.Spec,
    onDismiss: () -> Unit,
    onSave: (CustomReasoningConfig?) -> Unit,
) {
    val defaults = remember(spec) {
        spec.levels.map { if (spec.style == ThinkingSpecs.Style.TOGGLE && it != ThinkingLevel.OFF) "On" else it.label }
            .ifEmpty { listOf("On") }
    }
    var levels by rememberSaveable(config.id, config.model) { mutableStateOf(ArrayList(saved?.levels ?: defaults)) }
    var editingLevel by rememberSaveable { mutableStateOf<Int?>(null) }
    var mapping by rememberSaveable(config.id, config.model) { mutableStateOf(saved?.mapping.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<String?>(null) }
    var previewLevel by rememberSaveable { mutableIntStateOf(0) }
    var validating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    val json = remember { Json { prettyPrint = true } }
    LaunchedEffect(mapping, levels.toList(), previewLevel) {
        preview = null
        if (mapping.isNotBlank() && levels.isNotEmpty()) {
            delay(300)
            val name = levels[previewLevel.coerceIn(levels.indices)]
            preview = withContext(Dispatchers.Default) {
                runCatching { json.encodeToString(JsonObject.serializer(), ReasoningMapping.evaluate(mapping, name)) }
                    .getOrElse { it.message ?: "Invalid mapping" }
            }
        }
    }
    fun editLevels(change: MutableList<String>.() -> Unit) {
        levels = ArrayList(levels.toMutableList().apply(change)); error = null
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Customize thinking", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Close thinking settings") }
            }
            Text("${config.name} · ${config.model}", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            Text("Saved for this provider and model. Changes apply to your next message.",
                Modifier.padding(top = 6.dp, bottom = 12.dp), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Reasoning levels (low to high)", style = MaterialTheme.typography.titleSmall)
                Text(CustomReasoning.LEVEL_HELP, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    levels.forEachIndexed { index, name ->
                        FilterChip(selected = editingLevel == index, enabled = !validating,
                            onClick = { editingLevel = if (editingLevel == index) null else index },
                            label = { Text(name.takeIf { it.isNotBlank() }?.let(CustomReasoning::label) ?: "New level") })
                    }
                    IconButton(onClick = {
                        editLevels { add("") }; editingLevel = levels.lastIndex
                    }, enabled = !validating && levels.size < 16) {
                        Icon(Icons.Filled.Add, "Add reasoning level", Modifier.size(20.dp))
                    }
                }
                editingLevel?.takeIf { it in levels.indices }?.let { index ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(levels[index], { value -> editLevels { set(index, value.take(32)) } },
                            modifier = Modifier.weight(1f), singleLine = true, enabled = !validating,
                            label = { Text("Level ${index + 1}") })
                        IconButton(onClick = {
                            editLevels { java.util.Collections.swap(this, index, index - 1) }; editingLevel = index - 1
                        }, enabled = !validating && index > 0) {
                            Icon(Icons.Outlined.ArrowUpward, "Move level ${index + 1} up", Modifier.size(18.dp))
                        }
                        IconButton(onClick = {
                            editLevels { java.util.Collections.swap(this, index, index + 1) }; editingLevel = index + 1
                        }, enabled = !validating && index < levels.lastIndex) {
                            Icon(Icons.Outlined.ArrowDownward, "Move level ${index + 1} down", Modifier.size(18.dp))
                        }
                        IconButton(onClick = { editLevels { removeAt(index) }; editingLevel = null }, enabled = !validating) {
                            Icon(Icons.Outlined.Close, "Remove level ${index + 1}", Modifier.size(18.dp))
                        }
                    }
                }
                Text("Tap a level to rename, reorder, or remove it. Names ignore case: max → Max, HIGH → High.",
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                HorizontalDivider()
                Text("Reasoning parameter mapping (optional)", style = MaterialTheme.typography.titleSmall)
                Text("Use a CEL expression to map reasoningLevel to API request fields. It must return a JSON object, which is merged into the outgoing request body. A custom expression supplies the reasoning fields. Leave blank to use built-in mappings.",
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                Text("reasoningLevel is lowercase (for example: disabled, low, high, max, xhigh). Nested objects merge; null removes a field.",
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                OutlinedTextField(mapping, { mapping = it.take(8_192); error = null },
                    Modifier.fillMaxWidth(), minLines = 5, maxLines = 9, enabled = !validating,
                    label = { Text("CEL expression") },
                    placeholder = { Text("{\"reasoning_effort\": reasoningLevel}", fontFamily = FontFamily.Monospace) },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                if (mapping.isNotBlank() && levels.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Request fields preview", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        var showLevels by remember { mutableStateOf(false) }
                        Box {
                            TextButton(onClick = { showLevels = true }) { Text(levels[previewLevel.coerceIn(levels.indices)].ifBlank { "Unnamed" }) }
                            DropdownMenu(showLevels, { showLevels = false }) {
                                levels.forEachIndexed { i, label -> DropdownMenuItem(text = { Text(label.ifBlank { "Unnamed" }) },
                                    onClick = { previewLevel = i; showLevels = false }) }
                            }
                        }
                    }
                    Surface(shape = MaterialTheme.shapes.medium, color = scheme.surfaceContainerHigh) {
                        Text(preview ?: "Checking expression…", Modifier.fillMaxWidth().padding(12.dp),
                            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
            error?.let { Text(it, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = scheme.error) }
            HorizontalDivider(Modifier.padding(top = 8.dp))
            BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                val stacked = maxWidth < 300.dp * LocalDensity.current.fontScale
                val saveAction: () -> Unit = {
                    val draft = CustomReasoningConfig(levels.toList(), mapping)
                    scope.launch {
                        validating = true
                        val result = withContext(Dispatchers.Default) { runCatching { ReasoningMapping.validate(draft, config, spec) } }
                        validating = false
                        result.onSuccess(onSave).onFailure { error = it.message ?: "Check your reasoning settings." }
                    }
                }
                Column {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (!stacked) TextButton(onClick = { onSave(null) }, enabled = !validating) { Text("Use defaults") }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onDismiss, enabled = !validating) { Text("Cancel", maxLines = 1) }
                        Button(enabled = !validating, onClick = saveAction) { Text(if (validating) "Checking…" else "Save", maxLines = 1) }
                    }
                    if (stacked) TextButton(onClick = { onSave(null) }, enabled = !validating) { Text("Use defaults") }
                }
            }
        }
    }
}
