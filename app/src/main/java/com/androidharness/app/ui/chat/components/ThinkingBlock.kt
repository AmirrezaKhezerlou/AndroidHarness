package com.androidharness.app.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.androidharness.app.ui.chat.UserScrollObserver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import com.androidharness.app.ui.common.DotLoading
import com.androidharness.app.ui.theme.defaultEffectsSpec
import com.androidharness.app.ui.theme.fastEffectsSpec
import com.androidharness.app.ui.theme.fastSpatialSpec

/**
 * Collapsible reasoning transcript. Deliberately quiet: a label, a chevron, and
 * a hairline rail next to the body text. No brain icons, no purple, reasoning
 * is supporting evidence, not the main character.
 *
 * While live the block stays collapsed to a one-line pill (fast streams would
 * otherwise fight the layout); expanding mid-stream shows a height-capped,
 * auto-following view that stops following if the user scrolls up.
 */
@Composable
internal fun ThinkingBlock(
    thinking: String,
    live: Boolean = false,
) {
    if (thinking.isBlank() && !live) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    val rotation = animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = fastEffectsSpec(),
        label = "reasoning chevron",
    )
    val scheme = MaterialTheme.colorScheme

    Surface(
        color = scheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.5f)),
        onClick = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (live) {
                    DotLoading(color = scheme.onSurfaceVariant, dotSize = 4.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    if (live) "Thinking…" else "Thinking",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    modifier = Modifier
                        .size(18.dp)
                        .graphicsLayer { rotationZ = rotation.value },
                    tint = scheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(defaultEffectsSpec()) + expandVertically(animationSpec = fastSpatialSpec()),
                exit = fadeOut(defaultEffectsSpec()) + shrinkVertically(animationSpec = fastSpatialSpec()),
            ) {
                val scroll = rememberScrollState()
                var following by remember { mutableStateOf(true) }
                val userScrollObserver = remember { UserScrollObserver { following = false } }
                LaunchedEffect(live, following) {
                    if (!live || !following) return@LaunchedEffect
                    // Observe measured growth instead of racing layout on each text delta.
                    snapshotFlow { scroll.maxValue }.collect { end ->
                        if (following && !scroll.isScrollInProgress && end != Int.MAX_VALUE) {
                            scroll.scrollTo(end)
                        }
                    }
                }
                // Paint the rail after measurement instead of asking the entire text for
                // an intrinsic height before laying it out a second time.
                Box(
                    Modifier
                        .padding(top = 8.dp)
                        .nestedScroll(userScrollObserver)
                        .drawBehind {
                            val railWidth = 2.dp.toPx()
                            drawRoundRect(
                                color = scheme.outlineVariant.copy(alpha = 0.7f),
                                topLeft = Offset(if (layoutDirection == LayoutDirection.Rtl) size.width - railWidth else 0f, 0f),
                                size = Size(railWidth, size.height),
                                cornerRadius = CornerRadius(1.dp.toPx()),
                            )
                        }
                        .padding(start = 12.dp),
                ) {
                    // A completed, long transcript can lay out only visible paragraphs.
                    // Live thinking retains the exact scroll observer/following path above.
                    val paragraphs = remember(thinking, live) {
                        if (!live && thinking.length > 8_000) thinking.split('\n').takeIf { it.size > 80 }
                        else null
                    }
                    val textModifier = Modifier.fillMaxWidth().heightIn(max = 260.dp)
                    if (paragraphs != null) {
                        LazyColumn(modifier = textModifier) {
                            items(paragraphs.size, key = { it }) { index ->
                                ThinkingText(paragraphs[index], Modifier.fillMaxWidth())
                            }
                        }
                    } else {
                        ThinkingText(thinking, textModifier.verticalScroll(scroll))
                    }
                }
            }
        }
    }
}

@Composable
private fun ThinkingText(text: String, modifier: Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        fontStyle = FontStyle.Italic,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
