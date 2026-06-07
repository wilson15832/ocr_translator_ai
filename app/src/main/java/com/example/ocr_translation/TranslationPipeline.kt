package com.example.ocr_translation

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log

/**
 * One translation tick: crop → OCR → change-detection → translate → emit.
 *
 * Owns the lifecycle of the [Bitmap] it is handed and always recycles it.
 * Pure orchestration — no Android service or window concerns.
 */
class TranslationPipeline(
    private val translator: TranslationService,
    private val changeDetector: ChangeDetector = ChangeDetector(),
    private val onResult: (List<TranslationService.TranslatedBlock>) -> Unit
) {

    suspend fun process(
        frame: Bitmap,
        area: RectF?,
        sourceLanguage: String,
        targetLanguage: String,
        force: Boolean = false
    ) {
        var cropped: Bitmap? = null
        try {
            OCRProcessor.setLanguage(sourceLanguage)
            cropped = if (area != null) (cropToArea(frame, area) ?: frame) else frame

            val blocks = OCRProcessor.recognize(cropped)

            if (blocks.isEmpty()) {
                changeDetector.reset()
                onResult(emptyList())
                return
            }
            val changed = changeDetector.hasChanged(blocks)
            if (!force && !changed) {
                Log.d(TAG, "Screen text ~unchanged — skipping translation request")
                return
            }

            val results = translator.translateText(blocks, sourceLanguage, targetLanguage)
            // Sample each block's original background colour (for in-place mode) and, when a crop
            // area is active, offset positions to absolute screen coords.
            val bmp = cropped ?: frame
            val dx = area?.left?.toInt() ?: 0
            val dy = area?.top?.toInt() ?: 0
            val finalResults = results.map { block ->
                val bg = sampleBackgroundColor(bmp, block.boundingBox)
                val screenRect = Rect(block.boundingBox).apply { offset(dx, dy) }
                block.copy(boundingBox = screenRect, bgColor = bg)
            }
            Log.d(TAG, "process: area=$area blocks=${finalResults.size} firstRect=${finalResults.firstOrNull()?.boundingBox}")
            onResult(finalResults)
        } catch (e: Exception) {
            Log.e(TAG, "Pipeline failed", e)
            onResult(
                listOf(
                    TranslationService.TranslatedBlock(
                        originalText = "Error",
                        translatedText = "Translation failed: ${e.message ?: "Unknown error"}",
                        boundingBox = Rect(50, 50, 800, 150),
                        sourceLanguage = sourceLanguage,
                        targetLanguage = targetLanguage
                    )
                )
            )
        } finally {
            if (cropped != null && cropped != frame) recycle(cropped)
            recycle(frame)
        }
    }

    /**
     * Accessibility path: translate pre-extracted text [blocks] whose [boundingBox]es are already
     * absolute screen coordinates. Skips crop/OCR. [sampleFrame] (optional) is used only to sample
     * each block's background colour for in-place rendering; it is recycled here.
     */
    suspend fun processBlocks(
        blocks: List<OCRProcessor.TextBlock>,
        sourceLanguage: String,
        targetLanguage: String,
        force: Boolean = false,
        sampleFrame: Bitmap? = null
    ) {
        try {
            if (blocks.isEmpty()) {
                changeDetector.reset()
                onResult(emptyList())
                return
            }
            val changed = changeDetector.hasChanged(blocks)
            if (!force && !changed) {
                Log.d(TAG, "Accessibility text ~unchanged — skipping translation request")
                return
            }
            val results = translator.translateText(blocks, sourceLanguage, targetLanguage)
            val finalResults = results.map { block ->
                val bg = if (sampleFrame != null && !sampleFrame.isRecycled)
                    sampleBackgroundColor(sampleFrame, block.boundingBox) else 0
                block.copy(bgColor = bg)   // bounds are already absolute — no offset needed
            }
            Log.d(TAG, "processBlocks: blocks=${finalResults.size} firstRect=${finalResults.firstOrNull()?.boundingBox}")
            onResult(finalResults)
        } catch (e: Exception) {
            Log.e(TAG, "Accessibility pipeline failed", e)
            onResult(
                listOf(
                    TranslationService.TranslatedBlock(
                        originalText = "Error",
                        translatedText = "Translation failed: ${e.message ?: "Unknown error"}",
                        boundingBox = Rect(50, 50, 800, 150),
                        sourceLanguage = sourceLanguage,
                        targetLanguage = targetLanguage
                    )
                )
            )
        } finally {
            recycle(sampleFrame)
        }
    }

    /** Average colour of the top/bottom edges of [rect] — approximates the text's background. */
    private fun sampleBackgroundColor(bmp: Bitmap, rect: Rect): Int {
        if (bmp.isRecycled) return 0
        val xs = intArrayOf(rect.left + 1, rect.centerX(), rect.right - 1)
        val ys = intArrayOf(rect.top + 1, rect.bottom - 1)
        var r = 0; var g = 0; var b = 0; var n = 0
        for (x in xs) for (y in ys) {
            if (x in 0 until bmp.width && y in 0 until bmp.height) {
                val c = bmp.getPixel(x, y)
                r += Color.red(c); g += Color.green(c); b += Color.blue(c); n++
            }
        }
        return if (n == 0) 0 else Color.rgb(r / n, g / n, b / n)
    }

    /** OCR-only: returns a normalized text signature of the (optionally cropped) frame. Recycles [frame]. */
    suspend fun ocrSignature(frame: Bitmap, area: RectF?, sourceLanguage: String): String {
        var cropped: Bitmap? = null
        return try {
            OCRProcessor.setLanguage(sourceLanguage)
            cropped = if (area != null) (cropToArea(frame, area) ?: frame) else frame
            OCRProcessor.recognize(cropped)
                .joinToString("|") { it.text }
                .lowercase()
                .replace("\\s+".toRegex(), "")
        } catch (e: Exception) {
            Log.e(TAG, "ocrSignature failed", e)
            ""
        } finally {
            if (cropped != null && cropped != frame) recycle(cropped)
            recycle(frame)
        }
    }

    private fun recycle(b: Bitmap?) {
        if (b != null && !b.isRecycled) {
            try { b.recycle() } catch (_: Exception) {}
        }
    }

    /** Crops [bitmap] to [area], clamped to bounds. Returns null if the region is invalid/too small. */
    private fun cropToArea(bitmap: Bitmap, area: RectF): Bitmap? {
        if (bitmap.isRecycled) return null
        val screen = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        val valid = RectF(
            area.left.coerceIn(screen.left, screen.right),
            area.top.coerceIn(screen.top, screen.bottom),
            area.right.coerceIn(screen.left, screen.right),
            area.bottom.coerceIn(screen.top, screen.bottom)
        )
        if (valid.width() <= 10 || valid.height() <= 10) return null
        val r = Rect(valid.left.toInt(), valid.top.toInt(), valid.right.toInt(), valid.bottom.toInt())
        if (r.left < 0 || r.top < 0 || r.right > bitmap.width || r.bottom > bitmap.height ||
            r.width() <= 0 || r.height() <= 0
        ) return null
        return try {
            Bitmap.createBitmap(bitmap, r.left, r.top, r.width(), r.height())
        } catch (e: Exception) {
            Log.e(TAG, "Crop failed", e)
            null
        }
    }

    companion object {
        private const val TAG = "TranslationPipeline"
    }
}
