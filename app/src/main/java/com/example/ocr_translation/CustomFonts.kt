package com.example.ocr_translation

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A typeface the user loaded from a file, stored alongside the bundled ones.
 *
 * Kept as a list rather than the single slot this replaced. That slot copied every pick over one
 * fixed `custom_font.ttf`, which cost the user both things you want from loading a font: the file's
 * own name — the picker could only ever show `custom_font.ttf`, which says nothing about which font
 * it is — and the ability to keep more than one and switch between them.
 *
 * [name] is the picked file's display name without its extension, which is what the user recognises
 * it by. [fileName] is the copy's name inside [dir]; the file is copied there so its path survives
 * the content URI's permission expiring and any later move of the original.
 */
data class CustomFont(val name: String, val fileName: String) {

    fun toJson(): JSONObject = JSONObject().apply {
        put(KEY_NAME, name)
        put(KEY_FILE, fileName)
    }

    /** The stored token that selects this font, as it lives in `translationFont`. */
    val token: String get() = PREFIX + fileName

    fun file(context: Context): File = File(dir(context), fileName)

    companion object {
        private const val TAG = "CustomFont"
        private const val KEY_NAME = "name"
        private const val KEY_FILE = "file"

        /**
         * Marks a `translationFont` value as one of these rather than a bundled font resource or a
         * system family name. A prefix rather than a separate preference so there is still exactly
         * one answer to "which font is selected".
         */
        const val PREFIX = "customfont:"

        fun dir(context: Context): File = File(context.filesDir, "fonts")

        /** The custom font a stored `translationFont` value names, or null if it names anything else. */
        fun fromToken(token: String, fonts: List<CustomFont>): CustomFont? {
            if (!token.startsWith(PREFIX)) return null
            val fileName = token.removePrefix(PREFIX)
            return fonts.firstOrNull { it.fileName == fileName }
        }

        fun listFromJson(raw: String): List<CustomFont> {
            if (raw.isBlank()) return emptyList()
            return try {
                val array = JSONArray(raw)
                (0 until array.length()).mapNotNull { i ->
                    val o = array.optJSONObject(i) ?: return@mapNotNull null
                    val name = o.optString(KEY_NAME).takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    val file = o.optString(KEY_FILE).takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    CustomFont(name, file)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't parse stored custom fonts; ignoring", e)
                emptyList()
            }
        }

        fun listToJson(fonts: List<CustomFont>): String =
            JSONArray().apply { fonts.forEach { put(it.toJson()) } }.toString()
    }
}
