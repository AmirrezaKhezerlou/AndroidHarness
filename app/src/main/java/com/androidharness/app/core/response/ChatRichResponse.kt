package com.androidharness.app.core.response

/** Chat-only recovery for providers that omit the native-component fence. Code keeps strict parsing. */
internal object ChatRichResponse {
    private val header = Regex("""^\{\s*"type"\s*:\s*"(callout|card|metrics|chart|choices|steps|details|link|preview|table|tabs|gallery|calculator|evidence|diff|writing|column|columns|text|divider)"""")
    private val componentTypes = setOf("callout", "card", "metrics", "chart", "choices", "steps", "details", "link", "preview", "table", "tabs", "gallery", "calculator", "evidence", "diff", "writing", "column", "columns", "text", "divider")
    private fun partialHeader(candidate: String): Boolean {
        val compact = candidate.filterNot(Char::isWhitespace)
        val prefix = "{\"type\":\""
        return prefix.startsWith(compact) || (compact.startsWith(prefix) && componentTypes.any { it.startsWith(compact.removePrefix(prefix)) })
    }

    fun normalize(text: String, streaming: Boolean = false): String {
        if ('{' !in text) return text
        val output = StringBuilder()
        var position = 0
        var fence: String? = null
        while (position < text.length) {
            val lineEnd = text.indexOf('\n', position).let { if (it < 0) text.length else it }
            val line = text.substring(position, lineEnd)
            val trimmed = line.trimStart()
            val marker = trimmed.takeWhile { it == '`' || it == '~' }
            if (marker.length >= 3) {
                if (fence == null) fence = marker
                else if (trimmed.trimEnd().all { it == fence.first() } && marker.length >= fence.length) fence = null
            }
            val start = position + line.length - trimmed.length
            val candidateHead = text.substring(start, minOf(text.length, start + 160))
            if (fence == null && trimmed.startsWith('{') &&
                (header.containsMatchIn(candidateHead) || (streaming && partialHeader(candidateHead)))) {
                val end = objectEnd(text, start)
                if (end == null) {
                    // Withhold unfinished component data during streaming; never flash its JSON.
                    output.append("```harness-ui\n")
                    if (streaming) output.append(text.substring(start).take(RichResponse.MAX_JSON_CHARS))
                    else output.append("{}\n```\n")
                    break
                }
                val body = text.substring(start, end)
                if (RichResponse.decode(body) != null) output.append("```harness-ui\n").append(body).append("\n```\n")
                else output.append("```harness-ui\n{}\n```\n")
                position = if (end < text.length && text[end] == '\n') end + 1 else end
                continue
            }
            output.append(line)
            if (lineEnd < text.length) output.append('\n')
            position = lineEnd + 1
        }
        return output.toString()
    }

    private fun objectEnd(text: String, start: Int): Int? {
        var depth = 0
        var quoted = false
        var escaped = false
        for (index in start until minOf(text.length, start + RichResponse.MAX_JSON_CHARS + 1)) {
            val c = text[index]
            if (quoted) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') quoted = false
            } else when (c) {
                '"' -> quoted = true
                '{', '[' -> depth++
                '}', ']' -> if (--depth == 0) return index + 1
            }
        }
        return null
    }
}
