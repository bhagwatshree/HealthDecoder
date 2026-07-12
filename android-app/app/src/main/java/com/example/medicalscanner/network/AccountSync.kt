package com.example.medicalscanner.network

import android.content.Context
import com.example.medicalscanner.local.AppSettings
import com.example.medicalscanner.model.KeyAssignment

/**
 * Bridges the backend's per-user key issuance (GET /api/auth/keys) into the existing local
 * key storage in AppSettings, which GeminiClient/SpeechEngine already read from. This is the
 * "automatic key config" step: once logged in, the phone is handed either the user's own
 * saved key or their assigned house free-tier key, and it's written into the same override
 * slot a manually-pasted key would use — no separate code path needed in the AI callers.
 */
object AccountSync {

    /** Fetches the assigned key(s) and applies them locally. Returns the assignment so the
     *  caller can show usage/quota info, or null if the request failed (e.g. offline) —
     *  in which case the previously-synced key (or the BuildKeys fallback) keeps working. */
    suspend fun refreshAssignedKeys(context: Context): KeyAssignment? {
        val assignment = runCatching { NetworkModule.getApi(context).getAssignedKeys() }.getOrNull()
            ?: return null

        if (!assignment.geminiKey.isNullOrBlank()) {
            AppSettings.setGeminiKey(context, assignment.geminiKey)
        } else if (assignment.quotaExceeded) {
            // Don't overwrite a working key with nothing — leave the last-known-good key in
            // place so the phone keeps using it until the user adds their own key or the
            // free quota resets. The caller is expected to surface assignment.quotaExceeded.
        }
        if (!assignment.sarvamKey.isNullOrBlank()) {
            AppSettings.setSarvamKey(context, assignment.sarvamKey)
        }
        return assignment
    }

    /** Read-only usage/quota snapshot for display (e.g. the Account screen) — unlike
     *  refreshAssignedKeys, this never consumes a free-tier issuance, so it's safe to call
     *  every time the screen opens. Doesn't touch the locally cached active key. */
    suspend fun peekUsage(context: Context): KeyAssignment? =
        runCatching { NetworkModule.getApi(context).getUsage() }.getOrNull()
}
