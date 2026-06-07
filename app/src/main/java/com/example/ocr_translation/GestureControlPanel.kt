package com.example.ocr_translation

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.cardview.widget.CardView

/**
 * CardView that reports double-taps anywhere on it (including over child buttons)
 * without consuming the touch, so the buttons still receive their normal clicks.
 */
class GestureControlPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : CardView(context, attrs, defStyleAttr) {

    var onDoubleTap: (() -> Unit)? = null

    private val detector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                onDoubleTap?.invoke()
                return true
            }
        }
    )

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        detector.onTouchEvent(ev)        // observe only
        return super.dispatchTouchEvent(ev)  // still route to children
    }
}
