package com.example.ocr_translation.ui

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.example.ocr_translation.R
import com.example.ocr_translation.ui.AppTheme

/**
 * A single 44dp row of an iOS grouped list: 17sp label on the left, an optional grey value
 * and disclosure chevron (or a toggle) on the right, and an optional 29dp glyph tile.
 *
 * Replaces the label-plus-Spinner and label-plus-Switch [LinearLayout] pairs the two settings
 * screens used to repeat, so row height, type size, tracking and the switch style are defined
 * once here rather than per row.
 *
 * Configure via `app:srTitle` / `srSubtitle` / `srValue` / `srIcon` / `srAccessory` / `srRole`.
 */
class SettingsRow @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr), SettingsGroup.HairlineInset {

    private val iconTile: FrameLayout
    private val iconView: ImageView
    private val titleView: TextView
    private val subtitleView: TextView
    private val valueView: TextView
    private val chevron: ImageView

    /** Present only when `app:srAccessory="toggle"`. Tapping anywhere on the row toggles it. */
    val switch: SwitchCompat

    // Declared above `init` so the initializer below can assign through them.
    var title: CharSequence
        get() = titleView.text
        set(v) {
            titleView.text = v
        }

    var subtitle: CharSequence?
        get() = subtitleView.text
        set(v) {
            subtitleView.text = v ?: ""
            subtitleView.visibility = if (v.isNullOrEmpty()) View.GONE else View.VISIBLE
            applyPadding()
        }

    /** Right-hand grey value, e.g. the currently selected language. Null hides it. */
    var value: CharSequence?
        get() = valueView.text
        set(v) {
            valueView.text = v ?: ""
            valueView.visibility = if (v.isNullOrEmpty()) View.GONE else View.VISIBLE
        }

    var isChecked: Boolean
        get() = switch.isChecked
        set(v) {
            switch.isChecked = v
        }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_settings_row, this, true)

        iconTile = findViewById(R.id.srIconTile)
        iconView = findViewById(R.id.srIconView)
        titleView = findViewById(R.id.srTitleView)
        subtitleView = findViewById(R.id.srSubtitleView)
        valueView = findViewById(R.id.srValueView)
        chevron = findViewById(R.id.srChevron)
        switch = findViewById(R.id.srSwitch)

        val ta = context.obtainStyledAttributes(attrs, R.styleable.SettingsRow, defStyleAttr, 0)
        try {
            title = ta.getString(R.styleable.SettingsRow_srTitle) ?: ""
            subtitle = ta.getString(R.styleable.SettingsRow_srSubtitle)
            value = ta.getString(R.styleable.SettingsRow_srValue)

            val iconRes = ta.getResourceId(R.styleable.SettingsRow_srIcon, 0)
            if (iconRes != 0) {
                iconView.setImageResource(iconRes)
                iconTile.visibility = View.VISIBLE
                // The tile carries the accent unless the row names its own colour, so the
                // default has to come from the theme rather than @color/primary.
                val tint = ta.getColor(
                    R.styleable.SettingsRow_srIconBackground,
                    themeColorPrimary()
                )
                iconTile.backgroundTintList = android.content.res.ColorStateList.valueOf(tint)
                // The glyph has to react to the tile it sits on: an accent tile can be light
                // (orange, green) while the Overlay Settings tile is always dark grey.
                iconView.imageTintList = android.content.res.ColorStateList.valueOf(
                    AppTheme.contrastOn(tint)
                )
            }

            val textSize = ta.getDimensionPixelSize(R.styleable.SettingsRow_srTextSize, 0)
            if (textSize > 0) {
                titleView.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize.toFloat())
                valueView.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize.toFloat())
            }

            val accessory = ta.getInt(R.styleable.SettingsRow_srAccessory, ACCESSORY_NONE)
            val role = ta.getInt(R.styleable.SettingsRow_srRole, ROLE_NORMAL)
            val centerTitle = ta.getBoolean(
                R.styleable.SettingsRow_srCenterTitle,
                role != ROLE_NORMAL
            )
            applyAccessory(accessory)
            applyRole(role)
            // The text column already spans the row's width, so gravity is enough here.
            if (centerTitle) titleView.gravity = Gravity.CENTER

            val titleIcon = ta.getResourceId(R.styleable.SettingsRow_srTitleIcon, 0)
            if (titleIcon != 0) {
                titleView.setCompoundDrawablesRelativeWithIntrinsicBounds(titleIcon, 0, 0, 0)
                titleView.compoundDrawablePadding = dp(6)
                androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(
                    titleView,
                    android.content.res.ColorStateList.valueOf(titleView.currentTextColor)
                )
            }
        } finally {
            ta.recycle()
        }

        applyPadding()
    }

    private fun applyAccessory(accessory: Int) = when (accessory) {
        ACCESSORY_CHEVRON -> {
            chevron.visibility = View.VISIBLE
            // iOS highlights a disclosure row on press; a toggle row does not.
            setBackgroundResource(selectableItemBackground())
            isClickable = true
            isFocusable = true
        }

        ACCESSORY_TOGGLE -> {
            switch.visibility = View.VISIBLE
            isClickable = true
            isFocusable = true
            setOnClickListener { switch.toggle() }
        }

        else -> Unit
    }

    private fun applyRole(role: Int) {
        when (role) {
            ROLE_ACCENT -> titleView.setTextColor(themeColorPrimary())
            ROLE_DESTRUCTIVE ->
                titleView.setTextColor(ContextCompat.getColor(context, R.color.ios_destructive))
        }
        if (role != ROLE_NORMAL) {
            setBackgroundResource(selectableItemBackground())
            isClickable = true
            isFocusable = true
        }
    }

    /**
     * 44dp for a plain row, 58dp once a subtitle is showing — the two heights the design uses.
     * Vertical padding rather than a fixed height so a wrapped label can still grow the row.
     */
    private fun applyPadding() {
        val h = dp(16)
        val v = if (subtitleView.visibility == View.VISIBLE) dp(11) else dp(9)
        setPaddingRelative(h, v, h, v)
        minimumHeight = if (subtitleView.visibility == View.VISIBLE) dp(58) else dp(44)
    }

    override val hairlineInsetStart: Int
        get() = if (iconTile.visibility == View.VISIBLE) dp(57) else dp(16)

    private fun selectableItemBackground(): Int {
        val out = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
        return out.resourceId
    }

    /** Reads the live accent, which a ThemeOverlay may have moved off @color/primary. */
    private fun themeColorPrimary(): Int {
        val out = TypedValue()
        context.theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, out, true)
        return if (out.resourceId != 0) ContextCompat.getColor(context, out.resourceId) else out.data
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val ACCESSORY_NONE = 0
        const val ACCESSORY_CHEVRON = 1
        const val ACCESSORY_TOGGLE = 2

        const val ROLE_NORMAL = 0
        const val ROLE_ACCENT = 1
        const val ROLE_DESTRUCTIVE = 2
    }
}
