package com.example.ocr_translation.ui

import android.app.Activity
import android.content.Context
import android.util.TypedValue
import androidx.annotation.ColorRes
import androidx.annotation.StyleRes
import com.example.ocr_translation.PreferencesManager
import com.example.ocr_translation.R

/**
 * The app's accent colour, chosen from the swatch row on the main screen.
 *
 * Switching accents at runtime works by applying one of the
 * `ThemeOverlay.ScreenTranslator.Accent.*` styles over the activity's theme, which means
 * anything tinted by the accent has to resolve `?attr/colorPrimary` rather than reference
 * `@color/primary` directly — that includes the shape drawables (`bg_accent_dot`,
 * `bg_glyph_tile`) and the two settings screens' nav-bar text.
 */
object AppTheme {

    /** @param colorRes has a values-night variant, so the same accent reads correctly on black. */
    data class Accent(@ColorRes val colorRes: Int, @StyleRes val overlayRes: Int)

    val accents: List<Accent> = listOf(
        Accent(R.color.accent_blue, R.style.ThemeOverlay_ScreenTranslator_Accent_Blue),
        Accent(R.color.accent_purple, R.style.ThemeOverlay_ScreenTranslator_Accent_Purple),
        Accent(R.color.accent_green, R.style.ThemeOverlay_ScreenTranslator_Accent_Green),
        Accent(R.color.accent_orange, R.style.ThemeOverlay_ScreenTranslator_Accent_Orange),
        Accent(R.color.accent_pink, R.style.ThemeOverlay_ScreenTranslator_Accent_Pink),
        Accent(R.color.accent_graphite, R.style.ThemeOverlay_ScreenTranslator_Accent_Graphite)
    )

    fun selectedIndex(context: Context): Int =
        PreferencesManager.getInstance(context).accentIndex.coerceIn(accents.indices)

    /**
     * Must run before `setContentView` — inflation resolves `?attr/colorPrimary` eagerly, so an
     * overlay applied afterwards would leave already-inflated views on the old accent.
     */
    fun applyTo(activity: Activity) {
        activity.theme.applyStyle(accents[selectedIndex(activity)].overlayRes, true)
    }

    /** Persists a new accent. The caller is expected to `recreate()` to repaint. */
    fun select(context: Context, index: Int) {
        PreferencesManager.getInstance(context).accentIndex = index.coerceIn(accents.indices)
    }

    fun colorPrimary(context: Context): Int =
        resolveAttr(context, androidx.appcompat.R.attr.colorPrimary)

    fun colorPrimaryVariant(context: Context): Int =
        resolveAttr(context, com.google.android.material.R.attr.colorPrimaryVariant)

    /**
     * Black or white, whichever is legible on [background]. Used for glyphs sitting on a tile
     * whose colour isn't known at build time — the accent tiles follow the theme, while the
     * Overlay Settings tile stays grey, and a single hard-coded tint can't serve both.
     */
    fun contrastOn(background: Int): Int =
        if (androidx.core.graphics.ColorUtils.calculateLuminance(background) > 0.45) {
            android.graphics.Color.parseColor("#1A1A1A")
        } else {
            android.graphics.Color.WHITE
        }

    private fun resolveAttr(context: Context, attr: Int): Int {
        val out = TypedValue()
        context.theme.resolveAttribute(attr, out, true)
        return if (out.resourceId != 0) {
            androidx.core.content.ContextCompat.getColor(context, out.resourceId)
        } else {
            out.data
        }
    }
}
