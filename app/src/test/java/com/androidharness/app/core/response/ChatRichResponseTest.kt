package com.androidharness.app.core.response

import org.junit.Assert.*
import org.junit.Test

class ChatRichResponseTest {
    private val card = """{"type":"callout","tone":"info","title":"Today's lead story","text":"A report with {braces} and an escaped \"quote\"."}"""

    @Test fun screenshotStyleBareComponentsRenderWithSurroundingProseAndCitations() {
        val reply = "Here is today's news.\n$card\n[Source](https://example.com/report)"
        val parts = RichResponse.parse(ChatRichResponse.normalize(reply))
        assertTrue(parts[0] is RichResponse.Part.Markdown)
        assertEquals("Today's lead story", (parts[1] as RichResponse.Part.Content).node.let { it as RichResponse.Node.Callout }.title)
        assertTrue((parts[2] as RichResponse.Part.Markdown).text.contains("[Source]"))
        assertTrue("Code parsing remains strict", RichResponse.parse(reply).all { it is RichResponse.Part.Markdown })
    }

    @Test fun everyStreamedPrefixWithholdsComponentMetadataUntilItCanRender() {
        for (length in 1..card.length) {
            val parts = RichResponse.parse(ChatRichResponse.normalize("News\n" + card.take(length), true), true)
            assertFalse("prefix $length", parts.filterIsInstance<RichResponse.Part.Markdown>().any { it.text.contains('{') || it.text.contains("\"type\"") })
            assertTrue(parts.any { it is RichResponse.Part.Content || it is RichResponse.Part.Pending })
        }
    }

    @Test fun explicitCodeFencesAndOrdinaryJsonStayLiteralWhilePrettyComponentsRecover() {
        for (fence in listOf("```json", "```kotlin", "~~~json")) {
            val text = "$fence\n$card\n${fence.take(3)}\nordinary prose"
            assertEquals(text, ChatRichResponse.normalize(text))
        }
        val plain = "Data\n{\"type\":\"person\",\"name\":\"Ada\"}\nEnd"
        assertEquals(plain, ChatRichResponse.normalize(plain))
        val pretty = "{\n  \"type\": \"callout\",\n  \"title\": \"Summary\", \"text\": \"Details\"\n}"
        assertTrue(RichResponse.parse(ChatRichResponse.normalize(pretty)).single() is RichResponse.Part.Content)
        val alreadyFenced = "```harness-ui\n$card\n```"
        assertEquals(alreadyFenced, ChatRichResponse.normalize(alreadyFenced))
    }

    @Test fun brokenComponentsNeverLeakMetadataAndDoNotSwallowTrailingTextAfterCompleteObjects() {
        val broken = """{"type":"callout","title":12}"""
        val parts = RichResponse.parse(ChatRichResponse.normalize("$broken\nUseful trailing prose"))
        assertEquals(RichResponse.Part.Unavailable, parts[0])
        assertTrue((parts[1] as RichResponse.Part.Markdown).text.contains("Useful trailing prose"))
        assertEquals(RichResponse.Part.Unavailable, RichResponse.parse(ChatRichResponse.normalize(card.dropLast(1))).single())
    }
}
