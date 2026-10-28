package com.androidharness.app.tools

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.ImageRef
import com.androidharness.app.core.Role
import com.androidharness.app.data.ImageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** References are discovered from this chat, never from paths or URLs supplied to the tool. */
internal sealed interface ChatImageSource {
    val label: String
    data class Stored(val ref: ImageRef, override val label: String) : ChatImageSource
    data class Remote(val url: String, override val label: String) : ChatImageSource
}

internal object ChatImageInventory {
    private val markdownImage = Regex("!\\[([^\\]]*)]\\((https?://[^)\\s]+)\\)")
    fun from(messages: List<ChatMessage>): List<ChatImageSource> = messages.asReversed().asSequence()
        .filter { it.role == Role.USER || (it.role == Role.ASSISTANT && it.toolCallId == null) }
        .flatMap { message ->
            val who = if (message.role == Role.USER) "user attachment" else "assistant image"
            val stored = message.images.asReversed().map { ChatImageSource.Stored(it, who) }
            val remote = if (message.role == Role.ASSISTANT) markdownImage.findAll(message.text).toList()
                .asReversed().map { ChatImageSource.Remote(it.groupValues[2], who) } else emptyList()
            val rich = if (message.role == Role.ASSISTANT) com.androidharness.app.core.response.RichResponse.images(message.text)
                .asReversed().map { ChatImageSource.Remote(it.url, it.caption.ifBlank { who }) } else emptyList()
            (rich + remote + stored).distinctBy { if (it is ChatImageSource.Remote) it.url else it }.asSequence()
        }.take(20).toList()

    fun prompt(messages: List<ChatMessage>): String {
        val images = from(messages)
        if (images.isEmpty()) return ""
        return "\n## Images available for close inspection\n" +
            images.mapIndexed { i, source -> "${i + 1}: ${source.label}" }.joinToString("\n") + "\n"
    }
}

/** Chat-only image close-ups. Coordinates describe the entire image on a 0..1000 scale. */
internal class ChatImageZoomTool(
    private val images: ImageStore,
    private val history: () -> List<ChatMessage>,
) : Tool {
    override val name = "zoom_chat_image"
    override val description = "Inspect a close-up of an image in this chat. Choose its number from the image list in the system context; 1 is the most recent. Coordinates are relative to the full image, from 0 to 1000, with (0,0) at the top left. Use this when fine detail matters."
    override val parametersSchema = Schema.obj(mapOf(
        "image_number" to Schema.integer("Image number from the chat image list; defaults to 1."),
        "left" to Schema.integer("Left edge from 0 to 1000."),
        "top" to Schema.integer("Top edge from 0 to 1000."),
        "right" to Schema.integer("Right edge from 0 to 1000; greater than left."),
        "bottom" to Schema.integer("Bottom edge from 0 to 1000; greater than top."),
    ), required = listOf("left", "top", "right", "bottom"))
    override val isReadOnly = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        if (ctx.sessionId == null) return@withContext ToolResult(false, "Open a chat first.")
        val number = if ("image_number" in args) args["image_number"]?.jsonPrimitive?.intOrNull
            ?: return@withContext ToolResult(false, "Choose a valid image number from the current image list.") else 1
        val source = ChatImageInventory.from(history()).getOrNull(number - 1)
            ?: return@withContext ToolResult(false, "That image is not available in this chat. Choose a number from the current image list.")
        fun coordinate(name: String) = args[name]?.jsonPrimitive?.intOrNull
        val left = coordinate("left")
        val top = coordinate("top")
        val right = coordinate("right")
        val bottom = coordinate("bottom")
        if (left == null || top == null || right == null || bottom == null ||
            left !in 0..999 || top !in 0..999 || right !in 1..1000 || bottom !in 1..1000 ||
            left >= right || top >= bottom
        ) return@withContext ToolResult(false, "Use coordinates from 0 to 1000 with right greater than left and bottom greater than top.")

        val original: Boolean
        val open: () -> java.io.InputStream = when (source) {
            is ChatImageSource.Stored -> {
                val file = images.savedImageFile(source.ref.name)
                    ?: return@withContext ToolResult(false, "The image is no longer available in this chat.")
                val savedOriginal = images.originalFile(source.ref.name)
                original = savedOriginal.isFile
                val selected = if (original) savedOriginal else file
                { selected.inputStream() }
            }
            is ChatImageSource.Remote -> {
                val bytes = images.remoteOriginal(source.url)
                    ?: return@withContext ToolResult(false, "The image could not be loaded for close inspection.")
                original = true
                { bytes.inputStream() }
            }
        }
        fun bounds(width: Int, height: Int): Rect {
            require(width in 1..100_000 && height in 1..100_000) { "Image dimensions are unsupported" }
            fun start(value: Int, size: Int) = (value.toLong() * size / 1000).toInt()
            fun end(value: Int, size: Int) = ((value.toLong() * size + 999) / 1000).toInt().coerceAtMost(size)
            return Rect(start(left, width), start(top, height), end(right, width), end(bottom, height))
        }
        var fallbackDownscaled = false
        val result = runCatching {
            // Region decoding avoids shrinking a large image before extracting its details.
            runCatching {
                open().use { stream ->
                    @Suppress("DEPRECATION")
                    val decoder = BitmapRegionDecoder.newInstance(stream, false) ?: error("Unsupported region decoder")
                    try {
                        val rect = bounds(decoder.width, decoder.height)
                        var sample = 1
                        while (maxOf(rect.width(), rect.height()) / sample > 1568) sample *= 2
                        val crop = decoder.decodeRegion(rect, BitmapFactory.Options().apply { inSampleSize = sample })
                            ?: error("Could not decode the selected region")
                        try { images.saveCloseUp(crop) } finally { crop.recycle() }
                    } finally { decoder.recycle() }
                }
            }.getOrElse {
                // Some formats cannot be region-decoded. Bound the full decode,
                // then disclose when that fallback had to reduce detail.
                val info = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                open().use { stream -> BitmapFactory.decodeStream(stream, null, info) }
                bounds(info.outWidth, info.outHeight)
                var sample = 1
                while (maxOf(info.outWidth, info.outHeight) / sample > 1568) sample *= 2
                fallbackDownscaled = sample > 1
                val bitmap = open().use { stream -> BitmapFactory.decodeStream(stream, null,
                    BitmapFactory.Options().apply { inSampleSize = sample }) } ?: error("Unsupported image format")
                try {
                    val rect = bounds(bitmap.width, bitmap.height)
                    val crop = Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width(), rect.height())
                    try { images.saveCloseUp(crop) } finally { if (crop !== bitmap) crop.recycle() }
                } finally { bitmap.recycle() }
            }
        }.getOrElse { return@withContext ToolResult(false, "Could not create a close-up of this image: ${it.message ?: "unsupported image"}.") }
        val quality = when {
            !original -> "The original is unavailable; this close-up uses the saved reduced image, so fine detail may be lost."
            fallbackDownscaled -> "The original image had to be reduced before cropping, so fine detail may be lost."
            else -> "The close-up was made from the original image."
        }
        ToolResult(true, "Close-up of chat image $number ($left,$top to $right,$bottom on the full image). $quality", image = result)
    }
}
