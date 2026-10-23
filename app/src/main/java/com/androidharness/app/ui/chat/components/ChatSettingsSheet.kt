package com.androidharness.app.ui.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.data.ConversationConfig

/**
 * Per-chat instructions and the personalization switch. Personalization off
 * keeps the text stored but skips both the instructions and saved memory for
 * this conversation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatSettingsSheet(
    instructions: String,
    personalization: Boolean,
    saving: Boolean,
    onSave: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var text by remember(instructions) { mutableStateOf(instructions) }
    var enabled by remember(personalization) { mutableStateOf(personalization) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().imePadding().padding(horizontal = 20.dp).padding(bottom = 28.dp),
        ) {
            Text(stringResource(R.string.chat_settings_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.chat_settings_personalization),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        stringResource(R.string.chat_settings_personalization_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(ConversationConfig.MAX_INSTRUCTIONS_CHARS) },
                label = { Text(stringResource(R.string.chat_settings_instructions)) },
                placeholder = { Text(stringResource(R.string.chat_settings_instructions_hint)) },
                supportingText = {
                    Text(stringResource(if (enabled) R.string.chat_settings_instructions_help
                        else R.string.chat_settings_instructions_off))
                },
                enabled = enabled,
                minLines = 3,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_action_cancel)) }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onSave(text, enabled) }, enabled = !saving) {
                    Text(stringResource(R.string.settings_action_save))
                }
            }
        }
    }
}
