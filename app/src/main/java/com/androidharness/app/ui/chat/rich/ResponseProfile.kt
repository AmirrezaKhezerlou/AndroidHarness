package com.androidharness.app.ui.chat.rich

import android.graphics.Bitmap
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.core.response.RichResponse
import com.androidharness.app.ui.chat.LocalChatImagesEnabled
import com.androidharness.app.ui.chat.MarkdownText
import com.androidharness.app.ui.chat.RemoteContentLoader
import com.androidharness.app.ui.chat.components.ImageViewerDialog
import com.androidharness.app.ui.theme.expressiveSpatialSpec

/** A sourced knowledge panel, stored in the message and rendered without a separate lookup. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ResponseProfile(profile: RichResponse.Node.Profile, onOpenUrl: ((String) -> Unit)?) {
    val uri = LocalUriHandler.current
    val open: (String) -> Unit = onOpenUrl ?: { uri.openUri(it) }
    val context = LocalContext.current
    val showImage = LocalChatImagesEnabled.current && profile.image.isNotBlank()
    var expanded by rememberSaveable(profile.image) { mutableStateOf(false) }
    val photo by produceState<Pair<Boolean, Bitmap?>>(showImage to null, profile.image, showImage) {
        value = false to if (showImage) RemoteContentLoader.loadImage(context, profile.image) else null
    }
    if (expanded && showImage) ImageViewerDialog(profile.image, profile.imageSource, { expanded = false }, { url -> expanded = false; open(url) })
    Surface(shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f)),
        modifier = Modifier.fillMaxWidth().animateContentSize(expressiveSpatialSpec())) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val stack = maxWidth < 300.dp || LocalDensity.current.fontScale > 1.3f
                val heading: @Composable () -> Unit = {
                    SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(profile.name, style = MaterialTheme.typography.headlineSmallEmphasized)
                        if (profile.subtitle.isNotBlank()) Text(profile.subtitle, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } }
                }
                val portrait: @Composable () -> Unit = {
                    if (showImage && (photo.first || photo.second != null)) {
                        Box(Modifier.size(width = 100.dp, height = 124.dp).clip(RoundedCornerShape(20.dp))
                            .clickable(enabled = photo.second != null) { expanded = true }, contentAlignment = Alignment.Center) {
                            photo.second?.let { Image(it.asImageBitmap(), profile.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                                ?: CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        }
                    }
                }
                if (stack) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { portrait(); heading() }
                else Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { heading() }; portrait()
                }
            }
            SelectionContainer { MarkdownText(profile.description, onOpenUrl = open) }
            if (profile.facts.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                profile.facts.forEach { fact ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        BoxWithConstraints(Modifier.weight(1f)) {
                            if (maxWidth < 280.dp || LocalDensity.current.fontScale > 1.3f) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(fact.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                SelectionContainer { Text(fact.value, style = MaterialTheme.typography.bodyMedium) }
                            } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(fact.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(.38f))
                                SelectionContainer(Modifier.weight(.62f)) { Text(fact.value, style = MaterialTheme.typography.bodyMedium) }
                            }
                        }
                        IconButton(onClick = { open(fact.source) }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Outlined.OpenInNew, stringResource(R.string.rich_open_source), Modifier.size(16.dp))
                        }
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                profile.sources.forEach { source ->
                    SuggestionChip(onClick = { open(source.url) }, label = { Text(source.title) },
                        icon = { Icon(Icons.Outlined.OpenInNew, null, Modifier.size(14.dp)) })
                }
                if (showImage && profile.imageSource !in profile.sources.map { it.url }) {
                    TextButton(onClick = { open(profile.imageSource) }) { Text(stringResource(R.string.rich_open_source)) }
                }
            }
        }
    }
}
