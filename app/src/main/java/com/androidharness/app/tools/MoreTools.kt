package com.androidharness.app.tools

import com.androidharness.app.agent.TodoItem
import com.androidharness.app.agent.TodoStore
import com.androidharness.app.workspace.FileFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class CreateDirTool : Tool {
    override val name = "create_dir"
    override val description =
        "Create a directory (including parents) in the workspace. Fails if the path exists and is a file."
    override val parametersSchema = Schema.obj(
        mapOf("path" to Schema.string("Directory path relative to the workspace root.")),
        required = listOf("path"),
    )
    override val isReadOnly = false

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val path = args["path"]?.jsonPrimitive?.content
                ?: throw ToolFailure("Missing required argument: path")
            val node = ctx.workspace.resolve(path)
            if (node.exists && node.isFile) {
                throw ToolFailure("Cannot create directory: $path already exists and is a file")
            }
            if (node.exists && node.isDirectory) {
                return@withContext ToolResult(true, "Directory already exists at $path")
            }
            node.mkdirs()
            if (!node.exists || !node.isDirectory) {
                throw ToolFailure("Failed to create directory $path")
            }
            val warn = caseCollisionWarning(ctx.workspace, node)
            ToolResult(
                true,
                buildString {
                    append("Created directory $path")
                    if (warn != null) append('\n').append(warn)
                },
            )
        }
}

class DeleteFileTool : Tool {
    override val name = "delete_file"
    override val description =
        "Delete a file or directory in the workspace. Deleting a directory that contains anything " +
            "requires recursive=true. This cannot be undone. The workspace root can never be deleted."
    override val parametersSchema = Schema.obj(
        mapOf(
            "path" to Schema.string("Path relative to the workspace root. Must not be the workspace root."),
            "recursive" to Schema.boolean(
                "Required (true) to delete a directory that has files or subdirectories inside it.",
            ),
        ),
        required = listOf("path"),
    )
    override val isReadOnly = false

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val path = args["path"]?.jsonPrimitive?.content
                ?: throw ToolFailure("Missing required argument: path")
            val recursive = args["recursive"]?.jsonPrimitive?.booleanOrNull ?: false
            if (path.isBlank() || path.trim() == ".") {
                throw ToolFailure(
                    "Refusing to delete the workspace root. Delete the contents individually if that is intended.",
                )
            }
            val node = ctx.workspace.resolve(path)
            if (node.relPath == "." || node.relPath.isBlank()) {
                throw ToolFailure(
                    "Refusing to delete the workspace root. Delete the contents individually if that is intended.",
                )
            }
            if (!node.exists) throw ToolFailure("Path does not exist: $path")
            if (node.isDirectory) {
                val childCount = node.list().size
                if (childCount > 0 && !recursive) {
                    throw ToolFailure(
                        "$path is a directory containing $childCount entr${if (childCount == 1) "y" else "ies"}. " +
                            "Pass recursive=true to delete it with everything inside, or delete the contents individually.",
                    )
                }
            }
            val wasDirectory = node.isDirectory
            if (node.delete()) {
                val what = if (wasDirectory) "directory" else "file"
                ToolResult(true, "Deleted $what $path")
            } else {
                ToolResult(false, "Failed to delete $path")
            }
        }
}

class MoveFileTool : Tool {
    override val name = "move_file"
    override val description =
        "Move or rename a file within the workspace. Refuses to overwrite an existing destination " +
            "file unless overwrite=true."
    override val parametersSchema = Schema.obj(
        mapOf(
            "source" to Schema.string("Current path relative to the workspace root."),
            "destination" to Schema.string("New path relative to the workspace root."),
            "overwrite" to Schema.boolean(
                "Replace an existing destination file. Default false: the move fails instead of " +
                    "silently destroying the file that is already there.",
            ),
        ),
        required = listOf("source", "destination"),
    )
    override val isReadOnly = false

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val source = args["source"]?.jsonPrimitive?.content
                ?: throw ToolFailure("Missing required argument: source")
            val destination = args["destination"]?.jsonPrimitive?.content
                ?: throw ToolFailure("Missing required argument: destination")
            val overwrite = args["overwrite"]?.jsonPrimitive?.booleanOrNull ?: false

            try {
                val from = ctx.workspace.resolve(source)
                if (!from.exists) {
                    if (com.androidharness.app.workspace.isNameTooLong(null, source)) {
                        throw ToolFailure(com.androidharness.app.workspace.longFilenameGuidance(source))
                    }
                    throw ToolFailure("Source does not exist: $source")
                }
                if (com.androidharness.app.workspace.isNameTooLong(null, destination)) {
                    throw ToolFailure(com.androidharness.app.workspace.longFilenameGuidance(destination))
                }
                val to = ctx.workspace.resolve(destination)

                val srcParts = source.trim('/').replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
                val dstParts = destination.trim('/').replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
                if (srcParts.isEmpty()) throw ToolFailure("Cannot move workspace root.")
                if (dstParts.isEmpty()) throw ToolFailure("Destination cannot be workspace root.")
                if (srcParts == dstParts) throw ToolFailure("Source and destination are the same: $source")

                if (dstParts.size > srcParts.size && dstParts.subList(0, srcParts.size) == srcParts) {
                    throw ToolFailure("Cannot move '$source' into itself or a subdirectory: '$destination'")
                }

                if (from.isDirectory && srcParts.size > dstParts.size && srcParts.subList(0, dstParts.size) == dstParts) {
                    throw ToolFailure("Cannot move directory '$source' into its ancestor '$destination'")
                }

                if (to.exists && to.isDirectory) {
                    throw ToolFailure("Destination already exists and is a directory: $destination")
                }

                if (from.isDirectory && to.exists) {
                    throw ToolFailure("Destination already exists: $destination")
                }

                // A file destination is never replaced silently: the caller has
                // to say so, or a typo in the destination path destroys a file
                // it never mentioned (QA, 2026-09-21).
                if (to.exists && !overwrite) {
                    throw ToolFailure(
                        "Destination already exists: $destination. Pass overwrite=true to replace it.",
                    )
                }

                val srcParent = if (srcParts.size > 1) srcParts.dropLast(1).joinToString("/") else ""
                val dstParent = if (dstParts.size > 1) dstParts.dropLast(1).joinToString("/") else ""
                val sameDir = srcParent == dstParent
                val warn = caseCollisionWarning(ctx.workspace, to)
                if (sameDir && from.renameTo(to.name)) {
                    return@withContext ToolResult(
                        true,
                        buildString {
                            append("Moved $source → $destination")
                            if (warn != null) append('\n').append(warn)
                        },
                    )
                }
                // Slow path: copy content + delete (files only)
                if (!from.isFile) {
                    throw ToolFailure("Moving directories across folders is not supported; move the files individually.")
                }
                (from.openInputStream() ?: throw ToolFailure("Cannot read source file: $source")).use { to.writeFrom(it) }
                if (!from.delete()) return@withContext ToolResult(false,
                    "Copied $source to $destination, but could not delete the source. Both files remain; check permissions before retrying.")
                ToolResult(
                    true,
                    buildString {
                        append("Moved $source → $destination")
                        if (warn != null) append('\n').append(warn)
                    },
                )
            } catch (e: Exception) {
                if (e is ToolFailure) throw e
                if (com.androidharness.app.workspace.isNameTooLong(e, source)) {
                    throw ToolFailure(com.androidharness.app.workspace.longFilenameGuidance(source))
                }
                if (com.androidharness.app.workspace.isNameTooLong(e, destination)) {
                    throw ToolFailure(com.androidharness.app.workspace.longFilenameGuidance(destination))
                }
                throw e
            }
        }
}

class WebFetchTool(
    private val client: OkHttpClient,
    /** Turn-scoped page-read allowance; null in Code and Auto. */
    private val readBudget: SearchSourceBudget? = null,
) : Tool {
    override val name = "web_fetch"
    override val description =
        "Fetch a URL and return readable text. HTML is reduced to the main content, with scripts, " +
            "navigation, and footers removed; PDFs are text-extracted. Long pages can be paged with " +
            "offset and max_chars, or searched with find. Set images=true to also return direct image URLs from the page."
    override val parametersSchema = Schema.obj(
        mapOf(
            "url" to Schema.string("The http(s) URL to fetch."),
            "offset" to Schema.integer("Start character offset for long pages. Defaults to 0."),
            "max_chars" to Schema.integer("Maximum characters to return (default 20000, max 60000)."),
            "find" to Schema.string("Optional terms; return the passages that match them instead of the page start."),
            "images" to Schema.boolean("Also return direct image URLs found on the page (default false)."),
        ),
        required = listOf("url"),
    )
    override val isReadOnly = true

    internal fun forBudget(budget: SearchSourceBudget?): WebFetchTool = WebFetchTool(client, budget)

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val url = args["url"]?.jsonPrimitive?.content
                ?: throw ToolFailure("Missing required argument: url")
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                throw ToolFailure("Only http(s) URLs are supported.")
            }
            val offset = (args["offset"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0).coerceAtLeast(0)
            val maxChars = (args["max_chars"]?.jsonPrimitive?.content?.toIntOrNull() ?: 20_000)
                .coerceIn(1_000, 60_000)
            val find = args["find"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
            val includeImages = args["images"]?.jsonPrimitive?.booleanOrNull == true
            if (readBudget != null && !readBudget.canRead()) {
                return@withContext ToolResult(true, readLimitText())
            }
            WebResultCache.getPage(url)?.let { cached ->
                if (readBudget != null && !readBudget.claimRead()) {
                    return@withContext ToolResult(true, readLimitText())
                }
                val imageNote = if (includeImages) renderImageNote(WebResultCache.getPageImages(url).orEmpty()) else ""
                return@withContext ToolResult(true, renderPage(cached, offset, maxChars, find) + imageNote + readNote())
            }
            val fetchClient = client.newBuilder()
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
            try {
                fetchClient.newCall(
                    Request.Builder()
                        .url(url)
                        .header("User-Agent", BROWSER_USER_AGENT)
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,text/plain;q=0.8,*/*;q=0.7")
                        .header("Accept-Language", languageHeader(java.util.Locale.getDefault().language))
                        .build()
                ).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return@withContext ToolResult(false, "HTTP ${resp.code} fetching $url")
                    }
                    val body = resp.body
                    val mediaType = body?.contentType()
                    val bytes = body?.bytes() ?: ByteArray(0)
                    val mime = mediaType?.let { "${it.type}/${it.subtype}" } ?: "application/octet-stream"

                    val isPdf = mediaType?.subtype?.equals("pdf", true) == true || bytes.startsWithAscii("%PDF-")
                    if (isPdf) {
                        val extracted = com.androidharness.app.ui.chat.PdfTextExtractor.extract(bytes)
                            ?: return@withContext ToolResult(true, "[PDF with no extractable text: $mime, ${bytes.size} bytes]")
                        WebResultCache.putPage(url, extracted.text)
                        WebResultCache.putPageImages(url, emptyList())
                        if (readBudget != null && !readBudget.claimRead()) {
                            return@withContext ToolResult(true, readLimitText())
                        }
                        return@withContext ToolResult(true, renderPage(extracted.text, offset, maxChars, find) + readNote())
                    }
                    if (HttpBinaryDetector.isBinaryMediaType(mediaType)) {
                        return@withContext ToolResult(true, "[binary content: $mime, ${bytes.size} bytes]")
                    }
                    if (mediaType == null && bytes.isNotEmpty() && com.androidharness.app.workspace.isBinaryStream(java.io.ByteArrayInputStream(bytes))) {
                        return@withContext ToolResult(true, "[binary content: $mime, ${bytes.size} bytes]")
                    }
                    val raw = bytes.toString(mediaType?.charset() ?: Charsets.UTF_8)
                    val text = htmlToText(raw, mediaType?.subtype)
                    WebResultCache.putPage(url, text)
                    val pageUrl = resp.request.url.toString()
                    val host = resp.request.url.host
                    val scan = scanPageImages(raw, pageUrl)
                    val pageImages = scan.images.map { image ->
                        PageImage(
                            title = image.alt ?: scan.title ?: host,
                            imageUrl = image.url,
                            pageUrl = pageUrl,
                            source = host,
                        )
                    }
                    WebResultCache.putPageImages(url, pageImages)
                    if (readBudget != null && !readBudget.claimRead()) {
                        return@withContext ToolResult(true, readLimitText())
                    }
                    val jsNote = if (looksJavascriptOnly(raw, text)) {
                        "\n[note: this page appears to require JavaScript; readable text may be incomplete]"
                    } else ""
                    val imageNote = if (includeImages) renderImageNote(pageImages) else ""
                    ToolResult(true, renderPage(text, offset, maxChars, find) + jsNote + imageNote + readNote())
                }
            } catch (e: Exception) {
                ToolResult(false, "Fetch failed: ${e.message}")
            }
        }

    private fun renderImageNote(images: List<PageImage>): String =
        if (images.isEmpty()) "\n[no image urls found]"
        else "\n" + PageImageText.render(images, images.size)

    private fun readLimitText(): String {
        val budget = readBudget ?: return ""
        return "[Page read limit reached: ${budget.pagesRead}/${budget.maxReads} pages this turn. " +
            "Answer from the pages already read.]"
    }

    private fun readNote(): String {
        val budget = readBudget ?: return ""
        val limit = budget.maxReads ?: return ""
        return "\n[pages read this turn: ${budget.pagesRead}/$limit]"
    }

    private fun renderPage(text: String, offset: Int, maxChars: Int, find: String?): String {
        if (find != null) return excerpt(text, find, maxChars)
        if (text.isEmpty()) return "[no readable text]"
        val start = offset.coerceIn(0, text.length)
        val end = (start + maxChars).coerceAtMost(text.length)
        val body = text.substring(start, end)
        if (start == 0 && end == text.length) return body
        return buildString {
            append("[chars ").append(start + 1).append('-').append(end).append(" of ").append(text.length).append("]\n")
            append(body)
            if (end < text.length) append("\n[more text: use offset=").append(end).append(']')
        }
    }

    private fun excerpt(text: String, terms: String, maxChars: Int): String {
        val needles = terms.lowercase().split(Regex("\\s+")).filter { it.length > 1 }
        if (needles.isEmpty()) return renderPage(text, 0, maxChars, null)
        val paragraphs = text.split(Regex("\\n+")).map { it.trim() }.filter { it.isNotEmpty() }
        val scored = paragraphs.mapNotNull { paragraph ->
            val score = needles.count { paragraph.contains(it, ignoreCase = true) }
            if (score == 0) null else score to paragraph
        }.sortedByDescending { it.first }
        if (scored.isEmpty()) {
            return "No passages matched \"$terms\".\n" + renderPage(text, 0, maxChars, null)
        }
        val out = StringBuilder("[passages matching \"").append(terms).append("\"]\n")
        for ((_, paragraph) in scored) {
            val room = maxChars - out.length
            if (room <= 0) break
            out.append(paragraph.take(room)).append("\n\n")
            if (paragraph.length > room) break
        }
        return out.toString().trimEnd()
    }
}

private fun ByteArray.startsWithAscii(prefix: String): Boolean {
    if (size < prefix.length) return false
    return prefix.indices.all { this[it].toInt() == prefix[it].code }
}

/** A page that is mostly a JS shell, or that says JS is required, gets a warning. */
private fun looksJavascriptOnly(raw: String, text: String): Boolean =
    Regex("(?i)(enable|requires?) javascript|javascript is disabled").containsMatchIn(text) ||
        (raw.length > 4_000 && text.length < 400)

/**
 * Page title and image references from one HTML scan. Open Graph comes first,
 * then visible img tags; icon-sized images and SVG are skipped.
 */
internal fun scanPageImages(html: String, baseUrl: String): PageImageScan {
    val base = baseUrl.toHttpUrlOrNull() ?: return PageImageScan(null, emptyList())
    val metas = LinkedHashMap<String, MutableList<String>>()
    Regex("<meta\\s+[^>]*>", RegexOption.IGNORE_CASE).findAll(html).forEach { match ->
        val tag = match.value
        val key = Regex("(?:property|name)\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
            .find(tag)?.groupValues?.get(1)?.lowercase() ?: return@forEach
        val content = Regex("content\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)
            .find(tag)?.groupValues?.get(1) ?: return@forEach
        metas.getOrPut(key) { mutableListOf() }.add(content)
    }
    val title = cleanPageImageText(metas["og:title"]?.firstOrNull()) ?: cleanPageImageText(
        Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)?.groupValues?.get(1),
    )
    val out = LinkedHashMap<String, PageImageRef>()
    fun add(raw: String?, alt: String?) {
        val value = raw?.trim()?.substringBefore(' ')?.takeIf { it.isNotEmpty() } ?: return
        if (value.startsWith("data:")) return
        val resolved = if (value.startsWith("http://") || value.startsWith("https://")) value
        else base.resolve(value)?.toString() ?: return
        if (resolved.endsWith(".svg", ignoreCase = true)) return
        out.putIfAbsent(resolved, PageImageRef(resolved, cleanPageImageText(alt)?.take(200)))
    }
    val ogAlts = metas["og:image:alt"].orEmpty()
    (metas["og:image"].orEmpty() + metas["og:image:url"].orEmpty()).forEachIndexed { index, url ->
        add(url, ogAlts.getOrNull(index) ?: ogAlts.firstOrNull())
    }
    val twitterAlts = metas["twitter:image:alt"].orEmpty()
    metas["twitter:image"].orEmpty().forEachIndexed { index, url ->
        add(url, twitterAlts.getOrNull(index) ?: twitterAlts.firstOrNull())
    }
    for (match in Regex("<img\\s+[^>]*>", RegexOption.IGNORE_CASE).findAll(html)) {
        if (out.size >= 8) break
        val tag = match.value
        val width = Regex("width\\s*=\\s*[\"']?(\\d+)", RegexOption.IGNORE_CASE)
            .find(tag)?.groupValues?.get(1)?.toIntOrNull()
        val height = Regex("height\\s*=\\s*[\"']?(\\d+)", RegexOption.IGNORE_CASE)
            .find(tag)?.groupValues?.get(1)?.toIntOrNull()
        if ((width != null && width < 120) || (height != null && height < 120)) continue
        val src = Regex("(?:data-src|data-original|src)\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
            .find(tag)?.groupValues?.get(1)
        val srcset = Regex("srcset\\s*=\\s*[\"']([^\"']+)", RegexOption.IGNORE_CASE)
            .find(tag)?.groupValues?.get(1)?.substringBefore(',')
        val alt = Regex("alt\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)
            .find(tag)?.groupValues?.get(1)
        add(src ?: srcset, alt)
    }
    return PageImageScan(title, out.values.take(8).toList())
}

/**
 * Strips HTML into readable text.
 *
 * The trigger used to be "does the body contain <html or <body", which every
 * fragment-only document slips past: the served fixture
 * `<!doctype html><title>Next 731</title><a href="/">Home</a><p>NEXT_731</p>`
 * came back as raw markup even though the response was text/html (on-device
 * QA, 2026-09-17). Detection now also accepts the response's own content type,
 * an HTML doctype, and — when the response carries no content type at all — a
 * body that opens with an opening tag. Text that merely mentions HTML stays
 * untouched, and so do JSON, plain text and XML documents such as SVG.
 */
internal fun htmlToText(raw: String, contentTypeSubtype: String? = null): String {
    if (!looksLikeHtml(raw, contentTypeSubtype)) return raw
    return mainContent(raw)
        .replace(Regex("(?s)<script.*?</script>"), " ")
        .replace(Regex("(?s)<style.*?</style>"), " ")
        .replace(Regex("(?s)<noscript.*?</noscript>"), " ")
        .replace(Regex("(?s)<svg.*?</svg>"), " ")
        .replace(Regex("(?s)<canvas.*?</canvas>"), " ")
        .replace(Regex("(?s)<iframe.*?</iframe>"), " ")
        .replace(Regex("(?s)<nav\\b.*?</nav>"), " ")
        .replace(Regex("(?s)<aside\\b.*?</aside>"), " ")
        .replace(Regex("(?s)<footer\\b.*?</footer>"), " ")
        .replace(Regex("(?s)<form\\b.*?</form>"), " ")
        .replace(Regex("(?s)<!--.*?-->"), " ")
        .replace(Regex("<br\\s*/?>"), "\n")
        // Block-level boundaries become line breaks, on BOTH sides of the tag.
        // Dropping tags with no separator ran the whole page together, so a
        // doctype-less fixture came back as "Next 731HomeNEXT_731" (retest,
        // 2026-09-17). Inline elements (a, span, b, code, img…) still vanish
        // without breaking the run of text, which is what a browser does.
        .replace(BLOCK_BOUNDARY, "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace(Regex("&nbsp;"), " ")
        .replace(Regex("&amp;"), "&")
        .replace(Regex("&lt;"), "<")
        .replace(Regex("&gt;"), ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace(Regex("&#(\\d+);")) { decodeEntity(it.groupValues[1].toIntOrNull()) }
        .replace(Regex("&#[xX]([0-9a-fA-F]+);")) { decodeEntity(it.groupValues[1].toIntOrNull(16)) }
        .replace(Regex("[ \\t\\u00a0]+"), " ")
        .replace(Regex(" ?\\n ?"), "\n")
        // Adjacent boundaries (</div><div>) and any blank line the source had
        // collapse to ONE newline: the point is that terms stop running
        // together, not to reproduce the page's vertical spacing, and blank
        // lines between every element would just cost tokens.
        .replace(Regex("\\n+"), "\n")
        .trim()
}

/** Prefer the article/main container when it holds a real share of the page. */
private fun mainContent(raw: String): String {
    val candidates = listOfNotNull(
        Regex("(?is)<article\\b[^>]*>(.*?)</article>").find(raw)?.groupValues?.get(1),
        Regex("(?is)<main\\b[^>]*>(.*?)</main>").find(raw)?.groupValues?.get(1),
    )
    val best = candidates.maxByOrNull { it.length } ?: return raw
    // A container with a real body of text is the article; a short teaser is
    // not trusted over the page itself.
    return if (best.length >= 200) best else raw
}

private fun decodeEntity(codePoint: Int?): String {
    if (codePoint == null || codePoint <= 0 || codePoint > 0x10FFFF) return ""
    return runCatching { String(Character.toChars(codePoint)) }.getOrDefault("")
}

/**
 * Elements that render as their own block, so a line break belongs on each
 * side of them. This is what `innerText` would show; the list covers the
 * structural, list, table, heading and text-block elements.
 */
private const val BLOCK_TAGS =
    "address|article|aside|blockquote|dd|div|dl|dt|fieldset|figcaption|figure|footer|form|" +
        "h[1-6]|header|hr|li|main|nav|ol|p|pre|section|table|tbody|td|tfoot|th|thead|title|tr|ul"

/** Opening or closing tag of any [BLOCK_TAGS] element, with its attributes. */
private val BLOCK_BOUNDARY = Regex("(?i)</?(?:$BLOCK_TAGS)\\b[^>]*>")

/** Whether [raw] is HTML, by response media subtype first and body shape second. */
internal fun looksLikeHtml(raw: String, contentTypeSubtype: String? = null): Boolean {
    if (raw.contains("<html", ignoreCase = true) || raw.contains("<body", ignoreCase = true)) {
        return true
    }
    // An HTML doctype is unambiguous whatever the server labelled the response:
    // a mislabelled text/plain or application/xml page still is HTML.
    if (raw.trimStart().startsWith("<!doctype html", ignoreCase = true)) return true
    // Otherwise trust a content type that says HTML, even for a fragment with
    // no <html>/<body> wrapper. `xml`, `svg+xml` and friends are deliberately
    // NOT included: those are documents, not pages, and stripping their tags
    // would destroy them.
    val sub = contentTypeSubtype?.lowercase().orEmpty()
    if (sub.isNotEmpty()) return sub == "html" || sub.endsWith("+html") || sub == "xhtml"
    // No content type at all: fall back to body shape. A body that opens with
    // an opening tag is a fragment; `<` followed by a space (`1 < 2`) is not.
    return Regex("^<[A-Za-z][A-Za-z0-9]*[\\s/>]").containsMatchIn(raw.trimStart())
}

class TodoWriteTool(
    private val store: TodoStore,
) : Tool {
    override val name = "todo_write"
    override val description =
        "Maintain a task list for this session. Use it to plan multi-step work: mark tasks " +
        "in_progress before starting work or delegating it, then completed as each milestone is verified. " +
        "Update before moving to the next task; do not wait until the final answer. Replaces the whole list. " +
        "The todos parameter must be a JSON array like " +
        "[{\"content\": \"Do X\", \"status\": \"pending\"}], with statuses: pending, in_progress, completed."
    override val parametersSchema = Schema.obj(
        mapOf(
            "todos" to Schema.string(
                "JSON array of {\"content\": string, \"status\": \"pending\"|\"in_progress\"|\"completed\"}.",
            ),
        ),
        required = listOf("todos"),
    )
    override val isReadOnly = false

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val element = args["todos"] ?: throw ToolFailure("Missing required argument: todos")
        val raw = when (element) {
            is kotlinx.serialization.json.JsonArray -> element.toString()
            else -> element.jsonPrimitive.content
        }
        val items = parseTodos(raw)
            ?: throw ToolFailure("Invalid todos JSON: expected an array of {content, status} objects.")
        store.setAll(items)
        val done = items.count { it.status == TodoItem.Status.COMPLETED }
        return ToolResult(true, "Task list updated (${done}/${items.size} completed).")
    }

    /**
     * Tolerant parser: models frequently emit lowercase/alias statuses or
     * slightly malformed JSON (missing commas). Try strict parse first, then
     * a regex extraction that pairs up content/status values in order.
     */
    private fun parseTodos(raw: String): List<TodoItem>? {
        // 1) strict JSON array
        runCatching {
            val elements = json.parseToJsonElement(raw)
            if (elements is kotlinx.serialization.json.JsonArray) {
                return elements.map { el ->
                    val obj = el.jsonObject
                    val content = obj["content"]?.jsonPrimitive?.content
                        ?: throw ToolFailure("Each todo must include content.")
                    val status = obj["status"]
                    if (status != null && (status !is JsonPrimitive || !status.isString)) {
                        throw ToolFailure("Invalid todo status. Use pending, in_progress, or completed.")
                    }
                    TodoItem(content, parseStatus(status?.jsonPrimitive?.content))
                }
            }
        }.onFailure { if (it is ToolFailure) throw it }

        // 2) regex fallback for malformed JSON: pair content/status in order
        val contentRegex = Regex("\"content\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        val statusRegex = Regex("\"status\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        val contents = contentRegex.findAll(raw).map { m ->
            m.groupValues[1]
                .replace("\\\"", "\"").replace("\\n", "\n").replace("\\\\", "\\")
        }.toList()
        if (contents.isEmpty()) return null
        val statuses = statusRegex.findAll(raw).map { parseStatus(it.groupValues[1]) }.toList()
        if (statuses.size != Regex("\"status\"\\s*:").findAll(raw).count()) {
            throw ToolFailure("Invalid todo status. Use pending, in_progress, or completed.")
        }
        return contents.mapIndexed { idx, content ->
            TodoItem(content, statuses.getOrElse(idx) { TodoItem.Status.PENDING })
        }
    }

    private fun parseStatus(raw: String?): TodoItem.Status =
        when (raw?.trim()?.lowercase()) {
            "completed", "complete", "done", "finished" -> TodoItem.Status.COMPLETED
            "in_progress", "inprogress", "in-progress", "active", "started", "working", "doing" ->
                TodoItem.Status.IN_PROGRESS
            null, "pending", "waiting", "todo" -> TodoItem.Status.PENDING
            else -> throw ToolFailure("Invalid todo status '$raw'. Use pending, in_progress, or completed.")
        }
}
