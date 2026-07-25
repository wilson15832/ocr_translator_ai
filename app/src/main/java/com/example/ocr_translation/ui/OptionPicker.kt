package com.example.ocr_translation.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import com.example.ocr_translation.R
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * The sheet a [SettingsRow] with a chevron opens: the list of options with a checkmark on the
 * current one.
 *
 * This is what replaces the Spinners — a Spinner renders its own boxed control inside the row,
 * which fights the "label left, value right, chevron" rhythm the rest of the list uses.
 */
object OptionPicker {

    /**
     * @param selectedIndex index into [entries]; anything out of range simply shows no checkmark.
     * @param onPick invoked with the chosen index. Not called when the sheet is dismissed.
     */
    fun show(
        context: Context,
        title: CharSequence,
        entries: List<CharSequence>,
        selectedIndex: Int,
        onPick: (Int) -> Unit
    ) {
        val dialog = BottomSheetDialog(context)
        val content = LayoutInflater.from(context).inflate(R.layout.dialog_option_picker, null)
        content.findViewById<TextView>(R.id.pickerTitle).text = title

        val container = content.findViewById<LinearLayout>(R.id.pickerOptions)
        val inflater = LayoutInflater.from(context)
        var selectedItem: View? = null
        entries.forEachIndexed { index, label ->
            val item = inflater.inflate(R.layout.item_option_picker, container, false)
            item.findViewById<TextView>(R.id.optionLabel).text = label
            val isSelected = index == selectedIndex
            item.findViewById<ImageView>(R.id.optionCheck).visibility =
                if (isSelected) View.VISIBLE else View.INVISIBLE
            if (isSelected) selectedItem = item
            item.setOnClickListener {
                dialog.dismiss()
                onPick(index)
            }
            container.addView(item)
        }

        // Long lists (20 languages, 15 models) would otherwise open at the top with the current
        // choice off-screen.
        val scroll = content.findViewById<NestedScrollView>(R.id.pickerScroll)
        dialog.setOnShowListener {
            selectedItem?.let { item -> scroll.post { scroll.scrollTo(0, item.top) } }
        }

        dialog.setContentView(content)
        dialog.show()
    }
}
