package com.example.ocr_translation

import com.example.ocr_translation.ui.theme.OCR_TranslationTheme

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.example.ocr_translation.ScreenCaptureService
import com.example.ocr_translation.databinding.ActivityMainBinding
import com.example.ocr_translation.OverlayService
import com.example.ocr_translation.MainViewModel
import android.content.ComponentName
import android.view.accessibility.AccessibilityManager
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.app.ActivityManager
import android.view.View
import android.widget.AdapterView
import com.example.ocr_translation.TranslationService // Adjust this import if needed
import androidx.fragment.app.Fragment
import com.example.ocr_translation.AreaManagementFragment
import com.google.android.material.tabs.TabLayout
import android.widget.Button

import android.os.Handler
import android.os.Looper




class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private val permissionHelper = PermissionHelper(this)

    private val PERMISSION_CODE = 100
    private val PROJECTION_PERMISSION_CODE = 101

    private var areaManagementFragment: AreaManagementFragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupLanguageSpinners()
        setupAreaManagement() // Add this line

        binding.btnStartTranslation.setOnClickListener {
            Log.d("MainActivity", "btnStartTranslation clicked!") // <-- Add Log
            checkAndRequestPermissions()
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.spinnerSourceLanguage.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val languageCodes = resources.getStringArray(R.array.language_codes)
                viewModel.updateSourceLanguage(languageCodes[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Observe translation active state
        viewModel.translationActive.observe(this) { active ->
            updateTranslationUI(active)
        }
    }

    /*private fun setupAreaManagement() {
        Log.d("MainActivity", "Setting up area management")

        // Find the button by ID
        val btnAreaManagement = findViewById<Button>(R.id.btnAreaManagement)
        if (btnAreaManagement == null) {
            Log.e("MainActivity", "btnAreaManagement not found in layout! Check your XML.")
            return
        }

        btnAreaManagement.setOnClickListener {
            Log.d("MainActivity", "Translation Areas button clicked")
            showAreaManagementFragment()
        }
    }*/

    private fun setupAreaManagement() {
        val btnAreaManagement = findViewById<Button>(R.id.btnAreaManagement)
        btnAreaManagement?.setOnClickListener {
            val intent = Intent(this, AreaManagementActivity::class.java)
            startActivity(intent)
        }
    }

    // Add this method to show the area management fragment
    private fun showAreaManagementFragment() {
        Log.d("MainActivity", "Showing area management fragment")

        if (areaManagementFragment == null) {
            Log.d("MainActivity", "Creating new AreaManagementFragment instance")
            areaManagementFragment = AreaManagementFragment()
        }

        val fragmentContainer = findViewById<View>(R.id.fragment_container)
        if (fragmentContainer == null) {
            Log.e("MainActivity", "Fragment container view not found! Check your layout XML.")
            return
        }

        // Make sure the container is visible before adding the fragment
        fragmentContainer.visibility = View.VISIBLE
        Log.d("MainActivity", "Fragment container made visible")

        try {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, areaManagementFragment!!)
                .addToBackStack("area_management")
                .commit()
            Log.d("MainActivity", "Fragment transaction committed")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error showing fragment", e)
        }
    }


    private fun updateLanguageSettings() {
        val languageCodes = resources.getStringArray(R.array.language_codes)
        val sourceLanguage = languageCodes[binding.spinnerSourceLanguage.selectedItemPosition]
        val targetLanguage = languageCodes[binding.spinnerTargetLanguage.selectedItemPosition]

        // Update preferences
        viewModel.updateTranslationLanguages(sourceLanguage, targetLanguage)

        // Notify active services
        if (viewModel.translationActive.value == true) {
            val intent = Intent("com.example.ocr_translation.ACTION_UPDATE_TRANSLATION_SETTINGS")
            intent.putExtra("sourceLanguage", sourceLanguage)
            intent.putExtra("targetLanguage", targetLanguage)
            sendBroadcast(intent)
        }
    }


    private fun setupLanguageSpinners() {
        val languages = resources.getStringArray(R.array.languages)
        val languageCodes = resources.getStringArray(R.array.language_codes)


        val sourceAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            languages
        )
        binding.spinnerSourceLanguage.adapter = sourceAdapter

        val targetAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            languages
        )
        binding.spinnerTargetLanguage.adapter = targetAdapter

        // Set selections based on saved preferences
        val sourceLanguage = viewModel.selectedSourceLanguage
        val targetLanguage = viewModel.selectedTargetLanguage

        // Find position by code instead of name
        val sourceIndex = languageCodes.indexOf(viewModel.selectedSourceLanguage).takeIf { it >= 0 } ?: 0
        val targetIndex = languageCodes.indexOf(viewModel.selectedTargetLanguage).takeIf { it >= 0 } ?: 0

        binding.spinnerSourceLanguage.setSelection(sourceIndex)
        binding.spinnerTargetLanguage.setSelection(targetIndex)
    }

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

                    // 移除从 PreferencesManager 获取数据的代码:
                    // val preferencesManager = PreferencesManager.getInstance(this)
                    // val prefResultCode = preferencesManager.mediaProjectionResultCode
                    // val prefData = preferencesManager.mediaProjectionData

                    // 直接使用回调传递过来的 resultCode 和 data 启动服务
                    startTranslationService(resultCode, data)
                    // The preference manager should already have the media projection data

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

                            // 移除从 PreferencesManager 获取数据的代码:
                            // val preferencesManager = PreferencesManager.getInstance(this)
                            // val resultCode = preferencesManager.mediaProjectionResultCode
                            // val data = preferencesManager.mediaProjectionData

                            // 直接使用回调传递过来的 resultCode 和 data 启动服务
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

    private fun requestScreenCapture() {
        // Show explanation dialog before requesting screen capture
        AlertDialog.Builder(this)
            .setTitle("Screen Capture Permission")
            .setMessage("This app needs permission to capture your screen to provide OCR translation. Without this permission, the app cannot function properly.")
            .setPositiveButton("Request Permission") { _, _ ->
                // Proceed with the actual permission request
                val mediaProjectionManager = getSystemService(
                    Context.MEDIA_PROJECTION_SERVICE
                ) as MediaProjectionManager

                startActivityForResult(
                    mediaProjectionManager.createScreenCaptureIntent(),
                    PROJECTION_PERMISSION_CODE
                )
            }
            .setNegativeButton("Cancel") { _, _ ->
                Toast.makeText(
                    this,
                    "Screen capture permission is required for this app to function",
                    Toast.LENGTH_LONG
                ).show()
            }
            .setCancelable(false)
            .show()
    }


    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Log.d("MainActivity", "onActivityResult: requestCode=$requestCode, resultCode=$resultCode")

        // Let PermissionHelper handle its specific request codes
        permissionHelper.handleActivityResult(requestCode, resultCode, data)

        // Only handle non-PermissionHelper request codes here
        if (requestCode == PERMISSION_CODE) {
            if (Settings.canDrawOverlays(this)) {
                // Overlay permission granted, proceed with accessibility check
                if (!isAccessibilityServiceEnabled(this)) {
                    showAccessibilityExplanationDialog()
                }
            } else {
                Toast.makeText(this, "Overlay permission is required", Toast.LENGTH_SHORT).show()
            }
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
        startService(Intent(this, OverlayService::class.java))

        // Send screen capture permission to service
        val captureIntent = Intent(this, ScreenCaptureService::class.java)
        captureIntent.putExtra("resultCode", resultCode)
        captureIntent.putExtra("data", data)
        startService(captureIntent)

        // Update ViewModel
        viewModel.setTranslationActive(true)
    }

    private fun stopTranslationService() {
        // Stop services
        Log.i("MainActivity", "Stopping translation services...") // <-- Add Log
        stopService(Intent(this, OverlayService::class.java))
        stopService(Intent(this, ScreenCaptureService::class.java))
        stopAllServices()

        // Update ViewModel
        viewModel.setTranslationActive(false)
    }

    private fun stopAllServices() {
        // First stop the screen capture service
        val captureIntent = Intent(this, ScreenCaptureService::class.java)
        stopService(captureIntent)

        // Then stop the overlay service with a small delay
        Handler(Looper.getMainLooper()).postDelayed({
            val overlayIntent = Intent(this, OverlayService::class.java)
            stopService(overlayIntent)
        }, 100)
    }


    private fun isAccessibilityServiceEnabled(context: Context): Boolean { // 函数名改为更精确的 Enabled
        // 使用 ComponentName 获取规范的服务 ID，更可靠
        val expectedServiceComponentName = ComponentName(context, ScreenCaptureService::class.java)
        val expectedServiceName = expectedServiceComponentName.flattenToString()
        // expectedServiceName 的格式会是 "com.your_package_name/com.your_package_name.ScreenCaptureService"

        Log.d("AccessibilityCheck", "Looking for service: $expectedServiceName")

        val accessibilityManager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager?
            ?: return false // 如果获取 manager 失败，直接返回 false

        // 获取所有已启用的服务列表
        val enabledServices = accessibilityManager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)

        enabledServices?.forEach { serviceInfo ->
            Log.d("AccessibilityCheck", "Found enabled service: ${serviceInfo.id}")
            // 使用 equals 进行精确比较，忽略大小写通常更安全
            if (serviceInfo.id.equals(expectedServiceName, ignoreCase = true)) {
                Log.d("AccessibilityCheck", "Service ($expectedServiceName) IS enabled.")
                return true // 找到匹配的服务，返回 true
            }
        }

        // 遍历完列表都没有找到匹配的服务
        Log.d("AccessibilityCheck", "Service ($expectedServiceName) is NOT enabled.")
        return false
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

    @Override
    override fun onBackPressed() {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
            if (supportFragmentManager.backStackEntryCount == 0) {
                // Hide the fragment container when no fragments are displayed
                findViewById<View>(R.id.fragment_container).visibility = View.GONE
            }
        } else {
            super.onBackPressed()
        }
    }
}

