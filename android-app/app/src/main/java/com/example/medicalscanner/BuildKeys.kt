package com.example.medicalscanner

/**
 * API keys embedded in the app so a non-technical user never has to configure anything.
 * Actual values come from BuildConfig, which is generated at build time from
 * android-app/local.properties (gitignored, per-machine — see local.properties.example).
 *
 * ⚠️ SECURITY: keys compiled into an APK CAN be extracted by anyone who has the file.
 * This is acceptable for private testing only. Before any public/Play Store release, move
 * these to a server-side proxy or rotate/revoke them.
 */
object BuildKeys {
    // Google Gemini (Generative Language API) — used for on-device OCR, insights, chat, medicine info.
    val GEMINI_API_KEY = BuildConfig.GEMINI_API_KEY

    // Sarvam AI — used for Indic OCR/translation when needed.
    val SARVAM_API_KEY = BuildConfig.SARVAM_API_KEY
}
