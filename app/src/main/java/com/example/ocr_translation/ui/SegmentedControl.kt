package com.example.ocr_translation.ui

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.ocr_translation.R

/**
 * iOS segmented control: a pill track with one raised thumb.
 *
 * Used where a Spinner held only a handful of mutually exclusive options and the current
 * choice matters at a glance — the LLM provider (4 options) and the control panel's
 * orientation (2). Longer lists stay as picker rows, since a segment per language would not
 * fit.
 */
class SegmentedControl @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr), SettingsGroup.HairlineInset {

    /** Fired only for user taps, not for [setSelectionSilently]. */
    var onSelected: ((Int) -> Unit)? = null

    private var entries: List<String> = emptyList()

    /** See `app:scFill`. Weighted segments measure to zero inside a wrap_content parent. */
    private var fill = true

    var selectedIndex: Int = 0
        private set

    init {
        orientation = HORIZONTAL
        background = ContextCompat.getDrawable(context, R.drawable.bg_segment_track)
        setPadding(dp(2), dp(2), dp(2), dp(2))

        val ta = context.obtainStyledAttributes(
            attrs, R.styleable.SegmentedControl, defStyleAttr, 0
        )
        try {
            fill = ta.getBoolean(R.styleable.SegmentedControl_scFill, true)
            val arrayRes = ta.getResourceId(R.styleable.SegmentedControl_scEntries, 0)
            if (arrayRes != 0) setEntries(resources.getStringArray(arrayRes).toList())
        } finally {
            ta.recycle()
        }
    }

    fun setEntries(newEntries: List<String>) {
        entries = newEntries
        removeAllViews()
        entries.forEachIndexed { index, label ->
            addView(makeSegment(label, index))
        }
        selectedIndex = selectedIndex.coerceIn(0, (entries.size - 1).coerceAtLeast(0))
        refresh()
    }

    /** Applies a selection without notifying [onSelected] — for loading persisted state. */
    fun setSelectionSilently(index: Int) {
        if (entries.isEmpty()) return
        selectedIndex = index.coerceIn(0, entries.size - 1)
        refresh()
    }

    private fun makeSegment(label: String, index: Int) = TextView(context).apply {
        text = label
        textSize = 13f
        letterSpacing = -0.006f
        gravity = Gravity.CENTER
        val padH = if (fill) dp(6) else dp(14)
        setPadding(padH, dp(6), padH, dp(6))
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        layoutParams = if (fill) {
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        } else {
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        }.apply {
            if (index > 0) marginStart = dp(2)
        }
        setOnClickListener {
            if (selectedIndex == index) return@setOnClickListener
            selectedIndex = index
            refresh()
            onSelected?.invoke(index)
        }
    }

    private fun refresh() {
        for (i in 0 until childCount) {
            val segment = getChildAt(i) as TextView
            val selected = i == selectedIndex
            segment.background =
                if (selected) ContextCompat.getDrawable(context, R.drawable.bg_segment_thumb)
                else null
            segment.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
            segment.setTextColor(
                ContextCompat.getColor(
                    context,
                    if (selected) R.color.ios_label else R.color.ios_label_secondary
                )
            )
        }
    }

    override val hairlineInsetStart: Int
        get() = dp(16)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
