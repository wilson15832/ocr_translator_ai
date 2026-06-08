package com.example.ocr_translation

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Wrapper around [EncryptedSharedPreferences] for storing sensitive values
 * (currently: the LLM API key).
 *
 * Keystore-backed entries occasionally fail to decrypt after backup/restore or
 * keystore reset; the static accessors here fall back to clearing and recreating
 * the store rather than crashing the caller.
 */
object SecureStorage {

    private const val PREFS_NAME = "secure_prefs"

    private fun prefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return try {
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Keystore entry corrupted/rotated — wipe and try once more so we don't crash.
            Log.w("SecureStorage", "EncryptedSharedPreferences init failed; resetting store", e)
            context.deleteSharedPreferences(PREFS_NAME)
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }

    fun getEncryptedValue(context: Context, key: String): String? =
        prefs(context).getString(key, null)

    fun setEncryptedValue(context: Context, key: String, value: String) {
        prefs(context).edit().putString(key, value).apply()
    }

    fun removeEncryptedValue(context: Context, key: String) {
        prefs(context).edit().remove(key).apply()
    }

    fun containsKey(context: Context, key: String): Boolean =
        prefs(context).contains(key)
}