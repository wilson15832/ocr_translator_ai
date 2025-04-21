package com.example.ocr_translation

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.ocr_translation.R
import com.example.ocr_translation.AppDatabase
import com.example.ocr_translation.TranslationArea
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.util.Log
import android.graphics.RectF

class AreaSelectionActivity : AppCompatActivity() {
    private lateinit var areaSelectionOverlay: AreaSelectionOverlay
    private lateinit var database: AppDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_area_selection)

        database = AppDatabase.getInstance(this)

        val container = findViewById<FrameLayout>(R.id.area_selection_container)
        areaSelectionOverlay = AreaSelectionOverlay(this)
        container.addView(areaSelectionOverlay, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        findViewById<Button>(R.id.save_area_button).setOnClickListener {
            if (areaSelectionOverlay.selectedRect.width() > 10 && areaSelectionOverlay.selectedRect.height() > 10) {
                showSaveAreaDialog()
            }
        }

        findViewById<Button>(R.id.cancel_button).setOnClickListener {
            finish()
        }
    }

    private fun showSaveAreaDialog() {
        val input = EditText(this).apply {
            hint = "Area name"
        }

        AlertDialog.Builder(this)
            .setTitle("Save Translation Area")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val areaName = input.text.toString().takeIf { it.isNotBlank() } ?: "Area ${System.currentTimeMillis()}"
                saveArea(areaName)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveArea(name: String) {
        val area = TranslationArea.fromRectF(name, areaSelectionOverlay.selectedRect)

        CoroutineScope(Dispatchers.IO).launch {
            val id = database.translationAreaDao().insertArea(area)

            runOnUiThread {
                // Return both the ID and the RectF
                val resultIntent = Intent().apply {
                    putExtra("area_id", id)
                    putExtra("area_rect", areaSelectionOverlay.selectedRect)
                    putExtra("area_name", name)
                }
                setResult(Activity.RESULT_OK, resultIntent)
                finish()
            }
        }
    }
}