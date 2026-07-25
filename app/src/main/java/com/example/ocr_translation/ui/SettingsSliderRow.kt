package com.example.ocr_translation.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import com.example.ocr_translation.R
import com.google.android.material.slider.Slider

/**
 * A grouped-list row whose control is a slider: title and current value share the first line,
 * the slider owns the second.
 *
 * The caller still formats the value string (each setting has its own unit — `1.0x`, `80%`,
 * `24h`), so this exposes [slider] and [valueLabel] rather than trying to own the formatting.
 */
class SettingsSliderRow @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr), SettingsGroup.HairlineInset {

    private val titleView: TextView
    val valueLabel: TextView
    val slider: Slider

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_settings_slider_row, this, true)
        titleView = findViewById(R.id.ssTitleView)
        valueLabel = findViewById(R.id.ssValueView)
        slider = findViewById(R.id.ssSlider)

        val ta = context.obtainStyledAttributes(
            attrs, R.styleable.SettingsSliderRow, defStyleAttr, 0
        )
        try {
            titleView.text = ta.getString(R.styleable.SettingsSliderRow_ssTitle) ?: ""
            val from = ta.getFloat(R.styleable.SettingsSliderRow_ssFrom, 0f)
            val to = ta.getFloat(R.styleable.SettingsSliderRow_ssTo, 1f)
            val step = ta.getFloat(R.styleable.SettingsSliderRow_ssStep, 0f)
            // Order matters: Slider validates value against the range on every setter.
            slider.valueFrom = from
            slider.valueTo = to
            slider.stepSize = step
            slider.value = from
        } finally {
            ta.recycle()
        }

        setPaddingRelative(dp(16), dp(11), dp(16), dp(14))
    }

    var title: CharSequence
        get() = titleView.text
        set(v) {
            titleView.text = v
        }

    /**
     * Assigns a persisted value after clamping it to the range and rounding it onto the step
     * grid. [Slider.setValue] throws for anything off-grid, and a preference written by an older
     * build (or by a slider with a different step) is exactly that — so every load goes through
     * here rather than touching `slider.value` directly.
     */
    fun setSnapped(raw: Float) {
        val clamped = raw.coerceIn(slider.valueFrom, slider.valueTo)
        val step = slider.stepSize
        slider.value = if (step <= 0f) {
            clamped
        } else {
            val steps = Math.round((clamped - slider.valueFrom) / step)
            (slider.valueFrom + steps * step).coerceIn(slider.valueFrom, slider.valueTo)
        }
    }

    override val hairlineInsetStart: Int
        get() = dp(16)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
