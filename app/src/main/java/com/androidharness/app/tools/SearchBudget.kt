package com.androidharness.app.tools

import com.androidharness.app.data.SearchDepth

/**
 * Results to request from one search. Research levels pull a full batch and
 * ignore a smaller model count so the selected depth can actually reach its
 * budget; Instant never exceeds its five-source allowance.
 */
internal fun searchBatchCount(depth: SearchDepth?, rawCount: Int?, remaining: Int): Int {
    val maxPerCall = if (depth != null) 20 else 15
    val requested = when (depth) {
        SearchDepth.INSTANT -> (rawCount ?: 5).coerceAtMost(5)
        SearchDepth.DEEP,
        SearchDepth.DEEP_RESEARCH,
        SearchDepth.MAX_RESEARCH -> (rawCount ?: 20).coerceAtLeast(20)
        else -> rawCount ?: 8
    }
    return requested.coerceIn(1, maxPerCall).coerceAtMost(remaining)
}

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
