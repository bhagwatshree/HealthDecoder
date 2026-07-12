package com.example.medicalscanner.local

import android.content.Context
import com.example.medicalscanner.BuildKeys

/**
 * Stores the on-device API keys (entered by the user in Settings) so the phone can call
 * Gemini / Sarvam directly without a PC server. Reuses the existing prefs file.
 */
object AppSettings {
    private const val PREFS = "medical_scanner_prefs"
    private const val KEY_GEMINI = "gemini_api_key"
    private const val KEY_SARVAM = "sarvam_api_key"
    private const val KEY_LANGUAGE = "preferred_language"
    private const val KEY_VOICE_ENGINE = "voice_engine"
    private const val KEY_REMINDER_STYLE = "reminder_style"

    /** Medicine reminder styles: a standard notification, or a full-screen alarm page
     *  with very large text so elderly users can read the medicine names clearly. */
    const val REMINDER_STYLE_NORMAL = "normal"
    const val REMINDER_STYLE_FULLSCREEN = "fullscreen"

    fun getReminderStyle(context: Context): String =
        prefs(context).getString(KEY_REMINDER_STYLE, REMINDER_STYLE_NORMAL) ?: REMINDER_STYLE_NORMAL

    fun setReminderStyle(context: Context, style: String) {
        prefs(context).edit().putString(KEY_REMINDER_STYLE, style).apply()
    }

    // ── Scan pipeline tuning (internal, no UI) ──────────────────────────────
    // Large multi-document scans are sent to the AI in chunks of this many pages per
    // request; one giant request exceeds free-tier request/response limits and fails.
    // Raise via setScanChunkPages() if a paid API tier with bigger limits is used.
    private const val KEY_SCAN_CHUNK_PAGES = "scan_chunk_pages"
    private const val KEY_SCAN_MAX_PAGES = "scan_max_pages"

    fun getScanChunkPages(context: Context): Int =
        prefs(context).getInt(KEY_SCAN_CHUNK_PAGES, 6).coerceIn(1, 30)

    fun setScanChunkPages(context: Context, pages: Int) {
        prefs(context).edit().putInt(KEY_SCAN_CHUNK_PAGES, pages).apply()
    }

    /** Total page cap per scan session (memory guard). */
    fun getScanMaxPages(context: Context): Int =
        prefs(context).getInt(KEY_SCAN_MAX_PAGES, 60).coerceIn(5, 200)

    fun setScanMaxPages(context: Context, pages: Int) {
        prefs(context).edit().putInt(KEY_SCAN_MAX_PAGES, pages).apply()
    }

    // Minimum spacing between Gemini requests. The free tier allows ~20 requests/minute;
    // without pacing a bulk scan bursts past it and every call 429s. Set to 0 when a
    // paid API tier is used.
    private const val KEY_AI_MIN_INTERVAL_MS = "ai_min_request_interval_ms"

    fun getAiMinRequestIntervalMs(context: Context): Long =
        prefs(context).getLong(KEY_AI_MIN_INTERVAL_MS, 3200L).coerceIn(0L, 30_000L)

    fun setAiMinRequestIntervalMs(context: Context, ms: Long) {
        prefs(context).edit().putLong(KEY_AI_MIN_INTERVAL_MS, ms).apply()
    }

    /** Text-to-speech engines the user can pick. */
    val VOICE_ENGINES = listOf("Sarvam", "Gemini", "Phone")

    fun getVoiceEngine(context: Context): String =
        prefs(context).getString(KEY_VOICE_ENGINE, "Sarvam") ?: "Sarvam"

    fun setVoiceEngine(context: Context, engine: String) {
        prefs(context).edit().putString(KEY_VOICE_ENGINE, engine).apply()
    }

    /** Languages offered for medicine explanations (must match backend LANGUAGE_CODES). */
    val SUPPORTED_LANGUAGES = listOf(
        "English", "Hindi", "Marathi", "Gujarati", "Tamil",
        "Telugu", "Kannada", "Bengali", "Punjabi", "Malayalam", "Odia"
    )

    fun getPreferredLanguage(context: Context): String =
        prefs(context).getString(KEY_LANGUAGE, "English") ?: "English"

    fun setPreferredLanguage(context: Context, language: String) {
        prefs(context).edit().putString(KEY_LANGUAGE, language).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // Keys fall back to the embedded BuildKeys so a non-technical user never has to enter
    // anything; a stored override (if ever set) wins.
    fun getGeminiKey(context: Context): String {
        val stored = prefs(context).getString(KEY_GEMINI, "")?.trim().orEmpty()
        return stored.ifEmpty { BuildKeys.GEMINI_API_KEY }
    }

    fun setGeminiKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_GEMINI, key.trim()).apply()
    }

    fun getSarvamKey(context: Context): String {
        val stored = prefs(context).getString(KEY_SARVAM, "")?.trim().orEmpty()
        return stored.ifEmpty { BuildKeys.SARVAM_API_KEY }
    }

    fun setSarvamKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_SARVAM, key.trim()).apply()
    }

    fun hasGeminiKey(context: Context): Boolean = getGeminiKey(context).isNotEmpty()

    // ── Account / login ──────────────────────────────────────────────────────
    // The backend never sees scanned images (all AI calls happen on-device via GeminiClient),
    // it only issues which key to use and tracks each user's free-tier usage. See
    // network/AccountSync.kt for how the JWT below is used to pull that assigned key.
    private const val KEY_AUTH_TOKEN = "auth_token"
    private const val KEY_USER_EMAIL = "auth_user_email"

    private const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"
    private const val KEY_BIOMETRIC_TOKEN = "biometric_token"
    private const val KEY_BIOMETRIC_USER_EMAIL = "biometric_user_email"

    fun getAuthToken(context: Context): String? =
        prefs(context).getString(KEY_AUTH_TOKEN, null)?.takeIf { it.isNotBlank() }

    fun setAuthToken(context: Context, token: String?) {
        prefs(context).edit().putString(KEY_AUTH_TOKEN, token).apply()
    }

    fun getUserEmail(context: Context): String? = prefs(context).getString(KEY_USER_EMAIL, null)

    fun setUserEmail(context: Context, email: String?) {
        prefs(context).edit().putString(KEY_USER_EMAIL, email).apply()
    }

    fun isLoggedIn(context: Context): Boolean = getAuthToken(context) != null

    /** Logs out. Deliberately does NOT clear the Gemini/Sarvam keys — they fall back to
     *  BuildKeys so scanning still works offline/logged-out, just without a personal quota. */
    fun logout(context: Context) {
        prefs(context).edit().remove(KEY_AUTH_TOKEN).remove(KEY_USER_EMAIL).apply()
    }

    fun isBiometricEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BIOMETRIC_ENABLED, false)

    fun setBiometricEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BIOMETRIC_ENABLED, enabled).apply()
    }

    fun getBiometricToken(context: Context): String? =
        prefs(context).getString(KEY_BIOMETRIC_TOKEN, null)?.takeIf { it.isNotBlank() }

    fun setBiometricToken(context: Context, token: String?) {
        prefs(context).edit().putString(KEY_BIOMETRIC_TOKEN, token).apply()
    }

    fun getBiometricUserEmail(context: Context): String? =
        prefs(context).getString(KEY_BIOMETRIC_USER_EMAIL, null)?.takeIf { it.isNotBlank() }

    fun setBiometricUserEmail(context: Context, email: String?) {
        prefs(context).edit().putString(KEY_BIOMETRIC_USER_EMAIL, email).apply()
    }

    fun clearBiometricCredentials(context: Context) {
        prefs(context).edit()
            .remove(KEY_BIOMETRIC_TOKEN)
            .remove(KEY_BIOMETRIC_USER_EMAIL)
            .apply()
    }

    const val THEME_SYSTEM = "system"
    const val THEME_LIGHT = "light"
    const val THEME_DARK = "dark"
    private const val KEY_THEME_MODE = "theme_mode"

    fun getThemeMode(context: Context): String =
        prefs(context).getString(KEY_THEME_MODE, THEME_SYSTEM) ?: THEME_SYSTEM

    fun setThemeMode(context: Context, mode: String) {
        prefs(context).edit().putString(KEY_THEME_MODE, mode).apply()
    }
}
