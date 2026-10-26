package com.androidharness.app.ui.chat.components

import com.androidharness.app.ui.common.ExpressiveDropdownMenu as DropdownMenu
import com.androidharness.app.ui.common.ExpressiveDropdownMenuItem as DropdownMenuItem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import com.androidharness.app.ui.theme.expressiveSpatialSpec
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
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
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, expressiveSpatialSpec(), label = "personalizationChevron")
    // DropdownMenu aligns its start edge to the anchor, so the anchor must be
    // as wide as the menu for a centered trigger to produce a centered menu.
    val menuWidth = with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.width.toDp() - 32.dp
    }.coerceIn(48.dp, 360.dp).coerceAtMost(320.dp)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(Modifier.width(menuWidth), contentAlignment = Alignment.Center) {
            TextButton(onClick = { expanded = true }, enabled = enabled,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(if (personalized) R.string.chat_personalized else R.string.chat_unpersonalized),
                    style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(20.dp).graphicsLayer { rotationZ = chevron })
            }
            DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false },
                modifier = Modifier.width(menuWidth)) {
                for (option in listOf(true, false)) {
                    DropdownMenuItem(
                        text = { Column {
                            Text(stringResource(if (option) R.string.chat_personalized else R.string.chat_unpersonalized))
                            Text(stringResource(if (option) R.string.chat_settings_personalization_desc else R.string.chat_unpersonalized_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } },
                        leadingIcon = { if (option == personalized) Icon(Icons.Filled.Check, null) else Spacer(Modifier.size(24.dp)) },
                        modifier = Modifier.background(if (option == personalized) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, RoundedCornerShape(16.dp))
                            .semantics { selected = option == personalized },
                        onClick = { expanded = false; onSelect(option) },
                    )
                }
            }
        }
    }
}
