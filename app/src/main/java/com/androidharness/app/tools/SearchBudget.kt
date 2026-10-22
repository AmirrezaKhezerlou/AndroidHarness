package com.androidharness.app.tools

/**
 * Per-turn source allowance for Chat research. Counts distinct result URLs, so
 * repeated hits from overlapping queries do not consume the budget twice.
 */
class SearchSourceBudget(val maxSources: Int) {
    private val seen = LinkedHashSet<String>()
    private val lock = Any()

    val used: Int get() = synchronized(lock) { seen.size }
    val remaining: Int get() = (maxSources - used).coerceAtLeast(0)

    /** Results that still fit the budget, with their URLs recorded as used. */
    fun claim(results: List<WebSearchResult>): List<WebSearchResult> = synchronized(lock) {
        if (seen.size >= maxSources) return emptyList()
        val room = maxSources - seen.size
        val out = ArrayList<WebSearchResult>(minOf(room, results.size))
        for (result in results) {
            if (out.size >= room) break
            val key = result.url.trim().trimEnd('/').lowercase()
            if (key.isEmpty() || !seen.add(key)) continue
            out += result
        }
        out
    }
}
