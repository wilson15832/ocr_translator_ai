package com.example.ocr_translation

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.fragment.app.FragmentActivity
import android.util.Log
import android.content.ComponentName // <-- Import ComponentName
import android.text.TextUtils         // <-- Import TextUtils

/**
 * Helper class to manage permissions required by the app
 */
class PermissionHelper(private val activity: FragmentActivity) {

    companion object {
        // Keep original request codes if needed for Overlay, but maybe remove ACCESSIBILITY code
        const val OVERLAY_PERMISSION_REQUEST_CODE = 100
        // const val ACCESSIBILITY_PERMISSION_REQUEST_CODE = 101 // No longer needed if using launcher
        const val NOTIFICATION_PERMISSION_REQUEST_CODE = 102
        const val MEDIA_PROJECTION_REQUEST_CODE = 1005 // 使用你日志中看到的请求码，或者最好是在请求时定义并使用同一个常量
    }

    private var currentCallback: ((type: PermissionType, granted: Boolean, resultCode: Int?, data: Intent?) -> Unit)? = null

    enum class PermissionType {
        OVERLAY,
        ACCESSIBILITY,
        NOTIFICATION,
        MEDIA_PROJECTION
    }

    // --- Modern Launchers ---
    private val notificationPermissionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        currentCallback?.invoke(PermissionType.NOTIFICATION, isGranted, null, null)
        // currentCallback = null // Reset if appropriate
    }

    private val mediaProjectionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            // 成功获取权限和数据
            Log.d("PermissionHelper", "Media projection granted. Passing data back directly via callback.")
            // 移除保存到 PreferencesManager 的代码:
            // val preferencesManager = PreferencesManager.getInstance(activity)
            // preferencesManager.saveMediaProjectionData(result.resultCode, result.data!!)

            // 直接通过回调传递结果 (granted=true, 以及 resultCode 和 data)
            currentCallback?.invoke(PermissionType.MEDIA_PROJECTION, true, result.resultCode, result.data)
        } else {
            // 用户拒绝或取消
            Log.w("PermissionHelper", "Media projection denied or cancelled by user.")
            // 通过回调传递失败信息 (granted=false, data=null)
            currentCallback?.invoke(PermissionType.MEDIA_PROJECTION, false, null, null)
            // 你原有的显示重试对话框的逻辑可以保留
            showPermissionRequiredDialog(
                activity.getString(R.string.media_projection_permission_title),
                activity.getString(R.string.media_projection_permission_message)
            ) {
                // 注意：重试时需要确保 currentCallback 仍然有效或重新设置
                requestMediaProjectionPermission(currentCallback ?: { _, _, _, _ -> }) // 可能需要调整这里的回调处理
            }
        }
        // 考虑是否在回调后重置 currentCallback = null，取决于你的回调是一次性还是持续性的
    }


    // Use this launcher for Accessibility Settings Intent
    private val accessibilitySettingsLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ -> // Result isn't used directly, we re-check permission
        Log.d("PermissionHelper", "Returned from Accessibility Settings.")
        // Re-check the permission status after the user returns
        val granted = isAccessibilityPermissionEnabled()
        Log.d("PermissionHelper", "Accessibility granted after re-check: $granted")
        currentCallback?.invoke(PermissionType.ACCESSIBILITY, granted, null, null)
        // currentCallback = null // Reset if appropriate
    }

    // Keep using startActivityForResult for Overlay for now unless you create a specific launcher
    private val overlayPermissionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val granted = isOverlayPermissionGranted()
        Log.d("PermissionHelper", "Overlay granted after re-check: $granted")
        currentCallback?.invoke(PermissionType.OVERLAY, granted, null, null)
        // currentCallback = null // Reset if appropriate
    }


    // --- Permission Check Functions ---

    fun areAllPermissionsGranted(): Boolean {
        val overlay = isOverlayPermissionGranted()
        val accessibility = isAccessibilityPermissionEnabled() // Uses updated check
        val notification = isNotificationPermissionGranted()
        Log.d("PermissionHelper", "areAllPermissionsGranted: Overlay=$overlay, Accessibility=$accessibility, Notification=$notification")
        return overlay && accessibility && notification
    }

    fun isOverlayPermissionGranted(): Boolean {
        return Settings.canDrawOverlays(activity)
    }

    /**
     * [REVISED] Check if accessibility service is enabled using a robust method.
     * Assumes ScreenCaptureService is the target service.
     */
    fun isAccessibilityPermissionEnabled(serviceClass: Class<*> = ScreenCaptureService::class.java): Boolean {
        // Use ComponentName for reliable service ID generation
        val expectedComponentName = ComponentName(activity, serviceClass)
        // Format: com.example.ocr_translation/com.example.ocr_translation.ScreenCaptureService
        val expectedServiceName = expectedComponentName.flattenToString()

        Log.d("PermissionHelper", "Checking for accessibility service: $expectedServiceName")

        val enabledServicesSetting = Settings.Secure.getString(
            activity.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )

        Log.d("PermissionHelper", "Current accessibility settings raw: '$enabledServicesSetting'")

        if (enabledServicesSetting == null || TextUtils.isEmpty(enabledServicesSetting)) {
            Log.d("PermissionHelper", "Accessibility setting string is null or empty.")
            return false
        }

        // Use TextUtils.SimpleStringSplitter for safe parsing of colon-separated list
        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)

        while (colonSplitter.hasNext()) {
            val enabledService = colonSplitter.next()
            Log.d("PermissionHelper", " Checking against enabled service entry: '$enabledService'")
            // Exact match comparison (ignore case for robustness)
            if (enabledService.equals(expectedServiceName, ignoreCase = true)) {
                Log.d("PermissionHelper", "SERVICE MATCH FOUND!")
                return true
            }
        }

        Log.d("PermissionHelper", "Service match NOT found in enabled list.")
        return false
    }

    fun isNotificationPermissionGranted(): Boolean {
        // ... (keep existing implementation) ...
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.checkSelfPermission(
                activity,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true // Granted automatically on older versions
        }
    }


    // --- Permission Request Functions ---

    fun requestMediaProjectionPermission(callback: (type: PermissionType, granted: Boolean, resultCode: Int?, data: Intent?) -> Unit) {
//                                                                     ^^^^^^^^^^^^^^^^^^^^^^^ <-- 添加这两个参数
        currentCallback = callback
        val mediaProjectionManager = activity.getSystemService(
            Context.MEDIA_PROJECTION_SERVICE
        ) as android.media.projection.MediaProjectionManager
        try {
            mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
        } catch (e: Exception) {
            Log.e("PermissionHelper", "Error launching media projection intent", e)
            // 发生错误时，也要通过回调传递失败信息 (granted=false, data=null)
            callback(PermissionType.MEDIA_PROJECTION, false, null, null)
        }
    }

    /**
     * [REVISED] Request single permission type, using modern launchers where possible.
     */
    fun requestPermission(
        type: PermissionType,
        rationaleTitle: String,
        rationaleMessage: String,
        callback: (type: PermissionType, granted: Boolean, resultCode: Int?, data: Intent?) -> Unit
    ) {
        currentCallback = callback // Store callback for this specific request
        when (type) {
            PermissionType.OVERLAY -> {
                if (isOverlayPermissionGranted()) {
                    Log.d("PermissionHelper", "Overlay already granted.")
                    callback(type, true, null, null)
                    return
                }
                Log.d("PermissionHelper", "Requesting Overlay: Showing rationale.")
                showRationaleDialog(rationaleTitle, rationaleMessage) {
                    Log.d("PermissionHelper", "Requesting Overlay: Launching Settings.")
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${activity.packageName}"))
                    // Decide: Use existing startActivityForResult or switch to overlayPermissionLauncher
                    // Using launcher example: overlayPermissionLauncher.launch(intent)
                    activity.startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST_CODE) // Keep old for now
                }
            }

            PermissionType.ACCESSIBILITY -> {
                if (isAccessibilityPermissionEnabled()) { // Uses updated check
                    Log.d("PermissionHelper", "Accessibility already enabled.")
                    callback(type, true, null, null)
                    return
                }
                Log.d("PermissionHelper", "Requesting Accessibility: Showing rationale.")
                showRationaleDialog(rationaleTitle, rationaleMessage) {
                    Log.d("PermissionHelper", "Requesting Accessibility: Launching Settings via modern launcher.")
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    accessibilitySettingsLauncher.launch(intent) // <-- Use the MODERN launcher
                }
            }

            PermissionType.NOTIFICATION -> {
                if (isNotificationPermissionGranted()) {
                    Log.d("PermissionHelper", "Notification already granted.")
                    callback(type, true, null, null)
                    return
                }
                Log.d("PermissionHelper", "Requesting Notification.")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    showRationaleDialog(rationaleTitle, rationaleMessage) {
                        Log.d("PermissionHelper", "Requesting Notification: Launching via modern launcher.")
                        notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                } else {
                    callback(type, true, null, null) // Granted automatically
                }
            }

            PermissionType.MEDIA_PROJECTION -> {
                Log.d("PermissionHelper", "Requesting Media Projection via dedicated method.")
                requestMediaProjectionPermission(callback)
            }
        }
    }

    /**
     * [REVISED] Only needed for permissions still using startActivityForResult (like Overlay currently).
     */
    fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        Log.d("PermissionHelper", "handleActivityResult called! requestCode=$requestCode, resultCode=$resultCode")

        when (requestCode) {
            OVERLAY_PERMISSION_REQUEST_CODE -> {
                // Re-check permission after returning from settings
                val granted = isOverlayPermissionGranted()
                Log.d("PermissionHelper", "Overlay granted after check (via handleActivityResult): $granted")
                currentCallback?.invoke(PermissionType.OVERLAY, granted, null, null)
                // currentCallback = null // Consider if resetting callback is needed here
            }

            else -> {
                // 处理其他可能的请求码或忽略
                Log.d("PermissionHelper", "Ignoring unknown requestCode: $requestCode")
            }
        }
    }

    // --- Sequential Request Logic ---
    fun requestAllPermissions(callback: (Boolean) -> Unit) {
        Log.d("PermissionHelper", "Requesting all permissions...")
        val permissionsToRequest = mutableListOf<PermissionType>()

        if (!isOverlayPermissionGranted()) {
            Log.d("PermissionHelper", "Need Overlay permission.")
            permissionsToRequest.add(PermissionType.OVERLAY)
        }

        if (!isAccessibilityPermissionEnabled()) { // Uses updated check
            Log.d("PermissionHelper", "Need Accessibility permission.")
            permissionsToRequest.add(PermissionType.ACCESSIBILITY)
        }

        if (!isNotificationPermissionGranted()) {
            Log.d("PermissionHelper", "Need Notification permission.")
            permissionsToRequest.add(PermissionType.NOTIFICATION)
        }

        if (permissionsToRequest.isEmpty()) {
            Log.d("PermissionHelper", "All basic permissions already granted.")
            callback(true) // All permissions granted
            return
        }

        Log.d("PermissionHelper", "Starting permission request sequence for: $permissionsToRequest")
        requestPermissionSequence(permissionsToRequest, callback)
    }

    private fun requestPermissionSequence(
        permissions: List<PermissionType>,
        finalCallback: (Boolean) -> Unit,
        index: Int = 0,
        allGrantedSoFar: Boolean = true // Track if all succeeded
    ) {
        if (index >= permissions.size) {
            Log.d("PermissionHelper", "Permission sequence finished. All granted: $allGrantedSoFar")
            finalCallback(allGrantedSoFar) // Return final result
            return
        }

        val permissionType = permissions[index]
        Log.d("PermissionHelper", "Requesting permission at index $index: $permissionType")
        requestPermission(
            permissionType,
            getRationaleTitle(permissionType),
            getRationaleMessage(permissionType)
        ) { type, granted, _, _ ->
            Log.d("PermissionHelper", "Permission result for $type: $granted")
            // Proceed to the next permission only if the current one was granted
            if (!granted) {
                Log.w("PermissionHelper", "Permission $type denied. Stopping sequence.")
                finalCallback(false) // If any permission is denied, the overall result is false
            } else {
                // Recursively call for the next permission
                requestPermissionSequence(permissions, finalCallback, index + 1, true) // Continue sequence
            }
        }
    }


    // --- Rationale/Dialog Functions ---

    private fun getRationaleTitle(type: PermissionType): String {
        // ... (keep existing implementation) ...
        return when(type) {
            PermissionType.OVERLAY -> activity.getString(R.string.overlay_permission_title)
            PermissionType.ACCESSIBILITY -> activity.getString(R.string.accessibility_permission_title)
            PermissionType.NOTIFICATION -> activity.getString(R.string.notification_permission_title)
            PermissionType.MEDIA_PROJECTION -> activity.getString(R.string.media_projection_permission_title)
        }
    }

    private fun getRationaleMessage(type: PermissionType): String {
        // ... (keep existing implementation) ...
        return when(type) {
            PermissionType.OVERLAY -> activity.getString(R.string.overlay_permission_message)
            PermissionType.ACCESSIBILITY -> activity.getString(R.string.accessibility_permission_message)
            PermissionType.NOTIFICATION -> activity.getString(R.string.notification_permission_message)
            PermissionType.MEDIA_PROJECTION -> activity.getString(R.string.media_projection_permission_message)
        }
    }

    private fun showPermissionRequiredDialog(
        title: String,
        message: String,
        onRetry: () -> Unit
    ) {
        // ... (keep existing implementation) ...
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Try Again") { _, _ -> onRetry() }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
                // Optionally notify via callback that user cancelled retry
                // currentCallback?.invoke(PermissionType.MEDIA_PROJECTION, false) // Example
            }
            .setCancelable(false) // Prevent dismissing by tapping outside
            .create()
            .show()
    }


    private fun showRationaleDialog(
        title: String,
        message: String,
        onPositiveAction: () -> Unit
    ) {
        // ... (keep existing implementation, but check callback invocation on cancel) ...
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> onPositiveAction() }
            .setNegativeButton(android.R.string.cancel) { dialog, _ ->
                dialog.dismiss()
                // IMPORTANT: When user cancels rationale, the permission is effectively denied for this attempt.
                // Invoke the callback with 'false'. Need to know which type was cancelled.
                // This requires slightly different handling, maybe pass type to showRationaleDialog
                // Or handle it in the requestPermission where showRationaleDialog is called.
                // For simplicity, let's assume the callback is handled by the launcher/activityResult flow
                // But ideally, cancelling rationale should trigger callback(false) immediately.
                Log.w("PermissionHelper", "User cancelled rationale dialog for $title")
                // Example of immediate callback (might need adjustment based on flow):
                // currentCallback?.invoke(PermissionType.ACCESSIBILITY, false) // Needs type context
            }
            .create()
            .show()
    }
}