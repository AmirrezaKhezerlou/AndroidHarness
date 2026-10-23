package com.androidharness.app.core

import org.junit.Assert.*
import org.junit.Test

class ChatActivityPresentationTest {
    private fun call(id: String, name: String, args: String = "{}") = ToolCallData(id, name, args)
    private fun assistant(vararg calls: ToolCallData) = ChatMessage(Role.ASSISTANT, toolCalls = calls.toList())
    private fun result(id: String, text: String, error: Boolean = false) = ChatMessage(Role.TOOL, text, toolCallId = id, isError = error)

    @Test fun activityInterleavesThoughtsCommentaryAndRepeatedToolsWithoutIncludingTheAnswer() {
        val first = call("first", "web_search")
        val second = call("second", "web_search")
        val third = call("third", "web_fetch")
        val messages = listOf(
            ChatMessage(Role.ASSISTANT, "Let me search", thinking = "Think first", toolCalls = listOf(first), id = "a"), result("first", "Results"),
            ChatMessage(Role.ASSISTANT, "Compare another source", thinking = "Think again", toolCalls = listOf(second), id = "b"), result("second", "More results"),
            ChatMessage(Role.ASSISTANT, "Let me read the detail", thinking = "Choose a page", toolCalls = listOf(third), id = "c"), result("third", "Page"),
            ChatMessage(Role.ASSISTANT, "Final answer", thinking = "Synthesize", id = "d"))
        val entries = ChatActivityPresentation.timeline(messages)
        assertEquals(listOf("Think first", "Let me search", "web_search", "Think again", "Compare another source", "web_search", "Choose a page", "Let me read the detail", "web_fetch", "Synthesize"), entries.map { entry ->
            when (entry) {
                is ChatActivityPresentation.Entry.Thought -> entry.text
                is ChatActivityPresentation.Entry.Commentary -> entry.text
                is ChatActivityPresentation.Entry.Tool -> entry.call.name
            }
        })
        assertEquals(entries.size, entries.map { it.key }.toSet().size)
        assertTrue(entries.filterIsInstance<ChatActivityPresentation.Entry.Tool>().all { it.result != null })
    }

    @Test fun inFlightThoughtAndCommentaryPrecedeTheToolWhileItsDatabaseRowIsPending() {
        val pending = call("pending", "web_search")
        val entries = ChatActivityPresentation.timeline(emptyList(), listOf(pending), "Think before searching", "stream", "Let me search")
        assertTrue(entries[0] is ChatActivityPresentation.Entry.Thought)
        assertTrue(entries[1] is ChatActivityPresentation.Entry.Commentary)
        assertEquals(pending, (entries[2] as ChatActivityPresentation.Entry.Tool).call)
    }

    @Test fun sourcesComeOnlyFromSuccessfulMatchingParentCallsAndDeduplicateReadPages() {
        val search = call("s", "web_search")
        val read = call("r", "web_fetch", """{"url":"https://example.com/report#section"}""")
        val messages = listOf(assistant(search), result("s", "1. Example report\n   https://example.com/report\n   snippet https://unrelated.test\n2. Second report\n   https://second.test/story\n   snippet"),
            assistant(read), result("r", "Article text"),
            assistant(call("failed", "web_search")), result("failed", "1. Failure\nhttps://failure.test", true),
            result("orphan", "1. Orphan\nhttps://orphan.test"),
            assistant(call("binary", "web_fetch", """{"url":"https://binary.test"}""")), result("binary", "[binary content: image/png, 10 bytes]"),
            ChatMessage(Role.ASSISTANT, toolCallId = "child", toolCalls = listOf(call("inner", "web_search"))), result("inner", "1. Nested\nhttps://nested.test"))
        val sources = ChatActivityPresentation.sources(messages)
        assertEquals(listOf("example.com", "second.test"), sources.map { it.host })
        assertEquals("Example report", sources[0].title)
        assertTrue(sources[0].read); assertFalse(sources[1].read)
    }

    @Test fun unsafeOrMalformedUrlsAndUnfinishedCallsNeverBecomeSources() {
        val messages = listOf(assistant(call("s", "web_search")), result("s", "1. Unsafe\n javascript:alert(1)\n2. File\n file:///secret\n3. Credentials\n https://user:password@example.com\n4. Broken\n https://\n5. Valid\n https://valid.test/story\n"),
            assistant(call("pending", "web_fetch", """{"url":"https://pending.test"}""")),
            assistant(call("invalid", "web_fetch", "not json")), result("invalid", "Page"))
        assertEquals(listOf("https://valid.test/story"), ChatActivityPresentation.sources(messages).map { it.url })
    }

    @Test fun phaseKeepsCommittedCallsActiveUntilTheirResultAndDoesNotReviveFinishedCalls() {
        val search = call("s", "web_search"); val read = call("r", "web_fetch")
        val first = listOf(assistant(search))
        assertEquals(search, ChatActivityPresentation.activeCall(first, emptyList()))
        val complete = first + result("s", "No results")
        assertNull(ChatActivityPresentation.activeCall(complete, listOf(search)))
        assertEquals(read, ChatActivityPresentation.activeCall(complete, listOf(read)))
        val failed = complete + assistant(read) + result("r", "Failed", true)
        assertNull(ChatActivityPresentation.activeCall(failed, emptyList()))
        assertEquals(listOf(false, true), ChatActivityPresentation.steps(failed).map { it.failed })
        assertTrue(ChatActivityPresentation.steps(failed).all { it.finished })
    }

    @Test fun memorySavedFeedbackRequiresASuccessfulRememberResult() {
        val remember = call("m", "remember")
        assertTrue(ChatActivityPresentation.savedMemory(listOf(assistant(remember), result("m", "Saved to global memory."))))
        assertFalse(ChatActivityPresentation.savedMemory(listOf(assistant(remember), result("m", "Memory is off", true))))
        assertFalse(ChatActivityPresentation.savedMemory(listOf(assistant(call("s", "web_search")), result("s", "ok"))))
        assertFalse(ChatActivityPresentation.savedMemory(listOf(result("m", "orphan result"))))
    }

    @Test fun memoryReceiptsOnlyExposeIdsFromSuccessfulMatchingRememberCalls() {
        val messages = listOf(assistant(call("m", "remember")), result("m", "Saved.\n[memory_id:abc-123]"),
            assistant(call("f", "remember")), result("f", "[memory_id:failed]", true),
            result("orphan", "[memory_id:orphan]"), assistant(call("s", "web_search")), result("s", "[memory_id:search]"))
        assertEquals(setOf("abc-123"), ChatActivityPresentation.savedMemoryIds(messages))
        assertTrue(ChatActivityPresentation.savedMemoryIds(listOf(assistant(call("old", "remember")), result("old", "Saved to global memory."))).isEmpty())
    }
}
