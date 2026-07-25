package com.example.ocr_translation

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
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

    // The Spinners became picker rows, so the "selected position" each one used to hold for us
    // now lives here and is read back in saveSettings().
    private var modelIndex = 0
    private var textColorIndex = 0
    private var bgColorIndex = 0
    private var fontSelIndex = 0
    private var foldSelIndex = 0
    private var panelBgColorIndex = 0

    private fun populateModels(provider: LlmProvider, selectCode: String? = null) {
        val names = resources.getStringArray(R.array.models)
        val codes = resources.getStringArray(R.array.model_codes)
        val idx = codes.indices.filter { LlmProvider.fromModel(codes[it]) == provider }
        currentCodes = idx.map { codes[it] }
        currentModelNames = idx.map { names[it] }
        modelIndex = selectCode?.let { currentCodes.indexOf(it) }?.takeIf { it >= 0 } ?: 0
        binding.rowModel.value = currentModelNames.getOrNull(modelIndex)
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
                val ok = copyFontToFilesDir(uri)
                if (ok) {
                    refreshCustomFontLabel()
                    refreshPreview()
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
        currentProvider = LlmProvider.fromModel(preferencesManager.modelName)
        binding.segmentProvider.setSelectionSilently(currentProvider.ordinal)
        binding.switchUseLocalModel.isChecked = preferencesManager.useLocalModel
        populateModels(currentProvider, preferencesManager.modelName)
        binding.editApiKey.setText(preferencesManager.getApiKey(currentProvider))
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
        foldSelIndex = foldIndex(preferencesManager.foldFavorite)
        fontSelIndex = fontIndex(preferencesManager.translationFont)
        binding.switchInPlaceMode.isChecked = preferencesManager.inPlaceMode
        binding.switchUseAccessibility.isChecked = preferencesManager.useAccessibility
        binding.switchMergeOverlap.isChecked = preferencesManager.mergeOverlapBoxes

        // Control panel styling
        binding.segmentControlPanelOrientation.setSelectionSilently(
            controlPanelOrientationIndex(preferencesManager.controlPanelOrientation)
        )
        panelBgColorIndex = colorIndex(preferencesManager.controlPanelBgColor)
        binding.sliderControlPanelOpacity.setSnapped(preferencesManager.controlPanelOpacity)

        // Save-to-file + custom font
        binding.switchSaveToFile.isChecked = preferencesManager.saveToFileEnabled
        refreshSaveFolderLabel()
        refreshCustomFontLabel()

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
                currentProvider = np
                populateModels(np)                                   // 切到新公司模型（选第一个）
                binding.editApiKey.setText(preferencesManager.getApiKey(np))
            }
        }

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

        // Speech-bubble style changes the preview's corner radius / border
        binding.switchAlternativeStyle.switch.setOnCheckedChangeListener { _, _ -> refreshPreview() }

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
        val fonts = resources.getStringArray(R.array.font_options).toList()
        val foldOptions = resources.getStringArray(R.array.fold_options).toList()

        bindPicker(binding.rowModel, R.string.model, { currentModelNames }, { modelIndex }) {
            modelIndex = it
        }
        bindPicker(binding.rowFontColor, R.string.font_color, { colors }, { textColorIndex }) {
            textColorIndex = it
        }
        bindPicker(binding.rowBgColor, R.string.background_color, { colors }, { bgColorIndex }) {
            bgColorIndex = it
        }
        bindPicker(binding.rowFont, R.string.font_style, { fonts }, { fontSelIndex }) {
            fontSelIndex = it
        }
        bindPicker(binding.rowFoldFavorite, R.string.fold_favorite, { foldOptions }, { foldSelIndex }) {
            foldSelIndex = it
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
        binding.rowFont.value =
            resources.getStringArray(R.array.font_options).getOrNull(fontSelIndex)
        binding.rowFoldFavorite.value =
            resources.getStringArray(R.array.fold_options).getOrNull(foldSelIndex)
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

    /** Mirrors OverlayService.resultTypeface(): custom file first, bundled font, then family. */
    private fun previewTypeface(): android.graphics.Typeface {
        val customPath = preferencesManager.customFontPath
        if (customPath.isNotEmpty()) {
            try {
                val f = File(customPath)
                if (f.exists() && f.canRead()) return android.graphics.Typeface.createFromFile(f)
            } catch (e: Exception) {
                Log.w(TAG, "Preview: custom font load failed", e)
            }
        }
        val name = resources.getStringArray(R.array.font_values).getOrNull(fontSelIndex)
            ?: return android.graphics.Typeface.DEFAULT
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

    private fun refreshCustomFontLabel() {
        val path = preferencesManager.customFontPath
        binding.btnLoadCustomFont.value =
            if (path.isEmpty()) getString(R.string.custom_font_none) else File(path).name
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
        refreshPreview()
        Toast.makeText(this, R.string.custom_font_cleared, Toast.LENGTH_SHORT).show()
        updateActiveServices()
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
        val foldValues = resources.getStringArray(R.array.fold_option_values)
        preferencesManager.foldFavorite = foldValues[foldSelIndex.coerceIn(foldValues.indices)]
        preferencesManager.inPlaceMode = binding.switchInPlaceMode.isChecked
        preferencesManager.mergeOverlapBoxes = binding.switchMergeOverlap.isChecked
        preferencesManager.useAccessibility = binding.switchUseAccessibility.isChecked
        val fontValues = resources.getStringArray(R.array.font_values)
        preferencesManager.translationFont = fontValues[fontSelIndex.coerceIn(fontValues.indices)]

        // Control panel styling
        val orientationValues = resources.getStringArray(R.array.control_panel_orientation_values)
        preferencesManager.controlPanelOrientation = orientationValues[
            binding.segmentControlPanelOrientation.selectedIndex.coerceIn(orientationValues.indices)
        ]
        preferencesManager.controlPanelBgColor = parseColorAt(colorValues, panelBgColorIndex)
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
