package com.example.medicalscanner.ai

import android.content.Context
import android.util.Base64
import com.example.medicalscanner.local.AppSettings
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Calls the Gemini REST API directly from the phone (no PC server needed).
 * Endpoint: generativelanguage.googleapis.com …:generateContent?key=API_KEY
 *
 * This is the on-device replacement for the Node backend's GoogleGenAI SDK usage.
 */
object GeminiClient {

    private const val MODEL = "gemini-2.5-flash"
    private const val JSON_MEDIA = "application/json"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    class GeminiException(message: String) : Exception(message)

    /**
     * Sends a text-only prompt and returns the model's text response.
     * @throws GeminiException on missing key / HTTP / parse failure.
     */
    fun generateText(context: Context, prompt: String): String =
        generate(context, prompt, emptyList())

    /**
     * Sends an image + prompt (multimodal vision) and returns the model's text response.
     */
    fun generateFromImage(context: Context, prompt: String, imageBytes: ByteArray, mimeType: String): String =
        generate(context, prompt, listOf(imageBytes to mimeType))

    /** Sends multiple page images + prompt as a single multimodal request. */
    fun generateFromImages(context: Context, prompt: String, images: List<Pair<ByteArray, String>>): String =
        generate(context, prompt, images)

    // Global pacing so bulk scans don't burst past the free tier's requests-per-minute
    // limit (which turns every call into a 429). Interval is configurable in AppSettings.
    private val paceLock = Object()
    private var nextAllowedAt = 0L

    private fun paceRequest(context: Context) {
        val interval = AppSettings.getAiMinRequestIntervalMs(context)
        if (interval <= 0L) return
        var waitMs: Long
        synchronized(paceLock) {
            val now = System.currentTimeMillis()
            val start = maxOf(now, nextAllowedAt)
            waitMs = start - now
            nextAllowedAt = start + interval
        }
        if (waitMs > 0) try { Thread.sleep(waitMs) } catch (_: InterruptedException) { }
    }

    private fun generate(context: Context, prompt: String, images: List<Pair<ByteArray, String>>): String {
        val apiKey = AppSettings.getGeminiKey(context)
        if (apiKey.isEmpty()) throw GeminiException("Gemini API key is not set. Add it in Settings.")
        paceRequest(context)

        // Build request body: { contents: [ { parts: [ {inline_data}*, {text} ] } ] }
        val parts = JsonArray()
        for ((bytes, mime) in images) {
            val inlineData = JsonObject().apply {
                addProperty("mime_type", mime)
                addProperty("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
            }
            parts.add(JsonObject().apply { add("inline_data", inlineData) })
        }
        parts.add(JsonObject().apply { addProperty("text", prompt) })

        val content = JsonObject().apply { add("parts", parts) }
        val body = JsonObject().apply {
            add("contents", JsonArray().apply { add(content) })
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"
        val request = Request.Builder()
            .url(url)
            .addHeader("x-goog-api-key", apiKey) // works with both AIza… and AQ.… key formats
            .post(body.toString().toRequestBody(JSON_MEDIA.toMediaType()))
            .build()

        // The free tier rate-limits bursts (a single scan makes several calls). Retry 429/503
        // with backoff so transient limits recover instead of the app silently losing data.
        val maxAttempts = 4
        for (attempt in 0 until maxAttempts) {
            client.newCall(request).execute().use { response ->
                val respText = response.body?.string().orEmpty()
                if (response.isSuccessful) return extractText(respText)
                if (response.code == 429 || response.code == 503) {
                    if (attempt < maxAttempts - 1) {
                        val retryAfterMs = response.header("Retry-After")?.toLongOrNull()?.times(1000)
                        val waitMs = retryAfterMs ?: ((attempt + 1) * 3500L)
                        try { Thread.sleep(waitMs) } catch (_: InterruptedException) { }
                    } else {
                        throw GeminiException("Gemini is busy (free-tier limit). Please wait a minute and try again.")
                    }
                } else {
                    throw GeminiException("Gemini request failed (${response.code}): ${respText.take(300)}")
                }
            }
        }
        throw GeminiException("Gemini is temporarily unavailable. Please try again.")
    }

    /** Pulls candidates[0].content.parts[*].text out of a Gemini response. */
    private fun extractText(json: String): String {
        try {
            val root = JsonParser.parseString(json).asJsonObject
            val candidates = root.getAsJsonArray("candidates") ?: return ""
            if (candidates.size() == 0) return ""
            val parts = candidates[0].asJsonObject
                .getAsJsonObject("content")
                ?.getAsJsonArray("parts") ?: return ""
            val sb = StringBuilder()
            for (p in parts) {
                val t = p.asJsonObject.get("text")
                if (t != null && !t.isJsonNull) sb.append(t.asString)
            }
            return sb.toString().trim()
        } catch (e: Exception) {
            throw GeminiException("Could not parse Gemini response: ${e.message}")
        }
    }

    /** Strips ```json fences some models add around JSON output. */
    fun stripJsonFences(text: String): String {
        var t = text.trim()
        if (t.startsWith("```")) {
            t = t.removePrefix("```json").removePrefix("```").trim()
            if (t.endsWith("```")) t = t.removeSuffix("```").trim()
        }
        return t
    }
}
