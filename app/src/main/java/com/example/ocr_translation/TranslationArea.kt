package com.example.ocr_translation

import android.graphics.RectF
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "translation_areas")
data class TranslationArea(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    fun toRectF(): RectF = RectF(left, top, right, bottom)

    companion object {
        fun fromRectF(name: String, rectF: RectF): TranslationArea {
            return TranslationArea(
                name = name,
                left = rectF.left,
                top = rectF.top,
                right = rectF.right,
                bottom = rectF.bottom
            )
        }
    }
}
