package com.androidharness.app.ui.chat.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.androidharness.app.R

/** Outside the transcript so scrolling content can never cover its touch target. */
@Composable
internal fun ChatPersonalizationDropdown(
    personalized: Boolean,
    enabled: Boolean,
    onSelect: (Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box {
            TextButton(onClick = { expanded = true }, enabled = enabled,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(if (personalized) R.string.chat_personalized else R.string.chat_unpersonalized),
                    style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(20.dp))
            }
            DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
                for (option in listOf(true, false)) {
                    DropdownMenuItem(
                        text = { Column {
                            Text(stringResource(if (option) R.string.chat_personalized else R.string.chat_unpersonalized))
                            Text(stringResource(if (option) R.string.chat_settings_personalization_desc else R.string.chat_unpersonalized_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } },
                        leadingIcon = { if (option == personalized) Icon(Icons.Filled.Check, null) else Spacer(Modifier.size(24.dp)) },
                        modifier = Modifier.semantics { selected = option == personalized },
                        onClick = { expanded = false; onSelect(option) },
                    )
                }
            }
        }
    }
}
