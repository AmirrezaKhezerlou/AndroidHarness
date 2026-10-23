package com.androidharness.app.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.androidharness.app.tools.BROWSER_USER_AGENT
import com.androidharness.app.tools.PublicHttpAddresses
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Loads remote images and link previews for chat answers.
 *
 * Images are bounded in bytes and decoded size, cached in memory and on disk,
 * and only fetched from public http(s) hosts. Previews read the page head for
 * Open Graph tags. Nothing here executes page scripts, so the SVG/HTML
 * injection class of bug does not apply.
 */
internal object RemoteContentLoader {
    private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
    private const val MAX_IMAGE_EDGE = 1600
    private const val MEMORY_CACHE_BYTES = 24 * 1024 * 1024
    private const val DISK_CACHE_BYTES = 32L * 1024 * 1024
    private const val MAX_PREVIEW_BYTES = 512 * 1024L
    private const val PREVIEW_TTL_MS = 24 * 60 * 60 * 1000L
    private const val MAX_PREVIEWS = 64

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val noRedirectClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val memory = object : LruCache<String, Bitmap>(MEMORY_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private data class CachedPreview(val value: LinkPreview, val at: Long)
    private val previews = ConcurrentHashMap<String, CachedPreview>()

    suspend fun loadImage(context: Context, rawUrl: String, referer: String? = null): Bitmap? = withContext(Dispatchers.IO) {
        val url = rawUrl.toHttpUrlOrNull() ?: return@withContext null
        val key = url.toString()
        memory.get(key)?.let { return@withContext it }
        if (!isHttp(url) || !isPublic(url)) return@withContext null
        val file = imageCacheFile(context, key)
        val bytes = if (file.exists()) {
            runCatching { file.readBytes() }.getOrNull()
        } else null
        val downloaded = bytes ?: runCatching { downloadImage(url, referer) }.getOrNull() ?: return@withContext null
        if (bytes == null) {
            runCatching { file.writeBytes(downloaded) }
            trimDiskCache(context)
        }
        val bitmap = decode(downloaded) ?: return@withContext null
        memory.put(key, bitmap)
        bitmap
    }

    suspend fun loadPreview(rawUrl: String): LinkPreview? = withContext(Dispatchers.IO) {
        val url = rawUrl.toHttpUrlOrNull() ?: return@withContext null
        val key = url.toString()
        previews[key]?.takeIf { System.currentTimeMillis() - it.at < PREVIEW_TTL_MS }
            ?.let { return@withContext it.value }
        if (!isHttp(url) || !isPublic(url)) return@withContext null
        val preview = LinkPreviewParser.youtubeVideoId(key)?.let { id ->
            LinkPreview(
                url = key,
                title = null,
                description = null,
                imageUrl = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
                siteName = "YouTube",
                embedUrl = "https://www.youtube.com/embed/$id",
            )
        } ?: fetchPreview(url)
        if (previews.size >= MAX_PREVIEWS) previews.clear()
        previews[key] = CachedPreview(preview, System.currentTimeMillis())
        preview
    }

    private fun downloadImage(url: HttpUrl, referer: String?, allowQueryStrip: Boolean = true): ByteArray? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_USER_AGENT)
            .header("Accept", "image/avif,image/webp,image/png,image/jpeg,image/*;q=0.8")
            .apply { referer?.takeIf { it.startsWith("http://") || it.startsWith("https://") }?.let { header("Referer", it) } }
            .build()
        evaluate(request)?.use { response ->
            val type = response.body?.contentType()?.toString()?.lowercase().orEmpty()
            if (type.contains("svg")) {
                // Some CDNs answer a resized preview URL with an SVG placeholder
                // while the plain asset URL is a normal image. Drop the query
                // once before giving up.
                if (allowQueryStrip && url.query != null) return downloadImage(url.newBuilder().query(null).build(), referer, false)
                return null
            }
            if (type.isNotEmpty() && !type.startsWith("image/")) return null
            val length = response.body?.contentLength() ?: -1L
            if (length > MAX_IMAGE_BYTES) return null
            val source = response.body?.source() ?: return null
            source.request(MAX_IMAGE_BYTES.toLong() + 1)
            val buffer = source.buffer
            if (buffer.size > MAX_IMAGE_BYTES) return null
            return buffer.readByteArray()
        }
        return null
    }

    private fun fetchPreview(url: HttpUrl): LinkPreview {
        val host = url.host.removePrefix("www.")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.5")
            .header("Accept-Language", java.util.Locale.getDefault().language)
            .build()
        return evaluate(request)?.use { response ->
            val finalUrl = response.request.url.toString()
            val type = response.body?.contentType()?.toString()?.lowercase().orEmpty()
            if (type.isNotEmpty() && !type.contains("html") && !type.contains("text")) {
                return@use LinkPreview(finalUrl, null, null, null, host)
            }
            val html = response.peekBody(MAX_PREVIEW_BYTES).string()
            LinkPreviewParser.parse(html, finalUrl, url.toString())
        } ?: LinkPreview(url.toString(), null, null, null, host)
    }

    /** Follows redirects by hand so every hop is checked against the public-address rule. */
    private fun evaluate(request: Request): Response? {
        var current = request
        repeat(4) {
            val url = current.url
            if (!isHttp(url) || !isPublic(url)) return null
            val response = noRedirectClient.newCall(current).execute()
            if (response.isSuccessful) return response
            if (response.code in 301..308) {
                val location = response.header("Location")
                response.close()
                val next = location?.let { url.resolve(it) } ?: return null
                current = current.newBuilder().url(next).build()
            } else {
                response.close()
                return null
            }
        }
        return null
    }

    private fun isHttp(url: HttpUrl): Boolean = url.scheme == "http" || url.scheme == "https"

    private fun isPublic(url: HttpUrl): Boolean {
        val addresses = runCatching { client.dns.lookup(url.host) }.getOrNull() ?: return false
        return addresses.isNotEmpty() && addresses.all { PublicHttpAddresses.isPublic(it) }
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_IMAGE_EDGE) sample *= 2
        return BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }

    private fun imageDir(context: Context) = File(context.cacheDir, "remote-images").apply { mkdirs() }

    private fun imageCacheFile(context: Context, key: String): File =
        File(imageDir(context), sha256(key) + ".img")

    private fun trimDiskCache(context: Context) {
        val files = imageDir(context).listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= DISK_CACHE_BYTES) break
            total -= file.length()
            runCatching { file.delete() }
        }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
