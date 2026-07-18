package com.example.medicalscanner.local

import android.content.Context
import com.example.medicalscanner.BuildKeys
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

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
    private const val KEY_DISCLAIMER_ACCEPTED = "medical_disclaimer_accepted"
    private const val KEY_TREND_STANDARD_UNITS = "trend_standard_units"

    private val gson = Gson()

    /**
     * The unit each test is standardized to on the trend chart — the first non-blank unit ever
     * seen for that test, LOCKED so later readings in a different unit get converted to it (and
     * so the standard survives deleting or period-filtering the report it came from). Keyed by
     * "<patientName>|<trendCategory>". Trend charting only; the report screen is unaffected.
     */
    fun getTrendStandardUnits(context: Context): Map<String, String> {
        val json = prefs(context).getString(KEY_TREND_STANDARD_UNITS, null) ?: return emptyMap()
        return runCatching {
            gson.fromJson<Map<String, String>>(json, object : TypeToken<Map<String, String>>() {}.type)
        }.getOrNull() ?: emptyMap()
    }

    /** Records [unit] as the standard for [key] ("<patient>|<category>") only if none is set yet. */
    fun lockTrendStandardUnitIfAbsent(context: Context, key: String, unit: String) {
        if (unit.isBlank()) return
        val current = getTrendStandardUnits(context)
        if (current.containsKey(key)) return
        val updated = current + (key to unit)
        prefs(context).edit().putString(KEY_TREND_STANDARD_UNITS, gson.toJson(updated)).apply()
    }

    fun isDisclaimerAccepted(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DISCLAIMER_ACCEPTED, false)

    fun setDisclaimerAccepted(context: Context, accepted: Boolean) {
        prefs(context).edit().putBoolean(KEY_DISCLAIMER_ACCEPTED, accepted).apply()
    }

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

    // On first read ever (key never set), seed from the device's active keyboard language
    // instead of hardcoding English, then persist it so this only runs once.
    fun getPreferredLanguage(context: Context): String {
        val p = prefs(context)
        if (!p.contains(KEY_LANGUAGE)) {
            val detected = com.example.medicalscanner.util.DeviceLanguageDetector.detectFromKeyboard(context)
            p.edit().putString(KEY_LANGUAGE, detected).apply()
            return detected
        }
        return p.getString(KEY_LANGUAGE, "English") ?: "English"
    }

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

    // ── OAuth deep-link nonce ────────────────────────────────────────────────
    // "Link Google Account" opens a browser flow that redirects back into the app via the
    // medicalscanner://oauth2(-link) custom scheme — which any other app on the device can
    // also fire directly, since a custom scheme isn't exclusive. A single-use nonce generated
    // right before launching the flow, and required to match on the way back (Navigation.kt),
    // is what stops an unsolicited deep link from being trusted as a real OAuth result.
    private const val KEY_PENDING_OAUTH_NONCE = "pending_oauth_nonce"
    private const val KEY_PENDING_OAUTH_NONCE_AT = "pending_oauth_nonce_at"
    private const val OAUTH_NONCE_TTL_MS = 5 * 60 * 1000L // 5 minutes — plenty for a login round trip

    fun setPendingOAuthNonce(context: Context, nonce: String) {
        prefs(context).edit()
            .putString(KEY_PENDING_OAUTH_NONCE, nonce)
            .putLong(KEY_PENDING_OAUTH_NONCE_AT, System.currentTimeMillis())
            .apply()
    }

    /**
     * Non-destructive: returns the pending nonce (or null if none/expired) WITHOUT clearing it.
     * Deliberately not "consume on read" — a stray/attacker-fired deep link with no or a wrong
     * nonce must not burn a legitimate flow that's still in flight. Only [clearPendingOAuthNonce]
     * (called once the real match is confirmed) actually consumes it.
     */
    fun peekPendingOAuthNonce(context: Context): String? {
        val p = prefs(context)
        val nonce = p.getString(KEY_PENDING_OAUTH_NONCE, null)
        val setAt = p.getLong(KEY_PENDING_OAUTH_NONCE_AT, 0L)
        if (nonce.isNullOrBlank()) return null
        if (System.currentTimeMillis() - setAt > OAUTH_NONCE_TTL_MS) return null
        return nonce
    }

    fun clearPendingOAuthNonce(context: Context) {
        prefs(context).edit().remove(KEY_PENDING_OAUTH_NONCE).remove(KEY_PENDING_OAUTH_NONCE_AT).apply()
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

    // ── Email Scanning Configurations ────────────────────────────────────────
    private const val KEY_EMAIL_CONSENT = "email_consent_granted"
    private const val KEY_LINKED_EMAIL = "linked_email_address"
    private const val KEY_LINKED_EMAIL_TYPE = "linked_email_type"
    private const val KEY_IMAP_HOST = "linked_imap_host"
    private const val KEY_IMAP_PORT = "linked_imap_port"
    private const val KEY_EMAIL_SEARCH_PROMPT = "email_search_prompt"
    private const val KEY_EMAIL_SCAN_HOUR = "email_scan_hour"
    private const val KEY_EMAIL_SCAN_MINUTE = "email_scan_minute"

    fun isEmailConsentGranted(context: Context): Boolean =
        prefs(context).getBoolean(KEY_EMAIL_CONSENT, false)

    fun setEmailConsentGranted(context: Context, granted: Boolean) {
        prefs(context).edit().putBoolean(KEY_EMAIL_CONSENT, granted).apply()
    }

    fun getLinkedEmail(context: Context): String? =
        prefs(context).getString(KEY_LINKED_EMAIL, null)?.takeIf { it.isNotBlank() }

    fun setLinkedEmail(context: Context, email: String?) {
        prefs(context).edit().putString(KEY_LINKED_EMAIL, email).apply()
    }

    fun getLinkedEmailType(context: Context): String? =
        prefs(context).getString(KEY_LINKED_EMAIL_TYPE, null)?.takeIf { it.isNotBlank() }

    fun setLinkedEmailType(context: Context, type: String?) {
        prefs(context).edit().putString(KEY_LINKED_EMAIL_TYPE, type).apply()
    }

    fun getImapHost(context: Context): String =
        prefs(context).getString(KEY_IMAP_HOST, "imap.gmail.com") ?: "imap.gmail.com"

    fun setImapHost(context: Context, host: String) {
        prefs(context).edit().putString(KEY_IMAP_HOST, host).apply()
    }

    fun getImapPort(context: Context): Int =
        prefs(context).getInt(KEY_IMAP_PORT, 993)

    fun setImapPort(context: Context, port: Int) {
        prefs(context).edit().putInt(KEY_IMAP_PORT, port).apply()
    }

    fun getEmailScanHour(context: Context): Int =
        prefs(context).getInt(KEY_EMAIL_SCAN_HOUR, 19)

    fun getEmailScanMinute(context: Context): Int =
        prefs(context).getInt(KEY_EMAIL_SCAN_MINUTE, 0)

    fun setEmailScanTime(context: Context, hour: Int, minute: Int) {
        prefs(context).edit()
            .putInt(KEY_EMAIL_SCAN_HOUR, hour)
            .putInt(KEY_EMAIL_SCAN_MINUTE, minute)
            .apply()
    }

    fun getEmailSearchPrompt(context: Context): String =
        prefs(context).getString(KEY_EMAIL_SEARCH_PROMPT, "") ?: ""

    fun setEmailSearchPrompt(context: Context, prompt: String) {
        prefs(context).edit().putString(KEY_EMAIL_SEARCH_PROMPT, prompt).apply()
    }
}
