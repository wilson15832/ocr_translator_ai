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
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(R.layout.activity_settings)
        setContentView(binding.root)

        // Set up toolbar with back button
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle(R.string.settings_title)

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
        //binding.editApiKey.setText(preferencesManager.llmApiKey)
        // Retrieve API key securely
        val apiKey = SecureStorage.getEncryptedValue(this, "api_key")
        binding.editApiKey.setText(apiKey)

        binding.spinnerModel.setSelection(getModelPosition(preferencesManager.modelName))
        binding.switchUseLocalModel.isChecked = preferencesManager.useLocalModel
        binding.editInstruction.setText(preferencesManager.llmInstruction)

        // Capture settings
        binding.sliderCaptureInterval.value = preferencesManager.captureInterval / 1000f // Convert to seconds
        binding.switchAutoCapture.isChecked = preferencesManager.autoCaptureEnabled

        // Display settings
        binding.sliderTextSize.value = preferencesManager.textSizeMultiplier
        binding.sliderOverlayOpacity.value = preferencesManager.overlayOpacity
        binding.switchHighlightOriginal.isChecked = preferencesManager.highlightOriginalText
        binding.switchAlternativeStyle.isChecked = preferencesManager.useAlternativeStyle

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
    }

    private fun getLanguagePosition(languageCode: String, isTarget: Boolean = false): Int {
        Log.d(TAG, "getLanguagePosition called. languageCode: $languageCode, isTarget: $isTarget") // 添加日志
        val languageCodes = resources.getStringArray(R.array.language_codes)
        Log.d(TAG, "languageCodes array: ${languageCodes.joinToString()}") // 添加日志
        val positionInFullList = languageCodes.indexOf(languageCode)
        Log.d(TAG, "positionInFullList: $positionInFullList") // 添加日志


        return if (positionInFullList >= 0) {
            if (isTarget) {
                // 如果是目标语言 Spinner，且语言代码不是 "auto" (因为目标 Spinner 移除了 "auto")
                // 位置需要在完整列表的基础上减 1
                if (languageCode == "auto") {
                    Log.d(TAG, "Returning 0 for target 'auto'") // 添加日志
                    // 如果目标语言被设置为 auto (不应该发生，但作为 fallback)
                    // 返回目标 Spinner 的第一个位置 (通常是第一个实际语言)
                    0
                } else {
                    val finalPosition = positionInFullList - 1
                    Log.d(TAG, "Returning adjusted position for target: $finalPosition") // 添加日志

                }
            } else {
                // 对于源语言 Spinner，直接使用在完整列表中的位置
                positionInFullList
                Log.d(TAG, "Returning position for source: $positionInFullList") // 添加日志
            }
        } else {
            // 语言代码未找到，返回各自 Spinner 的第一个位置作为默认
            Log.d(TAG, "Language code not found, returning 0") // 添加日志
            0
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
        //preferencesManager.llmApiKey = binding.editApiKey.text.toString()
        // Save API key securely
        val apiKey = binding.editApiKey.text.toString()
        SecureStorage.setEncryptedValue(this, "api_key", apiKey)

        preferencesManager.modelName = modelCodes[binding.spinnerModel.selectedItemPosition]
        preferencesManager.useLocalModel = binding.switchUseLocalModel.isChecked
        preferencesManager.llmInstruction = binding.editInstruction.text.toString()

        // Capture settings
        preferencesManager.captureInterval = (binding.sliderCaptureInterval.value * 1000).toLong()
        preferencesManager.autoCaptureEnabled = binding.switchAutoCapture.isChecked

        // Display settings
        preferencesManager.textSizeMultiplier = binding.sliderTextSize.value
        preferencesManager.overlayOpacity = binding.sliderOverlayOpacity.value
        preferencesManager.highlightOriginalText = binding.switchHighlightOriginal.isChecked
        preferencesManager.useAlternativeStyle = binding.switchAlternativeStyle.isChecked

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
            // Also clear the securely stored API key on reset
            SecureStorage.setEncryptedValue(this@SettingsActivity, "api_key", "")
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