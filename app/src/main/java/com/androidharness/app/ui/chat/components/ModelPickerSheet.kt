package com.androidharness.app.ui.chat.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.R
import com.androidharness.app.chatgpt.ChatGptProtocol
import com.androidharness.app.data.ModelShortcut
import com.androidharness.app.data.ModelShortcuts
import com.androidharness.app.data.ModelVisibility
import com.androidharness.app.llm.*
import com.androidharness.app.ui.common.ModelReasoningIcon
import com.androidharness.app.ui.common.ProviderMark
import com.androidharness.app.ui.settings.ModelVisibilitySheet
import com.androidharness.app.ui.theme.HarnessMono
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Provider-first quick selection. Show all explicitly bypasses both provider and model visibility. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(
    providers: List<ProviderConfig>,
    activeProviderId: String?,
    activeModel: String?,
    catalogs: Map<String, List<ModelEntry>>,
    onDismiss: () -> Unit,
    onSelect: (providerId: String, model: String?) -> Unit,
    onRefreshCatalog: suspend (providerId: String) -> String?,
    onManageProviders: () -> Unit,
    browseProviderId: String? = null,
    onAddCustomModel: (providerId: String, model: String, reasoning: Boolean?) -> Unit = { _, _, _ -> },
    onDeleteCustomModel: (providerId: String, model: String) -> Unit = { _, _ -> },
    sameModelAcrossChats: Boolean? = null,
    onModelScopeChange: (Boolean) -> Unit = {},
    modelScopeEnabled: Boolean = true,
    modelShortcuts: ModelShortcuts? = null,
    searchAllProvidersInitially: Boolean = false,
    onEnableProvider: (String) -> Unit = {},
    fallbackSummary: String? = null,
    fallbackEditEnabled: Boolean = true,
    onEditFallbacks: () -> Unit = {},
    modelVisibility: ModelVisibility? = null,
    manageModelsInitially: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val shortcuts = remember(context, modelShortcuts) { modelShortcuts ?: ModelShortcuts.get(context) }
    val visibility = remember(context, modelVisibility) { modelVisibility ?: ModelVisibility.get(context) }
    val saved by shortcuts.state.collectAsStateWithLifecycle()
    val visible by visibility.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var showAll by rememberSaveable { mutableStateOf(searchAllProvidersInitially) }
    var listedId by rememberSaveable { mutableStateOf(browseProviderId ?: activeProviderId ?: providers.firstOrNull { it.enabled }?.id) }
    var collection by rememberSaveable { mutableStateOf("All") }
    var thinkOnly by rememberSaveable { mutableStateOf(false) }
    var managing by rememberSaveable { mutableStateOf(manageModelsInitially) }
    var showAddCustom by rememberSaveable { mutableStateOf(false) }
    var reasoningLegend by remember { mutableStateOf(false) }
    var choosingProvider by rememberSaveable { mutableStateOf(false) }
    var pendingProvider by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingModel by rememberSaveable { mutableStateOf<String?>(null) }
    var enableChoice by remember { mutableStateOf<ModelShortcut?>(null) }
    var isRefreshing by remember { mutableStateOf(false) }
    var refreshError by remember { mutableStateOf<String?>(null) }
    val activeProvider = providers.firstOrNull { it.id == activeProviderId }
    val effective = activeModel?.takeIf { it.isNotBlank() } ?: activeProvider?.model
    val listedProvider = providers.firstOrNull { it.id == listedId }
    val chosenProviderId = pendingProvider ?: activeProviderId
    val chosenModelId = pendingModel ?: effective
    val chosenProvider = providers.firstOrNull { it.id == chosenProviderId }
    val chosenEntry = chosenProvider?.let { p -> ProviderModelSearch.models(p, catalogs[p.id].orEmpty()).firstOrNull { it.id == chosenModelId } }
    LaunchedEffect(providers.map { it.id }, listedId) {
        if (providers.none { it.id == listedId }) listedId = providers.firstOrNull { it.id == activeProviderId }?.id
            ?: providers.firstOrNull { it.enabled }?.id
    }
    val rows = ProviderModelSearch.results(
        providers = if (showAll) providers else providers.filter { it.id == listedId },
        catalogs = catalogs, query = query, collection = collection, favourites = saved.favourites,
        recent = saved.recent, thinkingOnly = thinkOnly, includeDisabled = showAll,
        visibility = if (showAll) null else visible,
    ).let { results ->
        if (!showAll && ChatGptProtocol.isProvider(listedId)) results.sortedWith { a, b -> newestChatGptFirst.compare(a.model, b.model) }
        else results
    }
    fun commit(providerId: String, model: String) {
        if (!modelScopeEnabled) return
        onSelect(providerId, model)
        shortcuts.selected(ModelShortcut(providerId, model))
        onDismiss()
    }
    fun choose(provider: ProviderConfig, model: String) {
        if (!modelScopeEnabled) return
        if (!provider.enabled) enableChoice = ModelShortcut(provider.id, model)
        else { pendingProvider = provider.id; pendingModel = model }
    }
    suspend fun refresh(ids: List<String>) {
        if (isRefreshing || ids.isEmpty()) return
        isRefreshing = true
        refreshError = null
        try {
            val limit = Semaphore(3)
            val failures = supervisorScope {
                ids.map { id -> async { limit.withPermit {
                    val message = try { onRefreshCatalog(id) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { "Could not refresh" }
                    message?.let { "${providers.firstOrNull { it.id == id }?.name ?: "Provider"}: $it" }
                } } }.awaitAll().filterNotNull()
            }
            refreshError = failures.firstOrNull()?.let { if (failures.size > 1) "${failures.size} providers could not refresh. $it" else it }
        } finally { isRefreshing = false }
    }
    LaunchedEffect(listedId, showAll, managing) {
        if (showAll) refresh(providers.filter { it.enabled && catalogs[it.id].isNullOrEmpty() }.map { it.id })
        else if (listedProvider != null && (catalogs[listedProvider.id].isNullOrEmpty() ||
                ChatGptProtocol.isProvider(listedProvider.id) || listedProvider.id == HarnessProvider.ID)) refresh(listOf(listedProvider.id))
    }
    if (managing) {
        ModelVisibilitySheet(providers, catalogs, listedId,
            onDismiss = { if (manageModelsInitially) onDismiss() else managing = false },
            visibility = visibility, onRefreshCatalog = onRefreshCatalog)
        return
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().height((LocalConfiguration.current.screenHeightDp * .9f).dp)
            .navigationBarsPadding().imePadding()) {
            LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                item(key = "picker-controls") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(onClick = { choosingProvider = true }, shape = RoundedCornerShape(14.dp),
                            color = scheme.surfaceContainerHigh,
                            modifier = Modifier.weight(1f).semantics { contentDescription = "Switch provider" }) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                ProviderMark(size = 30.dp)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Choose provider", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                                    Text(if (showAll) "All providers" else listedProvider?.name ?: "Select a provider",
                                        style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                Icon(Icons.Outlined.ExpandMore, null, Modifier.size(20.dp))
                            }
                        }
                        IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Close") }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Choose model", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        TextButton(onClick = { managing = true }) { Text("Manage models") }
                    }
                    OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                        placeholder = { Text(if (showAll) "Search models or providers" else "Search models") },
                        leadingIcon = { Icon(Icons.Outlined.Search, null) }, shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth())
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        FilterChip(selected = showAll, onClick = { showAll = !showAll }, label = { Text("Show all") },
                            leadingIcon = { Icon(if (showAll) Icons.Filled.Check else Icons.Outlined.FilterList, null, Modifier.size(16.dp)) })
                        listOf("Favourites", "Recent").forEach { label ->
                            FilterChip(selected = collection == label, onClick = { collection = if (collection == label) "All" else label }, label = { Text(label) })
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(if (showAll) "All connected catalogs · ${rows.size} ${if (rows.size == 1) "model" else "models"}" else "Selected models · ${rows.size}",
                            style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        IconToggleButton(checked = thinkOnly, onCheckedChange = { thinkOnly = it }) {
                            Icon(painterResource(R.drawable.ic_brain), "Thinking only", Modifier.size(22.dp),
                                tint = if (thinkOnly) scheme.tertiary else scheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { reasoningLegend = true }) { Icon(Icons.Outlined.Info, "Reasoning indicators", Modifier.size(18.dp)) }
                        IconButton(enabled = !isRefreshing, onClick = { scope.launch { refresh(if (showAll) providers.map { it.id } else listOfNotNull(listedProvider?.id)) } }) {
                            if (isRefreshing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Outlined.Refresh, "Refresh models", Modifier.size(20.dp))
                        }
                    }
                    if (showAll) Text("Includes hidden models and disabled providers.", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    if (!showAll && listedProvider?.enabled == false) Text("This provider is disabled. Show all to enable and use it.", style = MaterialTheme.typography.bodySmall)
                    refreshError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.error) }
                    if (collection == "Recent" && saved.recent.any { it.providerId == listedId }) TextButton(onClick = { listedId?.let(shortcuts::clearRecent) }) { Text("Clear recent") }
                }
                if (rows.isEmpty()) item(key = "empty-models") {
                    Column(Modifier.padding(vertical = 20.dp)) {
                        Text(when {
                            isRefreshing -> "Loading provider catalogs…"
                            providers.isEmpty() -> "Add a provider first."
                            collection == "Favourites" -> "Tap a star beside a model to keep it here."
                            collection == "Recent" -> "Models you choose will appear here."
                            else -> "No models match. Manage models or show all."
                        }, color = scheme.onSurfaceVariant)
                    }
                }
                items(rows, key = { "${it.provider.id.length}:${it.provider.id}:${it.model.id}" }) { result ->
                    val owner = result.provider
                    val entry = result.model
                    ModelRow(entry, owner,
                        selected = owner.id == chosenProviderId && entry.id == chosenModelId,
                        hidden = !visible.includes(owner.id, entry.id),
                        context = ctxLabel(entry.contextTokens ?: ModelsDev.entry(ModelsDev.providerKeyFor(owner.baseUrl), entry.id)?.contextTokens),
                        note = if (!result.available) "Unavailable · refresh or choose another model" else entry.note,
                        enabled = modelScopeEnabled && result.available,
                        favourite = result.shortcut in saved.favourites,
                        onFavourite = { shortcuts.toggle(result.shortcut) },
                        onClick = { choose(owner, entry.id) },
                        onDelete = if (entry.custom) ({ onDeleteCustomModel(owner.id, entry.id) }) else null)
                }
                item(key = "picker-options") {
                    if (!showAll && listedProvider != null && !ChatGptProtocol.isProvider(listedId) && !com.androidharness.app.local.LocalModelCatalog.isLocal(listedProvider.id)) {
                        TextButton(onClick = { showAddCustom = true }) { Text("+ Custom model…") }
                    }
                    TextButton(onClick = { onDismiss(); onManageProviders() }) { Text("Manage providers…") }
                    sameModelAcrossChats?.let { shared ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .toggleable(shared, enabled = modelScopeEnabled, role = Role.Switch, onValueChange = onModelScopeChange)
                            .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Use the same model in every chat", style = MaterialTheme.typography.bodyMedium)
                                Text(if (shared) "Changes apply across all chats." else "Each chat remembers its own model.",
                                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                            }
                            Switch(shared, onCheckedChange = null, enabled = modelScopeEnabled)
                        }
                    }
                    fallbackSummary?.let { summary ->
                        TextButton(onClick = onEditFallbacks, enabled = modelScopeEnabled && fallbackEditEnabled) {
                            Column { Text("Fallbacks"); Text(summary, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                    if (ChatGptProtocol.isProvider(listedId)) com.androidharness.app.ui.common.ChatGptUsageLimits(compact = true)
                }
            }
            HorizontalDivider()
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text((if (pendingProvider != null) "Selected: " else "Current: ") +
                    (chosenEntry?.displayName ?: chosenModelId ?: "Choose a model"),
                    style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = scheme.onSurfaceVariant)
                Button(onClick = {
                    if (chosenProvider != null && chosenModelId != null) {
                        if (chosenProvider.enabled) commit(chosenProvider.id, chosenModelId)
                        else enableChoice = ModelShortcut(chosenProvider.id, chosenModelId)
                    }
                }, enabled = modelScopeEnabled && chosenEntry != null,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text("Use ${chosenEntry?.displayName ?: chosenModelId ?: "selected model"}", maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    if (choosingProvider) ProviderChooserDialog(
        providers = providers.filter { it.enabled }, selectedId = if (showAll) null else listedId,
        onDismiss = { choosingProvider = false },
        onChoose = { provider ->
            listedId = provider.id
            showAll = false
            query = ""
            choosingProvider = false
        },
        onManage = { choosingProvider = false; onDismiss(); onManageProviders() },
    )
    enableChoice?.let { choice ->
        val provider = providers.firstOrNull { it.id == choice.providerId }
        AlertDialog(onDismissRequest = { enableChoice = null }, title = { Text("Enable ${provider?.name ?: "provider"}?") },
            text = { Text("Enable this provider and use ${choice.modelId} for this chat.") },
            confirmButton = { TextButton(enabled = provider != null && modelScopeEnabled, onClick = {
                onEnableProvider(choice.providerId)
                commit(choice.providerId, choice.modelId)
            }) { Text("Enable and use") } }, dismissButton = { TextButton(onClick = { enableChoice = null }) { Text("Cancel") } })
    }
    if (reasoningLegend) AlertDialog(onDismissRequest = { reasoningLegend = false }, title = { Text("Reasoning indicators") },
        text = { Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
            Text("The brain icon marks reasoning support. A colored brain is reported by the provider or marked by you for a custom model. A muted brain is an unconfirmed model-family hint. Tap the brain to check the source.\n\nNo icon means support is unknown or reported as unsupported. Reasoning support does not guarantee visible thinking text.")
        } },
        confirmButton = { TextButton(onClick = { reasoningLegend = false }) { Text("Got it") } })
    if (showAddCustom && listedProvider != null) AddCustomModelDialog(listedProvider.name,
        onDismiss = { showAddCustom = false }, onAdd = { model, reasoning ->
            onAddCustomModel(listedProvider.id, model, reasoning)
            // An explicitly added model should immediately join the customized quick list.
            if (visible.selected.containsKey(listedProvider.id)) visibility.save(listedProvider.id,
                visible.selected.getValue(listedProvider.id) + model)
            commit(listedProvider.id, model)
        })
}

@Composable
private fun ProviderChooserDialog(
    providers: List<ProviderConfig>, selectedId: String?, onDismiss: () -> Unit,
    onChoose: (ProviderConfig) -> Unit, onManage: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val matches = providers.filter { it.name.contains(query.trim(), ignoreCase = true) }
    val scheme = MaterialTheme.colorScheme
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Choose provider") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, singleLine = true,
                    placeholder = { Text("Search providers") }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * .45f).dp)) {
                    items(matches, key = { it.id }) { provider ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .selectable(provider.id == selectedId, role = Role.RadioButton, onClick = { onChoose(provider) })
                            .semantics { contentDescription = "Browse models from ${provider.name}" }
                            .padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            ProviderMark(size = 28.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(provider.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            RadioButton(provider.id == selectedId, onClick = null)
                        }
                    }
                    if (matches.isEmpty()) item {
                        Text("No enabled providers match.", Modifier.padding(vertical = 16.dp), color = scheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = { TextButton(onClick = onManage) { Text("Manage providers") } },
    )
}

@Composable
private fun ModelRow(
    entry: ModelEntry, provider: ProviderConfig, selected: Boolean, hidden: Boolean, context: String?,
    note: String?, enabled: Boolean, favourite: Boolean, onFavourite: () -> Unit,
    onClick: () -> Unit, onDelete: (() -> Unit)?,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(color = if (selected) scheme.secondaryContainer else scheme.surface,
        shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).heightIn(min = 56.dp)
                .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
                .semantics { contentDescription = "Choose ${entry.displayName ?: entry.id} from ${provider.name}" },
                verticalAlignment = Alignment.CenterVertically) {
                ProviderMark(size = 30.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(entry.displayName ?: entry.id, style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(provider.name, if (hidden) "Hidden" else null, if (entry.custom) "Custom" else null, context).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                    if (!provider.enabled) Text("Disabled · Enable & use", style = MaterialTheme.typography.labelMedium, color = scheme.primary)
                    note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant) }
                }
            }
            ModelReasoningIcon(entry, provider.type)
            IconButton(onClick = onFavourite) {
                Icon(if (favourite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    contentDescription = if (favourite) "Remove ${entry.displayName ?: entry.id} from favourites" else "Favourite ${entry.displayName ?: entry.id}",
                    tint = if (favourite) scheme.primary else scheme.onSurfaceVariant)
            }
            if (onDelete != null) IconButton(onClick = onDelete) { Icon(Icons.Outlined.Close, "Remove custom model") }
            if (selected) Icon(Icons.Filled.Check, "Selected", Modifier.size(18.dp), tint = scheme.primary)
        }
    }
}

@Composable
private fun AddCustomModelDialog(
    providerName: String,
    onDismiss: () -> Unit,
    onAdd: (modelId: String, reasoning: Boolean?) -> Unit,
) {
    var modelId by remember { mutableStateOf("") }
    var thinking by remember { mutableStateOf(false) }
    val clean = modelId.trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add custom model") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Add any model ID supported by $providerName.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = modelId,
                    onValueChange = { modelId = it },
                    label = { Text("Model ID") },
                    placeholder = { Text("e.g. meta-llama/llama-3.3-70b") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { thinking = !thinking }
                        .padding(vertical = 4.dp),
                ) {
                    Checkbox(
                        checked = thinking,
                        onCheckedChange = { thinking = it },
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("Reasoning model", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Enables thinking level ladder for this model",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = clean.isNotBlank(),
                onClick = {
                    onAdd(clean, if (thinking) true else null)
                    onDismiss()
                },
            ) {
                Text("Add & Select")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

private val newestChatGptFirst = Comparator<ModelEntry> { left, right ->
    fun version(entry: ModelEntry) = entry.id.lowercase().removePrefix("gpt-")
        .substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    val leftVersion = version(left)
    val rightVersion = version(right)
    for (index in 0 until maxOf(leftVersion.size, rightVersion.size)) {
        val order = rightVersion.getOrElse(index) { 0 }.compareTo(leftVersion.getOrElse(index) { 0 })
        if (order != 0) return@Comparator order
    }
    0
}

/** Compact context-window label for picker rows ("200K ctx", "1M ctx", "1B ctx"). */
private fun ctxLabel(tokens: Long?): String? = when {
    tokens == null || tokens <= 0 -> null
    tokens >= 1_000_000_000 -> "${tokens / 1_000_000_000}B ctx"
    tokens >= 1_000_000 -> "${tokens / 1_000_000}M ctx"
    tokens >= 1_000 -> "${tokens / 1_000}K ctx"
    else -> "$tokens ctx"
}
