package com.example.medicalscanner.ai

import android.content.Context
import android.util.Base64
import com.example.medicalscanner.local.AppSettings
import com.example.medicalscanner.util.LanguageUtil
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * On-device text-to-speech: calls Sarvam or Gemini TTS directly from the phone (no server),
 * returning base64 WAV clips for AudioPlayer. The "Phone" engine is handled by the UI with
 * Android's TextToSpeech and never reaches here.
 */
object SpeechEngine {
    private const val JSON = "application/json"
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    fun synthesize(context: Context, text: String, language: String, engine: String): List<String> = try {
        if (engine.equals("gemini", true)) geminiTts(context, text) else sarvamTts(context, text, language)
    } catch (e: Exception) {
        e.printStackTrace(); emptyList()
    }

    private fun chunk(text: String, max: Int): List<String> {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        if (clean.length <= max) return if (clean.isEmpty()) emptyList() else listOf(clean)
        val out = ArrayList<String>()
        var cur = StringBuilder()
        for (s in Regex("[^.!?।]+[.!?।]?").findAll(clean).map { it.value }) {
            if (cur.length + s.length > max) {
                if (cur.isNotBlank()) out.add(cur.toString().trim())
                if (s.length > max) {
                    var i = 0
                    while (i < s.length) { out.add(s.substring(i, minOf(i + max, s.length))); i += max }
                    cur = StringBuilder()
                } else cur = StringBuilder(s)
            } else cur.append(s)
        }
        if (cur.isNotBlank()) out.add(cur.toString().trim())
        return out.take(8)
    }

    private fun sarvamTts(context: Context, text: String, language: String): List<String> {
        val key = AppSettings.getSarvamKey(context)
        if (key.isEmpty()) return emptyList()
        val chunks = chunk(text, 450)
        if (chunks.isEmpty()) return emptyList()
        val body = JsonObject().apply {
            add("inputs", JsonArray().apply { chunks.forEach { add(it) } })
            addProperty("target_language_code", LanguageUtil.tagFor(language))
            addProperty("speaker", "anushka")
            addProperty("model", "bulbul:v2")
            addProperty("speech_sample_rate", 22050)
            addProperty("enable_preprocessing", true)
        }
        val req = Request.Builder()
            .url("https://api.sarvam.ai/text-to-speech")
            .addHeader("api-subscription-key", key)
            .post(body.toString().toRequestBody(JSON.toMediaType()))
            .build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return emptyList()
            val root = JsonParser.parseString(r.body?.string().orEmpty()).asJsonObject
            val arr = root.getAsJsonArray("audios") ?: return emptyList()
            return arr.map { it.asString }
        }
    }

    private fun geminiTts(context: Context, text: String): List<String> {
        val key = AppSettings.getGeminiKey(context)
        if (key.isEmpty()) return emptyList()
        val chunks = chunk(text, 800)
        val out = ArrayList<String>()
        for (c in chunks) {
            val body = JsonObject().apply {
                add("contents", JsonArray().apply {
                    add(JsonObject().apply { add("parts", JsonArray().apply { add(JsonObject().apply { addProperty("text", c) }) }) })
                })
                add("generationConfig", JsonObject().apply {
                    add("responseModalities", JsonArray().apply { add("AUDIO") })
                    add("speechConfig", JsonObject().apply {
                        add("voiceConfig", JsonObject().apply {
                            add("prebuiltVoiceConfig", JsonObject().apply { addProperty("voiceName", "Kore") })
                        })
                    })
                })
            }
            val req = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-preview-tts:generateContent")
                .addHeader("x-goog-api-key", key)
                .post(body.toString().toRequestBody(JSON.toMediaType()))
                .build()
            client.newCall(req).execute().use { r ->
                if (r.isSuccessful) {
                    val root = JsonParser.parseString(r.body?.string().orEmpty()).asJsonObject
                    val data = root.getAsJsonArray("candidates")?.firstOrNull()?.asJsonObject
                        ?.getAsJsonObject("content")?.getAsJsonArray("parts")?.firstOrNull()?.asJsonObject
                        ?.getAsJsonObject("inlineData")?.get("data")?.asString
                    if (data != null) out.add(pcmToWavBase64(data, 24000))
                }
            }
        }
        return out
    }

    private fun pcmToWavBase64(pcmBase64: String, sampleRate: Int): String {
        val pcm = Base64.decode(pcmBase64, Base64.DEFAULT)
        val channels = 1; val bits = 16
        val byteRate = sampleRate * channels * bits / 8
        val blockAlign = channels * bits / 8
        val out = ByteArrayOutputStream()
        fun str(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun intLE(v: Int) { out.write(v and 0xff); out.write((v shr 8) and 0xff); out.write((v shr 16) and 0xff); out.write((v shr 24) and 0xff) }
        fun shortLE(v: Int) { out.write(v and 0xff); out.write((v shr 8) and 0xff) }
        str("RIFF"); intLE(36 + pcm.size); str("WAVE"); str("fmt "); intLE(16); shortLE(1); shortLE(channels)
        intLE(sampleRate); intLE(byteRate); shortLE(blockAlign); shortLE(bits); str("data"); intLE(pcm.size); out.write(pcm)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
