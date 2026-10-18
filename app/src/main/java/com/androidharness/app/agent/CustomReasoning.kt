package com.androidharness.app.agent

import com.androidharness.app.llm.ProviderConfig
import java.security.MessageDigest
import java.util.Locale
import kotlinx.serialization.Serializable

@Serializable
data class CustomReasoningConfig(val levels: List<String>, val mapping: String = "")

/** Custom vocabulary belongs to one connection and model, never to an unrelated fallback. */
object CustomReasoning {
    const val LEVEL_HELP = "Set the reasoning levels available in chat. Order them from lowest to highest reasoning effort. Do not configure reasoning levels the model does not support."
    fun key(config: ProviderConfig): String {
        val target = ThinkingSpecs.routedConfig(config)
        return MessageDigest.getInstance("SHA-256")
            .digest(listOf(target.id, target.type.name, target.baseUrl.trimEnd('/'), target.model).joinToString("\u0000").toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    fun wire(value: String): String = value.trim().lowercase(Locale.ROOT).replace(Regex("[\\s_-]+"), " ")
        .let { when (it) { "off", "none", "disabled" -> "disabled"; "x high", "x highest", "xhighest", "extra high", "extra highest" -> "xhigh"; else -> it } }

    fun label(value: String): String = when (val id = wire(value)) {
        "disabled" -> "Disabled"
        "xhigh" -> "X-High"
        else -> id.replaceFirstChar { it.titlecase(Locale.ROOT) }
    }

    fun native(value: String): ThinkingLevel? = when (wire(value)) {
        "disabled" -> ThinkingLevel.OFF
        "on", "enabled" -> ThinkingLevel.HIGH
        else -> ThinkingLevel.entries.firstOrNull { it.name.lowercase(Locale.ROOT) == wire(value) }
    }

    fun normalized(config: CustomReasoningConfig): CustomReasoningConfig {
        require(config.levels.isNotEmpty()) { "Add at least one reasoning level." }
        require(config.levels.size <= 16) { "Use at most 16 reasoning levels." }
        require(config.levels.all { it.isNotBlank() && it.length <= 32 && it.none(Char::isISOControl) }) {
            "Each level needs a name of 1–32 characters."
        }
        val levels = config.levels.map(::label)
        require(levels.map(::wire).distinct().size == levels.size) { "Reasoning levels must be unique (ignoring case)." }
        return config.copy(levels = levels, mapping = config.mapping.trim())
    }

    fun resolve(provider: ProviderConfig, requested: ThinkingLevel, maxOutput: Int, entry: com.androidharness.app.llm.ModelEntry?,
        config: CustomReasoningConfig?, selected: String?): ThinkingSpecs.ResolvedThinking {
        val base = ThinkingSpecs.resolve(provider, requested, maxOutput, entry)
        if (config == null || config.levels.isEmpty()) return base
        val choice = config.levels.firstOrNull { wire(it) == selected?.let(::wire) }
            ?: config.levels.firstOrNull { native(it) == base.level }
            ?: config.levels.last()
        val native = native(choice)
        // Explicit CEL supplies reasoning fields itself. Blank mapping keeps the verified native adapter.
        val effective = if (config.mapping.isBlank() && native != null) {
            ThinkingSpecs.resolve(provider, native, maxOutput, entry)
        } else base.copy(spec = base.spec.copy(style = ThinkingSpecs.Style.AUTOMATIC), effort = null, budget = null)
        return effective.copy(customConfig = config, customLevel = wire(choice))
    }
}
