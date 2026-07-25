package com.example.ocr_translation.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.example.ocr_translation.R

/**
 * One card of an iOS inset-grouped list: a rounded surface that draws hairline separators
 * between its own children.
 *
 * Doing it here rather than in the layout keeps `activity_settings.xml` free of the ~30
 * hand-placed `<View style="@style/IosHairline">` elements the design would otherwise need,
 * and guarantees every separator has the same thickness and the same inset rule — the
 * separator starts at the leading edge of the row's *text*, not the card, so rows with a
 * glyph tile push it further in. See [HairlineInset].
 */
class SettingsGroup @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    /** Implemented by row types that want the separator above them inset past a leading glyph. */
    interface HairlineInset {
        val hairlineInsetStart: Int
    }

    init {
        orientation = VERTICAL
        background = ContextCompat.getDrawable(context, R.drawable.bg_ios_card)
        // Lets row ripples and backgrounds respect the card's 10dp corners.
        clipToOutline = true
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        insertHairlines()
    }

    private fun insertHairlines() {
        if (childCount < 2) return
        val rows = (0 until childCount).map { getChildAt(it) }
        removeAllViews()
        rows.forEachIndexed { index, row ->
            if (index > 0) addView(makeHairline(insetFor(row)))
            addView(row)
        }
    }

    private fun insetFor(row: View): Int = when (row) {
        is HairlineInset -> row.hairlineInsetStart
        else -> defaultInset
    }

    private fun makeHairline(insetStart: Int) = View(context).apply {
        setBackgroundColor(ContextCompat.getColor(context, R.color.ios_separator))
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, hairlineHeight).apply {
            marginStart = insetStart
        }
    }

    private val defaultInset: Int
        get() = (16 * resources.displayMetrics.density).toInt()

    /** 0.5dp, floored to at least one physical pixel so it never disappears entirely. */
    private val hairlineHeight: Int
        get() = (0.5f * resources.displayMetrics.density).toInt().coerceAtLeast(1)
}
