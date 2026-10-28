package com.androidharness.app.ui.chat.rich

import android.graphics.Bitmap
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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

/** A photo-led knowledge panel; the original picture is always shown without cropping. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ResponseProfile(profile: RichResponse.Node.Profile, onOpenUrl: ((String) -> Unit)?) {
    val uri = LocalUriHandler.current
    val open: (String) -> Unit = onOpenUrl ?: { uri.openUri(it) }
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val showImage = LocalChatImagesEnabled.current && profile.image.isNotBlank()
    var imageExpanded by rememberSaveable(profile.image) { mutableStateOf(false) }
    LaunchedEffect(showImage) { if (!showImage) imageExpanded = false }
    val photo by produceState<Pair<Boolean, Bitmap?>>(showImage to null, profile.image, showImage) {
        value = false to if (showImage) RemoteContentLoader.loadImage(context, profile.image) else null
    }
    if (imageExpanded && showImage) ImageViewerDialog(profile.image, profile.imageSource, { imageExpanded = false }, { url -> imageExpanded = false; open(url) })
    Surface(shape = RoundedCornerShape(28.dp), color = colors.surfaceContainerLow,
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = .45f)),
        modifier = Modifier.fillMaxWidth().animateContentSize(expressiveSpatialSpec())) {
        Column {
            if (showImage && (photo.first || photo.second != null)) BoxWithConstraints(Modifier.fillMaxWidth()) {
                val bitmap = photo.second
                val ratio = bitmap?.let { it.width.toFloat() / it.height.coerceAtLeast(1) } ?: (4f / 3f)
                val height = (maxWidth / ratio).coerceIn(180.dp, 300.dp)
                Box(Modifier.fillMaxWidth().height(height).background(colors.surfaceContainerHigh)
                    .clickable(enabled = bitmap != null, onClickLabel = context.getString(R.string.rich_expand_image)) { imageExpanded = true },
                    contentAlignment = Alignment.Center) {
                    if (bitmap == null) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    else {
                        // The soft backdrop fills a portrait's side gutters; the foreground retains the complete source image.
                        Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize().blur(24.dp).graphicsLayer { alpha = .22f }, contentScale = ContentScale.Crop)
                        Image(bitmap.asImageBitmap(), profile.name, Modifier.fillMaxSize().padding(12.dp), contentScale = ContentScale.Fit)
                    }
                }
            }
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(profile.name, style = MaterialTheme.typography.headlineSmallEmphasized)
                    if (profile.subtitle.isNotBlank()) Text(profile.subtitle, style = MaterialTheme.typography.titleSmall,
                        color = colors.primary)
                } }
                SelectionContainer { MarkdownText(profile.description, onOpenUrl = open) }
                if (profile.facts.isNotEmpty()) Surface(shape = RoundedCornerShape(20.dp), color = colors.surfaceContainer) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                        profile.facts.forEachIndexed { index, fact ->
                            if (index > 0) HorizontalDivider(color = colors.outlineVariant.copy(alpha = .4f))
                            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SelectionContainer(Modifier.weight(1f)) { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(fact.label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                                    Text(fact.value, style = MaterialTheme.typography.bodyLarge)
                                } }
                                IconButton(onClick = { open(fact.source) }, modifier = Modifier.size(48.dp)) {
                                    Icon(Icons.Outlined.OpenInNew, stringResource(R.string.rich_open_source), Modifier.size(17.dp), tint = colors.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    profile.sources.forEach { source ->
                        SuggestionChip(onClick = { open(source.url) }, label = { Text(source.title) }, shape = RoundedCornerShape(50),
                            icon = { Icon(Icons.Outlined.OpenInNew, null, Modifier.size(14.dp)) })
                    }
                    if (showImage && profile.imageSource !in profile.sources.map { it.url }) SuggestionChip(
                        onClick = { open(profile.imageSource) }, label = { Text(stringResource(R.string.rich_open_source)) }, shape = RoundedCornerShape(50),
                        icon = { Icon(Icons.Outlined.Image, null, Modifier.size(14.dp)) })
                }
            }
        }
    }
}
