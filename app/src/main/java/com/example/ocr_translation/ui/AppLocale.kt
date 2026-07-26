package com.example.ocr_translation.ui

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.example.ocr_translation.R

/**
 * The app's UI language, independent of the translation source/target languages.
 *
 * Uses AppCompat's per-app locale API rather than swapping resources by hand: on Android 13+ it
 * delegates to the platform (so the choice also shows up in the system's per-app language screen),
 * and below that AppCompat persists and re-applies it. `AppLocalesMetadataHolderService` in the
 * manifest is what enables that persistence on older releases.
 */
object AppLocale {

    /** `null` tag means "follow the system", which is the default. */
    data class Option(val tag: String?, val labelRes: Int)

    val options: List<Option> = listOf(
        Option(null, R.string.ui_language_system),
        Option("en", R.string.ui_language_en),
        Option("zh", R.string.ui_language_zh)
    )

    /** Index into [options] for whatever is currently applied. */
    fun selectedIndex(): Int {
        val current = AppCompatDelegate.getApplicationLocales()
        if (current.isEmpty) return 0
        val tag = current[0]?.language ?: return 0
        return options.indexOfFirst { it.tag == tag }.takeIf { it >= 0 } ?: 0
    }

    /**
     * Applies a choice. AppCompat recreates the running activities itself, so callers don't need
     * to — and shouldn't, or the recreation happens twice.
     */
    fun select(index: Int) {
        val tag = options.getOrNull(index)?.tag
        AppCompatDelegate.setApplicationLocales(
            if (tag == null) LocaleListCompat.getEmptyLocaleList()
            else LocaleListCompat.forLanguageTags(tag)
        )
    }
}
