package com.example.medicalscanner.util

import java.util.Locale

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
}
