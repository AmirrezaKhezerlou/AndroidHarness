package com.androidharness.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchSourceBudgetTest {
    private fun hit(url: String) = WebSearchResult(title = url, url = url, snippet = "")

    @Test fun capsDistinctSourcesForTheTurn() {
        val budget = SearchSourceBudget(5)
        val first = budget.claim((1..4).map { hit("https://example.com/$it") })
        assertEquals(4, first.size)
        assertEquals(1, budget.remaining)

        val second = budget.claim((4..9).map { hit("https://example.com/$it") })
        assertEquals(1, second.size)
        assertEquals("https://example.com/5", second.single().url)
        assertEquals(0, budget.remaining)
        assertTrue(budget.claim(listOf(hit("https://example.com/10"))).isEmpty())
    }

    @Test fun repeatedSourcesDoNotSpendBudgetTwice() {
        val budget = SearchSourceBudget(3)
        assertEquals(2, budget.claim(listOf(hit("https://a.example"), hit("https://b.example"))).size)
        assertEquals(1, budget.remaining)
        assertEquals(0, budget.claim(listOf(hit("https://a.example/"), hit("https://b.example"))).size)
        assertEquals(1, budget.remaining)
        assertEquals(1, budget.claim(listOf(hit("https://c.example"))).size)
    }

    @Test fun emptyAndMalformedUrlsAreIgnored() {
        val budget = SearchSourceBudget(10)
        assertTrue(budget.claim(listOf(hit(""), hit("   "))).isEmpty())
        assertEquals(0, budget.used)
    }

    @Test fun depthCopiesExposeTheirLimitAndAutoHasNone() {
        val client = okhttp3.OkHttpClient()
        val base = WebSearchTool(client)
        assertTrue(base.forDepth(com.androidharness.app.data.SearchDepth.AUTO) === base)
        val instant = base.forDepth(com.androidharness.app.data.SearchDepth.INSTANT)
        assertTrue(instant.description.contains("at most 5 distinct sources"))
        val max = base.forDepth(com.androidharness.app.data.SearchDepth.MAX_RESEARCH)
        assertTrue(max.description.contains("at most 100 distinct sources"))
    }
}
