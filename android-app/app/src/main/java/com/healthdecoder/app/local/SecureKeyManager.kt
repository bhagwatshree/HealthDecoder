package com.healthdecoder.app.local

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import java.security.SecureRandom

/**
 * Manages secure cryptographically strong keys/passphrases for SQLCipher database encryption.
 * Passphrases are generated on first launch and stored locally in EncryptedSharedPreferences,
 * which uses keys secured by the Android Keystore system (hardware-backed when available).
 */
object SecureKeyManager {
    private const val PREFS_FILE = "secure_key_prefs"
    private const val KEY_DB_PASSWORD = "db_passphrase_key"

    // Set the moment either Keystore-backed path below throws and falls back to plain
    // SharedPreferences (e.g. a corrupted/modified Keystore on some custom ROMs) — surfaced
    // by SettingsScreen.kt as a warning instead of silently downgrading with no indication.
    @Volatile
    private var usedInsecureFallback = false

    fun isStorageHardwareBacked(): Boolean = !usedInsecureFallback

    /**
     * Retrieves the persisted database passphrase, generating a new one if it doesn't exist.
     * Returns the 32-byte key as a ByteArray.
     */
    fun getDatabasePassphrase(context: Context): ByteArray {
        return try {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            val sharedPreferences = EncryptedSharedPreferences.create(
                PREFS_FILE,
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

            var passStr = sharedPreferences.getString(KEY_DB_PASSWORD, null)
            if (passStr.isNullOrBlank()) {
                val key = ByteArray(32)
                SecureRandom().nextBytes(key)
                passStr = Base64.encodeToString(key, Base64.NO_WRAP)
                sharedPreferences.edit().putString(KEY_DB_PASSWORD, passStr).apply()
            }
            Base64.decode(passStr, Base64.NO_WRAP)
        } catch (e: Exception) {
            e.printStackTrace()
            usedInsecureFallback = true
            // Fallback (e.g., if Keystore has become corrupted or modified on custom ROMs)
            val legacyPrefs = context.getSharedPreferences("legacy_key_prefs", Context.MODE_PRIVATE)
            var passStr = legacyPrefs.getString(KEY_DB_PASSWORD, null)
            if (passStr.isNullOrBlank()) {
                val key = ByteArray(32)
                SecureRandom().nextBytes(key)
                passStr = Base64.encodeToString(key, Base64.NO_WRAP)
                legacyPrefs.edit().putString(KEY_DB_PASSWORD, passStr).apply()
            }
            Base64.decode(passStr, Base64.NO_WRAP)
        }
    }

    /**
     * Overwrites the persisted database passphrase — used only when restoring a backup created
     * on a different install (see BackupManager.restoreBackup). A fresh install always generates
     * its own random passphrase on first use, which can't decrypt a medical_records.db encrypted
     * under a different device's passphrase; this replaces it with the one the backup was made
     * with, so the restored database is actually readable instead of silently recreated empty.
     */
    fun setDatabasePassphrase(context: Context, passphrase: ByteArray) {
        // commit(), not apply(): the caller (BackupManager.restoreBackup) force-kills the process
        // via Runtime.exit() moments later to reload from the restored files. apply()'s write is
        // async — if the process dies before it lands, the whole point of this call is lost and
        // the restored database becomes unreadable exactly like the bug this exists to fix.
        getSecurePrefs(context).edit()
            .putString(KEY_DB_PASSWORD, Base64.encodeToString(passphrase, Base64.NO_WRAP))
            .commit()
    }

    private fun getSecurePrefs(context: Context): android.content.SharedPreferences {
        return try {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                PREFS_FILE,
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            e.printStackTrace()
            usedInsecureFallback = true
            context.getSharedPreferences("legacy_secure_prefs", Context.MODE_PRIVATE)
        }
    }

    fun getEmailToken(context: Context): String? =
        getSecurePrefs(context).getString("email_oauth_token", null)

    fun setEmailToken(context: Context, token: String?) {
        getSecurePrefs(context).edit().putString("email_oauth_token", token).apply()
    }

    fun getImapPassword(context: Context): String? =
        getSecurePrefs(context).getString("email_imap_password", null)

    fun setImapPassword(context: Context, pass: String?) {
        getSecurePrefs(context).edit().putString("email_imap_password", pass).apply()
    }
}
