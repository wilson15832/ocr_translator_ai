package com.example.ocr_translation

import android.app.AlertDialog
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.accessibility.AccessibilityManager
import android.accessibilityservice.AccessibilityServiceInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.ocr_translation.databinding.ActivityMainBinding
import com.example.ocr_translation.ui.AppTheme
import com.example.ocr_translation.ui.OptionPicker


class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private val permissionHelper = PermissionHelper(this)

    private val PERMISSION_CODE = 100

    private companion object {
        /** language_codes[0]; the only source value that has no valid target counterpart. */
        const val AUTO_LANGUAGE_CODE = "auto"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Before inflation: ?attr/colorPrimary is resolved eagerly by the inflater.
        AppTheme.applyTo(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Makes the status bar continue the accent header band instead of sitting above it.
        AppTheme.tintStatusBarWithAccent(this)

        setupAccentSwatches()
        setupLanguageRows()

        binding.btnStartTranslation.setOnClickListener {
            Log.d("MainActivity", "btnStartTranslation clicked!") // <-- Add Log
            TranslationService.getInstance(this).warmUp()
            checkAndRequestPermissions()
        }

        binding.cardTranslationSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java)
                .putExtra(SettingsActivity.EXTRA_SECTION, SettingsActivity.SECTION_TRANSLATION))
        }

        binding.cardOverlaySettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java)
                .putExtra(SettingsActivity.EXTRA_SECTION, SettingsActivity.SECTION_OVERLAY))
        }

        // Observe translation active state
        viewModel.translationActive.observe(this) { active ->
            updateTranslationUI(active)
        }
    }

    /**
     * Builds the accent swatch row. Picking a colour persists it and recreates the activity —
     * the accent is a theme overlay, and views resolve `?attr/colorPrimary` at inflation time,
     * so re-inflating is what repaints them.
     */
    private fun setupAccentSwatches() {
        val container = binding.accentSwatches
        container.removeAllViews()
        val selected = AppTheme.selectedIndex(this)
        val size = dp(30)
        val gap = dp(10)

        AppTheme.accents.forEachIndexed { index, accent ->
            val swatch = FrameLayout(this).apply {
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_accent_swatch)
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this@MainActivity, accent.colorRes)
                )
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    if (index > 0) marginStart = gap
                }
                contentDescription = getString(R.string.accent_color_selected, index + 1)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (index == selected) return@setOnClickListener
                    AppTheme.select(this@MainActivity, index)
                    recreate()
                }
            }
            if (index == selected) {
                val swatchColor = ContextCompat.getColor(this, accent.colorRes)
                swatch.addView(
                    ImageView(this).apply {
                        setImageResource(R.drawable.ic_ios_check)
                        // A white check is invisible on the lighter accents.
                        imageTintList = android.content.res.ColorStateList.valueOf(
                            AppTheme.contrastOn(swatchColor)
                        )
                        layoutParams = FrameLayout.LayoutParams(dp(15), dp(11)).apply {
                            gravity = Gravity.CENTER
                        }
                    }
                )
            }
            container.addView(swatch)
        }
    }

    /**
     * The two language Spinners are now grouped-list rows that open a bottom-sheet picker
     * (design 2a). "Auto-detect" is offered for the source only — it was never a meaningful
     * target, and the old target Spinner listed it purely because both shared one adapter.
     */
    private fun setupLanguageRows() {
        val languages = resources.getStringArray(R.array.languages).toList()
        val codes = resources.getStringArray(R.array.language_codes).toList()
        val targetLanguages = languages.drop(1)
        val targetCodes = codes.drop(1)

        fun sourceIndex() = codes.indexOf(viewModel.selectedSourceLanguage).takeIf { it >= 0 } ?: 0
        fun targetIndex() =
            targetCodes.indexOf(viewModel.selectedTargetLanguage).takeIf { it >= 0 } ?: 0

        fun refresh() {
            binding.rowSourceLanguage.value = languages[sourceIndex()]
            binding.rowTargetLanguage.value = targetLanguages[targetIndex()]
            // Nothing sensible to swap into the target while the source is Auto-detect.
            val canSwap = viewModel.selectedSourceLanguage != AUTO_LANGUAGE_CODE
            binding.rowSwapLanguages.isEnabled = canSwap
            binding.rowSwapLanguages.isClickable = canSwap
            binding.rowSwapLanguages.alpha = if (canSwap) 1f else 0.35f
        }

        binding.rowSourceLanguage.setOnClickListener {
            OptionPicker.show(
                this, getString(R.string.source_language), languages, sourceIndex()
            ) { index ->
                viewModel.updateSourceLanguage(codes[index])
                refresh()
            }
        }

        binding.rowTargetLanguage.setOnClickListener {
            OptionPicker.show(
                this, getString(R.string.target_language), targetLanguages, targetIndex()
            ) { index ->
                viewModel.updateTargetLanguage(targetCodes[index])
                refresh()
            }
        }

        binding.rowSwapLanguages.setOnClickListener {
            val oldSource = viewModel.selectedSourceLanguage
            if (oldSource == AUTO_LANGUAGE_CODE) return@setOnClickListener
            viewModel.updateSourceLanguage(viewModel.selectedTargetLanguage)
            viewModel.updateTargetLanguage(oldSource)
            refresh()
        }

        // Last, so it wins over the isClickable that setOnClickListener forces on.
        refresh()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun updateTranslationUI(active: Boolean) {
        if (active) {
            binding.btnStartTranslation.text = getString(R.string.stop_translation)
            binding.btnStartTranslation.setIconResource(R.drawable.ic_stop)
        } else {
            binding.btnStartTranslation.text = getString(R.string.start_translation)
            binding.btnStartTranslation.setIconResource(R.drawable.ic_start)
        }
    }

    private fun checkAndRequestPermissions() {
        if (viewModel.translationActive.value == true) {
            stopTranslationService()
            return
        }

        // First check if all required permissions are granted already
        if (permissionHelper.areAllPermissionsGranted()) {
            permissionHelper.requestMediaProjectionPermission { type, granted, resultCode, data ->
                if (granted && resultCode != null && data != null) {
                    // 权限被授予，并且我们收到了有效的 resultCode 和 data
                    Log.d(
                        "MainActivity",
                        "Media Projection permission granted. Received data directly via callback."
                    )

                    // Use the resultCode/data delivered by the callback to start the service.
                    startTranslationService(resultCode, data)

                } else if (granted) {
                    // 权限被授予了，但不知何故 resultCode 或 data 是 null (理论上不应发生)
                    Log.e(
                        "MainActivity",
                        "Media Projection granted, but resultCode ($resultCode) or data ($data) is missing!"
                    )
                    showErrorDialog("Error receiving screen capture data after grant.") // 可以显示一个不同的错误信息

                } else {
                    // 用户拒绝了权限
                    Log.w("MainActivity", "Media Projection permission denied by user.")
                    Toast.makeText(
                        this,
                        "Screen capture permission is required for translation",
                        Toast.LENGTH_SHORT
                    ).show()
                    // 可能需要更新UI状态或ViewModel
                    viewModel.setTranslationActive(false)
                }

            }
        } else { // 当 areAllPermissionsGranted() 返回 false 时
            // 请求所有权限（无障碍、悬浮窗等）
            Log.d("MainActivity", "Some basic permissions missing. Requesting all...") // 加个日志
            permissionHelper.requestAllPermissions { allGranted ->
                if (allGranted) {
                    // 所有基本权限都已被（依次）授予
                    // 现在专门请求屏幕录制权限
                    Log.d(
                        "MainActivity",
                        "All basic permissions granted in sequence. Requesting Media Projection..."
                    )

                    // V V V 使用和上面 if 分支完全一样的回调逻辑 V V V
                    permissionHelper.requestMediaProjectionPermission { type, granted, resultCode, data -> // <-- 接收4个参数
                        if (granted && resultCode != null && data != null) {
                            // 权限被授予，并且我们收到了有效的 resultCode 和 data
                            Log.d(
                                "MainActivity",
                                "Media Projection granted (after sequence). Received data directly."
                            )

                            // Use the resultCode/data delivered by the callback to start the service.
                            startTranslationService(resultCode, data)

                        } else if (granted) {
                            // 权限授予了，但数据缺失（理论上不应发生）
                            Log.e(
                                "MainActivity",
                                "Media Projection granted (after sequence), but resultCode ($resultCode) or data ($data) is missing!"
                            )
                            showErrorDialog("Error receiving screen capture data after grant.")
                        } else {
                            // 用户在此路径中拒绝了屏幕录制权限
                            Log.w(
                                "MainActivity",
                                "Media Projection denied by user (after sequence)."
                            )
                            Toast.makeText(
                                this,
                                "Screen capture permission is required",
                                Toast.LENGTH_SHORT
                            ).show()
                            // 可能需要更新UI状态或ViewModel
                            viewModel.setTranslationActive(false)
                        }
                    }
                    // ^ ^ ^ 使用和上面 if 分支完全一样的回调逻辑 ^ ^ ^

                } else {
                    // requestAllPermissions 的回调返回 false，说明基本权限中至少有一个被拒绝了
                    Log.w(
                        "MainActivity",
                        "One or more basic permissions were denied during the sequence."
                    )
                    Toast.makeText(
                        this,
                        "Required permissions were not granted.",
                        Toast.LENGTH_SHORT
                    ).show()
                    viewModel.setTranslationActive(false)
                }
            }
        }
    }

    private fun showErrorDialog(message: String) {
        AlertDialog.Builder(this)
            .setTitle("Error")
            .setMessage(message)
            .setPositiveButton("Try Again") { _, _ -> checkAndRequestPermissions() }
            .setNegativeButton("Cancel", null)
            .show()
    }


    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Log.d("MainActivity", "onActivityResult: requestCode=$requestCode, resultCode=$resultCode")

        // Let PermissionHelper handle its specific request codes
        permissionHelper.handleActivityResult(requestCode, resultCode, data)

        // Only handle non-PermissionHelper request codes here
        if (requestCode == PERMISSION_CODE && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Overlay permission is required", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAccessibilityExplanationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Accessibility Service Required")
            .setMessage("This app needs to use the accessibility service to detect text on screen and provide translation. Please enable the service in the following screen.")
            .setPositiveButton("Open Settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton("Cancel") { _, _ ->
                Toast.makeText(
                    this,
                    "OCR translation requires accessibility service to be enabled",
                    Toast.LENGTH_LONG
                ).show()
            }
            .show()
    }

    private fun startTranslationService(resultCode: Int, data: Intent) {
        // Start overlay service
        Log.i("MainActivity", "Starting translation services...") // <-- Add Log
        Log.d("MainActivity", "onActivityResult: resultCode=$resultCode, data=$data")
        val overlayIntent = Intent(this, OverlayService::class.java)

        // Send screen capture permission to service
        val captureIntent = Intent(this, ScreenCaptureService::class.java)
        captureIntent.putExtra("resultCode", resultCode)
        captureIntent.putExtra("data", data)

        androidx.core.content.ContextCompat.startForegroundService(this, overlayIntent)
        androidx.core.content.ContextCompat.startForegroundService(this, captureIntent)

        // Update ViewModel
        viewModel.setTranslationActive(true)
    }

    private fun stopTranslationService() {
        // Stop services
        Log.i("MainActivity", "Stopping translation services...") // <-- Add Log
        stopService(Intent(this, OverlayService::class.java))
        stopService(Intent(this, ScreenCaptureService::class.java))

        // Update ViewModel
        viewModel.setTranslationActive(false)
    }

    private fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expected = ComponentName(context, ScreenCaptureService::class.java)
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager? ?: return false
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            ?.any { ComponentName.unflattenFromString(it.id) == expected } ?: false
    }

    override fun onResume() {
        super.onResume()

        // Check if services are still running
        if (viewModel.translationActive.value == true) {
            // Query service running state
            val overlayServiceRunning = isServiceRunning(OverlayService::class.java)
            val captureServiceRunning = isServiceRunning(ScreenCaptureService::class.java)

            if (!overlayServiceRunning || !captureServiceRunning) {
                // Services stopped externally, update UI
                viewModel.setTranslationActive(false)
            }
        }
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return manager.getRunningServices(Integer.MAX_VALUE)
            .any { it.service.className == serviceClass.name }
    }

}