package com.example.ocr_translation

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SecureStorage(private val context: Context) {

    companion object {
        // Static method that matches your existing code
        fun getEncryptedValue(context: Context, key: String): String? {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            val securePreferences = EncryptedSharedPreferences.create(
                context,
                "secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

            return securePreferences.getString(key, null)
        }

        // Adding the corresponding setter method
        fun setEncryptedValue(context: Context, key: String, value: String) {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            val securePreferences = EncryptedSharedPreferences.create(
                context,
                "secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

            securePreferences.edit().putString(key, value).apply()
        }
    }

    // Instance methods can still be used if needed
    fun saveApiKey(key: String) {
        setEncryptedValue(context, "api_key", key)
    }

    fun getApiKey(): String? {
        return getEncryptedValue(context, "api_key")
    }
}