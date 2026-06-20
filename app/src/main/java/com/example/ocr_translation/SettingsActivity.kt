package com.example.ocr_translation

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.ocr_translation.databinding.ActivitySettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.util.Log
import java.io.File

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var preferencesManager: PreferencesManager

    // Folder picker (SAF) for "save translations to file" — returns a tree URI that we persist
    // permission for so the ScreenCaptureService can write to it across process restarts.
    private lateinit var pickSaveFolder: ActivityResultLauncher<Uri?>

    // File picker for a user .ttf/.otf font; we copy it into filesDir so the path stays valid
    // across app updates and the file content URI doesn't expire.
    private lateinit var pickCustomFont: ActivityResultLauncher<Array<String>>

    private var currentProvider = LlmProvider.CHATGPT
    private var currentCodes: List<String> = emptyList()   // 当前公司的模型码（save 时按下标取）

    private fun populateModels(provider: LlmProvider, selectCode: String? = null) {
        val names = resources.getStringArray(R.array.models)
        val codes = resources.getStringArray(R.array.model_codes)
        val idx = codes.indices.filter { LlmProvider.fromModel(codes[it]) == provider }
        currentCodes = idx.map { codes[it] }
        binding.spinnerModel.adapter = android.widget.ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, idx.map { names[it] })
        val sel = selectCode?.let { currentCodes.indexOf(it) }?.takeIf { it >= 0 } ?: 0
        binding.spinnerModel.setSelection(sel)
    }
    companion object {
        private const val TAG = "SettingsActivity"
        const val EXTRA_SECTION = "section"
        const val SECTION_TRANSLATION = "translation"
        const val SECTION_OVERLAY = "overlay"
        // File name inside filesDir/ where we keep the user-loaded font.
        private const val CUSTOM_FONT_FILENAME = "custom_font.ttf"
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Set up toolbar with back button
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Register the SAF launchers before any UI wiring touches them.
        pickSaveFolder = registerForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
        ) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                // Persist permission so the service can write across reboots.
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                contentResolver.takePersistableUriPermission(uri, flags)
                preferencesManager.saveFolderUri = uri.toString()
                refreshSaveFolderLabel()
                Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
            } catch (e: SecurityException) {
                Log.e(TAG, "takePersistableUriPermission failed", e)
                Toast.makeText(this, R.string.save_folder_unset, Toast.LENGTH_LONG).show()
            }
        }

        pickCustomFont = registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri == null) return@registerForActivityResult
            lifecycleScope.launch {
                val ok = copyFontToFilesDir(uri)
                if (ok) {
                    refreshCustomFontLabel()
                    Toast.makeText(this@SettingsActivity, R.string.custom_font_loaded, Toast.LENGTH_SHORT).show()
                    // Apply immediately so the user sees the new font in the next translation
                    updateActiveServices()
                } else {
                    Toast.makeText(this@SettingsActivity, R.string.custom_font_load_failed, Toast.LENGTH_LONG).show()
                }
            }
        }

        // Show only the requested group (homepage opens one or the other)
        when (intent.getStringExtra(EXTRA_SECTION)) {
            SECTION_OVERLAY -> {
                binding.groupTranslation.visibility = android.view.View.GONE
                binding.groupOverlay.visibility = android.view.View.VISIBLE
                supportActionBar?.setTitle(R.string.display_settings)
            }
            SECTION_TRANSLATION -> {
                binding.groupTranslation.visibility = android.view.View.VISIBLE
                binding.groupOverlay.visibility = android.view.View.GONE
                supportActionBar?.setTitle(R.string.translation_settings)
            }
            else -> {  // no section specified: show everything
                binding.groupTranslation.visibility = android.view.View.VISIBLE
                binding.groupOverlay.visibility = android.view.View.VISIBLE
                supportActionBar?.setTitle(R.string.settings)
            }
        }

        // Initialize preferences manager
        preferencesManager = PreferencesManager.getInstance(this)

        // Set up language spinners
        setupLanguageSpinners()

        // Set up listeners
        setupListeners()

        // Load current settings
        loadSettings()
    }

    private fun loadSettings() {
        // LLM API settings
        binding.editSystemPrompt.setText(preferencesManager.systemPrompt)
        binding.editUserPrompt.setText(preferencesManager.userPrompt)
        currentProvider = LlmProvider.fromModel(preferencesManager.modelName)
        binding.spinnerProvider.setSelection(currentProvider.ordinal)
        binding.switchUseLocalModel.isChecked = preferencesManager.useLocalModel
        populateModels(currentProvider, preferencesManager.modelName)
        binding.editApiKey.setText(preferencesManager.getApiKey(currentProvider))

        // Capture settings
        // Snap to the slider's step grid (0.2 + k*0.1) so a stale off-grid value can't crash the Slider
        val scanSeconds = (preferencesManager.captureInterval / 1000f).coerceIn(0.2f, 1.5f)
        val scanSteps = Math.round((scanSeconds - 0.2f) / 0.1f)
        binding.sliderCaptureInterval.value = (0.2f + scanSteps * 0.1f).coerceIn(0.2f, 1.5f)
        binding.switchAutoCapture.isChecked = preferencesManager.autoCaptureEnabled

        // Display settings
        binding.sliderTextSize.value = preferencesManager.textSizeMultiplier
        binding.sliderOverlayOpacity.value = preferencesManager.overlayOpacity
        binding.switchAlternativeStyle.isChecked = preferencesManager.useAlternativeStyle
        binding.switchShowAreaBorder.isChecked = preferencesManager.showAreaBorder
        binding.spinnerFontColor.setSelection(colorIndex(preferencesManager.translationTextColor))
        binding.spinnerBgColor.setSelection(colorIndex(preferencesManager.translationBgColor))
        binding.spinnerFoldFavorite.setSelection(foldIndex(preferencesManager.foldFavorite))
        binding.switchInPlaceMode.isChecked = preferencesManager.inPlaceMode
        binding.switchUseAccessibility.isChecked = preferencesManager.useAccessibility
        binding.spinnerFont.setSelection(fontIndex(preferencesManager.translationFont))
        binding.switchMergeOverlap.isChecked = preferencesManager.mergeOverlapBoxes

        // Control panel styling
        binding.spinnerControlPanelOrientation.setSelection(
            controlPanelOrientationIndex(preferencesManager.controlPanelOrientation)
        )
        binding.spinnerControlPanelBgColor.setSelection(colorIndex(preferencesManager.controlPanelBgColor))
        binding.sliderControlPanelOpacity.value = preferencesManager.controlPanelOpacity

        // Save-to-file + custom font
        binding.switchSaveToFile.isChecked = preferencesManager.saveToFileEnabled
        refreshSaveFolderLabel()
        refreshCustomFontLabel()

        // Cache settings
        binding.sliderMaxCache.value = preferencesManager.maxCacheEntries.toFloat()
        binding.sliderMaxTokens.value = preferencesManager.maxTokens.toFloat()
        binding.sliderCacheTtl.value = preferencesManager.cacheTtlHours.toFloat()

        // History settings
        binding.sliderHistoryDays.value = preferencesManager.keepHistoryDays.toFloat()

        // Update display values
        updateDisplayValues()
    }

    private fun updateDisplayValues() {
        // Update text views with current values
        binding.textCaptureIntervalValue.text = getString(
            R.string.seconds_value,
            binding.sliderCaptureInterval.value
        )

        binding.textTextSizeValue.text = getString(
            R.string.multiplier_value,
            binding.sliderTextSize.value
        )

        binding.textOverlayOpacityValue.text = getString(
            R.string.percentage_value,
            (binding.sliderOverlayOpacity.value * 100).toInt()
        )

        binding.textMaxCacheValue.text = binding.sliderMaxCache.value.toInt().toString()

        binding.textCacheTtlValue.text = getString(
            R.string.hours_value,
            binding.sliderCacheTtl.value.toInt()
        )

        binding.textHistoryDaysValue.text = getString(
            R.string.days_value,
            binding.sliderHistoryDays.value.toInt()
        )

        binding.textControlPanelOpacityValue.text = getString(
            R.string.percentage_value,
            (binding.sliderControlPanelOpacity.value * 100).toInt()
        )
    }

    private fun setupListeners() {
        // Save button
        binding.btnSaveSettings.setOnClickListener {
            saveSettings()
        }

        // Reset button
        binding.btnResetSettings.setOnClickListener {
            resetSettings()
        }

        binding.spinnerProvider.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                    val np = LlmProvider.values()[pos]
                    if (np == currentProvider) return
                    preferencesManager.setApiKey(currentProvider, binding.editApiKey.text.toString()) // 先存旧公司的 key
                    currentProvider = np
                    populateModels(np)                                   // 切到新公司模型（选第一个）
                    binding.editApiKey.setText(preferencesManager.getApiKey(np))
                }
                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }

        // Open system accessibility settings so the user can enable the enhanced-OCR service
        binding.btnEnableAccessibility.setOnClickListener {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this, "Couldn't open accessibility settings", Toast.LENGTH_SHORT).show()
            }
        }

        // Slider change listeners
        binding.sliderCaptureInterval.addOnChangeListener { _, value, _ ->
            binding.textCaptureIntervalValue.text = getString(R.string.seconds_value, value)
        }

        binding.sliderTextSize.addOnChangeListener { _, value, _ ->
            binding.textTextSizeValue.text = getString(R.string.multiplier_value, value)
        }

        binding.sliderOverlayOpacity.addOnChangeListener { _, value, _ ->
            binding.textOverlayOpacityValue.text = getString(
                R.string.percentage_value,
                (value * 100).toInt()
            )
        }

        binding.sliderMaxCache.addOnChangeListener { _, value, _ ->
            binding.textMaxCacheValue.text = value.toInt().toString()
        }

        binding.sliderMaxTokens.addOnChangeListener { _, value, _ ->
            binding.textMaxTokensValue.text = value.toInt().toString()
        }

        binding.sliderCacheTtl.addOnChangeListener { _, value, _ ->
            binding.textCacheTtlValue.text = getString(
                R.string.hours_value,
                value.toInt()
            )
        }

        binding.sliderHistoryDays.addOnChangeListener { _, value, _ ->
            binding.textHistoryDaysValue.text = getString(
                R.string.days_value,
                value.toInt()
            )
        }

        // Local model switch
        binding.switchUseLocalModel.setOnCheckedChangeListener { _, isChecked ->
            binding.layoutApiSettings.isEnabled = !isChecked
            binding.editApiKey.isEnabled = !isChecked
            binding.spinnerModel.isEnabled = !isChecked
        }

        // Control panel opacity live readout
        binding.sliderControlPanelOpacity.addOnChangeListener { _, value, _ ->
            binding.textControlPanelOpacityValue.text = getString(
                R.string.percentage_value,
                (value * 100).toInt()
            )
        }

        // Save-to-file: launch folder picker
        binding.btnChooseSaveFolder.setOnClickListener {
            try {
                pickSaveFolder.launch(null)
            } catch (e: Exception) {
                Log.e(TAG, "OpenDocumentTree launch failed", e)
                Toast.makeText(this, "File picker unavailable", Toast.LENGTH_SHORT).show()
            }
        }

        // Custom font: launch document picker (accept any file; we still validate by trying to
        // load the typeface, since the system picker doesn't reliably filter by .ttf/.otf alone).
        binding.btnLoadCustomFont.setOnClickListener {
            try {
                pickCustomFont.launch(arrayOf("font/*", "application/octet-stream", "*/*"))
            } catch (e: Exception) {
                Log.e(TAG, "OpenDocument launch failed", e)
                Toast.makeText(this, "File picker unavailable", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnClearCustomFont.setOnClickListener {
            clearCustomFont()
        }
    }

    private fun setupLanguageSpinners() {
        // Set up language adapters
        val languages = resources.getStringArray(R.array.languages)
        val languageCodes = resources.getStringArray(R.array.language_codes)

        Log.d(TAG, "Languages array content: ${languages.joinToString()}") // 添加日志
        Log.d(TAG, "Language codes array content: ${languageCodes.joinToString()}") // 添加日志

        val sourceAdapter = android.widget.ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            languages
        )
        Log.d(TAG, "Source spinner adapter item count: ${sourceAdapter.count}") // 添加日志

        val targetAdapter = android.widget.ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            languages.copyOfRange(1, languages.size) // Skip "Auto-detect" for target
        )
        Log.d(TAG, "Target spinner adapter item count: ${targetAdapter.count}") // 添加日志

        // Set up model spinner
        val models = resources.getStringArray(R.array.models)
        val modelCodes = resources.getStringArray(R.array.model_codes)

        val modelAdapter = android.widget.ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            models
        )

        binding.spinnerProvider.adapter = android.widget.ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            LlmProvider.values().map { it.displayName })

        // Overlay colour + fold-favorite spinners
        val colors = resources.getStringArray(R.array.overlay_colors)
        binding.spinnerFontColor.adapter =
            android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, colors)
        binding.spinnerBgColor.adapter =
            android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, colors)

        val foldOptions = resources.getStringArray(R.array.fold_options)
        binding.spinnerFoldFavorite.adapter =
            android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, foldOptions)

        val fonts = resources.getStringArray(R.array.font_options)
        binding.spinnerFont.adapter =
            android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, fonts)

        // Control panel styling spinners
        val orientationLabels = resources.getStringArray(R.array.control_panel_orientation_options)
        binding.spinnerControlPanelOrientation.adapter =
            android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, orientationLabels)

        // Reuse the existing palette for the control panel background colour
        binding.spinnerControlPanelBgColor.adapter =
            android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, colors)
    }

    private fun fontIndex(value: String): Int {
        val idx = resources.getStringArray(R.array.font_values).indexOf(value)
        return if (idx >= 0) idx else 0
    }

    private fun colorIndex(color: Int): Int {
        val values = resources.getStringArray(R.array.overlay_color_values)
        val idx = values.indexOfFirst { android.graphics.Color.parseColor(it) == color }
        return if (idx >= 0) idx else 0
    }

    private fun foldIndex(value: String): Int {
        val idx = resources.getStringArray(R.array.fold_option_values).indexOf(value)
        return if (idx >= 0) idx else 0
    }

    private fun controlPanelOrientationIndex(value: String): Int {
        val idx = resources.getStringArray(R.array.control_panel_orientation_values).indexOf(value)
        return if (idx >= 0) idx else 0
    }

    /** Update the "Saving to: ..." label, also verifies the persisted permission is still valid. */
    private fun refreshSaveFolderLabel() {
        val uriStr = preferencesManager.saveFolderUri
        if (uriStr.isEmpty()) {
            binding.textSaveFolderCurrent.text = getString(R.string.save_folder_none)
            return
        }
        val uri = Uri.parse(uriStr)
        // Confirm we still hold the persisted permission (user may have revoked it via system UI).
        val stillGranted = contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
        if (!stillGranted) {
            binding.textSaveFolderCurrent.text = getString(R.string.save_folder_unset)
            preferencesManager.saveFolderUri = ""
        } else {
            val display = Uri.decode(uri.lastPathSegment ?: uriStr)
            binding.textSaveFolderCurrent.text = getString(R.string.save_folder_current, display)
        }
    }

    private fun refreshCustomFontLabel() {
        val path = preferencesManager.customFontPath
        if (path.isEmpty()) {
            binding.textCustomFontCurrent.text = getString(R.string.custom_font_none)
        } else {
            val name = File(path).name
            binding.textCustomFontCurrent.text = getString(R.string.custom_font_current, name)
        }
    }

    /**
     * Copy the picked font file into filesDir so its path is stable across reboots. Returns false
     * if the input stream couldn't be opened or the file isn't a valid typeface.
     */
    private suspend fun copyFontToFilesDir(srcUri: Uri): Boolean = withContext(Dispatchers.IO) {
        val dest = File(filesDir, CUSTOM_FONT_FILENAME)
        try {
            contentResolver.openInputStream(srcUri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext false

            // Validate by trying to load it; createFromFile throws RuntimeException for non-fonts.
            try {
                android.graphics.Typeface.createFromFile(dest)
            } catch (e: Exception) {
                Log.w(TAG, "Picked file isn't a valid typeface", e)
                dest.delete()
                return@withContext false
            }
            preferencesManager.customFontPath = dest.absolutePath
            true
        } catch (e: Exception) {
            Log.e(TAG, "copyFontToFilesDir failed", e)
            dest.delete()
            false
        }
    }

    private fun clearCustomFont() {
        val path = preferencesManager.customFontPath
        if (path.isNotEmpty()) {
            try { File(path).delete() } catch (_: Exception) {}
            preferencesManager.customFontPath = ""
        }
        refreshCustomFontLabel()
        Toast.makeText(this, R.string.custom_font_cleared, Toast.LENGTH_SHORT).show()
        updateActiveServices()
    }

    private fun getModelPosition(modelName: String): Int {
        val modelCodes = resources.getStringArray(R.array.model_codes)
        val position = modelCodes.indexOf(modelName)
        return if (position >= 0) position else 0
    }

    private fun saveSettings() {
        Log.d("SettingsActivity", "saveSettings() called")

        // LLM API settings
        preferencesManager.setApiKey(currentProvider, binding.editApiKey.text.toString())
        if (currentCodes.isNotEmpty())
            preferencesManager.modelName = currentCodes[binding.spinnerModel.selectedItemPosition]
        preferencesManager.systemPrompt =
            binding.editSystemPrompt.text.toString().ifBlank { PreferencesManager.DEFAULT_SYSTEM_PROMPT }
        preferencesManager.userPrompt =
            binding.editUserPrompt.text.toString().ifBlank { PreferencesManager.DEFAULT_USER_PROMPT }
        preferencesManager.useLocalModel = binding.switchUseLocalModel.isChecked
        preferencesManager.maxTokens = binding.sliderMaxTokens.value.toInt()

        // Capture settings
        preferencesManager.captureInterval = (binding.sliderCaptureInterval.value * 1000).toLong()
        preferencesManager.autoCaptureEnabled = binding.switchAutoCapture.isChecked

        // Display settings
        preferencesManager.textSizeMultiplier = binding.sliderTextSize.value
        preferencesManager.overlayOpacity = binding.sliderOverlayOpacity.value
        preferencesManager.useAlternativeStyle = binding.switchAlternativeStyle.isChecked
        preferencesManager.showAreaBorder = binding.switchShowAreaBorder.isChecked
        val colorValues = resources.getStringArray(R.array.overlay_color_values)
        preferencesManager.translationTextColor =
            android.graphics.Color.parseColor(colorValues[binding.spinnerFontColor.selectedItemPosition])
        preferencesManager.translationBgColor =
            android.graphics.Color.parseColor(colorValues[binding.spinnerBgColor.selectedItemPosition])
        val foldValues = resources.getStringArray(R.array.fold_option_values)
        preferencesManager.foldFavorite = foldValues[binding.spinnerFoldFavorite.selectedItemPosition]
        preferencesManager.inPlaceMode = binding.switchInPlaceMode.isChecked
        preferencesManager.mergeOverlapBoxes = binding.switchMergeOverlap.isChecked
        preferencesManager.useAccessibility = binding.switchUseAccessibility.isChecked
        preferencesManager.translationFont =
            resources.getStringArray(R.array.font_values)[binding.spinnerFont.selectedItemPosition]

        // Control panel styling
        preferencesManager.controlPanelOrientation =
            resources.getStringArray(R.array.control_panel_orientation_values)[
                binding.spinnerControlPanelOrientation.selectedItemPosition
            ]
        preferencesManager.controlPanelBgColor = android.graphics.Color.parseColor(
            colorValues[binding.spinnerControlPanelBgColor.selectedItemPosition]
        )
        preferencesManager.controlPanelOpacity = binding.sliderControlPanelOpacity.value

        // Save-to-file: the folder URI is set by the picker callback; only the enable switch is here.
        preferencesManager.saveToFileEnabled = binding.switchSaveToFile.isChecked

        // Cache settings
        preferencesManager.maxCacheEntries = binding.sliderMaxCache.value.toInt()
        preferencesManager.cacheTtlHours = binding.sliderCacheTtl.value.toInt()

        // History settings
        preferencesManager.keepHistoryDays = binding.sliderHistoryDays.value.toInt()

        // Notify user
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()

        // Apply settings to active services
        updateActiveServices()
    }

    private fun resetSettings() {
        lifecycleScope.launch {
            preferencesManager.resetToDefaults()
            loadSettings()
            Toast.makeText(this@SettingsActivity, R.string.settings_reset, Toast.LENGTH_SHORT).show()

            // Apply settings to active services
            updateActiveServices()
        }
    }

    private fun updateActiveServices() {
        // Send broadcast to update overlay service settings
        val overlayIntent = Intent("com.example.ocr_translation.ACTION_UPDATE_OVERLAY_SETTINGS")
        overlayIntent.putExtra("textSize", preferencesManager.textSizeMultiplier)
        overlayIntent.putExtra("opacity", preferencesManager.overlayOpacity)
        overlayIntent.putExtra("alternativeStyle", preferencesManager.useAlternativeStyle)
        overlayIntent.putExtra("showAreaBorder", preferencesManager.showAreaBorder)
        sendBroadcast(overlayIntent)

        // Send broadcast to update capture service settings
        val captureIntent = Intent("com.example.ocr_translation.ACTION_UPDATE_CAPTURE_SETTINGS")
        captureIntent.putExtra("captureInterval", preferencesManager.captureInterval)
        captureIntent.putExtra("autoCapture", preferencesManager.autoCaptureEnabled)
        sendBroadcast(captureIntent)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}