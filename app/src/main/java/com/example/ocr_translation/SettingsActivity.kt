package com.example.ocr_translation

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.ocr_translation.PreferencesManager
import com.example.ocr_translation.databinding.ActivitySettingsBinding
import kotlinx.coroutines.launch
import android.util.Log

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var preferencesManager: PreferencesManager

    companion object {
        private const val TAG = "SettingsActivity"
        const val EXTRA_SECTION = "section"
        const val SECTION_TRANSLATION = "translation"
        const val SECTION_OVERLAY = "overlay"
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Set up toolbar with back button
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

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
        // Translation settings
        binding.spinnerSourceLanguage.setSelection(getLanguagePosition(preferencesManager.sourceLanguage, isTarget = false))
        binding.spinnerTargetLanguage.setSelection(getLanguagePosition(preferencesManager.targetLanguage, isTarget = true))

        // LLM API settings
        binding.editApiEndpoint.setText(preferencesManager.llmApiEndpoint)
        binding.editApiKey.setText(preferencesManager.llmApiKey)
        binding.editSystemPrompt.setText(preferencesManager.systemPrompt)
        binding.editUserPrompt.setText(preferencesManager.userPrompt)
        binding.spinnerModel.setSelection(getModelPosition(preferencesManager.modelName))
        binding.switchUseLocalModel.isChecked = preferencesManager.useLocalModel

        // Capture settings
        // Snap to the slider's step grid (0.2 + k*0.1) so a stale off-grid value can't crash the Slider
        val scanSeconds = (preferencesManager.captureInterval / 1000f).coerceIn(0.2f, 1.5f)
        val scanSteps = Math.round((scanSeconds - 0.2f) / 0.1f)
        binding.sliderCaptureInterval.value = (0.2f + scanSteps * 0.1f).coerceIn(0.2f, 1.5f)
        binding.switchAutoCapture.isChecked = preferencesManager.autoCaptureEnabled

        // Display settings
        binding.sliderTextSize.value = preferencesManager.textSizeMultiplier
        binding.sliderOverlayOpacity.value = preferencesManager.overlayOpacity
        binding.switchHighlightOriginal.isChecked = preferencesManager.highlightOriginalText
        binding.switchAlternativeStyle.isChecked = preferencesManager.useAlternativeStyle
        binding.switchShowAreaBorder.isChecked = preferencesManager.showAreaBorder
        binding.spinnerFontColor.setSelection(colorIndex(preferencesManager.translationTextColor))
        binding.spinnerBgColor.setSelection(colorIndex(preferencesManager.translationBgColor))
        binding.spinnerFoldFavorite.setSelection(foldIndex(preferencesManager.foldFavorite))
        binding.switchInPlaceMode.isChecked = preferencesManager.inPlaceMode
        binding.switchUseAccessibility.isChecked = preferencesManager.useAccessibility
        binding.spinnerFont.setSelection(fontIndex(preferencesManager.translationFont))

        // Cache settings
        binding.sliderMaxCache.value = preferencesManager.maxCacheEntries.toFloat()
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
            binding.editApiEndpoint.isEnabled = !isChecked
            binding.editApiKey.isEnabled = !isChecked
            binding.spinnerModel.isEnabled = !isChecked
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

        binding.spinnerSourceLanguage.adapter = sourceAdapter
        binding.spinnerTargetLanguage.adapter = targetAdapter

        // Set up model spinner
        val models = resources.getStringArray(R.array.models)
        val modelCodes = resources.getStringArray(R.array.model_codes)

        val modelAdapter = android.widget.ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            models
        )

        binding.spinnerModel.adapter = modelAdapter

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

    private fun getLanguagePosition(languageCode: String, isTarget: Boolean = false): Int {
        val languageCodes = resources.getStringArray(R.array.language_codes)
        val positionInFullList = languageCodes.indexOf(languageCode)

        if(positionInFullList < 0) return 0

        return if (isTarget) {
            if (languageCode == "auto") 0 else positionInFullList - 1
        } else {
            positionInFullList
        }
    }

    private fun getModelPosition(modelName: String): Int {
        val modelCodes = resources.getStringArray(R.array.model_codes)
        val position = modelCodes.indexOf(modelName)
        return if (position >= 0) position else 0
    }

    private fun saveSettings() {
        Log.d("SettingsActivity", "saveSettings() called") // 添加这行日志
        // Get language codes
        val languageCodes = resources.getStringArray(R.array.language_codes)
        val modelCodes = resources.getStringArray(R.array.model_codes)

        // Translation settings
        preferencesManager.sourceLanguage = languageCodes[binding.spinnerSourceLanguage.selectedItemPosition]

        // Adjust target language position (since we skipped "Auto" in the target spinner)
        val targetPosition = binding.spinnerTargetLanguage.selectedItemPosition + 1
        preferencesManager.targetLanguage = languageCodes[targetPosition]

        // LLM API settings
        preferencesManager.llmApiEndpoint = binding.editApiEndpoint.text.toString()
        preferencesManager.llmApiKey = binding.editApiKey.text.toString()
        preferencesManager.systemPrompt =
            binding.editSystemPrompt.text.toString().ifBlank { PreferencesManager.DEFAULT_SYSTEM_PROMPT }
        preferencesManager.userPrompt =
            binding.editUserPrompt.text.toString().ifBlank { PreferencesManager.DEFAULT_USER_PROMPT }
        preferencesManager.modelName = modelCodes[binding.spinnerModel.selectedItemPosition]
        preferencesManager.useLocalModel = binding.switchUseLocalModel.isChecked

        // Capture settings
        preferencesManager.captureInterval = (binding.sliderCaptureInterval.value * 1000).toLong()
        preferencesManager.autoCaptureEnabled = binding.switchAutoCapture.isChecked

        // Display settings
        preferencesManager.textSizeMultiplier = binding.sliderTextSize.value
        preferencesManager.overlayOpacity = binding.sliderOverlayOpacity.value
        preferencesManager.highlightOriginalText = binding.switchHighlightOriginal.isChecked
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
        preferencesManager.useAccessibility = binding.switchUseAccessibility.isChecked
        preferencesManager.translationFont =
            resources.getStringArray(R.array.font_values)[binding.spinnerFont.selectedItemPosition]

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
        overlayIntent.putExtra("highlight", preferencesManager.highlightOriginalText)
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