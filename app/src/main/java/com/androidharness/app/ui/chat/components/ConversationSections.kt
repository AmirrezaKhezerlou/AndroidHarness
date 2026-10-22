package com.androidharness.app.ui.chat.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.data.ChatTool
import com.androidharness.app.data.ConversationConfig
import com.androidharness.app.data.ConversationKind

@Composable
internal fun ConversationSectionSwitcher(
    config: ConversationConfig,
    enabled: Boolean,
    onSelect: (ConversationKind) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
        Row(Modifier.padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            ConversationKind.entries.forEach { kind ->
                val active = config.kind == kind
                Surface(onClick = { if (!active) onSelect(kind) }, enabled = enabled,
                    color = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                    contentColor = if (active) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.weight(1f).semantics { role = Role.Tab; selected = active }) {
                    Row(Modifier.heightIn(min = 44.dp).padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        Icon(if (kind == ConversationKind.CODE) Icons.Outlined.Code else Icons.Outlined.ChatBubbleOutline,
                            null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(if (kind == ConversationKind.CODE) R.string.conversation_code else R.string.conversation_chat),
                            style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

@Composable
internal fun ConversationModeLabel(config: ConversationConfig, workspaceName: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(if (config.isChat) Icons.Outlined.ChatBubbleOutline else Icons.Outlined.Code, null,
            Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        val section = stringResource(if (config.isChat) R.string.conversation_chat else R.string.conversation_code)
        val detail = if (config.isChat) stringResource(if (config.toolsEnabled) R.string.chat_tools_status_on else R.string.chat_tools_status_off)
            else workspaceName?.takeIf { it.isNotBlank() }
        Text(section + detail?.let { " · $it" }.orEmpty(), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatToolsSheet(config: ConversationConfig, enabled: Boolean,
    onSave: (ConversationConfig) -> Unit, onDismiss: () -> Unit) {
    var draft by remember(config) { mutableStateOf(config) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)
            .navigationBarsPadding().padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.chat_tools_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.chat_tools_description), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().toggleable(value = draft.toolsEnabled, enabled = enabled, role = Role.Switch,
                onValueChange = { draft = draft.copy(toolsEnabled = it) }), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.chat_tools_enable), Modifier.weight(1f))
                Switch(checked = draft.toolsEnabled, onCheckedChange = null, enabled = enabled)
            }
            HorizontalDivider()
            ChatTool.entries.forEach { tool ->
                val label = when (tool) {
                    ChatTool.WEB_SEARCH -> R.string.chat_tool_search
                    ChatTool.WEB_PAGES -> R.string.chat_tool_pages
                    ChatTool.ARTIFACTS -> R.string.chat_tool_artifacts
                }
                Row(Modifier.fillMaxWidth().toggleable(value = tool in draft.selectedTools, enabled = enabled && draft.toolsEnabled, role = Role.Checkbox,
                    onValueChange = { checked -> draft = draft.copy(selectedTools = if (checked) draft.selectedTools + tool else draft.selectedTools - tool) }), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(label), Modifier.weight(1f))
                    Checkbox(checked = tool in draft.selectedTools, enabled = enabled && draft.toolsEnabled,
                        onCheckedChange = null)
                }
            }
            if (!enabled) Text(stringResource(R.string.chat_tools_wait), style = MaterialTheme.typography.bodySmall)
            Button(onClick = { onSave(draft); onDismiss() }, enabled = enabled, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.chat_tools_save))
            }
        }
    }
}
