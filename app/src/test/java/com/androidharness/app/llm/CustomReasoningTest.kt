package com.androidharness.app.llm

import com.androidharness.app.agent.*
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.data.ChatModelSelection
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class CustomReasoningTest {
    private val config = ProviderConfig("deepseek", "DeepSeek", ProviderType.OPENAI_COMPAT, "https://api.deepseek.com/v1", "deepseek-v4-flash")
    private val user = listOf(ChatMessage(Role.USER, "Check"))
    private val client = OkHttpClient()
    private val json = ProviderFactory.json
    private fun options(c: ProviderConfig = config, custom: CustomReasoningConfig, level: String = "max") = RequestOptions(
        thinking = ThinkingLevel.LOW, customReasoning = mapOf(CustomReasoning.key(c) to custom),
        customThinkingLevels = mapOf(CustomReasoning.key(c) to level))
    private fun body(c: ProviderConfig = config, custom: CustomReasoningConfig, level: String = "max"): JsonObject =
        OpenAiCompatProvider(client, json).buildRequestBody(c, "sys", user, emptyList(), options(c, custom, level))

    @Test fun `names normalize case and x highest aliases without losing user order`() {
        val normalized = CustomReasoning.normalized(CustomReasoningConfig(listOf("disabled", "LOW", "hIGH", "max", "X highest")))
        assertEquals(listOf("Disabled", "Low", "High", "Max", "X-High"), normalized.levels)
        assertEquals("max", CustomReasoning.wire("  MaX "))
        assertEquals("xhigh", CustomReasoning.wire("X Highest"))
        assertThrows(IllegalArgumentException::class.java) { CustomReasoning.normalized(CustomReasoningConfig(listOf("max", "MAX"))) }
    }

    @Test fun `optional blank mapping uses the selected native effort and off toggle`() {
        val custom = CustomReasoningConfig(listOf("disabled", "low", "high", "max"))
        val selected = options(custom = custom).resolveThinking(config)
        assertEquals("Max", selected.label)
        assertEquals("max", body(custom = custom)["reasoning_effort"]!!.jsonPrimitive.content)
        assertEquals("disabled", body(custom = custom, level = "disabled")["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val budget = config.copy(type = ProviderType.ANTHROPIC, baseUrl = "https://api.anthropic.com", model = "claude-sonnet-4-5")
        assertEquals(ThinkingSpecs.resolve(budget, ThinkingLevel.MAX).budget,
            options(budget, CustomReasoningConfig(listOf("Low", "Max"))).resolveThinking(budget).budget)
    }

    @Test fun `real CEL supports conditional nested objects numeric lookup and null`() {
        val expression = """reasoningLevel == "disabled" ? {"thinking": {"type": "disabled"}, "reasoning_effort": null} : {"thinking": {"type": "enabled", "budget_tokens": {"low": 1024, "max": 32768}[reasoningLevel]}}"""
        val custom = ReasoningMapping.validate(CustomReasoningConfig(listOf("disabled", "low", "max"), expression), config, ThinkingSpecs.forProvider(config))
        assertEquals(32768, body(custom = custom)["thinking"]!!.jsonObject["budget_tokens"]!!.jsonPrimitive.int)
        assertFalse(body(custom = custom, level = "disabled").containsKey("reasoning_effort"))
        assertEquals("max", ReasoningMapping.evaluate("""{"effort": reasoningLevel}""", "MAX")["effort"]!!.jsonPrimitive.content)
    }

    @Test fun `invalid expressions or nonobject results are rejected for every configured branch`() {
        for (expression in listOf("unknownField", "1 +", "42", "{'budget': 0.0 / 0.0}", "reasoningLevel == 'low' ? {'x': 1} : {'x': 1 / 0}")) {
            assertThrows(IllegalArgumentException::class.java) {
                ReasoningMapping.validate(CustomReasoningConfig(listOf("Low", "Max"), expression), config, ThinkingSpecs.forProvider(config))
            }
        }
        assertThrows(IllegalArgumentException::class.java) { body(custom = CustomReasoningConfig(listOf("Max"), "42")) }
    }

    @Test fun `recursive merge preserves output config and explicit null removes keys`() {
        val base = json.parseToJsonElement("""{"generationConfig":{"maxOutputTokens":9000,"thinkingConfig":{"old":true}},"stream":true}""").jsonObject
        val patch = json.parseToJsonElement("""{"generationConfig":{"thinkingConfig":{"old":null,"thinkingLevel":"high"}}}""").jsonObject
        val merged = ReasoningMapping.merge(base, patch)
        assertEquals(9000, merged["generationConfig"]!!.jsonObject["maxOutputTokens"]!!.jsonPrimitive.int)
        assertFalse(merged["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject.containsKey("old"))
        assertEquals(true, merged["stream"]!!.jsonPrimitive.boolean)
    }

    @Test fun `custom mappings reach all four remote protocols and preserve messages and tools`() {
        val custom = CustomReasoningConfig(listOf("Low", "X highest"), """{"custom_reasoning": {"effort": reasoningLevel}}""")
        val fixtures = listOf(config, config.copy(type = ProviderType.OPENAI_RESPONSES),
            config.copy(type = ProviderType.ANTHROPIC), config.copy(type = ProviderType.GEMINI))
        for (c in fixtures) {
            val opts = options(c, custom, "X HIGHEST")
            val body = when(c.type) {
                ProviderType.OPENAI_COMPAT -> OpenAiCompatProvider(client, json).buildRequestBody(c, "sys", user, emptyList(), opts)
                ProviderType.OPENAI_RESPONSES -> OpenAiResponsesProvider(client, json).buildRequestBody(c, "sys", user, emptyList(), opts)
                ProviderType.ANTHROPIC -> AnthropicProvider(client, json).buildRequestBody(c, "sys", user, emptyList(), opts)
                ProviderType.GEMINI -> GeminiProvider(client, json).buildRequestBody(c, "sys", user, emptyList(), opts)
            }
            assertEquals("xhigh", body["custom_reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
            assertTrue(body.containsKey(when(c.type) { ProviderType.OPENAI_RESPONSES -> "input"; ProviderType.GEMINI -> "contents"; else -> "messages" }))
        }
    }

    @Test fun `custom configuration cannot leak across provider model protocol or endpoint changes`() {
        val opts = options(custom = CustomReasoningConfig(listOf("max"), """{"reasoning_effort":"custom"}"""))
        for (other in listOf(config.copy(id = "other"), config.copy(model = "other"), config.copy(baseUrl = "https://other.example/v1"), config.copy(type = ProviderType.OPENAI_RESPONSES))) {
            assertNull(opts.resolveThinking(other).customConfig)
            assertNull(opts.applyReasoningMapping(other, JsonObject(emptyMap()))["reasoning_effort"])
        }
        assertEquals(CustomReasoning.key(config), CustomReasoning.key(config.copy(baseUrl = config.baseUrl + "/")))
        assertFalse(CustomReasoning.key(config).contains("deepseek"))
    }

    @Test fun `unknown or unsupported native labels require explicit mapping`() {
        for (name in listOf("Turbo", "Ultra")) assertThrows(IllegalArgumentException::class.java) {
            ReasoningMapping.validate(CustomReasoningConfig(listOf(name)), config, ThinkingSpecs.forProvider(config))
        }
        ReasoningMapping.validate(CustomReasoningConfig(listOf("Turbo"), """{"reasoning_effort": "max"}"""), config, ThinkingSpecs.forProvider(config))
    }

    @Test fun `selections round trip independently for each chat and older JSON remains readable`() {
        val a = ChatModelSelection(customThinkingLevels = mapOf(CustomReasoning.key(config) to "max"))
        assertEquals(a, ChatModelSelection.decode(ChatModelSelection.encode(a)))
        assertTrue(ChatModelSelection.decode("{}")!!.customThinkingLevels.isEmpty())
        assertTrue(ChatModelSelection().customThinkingLevels.isEmpty())
    }

    @Test fun `model managed thinking says On only when support is known`() {
        val c = config.copy(baseUrl = "https://custom.example/v1", model = "custom")
        assertEquals("On", RequestOptions().resolveThinking(c, ModelEntry("custom", reasoning = true)).label)
        assertEquals("Thinking", RequestOptions().resolveThinking(c).label)
        assertEquals("Off", RequestOptions().resolveThinking(c, ModelEntry("custom", reasoning = false)).label)
    }
}
