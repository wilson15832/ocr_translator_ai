package com.example.ocr_translation

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.app.Service
import android.os.Build
import android.app.Activity
import com.example.ocr_translation.TranslationService
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.view.accessibility.AccessibilityEvent
import com.example.ocr_translation.TranslationService.TranslatedBlock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.example.ocr_translation.PreferencesManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent // <-- Import
import android.content.Context // <-- Import
import androidx.core.app.NotificationCompat
import android.os.Handler
import android.os.HandlerThread // <-- Import
import android.os.Looper     // <-- Import

import android.graphics.Matrix
import android.hardware.SensorManager
import android.util.DisplayMetrics
import android.view.OrientationEventListener
import android.view.Surface
import android.view.WindowManager
import android.graphics.RectF

import android.graphics.Rect
import android.content.BroadcastReceiver
import android.content.IntentFilter


class ScreenCaptureService : AccessibilityService() {

    private val TAG = "ScreenCaptureService_OnStart" // 使用一个统一的 TAG

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private lateinit var translationService: TranslationService
    private lateinit var translationCache: TranslationCache

    private var mediaProjectionCallback: MediaProjection.Callback? = null
    private var callbackHandler: Handler? = null // Handler for the callback
    private var handlerThread: HandlerThread? = null // Thread for the handler

    private var activeTranslationArea: RectF? = null
    private lateinit var preferencesManager: PreferencesManager // Add PreferencesManager reference

    companion object {
        private const val FOREGROUND_NOTIFICATION_ID = 1003 // 通知 ID，不能和 OverlayService 冲突
        private const val CAPTURE_CHANNEL_ID = "screen_capture_channel"
    }

    // Screen metrics
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    // Capture settings
    private var captureInterval = 5000L // 1 second default
    private var isRunning = false

    private var currentRotation = Surface.ROTATION_0
    private val orientationListener by lazy {
        object : OrientationEventListener(this, SensorManager.SENSOR_DELAY_NORMAL) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return

                val display = (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
                val newRotation = display.rotation

                if (newRotation != currentRotation) {
                    Log.d("ScreenCaptureService", "Orientation changed from $currentRotation to $newRotation")
                    currentRotation = newRotation
                    updateCaptureWithRotation()
                }
            }
        }
    }

    private val areaReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d("ScreenCaptureService", "areaReceiver received action: ${intent.action}") // <-- 添加此日志
            when (intent.action) {
                "com.example.ocr_translation.ACTION_SET_TRANSLATION_AREA" -> {
                    Log.d("ScreenCaptureService", "Received SET_TRANSLATION_AREA broadcast")

                    if (intent.hasExtra("area_left") && intent.hasExtra("area_top") &&
                        intent.hasExtra("area_right") && intent.hasExtra("area_bottom")) {

                        val left = intent.getFloatExtra("area_left", 0f)
                        val top = intent.getFloatExtra("area_top", 0f)
                        val right = intent.getFloatExtra("area_right", 0f)
                        val bottom = intent.getFloatExtra("area_bottom", 0f)

                        val newRect = RectF(left, top, right, bottom)
                        val areaName = intent.getStringExtra("area_name") ?: "Custom Area"

                        Log.d("ScreenCaptureService", "Setting translation area: $areaName ($newRect)")
                        updateActiveTranslationArea(newRect)
                    } else {
                        Log.e("ScreenCaptureService", "Missing area coordinates in broadcast")
                    }
                }
                "com.example.ocr_translation.ACTION_CLEAR_TRANSLATION_AREA" -> {
                    Log.d("ScreenCaptureService", "Clearing translation area")
                    updateActiveTranslationArea(null)
                }
            }
        }
    }


    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.e("ScreenCaptureService", "======= ScreenCaptureService.onCreate() called =======")

        handlerThread = HandlerThread("MediaProjectionCallbackThread").apply { start() }
        callbackHandler = Handler(handlerThread!!.looper)

        // Initialize PreferencesManager
        preferencesManager = PreferencesManager.getInstance(this)

        setupMediaProjectionCallback()
        // Load capture interval from preferences
        setCaptureInterval(preferencesManager.captureInterval)

        // Initialize TranslationService and load configuration
        translationService = TranslationService.getInstance(this)
        translationService.loadConfig(preferencesManager)
        // Initialize TranslationCache
        translationCache = TranslationCache(applicationContext)

        // Initialize current rotation
        val display = (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        currentRotation = display.rotation

        // Enable orientation listener
        if (orientationListener.canDetectOrientation()) {
            orientationListener.enable()
            Log.d("ScreenCaptureService", "Orientation listener enabled")
        }

        val filter = IntentFilter().apply {
            addAction("com.example.ocr_translation.ACTION_SET_TRANSLATION_AREA")
            addAction("com.example.ocr_translation.ACTION_CLEAR_TRANSLATION_AREA")
        }
        registerReceiver(areaReceiver, filter, Context.RECEIVER_NOT_EXPORTED)

        // Restore any previously saved area
        restoreActiveTranslationArea()
    }

    private fun updateCaptureWithRotation() {
        // Update screen metrics
        updateScreenMetrics()

        // Broadcast rotation change for overlay service
        broadcastRotationChange()

        // Only proceed if running
        if (!isRunning || mediaProjection == null) return

        try {
            // We need to recreate the ImageReader with new dimensions
            Log.d("ScreenCaptureService", "Recreating ImageReader for new dimensions: $screenWidth x $screenHeight")

            // Release old resources but keep virtual display reference
            imageReader?.close()

            // Create new ImageReader with updated dimensions
            imageReader = ImageReader.newInstance(
                screenWidth, screenHeight,
                PixelFormat.RGBA_8888, 2
            )

            // Update the virtual display's surface
            virtualDisplay?.surface = imageReader?.surface

            // Resize virtual display to match new dimensions
            virtualDisplay?.resize(screenWidth, screenHeight, screenDensity)

            Log.d("ScreenCaptureService", "Successfully updated capture for rotation change")
        } catch (e: Exception) {
            Log.e("ScreenCaptureService", "Error updating capture for rotation", e)
        }
    }

    /*override fun onServiceConnected() {
        super.onServiceConnected()
        // Initialize screen metrics
        //val metrics = resources.displayMetrics
        //screenWidth = metrics.widthPixels
        //screenHeight = metrics.heightPixels
        //screenDensity = metrics.densityDpi
        updateScreenMetrics()

        // Initialize image reader for screenshots
        //imageReader = ImageReader.newInstance(
        //    screenWidth, screenHeight,
        //    PixelFormat.RGBA_8888, 2
        //)
        initializeImageReader()
    }*/

    private fun updateScreenMetrics() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = wm.defaultDisplay
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)

        // Get correct screen dimensions based on rotation
        val isPortrait = currentRotation == Surface.ROTATION_0 || currentRotation == Surface.ROTATION_180
        screenWidth = if (isPortrait) metrics.widthPixels else metrics.heightPixels
        screenHeight = if (isPortrait) metrics.heightPixels else metrics.widthPixels
        screenDensity = metrics.densityDpi

        Log.d("ScreenCaptureService",
            "Screen metrics updated: w=$screenWidth, h=$screenHeight, density=$screenDensity, rotation=$currentRotation")

        // Update OCR processor with new rotation
        OCRProcessor.setScreenRotation(currentRotation)
    }

    private fun broadcastRotationChange() {
        val intent = Intent("com.example.ocr_translation.ACTION_ROTATION_CHANGED")
        intent.putExtra("rotation", currentRotation)
        intent.putExtra("screenWidth", screenWidth)
        intent.putExtra("screenHeight", screenHeight)
        sendBroadcast(intent)
        Log.d("ScreenCaptureService", "Broadcast rotation change: $currentRotation")
    }

    private fun initializeImageReader() {
        // Close existing imageReader if it exists
        imageReader?.close()

        try {
            imageReader = ImageReader.newInstance(
                screenWidth, screenHeight,
                PixelFormat.RGBA_8888, 2
            )
            Log.d("ScreenCaptureService", "ImageReader initialized with w:$screenWidth, h:$screenHeight")
        } catch (e: IllegalArgumentException) {
            Log.e("ScreenCaptureService", "Error initializing ImageReader", e)
        }
    }


    private fun setupMediaProjectionCallback() {
        mediaProjectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w("ScreenCaptureService", "MediaProjection.Callback.onStop() called!")
                // 确保在主线程或合适的地方停止服务和清理资源
                Handler(Looper.getMainLooper()).post {
                    stopCapture() // 调用清理方法
                    stopSelf()    // 停止服务
                }
            }
        }
    }

    private fun notifyRotationChanged(rotation: Int) {
        val intent = Intent("com.example.ocr_translation.ACTION_ROTATION_CHANGED")
        intent.putExtra("rotation", rotation)
        sendBroadcast(intent)
        Log.d("ScreenCaptureService", "Broadcasted rotation change: $rotation")
    }

    fun startCapture(resultCode: Int, data: Intent) {
        Log.d("ScreenCaptureService", "startCapture() called.") // <-- 日志1
        if (isRunning) {
            Log.w("ScreenCaptureService", "Capture already running, ignoring call.") // <-- 日志2
            return
        }

        // Get projection manager and create media projection
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        Log.d("ScreenCaptureService", "Registering MediaProjection callback...")
        mediaProjection?.registerCallback(mediaProjectionCallback!!, callbackHandler) // 使用 !! 因为前面检查了非空

        updateScreenMetrics()

        if (imageReader == null) {
            Log.d("ScreenCaptureService", "Initializing ImageReader in startCapture.")
            val metrics = resources.displayMetrics
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
            screenDensity = metrics.densityDpi // 确保 screenDensity 也被初始化
            try {
                imageReader = ImageReader.newInstance(
                    screenWidth, screenHeight,
                    PixelFormat.RGBA_8888, 2
                )
                Log.d("ScreenCaptureService", "ImageReader initialized.")
            } catch (e: IllegalArgumentException) {
                Log.e("ScreenCaptureService", "Error initializing ImageReader", e)
                stopCapture()
                stopSelf()
                return
            }
        }

        try {
            Log.d("ScreenCaptureService", "Creating VirtualDisplay...")
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenCapture",
                screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, callbackHandler // 可以传递同一个后台 Handler
            )
            Log.d("ScreenCaptureService", "VirtualDisplay created successfully.")
            isRunning = true
            startPeriodicCapture() // 开始截图循环
        } catch (e: Exception) { // 捕获更广泛的异常，包括可能的 SecurityException 等
            Log.e("ScreenCaptureService", "Error creating VirtualDisplay", e)
            stopCapture() // 创建失败时清理
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 使用上面定义的 TAG
        Log.d(TAG, "onStartCommand received intent: $intent")

        // 确保服务始终在前台运行（这是截图服务通常需要的权限）
        // createForegroundNotification() 方法需要在类中实现
        val notification = createForegroundNotification()
        try {
            Log.d(TAG, "Starting foreground service...")
            // startForeground 会根据你的应用状态和 Android 版本，
            // 将服务提升到前台状态，防止被系统轻易杀死
            startForeground(FOREGROUND_NOTIFICATION_ID, notification)
            Log.d(TAG, "Foreground service started.")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground service", e)
            // 处理异常，例如 Android 12+ 在后台启动限制
            // 如果无法启动前台服务，通常无法进行截图，此时停止服务可能是合理的
            stopSelf(startId)
            return Service.START_NOT_STICKY
        }


        // 使用 when 来根据 Intent 的 Action 分发不同的处理逻辑
        when (intent?.action) {
            // === 处理启动 MediaProjection 的 Intent ===
            // 这个 Intent 预计包含 resultCode 和 data extra
            // 它可能没有特定的 action，或者 action 为 null，或者系统默认 action (如 ACTION_MAIN)
            null, Intent.ACTION_MAIN -> {
                Log.d(TAG, "Received null or main action intent. Checking for MediaProjection extras.")

                // 检查是否包含启动 MediaProjection 所需的 extra
                if (intent?.hasExtra("resultCode") == true && intent.hasExtra("data") == true) {
                    val resultCode = intent.getIntExtra("resultCode", -1)
                    val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra("data", Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra("data") as? Intent
                    }

                    if (resultCode != Activity.RESULT_OK || data == null) {
                        Log.e(TAG, "Invalid MediaProjection result code or data. Stopping service.")
                        // 只有当 MediaProjection 启动所需的数据无效时，才停止服务
                        stopSelf(startId)
                        return Service.START_NOT_STICKY
                    }

                    Log.d(TAG, "Received valid MediaProjection result. Calling startCapture...")
                    // 调用 startCapture 方法来设置 MediaProjection, VirtualDisplay 和 ImageReader
                    startCapture(resultCode, data)

                } else {
                    // 如果服务启动时没有 MediaProjection extra (例如服务被系统重启，或者被没有发这些extra的intent启动)
                    // 这并不一定是一个错误，服务可以保持运行等待下次启动 MediaProjection 的 Intent
                    Log.w(TAG, "Service started without required MediaProjection extras. It will remain running and wait for capture start intent.")
                    // !!! 重点：不要在这里无条件 stopSelf !!!
                    // return Service.START_NOT_STICKY // 也不需要无条件返回，服务继续运行等待命令
                }
            }

            // === 处理设置翻译区域的 Intent ===
            // 这个 Intent 预计由 AreaManagementFragment 发送，包含区域的 float 坐标
            "com.example.ocr_translation.ACTION_SET_TRANSLATION_AREA" -> {
                Log.d(TAG, "Received ACTION_SET_TRANSLATION_AREA intent.")

                // 从 Intent 中获取四个 float 值来重建 RectF
                val left = intent.getFloatExtra("area_left", Float.NaN)
                val top = intent.getFloatExtra("area_top", Float.NaN)
                val right = intent.getFloatExtra("area_right", Float.NaN)
                val bottom = intent.getFloatExtra("area_bottom", Float.NaN)

                val areaName = intent.getStringExtra("area_name") ?: "Custom Area"

                // 检查获取到的坐标是否有效，并重建 RectF
                val rectF = if (!left.isNaN() && !top.isNaN() && !right.isNaN() && !bottom.isNaN()) {
                    // 可以选择在这里添加 RectF 的尺寸有效性检查 (width() > 0, height() > 0)
                    RectF(left, top, right, bottom)
                } else {
                    null // 如果任何一个坐标无效，视为区域无效
                }

                if (rectF != null) {
                    Log.d(TAG, "Setting translation area: $areaName ($rectF)")
                    // 调用方法更新服务中用于裁剪的区域变量
                    updateActiveTranslationArea(rectF) // 这个方法会更新 activeTranslationArea 并保存到 Preferences
                } else {
                    // 记录错误，但服务继续运行
                    Log.e(TAG, "ACTION_SET_TRANSLATION_AREA intent missing or invalid area coordinates.")
                }
            }

            // === 处理清除翻译区域的 Intent ===
            // 这个 Intent 预计由 AreaManagementFragment 发送
            "com.example.ocr_translation.ACTION_CLEAR_TRANSLATION_AREA" -> {
                Log.d(TAG, "Received ACTION_CLEAR_TRANSLATION_AREA intent.")
                // 调用方法清除区域变量
                updateActiveTranslationArea(null) // 这个方法会设置 activeTranslationArea 为 null 并清除 Preferences
            }

            // === 处理其他未知 Action 的 Intent (可选) ===
            else -> {
                Log.w(TAG, "Received unhandled action: ${intent?.action}")
                // 对于未知 Intent，通常服务会忽略并继续运行
            }
        }

        // 根据你的服务设计，返回适当的标志：
        // START_STICKY: 服务在被系统杀死后会尝试重新创建，且在内存充足时重新发送最后一个 START 命令的 Intent (如果是 null 则发 null intent)。适合需要持续运行的服务。
        // START_NOT_STICKY: 服务被杀死后不会自动重建，除非有新的 START 命令。
        // START_REDELIVER_INTENT: 服务被杀死后会重新创建，并保证重新发送最后一个 START 命令的 Intent，即使 Intent 为 null。
        // 对于截图服务，START_STICKY 通常是合适的，它会在内存充足时尝试保持服务运行。
        return Service.START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CAPTURE_CHANNEL_ID,
                getString(R.string.capture_notification_channel_name), // 在 strings.xml 添加 "Screen Capture"
                NotificationManager.IMPORTANCE_LOW // 低优先级，避免干扰用户
            ).apply {
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun cropBitmapToArea(bitmap: Bitmap, area: RectF): Bitmap? {
        if (bitmap.isRecycled) {
            Log.e("ScreenCaptureService", "Cannot crop recycled bitmap")
            return null
        }

        val screenRect = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())

        // Ensure area is within screen boundaries
        val validArea = RectF().apply {
            left = area.left.coerceIn(screenRect.left, screenRect.right)
            top = area.top.coerceIn(screenRect.top, screenRect.bottom)
            right = area.right.coerceIn(screenRect.left, screenRect.right)
            bottom = area.bottom.coerceIn(screenRect.top, screenRect.bottom)
        }

        // Additional validation
        if (validArea.right <= validArea.left || validArea.bottom <= validArea.top) {
            Log.e("ScreenCaptureService", "Invalid crop area: $validArea")
            return null
        }

        // Check if the area has valid dimensions
        return if (validArea.width() > 10 && validArea.height() > 10) {
            try {
                // Convert to integer coordinates for cropping
                val cropRect = Rect(
                    validArea.left.toInt(),
                    validArea.top.toInt(),
                    validArea.right.toInt(),
                    validArea.bottom.toInt()
                )

                // Final validation of the Rect
                if (cropRect.left < 0 || cropRect.top < 0 ||
                    cropRect.right > bitmap.width || cropRect.bottom > bitmap.height ||
                    cropRect.width() <= 0 || cropRect.height() <= 0) {

                    Log.e("ScreenCaptureService", "Crop rectangle out of bounds: $cropRect, bitmap: ${bitmap.width}x${bitmap.height}")
                    return null
                }

                Log.d("ScreenCaptureService", "Cropping bitmap to: $cropRect")
                Bitmap.createBitmap(
                    bitmap,
                    cropRect.left,
                    cropRect.top,
                    cropRect.width(),
                    cropRect.height()
                )
            } catch (e: Exception) {
                Log.e("ScreenCaptureService", "Error cropping bitmap: ${e.message}", e)
                null
            }
        } else {
            Log.d("ScreenCaptureService", "Area too small to crop, returning null")
            null
        }
    }

    private fun createForegroundNotification(): Notification {
        // 创建一个点击通知时打开应用主界面的 Intent
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)

        return NotificationCompat.Builder(this, CAPTURE_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.capture_notification_text)) // 在 strings.xml 添加 "Screen capture service is running"
            .setSmallIcon(R.drawable.ic_stat_screen_capture) // 添加一个小图标资源
            .setContentIntent(pendingIntent) // 点击通知的操作
            .setOngoing(true) // 使通知不可滑动移除
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startPeriodicCapture() {
        serviceScope.launch {
            Log.d("ScreenCaptureService", "Periodic capture loop coroutine started.") // <-- 日志7
            while (isRunning) {
                Log.d("ScreenCaptureService", "Top of capture loop iteration.") // <-- 日志8
                try {
                    captureScreen()?.let { bitmap ->
                        processScreenCapture(bitmap)
                    }
                } catch (e: Exception) {
                    Log.e("ScreenCaptureService", "Capture cycle failed", e)
                    // Consider adding a short delay before retrying on error
                    kotlinx.coroutines.delay(1000)
                }
                kotlinx.coroutines.delay(captureInterval)
            }
        }
        Log.d("ScreenCaptureService", "Periodic capture loop exited.") // <-- 日志12
    }

    private suspend fun processScreenCapture(bitmap: Bitmap) {
        Log.d("ScreenCaptureService", "processScreenCapture() called for bitmap: $bitmap")

        // Create a local reference to avoid race conditions or nullability issues
        val localBitmap = bitmap
        var croppedBitmap: Bitmap? = null

        try {
            // Load the latest settings before processing
            translationService.loadConfig(preferencesManager) // Ensure latest settings are used

            val sourceLanguage = preferencesManager.sourceLanguage
            val targetLanguage = preferencesManager.targetLanguage
            OCRProcessor.setLanguage(sourceLanguage)

            Log.d("ScreenCaptureService", "Attempting to crop bitmap to area: $activeTranslationArea")
            // Apply the custom area if one is active - safely with null checks
            if (activeTranslationArea != null) {
                try {
                    Log.d("ScreenCaptureService", "Attempting to crop bitmap to area: $activeTranslationArea")
                    croppedBitmap = cropBitmapToArea(localBitmap, activeTranslationArea!!)

                    // If cropping failed, fall back to the original bitmap
                    if (croppedBitmap == null) {
                        Log.w("ScreenCaptureService", "Cropping failed, using original bitmap")
                        croppedBitmap = localBitmap
                    }
                } catch (e: Exception) {
                    Log.e("ScreenCaptureService", "Error cropping bitmap", e)
                    // On error, fall back to original bitmap
                    croppedBitmap = localBitmap
                }
            } else {
                // No active area, use original bitmap
                croppedBitmap = localBitmap
            }

            // Safety check for bitmap
            if (croppedBitmap == null || croppedBitmap.isRecycled) {
                Log.e("ScreenCaptureService", "Bitmap is null or recycled before OCR, aborting")
                return
            }

            // Use a final reference for the bitmap in the callback
            val finalBitmap = croppedBitmap
            val originalBitmap = localBitmap

            Log.d("ScreenCaptureService", "Calling OCRProcessor.processImage...")
            OCRProcessor.processImage(finalBitmap) { extractedText ->
                try {
                    Log.d("ScreenCaptureService", "OCR callback received with text: ${extractedText?.size ?: 0} items")

                    // Only process if we have text and bitmaps are still valid
                    if (extractedText?.isNotEmpty() == true && !finalBitmap.isRecycled) {
                        serviceScope.launch {
                            try {
                                Log.d("ScreenCaptureService", "Starting translation for ${extractedText.size} items")
                                val translatedResultList = translationService.translateText(
                                    extractedText,
                                    sourceLanguage,
                                    targetLanguage
                                )
                                Log.d("ScreenCaptureService", "Translation finished. Result count: ${translatedResultList.size}")
                                OverlayService.showTranslation(translatedResultList)
                            } catch (e: Exception) {
                                Log.e("ScreenCaptureService", "Translation task failed", e)
                                val errorBlock = TranslationService.TranslatedBlock(
                                    originalText = "Error",
                                    translatedText = "Translation failed: ${e.message ?: "Unknown error"}",
                                    boundingBox = android.graphics.Rect(50, 50, 800, 150),
                                    sourceLanguage ?: "N/A",
                                    targetLanguage ?: "N/A"
                                )
                                OverlayService.showTranslation(listOf(errorBlock))
                            } finally {
                                // Recycle bitmaps safely
                                recycleBitmapSafely(finalBitmap)
                                if (finalBitmap != originalBitmap) {
                                    recycleBitmapSafely(originalBitmap)
                                }
                            }
                        }
                    } else {
                        // No text found
                        Log.d("ScreenCaptureService", "OCR returned no text, skipping translation")
                        OverlayService.showTranslation(emptyList())

                        // Recycle bitmaps safely
                        recycleBitmapSafely(finalBitmap)
                        if (finalBitmap != originalBitmap) {
                            recycleBitmapSafely(originalBitmap)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("ScreenCaptureService", "Error in OCR callback", e)

                    // Recycle bitmaps on error
                    recycleBitmapSafely(finalBitmap)
                    if (finalBitmap != originalBitmap) {
                        recycleBitmapSafely(originalBitmap)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ScreenCaptureService", "Error in processScreenCapture", e)

            // Recycle original bitmap if we failed before OCR
            if (!localBitmap.isRecycled) {
                try {
                    localBitmap.recycle()
                } catch (e: Exception) {
                    Log.e("ScreenCaptureService", "Error recycling bitmap", e)
                }
            }

            // Recycle cropped bitmap if it exists and is different
            if (croppedBitmap != null && croppedBitmap != localBitmap && !croppedBitmap.isRecycled) {
                try {
                    croppedBitmap.recycle()
                } catch (e: Exception) {
                    Log.e("ScreenCaptureService", "Error recycling cropped bitmap", e)
                }
            }
        }
    }

    // Helper method to safely recycle bitmaps
    private fun recycleBitmapSafely(bitmap: Bitmap?) {
        if (bitmap != null && !bitmap.isRecycled) {
            try {
                bitmap.recycle()
            } catch (e: Exception) {
                Log.e("ScreenCaptureService", "Error recycling bitmap", e)
            }
        }
    }


    private fun captureScreen(): Bitmap? {
        var bitmap: Bitmap? = null
        var image: Image? = null

        try {
            image = imageReader?.acquireLatestImage()
            if (image != null) {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * screenWidth

                // Create bitmap with exactly the right size based on buffer dimensions
                val width = screenWidth + rowPadding / pixelStride
                val height = screenHeight

                Log.d("ScreenCaptureService", "Creating bitmap with dimensions: $width x $height")

                try {
                    bitmap = Bitmap.createBitmap(
                        width,
                        height,
                        Bitmap.Config.ARGB_8888
                    )
                    bitmap.copyPixelsFromBuffer(buffer)
                } catch (e: Exception) {
                    Log.e("ScreenCaptureService", "Error creating or copying bitmap: ${e.message}")
                    e.printStackTrace()

                    // Try with an alternative approach if the first one fails
                    if (bitmap == null || bitmap.isRecycled) {
                        try {
                            // For safety, ensure we're creating a bitmap with dimensions
                            // that won't exceed the buffer size
                            val bufferSize = buffer.remaining()
                            val estimatedWidth = Math.min(width, Math.sqrt(bufferSize / 4.0).toInt())
                            val estimatedHeight = Math.min(height, bufferSize / (estimatedWidth * 4))

                            Log.d("ScreenCaptureService", "Retry with safe dimensions: $estimatedWidth x $estimatedHeight")

                            bitmap = Bitmap.createBitmap(
                                estimatedWidth,
                                estimatedHeight,
                                Bitmap.Config.ARGB_8888
                            )
                            bitmap.copyPixelsFromBuffer(buffer)
                        } catch (e2: Exception) {
                            Log.e("ScreenCaptureService", "Alternative bitmap creation also failed: ${e2.message}")
                            bitmap?.recycle()
                            bitmap = null
                        }
                    }
                }
            } else {
                Log.d("ScreenCaptureService", "acquireLatestImage() returned null")
            }
        } catch (e: Exception) {
            Log.e("ScreenCaptureService", "Exception in captureScreen()", e)
        } finally {
            image?.close()
        }

        return bitmap
    }

    fun stopCapture() {
        Log.d("ScreenCaptureService", "stopCapture() called.")
        if (!isRunning && virtualDisplay == null && mediaProjection == null) {
            Log.d("ScreenCaptureService", "stopCapture called but nothing seems to be running.")
            return // 避免重复或无效的停止操作
        }
        isRunning = false // 停止截图循环

        // 6. 注销 Callback
        try {
            mediaProjectionCallback?.let { callback ->
                Log.d("ScreenCaptureService", "Unregistering MediaProjection callback.")
                mediaProjection?.unregisterCallback(callback)
            }
        } catch (e: Exception) {
            Log.w("ScreenCaptureService", "Error unregistering MediaProjection callback", e)
        }
        // mediaProjectionCallback = null // 不在这里置 null，因为 onStop 可能还需要它

        // 按顺序释放资源
        try {
            virtualDisplay?.release()
            Log.d("ScreenCaptureService", "VirtualDisplay released.")
        } catch (e: Exception){ Log.w("ScreenCaptureService", "Error releasing VirtualDisplay", e) }
        virtualDisplay = null

        // ImageReader 通常在 VirtualDisplay 释放后关闭
        try {
            imageReader?.close()
            Log.d("ScreenCaptureService", "ImageReader closed.")
        } catch (e: Exception) { Log.w("ScreenCaptureService", "Error closing ImageReader", e) }
        imageReader = null

        try {
            mediaProjection?.stop()
            Log.d("ScreenCaptureService", "MediaProjection stopped.")
        } catch (e: Exception) { Log.w("ScreenCaptureService", "Error stopping MediaProjection", e) }
        mediaProjection = null
        Log.d("ScreenCaptureService", "stopCapture finished.")
    }

    fun setCaptureInterval(interval: Long) {
        captureInterval = interval
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Process accessibility events if needed
    }

    override fun onInterrupt() {
        // Handle interruption
    }

    // Add utility method to rotate bitmap
    private fun rotateBitmap(source: Bitmap, angle: Float): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(angle)
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun broadcastAreaStatus() {
        val intent = Intent("com.example.ocr_translation.ACTION_AREA_STATUS_UPDATE")
        intent.putExtra("has_active_area", activeTranslationArea != null)
        if (activeTranslationArea != null) {
            intent.putExtra("area_rect", activeTranslationArea)
        }
        sendBroadcast(intent)
    }

    // Call this method whenever activeTranslationArea changes
    private fun updateActiveTranslationArea(area: RectF?) {
        Log.d("ScreenCaptureService", "updateActiveTranslationArea called with: $area")
        activeTranslationArea = area
        broadcastAreaStatus()

        // Save to preferences
        val preferencesManager = PreferencesManager.getInstance(this)
        if (area == null) {
            preferencesManager.clearActiveTranslationArea()
        } else {
            preferencesManager.saveActiveTranslationArea(area)
        }

        // Extra logging to verify
        Log.d("ScreenCaptureService", "After update, activeTranslationArea is now: $activeTranslationArea")
    }


    // Method to restore area settings on service start
    private fun restoreActiveTranslationArea() {
        val preferencesManager = PreferencesManager.getInstance(this)
        activeTranslationArea = preferencesManager.getActiveTranslationArea()
        if (activeTranslationArea != null) {
            Log.d("ScreenCaptureService", "Restored translation area: $activeTranslationArea")
        } else {
            Log.d("ScreenCaptureService", "No saved translation area found")
        }
    }

    override fun onDestroy() {
        Log.d("ScreenCaptureService", "onDestroy called.")
        stopCapture() // 确保停止
        orientationListener.disable()

        // 停止后台线程
        handlerThread?.quitSafely()
        handlerThread = null
        callbackHandler = null
        stopForeground(true) // 停止前台服务
        // Unregister the receiver
        try {
            unregisterReceiver(areaReceiver)
        } catch (e: Exception) {
            Log.w("ScreenCaptureService", "Error unregistering area receiver", e)
        }

        super.onDestroy()
    }
}