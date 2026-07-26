package com.example.ocr_translation

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.hypot

import android.util.Log

object OCRProcessor {

    // ML Kit's TextRecognizer is not safe to close while another coroutine is calling process().
    // The mutex serializes recognize/setLanguage/cleanup, and we track the current language so we
    // only rebuild the recognizer when it actually changes.
    private val recognizerLock = Mutex()
    private var currentLanguage: String = "ja"
    private var recognizer: TextRecognizer = createRecognizer(currentLanguage)

    private fun createRecognizer(languageCode: String): TextRecognizer {
        return when (languageCode) {
            "zh" -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            "ja" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            "ko" -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            "hi" -> TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
            else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }
    }

    // ML Kit doesn't expose per-block confidence scores, so [confidence] is informational only and
    // defaults to 1f. The accessibility path also reports 1f to match.
    data class TextBlock(
        val text: String,
        val boundingBox: Rect,
        val confidence: Float = 1f,
        /**
         * The line's own height, measured across its quad rather than across [boundingBox].
         *
         * They differ whenever ML Kit reads the line as slightly rotated: `boundingBox` is the
         * axis-aligned box around a quad that need not be axis-aligned, so its height picks up
         * `width * sin(angle)` on top of the real one. Over a line several hundred pixels wide, a
         * degree or two of estimated skew is worth tens of pixels — enough to make one line of a
         * paragraph look half again as tall as its neighbours when nothing about the type differs.
         *
         * 0 when unknown; callers fall back to the bounding box.
         */
        val lineHeight: Int = 0
    )

    suspend fun setLanguage(languageCode: String) {
        recognizerLock.withLock {
            if (languageCode == currentLanguage) return@withLock
            Log.d("OCRProcessor", "Switching OCR language: $currentLanguage -> $languageCode")
            try { recognizer.close() } catch (e: Exception) { Log.w("OCRProcessor", "close() failed", e) }
            recognizer = createRecognizer(languageCode)
            currentLanguage = languageCode
        }
    }

    /** Clean suspend OCR: returns recognized blocks (empty on failure). */
    suspend fun recognize(bitmap: Bitmap): List<TextBlock> = withContext(Dispatchers.IO) {
        // Holding the lock for the duration of the recognition guarantees the recognizer isn't
        // closed by a concurrent setLanguage() call while ML Kit is still consuming it.
        recognizerLock.withLock {
            // The MediaProjection mirror is already upright in the capture buffer, so no rotation hint.
            val image = InputImage.fromBitmap(bitmap, 0)
            suspendCancellableCoroutine { cont ->
                recognizer.process(image)
                    .addOnSuccessListener { visionText -> cont.resume(extractTextBlocks(visionText)) }
                    .addOnFailureListener { e ->
                        Log.e("OCRProcessor", "Text recognition failed", e)
                        cont.resume(emptyList())
                    }
            }
        }
    }

    private fun extractTextBlocks(visionText: Text): List<TextBlock> {
        val out = ArrayList<TextBlock>()
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val bb = line.boundingBox ?: continue
                out.add(
                    TextBlock(
                        text = line.text,
                        boundingBox = bb,
                        lineHeight = quadHeight(line.cornerPoints, bb.height())
                    )
                )
            }
        }
        return out
    }

    /**
     * Height of the line across its own quad: the mean of the two side edges.
     *
     * [Text.Line.cornerPoints] comes back in reading order — top-left, top-right, bottom-right,
     * bottom-left — rotated with the text, so the left and right edges are the line's height no
     * matter how it is oriented, where the bounding box's height is only the height when the line
     * happens to be level.
     *
     * Falls back to [fallback] if the points are missing or come out implausible; the measurement
     * can only ever be shorter than the axis-aligned box, so a larger one means something is not
     * what this assumes and the box is the safer answer.
     */
    private fun quadHeight(corners: Array<android.graphics.Point>?, fallback: Int): Int {
        if (corners == null || corners.size < 4) return fallback
        val left = hypot(
            (corners[3].x - corners[0].x).toDouble(), (corners[3].y - corners[0].y).toDouble()
        )
        val right = hypot(
            (corners[2].x - corners[1].x).toDouble(), (corners[2].y - corners[1].y).toDouble()
        )
        val height = ((left + right) / 2.0).toInt()
        return if (height in 1..fallback) height else fallback
    }

//    suspend fun cleanup() {
//        recognizerLock.withLock {
//            try { recognizer.close() } catch (e: Exception) { Log.w("OCRProcessor", "cleanup close() failed", e) }
//        }
//    }
}