package com.androidharness.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.llm.*
import com.androidharness.app.R
import com.androidharness.app.ui.chat.components.ModelPickerSheet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun FallbackSettingsSection(container: AppContainer, policy: FallbackPolicy) {
    var editing by rememberSaveable { mutableStateOf(false) }
    SettingsAnchor(R.string.settings_anchor_fallbacks) {
        SettingsPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().clickable { editing = true }.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.settings_anchor_fallbacks), style = MaterialTheme.typography.titleSmall)
                Text(
                    if (policy.enabled) stringResource(
                        R.string.settings_fallback_summary,
                        policy.targets.size.toString(),
                        policy.retries.toString(),
                    ) else stringResource(R.string.settings_fallback_summary_empty),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (editing) FallbackEditorSheet(container, policy, onDismiss = { editing = false },
        onSave = { _, next -> container.settings.setFallbackPolicy(next) })
}

/** One scroll surface keeps the chain, routing controls, and Save reachable on short screens. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FallbackEditorSheet(
    container: AppContainer,
    defaultPolicy: FallbackPolicy,
    onDismiss: () -> Unit,
    onSave: suspend (FallbackMode?, FallbackPolicy) -> Unit,
    chatMode: FallbackMode? = null,
    customPolicy: FallbackPolicy? = null,
    selectedProviderId: String? = null,
) {
    val providers by container.providers.providers.collectAsStateWithLifecycle(initialValue = emptyList())
    val catalogs by container.providers.catalogs.collectAsStateWithLifecycle(initialValue = emptyMap())
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = com.androidharness.app.data.AppSettings())
    val scope = rememberCoroutineScope()
    var draftJson by rememberSaveable { mutableStateOf(FallbackPolicy.encode(customPolicy ?: defaultPolicy)) }
    val draft = FallbackPolicy.decode(draftJson)
    var modeName by rememberSaveable { mutableStateOf(chatMode?.name ?: FallbackMode.CUSTOM.name) }
    val mode = FallbackMode.valueOf(modeName)
    var picking by rememberSaveable { mutableStateOf(false) }
    var managing by rememberSaveable { mutableStateOf(false) }
    var browseProviderId by rememberSaveable { mutableStateOf<String?>(null) }
    var privacy by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val editable = chatMode == null || mode == FallbackMode.CUSTOM
    val shown = if (editable) draft else if (mode == FallbackMode.DEFAULT) defaultPolicy else FallbackPolicy()
    fun update(policy: FallbackPolicy) { draftJson = FallbackPolicy.encode(policy); error = null }

    if (managing) {
        ProviderManagerSheet(providers, browseProviderId, container.providers::apiKey,
            onDismiss = { managing = false; picking = true },
            onSetActive = { id -> browseProviderId = id; managing = false; picking = true },
            onDelete = { id -> container.providers.delete(id) },
            catalogs = catalogs, onRefreshCatalog = container.providers::refreshCatalog,
            onEnabledChange = { id, enabled -> container.providers.setEnabled(id, enabled) },
            onMove = { id, direction, visible -> scope.launch { container.providers.move(id, direction, visible) } },
            onSave = { old, name, type, url, model, key, headers -> scope.launch {
                try {
                    if (old == null) container.providers.add(name, type, url, model, key, headers)
                    else container.providers.update(old.copy(name = name, type = type, baseUrl = url, model = model, headers = headers), key)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { error = "Could not save the provider. Check the connection and retry." }
            } })
        return
    }

    if (picking) {
        ModelPickerSheet(providers, null, null, catalogs,
            onDismiss = { picking = false },
            onSelect = { providerId, model ->
                val id = model ?: providers.firstOrNull { it.id == providerId }?.model
                if (!id.isNullOrBlank()) {
                    val target = FallbackTarget(providerId, id)
                    if (target !in draft.targets && draft.targets.size < FallbackPolicy.MAX_TARGETS)
                        update(draft.copy(enabled = true, targets = draft.targets + target))
                }
                picking = false
            },
            onRefreshCatalog = { id ->
                val provider = providers.firstOrNull { it.id == id }
                if (provider == null) "Provider unavailable" else {
                    val key = if (id == HarnessProvider.ID) container.providers.harnessApiKey()
                        else provider.effectiveApiKey(container.providers.apiKey(id))
                    if (key.isBlank()) "Reconnect this provider" else when (val result = ModelCatalog.listModels(provider, key)) {
                        is ModelCatalog.Result.Models -> { container.providers.saveCatalog(id, result.models); null }
                        is ModelCatalog.Result.Failed -> result.message
                    }
                }
            },
            onManageProviders = { picking = false; managing = true },
            searchAllProvidersInitially = browseProviderId == null,
            browseProviderId = browseProviderId,
            onEnableProvider = { id -> scope.launch { container.providers.setEnabled(id, true) } },
        )
        return
    }

    ModalBottomSheet(onDismissRequest = { if (!saving) onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().height((LocalConfiguration.current.screenHeightDp * .9f).dp)
            .navigationBarsPadding().imePadding().padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_anchor_fallbacks), style = MaterialTheme.typography.titleMedium)
                        Text(if (chatMode == null) "Default for chats" else "For this chat", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onDismiss, enabled = !saving) { Icon(Icons.Default.Close, "Close fallbacks") }
                }
            }
            if (chatMode != null) item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(FallbackMode.DEFAULT to "Use default", FallbackMode.CUSTOM to "Custom chain", FallbackMode.OFF to "Off").forEach { (value, label) ->
                        FilterChip(selected = mode == value, onClick = { modeName = value.name }, label = { Text(label) }, enabled = !saving)
                    }
                }
            }
            if (editable) item {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(draft.enabled, enabled = !saving, role = Role.Switch,
                    onValueChange = { update(draft.copy(enabled = it)) }), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.settings_fallback_automatic), Modifier.weight(1f))
                    Switch(checked = draft.enabled, onCheckedChange = null, enabled = !saving)
                }
            }
            item { Text(stringResource(R.string.settings_fallback_order_note),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            itemsIndexed(shown.targets, key = { _, it -> "${it.providerId}:${it.model}" }) { index, target ->
                val provider = providers.firstOrNull { it.id == target.providerId }
                val available = provider?.let { it.enabled && ProviderModelSearch.models(it, catalogs[it.id].orEmpty()).any { it.id == target.model } } == true
                val restricted = shown.sameProviderOnly && target.providerId != (selectedProviderId ?: settings.activeProviderId)
                var menu by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${index + 1}", color = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text(target.model, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(provider?.name ?: stringResource(R.string.settings_fallback_provider_unavailable), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (!available) Text(stringResource(R.string.settings_fallback_unavailable), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                        else if (restricted) Text(stringResource(R.string.settings_fallback_same_provider_skip), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (privacy && provider != null) Text(runCatching { java.net.URI(
                            if (provider.id == HarnessProvider.ID) HarnessProvider.pool[target.model]?.baseUrl ?: provider.baseUrl else provider.baseUrl
                        ).host }.getOrNull() ?: "Local device", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (editable) Box {
                        IconButton(onClick = { menu = true }, enabled = !saving) { Icon(Icons.Default.MoreVert, "Fallback actions ${index + 1}") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            fun move(offset: Int) {
                                val targets = draft.targets.toMutableList()
                                val other = index + offset
                                val swap = targets[other]; targets[other] = targets[index]; targets[index] = swap
                                update(draft.copy(targets = targets)); menu = false
                            }
                            DropdownMenuItem(text = { Text(stringResource(R.string.settings_action_move_up)) }, onClick = { move(-1) }, enabled = index > 0)
                            DropdownMenuItem(text = { Text(stringResource(R.string.settings_action_move_down)) }, onClick = { move(1) }, enabled = index < draft.targets.lastIndex)
                            DropdownMenuItem(text = { Text(stringResource(R.string.settings_action_remove)) }, onClick = { update(draft.copy(targets = draft.targets - target)); menu = false })
                        }
                    }
                }
            }
            if (shown.targets.isEmpty()) item { Text(if (mode == FallbackMode.OFF && chatMode != null) "Automatic switching is off for this chat." else "No backup models added.",
                style = MaterialTheme.typography.bodyMedium) }
            if (chatMode != null && mode == FallbackMode.DEFAULT && !defaultPolicy.enabled) item {
                Text(stringResource(R.string.settings_fallback_empty), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (editable) item {
                TextButton(onClick = { picking = true }, enabled = !saving && draft.targets.size < FallbackPolicy.MAX_TARGETS) {
                    Text(
                        stringResource(
                            R.string.settings_fallback_add_model,
                            draft.targets.size.toString(),
                            FallbackPolicy.MAX_TARGETS.toString(),
                        ),
                    )
                }
            }
            if (editable) item {
                var retriesMenu by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.settings_fallback_retries), Modifier.weight(1f))
                    Box {
                        TextButton(onClick = { retriesMenu = true }, enabled = !saving) {
                            Text(stringResource(R.string.settings_fallback_retries_value, draft.retries.toString()))
                        }
                        DropdownMenu(expanded = retriesMenu, onDismissRequest = { retriesMenu = false }) {
                            (0..5).forEach { count ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.settings_fallback_retries_value, count.toString())) },
                                    onClick = { update(draft.copy(retries = count)); retriesMenu = false },
                                )
                            }
                        }
                    }
                }
            }
            item { Text(stringResource(R.string.settings_fallback_policy_note),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { TextButton(onClick = { privacy = !privacy }) { Text(if (privacy) "Hide privacy & routing" else "Privacy & routing") } }
            if (privacy) {
                item { Text((if (shown.enabled) "Only your selected connection and the models in this chain may receive the conversation, including for subagents and automatic compaction. "
                    else if (chatMode != null && mode != FallbackMode.DEFAULT) "Automatic switching is off for this chat. Explicitly configured subagent models still apply. "
                    else "No default chain is enabled. Connected-account and subagent settings still apply. ") +
                    "Data retention follows each provider’s policy; this setting does not guarantee ZDR.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                item {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(shown.sameProviderOnly, enabled = editable && !saving, role = Role.Switch,
                        onValueChange = { update(draft.copy(sameProviderOnly = it)) }), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.settings_fallback_stay_provider), Modifier.weight(1f))
                        Switch(shown.sameProviderOnly, onCheckedChange = null, enabled = editable && !saving)
                    }
                }
            }
            error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            item {
                Button(onClick = {
                    saving = true; error = null
                    scope.launch {
                        try { onSave(if (chatMode == null) null else mode, draft); onDismiss() }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { error = "Could not save fallbacks. Try again." }
                        finally { saving = false }
                    }
                }, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text(if (saving) "Saving…" else "Save fallbacks") }
            }
        }
    }
}
