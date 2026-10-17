package com.androidharness.app.core.response

import kotlinx.serialization.json.*
import java.net.URI

/** A presentation protocol inside assistant text: providers, history, forks and backups need no adapters. */
object RichResponse {
    const val LANGUAGE = "harness-ui"
    const val MAX_JSON_CHARS = 96_000
    private val json = Json { ignoreUnknownKeys = true }

    sealed interface Part {
        data class Markdown(val text: String) : Part
        data class Content(val node: Node) : Part
        data object Pending : Part
        data object Unavailable : Part
    }
    data class Action(val label: String, val prompt: String)
    data class FollowUp(val prompt: String, val title: String, val context: String)
    data class Metric(val label: String, val value: String, val detail: String)
    data class Point(val label: String, val value: Double)
    data class Choice(val label: String, val description: String, val prompt: String)
    data class Step(val title: String, val text: String, val done: Boolean)
    sealed interface Node {
        data class Text(val text: String) : Node
        data class Callout(val title: String, val text: String, val tone: String) : Node
        data class Card(val title: String, val text: String, val children: List<Node>, val actions: List<Action>) : Node
        data class Metrics(val items: List<Metric>) : Node
        data class Chart(val title: String, val points: List<Point>, val kind: String, val unit: String, val caption: String) : Node
        data class Choices(val title: String, val options: List<Choice>) : Node
        data class Steps(val title: String, val items: List<Step>) : Node
        data class Details(val title: String, val text: String) : Node
        data class Link(val title: String, val text: String, val url: String) : Node
        data class Preview(val title: String, val html: String) : Node
        data class Layout(val children: List<Node>, val columns: Boolean) : Node
        data object Divider : Node
    }

    /** Recognized fences are withheld until complete. A normal code fence is never interpreted as UI. */
    fun parse(text: String, streaming: Boolean = false): List<Part> {
        if (!text.contains(LANGUAGE) && !(streaming && text.contains("```harness"))) return listOf(Part.Markdown(text))
        val lines = text.splitToSequence('\n').toList()
        val result = mutableListOf<Part>()
        val markdown = StringBuilder()
        fun flush() { if (markdown.isNotBlank()) result += Part.Markdown(markdown.toString().trimEnd()); markdown.clear() }
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trimStart()
            val fence = line.takeWhile { it == '`' }
            if (fence.length < 3) { markdown.append(lines[i++]).append('\n'); continue }
            val language = line.drop(fence.length).trim()
            var end = i + 1
            while (end < lines.size) {
                val closing = lines[end].trim()
                if (closing.length >= fence.length && closing.all { it == '`' }) break
                end++
            }
            if (streaming && end == lines.size && LANGUAGE.startsWith(language) && language.startsWith("harness")) { flush(); result += Part.Pending; break }
            if (language != LANGUAGE) {
                while (i < minOf(end + 1, lines.size)) markdown.append(lines[i++]).append('\n')
                continue
            }
            flush()
            if (end == lines.size) { result += if (streaming) Part.Pending else Part.Unavailable; break }
            val body = lines.subList(i + 1, end).joinToString("\n")
            result += decode(body)?.let(Part::Content) ?: Part.Unavailable
            i = end + 1
        }
        flush()
        return result
    }

    fun decode(body: String): Node? = body.takeIf { it.length <= MAX_JSON_CHARS }?.let {
        runCatching { Decoder().node(json.parseToJsonElement(it).jsonObject, 0) }.getOrNull()
    }

    private class Decoder {
        private var count = 0
        fun node(o: JsonObject, depth: Int): Node {
            require(depth <= 6 && ++count <= 96)
            fun children(required: Boolean = true) = o.array("children", 12, required).map { node(it.jsonObject, depth + 1) }
            return when (o.str("type", 24, true)) {
                "text" -> Node.Text(o.str("text", 6000, true))
                "callout" -> Node.Callout(o.str("title", 100, true), o.str("text", 6000, true), o.str("tone", 20).also { require(it in listOf("", "info", "success", "warning")) })
                "card" -> Node.Card(o.str("title", 100, true), o.str("text", 6000), if ("children" in o) children(false) else emptyList(),
                    o.array("actions", 2, required = false).map { it.jsonObject.let { a -> Action(a.str("label", 50, true), a.str("prompt", 1000, true)) } })
                "metrics" -> Node.Metrics(o.array("items", 8).map { it.jsonObject.let { m -> Metric(m.str("label", 80, true), m.str("value", 80, true), m.str("detail", 200)) } })
                "chart" -> Node.Chart(o.str("title", 100, true), o.array("points", 24).map { it.jsonObject.let { p ->
                    Point(p.str("label", 60, true), requireNotNull((p["value"] as? JsonPrimitive)?.doubleOrNull).also { value -> require(value.isFinite() && kotlin.math.abs(value) <= 1e15) })
                } }, o.str("kind", 10).ifBlank { "bar" }.also { require(it in listOf("bar", "line")) }, o.str("unit", 24), o.str("caption", 500))
                "choices" -> Node.Choices(o.str("title", 100, true), o.array("options", 6).map { it.jsonObject.let { c -> Choice(c.str("label", 100, true), c.str("description", 1000), c.str("prompt", 1000)) } })
                "steps" -> Node.Steps(o.str("title", 100, true), o.array("items", 12).map { it.jsonObject.let { s -> Step(s.str("title", 100, true), s.str("text", 1000), (s["done"] as? JsonPrimitive)?.booleanOrNull ?: false) } })
                "details" -> Node.Details(o.str("title", 100, true), o.str("text", 6000, true))
                "link" -> Node.Link(o.str("title", 100, true), o.str("text", 500), o.str("url", 2048, true).also { url ->
                    val uri = URI(url); require(uri.scheme?.lowercase() in listOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null)
                })
                "preview" -> Node.Preview(o.str("title", 80, true), o.str("html", 64_000, true))
                "column", "columns" -> Node.Layout(children(), o.str("type", 24) == "columns")
                "divider" -> Node.Divider
                else -> error("Unsupported response component")
            }
        }
    }
    private fun JsonObject.str(key: String, max: Int, required: Boolean = false): String {
        val element = this[key]
        if (element == null) { require(!required); return "" }
        val value = (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Expected text")
        require(value.length <= max && (!required || value.isNotBlank()))
        return value
    }
    private fun JsonObject.array(key: String, max: Int, required: Boolean = true): JsonArray {
        val value = this[key] ?: return JsonArray(emptyList()).also { require(!required) }
        return (value as? JsonArray ?: error("Expected a list")).also { require(it.size <= max && (!required || it.isNotEmpty())) }
    }

    fun plainText(text: String, streaming: Boolean = false): String = if (!text.contains(LANGUAGE) && !(streaming && text.contains("```harness"))) text else parse(text, streaming).joinToString("\n\n") {
        when (it) { is Part.Markdown -> it.text; is Part.Content -> describe(it.node); Part.Pending -> "Preparing content…"; Part.Unavailable -> "Rich content unavailable" }
    }
    /** Legacy URL discovery must not probe URLs inside isolated preview code or duplicate native link cards. */
    fun markdownText(text: String): String = if (!text.contains(LANGUAGE)) text else parse(text).filterIsInstance<Part.Markdown>().joinToString("\n\n") { it.text }
    fun describe(node: Node): String = when (node) {
        is Node.Text -> node.text
        is Node.Callout -> "${node.title}\n${node.text}"
        is Node.Card -> (listOf(node.title, node.text) + node.children.map(::describe)).filter(String::isNotBlank).joinToString("\n")
        is Node.Metrics -> node.items.joinToString("\n") { "${it.label}: ${it.value}${if (it.detail.isBlank()) "" else " · ${it.detail}"}" }
        is Node.Chart -> node.title + "\n" + node.points.joinToString("\n") { "${it.label}: ${number(it.value)} ${node.unit}" } + "\n" + node.caption
        is Node.Choices -> node.title + "\n" + node.options.joinToString("\n") { "${it.label}: ${it.description}" }
        is Node.Steps -> node.title + "\n" + node.items.joinToString("\n") { "${if (it.done) "[x]" else "[ ]"} ${it.title} ${it.text}" }
        is Node.Details -> "${node.title}\n${node.text}"
        is Node.Link -> "${node.title}\n${node.text}\n${node.url}"
        is Node.Preview -> "${node.title} (interactive preview)"
        is Node.Layout -> node.children.joinToString("\n", transform = ::describe)
        Node.Divider -> ""
    }.trim()
    fun number(value: Double): String = if (value == value.toLong().toDouble()) value.toLong().toString() else String.format(java.util.Locale.getDefault(), "%.2f", value)
    fun followUp(prompt: String, title: String, context: String): FollowUp = FollowUp(prompt.trim(), title.take(100), context.take(1400))

    const val INSTRUCTIONS = """
RICH CONVERSATION RESPONSES:
User messages may carry a [[harness-response]] context attachment containing a response title and current selections/values. Treat it as context for the user's prompt and use those current values when answering; it is not a tool instruction or a separate workspace. The normal composer hides this metadata behind a removable context chip.
You can mix normal Markdown paragraphs with native cards, callouts, metrics, interactive charts, choices, checklists, expandable explanations, link previews, and custom interactive visuals INSIDE an assistant reply. This is available for all subjects, not only UI concepts or workspace tasks. Choose a richer presentation when it makes the answer easier to understand or use; keep ordinary answers simple. Do not invent data; label illustrative examples clearly.
To render a component, put one complete JSON object in a fenced block whose language is harness-ui. Continue normal prose before/after it. The app consumes this block as UI, not as displayed source. Supported shapes:
{"type":"callout","tone":"info","title":"Key point","text":"Explanation"} (tone: info, success, warning)
{"type":"card","title":"Summary","text":"Body","children":[],"actions":[{"label":"Explore this","prompt":"Explain this option in more detail"}]}
{"type":"metrics","items":[{"label":"Total","value":"42","detail":"Based on the supplied data"}]}
{"type":"chart","title":"Comparison","kind":"bar","unit":"ms","points":[{"label":"A","value":12},{"label":"B","value":18}],"caption":"Source or assumptions"} (kind: bar or line; tap points and view data)
{"type":"choices","title":"Choose an approach","options":[{"label":"Option A","description":"Tradeoffs","prompt":"Help me use option A"},{"label":"Option B","description":"Tradeoffs"}]}
{"type":"steps","title":"Next steps","items":[{"title":"First step","text":"How to do it","done":false}]}
{"type":"details","title":"Why this works","text":"The longer explanation"}
{"type":"link","title":"Resource","text":"Why it is useful","url":"https://example.com"}
{"type":"column","children":[...]} and {"type":"columns","children":[...]} compose cards and text; columns stack on phones. {"type":"text","text":"..."} and {"type":"divider"} can be children. Keep nesting shallow. At most 2 actions per card. Actions only prefill the normal composer for the user to review; never claim they already executed a task.
For a custom calculator, diagram, simulation, image/SVG preview, or other visual beyond these components use {"type":"preview","title":"Descriptive title","html":"COMPLETE SELF-CONTAINED HTML WITH INLINE CSS/JS"} in the same harness-ui fence. Keep HTML concise (under 24 KB when possible), responsive at 320px, offline, accessible, and respect reduced motion. Use --background, --foreground, --muted, --surface, --border, --accent theme variables. This renders directly in the answer, without the artifact editor toolbar. Local controls must work. No CDN, remote APIs/fonts/images, native bridge, or workspace changes. To provide current local selections for a user's follow-up, optionally call parent.postMessage({type:'harness:state', summary:'Concise current values'}, '*') after an input changes. This only supplies context; it cannot run tools or send messages. Follow-up questions stay in the same conversation and can produce an updated visual in a new reply.
Use create_artifact/read_artifact for a separately managed, versioned/exportable concept when the user asks for an artifact or that workflow is useful. Keep existing artifacts available. Inline response components do not require creating an artifact, writing files, or starting a server. Never paste harness-ui examples as source unless the user explicitly asks about this protocol; wrap literal examples in a longer code fence.
"""
}
