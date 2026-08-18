package com.example.ocr_translation

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.View
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.ocr_translation.databinding.ActivitySettingsBinding
import com.example.ocr_translation.ui.AppTheme
import com.example.ocr_translation.ui.OptionPicker
import com.example.ocr_translation.ui.SettingsRow
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
    private var currentModelNames: List<String> = emptyList()

    /**
     * Each provider's chosen model, remembered while the screen is open.
     *
     * Only one model is persisted — the active one, in [PreferencesManager.modelName] — but the
     * picker shows a different list per provider, so switching provider used to reset the choice to
     * that list's first entry, and switching back lost what you had picked. This keeps each
     * provider's selection so a switch is a switch, not a reset.
     */
    private val selectedCodeByProvider = mutableMapOf<LlmProvider, String>()

    // The Spinners became picker rows, so the "selected position" each one used to hold for us
    // now lives here and is read back in saveSettings().
    private var modelIndex = 0
    private var textColorIndex = 0
    private var bgColorIndex = 0
    private var fontSelIndex = 0
    private var panelBgColorIndex = 0

    /** Index in [currentCodes] where the user's own models begin. */
    private var firstCustomIndex = 0

    // Same arrangement for fonts: bundled entries first, then the user's own files.
    private var currentFontValues: List<String> = emptyList()
    private var currentFontNames: List<String> = emptyList()
    private var firstCustomFontIndex = 0

    /**
     * Built-in models for [provider], then the user's own additions for it. Custom entries are
     * marked in the list so they can be told apart, and so the picker knows which rows carry the
     * edit action.
     */
    private fun populateModels(provider: LlmProvider, selectCode: String? = null) {
        val names = resources.getStringArray(R.array.models)
        val codes = resources.getStringArray(R.array.model_codes)
        val builtIn = codes.indices.filter { LlmProvider.fromModel(codes[it]) == provider }

        val custom = preferencesManager.customModels.filter { it.provider == provider }
        currentCodes = builtIn.map { codes[it] } + custom.map { it.code }
        currentModelNames = builtIn.map { names[it] } +
                custom.map { getString(R.string.custom_model_suffix, it.title) }
        firstCustomIndex = builtIn.size

        modelIndex = selectCode?.let { currentCodes.indexOf(it) }?.takeIf { it >= 0 } ?: 0
        binding.rowModel.value = currentModelNames.getOrNull(modelIndex)
    }

    /**
     * Model picker with an "Add model…" row appended, because vendors release models faster than
     * the bundled `@array/models` can follow while the request URL and payload stay the same.
     * User-added rows carry a pencil that opens them for editing or removal.
     */
    private fun showModelPicker() {
        val options = currentModelNames + getString(R.string.add_model)
        OptionPicker.show(
            context = this,
            title = getString(R.string.model),
            entries = options,
            selectedIndex = modelIndex,
            secondaryFor = { index -> index in firstCustomIndex until currentModelNames.size },
            onSecondary = { index -> customModelAt(index)?.let { promptForCustomModel(it) } }
        ) { index ->
            if (index == options.lastIndex) {
                promptForCustomModel()
            } else {
                modelIndex = index
                binding.rowModel.value = currentModelNames.getOrNull(index)
            }
        }
    }

    /**
     * The sample sentence for the configured source language, looked up by index the same way the
     * model arrays are.
     *
     * Per-language rather than one fixed string because the test is only convincing if you can
     * read the answer: a reply to a sentence you recognise tells you at a glance whether the model
     * translated it, echoed it, or returned something else entirely. It also exercises the script
     * the OCR will actually be feeding it.
     *
     * "auto" resolves to index 0, which carries the fallback, so no special case is needed.
     */
    private fun connectionTestSample(): String {
        val codes = resources.getStringArray(R.array.language_codes)
        val samples = resources.getStringArray(R.array.connection_test_samples)
        val index = codes.indexOf(preferencesManager.sourceLanguage)
        return samples.getOrNull(index) ?: samples.first()
    }

    /**
     * The gateway as the screen currently has it, or null to test the vendor directly.
     *
     * Read from the views for the same reason the key and model are: the point of the test is to
     * find out whether what you just typed works, before it is saved.
     */
    private fun liveGateway(): TranslationService.Gateway? {
        if (!binding.switchCloudflareProxy.isChecked) return null
        val account = binding.editCloudflareAccount.text.toString().trim()
        val token = binding.editCloudflareToken.text.toString().trim()
        if (account.isEmpty() || token.isEmpty()) return null
        val name = binding.editCloudflareGateway.text.toString().trim()
            .ifBlank { PreferencesManager.DEFAULT_CF_GATEWAY }
        return TranslationService.Gateway(account, name, token)
    }

    /** Greys out the gateway's fields when the proxy is off, as the API rows do for local model. */
    private fun applyCloudflareState(enabled: Boolean) {
        val alpha = if (enabled) 1f else 0.4f
        listOf(
            binding.layoutCloudflareAccount,
            binding.layoutCloudflareGateway,
            binding.layoutCloudflareToken
        ).forEach { it.isEnabled = enabled; it.alpha = alpha }
        listOf(
            binding.editCloudflareAccount,
            binding.editCloudflareGateway,
            binding.editCloudflareToken
        ).forEach { it.isEnabled = enabled }
        binding.btnToggleCloudflareToken.isEnabled = enabled
    }

    /**
     * Sends one real translation request using what is currently on screen, and reports what came
     * back — the mirror of Overlay Settings' preview card, for the half of the settings whose
     * effect you otherwise only discover mid-game.
     *
     * Deliberately reads the live views rather than [preferencesManager]: the common case is
     * pasting a key or picking a model and wanting to know whether it works *before* saving.
     * Nothing here writes a preference, so a failed test leaves no trace.
     */
    private fun runConnectionTest() {
        if (binding.switchUseLocalModel.isChecked) {
            binding.textTestResult.text = getString(R.string.connection_test_local)
            return
        }
        val provider = currentProvider
        val model = currentCodes.getOrNull(modelIndex).orEmpty()
        val key = binding.editApiKey.text.toString()
        val maxTokens = binding.sliderMaxTokens.slider.value.toInt()
        val system = binding.editSystemPrompt.text.toString()
        val user = binding.editUserPrompt.text.toString()
            .ifBlank { PreferencesManager.DEFAULT_USER_PROMPT }

        binding.btnTestConnection.isEnabled = false
        binding.textTestResult.text = getString(R.string.connection_test_running, provider.displayName)

        lifecycleScope.launch {
            val result = TranslationService.getInstance(this@SettingsActivity).testConnection(
                provider = provider,
                model = model,
                key = key,
                maxTokens = maxTokens,
                sample = binding.textTestSource.text.toString(),
                sourceLanguage = preferencesManager.sourceLanguage,
                targetLanguage = preferencesManager.targetLanguage,
                systemPrompt = system,
                userPrompt = user,
                gateway = liveGateway()
            )
            binding.textTestResult.text = when (result) {
                is TranslationService.ConnectionTest.Success ->
                    getString(R.string.connection_test_ok, result.reply, result.millis)
                is TranslationService.ConnectionTest.Failure ->
                    getString(R.string.connection_test_failed, result.reason)
            }
            binding.btnTestConnection.isEnabled = true
        }
    }

    /**
     * Bundled fonts, then the user's own, in the order the picker shows them.
     *
     * Mirrors [populateModels]. The two lists are rebuilt rather than cached because loading or
     * removing a font changes them under an open screen.
     */
    private fun populateFonts(selectValue: String? = null) {
        val names = resources.getStringArray(R.array.font_options)
        val values = resources.getStringArray(R.array.font_values)
        val custom = preferencesManager.customFonts
        currentFontValues = values.toList() + custom.map { it.token }
        currentFontNames = names.toList() + custom.map { it.name }
        firstCustomFontIndex = values.size

        val target = selectValue ?: currentFontValues.getOrNull(fontSelIndex)
        fontSelIndex = currentFontValues.indexOf(target).takeIf { it >= 0 } ?: 0
        binding.rowFont.value = currentFontNames.getOrNull(fontSelIndex)
    }

    /**
     * Font picker with a "Load font…" row appended, and a remove action on the user's own entries —
     * the same shape as the model picker, and for the same reason: adding one is part of choosing
     * one, so it belongs in the list rather than in a separate pair of rows beneath it.
     */
    private fun showFontPicker() {
        val options = currentFontNames + getString(R.string.custom_font_load)
        OptionPicker.show(
            context = this,
            title = getString(R.string.font_style),
            entries = options,
            selectedIndex = fontSelIndex,
            secondaryFor = { index -> index in firstCustomFontIndex until currentFontNames.size },
            onSecondary = { index -> confirmRemoveFont(index) }
        ) { index ->
            if (index == options.lastIndex) {
                launchFontPicker()
            } else {
                fontSelIndex = index
                binding.rowFont.value = currentFontNames.getOrNull(index)
                refreshPreview()
            }
        }
    }

    private fun launchFontPicker() {
        try {
            // Any file: the system picker doesn't reliably filter by .ttf/.otf, so the real check
            // is trying to load the typeface after the copy.
            pickCustomFont.launch(arrayOf("font/*", "application/octet-stream", "*/*"))
        } catch (e: Exception) {
            Log.e(TAG, "OpenDocument launch failed", e)
            Toast.makeText(this, "File picker unavailable", Toast.LENGTH_SHORT).show()
        }
    }

    /** Removes a user font: its file, its entry, and the selection if it was the one selected. */
    private fun confirmRemoveFont(index: Int) {
        val font = preferencesManager.customFonts.getOrNull(index - firstCustomFontIndex) ?: return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.custom_font_remove_title, font.name))
            .setPositiveButton(R.string.remove_model_action) { _, _ ->
                try { font.file(this).delete() } catch (_: Exception) {}
                // Compared by fileName: the getter re-parses its JSON on every read, so the
                // instances it hands back are never the same object twice.
                preferencesManager.customFonts =
                    preferencesManager.customFonts.filterNot { it.fileName == font.fileName }
                val wasSelected = currentFontValues.getOrNull(fontSelIndex) == font.token
                populateFonts(if (wasSelected) currentFontValues.firstOrNull() else null)
                refreshPreview()
                Toast.makeText(
                    this, getString(R.string.remove_model_done, font.name), Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** The custom model behind a picker row, or null if that row is a built-in. */
    private fun customModelAt(index: Int): CustomModel? {
        val code = currentCodes.getOrNull(index) ?: return null
        return preferencesManager.customModels
            .firstOrNull { it.provider == currentProvider && it.code == code }
    }

    /**
     * Add, or edit when [existing] is given — in which case the dialog also offers to remove,
     * since a mistyped code would otherwise be permanent.
     */
    private fun promptForCustomModel(existing: CustomModel? = null) {
        val view = layoutInflater.inflate(R.layout.dialog_add_model, null)
        val titleField = view.findViewById<android.widget.EditText>(R.id.editModelTitle)
        val codeField = view.findViewById<android.widget.EditText>(R.id.editModelCode)
        existing?.let {
            titleField.setText(it.title)
            codeField.setText(it.code)
        }

        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(
                if (existing == null) getString(R.string.add_model_for, currentProvider.displayName)
                else getString(R.string.edit_model)
            )
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val title = titleField.text.toString().trim()
                val code = codeField.text.toString().trim()
                if (title.isEmpty() || code.isEmpty()) {
                    Toast.makeText(this, R.string.add_model_incomplete, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                // Drop the row being edited and any same-code duplicate, then re-add.
                preferencesManager.customModels = preferencesManager.customModels
                    .filterNot { it == existing }
                    .filterNot { it.provider == currentProvider && it.code == code } +
                        CustomModel(currentProvider, title, code)
                // Select what was just saved, so it takes effect on Save without another tap.
                populateModels(currentProvider, code)
                Toast.makeText(
                    this, getString(R.string.add_model_added, title), Toast.LENGTH_SHORT
                ).show()
            }

        if (existing != null) {
            builder.setNeutralButton(R.string.remove_model_action) { _, _ ->
                // Structural equality, not identity: the customModels getter re-parses its JSON on
                // every read, so each access hands back fresh instances and `===` never matched —
                // which is why removing a model appeared to do nothing.
                preferencesManager.customModels =
                    preferencesManager.customModels.filterNot { it == existing }
                // Fall back to a built-in if the removed model was the selected one.
                populateModels(currentProvider, currentCodes.firstOrNull { it != existing.code })
                Toast.makeText(
                    this, getString(R.string.remove_model_done, existing.title), Toast.LENGTH_SHORT
                ).show()
            }
        }
        builder.show()
    }

    companion object {
        private const val TAG = "SettingsActivity"
        const val EXTRA_SECTION = "section"
        const val SECTION_TRANSLATION = "translation"
        const val SECTION_OVERLAY = "overlay"
        // File name inside filesDir/ where we keep the user-loaded font.
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Before inflation: ?attr/colorPrimary is resolved eagerly by the inflater.
        AppTheme.applyTo(this)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // The screen draws its own nav bar (Back / title / Save) instead of using an ActionBar.
        binding.btnNavBack.setOnClickListener { finish() }

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
                val font = addCustomFont(uri)
                if (font != null) {
                    // Added *and* selected: loading a font is only ever done in order to use it.
                    populateFonts(font.token)
                    refreshPreview()
                    Toast.makeText(
                        this@SettingsActivity,
                        getString(R.string.custom_font_added, font.name),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(this@SettingsActivity, R.string.custom_font_load_failed, Toast.LENGTH_LONG).show()
                }
            }
        }

        // Show only the requested group (homepage opens one or the other)
        when (intent.getStringExtra(EXTRA_SECTION)) {
            SECTION_OVERLAY -> {
                binding.groupTranslation.visibility = View.GONE
                binding.groupOverlay.visibility = View.VISIBLE
                binding.textNavTitle.setText(R.string.display_settings)
            }
            SECTION_TRANSLATION -> {
                binding.groupTranslation.visibility = View.VISIBLE
                binding.groupOverlay.visibility = View.GONE
                binding.textNavTitle.setText(R.string.translation_settings)
            }
            else -> {  // no section specified: show everything
                binding.groupTranslation.visibility = View.VISIBLE
                binding.groupOverlay.visibility = View.VISIBLE
                binding.textNavTitle.setText(R.string.settings)
            }
        }

        // Initialize preferences manager
        preferencesManager = PreferencesManager.getInstance(this)

        // Set up the picker rows and segmented controls
        setupChoiceControls()

        // Set up listeners
        setupListeners()

        // Load current settings
        loadSettings()
    }

    private fun loadSettings() {
        // LLM API settings
        binding.editSystemPrompt.setText(preferencesManager.systemPrompt)
        binding.editUserPrompt.setText(preferencesManager.userPrompt)
        binding.textTestSource.text = connectionTestSample()
        currentProvider = preferencesManager.providerFor(preferencesManager.modelName)
        binding.segmentProvider.setSelectionSilently(currentProvider.ordinal)
        binding.switchUseLocalModel.isChecked = preferencesManager.useLocalModel
        populateModels(currentProvider, preferencesManager.modelName)
        binding.editApiKey.setText(preferencesManager.getApiKey(currentProvider))
        binding.switchCloudflareProxy.isChecked = preferencesManager.cloudflareProxyEnabled
        binding.editCloudflareAccount.setText(preferencesManager.cloudflareAccountId)
        binding.editCloudflareGateway.setText(preferencesManager.cloudflareGateway)
        binding.editCloudflareToken.setText(preferencesManager.cloudflareToken)
        applyCloudflareState(preferencesManager.cloudflareProxyEnabled)
        applyLocalModelState(preferencesManager.useLocalModel)

        // Capture settings
        binding.sliderCaptureInterval.setSnapped(preferencesManager.captureInterval / 1000f)
        binding.switchAutoCapture.isChecked = preferencesManager.autoCaptureEnabled

        // Display settings
        binding.GifOpacity.setSnapped(preferencesManager.spinnerAlpha)
        binding.GifSize.setSnapped(preferencesManager.spinnerSizeDp.toFloat())
        binding.switchGifEnabled.isChecked = preferencesManager.spinnerEnabled
        binding.sliderTextSize.setSnapped(preferencesManager.textSizeMultiplier)
        binding.sliderOverlayOpacity.setSnapped(preferencesManager.overlayOpacity)
        binding.switchAlternativeStyle.isChecked = preferencesManager.useAlternativeStyle
        binding.switchShowAreaBorder.isChecked = preferencesManager.showAreaBorder
        textColorIndex = colorIndex(preferencesManager.translationTextColor)
        bgColorIndex = colorIndex(preferencesManager.translationBgColor)
        populateFonts(preferencesManager.translationFont)
        binding.switchInPlaceMode.isChecked = preferencesManager.inPlaceMode
        binding.switchUseAccessibility.isChecked = preferencesManager.useAccessibility
        binding.switchMergeOverlap.isChecked = preferencesManager.mergeOverlapBoxes
        binding.switchMergeAdjacent.isChecked = preferencesManager.mergeAdjacentBoxes
        binding.sliderMergeAdjacentGap.setSnapped(preferencesManager.mergeAdjacentGapDp.toFloat())

        // Control panel styling
        binding.segmentControlPanelOrientation.setSelectionSilently(
            controlPanelOrientationIndex(preferencesManager.controlPanelOrientation)
        )
        panelBgColorIndex = colorIndex(preferencesManager.controlPanelBgColor)
        binding.sliderControlPanelScale.setSnapped(preferencesManager.controlPanelScale)
        binding.sliderControlPanelOpacity.setSnapped(preferencesManager.controlPanelOpacity)

        // Save-to-file + custom font
        binding.switchSaveToFile.isChecked = preferencesManager.saveToFileEnabled
        refreshSaveFolderLabel()

        // Cache settings
        binding.sliderMaxCache.setSnapped(preferencesManager.maxCacheEntries.toFloat())
        binding.sliderMaxTokens.setSnapped(preferencesManager.maxTokens.toFloat())
        binding.sliderCacheTtl.setSnapped(preferencesManager.cacheTtlHours.toFloat())

        // History settings
        binding.sliderHistoryDays.setSnapped(preferencesManager.keepHistoryDays.toFloat())

        // Update display values
        refreshChoiceLabels()
        updateDisplayValues()
        refreshPreview()
    }

    private fun updateDisplayValues() {
        // Update the value shown at the right end of each slider row
        binding.sliderCaptureInterval.valueLabel.text = getString(
            R.string.seconds_value,
            binding.sliderCaptureInterval.slider.value
        )

        binding.sliderTextSize.valueLabel.text = getString(
            R.string.multiplier_value,
            binding.sliderTextSize.slider.value
        )

        binding.sliderOverlayOpacity.valueLabel.text = getString(
            R.string.percentage_value,
            (binding.sliderOverlayOpacity.slider.value * 100).toInt()
        )

        binding.GifOpacity.valueLabel.text = getString(
            R.string.percentage_value,
            (binding.GifOpacity.slider.value * 100).toInt()
        )

        binding.GifSize.valueLabel.text = binding.GifSize.slider.value.toInt().toString()

        binding.sliderMaxTokens.valueLabel.text =
            binding.sliderMaxTokens.slider.value.toInt().toString()

        binding.sliderMergeAdjacentGap.valueLabel.text = getString(
            R.string.dp_value,
            binding.sliderMergeAdjacentGap.slider.value.toInt()
        )

        binding.sliderMaxCache.valueLabel.text =
            binding.sliderMaxCache.slider.value.toInt().toString()

        binding.sliderCacheTtl.valueLabel.text = getString(
            R.string.hours_value,
            binding.sliderCacheTtl.slider.value.toInt()
        )

        binding.sliderHistoryDays.valueLabel.text = getString(
            R.string.days_value,
            binding.sliderHistoryDays.slider.value.toInt()
        )

        binding.sliderControlPanelOpacity.valueLabel.text = getString(
            R.string.percentage_value,
            (binding.sliderControlPanelOpacity.slider.value * 100).toInt()
        )

        binding.sliderControlPanelScale.valueLabel.text = getString(
            R.string.multiplier_value,
            binding.sliderControlPanelScale.slider.value
        )
    }

    private fun setupListeners() {
        // Save lives in the nav bar now
        binding.btnSaveSettings.setOnClickListener {
            saveSettings()
        }

        // Reset sits at the bottom of the list, in destructive red
        binding.btnResetSettings.setOnClickListener {
            resetSettings()
        }

        binding.segmentProvider.onSelected = { position ->
            val np = LlmProvider.values()[position]
            if (np != currentProvider) {
                preferencesManager.setApiKey(currentProvider, binding.editApiKey.text.toString()) // 先存旧公司的 key
                // Remember the model chosen for the provider we're leaving, so coming back restores
                // it rather than the list's first entry.
                currentCodes.getOrNull(modelIndex)?.let { selectedCodeByProvider[currentProvider] = it }
                currentProvider = np
                populateModels(np, selectedCodeByProvider[np])
                binding.editApiKey.setText(preferencesManager.getApiKey(np))
            }
        }

        binding.btnTestConnection.setOnClickListener { runConnectionTest() }

        // Reveal / hide the API key — replaces the old TextInputLayout password toggle
        binding.btnToggleApiKey.setOnClickListener {
            val field = binding.editApiKey
            val hidden = field.inputType and InputType.TYPE_TEXT_VARIATION_PASSWORD != 0
            val cursor = field.selectionStart
            // Assigning inputType resets the typeface to monospace, so restore it afterwards.
            val face = field.typeface
            field.inputType =
                if (hidden) InputType.TYPE_CLASS_TEXT
                else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            field.typeface = face
            field.setSelection(cursor.coerceIn(0, field.text?.length ?: 0))
            binding.btnToggleApiKey.setImageResource(
                if (hidden) R.drawable.ic_visibility_off else R.drawable.ic_visibility
            )
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
        binding.sliderCaptureInterval.slider.addOnChangeListener { _, value, _ ->
            binding.sliderCaptureInterval.valueLabel.text = getString(R.string.seconds_value, value)
        }

        binding.sliderTextSize.slider.addOnChangeListener { _, value, _ ->
            binding.sliderTextSize.valueLabel.text = getString(R.string.multiplier_value, value)
            refreshPreview()
        }

        binding.sliderOverlayOpacity.slider.addOnChangeListener { _, value, _ ->
            binding.sliderOverlayOpacity.valueLabel.text = getString(
                R.string.percentage_value,
                (value * 100).toInt()
            )
            refreshPreview()
        }

        binding.GifOpacity.slider.addOnChangeListener { _, value, _ ->
            binding.GifOpacity.valueLabel.text =
                getString(R.string.percentage_value, (value * 100).toInt())
        }

        binding.GifSize.slider.addOnChangeListener { _, value, _ ->
            binding.GifSize.valueLabel.text = value.toInt().toString()
        }

        binding.sliderMaxCache.slider.addOnChangeListener { _, value, _ ->
            binding.sliderMaxCache.valueLabel.text = value.toInt().toString()
        }

        binding.sliderMaxTokens.slider.addOnChangeListener { _, value, _ ->
            binding.sliderMaxTokens.valueLabel.text = value.toInt().toString()
        }

        binding.sliderMergeAdjacentGap.slider.addOnChangeListener { _, value, _ ->
            binding.sliderMergeAdjacentGap.valueLabel.text =
                getString(R.string.dp_value, value.toInt())
        }

        binding.sliderCacheTtl.slider.addOnChangeListener { _, value, _ ->
            binding.sliderCacheTtl.valueLabel.text = getString(
                R.string.hours_value,
                value.toInt()
            )
        }

        binding.sliderHistoryDays.slider.addOnChangeListener { _, value, _ ->
            binding.sliderHistoryDays.valueLabel.text = getString(
                R.string.days_value,
                value.toInt()
            )
        }

        // Local model switch
        binding.switchUseLocalModel.switch.setOnCheckedChangeListener { _, isChecked ->
            applyLocalModelState(isChecked)
        }

        binding.switchCloudflareProxy.switch.setOnCheckedChangeListener { _, isChecked ->
            applyCloudflareState(isChecked)
            if (isChecked && liveGateway() == null) {
                Toast.makeText(this, R.string.cf_incomplete, Toast.LENGTH_SHORT).show()
            }
        }

        // Reveal / hide the gateway token, the same affordance the API key row carries.
        binding.btnToggleCloudflareToken.setOnClickListener {
            val field = binding.editCloudflareToken
            val hidden = field.inputType and InputType.TYPE_TEXT_VARIATION_PASSWORD != 0
            val cursor = field.selectionStart
            val face = field.typeface
            field.inputType =
                if (hidden) InputType.TYPE_CLASS_TEXT
                else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            field.typeface = face
            field.setSelection(cursor.coerceIn(0, field.text?.length ?: 0))
            binding.btnToggleCloudflareToken.setImageResource(
                if (hidden) R.drawable.ic_visibility_off else R.drawable.ic_visibility
            )
        }

        // Speech-bubble style changes the preview's corner radius / border
        binding.switchAlternativeStyle.switch.setOnCheckedChangeListener { _, _ -> refreshPreview() }

        binding.sliderControlPanelScale.slider.addOnChangeListener { _, value, _ ->
            binding.sliderControlPanelScale.valueLabel.text =
                getString(R.string.multiplier_value, value)
        }

        // Control panel opacity live readout
        binding.sliderControlPanelOpacity.slider.addOnChangeListener { _, value, _ ->
            binding.sliderControlPanelOpacity.valueLabel.text = getString(
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

    }

    /** Greys out the API controls when the local model is in use, as the old screen did. */
    private fun applyLocalModelState(useLocal: Boolean) {
        binding.layoutApiSettings.isEnabled = !useLocal
        binding.editApiKey.isEnabled = !useLocal
        binding.btnToggleApiKey.isEnabled = !useLocal
        binding.rowModel.isEnabled = !useLocal
        binding.segmentProvider.isEnabled = !useLocal
        val alpha = if (useLocal) 0.4f else 1f
        binding.layoutApiSettings.alpha = alpha
        binding.rowModel.alpha = alpha
        binding.segmentProvider.alpha = alpha
    }

    /**
     * Wires every row that used to hold a Spinner to a bottom-sheet picker, plus the two
     * segmented controls. Called once; [refreshChoiceLabels] then keeps the right-hand values
     * in sync with the indices that [loadSettings] restores.
     */
    private fun setupChoiceControls() {
        binding.segmentProvider.setEntries(LlmProvider.values().map { it.displayName })

        val colors = resources.getStringArray(R.array.overlay_colors).toList()

        binding.rowModel.setOnClickListener { showModelPicker() }
        binding.rowFont.setOnClickListener { showFontPicker() }
        bindPicker(binding.rowFontColor, R.string.font_color, { colors }, { textColorIndex }) {
            textColorIndex = it
        }
        bindPicker(binding.rowBgColor, R.string.background_color, { colors }, { bgColorIndex }) {
            bgColorIndex = it
        }
        bindPicker(
            binding.rowControlPanelBgColor,
            R.string.control_panel_background_color,
            { colors },
            { panelBgColorIndex }
        ) { panelBgColorIndex = it }
    }

    /**
     * @param entries a supplier rather than a list: the model row's options change whenever the
     *   provider segment changes.
     */
    private fun bindPicker(
        row: SettingsRow,
        titleRes: Int,
        entries: () -> List<String>,
        selected: () -> Int,
        onPick: (Int) -> Unit
    ) {
        row.setOnClickListener {
            val options = entries()
            if (options.isEmpty()) return@setOnClickListener
            OptionPicker.show(this, getString(titleRes), options, selected()) { index ->
                onPick(index)
                row.value = options.getOrNull(index)
                refreshPreview()
            }
        }
    }

    /** Pushes the persisted indices back out to the rows' right-hand value labels. */
    private fun refreshChoiceLabels() {
        val colors = resources.getStringArray(R.array.overlay_colors)
        binding.rowModel.value = currentModelNames.getOrNull(modelIndex)
        binding.rowFontColor.value = colors.getOrNull(textColorIndex)
        binding.rowBgColor.value = colors.getOrNull(bgColorIndex)
        binding.rowFont.value = currentFontNames.getOrNull(fontSelIndex)
        binding.rowControlPanelBgColor.value = colors.getOrNull(panelBgColorIndex)
    }

    /**
     * Renders the sample translation with the text size, colours, font and opacity currently
     * selected — the one thing the old Overlay Settings screen made you guess at, since none of
     * those controls showed their effect until you went back into a game.
     */
    private fun refreshPreview() {
        val colorValues = resources.getStringArray(R.array.overlay_color_values)
        val textColor = parseColorAt(colorValues, textColorIndex)
        val bgColor = parseColorAt(colorValues, bgColorIndex)
        val opacity = binding.sliderOverlayOpacity.slider.value.coerceIn(0f, 1f)
        val multiplier = binding.sliderTextSize.slider.value

        val target = binding.textPreviewTarget
        target.setTextColor(textColor)
        target.textSize = 14f * multiplier
        target.typeface = previewTypeface()
        target.background = GradientDrawable().apply {
            cornerRadius = if (binding.switchAlternativeStyle.isChecked) dp(12f) else dp(5f)
            setColor((bgColor and 0x00FFFFFF) or ((255 * opacity).toInt() shl 24))
            if (binding.switchAlternativeStyle.isChecked) {
                setStroke(dp(1f).toInt(), 0x44FFFFFF)
            }
        }
    }

    /** Mirrors OverlayService.resultTypeface(): user's own file first, bundled font, then family. */
    private fun previewTypeface(): android.graphics.Typeface {
        val name = currentFontValues.getOrNull(fontSelIndex)
            ?: return android.graphics.Typeface.DEFAULT
        // The picked entry rather than the saved preference, so the preview follows the picker
        // before Save is pressed — which is the whole point of having one.
        CustomFont.fromToken(name, preferencesManager.customFonts)?.let { font ->
            try {
                val f = font.file(this)
                if (f.exists() && f.canRead()) return android.graphics.Typeface.createFromFile(f)
            } catch (e: Exception) {
                Log.w(TAG, "Preview: custom font load failed", e)
            }
        }
        val resId = resources.getIdentifier(name, "font", packageName)
        return if (resId != 0) {
            androidx.core.content.res.ResourcesCompat.getFont(this, resId)
                ?: android.graphics.Typeface.DEFAULT
        } else {
            android.graphics.Typeface.create(name, android.graphics.Typeface.NORMAL)
        }
    }

    private fun parseColorAt(values: Array<String>, index: Int): Int =
        try {
            android.graphics.Color.parseColor(values[index])
        } catch (e: Exception) {
            android.graphics.Color.WHITE
        }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    private fun colorIndex(color: Int): Int {
        val values = resources.getStringArray(R.array.overlay_color_values)
        val idx = values.indexOfFirst { android.graphics.Color.parseColor(it) == color }
        return if (idx >= 0) idx else 0
    }

    private fun controlPanelOrientationIndex(value: String): Int {
        val idx = resources.getStringArray(R.array.control_panel_orientation_values).indexOf(value)
        return if (idx >= 0) idx else 0
    }

    /** Update the folder shown on the "Choose folder" row; also verifies the permission holds. */
    private fun refreshSaveFolderLabel() {
        val uriStr = preferencesManager.saveFolderUri
        if (uriStr.isEmpty()) {
            binding.btnChooseSaveFolder.value = getString(R.string.save_folder_none)
            return
        }
        val uri = Uri.parse(uriStr)
        // Confirm we still hold the persisted permission (user may have revoked it via system UI).
        val stillGranted = contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
        if (!stillGranted) {
            binding.btnChooseSaveFolder.value = getString(R.string.save_folder_unset)
            preferencesManager.saveFolderUri = ""
        } else {
            binding.btnChooseSaveFolder.value = Uri.decode(uri.lastPathSegment ?: uriStr)
        }
    }

    /**
     * Copies the picked file into the app's own font directory and registers it. Returns the new
     * entry, or null if the stream couldn't be read or the file isn't a typeface.
     *
     * Each pick gets its own file name rather than overwriting a single fixed one, which is what
     * makes keeping several fonts possible at all.
     */
    private suspend fun addCustomFont(srcUri: Uri): CustomFont? = withContext(Dispatchers.IO) {
        val dir = CustomFont.dir(this@SettingsActivity)
        dir.mkdirs()
        val dest = File(dir, "font_${System.currentTimeMillis()}.ttf")
        try {
            contentResolver.openInputStream(srcUri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext null

            // Validate by trying to load it; createFromFile throws RuntimeException for non-fonts.
            try {
                android.graphics.Typeface.createFromFile(dest)
            } catch (e: Exception) {
                Log.w(TAG, "Picked file isn't a valid typeface", e)
                dest.delete()
                return@withContext null
            }
            val font = CustomFont(name = displayNameOf(srcUri), fileName = dest.name)
            preferencesManager.customFonts = preferencesManager.customFonts + font
            font
        } catch (e: Exception) {
            Log.e(TAG, "addCustomFont failed", e)
            dest.delete()
            null
        }
    }

    /**
     * What to call the font in the picker: the picked file's display name without its extension.
     *
     * The file name is what the user chose it by and recognises it as. Reading the family name out
     * of the typeface's own `name` table would be more correct in principle, but it means parsing
     * the font binary, and the file is almost always named after the family anyway.
     */
    private fun displayNameOf(uri: Uri): String {
        val fromProvider = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
        val raw = fromProvider ?: uri.lastPathSegment?.substringAfterLast('/')
        return raw?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
            ?: getString(R.string.custom_font)
    }

    private fun saveSettings() {
        Log.d("SettingsActivity", "saveSettings() called")

        // LLM API settings
        preferencesManager.setApiKey(currentProvider, binding.editApiKey.text.toString())
        if (currentCodes.isNotEmpty())
            preferencesManager.modelName = currentCodes[modelIndex.coerceIn(currentCodes.indices)]
        preferencesManager.systemPrompt =
            binding.editSystemPrompt.text.toString().ifBlank { PreferencesManager.DEFAULT_SYSTEM_PROMPT }
        preferencesManager.userPrompt =
            binding.editUserPrompt.text.toString().ifBlank { PreferencesManager.DEFAULT_USER_PROMPT }
        preferencesManager.useLocalModel = binding.switchUseLocalModel.isChecked
        preferencesManager.cloudflareProxyEnabled = binding.switchCloudflareProxy.isChecked
        preferencesManager.cloudflareAccountId = binding.editCloudflareAccount.text.toString()
        preferencesManager.cloudflareGateway = binding.editCloudflareGateway.text.toString()
        preferencesManager.cloudflareToken = binding.editCloudflareToken.text.toString()
        preferencesManager.maxTokens = binding.sliderMaxTokens.slider.value.toInt()

        // Capture settings
        preferencesManager.captureInterval = (binding.sliderCaptureInterval.slider.value * 1000).toLong()
        preferencesManager.autoCaptureEnabled = binding.switchAutoCapture.isChecked
        preferencesManager.spinnerEnabled = binding.switchGifEnabled.isChecked

        preferencesManager.spinnerAlpha = binding.GifOpacity.slider.value
        preferencesManager.spinnerSizeDp = binding.GifSize.slider.value.toInt()
        // Display settings
        preferencesManager.textSizeMultiplier = binding.sliderTextSize.slider.value
        preferencesManager.overlayOpacity = binding.sliderOverlayOpacity.slider.value
        preferencesManager.useAlternativeStyle = binding.switchAlternativeStyle.isChecked
        preferencesManager.showAreaBorder = binding.switchShowAreaBorder.isChecked
        val colorValues = resources.getStringArray(R.array.overlay_color_values)
        preferencesManager.translationTextColor = parseColorAt(colorValues, textColorIndex)
        preferencesManager.translationBgColor = parseColorAt(colorValues, bgColorIndex)
        preferencesManager.inPlaceMode = binding.switchInPlaceMode.isChecked
        preferencesManager.mergeOverlapBoxes = binding.switchMergeOverlap.isChecked
        preferencesManager.mergeAdjacentBoxes = binding.switchMergeAdjacent.isChecked
        preferencesManager.mergeAdjacentGapDp = binding.sliderMergeAdjacentGap.slider.value.toInt()
        preferencesManager.useAccessibility = binding.switchUseAccessibility.isChecked
        preferencesManager.translationFont =
            currentFontValues.getOrNull(fontSelIndex) ?: "sans-serif"

        // Control panel styling
        val orientationValues = resources.getStringArray(R.array.control_panel_orientation_values)
        preferencesManager.controlPanelOrientation = orientationValues[
            binding.segmentControlPanelOrientation.selectedIndex.coerceIn(orientationValues.indices)
        ]
        preferencesManager.controlPanelBgColor = parseColorAt(colorValues, panelBgColorIndex)
        preferencesManager.controlPanelScale = binding.sliderControlPanelScale.slider.value
        preferencesManager.controlPanelOpacity = binding.sliderControlPanelOpacity.slider.value

        // Save-to-file: the folder URI is set by the picker callback; only the enable switch is here.
        preferencesManager.saveToFileEnabled = binding.switchSaveToFile.isChecked

        // Cache settings
        preferencesManager.maxCacheEntries = binding.sliderMaxCache.slider.value.toInt()
        preferencesManager.cacheTtlHours = binding.sliderCacheTtl.slider.value.toInt()

        // History settings
        preferencesManager.keepHistoryDays = binding.sliderHistoryDays.slider.value.toInt()

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
}
