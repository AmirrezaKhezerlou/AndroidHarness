package com.androidharness.app.ui.settings

import androidx.annotation.StringRes
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
import com.androidharness.app.data.ChatStartMode
import com.androidharness.app.data.StartScreen

@Composable
internal fun ChatOptionsSection(
    settings: AppSettings,
    onStartScreen: (StartScreen) -> Unit,
    onChatStart: (ChatStartMode) -> Unit,
) {
    SettingsAnchor(R.string.settings_start_screen) {
        ChoicePanel(
            title = R.string.settings_start_screen,
            options = StartScreen.entries,
            selected = settings.startScreen,
            onSelect = onStartScreen,
            label = {
                when (it) {
                    StartScreen.CODE -> R.string.conversation_code
                    StartScreen.CHAT -> R.string.conversation_chat
                    StartScreen.LAST_SELECTED -> R.string.settings_start_last_selected
                }
            },
            description = {
                when (it) {
                    StartScreen.CODE -> R.string.settings_start_code_desc
                    StartScreen.CHAT -> R.string.settings_start_chat_desc
                    StartScreen.LAST_SELECTED -> R.string.settings_start_last_desc
                }
            },
        )
    }
    SettingsAnchor(R.string.settings_chat_start) {
        ChoicePanel(
            title = R.string.settings_chat_start,
            options = ChatStartMode.entries,
            selected = settings.chatStart,
            onSelect = onChatStart,
            label = {
                when (it) {
                    ChatStartMode.NEW_CHAT -> R.string.settings_chat_start_new
                    ChatStartMode.LAST_CHAT -> R.string.settings_chat_start_last
                }
            },
            description = {
                when (it) {
                    ChatStartMode.NEW_CHAT -> R.string.settings_chat_start_new_desc
                    ChatStartMode.LAST_CHAT -> R.string.settings_chat_start_last_desc
                }
            },
        )
    }
}

@Composable
private fun <T> ChoicePanel(
    @StringRes title: Int,
    options: List<T>,
    selected: T,
    label: (T) -> Int,
    description: (T) -> Int,
    onSelect: (T) -> Unit,
) {
    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.selectableGroup()) {
            Text(
                stringResource(title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            )
            options.forEachIndexed { index, option ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 64.dp).selectable(
                        selected = selected == option,
                        role = Role.RadioButton,
                        onClick = { onSelect(option) },
                    ).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(label(option)), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(description(option)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    RadioButton(selected = selected == option, onClick = null)
                }
                if (index < options.lastIndex) HorizontalDivider(
                    Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f),
                )
            }
        }
    }
}
