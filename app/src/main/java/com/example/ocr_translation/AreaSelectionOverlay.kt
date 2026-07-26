package com.example.ocr_translation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.example.ocr_translation.ui.AppTheme

/**
 * Area picker, design 3f.
 *
 * Everything *outside* the box dims, rather than the box itself being filled with translucent
 * accent. On a bright scene the old translucent-blue-rectangle-on-top read as barely there;
 * inverting it makes the selection unmistakable. Corner handles say the box can be adjusted, and
 * a live size chip confirms what was actually drawn.
 */
class AreaSelectionOverlay(context: Context) : View(context) {

    private val density = context.resources.displayMetrics.density

    private val accent = AppTheme.colorPrimary(context)

    private val framePaint = Paint().apply {
        color = accent
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        isAntiAlias = true
    }

    private val handlePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 5f * density
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    /** Dims the screen outside the selection. */
    private val scrimPaint = Paint().apply {
        color = Color.parseColor("#80000000")
        style = Paint.Style.FILL
    }

    private val chipPaint = Paint().apply {
        color = Color.parseColor("#D91C1C1E")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val chipTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = 11f * density
        isAntiAlias = true
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    /** The live selection, in view coordinates. Normalised: left <= right, top <= bottom. */
    private val selection = RectF()

    /**
     * What the current gesture is doing.
     *
     * Resizing and drawing are the same operation with a different fixed point — a corner drag
     * pins the opposite corner and follows the finger, exactly as drawing pins where the finger
     * went down. Sharing the path also means dragging a corner past its opposite one flips the box
     * the way you'd expect, instead of needing a special case.
     */
    private enum class Grip { NONE, CORNER, MOVE }

    private var grip = Grip.NONE

    /** The point a [Grip.CORNER] drag holds still: the corner opposite the one grabbed. */
    private var anchorX = 0f
    private var anchorY = 0f

    /** Where inside the box a [Grip.MOVE] was grabbed, so it doesn't jump under the finger. */
    private var grabOffsetX = 0f
    private var grabOffsetY = 0f

    /** Notified when the user starts (true) / finishes (false) dragging a box. */
    var onDragStateChanged: ((dragging: Boolean) -> Unit)? = null

    val selectedRect: RectF get() = RectF(selection)

    /**
     * Restores a previously chosen area so it can be adjusted instead of redrawn.
     *
     * Reselecting from scratch was the only way to change the area, which made a small correction —
     * the dialogue box is 20px taller than you thought — as much work as the original selection.
     */
    fun setSelection(rect: RectF?) {
        if (rect == null) selection.setEmpty() else selection.set(rect)
        invalidate()
    }

    /** True once there is something worth using. */
    private val hasSelection: Boolean
        get() = selection.width() > MIN_SIZE && selection.height() > MIN_SIZE

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                beginGesture(event.x, event.y)
                onDragStateChanged?.invoke(true)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                when (grip) {
                    Grip.CORNER -> {
                        selection.set(
                            minOf(anchorX, event.x).coerceAtLeast(0f),
                            minOf(anchorY, event.y).coerceAtLeast(0f),
                            maxOf(anchorX, event.x).coerceAtMost(width.toFloat()),
                            maxOf(anchorY, event.y).coerceAtMost(height.toFloat())
                        )
                    }
                    Grip.MOVE -> {
                        // Positioned from the grab offset rather than accumulated deltas, so
                        // dragging past an edge and back doesn't leave the box lagging the finger.
                        val w = selection.width()
                        val h = selection.height()
                        selection.offsetTo(
                            (event.x - grabOffsetX).coerceIn(0f, (width - w).coerceAtLeast(0f)),
                            (event.y - grabOffsetY).coerceIn(0f, (height - h).coerceAtLeast(0f))
                        )
                    }
                    Grip.NONE -> return true
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                grip = Grip.NONE
                onDragStateChanged?.invoke(false)
                // A tap, or a box too small to be worth anything: drop it rather than leave a
                // sliver behind that the buttons would happily accept.
                if (!hasSelection) selection.setEmpty()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * Decides what a touch does: a corner grabs that corner, anywhere inside moves the whole box,
     * and anywhere outside starts a new one.
     *
     * Corners win over the interior so a small box is still adjustable — its handles would
     * otherwise all be inside it and unreachable.
     */
    private fun beginGesture(x: Float, y: Float) {
        val slop = HANDLE_TOUCH_DP * density
        if (hasSelection) {
            val onLeft = kotlin.math.abs(x - selection.left) <= slop
            val onRight = kotlin.math.abs(x - selection.right) <= slop
            val onTop = kotlin.math.abs(y - selection.top) <= slop
            val onBottom = kotlin.math.abs(y - selection.bottom) <= slop
            if ((onLeft || onRight) && (onTop || onBottom)) {
                grip = Grip.CORNER
                anchorX = if (onLeft) selection.right else selection.left
                anchorY = if (onTop) selection.bottom else selection.top
                return
            }
            if (selection.contains(x, y)) {
                grip = Grip.MOVE
                grabOffsetX = x - selection.left
                grabOffsetY = y - selection.top
                return
            }
        }
        grip = Grip.CORNER
        anchorX = x
        anchorY = y
        selection.set(x, y, x, y)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (grip == Grip.NONE && !hasSelection) {
            // Nothing drawn yet: dim the whole screen so the instruction bar reads.
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)
            return
        }

        val area = selection
        drawScrimAround(canvas, area)

        val radius = 18f * density
        canvas.drawRoundRect(area, radius, radius, framePaint)
        drawCornerHandles(canvas, area)
        drawSizeChip(canvas, area)
    }

    /** Four rects around the selection — cheaper and sharper than a clipped full-screen fill. */
    private fun drawScrimAround(canvas: Canvas, area: RectF) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, area.top, scrimPaint)
        canvas.drawRect(0f, area.bottom, w, h, scrimPaint)
        canvas.drawRect(0f, area.top, area.left, area.bottom, scrimPaint)
        canvas.drawRect(area.right, area.top, w, area.bottom, scrimPaint)
    }

    private fun drawCornerHandles(canvas: Canvas, area: RectF) {
        val arm = 22f * density
        val inset = 1f * density
        // Top-left
        canvas.drawLine(area.left - inset, area.top + arm, area.left - inset, area.top - inset, handlePaint)
        canvas.drawLine(area.left - inset, area.top - inset, area.left + arm, area.top - inset, handlePaint)
        // Top-right
        canvas.drawLine(area.right - arm, area.top - inset, area.right + inset, area.top - inset, handlePaint)
        canvas.drawLine(area.right + inset, area.top - inset, area.right + inset, area.top + arm, handlePaint)
        // Bottom-left
        canvas.drawLine(area.left - inset, area.bottom - arm, area.left - inset, area.bottom + inset, handlePaint)
        canvas.drawLine(area.left - inset, area.bottom + inset, area.left + arm, area.bottom + inset, handlePaint)
        // Bottom-right
        canvas.drawLine(area.right - arm, area.bottom + inset, area.right + inset, area.bottom + inset, handlePaint)
        canvas.drawLine(area.right + inset, area.bottom + inset, area.right + inset, area.bottom - arm, handlePaint)
    }

    /** Live "330 × 130" readout, above the box or tucked inside it when there's no room. */
    private fun drawSizeChip(canvas: Canvas, area: RectF) {
        val label = "${area.width().toInt()} × ${area.height().toInt()}"
        val padH = 9f * density
        val padV = 3f * density
        val textW = chipTextPaint.measureText(label)
        val metrics = chipTextPaint.fontMetrics
        val chipH = (metrics.descent - metrics.ascent) + padV * 2
        val centreX = area.centerX()
        var bottom = area.top - 8f * density
        if (bottom - chipH < 0f) bottom = area.top + chipH + 8f * density
        val chip = RectF(
            centreX - textW / 2 - padH, bottom - chipH,
            centreX + textW / 2 + padH, bottom
        )
        canvas.drawRoundRect(chip, 8f * density, 8f * density, chipPaint)
        canvas.drawText(label, centreX - textW / 2, bottom - padV - metrics.descent, chipTextPaint)
    }

    private companion object {
        /** How near a corner a touch has to land to grab it, rather than move the box. */
        const val HANDLE_TOUCH_DP = 28f

        /** Below this in either direction the box is treated as a stray tap and discarded. */
        const val MIN_SIZE = 10f
    }
}
