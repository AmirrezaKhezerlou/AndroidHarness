package com.androidharness.app.data.audio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Groq's OpenAI-compatible speech endpoint. The same Groq key used for Whisper
 * works here, so the app never asks for a second credential.
 */
object GroqTtsClient {
    const val MODEL = "playai-tts"

    /** Voices offered in Settings. Groq exposes more; these are the stable English set. */
    val VOICES = listOf(
        "Fritz-PlayAI",
        "Arista-PlayAI",
        "Atlas-PlayAI",
        "Celeste-PlayAI",
        "Cheyenne-PlayAI",
        "Chip-PlayAI",
        "Deedee-PlayAI",
        "Eleanor-PlayAI",
        "Faye-PlayAI",
        "Jennifer-PlayAI",
        "Mason-PlayAI",
        "Mitch-PlayAI",
        "Nia-PlayAI",
        "Quinn-PlayAI",
        "Ruby-PlayAI",
        "Sadie-PlayAI",
        "Shelby-PlayAI",
        "Thunder-PlayAI",
        "Tiffany-PlayAI",
    )

    /** Groq rejects longer input; chunk below that and leave room for punctuation. */
    const val MAX_INPUT_CHARS = 3_500

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Returns a cached MP3 for this exact voice/speed/text, generating it once.
     * Cache names hash the input so replaying a reply costs nothing.
     */
    suspend fun synthesizeCached(
        cacheDir: File,
        text: String,
        apiKey: String,
        voice: String,
        speed: Float,
    ): Result<File> = withContext(Dispatchers.IO) {
        val dir = File(cacheDir, "tts").apply { mkdirs() }
        val key = sha256("$voice|$speed|$text")
        val file = File(dir, "$key.mp3")
        if (file.exists() && file.length() > 0) return@withContext Result.success(file)
        synthesize(text, apiKey, voice, speed).mapCatching { bytes ->
            val temp = File(dir, "$key.part")
            temp.writeBytes(bytes)
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
            trimCache(dir)
            file
        }
    }

    suspend fun synthesize(
        text: String,
        apiKey: String,
        voice: String,
        speed: Float,
    ): Result<ByteArray> {
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isBlank()) {
            return Result.failure(IllegalStateException("Groq API key is missing"))
        }
        if (text.isBlank()) {
            return Result.failure(IllegalArgumentException("Nothing to speak"))
        }
        val payload = buildJsonObject {
            put("model", MODEL)
            put("input", text)
            put("voice", voice)
            put("response_format", "mp3")
            put("speed", speed.coerceIn(0.5f, 2f))
        }.toString()
        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/audio/speech")
            .header("Authorization", "Bearer $trimmedKey")
            .post(payload.toRequestBody(jsonMedia))
            .build()
        return try {
            execute(request).use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    val message = runCatching {
                        json.parseToJsonElement(body).jsonObject["error"]
                            ?.jsonObject?.get("message")?.jsonPrimitive?.content
                    }.getOrNull() ?: "Groq speech failed (HTTP ${response.code})"
                    Result.failure(IOException(message))
                } else {
                    val bytes = response.body?.bytes()
                    if (bytes == null || bytes.isEmpty()) {
                        Result.failure(IOException("Groq returned no audio"))
                    } else {
                        Result.success(bytes)
                    }
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun execute(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = httpClient.newCall(request)
        continuation.invokeOnCancellation { runCatching { call.cancel() } }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response)
                else response.close()
            }
        })
    }

    private fun trimCache(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        if (files.size <= 200) return
        files.sortedBy { it.lastModified() }
            .take(files.size - 200)
            .forEach { runCatching { it.delete() } }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
