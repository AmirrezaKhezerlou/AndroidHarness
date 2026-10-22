package com.androidharness.app.core

import org.junit.Assert.*
import org.junit.Test

class ChatExperienceTest {
    @Test fun ordinaryChatAndCodeQuestionsDoNotOfferHandoff() {
        for (text in listOf("Help me brainstorm an idea", "Explain this Kotlin function", "What does Continue in Code mean?", "Don't continue in Code")) {
            assertFalse(text, ChatExperience.offersCode(listOf(ChatMessage(Role.USER, text))))
        }
    }

    @Test fun explicitRequestsAndSuccessfulAssistantOffersAreConditionalOnLatestTurn() {
        assertTrue(ChatExperience.offersCode(listOf(ChatMessage(Role.USER, "Please continue in Code"))))
        assertTrue(ChatExperience.offersCode(listOf(ChatMessage(Role.USER, "Can we switch to Code?"))))
        val turn = listOf(ChatMessage(Role.USER, "Apply this to my project"),
            ChatMessage(Role.ASSISTANT, toolCalls = listOf(ToolCallData("offer", "offer_code_handoff", "{}"))),
            ChatMessage(Role.TOOL, "Offered", toolCallId = "offer", toolName = "offer_code_handoff"))
        assertTrue(ChatExperience.offersCode(turn))
        assertFalse(ChatExperience.offersCode(turn.dropLast(1)))
        assertFalse(ChatExperience.offersCode(turn.dropLast(1) + turn.last().copy(isError = true)))
        assertFalse(ChatExperience.offersCode(turn + ChatMessage(Role.USER, "Just explain the idea for now")))
    }

    @Test fun titlesUseTheUsersWordsWithoutAttachmentBodies() {
        assertEquals("Help me brainstorm an idea", ChatExperience.title("  Help me\nbrainstorm an idea  "))
        assertEquals("Review this", ChatExperience.title("Review this\n\n[Attached file: notes.txt (text/plain, 12 B)]\n```\nprivate text\n```"))
        assertEquals(72, ChatExperience.title("x".repeat(100)).length)
    }
}
