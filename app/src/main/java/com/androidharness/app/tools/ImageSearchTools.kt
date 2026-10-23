package com.androidharness.app.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class ImageSearchResult(
    val title: String,
    val imageUrl: String,
    val thumbnailUrl: String? = null,
    val pageUrl: String? = null,
    val source: String,
    val credit: String? = null,
)

/** Stable text shape for image_search, shared by the tool and the chat UI. */
internal object ImageSearchText {
    private val numbered = Regex("^\\d+\\.\\s+(.+)$")

    fun render(results: List<ImageSearchResult>, count: Int): String =
        results.take(count).mapIndexed { index, result ->
            buildString {
                append(index + 1).append(". ").append(result.title).append('\n')
                append("   image: ").append(result.imageUrl).append('\n')
                result.thumbnailUrl?.takeIf { it != result.imageUrl }
                    ?.let { append("   thumb: ").append(it).append('\n') }
                result.pageUrl?.let { append("   page: ").append(it).append('\n') }
                append("   source: ").append(result.source)
                result.credit?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                append('\n')
            }
        }.joinToString("")

    fun parse(text: String): List<ImageSearchResult> {
        if (text.isBlank()) return emptyList()
        val lines = text.lineSequence().toList()
        val out = ArrayList<ImageSearchResult>()
        var index = 0
        while (index < lines.size) {
            val title = numbered.matchEntire(lines[index].trim())?.groupValues?.get(1)
            if (title == null) {
                index++
                continue
            }
            var image: String? = null
            var thumb: String? = null
            var page: String? = null
            var source = ""
            var credit: String? = null
            var cursor = index + 1
            while (cursor < lines.size && !numbered.matches(lines[cursor].trim())) {
                val line = lines[cursor].trim()
                when {
                    line.startsWith("image:") -> image = line.removePrefix("image:").trim()
                    line.startsWith("thumb:") -> thumb = line.removePrefix("thumb:").trim()
                    line.startsWith("page:") -> page = line.removePrefix("page:").trim()
                    line.startsWith("source:") -> {
                        val raw = line.removePrefix("source:").trim()
                        source = raw.substringBefore(" · ").trim()
                        credit = raw.substringAfter(" · ", "").trim().takeIf { it.isNotBlank() }
                    }
                }
                cursor++
            }
            if (image != null) {
                out += ImageSearchResult(
                    title = title,
                    imageUrl = image,
                    thumbnailUrl = thumb,
                    pageUrl = page,
                    source = source.ifBlank { "Web" },
                    credit = credit,
                )
            }
            index = cursor
        }
        return out
    }
}

/**
 * Keyless image search. Openverse and Wikimedia Commons are real APIs with
 * licensed results and attribution; Bing Images is the general-web fallback.
 * The app renders the result list as an image grid.
 */
class ImageSearchTool(
    private val client: OkHttpClient,
) : Tool {
    override val name = "image_search"
    override val description =
        "Search for images and return direct image URLs with a thumbnail, source page, and credit. " +
            "Use it when the user asks to see pictures. The app renders the results as an image grid, " +
            "so describe the images and cite the source pages instead of repeating the image URLs."
    override val parametersSchema = Schema.obj(
        mapOf(
            "query" to Schema.string("What to find images of."),
            "count" to Schema.integer("Number of images (default 6, max 10)."),
        ),
        required = listOf("query"),
    )
    override val isReadOnly = true

    private val cache = object : LinkedHashMap<String, Pair<Long, List<ImageSearchResult>>>(8, .75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, Pair<Long, List<ImageSearchResult>>>,
        ) = size > 16
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val query = args["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (query.isEmpty()) throw ToolFailure("Missing required argument: query")
        val count = (args["count"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 6).coerceIn(1, 10)
        val key = "$query|$count"
        val cached = synchronized(cache) { cache[key] }
        val results = if (cached != null && System.currentTimeMillis() - cached.first < CACHE_TTL_MS) {
            cached.second
        } else {
            withContext(Dispatchers.IO) { search(query, count) }
        }
        if (results.isNotEmpty() && (cached == null || results !== cached.second)) {
            synchronized(cache) { cache[key] = System.currentTimeMillis() to results }
        }
        if (results.isEmpty()) {
            return ToolResult(
                false,
                "No images found for \"$query\". Tried Openverse, Wikimedia Commons, and Bing Images.",
            )
        }
        return ToolResult(
            true,
            ImageSearchText.render(results, count) +
                "\n[The app renders these as an image grid. Describe them and cite the page URLs; " +
                "do not repeat the image URLs.]",
        )
    }

    private fun search(query: String, count: Int): List<ImageSearchResult> {
        val searchClient = client.newBuilder()
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
        val out = LinkedHashMap<String, ImageSearchResult>()
        fun merge(items: List<ImageSearchResult>) {
            for (item in items) {
                if (out.size >= count) return
                val key = canonicalResultUrl(item.imageUrl)
                if (key.isNotEmpty() && !out.containsKey(key)) out[key] = item
            }
        }
        merge(OpenverseImageBackend.fetch(searchClient, query, count))
        if (out.size < count) {
            merge(CommonsImageBackend.fetch(searchClient, query, count - out.size))
        }
        if (out.size < count) {
            merge(BingImageBackend.fetch(searchClient, query, count - out.size))
        }
        return out.values.toList()
    }

    companion object {
        private const val CACHE_TTL_MS = 10 * 60_000L
    }
}

internal object OpenverseImageBackend {
    private val json = Json { ignoreUnknownKeys = true }

    fun fetch(client: OkHttpClient, query: String, count: Int): List<ImageSearchResult> = runCatching {
        val url = "https://api.openverse.org/v1/images/?q=${URLEncoder.encode(query, "UTF-8")}" +
            "&page_size=${count.coerceIn(1, 20)}"
        val body = client.newCall(
            Request.Builder()
                .url(url)
                .header("User-Agent", BROWSER_USER_AGENT)
                .header("Accept", "application/json")
                .build()
        ).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            response.body?.string() ?: ""
        }
        parse(body)
    }.getOrDefault(emptyList())

    fun parse(body: String): List<ImageSearchResult> = runCatching {
        val results = json.parseToJsonElement(body).jsonObject["results"]?.jsonArray
            ?: return emptyList()
        results.mapNotNull { element ->
            val item = element.jsonObject
            if (item["mature"]?.jsonPrimitive?.booleanOrNull == true) return@mapNotNull null
            val image = item["url"]?.jsonPrimitive?.contentOrNull
                ?.takeIf(::validImageUrl) ?: return@mapNotNull null
            val title = cleanImageText(item["title"]?.jsonPrimitive?.contentOrNull) ?: "Image"
            val page = item["foreign_landing_url"]?.jsonPrimitive?.contentOrNull
                ?.takeIf(::validPageUrl)
            val thumb = item["thumbnail"]?.jsonPrimitive?.contentOrNull
                ?.takeIf(::validImageUrl)
            val credit = cleanImageText(item["attribution"]?.jsonPrimitive?.contentOrNull)?.take(220)
            ImageSearchResult(title, image, thumb, page, "Openverse", credit)
        }
    }.getOrDefault(emptyList())
}

internal object CommonsImageBackend {
    private val json = Json { ignoreUnknownKeys = true }
    private const val USER_AGENT =
        "AndroidHarness/2.7 (image search; https://github.com/Sanuu7/AndroidHarness)"

    fun fetch(client: OkHttpClient, query: String, count: Int): List<ImageSearchResult> = runCatching {
        val url = "https://commons.wikimedia.org/w/api.php?action=query&generator=search" +
            "&gsrsearch=${URLEncoder.encode(query, "UTF-8")}&gsrnamespace=6" +
            "&gsrlimit=${count.coerceIn(1, 20)}&prop=imageinfo&iiprop=url%7Cextmetadata" +
            "&iiurlwidth=800&format=json"
        val body = client.newCall(
            Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .build()
        ).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            response.body?.string() ?: ""
        }
        parse(body)
    }.getOrDefault(emptyList())

    fun parse(body: String): List<ImageSearchResult> = runCatching {
        val pages = json.parseToJsonElement(body).jsonObject["query"]
            ?.jsonObject?.get("pages")?.jsonObject ?: return emptyList()
        pages.values.mapNotNull { element ->
            val page = element.jsonObject
            val info = page["imageinfo"]?.jsonArray?.firstOrNull()?.jsonObject
                ?: return@mapNotNull null
            val image = info["url"]?.jsonPrimitive?.contentOrNull?.takeIf(::validImageUrl)
            val thumb = info["thumburl"]?.jsonPrimitive?.contentOrNull?.takeIf(::validImageUrl)
            val display = thumb ?: image ?: return@mapNotNull null
            val pageUrl = info["descriptionurl"]?.jsonPrimitive?.contentOrNull
                ?.takeIf(::validPageUrl)
            val metadata = info["extmetadata"]?.jsonObject
            val license = metadata?.get("LicenseShortName")?.jsonObject
                ?.get("value")?.jsonPrimitive?.contentOrNull
            val artist = metadata?.get("Artist")?.jsonObject
                ?.get("value")?.jsonPrimitive?.contentOrNull
            val credit = listOfNotNull(cleanImageText(artist), cleanImageText(license))
                .joinToString(", ").takeIf { it.isNotBlank() }?.take(220)
            val title = cleanImageText(
                page["title"]?.jsonPrimitive?.contentOrNull
                    ?.removePrefix("File:")?.substringBeforeLast('.'),
            ) ?: "Image"
            ImageSearchResult(title, image ?: display, thumb ?: display, pageUrl, "Wikimedia Commons", credit)
        }
    }.getOrDefault(emptyList())
}

internal object BingImageBackend {
    private val json = Json { ignoreUnknownKeys = true }

    fun fetch(client: OkHttpClient, query: String, count: Int): List<ImageSearchResult> = runCatching {
        val url = "https://www.bing.com/images/search?q=${URLEncoder.encode(query, "UTF-8")}" +
            "&form=HDRSC2&first=1&adlt=strict"
        val html = client.newCall(
            Request.Builder()
                .url(url)
                .header("User-Agent", BROWSER_USER_AGENT)
                .header("Accept-Language", java.util.Locale.getDefault().language)
                .build()
        ).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            response.body?.string() ?: ""
        }
        parse(html, count)
    }.getOrDefault(emptyList())

    fun parse(html: String, count: Int = 10): List<ImageSearchResult> =
        Regex("class=\"iusc\"[^>]*?m=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
            .findAll(html)
            .mapNotNull { match ->
                val raw = decodeImageEntities(match.groupValues[1])
                val item = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
                    ?: return@mapNotNull null
                val image = item["murl"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf(::validImageUrl) ?: return@mapNotNull null
                val thumb = item["turl"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf(::validImageUrl)
                val page = item["purl"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf(::validPageUrl)
                val title = cleanImageText(
                    item["t"]?.jsonPrimitive?.contentOrNull
                        ?: item["desc"]?.jsonPrimitive?.contentOrNull,
                ) ?: "Image"
                ImageSearchResult(title, image, thumb, page, "Bing Images", null)
            }
            .take(count)
            .toList()
}

internal fun validImageUrl(url: String): Boolean {
    val value = url.trim()
    if (!value.startsWith("http://") && !value.startsWith("https://")) return false
    return !value.endsWith(".svg", ignoreCase = true)
}

internal fun validPageUrl(url: String): Boolean =
    url.trim().let { it.startsWith("http://") || it.startsWith("https://") }

internal fun cleanImageText(value: String?): String? {
    val text = value
        ?.replace(Regex("[\\uE000\\uE001]"), "")
        ?.replace(Regex("<[^>]+>"), " ")
        ?.let(::decodeImageEntities)
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?: return null
    return text.takeIf { it.isNotEmpty() }
}

private fun decodeImageEntities(value: String): String = value
    .replace("&quot;", "\"")
    .replace("&#34;", "\"")
    .replace("&amp;", "&")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&#39;", "'")
    .replace("&apos;", "'")
    .replace("&nbsp;", " ")
    .replace(Regex("&#(\\d+);")) { codePoint(it.groupValues[1].toIntOrNull()) }
    .replace(Regex("&#[xX]([0-9a-fA-F]+);")) { codePoint(it.groupValues[1].toIntOrNull(16)) }

private fun codePoint(value: Int?): String {
    if (value == null || value <= 0 || value > 0x10FFFF) return ""
    return runCatching { String(Character.toChars(value)) }.getOrDefault("")
}
