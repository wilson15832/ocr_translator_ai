package com.example.ocr_translation

import android.content.SharedPreferences
import android.content.Context
import androidx.core.content.edit
import android.util.Log


class PreferencesManager private constructor(context: Context) {

    // applicationContext is required for SecureStorage (the API key lives in EncryptedSharedPreferences)
    private val appContext: Context = context.applicationContext

    // Singleton pattern implementation
    companion object {
        private const val PREFS_NAME = "screen_translator_prefs"
        const val DEFAULT_SYSTEM_PROMPT =
            "你是专业的翻译引擎。只输出译文本身，绝不添加解释、注释、读音、假名标注、备注或任何多余文字。"
        const val DEFAULT_USER_PROMPT =
            "把下面每个块从 {source} 翻译成 {target}。\n" +
                    "保留每个 BLOCK_XXX: 前缀原样不动，在其后的同一行只写译文。\n" +
                    "除译文外不要输出任何东西。若某块文字不清楚，直接给出你认为最贴切的一种译法即可。\n\n" +
                    "{text}"
        private const val KEY_TRANSLATION_ACTIVE = "translation_active"
        private const val KEY_SOURCE_LANGUAGE = "source_language"
        private const val KEY_TARGET_LANGUAGE = "target_language"
        private const val KEY_LLM_API_ENDPOINT = "llm_api_endpoint"
        private const val KEY_LLM_API_KEY_SECURE = "llm_api_key"    // encrypted store key
        private const val KEY_MODEL_NAME = "model_name"
        private const val KEY_USE_LOCAL_MODEL = "use_local_model"
        private const val KEY_CAPTURE_INTERVAL = "capture_interval"
        private const val KEY_AUTO_CAPTURE_ENABLED = "auto_capture_enabled"
        private const val KEY_TEXT_SIZE_MULTIPLIER = "text_size_multiplier"
        private const val KEY_OVERLAY_OPACITY = "overlay_opacity"
        private const val KEY_USE_ALTERNATIVE_STYLE = "use_alternative_style"
        private const val KEY_MAX_CACHE_ENTRIES = "max_cache_entries"
        private const val KEY_CACHE_TTL_HOURS = "cache_ttl_hours"
        private const val KEY_KEEP_HISTORY_DAYS = "keep_history_days"
        private const val PREF_ACTIVE_AREA_LEFT = "active_area_left"
        private const val PREF_ACTIVE_AREA_TOP = "active_area_top"
        private const val PREF_ACTIVE_AREA_RIGHT = "active_area_right"
        private const val PREF_ACTIVE_AREA_BOTTOM = "active_area_bottom"
        private const val PREF_HAS_ACTIVE_AREA = "has_active_area"
        private const val KEY_MAX_TOKENS = "max_tokens"
        private const val KEY_MERGE_OVERLAP = "merge_overlap_boxes"

        @Volatile
        private var INSTANCE: PreferencesManager? = null

        fun getInstance(context: Context): PreferencesManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PreferencesManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // NOTE: The previous build persisted MediaProjection resultCode + Intent to SharedPreferences
    // (via Parcel marshalling + Base64) so the service could be relaunched without re-prompting.
    // That contract was unsound — the projection token doesn't survive process death — and the
    // current flow passes resultCode/data straight from the activity result to ScreenCaptureService.
    // The persistence code was removed; if it's ever needed again, prompt the user instead.


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

    /** API key, stored in EncryptedSharedPreferences (see [SecureStorage]). */
    fun getApiKey(provider: LlmProvider): String =
        SecureStorage.getEncryptedValue(appContext, provider.secureKey) ?: ""

    fun setApiKey(provider: LlmProvider, value: String) {
        if (value.isBlank()) SecureStorage.removeEncryptedValue(appContext, provider.secureKey)
        else SecureStorage.setEncryptedValue(appContext, provider.secureKey, value.trim())
    }

    /** 运行时用：当前激活模型对应公司的 key。 */
    val activeApiKey: String
        get() = getApiKey(LlmProvider.fromModel(modelName))

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

    var useAlternativeStyle: Boolean
        get() = prefs.getBoolean(KEY_USE_ALTERNATIVE_STYLE, false)
        set(value) = prefs.edit { putBoolean(KEY_USE_ALTERNATIVE_STYLE, value) }

    var showAreaBorder: Boolean
        get() = prefs.getBoolean("show_area_border", true)
        set(value) = prefs.edit { putBoolean("show_area_border", value) }

    // Which floating-bar button stays visible when the bar is folded ("manual" | "auto")
    var foldFavorite: String
        get() = prefs.getString("fold_favorite", "manual") ?: "manual"
        set(value) = prefs.edit { putString("fold_favorite", value) }

    // Translation result display
    var translationTextColor: Int
        get() = prefs.getInt("translation_text_color", 0xFFFFFFFF.toInt())
        set(value) = prefs.edit { putInt("translation_text_color", value) }

    var translationBgColor: Int
        get() = prefs.getInt("translation_bg_color", 0xFF000000.toInt())
        set(value) = prefs.edit { putInt("translation_bg_color", value) }

    // In-place mode: draw the translation over each original text region instead of one block
    var inPlaceMode: Boolean
        get() = prefs.getBoolean("in_place_mode", false)
        set(value) = prefs.edit { putBoolean("in_place_mode", value) }

    var mergeOverlapBoxes: Boolean
        get() = prefs.getBoolean(KEY_MERGE_OVERLAP, false)
        set(value) = prefs.edit { putBoolean(KEY_MERGE_OVERLAP, value) }

    // Enhanced OCR: read text from the accessibility node tree instead of screen-capture OCR.
    // Exact for native apps; has no effect on canvas/GL games (which expose no text nodes).
    var useAccessibility: Boolean
        get() = prefs.getBoolean("use_accessibility", false)
        set(value) = prefs.edit { putBoolean("use_accessibility", value) }

    // Result text font (system family name, e.g. "sans-serif-medium", "casual", "cursive")
    var translationFont: String
        get() = prefs.getString("translation_font", "sans-serif") ?: "sans-serif"
        set(value) = prefs.edit { putString("translation_font", value) }

    // User-loaded font file (absolute path inside filesDir; "" = no custom font, use [translationFont]).
    // Set via SettingsActivity's "Load custom font" picker, which copies the .ttf/.otf here.
    var customFontPath: String
        get() = prefs.getString("custom_font_path", "") ?: ""
        set(value) = prefs.edit { putString("custom_font_path", value) }

    // ---- Control panel (floating bar) styling ----
    // "horizontal" (default) or "vertical" — orientation of the button strip
    var controlPanelOrientation: String
        get() = prefs.getString("control_panel_orientation", "horizontal") ?: "horizontal"
        set(value) = prefs.edit { putString("control_panel_orientation", value) }

    // ARGB; alpha component is overwritten by [controlPanelOpacity] at draw time.
    var controlPanelBgColor: Int
        get() = prefs.getInt("control_panel_bg_color", 0xFF333333.toInt())
        set(value) = prefs.edit { putInt("control_panel_bg_color", value) }

    // 0.0 = transparent, 1.0 = opaque
    var controlPanelOpacity: Float
        get() = prefs.getFloat("control_panel_opacity", 0.8f)
        set(value) = prefs.edit { putFloat("control_panel_opacity", value) }

    // Last-known control panel position (saved on drag release). Int.MIN_VALUE = not yet placed.
    var controlPanelX: Int
        get() = prefs.getInt("control_panel_x", Int.MIN_VALUE)
        set(value) = prefs.edit { putInt("control_panel_x", value) }

    var controlPanelY: Int
        get() = prefs.getInt("control_panel_y", Int.MIN_VALUE)
        set(value) = prefs.edit { putInt("control_panel_y", value) }

    // ---- Save translations to text file ----
    var saveToFileEnabled: Boolean
        get() = prefs.getBoolean("save_to_file_enabled", false)
        set(value) = prefs.edit { putBoolean("save_to_file_enabled", value) }

    // SAF directory tree URI (granted via ACTION_OPEN_DOCUMENT_TREE). "" = not configured.
    var saveFolderUri: String
        get() = prefs.getString("save_folder_uri", "") ?: ""
        set(value) = prefs.edit { putString("save_folder_uri", value) }

    // Customizable LLM prompts ({source}, {target}, {text} placeholders in the user prompt)
    var systemPrompt: String
        get() = prefs.getString("system_prompt", DEFAULT_SYSTEM_PROMPT) ?: DEFAULT_SYSTEM_PROMPT
        set(value) = prefs.edit { putString("system_prompt", value) }

    var userPrompt: String
        get() = prefs.getString("user_prompt", DEFAULT_USER_PROMPT) ?: DEFAULT_USER_PROMPT
        set(value) = prefs.edit { putString("user_prompt", value) }

    var maxCacheEntries: Int
        get() = prefs.getInt(KEY_MAX_CACHE_ENTRIES, 100)
        set(value) = prefs.edit { putInt(KEY_MAX_CACHE_ENTRIES, value) }

    var maxTokens: Int
        get() = prefs.getInt(KEY_MAX_TOKENS, 1024)
        set(value) = prefs.edit { putInt(KEY_MAX_TOKENS, value) }

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

    // Add the reset method called by SettingsActivity
    fun resetToDefaults() {
        // Clear only the keys related to settings adjustable in SettingsActivity
        // Or clear all if that's the desired behavior
        prefs.edit {
            remove(KEY_SOURCE_LANGUAGE)
            remove(KEY_TARGET_LANGUAGE)
            remove(KEY_LLM_API_ENDPOINT)
            remove(KEY_MODEL_NAME)
            remove(KEY_USE_LOCAL_MODEL)
            remove(KEY_CAPTURE_INTERVAL)
            remove(KEY_AUTO_CAPTURE_ENABLED)
            remove(KEY_TEXT_SIZE_MULTIPLIER)
            remove(KEY_OVERLAY_OPACITY)
            remove(KEY_MAX_TOKENS)
            remove(KEY_USE_ALTERNATIVE_STYLE)
            remove(KEY_MAX_CACHE_ENTRIES)
            remove(KEY_CACHE_TTL_HOURS)
            remove(KEY_KEEP_HISTORY_DAYS)
            remove(KEY_MERGE_OVERLAP)
            // Decide if you want to reset translation_active etc. too
        }
        // Also clear the encrypted API key
        SecureStorage.removeEncryptedValue(appContext, KEY_LLM_API_KEY_SECURE)
        // Applying default values immediately might be better depending on your logic
        // Or rely on the getters providing defaults when the key is missing.
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
}