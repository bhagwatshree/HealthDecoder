package com.example.medicalscanner.local

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
}
