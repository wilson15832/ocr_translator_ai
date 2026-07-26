package com.example.ocr_translation

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * User-added models, stored alongside the built-in list.
 *
 * Vendors ship new models faster than the app's `@array/models` can follow, but the request URL
 * and payload format don't change with them — so a new model only needs a code and something to
 * call it in the picker. This keeps those additions in preferences rather than requiring a
 * release.
 *
 * The provider is stored explicitly rather than being derived from the code's prefix the way
 * [LlmProvider.fromModel] does it: a user-entered code needn't follow the vendor's naming, and a
 * code that matches no prefix would silently fall back to ChatGPT and send the request to the
 * wrong endpoint.
 */
data class CustomModel(val provider: LlmProvider, val title: String, val code: String) {

    fun toJson(): JSONObject = JSONObject().apply {
        put(KEY_PROVIDER, provider.name)
        put(KEY_TITLE, title)
        put(KEY_CODE, code)
    }

    companion object {
        private const val TAG = "CustomModel"
        private const val KEY_PROVIDER = "provider"
        private const val KEY_TITLE = "title"
        private const val KEY_CODE = "code"

        fun listFromJson(raw: String): List<CustomModel> {
            if (raw.isBlank()) return emptyList()
            return try {
                val array = JSONArray(raw)
                (0 until array.length()).mapNotNull { i ->
                    val o = array.optJSONObject(i) ?: return@mapNotNull null
                    val provider = runCatching {
                        LlmProvider.valueOf(o.optString(KEY_PROVIDER))
                    }.getOrNull() ?: return@mapNotNull null
                    val title = o.optString(KEY_TITLE).takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    val code = o.optString(KEY_CODE).takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    CustomModel(provider, title, code)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't parse stored custom models; ignoring", e)
                emptyList()
            }
        }

        fun listToJson(models: List<CustomModel>): String =
            JSONArray().apply { models.forEach { put(it.toJson()) } }.toString()
    }
}
