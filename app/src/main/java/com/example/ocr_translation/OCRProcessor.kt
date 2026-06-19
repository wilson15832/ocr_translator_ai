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
        val confidence: Float = 1f
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
        val out = ArrayList<TextBlock>(visionText.textBlocks.size)
        for (block in visionText.textBlocks) {
            val boundingBox = block.boundingBox ?: continue
            out.add(TextBlock(text = block.text, boundingBox = boundingBox))
        }
        return out
    }

//    suspend fun cleanup() {
//        recognizerLock.withLock {
//            try { recognizer.close() } catch (e: Exception) { Log.w("OCRProcessor", "cleanup close() failed", e) }
//        }
//    }
}