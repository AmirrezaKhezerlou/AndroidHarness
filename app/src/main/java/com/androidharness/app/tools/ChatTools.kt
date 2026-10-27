package com.androidharness.app.tools

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import kotlinx.serialization.json.*

class ViewChatImageTool(private val images: com.androidharness.app.data.ImageStore) : Tool {
    override val name = "view_chat_image"
    override val description = "Visually inspect a public image URL from a fetched page or the conversation. Use before making claims about what a picture shows."
    override val parametersSchema = Schema.obj(mapOf("url" to Schema.string("The direct public image URL.")), required = listOf("url"))
    override val isReadOnly = true
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val url = args["url"]?.jsonPrimitive?.contentOrNull ?: throw ToolFailure("Provide an image URL.")
        val image = images.remoteImage(url) ?: return ToolResult(false, "Could not load this image for visual inspection. Do not claim to have seen it.")
        return ToolResult(true, "Image for visual inspection: $url", image = images.saveResolved(image))
    }
}

/** Reads conversation input, never a workspace or a user-supplied filesystem path. */
class ReadChatTextTool(private val load: suspend (String) -> List<ChatMessage>) : Tool {
    override val name = "read_chat_text"
    override val description = "Read user-supplied text and inline text-file attachments in this chat. Omit message_id for the latest user message. No filesystem access."
    override val parametersSchema = Schema.obj(mapOf(
        "message_id" to Schema.string("Optional user message id from this chat."),
        "offset" to Schema.integer("1-based character offset, default 1."),
        "limit" to Schema.integer("Characters to read, 1..12000, default 6000."),
    ))
    override val isReadOnly = true
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val sid = ctx.sessionId ?: throw ToolFailure("Open a chat first.")
        val id = args["message_id"]?.jsonPrimitive?.content
        val message = load(sid).lastOrNull { it.role == Role.USER && (id == null || it.id == id) }
            ?: throw ToolFailure("No matching user message in this chat.")
        val offset = args["offset"]?.jsonPrimitive?.intOrNull ?: 1
        val limit = args["limit"]?.jsonPrimitive?.intOrNull ?: 6000
        if (offset < 1 || limit !in 1..12000) throw ToolFailure("Use a positive offset and a limit of 1..12000.")
        val text = message.text
        val start = (offset - 1).coerceAtMost(text.length)
        return ToolResult(true, "User-supplied text (${text.length} characters), message_id=${message.id.orEmpty()}:\n" + text.substring(start, minOf(start + limit, text.length)))
    }
}

class OfferCodeHandoffTool : Tool {
    override val name = "offer_code_handoff"
    override val description = "Offer a Continue in Code card only when the user requests Code or project editing/execution is needed. Does not navigate, run commands, or change files."
    override val parametersSchema = Schema.obj(mapOf(
        "title" to Schema.string("Concise action title for the card in the user's language, such as Build your reading tracker. Up to 80 characters."),
        "reason" to Schema.string("One short sentence describing the project editing or execution needed, in the user's language. Up to 400 characters."),
    ), required = listOf("reason"))
    override val isReadOnly = true
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val reason = args["reason"]?.jsonPrimitive?.content?.trim().orEmpty()
        val title = args["title"]?.jsonPrimitive?.content?.trim()
        if (title != null && (title.isBlank() || title.length > 80)) throw ToolFailure("Provide an action title of 1 to 80 characters, or omit it.")
        if (reason.isBlank() || reason.length > 400) throw ToolFailure("Provide a short reason, up to 400 characters.")
        return ToolResult(true, "Continue in Code is offered. The user must choose it before a Code conversation is created.")
    }
}
