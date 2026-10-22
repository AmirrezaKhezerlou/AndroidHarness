package com.androidharness.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.data.AppSettings
import com.androidharness.app.data.StartScreen

@Composable
internal fun ChatOptionsSection(settings: AppSettings, onStartScreen: (StartScreen) -> Unit) {
    SettingsAnchor(R.string.settings_start_screen) {
        SettingsPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.selectableGroup()) {
                Text(stringResource(R.string.settings_start_screen), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp))
                StartScreen.entries.forEachIndexed { index, option ->
                    val (title, description) = when (option) {
                        StartScreen.CODE -> R.string.conversation_code to R.string.settings_start_code_desc
                        StartScreen.CHAT -> R.string.conversation_chat to R.string.settings_start_chat_desc
                        StartScreen.LAST_SELECTED -> R.string.settings_start_last_selected to R.string.settings_start_last_desc
                    }
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).selectable(
                        selected = settings.startScreen == option, role = Role.RadioButton,
                        onClick = { onStartScreen(option) }).padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        RadioButton(selected = settings.startScreen == option, onClick = null)
                    }
                    if (index < StartScreen.entries.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                }
            }
        }
    }
}
