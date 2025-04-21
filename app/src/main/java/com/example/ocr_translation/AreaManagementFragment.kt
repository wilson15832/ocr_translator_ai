package com.example.ocr_translation

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.ocr_translation.R
import com.example.ocr_translation.TranslationAreasAdapter
import com.example.ocr_translation.AreaSelectionActivity
import com.example.ocr_translation.ScreenCaptureService
import com.example.ocr_translation.AppDatabase
import com.example.ocr_translation.TranslationArea
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.Build
import android.util.Log

class AreaManagementFragment : Fragment() {

    private lateinit var areasAdapter: TranslationAreasAdapter
    private lateinit var database: AppDatabase
    private val AREA_SELECTION_REQUEST = 1001

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_area_management, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        database = AppDatabase.getInstance(requireContext())

        // Set up RecyclerView
        val recyclerView = view.findViewById<RecyclerView>(R.id.areas_recycler_view)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        areasAdapter = TranslationAreasAdapter(
            emptyList(),
            onAreaSelected = { area ->
                activateTranslationArea(area)
            },
            onAreaDelete = { area ->
                deleteTranslationArea(area)
            }
        )

        recyclerView.adapter = areasAdapter

        // Set up buttons
        view.findViewById<Button>(R.id.add_area_button).setOnClickListener {
            startAreaSelection()
        }

        view.findViewById<Button>(R.id.clear_area_button).setOnClickListener {
            clearActiveArea()
        }

        // Load saved areas
        loadSavedAreas()
    }

    private fun startAreaSelection() {
        val intent = Intent(requireContext(), AreaSelectionActivity::class.java)
        startActivityForResult(intent, AREA_SELECTION_REQUEST)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == AREA_SELECTION_REQUEST && resultCode == Activity.RESULT_OK) {
            // Refresh the areas list
            loadSavedAreas()

            // Optionally activate the newly created area
            data?.let {
                val areaId = it.getLongExtra("area_id", -1L)
                if (areaId != -1L) {
                    activateTranslationAreaById(areaId)
                    Toast.makeText(
                        requireContext(),
                        "New area activated for translation",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun loadSavedAreas() {
        CoroutineScope(Dispatchers.IO).launch {
            val areas = database.translationAreaDao().getAllAreas()

            withContext(Dispatchers.Main) {
                areasAdapter.updateAreas(areas)
            }
        }
    }

    private fun activateTranslationArea(area: TranslationArea) {
        Log.d("AreaManagementFragment", "activateTranslationArea called with area: ${area.name}") // <-- 添加此日志
        // Assuming TranslationArea has properties like left, top, right, bottom based on DB storage
        Log.d("AreaManagementFragment", "Area DB coords: left=${area.left}, top=${area.top}, right=${area.right}, bottom=${area.bottom}") // <-- 添加此日志 (需要 TranslationArea 类定义)

        val rectF = area.toRectF()
        Log.d("AreaManagementFragment", "Converted to RectF: $rectF")

        //Log.d("AreaManagementFragment", "Sending broadcast to activate area: ${area.name}, bounds: $rectF")

        val serviceIntent = Intent(requireContext(), ScreenCaptureService::class.java).apply {
            action = "com.example.ocr_translation.ACTION_SET_TRANSLATION_AREA"
            putExtra("area_left", rectF.left)
            putExtra("area_top", rectF.top)
            putExtra("area_right", rectF.right)
            putExtra("area_bottom", rectF.bottom)
            putExtra("area_name", area.name)
        }

        // 使用 startService 或 startForegroundService
        // 如果服务是前台服务，应该使用 startForegroundService
        // 请根据你的 ScreenCaptureService 如何启动（是用户手动还是通过 startForegroundService）来选择
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            requireContext().startForegroundService(serviceIntent)
            Log.d("AreaManagementFragment", "Sent explicit FOREGROUND Service intent.")
        } else {
            requireContext().startService(serviceIntent)
            Log.d("AreaManagementFragment", "Sent explicit Service intent.")
        }


        // Send individual float values instead of Parcelable
        /*val intent = Intent("com.example.ocr_translation.ACTION_SET_TRANSLATION_AREA")
        intent.putExtra("area_left", rectF.left)
        intent.putExtra("area_top", rectF.top)
        intent.putExtra("area_right", rectF.right)
        intent.putExtra("area_bottom", rectF.bottom)
        intent.putExtra("area_name", area.name)
        requireContext().sendBroadcast(intent)*/
    }


    private fun activateTranslationAreaById(areaId: Long) {
        CoroutineScope(Dispatchers.IO).launch {
            val area = database.translationAreaDao().getAreaById(areaId)

            withContext(Dispatchers.Main) {
                area?.let {
                    Log.d("AreaManagementFragment", "Calling activateTranslationArea from activateTranslationAreaById for ${it.name}") // <-- 添加此日志
                    activateTranslationArea(it)
                } ?: run {
                    Log.e("AreaManagementFragment", "Area with ID $areaId not found in DB.") // <-- 确认这条日志存在并开启
                }
            }
        }
    }

    private fun deleteTranslationArea(area: TranslationArea) {
        CoroutineScope(Dispatchers.IO).launch {
            database.translationAreaDao().deleteArea(area)

            withContext(Dispatchers.Main) {
                loadSavedAreas()
                Toast.makeText(
                    requireContext(),
                    "Area '${area.name}' deleted",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun clearActiveArea() {
        val intent = Intent("com.example.ocr_translation.ACTION_CLEAR_TRANSLATION_AREA")
        requireContext().sendBroadcast(intent)

        Toast.makeText(
            requireContext(),
            "Using full screen for translation",
            Toast.LENGTH_SHORT
        ).show()
    }
}