package com.example.ocr_translation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

class AreaSelectionOverlay(context: Context) : View(context) {
    private val rectPaint = Paint().apply {
        color = Color.parseColor("#4285F4")
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    private val fillPaint = Paint().apply {
        color = Color.parseColor("#334285F4")
        style = Paint.Style.FILL
    }

    private var startX = 0f
    private var startY = 0f
    private var currentX = 0f
    private var currentY = 0f
    private var isDragging = false

    val selectedRect: RectF
        get() = RectF(
            minOf(startX, currentX),
            minOf(startY, currentY),
            maxOf(startX, currentX),
            maxOf(startY, currentY)
        )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                currentX = event.x
                currentY = event.y
                isDragging = true
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
        if (isDragging || (selectedRect.width() > 10 && selectedRect.height() > 10)) {
            canvas.drawRect(selectedRect, fillPaint)
            canvas.drawRect(selectedRect, rectPaint)
        }
    }
}