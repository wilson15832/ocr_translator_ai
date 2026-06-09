package com.example.ocr_translation

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.cardview.widget.CardView

/**
 * CardView for the floating translator bar.
 *
 * Two responsibilities:
 *   1. Reports double-taps anywhere on the bar (including over child buttons) without consuming
 *      single taps — so the buttons still receive normal clicks.
 *   2. Owns the drag gesture. The pre-round-4 implementation set [setOnTouchListener] on the
 *      CardView, which only fires on truly-empty regions; with five buttons crammed in, almost no
 *      empty region remained and the user couldn't drag. We now intercept moves once they exceed
 *      the system touch slop, so the user can grab any part of the bar — including a button — and
 *      drag it. A tap that doesn't exceed slop falls through to the button as before.
 */
class GestureControlPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : CardView(context, attrs, defStyleAttr) {

    var onDoubleTap: (() -> Unit)? = null

    /** Called once, when a drag starts (slop exceeded). Receives the original down position. */
    var onDragStart: ((downRawX: Float, downRawY: Float) -> Unit)? = null
    /** Called for each move event during a drag. Receives delta from the down position. */
    var onDragMove: ((dx: Float, dy: Float) -> Unit)? = null
    /** Called once when the user lifts (or cancel). */
    var onDragEnd: (() -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downRawX = 0f
    private var downRawY = 0f
    private var dragging = false

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
        detector.onTouchEvent(ev)            // observe only — does not consume
        return super.dispatchTouchEvent(ev)  // still route to children
    }

    /**
     * Decide whether to steal the gesture from a child (button) when the user starts dragging.
     * - DOWN: record start point, don't intercept yet (let the child see the press feedback)
     * - MOVE: if movement exceeds touchSlop, intercept; from here on the events come to
     *   [onTouchEvent] instead of the child, which will cancel the child's pending click.
     */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = ev.rawX
                downRawY = ev.rawY
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) {
                    val dx = ev.rawX - downRawX
                    val dy = ev.rawY - downRawY
                    if (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop) {
                        dragging = true
                        onDragStart?.invoke(downRawX, downRawY)
                        return true   // steal; subsequent MOVE/UP land in onTouchEvent below
                    }
                }
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (dragging) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    onDragMove?.invoke(event.rawX - downRawX, event.rawY - downRawY)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    onDragEnd?.invoke()
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }
}