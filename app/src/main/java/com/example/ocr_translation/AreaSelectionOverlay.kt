package com.example.ocr_translation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * Area picker, in the photo-crop idiom: corner brackets, a thirds grid, and everything outside
 * dimmed.
 *
 * Borrowed deliberately, because it is the one selection UI every phone user has already met — in
 * the camera roll, in every editor — so nothing about it needs explaining. Its parts also happen to
 * say the right things here: brackets read as grabbable where the previous decorative handles only
 * looked it, and the absence of a drawn frame leaves the scrim's edge to define the boundary, which
 * is a cleaner line than a stroke straddling it.
 *
 * Monochrome rather than accent-tinted. Over an arbitrary game frame a themed outline competes with
 * whatever is behind it, and white on a dimmed surround is legible against all of them.
 */
class AreaSelectionOverlay(context: Context) : View(context) {

    private val density = context.resources.displayMetrics.density

    private val cornerPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
        strokeCap = Paint.Cap.SQUARE
        isAntiAlias = true
    }

    /** Rule-of-thirds guides: present enough to compose against, faint enough to ignore. */
    private val gridPaint = Paint().apply {
        color = Color.parseColor("#40FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
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
        drawThirds(canvas, area)
        drawCornerBrackets(canvas, area)
        drawSizeChip(canvas, area)
    }

    /**
     * Two lines each way, at the thirds.
     *
     * Skipped on a small box: at that size the guides are closer together than the text being
     * framed and read as noise over it rather than as structure.
     */
    private fun drawThirds(canvas: Canvas, area: RectF) {
        if (area.width() < GRID_MIN_DP * density || area.height() < GRID_MIN_DP * density) return
        val thirdW = area.width() / 3f
        val thirdH = area.height() / 3f
        for (i in 1..2) {
            val x = area.left + thirdW * i
            canvas.drawLine(x, area.top, x, area.bottom, gridPaint)
            val y = area.top + thirdH * i
            canvas.drawLine(area.left, y, area.right, y, gridPaint)
        }
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

    /**
     * An L at each corner, square, meeting exactly on the crop edge.
     *
     * The arm shortens on a small box so the four brackets can't grow into one another and close
     * the shape back into the frame this replaced.
     */
    private fun drawCornerBrackets(canvas: Canvas, area: RectF) {
        val arm = minOf(
            ARM_DP * density,
            minOf(area.width(), area.height()) / 3f
        ).coerceAtLeast(1f)
        // Half the stroke sits either side of the path, so the outer edge of each bracket lands on
        // the crop edge rather than straddling it.
        val out = cornerPaint.strokeWidth / 2f
        val l = area.left - out
        val t = area.top - out
        val r = area.right + out
        val b = area.bottom + out

        canvas.drawLine(l, t, l + arm, t, cornerPaint)
        canvas.drawLine(l, t, l, t + arm, cornerPaint)

        canvas.drawLine(r - arm, t, r, t, cornerPaint)
        canvas.drawLine(r, t, r, t + arm, cornerPaint)

        canvas.drawLine(l, b - arm, l, b, cornerPaint)
        canvas.drawLine(l, b, l + arm, b, cornerPaint)

        canvas.drawLine(r - arm, b, r, b, cornerPaint)
        canvas.drawLine(r, b - arm, r, b, cornerPaint)
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
        /** Length of each bracket arm on a box with room for it. */
        const val ARM_DP = 24f

        /** Below this in either direction the thirds grid is left off. */
        const val GRID_MIN_DP = 96f

        /** How near a corner a touch has to land to grab it, rather than move the box. */
        const val HANDLE_TOUCH_DP = 28f

        /** Below this in either direction the box is treated as a stray tap and discarded. */
        const val MIN_SIZE = 10f
    }
}
