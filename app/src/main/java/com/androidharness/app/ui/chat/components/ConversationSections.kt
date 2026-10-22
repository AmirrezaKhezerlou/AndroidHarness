package com.androidharness.app.ui.chat.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Tune
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
internal fun ConversationSectionRow(
    config: ConversationConfig,
    enabled: Boolean,
    onSelect: (ConversationKind) -> Unit,
    onTools: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            ConversationKind.entries.forEach { kind ->
                val active = config.kind == kind
                Surface(onClick = { if (!active) onSelect(kind) }, enabled = enabled,
                    color = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                    contentColor = if (active) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.semantics { role = Role.Tab; selected = active }) {
                    Row(Modifier.heightIn(min = 40.dp).padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(if (kind == ConversationKind.CODE) Icons.Outlined.Code else Icons.Outlined.ChatBubbleOutline,
                            null, Modifier.size(16.dp))
                        Text(stringResource(if (kind == ConversationKind.CODE) R.string.conversation_code else R.string.conversation_chat),
                            style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        if (config.isChat) {
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onTools, enabled = enabled) {
                Icon(Icons.Outlined.Tune, stringResource(if (config.toolsEnabled) R.string.chat_tools_on else R.string.chat_tools_off), Modifier.size(19.dp),
                    tint = if (config.toolsEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
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
