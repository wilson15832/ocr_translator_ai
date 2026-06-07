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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import android.view.Surface


object OCRProcessor {
    private var recognizer: TextRecognizer = createRecognizer("ja")
    private var currentRotation: Int = Surface.ROTATION_0

    private fun createRecognizer(languageCode: String): TextRecognizer {
        return when (languageCode) {
            "zh" -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            "ja" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            "ko" -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            "hi" -> TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
            else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }
    }

    // Data class to hold text with position information
    data class TextBlock(
        val text: String,
        val boundingBox: Rect,
        val confidence: Float
    )

    fun setScreenRotation(rotation: Int) {
        Log.d("OCRProcessor", "Setting screen rotation to: $rotation")
        currentRotation = rotation
    }


    fun setLanguage(languageCode: String) {
        Log.d("OCRProcessor", "Setting OCR language to: $languageCode") // <<< Add this log
        recognizer.close()
        recognizer = createRecognizer(languageCode)
    }

    /** Clean suspend OCR: returns recognized blocks (empty on failure). */
    suspend fun recognize(bitmap: Bitmap): List<TextBlock> = withContext(Dispatchers.IO) {
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

    // Extract text blocks with position information
    private fun extractTextBlocks(visionText: Text): List<TextBlock> {
        val textBlocks = mutableListOf<TextBlock>()

        for (block in visionText.textBlocks) {
            val boundingBox = block.boundingBox ?: continue

            // Calculate confidence (average of line confidences)
            var totalConfidence = 0f
            var lineCount = 0

            for (line in block.lines) {
                // ML Kit doesn't provide confidence scores directly,
                // but we can estimate based on other factors
                val lineConfidence = 0.8f // Default confidence
                totalConfidence += lineConfidence
                lineCount++
            }

            val avgConfidence = if (lineCount > 0) totalConfidence / lineCount else 0.5f

            textBlocks.add(
                TextBlock(
                    text = block.text,
                    boundingBox = boundingBox,
                    confidence = avgConfidence
                )
            )
        }

        return textBlocks
    }

    fun cleanup() {
        recognizer.close()
    }

}