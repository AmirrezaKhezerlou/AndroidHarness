package com.androidharness.app.ui.chat.components

import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.Add

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.ForkRight
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import com.androidharness.app.R
import com.androidharness.app.agent.AgentMode
import com.androidharness.app.agent.PermissionMode
import com.androidharness.app.agent.ThinkingLevel
import com.androidharness.app.ui.theme.fastEffectsSpec
import com.androidharness.app.ui.common.ExpressiveIconButton

/**
 * Phones give the model and thinking level their own row so neither competes
 * with navigation or workspace actions. Desktop uses its own compact header.
 * Labels and touch targets grow with the system font size.
 */
@Composable
internal fun MainHeader(
    sessionTitle: String,
    busy: Boolean,
    pickerLabel: String,
    mode: AgentMode,
    dualPlanning: Boolean = false,
    thinkingLevel: ThinkingLevel,
    /** Choices for this provider; an empty list means no configurable effort. */
    thinkingLevels: List<ThinkingLevel>,
    permissionMode: PermissionMode,
    canUndo: Boolean,
    onOpenDrawer: () -> Unit,
    onPickModel: () -> Unit,
    onOpenTerminal: () -> Unit,
    onSetThinking: (ThinkingLevel) -> Unit,
    onSetPermission: (PermissionMode) -> Unit,
    onSetMode: (AgentMode) -> Unit,
    onToggleDualPlanning: () -> Unit = {},
    onOpenContext: () -> Unit,
    onOpenUndo: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenGitHub: () -> Unit,
    canOpenGitHub: Boolean = true,
    onOpenWebPreview: () -> Unit = {},
    modelScopeLabel: String? = null,
    workspaceName: String? = null,
    thinkingResolved: com.androidharness.app.agent.ThinkingSpecs.ResolvedThinking? = null,
    onCustomizeThinking: (() -> Unit)? = null,
    onAddThinkingLevel: (() -> Unit)? = null,
    onSetCustomThinking: (String) -> Unit = {},
) {
    var menu by remember { mutableStateOf(false) }
    var thinkingMenu by remember { mutableStateOf(false) }
    var permissionMenu by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val thinkingOn = thinkingLevel != ThinkingLevel.OFF

    if (com.androidharness.app.ui.LocalDesktopLayout.current) {
        DesktopConversationHeader(sessionTitle, busy, pickerLabel, thinkingLevel, thinkingLevels, mode,
            permissionMode, onPickModel, onSetThinking, onSetMode, onSetPermission,
            onOpenContext, onOpenUndo, canUndo, onOpenGitHub, canOpenGitHub, onOpenWebPreview, thinkingResolved, onCustomizeThinking, onSetCustomThinking, onAddThinkingLevel)
        return
    }

    Column(
        Modifier
            .background(scheme.surface)
            .statusBarsPadding(),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .padding(start = 10.dp, end = 10.dp),
                ) {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(painterResource(R.drawable.ic_chat_menu), contentDescription = "Open navigation", modifier = Modifier.size(18.dp))
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 6.dp, vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (sessionTitle.startsWith("Fork of ")) {
                                Icon(Icons.Outlined.ForkRight, contentDescription = "Forked session",
                                    tint = scheme.primary, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                            }
                            Text(sessionTitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                        }
                        workspaceName?.let {
                            Text(it, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }

                    // Workspace files explorer, migrated here from the drawer;
                    // workspace switching itself lives inside the file manager.
                    IconButton(onClick = onOpenFiles) {
                        Icon(
                            painterResource(R.drawable.ic_chat_folder),
                            modifier = Modifier.size(18.dp),
                            contentDescription = "Workspace files",
                            tint = scheme.onSurfaceVariant,
                        )
                    }
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(painterResource(R.drawable.ic_chat_ellipsis_vertical), contentDescription = "More options", modifier = Modifier.size(18.dp))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("GitHub") }, enabled = canOpenGitHub,
                                leadingIcon = { Icon(painterResource(R.drawable.ic_github), null, Modifier.size(18.dp)) },
                                onClick = { menu = false; onOpenGitHub() },
                            )
                            DropdownMenuItem(
                                text = { Text(if (mode == AgentMode.PLAN && !dualPlanning) "Act mode" else "Plan mode") },
                                leadingIcon = {
                                    Icon(
                                        Icons.Outlined.EditNote,
                                        contentDescription = null,
                                        tint = if (mode == AgentMode.PLAN && !dualPlanning) scheme.primary else scheme.onSurfaceVariant,
                                    )
                                },
                                trailingIcon = {
                                    if (mode == AgentMode.PLAN && !dualPlanning) {
                                        Icon(Icons.Filled.Check, contentDescription = "Enabled", tint = scheme.primary)
                                    }
                                },
                                onClick = {
                                    menu = false
                                    onSetMode(if (mode == AgentMode.PLAN && !dualPlanning) AgentMode.ACT else AgentMode.PLAN)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Dual planning") },
                                leadingIcon = {
                                    Icon(
                                        Icons.Outlined.ForkRight,
                                        contentDescription = null,
                                        tint = if (dualPlanning) scheme.primary else scheme.onSurfaceVariant,
                                    )
                                },
                                trailingIcon = {
                                    if (dualPlanning) {
                                        Icon(Icons.Filled.Check, contentDescription = "Enabled", tint = scheme.primary)
                                    }
                                },
                                onClick = {
                                    menu = false
                                    onToggleDualPlanning()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Thinking · ${thinkingResolved?.label ?: thinkingLevel.label}") },
                                leadingIcon = {
                                    Icon(
                                        painterResource(R.drawable.ic_brain),
                                        contentDescription = null,
                                        tint = if (thinkingOn) scheme.primary else scheme.onSurfaceVariant,
                                    )
                                },
                                trailingIcon = {
                                    Icon(
                                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        contentDescription = null,
                                        tint = scheme.onSurfaceVariant,
                                    )
                                },
                                onClick = { menu = false; thinkingMenu = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Permission · ${permissionMode.label}") },
                                leadingIcon = {
                                    Icon(
                                        Icons.Outlined.Shield,
                                        contentDescription = null,
                                        tint = when (permissionMode) {
                                            PermissionMode.FULL_ACCESS -> FullAccessOrange
                                            PermissionMode.FULL_AUTO -> scheme.error
                                            PermissionMode.CONFIRM_RISKY -> scheme.primary
                                            PermissionMode.CONFIRM_ALL -> scheme.onSurfaceVariant
                                        },
                                    )
                                },
                                trailingIcon = {
                                    Icon(
                                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        contentDescription = null,
                                        tint = scheme.onSurfaceVariant,
                                    )
                                },
                                onClick = { menu = false; permissionMenu = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Context & limits") },
                                leadingIcon = {
                                    Icon(Icons.Outlined.QueryStats, contentDescription = null)
                                },
                                onClick = { menu = false; onOpenContext() },
                            )
                            HorizontalDivider(Modifier.padding(vertical = 6.dp))
                            DropdownMenuItem(
                                text = { Text("Terminal") },
                                leadingIcon = { Icon(Icons.Outlined.Terminal, contentDescription = null) },
                                onClick = { menu = false; onOpenTerminal() },
                            )
                            DropdownMenuItem(
                                text = { Text("Web preview (localhost)") },
                                leadingIcon = { Icon(Icons.Outlined.Language, contentDescription = null) },
                                onClick = { menu = false; onOpenWebPreview() },
                            )
                            DropdownMenuItem(
                                text = { Text("Undo file changes…") },
                                leadingIcon = { Icon(Icons.Outlined.History, contentDescription = null) },
                                enabled = canUndo,
                                onClick = { menu = false; onOpenUndo() },
                            )
                        }
                        DropdownMenu(expanded = permissionMenu, onDismissRequest = { permissionMenu = false }) {
                            PermissionMode.entries.forEach { entry ->
                                DropdownMenuItem(text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            entry.label,
                                            Modifier.weight(1f),
                                            color = if (entry == PermissionMode.FULL_ACCESS) FullAccessOrange
                                            else Color.Unspecified,
                                        )
                                        if (entry == permissionMode) {
                                            Icon(
                                                Icons.Filled.Check,
                                                contentDescription = "Selected",
                                                tint = if (entry == PermissionMode.FULL_ACCESS) FullAccessOrange
                                                else scheme.primary,
                                            )
                                        }
                                    }
                                }, onClick = { onSetPermission(entry); permissionMenu = false })
                            }
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(start = 18.dp, end = 10.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        HeaderModelPicker(pickerLabel, onPickModel, Modifier.weight(1f, fill = false), scopeLabel = modelScopeLabel)
                        if (thinkingResolved?.customConfig != null || thinkingLevels.isNotEmpty() || thinkingResolved?.spec?.style == com.androidharness.app.agent.ThinkingSpecs.Style.AUTOMATIC) {
                            Spacer(Modifier.width(8.dp))
                            HeaderThinkingControl(thinkingLevel, thinkingLevels, thinkingMenu,
                                { thinkingMenu = it }, onSetThinking, onPickModel, thinkingResolved, busy, onCustomizeThinking, onSetCustomThinking, onAddThinkingLevel)
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    ExpressiveIconButton(onClick = onOpenGitHub, enabled = canOpenGitHub) {
                        Icon(painterResource(R.drawable.ic_github), contentDescription = "Open GitHub",
                            modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f))
    }
}

@Composable
private fun DesktopConversationHeader(
    title: String, busy: Boolean, model: String, thinking: ThinkingLevel, levels: List<ThinkingLevel>,
    mode: AgentMode, permission: PermissionMode,
    onModel: () -> Unit, onThinking: (ThinkingLevel) -> Unit, onMode: (AgentMode) -> Unit,
    onPermission: (PermissionMode) -> Unit, onContext: () -> Unit, onUndo: () -> Unit,
    canUndo: Boolean, onGitHub: () -> Unit, canGitHub: Boolean, onPreview: () -> Unit,
    thinkingResolved: com.androidharness.app.agent.ThinkingSpecs.ResolvedThinking?,
    onCustomizeThinking: (() -> Unit)?,
    onSetCustomThinking: (String) -> Unit,
    onAddThinkingLevel: (() -> Unit)?,
) {
    val scheme = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    var thinkingMenu by remember { mutableStateOf(false) }
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (busy) androidx.compose.material3.CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
            HeaderModelPicker(model, onModel, Modifier.widthIn(max = 180.dp))
            if (thinkingResolved?.customConfig != null || levels.isNotEmpty() || thinkingResolved?.spec?.style == com.androidharness.app.agent.ThinkingSpecs.Style.AUTOMATIC)
                HeaderThinkingControl(thinking, levels, thinkingMenu, { thinkingMenu = it }, onThinking, onModel, thinkingResolved, busy, onCustomizeThinking, onSetCustomThinking, onAddThinkingLevel)
            TextButton(onClick = { onMode(if (mode == AgentMode.PLAN) AgentMode.ACT else AgentMode.PLAN) }) {
                Text(if (mode == AgentMode.PLAN) "Plan" else "Act", style = MaterialTheme.typography.labelMedium)
            }
            ExpressiveIconButton(onClick = onGitHub, enabled = canGitHub, modifier = Modifier.size(36.dp)) {
                Icon(painterResource(R.drawable.ic_github), "Open GitHub", Modifier.size(18.dp))
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.MoreVert, "Conversation actions", Modifier.size(18.dp))
                }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Context & limits") }, onClick = { menu = false; onContext() })
                    DropdownMenuItem(text = { Text("Web preview") }, onClick = { menu = false; onPreview() })
                    DropdownMenuItem(text = { Text("GitHub") }, enabled = canGitHub, onClick = { menu = false; onGitHub() })
                    DropdownMenuItem(text = { Text("Undo file changes") }, enabled = canUndo, onClick = { menu = false; onUndo() })
                    HorizontalDivider()
                    PermissionMode.entries.forEach { option ->
                        DropdownMenuItem(text = { Text(option.label) }, onClick = { menu = false; onPermission(option) },
                            trailingIcon = { if (permission == option) Icon(Icons.Filled.Check, "Selected", Modifier.size(16.dp)) })
                    }
                }
            }
        }
        HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.4f))
    }
}

@Composable
private fun HeaderModelPicker(label: String, onPickModel: () -> Unit, modifier: Modifier = Modifier, scopeLabel: String? = null) {
    Row(
        modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onPickModel)
            .heightIn(min = 36.dp).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f, fill = false)) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            scopeLabel?.let { Text(it, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary) }
        }
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Switch model",
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun HeaderThinkingControl(
    thinkingLevel: ThinkingLevel,
    thinkingLevels: List<ThinkingLevel>,
    thinkingMenu: Boolean,
    onMenuChange: (Boolean) -> Unit,
    onSetThinking: (ThinkingLevel) -> Unit,
    onPickModel: () -> Unit,
    resolved: com.androidharness.app.agent.ThinkingSpecs.ResolvedThinking? = null,
    appliesNext: Boolean = false,
    onCustomize: (() -> Unit)? = null,
    onCustomLevel: (String) -> Unit = {},
    onAddLevel: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val thinkingOn = resolved?.active ?: (thinkingLevel != ThinkingLevel.OFF)
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val windowWidth = LocalWindowInfo.current.containerSize.width
    val edgeMargin = with(density) { 12.dp.roundToPx() }
    val maxMenuWidth = with(density) { (windowWidth - 2 * edgeMargin).coerceAtLeast(0).toDp() }.coerceAtMost(320.dp)
    var anchorBounds by remember { mutableStateOf(Rect.Zero) }
    var menuWidth by remember(density) { mutableIntStateOf(with(density) { 248.dp.roundToPx() }) }
    // Center on the badge, then keep the menu inset from either window edge.
    // The default start/end candidates can both fail and snap a wide menu to x = 0.
    val menuLeft = (anchorBounds.center.x - menuWidth / 2f)
        .coerceIn(edgeMargin.toFloat(), (windowWidth - edgeMargin - menuWidth).coerceAtLeast(edgeMargin).toFloat())
    val horizontalOffset = if (layoutDirection == LayoutDirection.Ltr) menuLeft - anchorBounds.left
        else anchorBounds.right - menuLeft - menuWidth
    Box(Modifier.onGloballyPositioned { anchorBounds = it.boundsInWindow() }) {
        // The visible badge stays small; clickable expands its touch target as needed.
        Box(
            Modifier.widthIn(max = 148.dp).heightIn(min = 36.dp).clip(RoundedCornerShape(8.dp)).clickable { onMenuChange(true) },
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (thinkingOn) scheme.primaryContainer else scheme.surfaceContainerHigh,
                contentColor = if (thinkingOn) scheme.primary else scheme.onSurfaceVariant,
            ) {
                Row(Modifier.heightIn(min = 28.dp).padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_brain), contentDescription = "Thinking level", modifier = Modifier.size(14.dp),
                        tint = if (thinkingOn) scheme.primary else scheme.onSurfaceVariant)
                    Spacer(Modifier.width(4.dp))
                    Text(resolved?.label ?: thinkingLevel.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium)
                }
            }
        }
        DropdownMenu(
            expanded = thinkingMenu,
            onDismissRequest = { onMenuChange(false) },
            offset = DpOffset(with(density) { horizontalOffset.toDp() }, 4.dp),
            modifier = Modifier.widthIn(min = 248.dp.coerceAtMost(maxMenuWidth), max = maxMenuWidth)
                .onSizeChanged { menuWidth = it.width }
                .semantics { paneTitle = "Thinking options" },
        ) {
            Text("Thinking level", Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
            if (appliesNext) {
                Text("Applies to your next message", Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall, color = scheme.primary)
            }
            if (resolved?.customConfig == null && thinkingLevels.isEmpty()) {
                Text(if (resolved?.spec?.reasoningSupported == true) "This model manages its own effort." else "Levels aren't reported for this model.",
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            resolved?.customConfig?.levels?.forEach { name ->
                ThinkingMenuOption(com.androidharness.app.agent.CustomReasoning.label(name),
                    resolved.customLevel == com.androidharness.app.agent.CustomReasoning.wire(name)) {
                    onCustomLevel(name); onMenuChange(false)
                }
            }
            if (resolved?.customConfig == null) thinkingLevels.forEach { entry ->
                ThinkingMenuOption(resolved?.optionLabel(entry) ?: entry.label, entry == (resolved?.level ?: thinkingLevel)) {
                    onSetThinking(entry); onMenuChange(false)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            if (onCustomize != null) DropdownMenuItem(
                text = { Text("Customize thinking") },
                leadingIcon = { Icon(Icons.Outlined.EditNote, null, tint = scheme.onSurfaceVariant) },
                onClick = { onMenuChange(false); onCustomize() },
            )
            val addLevel = onAddLevel ?: onCustomize
            if (addLevel != null) DropdownMenuItem(
                text = { Text("Add thinking level") },
                leadingIcon = { Icon(Icons.Filled.Add, null, tint = scheme.primary) },
                onClick = { onMenuChange(false); addLevel() },
            )
            DropdownMenuItem(
                text = { Text("Switch model…") },
                leadingIcon = {
                    Icon(Icons.Outlined.SwapHoriz, contentDescription = null, tint = scheme.primary)
                },
                onClick = {
                    onMenuChange(false)
                    onPickModel()
                },
            )
        }
    }
}

@Composable
private fun ThinkingMenuOption(label: String, isSelected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    DropdownMenuItem(
        modifier = Modifier.padding(horizontal = 6.dp).clip(RoundedCornerShape(10.dp))
            .background(if (isSelected) scheme.secondaryContainer else Color.Transparent)
            .semantics { selected = isSelected },
        text = { Text(label, color = if (isSelected) scheme.onSecondaryContainer else scheme.onSurface,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal) },
        trailingIcon = { if (isSelected) Icon(Icons.Filled.Check, "Selected", tint = scheme.onSecondaryContainer) },
        onClick = onClick,
    )
}

/** Accent for the Full access permission mode, warning orange, distinct from error red. */
internal val FullAccessOrange = Color(0xFFF59E0B)
