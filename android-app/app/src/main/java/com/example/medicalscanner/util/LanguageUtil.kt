package com.example.medicalscanner.util

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Maps the app's language names to BCP-47 tags used by speech recognition (STT) and
 * text-to-speech (TTS). Must stay in sync with AppSettings.SUPPORTED_LANGUAGES and the
 * backend's LANGUAGE_CODES.
 */
object LanguageUtil {
    private val bcp47 = mapOf(
        "English" to "en-IN",
        "Hindi" to "hi-IN",
        "Marathi" to "mr-IN",
        "Gujarati" to "gu-IN",
        "Tamil" to "ta-IN",
        "Telugu" to "te-IN",
        "Kannada" to "kn-IN",
        "Bengali" to "bn-IN",
        "Punjabi" to "pa-IN",
        "Malayalam" to "ml-IN",
        "Odia" to "or-IN"
    )

    fun tagFor(language: String): String = bcp47[language] ?: "en-IN"

    fun localeFor(language: String): Locale = Locale.forLanguageTag(tagFor(language))

    /** Translates text from English to target language via Sarvam Translate API */
    fun translate(context: Context, text: String, targetLanguage: String): String {
        if (targetLanguage.equals("English", ignoreCase = true) || text.isBlank()) {
            return text
        }
        val targetCode = tagFor(targetLanguage)
        val apiKey = com.example.medicalscanner.local.AppSettings.getSarvamKey(context)
        if (apiKey.isBlank()) {
            return text
        }

        val paragraphs = text.split("\n\n")
        val translatedParagraphs = paragraphs.map { paragraph ->
            if (paragraph.isBlank()) "" else translateChunk(paragraph, targetCode, apiKey)
        }
        return translatedParagraphs.joinToString("\n\n")
    }

    private fun translateChunk(text: String, targetCode: String, apiKey: String): String {
        return try {
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()

            val bodyJson = JsonObject().apply {
                addProperty("input", text)
                addProperty("source_language_code", "en-IN")
                addProperty("target_language_code", targetCode)
                addProperty("model", "sarvam-translate:v1")
            }

            val request = Request.Builder()
                .url("https://api.sarvam.ai/translate")
                .addHeader("api-subscription-key", apiKey)
                .addHeader("Content-Type", "application/json")
                .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val respText = response.body?.string() ?: ""
                    val root = JsonParser.parseString(respText).asJsonObject
                    root.get("translated_text")?.asString ?: text
                } else {
                    text
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            text
        }
    }
}
