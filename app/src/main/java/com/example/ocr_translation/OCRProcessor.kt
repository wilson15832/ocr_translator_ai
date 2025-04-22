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

    private fun estimateConfidence(line: Text.Line): Float {
        // Factors that might indicate higher confidence:
        // - More elements/symbols in a line
        // - Clearer/larger text (can check height of bounding box)
        val elements = line.elements.size
        val boundingBoxArea = line.boundingBox?.let { it.width() * it.height() } ?: 0

        // Simple heuristic
        return when {
            elements > 5 && boundingBoxArea > 5000 -> 0.9f
            elements > 3 && boundingBoxArea > 2000 -> 0.8f
            elements > 1 -> 0.7f
            else -> 0.5f
        }
    }


    // Process image and extract text with layout information
    suspend fun processImage(bitmap: Bitmap, callback: (List<TextBlock>) -> Unit) {
        withContext(Dispatchers.IO) {
            try {
                // Pass the rotation to MLKit

                val rotation = when (currentRotation) {
                    Surface.ROTATION_0 -> 0
                    Surface.ROTATION_90 -> 90
                    Surface.ROTATION_180 -> 180
                    Surface.ROTATION_270 -> 270
                    else -> 0
                }

                Log.d("OCRProcessor", "Processing image with rotation: $rotation, bitmap: $bitmap")
                val image = InputImage.fromBitmap(bitmap, rotation)

                Log.d("OCRProcessor", "Processing image with rotation: $rotation")

                recognizer.process(image)
                    .addOnSuccessListener { visionText ->
                        val textBlocks = extractTextBlocks(visionText)
                        Log.d("OCRProcessor", "OCR successful, found ${textBlocks.size} blocks")
                        callback(textBlocks)
                    }
                    .addOnFailureListener { e ->
                        Log.e("OCRProcessor", "Text recognition failed", e)
                        callback(emptyList())
                    }
            } catch (e: Exception) {
                Log.e("OCRProcessor", "Error processing image", e)
                callback(emptyList())
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

    // Helper function to preprocess image if needed (resize, enhance contrast)
    fun preprocessImage(bitmap: Bitmap): Bitmap {
        // Create a mutable copy of the bitmap
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)

        // Apply preprocessing
        val canvas = android.graphics.Canvas(result)
        val paint = android.graphics.Paint()

        // Increase contrast
        val contrast = 1.5f  // Contrast factor (1.0 = no change)
        val brightness = 0f  // Brightness adjustment

        val colorMatrix = android.graphics.ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, brightness,
            0f, contrast, 0f, 0f, brightness,
            0f, 0f, contrast, 0f, brightness,
            0f, 0f, 0f, 1f, 0f
        ))

        paint.colorFilter = android.graphics.ColorMatrixColorFilter(colorMatrix)
        canvas.drawBitmap(result, 0f, 0f, paint)

        return result
    }

    fun cleanup() {
        recognizer.close()
    }

    fun mergeRelatedBlocks(blocks: List<TextBlock>, maxDistance: Int = 50): List<TextBlock> {
        if (blocks.size <= 1) return blocks

        val result = mutableListOf<TextBlock>()
        val processed = mutableSetOf<Int>()

        for (i in blocks.indices) {
            if (i in processed) continue

            val block = blocks[i]
            val mergedText = StringBuilder(block.text)
            var mergedBox = Rect(block.boundingBox)
            var totalConfidence = block.confidence
            var count = 1

            for (j in i + 1 until blocks.size) {
                if (j in processed) continue

                val nextBlock = blocks[j]
                if (areBlocksRelated(block.boundingBox, nextBlock.boundingBox, maxDistance)) {
                    mergedText.append(" ").append(nextBlock.text)
                    mergedBox.union(nextBlock.boundingBox)
                    totalConfidence += nextBlock.confidence
                    count++
                    processed.add(j)
                }
            }

            result.add(TextBlock(
                text = mergedText.toString(),
                boundingBox = mergedBox,
                confidence = totalConfidence / count
            ))

            processed.add(i)
        }

        return result
    }

    private fun areBlocksRelated(rect1: Rect, rect2: Rect, maxDistance: Int): Boolean {
        // Check if blocks are horizontally or vertically adjacent
        val horizontalOverlap = rect1.left <= rect2.right && rect2.left <= rect1.right
        val verticalOverlap = rect1.top <= rect2.bottom && rect2.top <= rect1.bottom

        // Check distance between blocks
        val horizontalDistance = if (horizontalOverlap) 0 else
            Math.min(Math.abs(rect1.right - rect2.left), Math.abs(rect2.right - rect1.left))
        val verticalDistance = if (verticalOverlap) 0 else
            Math.min(Math.abs(rect1.bottom - rect2.top), Math.abs(rect2.bottom - rect1.top))

        // Consider blocks related if they overlap in one dimension and are close in the other
        return (horizontalOverlap && verticalDistance <= maxDistance) ||
                (verticalOverlap && horizontalDistance <= maxDistance)
    }

}