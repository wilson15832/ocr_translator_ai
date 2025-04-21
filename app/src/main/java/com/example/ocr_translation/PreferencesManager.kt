package com.example.ocr_translation

import android.content.SharedPreferences
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import android.os.Bundle
import android.os.Parcel
import android.util.Base64
import android.os.Build
import android.util.Log
import android.graphics.RectF


class PreferencesManager private constructor(context: Context) {

    // Singleton pattern implementation
    companion object {
        private const val PREFS_NAME = "screen_translator_prefs"
        private const val KEY_TRANSLATION_ACTIVE = "translation_active"
        private const val KEY_PROJECTION_RESULT_CODE = "projection_result_code"
        private const val KEY_SOURCE_LANGUAGE = "source_language"
        private const val KEY_TARGET_LANGUAGE = "target_language"
        private const val KEY_LLM_INSTRUCTION = "llm_instruction"
        private const val KEY_LLM_API_ENDPOINT = "llm_api_endpoint"
        private const val KEY_LLM_API_KEY = "llm_api_key"
        private const val KEY_MODEL_NAME = "model_name"
        private const val KEY_USE_LOCAL_MODEL = "use_local_model"
        private const val KEY_CAPTURE_INTERVAL = "capture_interval"
        private const val KEY_AUTO_CAPTURE_ENABLED = "auto_capture_enabled"
        private const val KEY_TEXT_SIZE_MULTIPLIER = "text_size_multiplier"
        private const val KEY_OVERLAY_OPACITY = "overlay_opacity"
        private const val KEY_HIGHLIGHT_ORIGINAL = "highlight_original"
        private const val KEY_USE_ALTERNATIVE_STYLE = "use_alternative_style"
        private const val KEY_MAX_CACHE_ENTRIES = "max_cache_entries"
        private const val KEY_CACHE_TTL_HOURS = "cache_ttl_hours"
        private const val KEY_KEEP_HISTORY_DAYS = "keep_history_days"
        private const val KEY_PROJECTION_DATA_BUNDLE = "projection_data_bundle"
        private const val PREF_ACTIVE_AREA_LEFT = "active_area_left"
        private const val PREF_ACTIVE_AREA_TOP = "active_area_top"
        private const val PREF_ACTIVE_AREA_RIGHT = "active_area_right"
        private const val PREF_ACTIVE_AREA_BOTTOM = "active_area_bottom"
        private const val PREF_HAS_ACTIVE_AREA = "has_active_area"


        @Volatile
        private var INSTANCE: PreferencesManager? = null

        fun getInstance(context: Context): PreferencesManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PreferencesManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)


    fun saveMediaProjectionData(resultCode: Int, data: Intent) {
        Log.d("PreferencesManager", "Attempting to save media projection data. ResultCode: $resultCode, Intent: $data")
        prefs.edit { // Saving resultCode
            putInt(KEY_PROJECTION_RESULT_CODE, resultCode)
            Log.d("PreferencesManager", "Saved resultCode: $resultCode")
        }

        try {
            val bundle = Bundle()
            bundle.putParcelable("intent_data", data)
            Log.d("PreferencesManager", "Intent put into bundle.")
            val bundleBytes = getBundleAsBytes(bundle) // Assumes getBundleAsBytes works or logs errors if it fails
            if (bundleBytes != null) {
                val bundleStr = Base64.encodeToString(bundleBytes, Base64.DEFAULT)
                Log.d("PreferencesManager", "Marshalled bundle to Base64 string (length: ${bundleStr.length}).")
                prefs.edit { // Saving bundle string
                    putString(KEY_PROJECTION_DATA_BUNDLE, bundleStr)
                    Log.d("PreferencesManager", "Saved bundle string.")
                }
            } else {
                Log.e("PreferencesManager", "Failed to marshall bundle to bytes!")
            }
        } catch (e: Exception) {
            // **** Log the exception during save ****
            Log.e("PreferencesManager", "Error saving media projection data bundle", e)
        }
    }


    private fun getBundleAsBytes(bundle: Bundle): ByteArray? {
        val parcel = Parcel.obtain()
        try {
            bundle.writeToParcel(parcel, 0)
            return parcel.marshall()
        } finally {
            parcel.recycle()
        }
    }

    private fun getBytesAsBundle(bytes: ByteArray): Bundle? {
        val parcel = Parcel.obtain()
        try {
            parcel.unmarshall(bytes, 0, bytes.size)
            parcel.setDataPosition(0)
            return Bundle.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }


    // Media projection result code
    val mediaProjectionResultCode: Int
        get() = prefs.getInt(KEY_PROJECTION_RESULT_CODE, 0)

    val mediaProjectionData: Intent?
        get() {
            Log.d("PreferencesManager", "Attempting to retrieve media projection data...")
            val bundleStr = prefs.getString(KEY_PROJECTION_DATA_BUNDLE, null)
            if (bundleStr == null) {
                Log.w("PreferencesManager", "Bundle string not found in prefs.")
                return null
            }
            Log.d("PreferencesManager", "Retrieved bundle string (length: ${bundleStr.length}).")
            try {
                val bundleBytes = Base64.decode(bundleStr, Base64.DEFAULT)
                Log.d("PreferencesManager", "Decoded Base64 string to bytes.")
                val bundle = getBytesAsBundle(bundleBytes)
                if (bundle == null) {
                    Log.e("PreferencesManager", "Failed to unmarshall bytes to bundle!")
                    return null
                }
                Log.d("PreferencesManager", "Unmarshalled bytes to bundle.")

                val intent: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Log.d("PreferencesManager", "Getting parcelable for TIRAMISU+")
                    bundle.getParcelable("intent_data", Intent::class.java)
                } else {
                    Log.d("PreferencesManager", "Getting parcelable pre-TIRAMISU")
                    @Suppress("DEPRECATION")
                    bundle.getParcelable("intent_data")
                }
                Log.d("PreferencesManager", "Retrieved intent from bundle: $intent")
                return intent
            } catch (e: Exception) {
                Log.e("PreferencesManager", "Error retrieving media projection data during processing", e)
                return null // Return null on error
            }
        }



    // --- Define properties matching SettingsActivity usage ---

    var sourceLanguage: String
        get() {
            val value = prefs.getString(KEY_SOURCE_LANGUAGE, "auto") ?: "auto"
            Log.d("PreferencesManager", "Loading source language: $value") // 添加日志
            return value
        }
        set(value) {
            Log.d("PreferencesManager", "Saving source language: $value") // 添加日志
            prefs.edit { putString(KEY_SOURCE_LANGUAGE, value) }
        }

    var targetLanguage: String
        get() {
            val value = prefs.getString(KEY_TARGET_LANGUAGE, "en") ?: "en"
            Log.d("PreferencesManager", "Loading target language: $value") // 添加日志
            return value
        }
        set(value) {
            Log.d("PreferencesManager", "Saving target language: $value") // 添加日志
            prefs.edit { putString(KEY_TARGET_LANGUAGE, value) }
        }

    var llmInstruction: String
        get() = prefs.getString(KEY_LLM_INSTRUCTION, "Be faithful to the original, fluent in expression, and graceful in style.") ?: "Be faithful to the original, fluent in expression, and graceful in style."
        set(value) = prefs.edit { putString(KEY_LLM_INSTRUCTION, value) }

    var llmApiEndpoint: String
        get() = prefs.getString(KEY_LLM_API_ENDPOINT, "https://api.openai.com/v1/chat/completions") ?: "https://api.openai.com/v1/chat/completions"
        set(value) = prefs.edit { putString(KEY_LLM_API_ENDPOINT, value) }

    var llmApiKey: String
        get() = prefs.getString(KEY_LLM_API_KEY, "") ?: ""
        set(value) = prefs.edit { putString(KEY_LLM_API_KEY, value) } // Consider secure storage for API keys!

    var modelName: String
        get() = prefs.getString(KEY_MODEL_NAME, "gpt-4-turbo") ?: "gpt-4-turbo"
        set(value) = prefs.edit { putString(KEY_MODEL_NAME, value) }

    var useLocalModel: Boolean
        get() = prefs.getBoolean(KEY_USE_LOCAL_MODEL, false)
        set(value) = prefs.edit { putBoolean(KEY_USE_LOCAL_MODEL, value) }

    var captureInterval: Long // Stored as Long (milliseconds)
        get() = prefs.getLong(KEY_CAPTURE_INTERVAL, 1000L) // Default 1000ms
        set(value) = prefs.edit { putLong(KEY_CAPTURE_INTERVAL, value) }

    var autoCaptureEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CAPTURE_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_CAPTURE_ENABLED, value) }

    var textSizeMultiplier: Float
        get() = prefs.getFloat(KEY_TEXT_SIZE_MULTIPLIER, 1.0f)
        set(value) = prefs.edit { putFloat(KEY_TEXT_SIZE_MULTIPLIER, value) }

    var overlayOpacity: Float
        get() = prefs.getFloat(KEY_OVERLAY_OPACITY, 0.8f)
        set(value) = prefs.edit { putFloat(KEY_OVERLAY_OPACITY, value) }

    var highlightOriginalText: Boolean
        get() = prefs.getBoolean(KEY_HIGHLIGHT_ORIGINAL, true)
        set(value) = prefs.edit { putBoolean(KEY_HIGHLIGHT_ORIGINAL, value) }

    var useAlternativeStyle: Boolean
        get() = prefs.getBoolean(KEY_USE_ALTERNATIVE_STYLE, false)
        set(value) = prefs.edit { putBoolean(KEY_USE_ALTERNATIVE_STYLE, value) }

    var maxCacheEntries: Int
        get() = prefs.getInt(KEY_MAX_CACHE_ENTRIES, 100)
        set(value) = prefs.edit { putInt(KEY_MAX_CACHE_ENTRIES, value) }

    var cacheTtlHours: Int
        get() = prefs.getInt(KEY_CACHE_TTL_HOURS, 24)
        set(value) = prefs.edit { putInt(KEY_CACHE_TTL_HOURS, value) }

    var keepHistoryDays: Int
        get() = prefs.getInt(KEY_KEEP_HISTORY_DAYS, 7)
        set(value) = prefs.edit { putInt(KEY_KEEP_HISTORY_DAYS, value) }

    var preserveFormatting: Boolean
        get() = prefs.getBoolean("preserve_formatting", true)
        set(value) = prefs.edit { putBoolean("preserve_formatting", value) }

    var preferSpeed: Boolean
        get() = prefs.getBoolean("prefer_speed", false)
        set(value) = prefs.edit { putBoolean("prefer_speed", value) }

    // --- Keep other methods if needed ---
    fun isTranslationActive(): Boolean {
        return prefs.getBoolean(KEY_TRANSLATION_ACTIVE, false)
    }

    fun setTranslationActive(active: Boolean) {
        prefs.edit { putBoolean(KEY_TRANSLATION_ACTIVE, active) }
    }

    // ... keep projection methods if needed, remembering the limitations ...

    // Add the reset method called by SettingsActivity
    fun resetToDefaults() {
        // Clear only the keys related to settings adjustable in SettingsActivity
        // Or clear all if that's the desired behavior
        prefs.edit {
            remove(KEY_SOURCE_LANGUAGE)
            remove(KEY_TARGET_LANGUAGE)
            remove(KEY_LLM_INSTRUCTION)
            remove(KEY_LLM_API_ENDPOINT)
            remove(KEY_LLM_API_KEY) // Be careful resetting API keys
            remove(KEY_MODEL_NAME)
            remove(KEY_USE_LOCAL_MODEL)
            remove(KEY_CAPTURE_INTERVAL)
            remove(KEY_AUTO_CAPTURE_ENABLED)
            remove(KEY_TEXT_SIZE_MULTIPLIER)
            remove(KEY_OVERLAY_OPACITY)
            remove(KEY_HIGHLIGHT_ORIGINAL)
            remove(KEY_USE_ALTERNATIVE_STYLE)
            remove(KEY_MAX_CACHE_ENTRIES)
            remove(KEY_CACHE_TTL_HOURS)
            remove(KEY_KEEP_HISTORY_DAYS)
            // Decide if you want to reset translation_active etc. too
        }
        // Applying default values immediately might be better depending on your logic
        // Or rely on the getters providing defaults when the key is missing.
    }

    fun areSettingsComplete(): Boolean {
        // Check if essential settings are configured
        return llmApiKey.isNotEmpty() && llmApiEndpoint.isNotEmpty()
    }

    fun saveActiveTranslationArea(rectF: android.graphics.RectF) {
        prefs.edit().apply {
            putFloat(PREF_ACTIVE_AREA_LEFT, rectF.left)
            putFloat(PREF_ACTIVE_AREA_TOP, rectF.top)
            putFloat(PREF_ACTIVE_AREA_RIGHT, rectF.right)
            putFloat(PREF_ACTIVE_AREA_BOTTOM, rectF.bottom)
            putBoolean(PREF_HAS_ACTIVE_AREA, true)
            apply()
        }
        Log.d("PreferencesManager", "Saved active translation area: $rectF")
    }

    fun clearActiveTranslationArea() {
        prefs.edit().apply {
            putBoolean(PREF_HAS_ACTIVE_AREA, false)
            apply()
        }
        Log.d("PreferencesManager", "Cleared active translation area")
    }

    // Get the active translation area from preferences
    fun getActiveTranslationArea(): android.graphics.RectF? {
        val hasActiveArea = prefs.getBoolean(PREF_HAS_ACTIVE_AREA, false)
        if (!hasActiveArea) {
            return null
        }

        return android.graphics.RectF(
            prefs.getFloat(PREF_ACTIVE_AREA_LEFT, 0f),
            prefs.getFloat(PREF_ACTIVE_AREA_TOP, 0f),
            prefs.getFloat(PREF_ACTIVE_AREA_RIGHT, 0f),
            prefs.getFloat(PREF_ACTIVE_AREA_BOTTOM, 0f)
        ).also {
            Log.d("PreferencesManager", "Retrieved active translation area: $it")
        }
    }

    // REMOVED the duplicate explicit getter methods
    // The property accessors above already generate these methods

    fun saveSourceLanguage(language: String) {
        Log.d("PreferencesManager", "Saving source language: $language")
        sourceLanguage = language
    }

    fun saveTargetLanguage(language: String) {
        Log.d("PreferencesManager", "Saving source language: $language")
        targetLanguage = language
    }
}