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

    private var startX = 0f
    private var startY = 0f
    private var currentX = 0f
    private var currentY = 0f
    private var isDragging = false

    /** Notified when the user starts (true) / finishes (false) dragging a box. */
    var onDragStateChanged: ((dragging: Boolean) -> Unit)? = null

    val selectedRect: RectF
        get() = RectF(
            minOf(startX, currentX),
            minOf(startY, currentY),
            maxOf(startX, currentX),
            maxOf(startY, currentY)
        )

    /** True once the user has drawn something worth using. */
    private val hasSelection: Boolean
        get() = selectedRect.width() > 10 && selectedRect.height() > 10

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                currentX = event.x
                currentY = event.y
                isDragging = true
                onDragStateChanged?.invoke(true)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    currentX = event.x
                    currentY = event.y
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                isDragging = false
                onDragStateChanged?.invoke(false)
                // Make sure we have minimum dimensions
                if (selectedRect.width() < 10 || selectedRect.height() < 10) {
                    startX = 0f
                    startY = 0f
                    currentX = 0f
                    currentY = 0f
                }
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (!isDragging && !hasSelection) {
            // Nothing drawn yet: dim the whole screen so the instruction bar reads.
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)
            return
        }

        val area = selectedRect
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
}
