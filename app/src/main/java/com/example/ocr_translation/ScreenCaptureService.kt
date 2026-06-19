package com.example.ocr_translation

import android.util.Log
import android.app.Service
import android.os.Build
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.example.ocr_translation.TranslationService.TranslatedBlock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent // <-- Import
import android.content.Context // <-- Import
import androidx.core.app.NotificationCompat
import android.os.Handler
import android.os.HandlerThread // <-- Import
import android.os.Looper     // <-- Import
import android.os.IBinder
import android.content.pm.ServiceInfo
import kotlinx.coroutines.cancel

import android.hardware.SensorManager
import android.util.DisplayMetrics
import android.view.OrientationEventListener
import android.view.Surface
import android.view.WindowManager
import android.graphics.RectF

import android.graphics.Rect
import android.graphics.Color


class ScreenCaptureService : Service() {

    private val TAG = "ScreenCaptureService_OnStart"

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private lateinit var translationService: TranslationService
    private lateinit var translationCache: TranslationCache

    private var mediaProjectionCallback: MediaProjection.Callback? = null
    private var callbackHandler: Handler? = null // Handler for the callback
    private var handlerThread: HandlerThread? = null // Thread for the handler

    @Volatile private var activeTranslationArea: RectF? = null
    private lateinit var pipeline: TranslationPipeline
    @Volatile private var lastResultRects: List<Rect> = emptyList()
    private var lastFingerprint: IntArray? = null
    private var stableCount = 0
    private var pendingRetranslate = false
    // In-place state machine
    private var inPlaceScanning = true
    private var lastScanSig = ""
    private var scanStableSince = 0L
    private var steadyEnteredAt = 0L
    // Cache of the currently-shown in-place result + the OCR text that produced it, so a re-scan
    // that finds unchanged text can restore the box without re-translating (no flicker, no API call).
    private var lastShownResults: List<TranslationService.TranslatedBlock> = emptyList()
    private var shownOcrSig = ""
    // Tap-triggered scanning state: after the user taps, the screen often keeps showing the OLD
    // text for a couple of seconds (e.g. FGO's ~3s post-choice load). During that window we must
    // NOT restore the previous translation from cache (it's stale to the user's choice) and must
    // NOT retranslate the identical text (would just re-display the same stale box). We wait for
    // the text to genuinely differ from [shownOcrSig], or for [postTapDeadline] to pass.
    @Volatile private var lastShownIsStale = false
    private var postTapDeadline = 0L
    @Volatile private var translationStartSession = 0

    // Fingerprint of the original (pre-box) frame that produced the currently-shown translation.
    // Used by handleManualRequest to detect — at ~10ms cost vs ~200ms for a full OCR — whether the
    // screen content has changed since the last translation. If unchanged, we skip OCR and re-feed
    // the cached blocks to the LLM (bypassCache=true gives a fresh result); if changed, we OCR the
    // freshly-captured frame and translate that.
    //
    // [pendingOcrFingerprint] is set by each translation entry point (handleStableScan,
    // processScreenCapture, accessibilityTranslate) right before the pipeline call. The pipeline's
    // onResult callback promotes it to [lastOcrFingerprint] when the translation actually rendered.
    @Volatile private var pendingOcrFingerprint: IntArray? = null
    @Volatile private var lastOcrFingerprint: IntArray? = null
    enum class ManualKind { AUTO, FORCE_OCR }

    companion object {
        private const val FOREGROUND_NOTIFICATION_ID = 1003 // 通知 ID，不能和 OverlayService 冲突
        private const val CAPTURE_CHANNEL_ID = "screen_capture_channel"
        private const val STABLE_FRAMES = 2          // consecutive stable scans before translating (merged)
        private const val STABLE_TEXT_MS = 400L      // in-place: OCR text must hold this long before translating
        private const val STEADY_CHANGE_RATIO = 0.30 // in-place: frame delta that counts as a real change.
        // High enough that background animation (games) is
        // ignored; taps re-scan instantly regardless.
        private const val STEADY_GRACE_MS = 1000L    // in-place: after showing the box, ignore frame-diff
        // re-scans this long (only a tap re-scans) so the box's
        // own pixels / brief animations don't cause flicker

        // While we're hunting for stable text (in-place scanning state), poll fast — we only OCR,
        // not call the LLM. Tightens "dialog appears -> translation shows" by ~700ms vs the user's
        // captureInterval (which is meant for steady-state translation cadence).
        private const val SCAN_POLL_MS = 250L
        // After a user tap, the on-screen text may still be the OLD dialog for up to a few seconds
        // (FGO has a ~3s gap before the new dialog appears). During this window we refuse to
        // restore the previously-shown translation from cache, and refuse to re-translate the
        // identical text — we wait for genuinely-new text. If the deadline passes with no change,
        // we fall back to translating whatever is there (cache hit will make this near-free).
        private const val POST_TAP_WAIT_MS = 4000L

        // Translation mode, shared with the floating controls.
        // false = manual (default): translate only when requestManualTranslation() is called.
        // true  = auto: translate every capture cycle.
        @Volatile var autoMode: Boolean = false

        // Manual-translate request kinds.
        // AUTO     = let the service decide: if a translation is currently shown, re-translate the
        //            cached OCR blocks (skip OCR + force fresh LLM call); otherwise do a full pass.
        // FORCE_OCR = always re-run OCR (use this when the user knows the screen content changed and
        //            the cached blocks would be stale).
        @Volatile private var manualRequest: ManualKind? = null

        fun requestManualTranslation(kind: ManualKind = ManualKind.AUTO) {
            manualRequest = kind
        }

        private fun consumeManualRequest(): ManualKind? {
            val k = manualRequest ?: return null
            manualRequest = null
            return k
        }

        // Set by the overlay's touch-watch window whenever the user taps anywhere
        @Volatile private var userInputPending: Boolean = false
        @Volatile var sessionId: Int = 0
        fun onUserInput() {
            userInputPending = true
            sessionId++   // invalidate any in-flight translation so its result is dropped
        }
        private fun consumeUserInput(): Boolean {
            if (!userInputPending) return false
            userInputPending = false
            return true
        }
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

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.e("ScreenCaptureService", "======= ScreenCaptureService.onCreate() called =======")

        handlerThread = HandlerThread("MediaProjectionCallbackThread").apply { start() }
        callbackHandler = Handler(handlerThread!!.looper)

        setupMediaProjectionCallback()

//        setCaptureInterval(3000L)
        setCaptureInterval(PreferencesManager.getInstance(this).captureInterval)

        // Initialize both dependencies
        translationService = TranslationService.getInstance(this)
        translationCache = TranslationCache(applicationContext)
        pipeline = TranslationPipeline(translationService) { results ->
            // Drop the result if the user tapped while this translation was running
            if (translationStartSession == sessionId) {
                lastResultRects = results.map { it.boundingBox }
                if (results.isNotEmpty()) lastShownResults = results   // cache for unchanged re-scans
                OverlayService.showTranslation(results)
                if (results.isNotEmpty()) appendTranslationsToFile(results)
                // Promote the fingerprint of the frame that produced this translation. Only when we
                // actually rendered (results not empty) — otherwise the cache lookup in handleManual
                // might match the wrong frame.
                if (results.isNotEmpty()) {
                    lastOcrFingerprint = pendingOcrFingerprint
                }
                pendingOcrFingerprint = null
            } else {
                Log.d(TAG, "Discarding stale translation — user tapped during it")
                pendingOcrFingerprint = null
            }
        }
        // Start in manual mode so we don't OCR/translate the app's own UI on launch
        autoMode = false
        manualRequest = null

        // Initialize current rotation
        val display = (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        currentRotation = display.rotation

        // Enable orientation listener
        if (orientationListener.canDetectOrientation()) {
            orientationListener.enable()
            Log.d("ScreenCaptureService", "Orientation listener enabled")
        }

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

    private fun updateScreenMetrics() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = wm.defaultDisplay
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)

        // getRealMetrics() already returns orientation-correct dimensions — use them directly.
        // (The old rotation-based swap double-corrected and captured a portrait slice in landscape.)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        Log.d("ScreenCaptureService",
            "Screen metrics updated: w=$screenWidth, h=$screenHeight, density=$screenDensity, rotation=$currentRotation")

    }

    private fun broadcastRotationChange() {
        val intent = Intent("com.example.ocr_translation.ACTION_ROTATION_CHANGED")
        intent.putExtra("rotation", currentRotation)
        intent.putExtra("screenWidth", screenWidth)
        intent.putExtra("screenHeight", screenHeight)
        sendBroadcast(intent)
        Log.d("ScreenCaptureService", "Broadcast rotation change: $currentRotation")
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

        Log.d(TAG, "onStartCommand received intent: $intent")

        // 确保服务始终在前台运行（这是截图服务通常需要的权限）
        // createForegroundNotification() 方法需要在类中实现
        val notification = createForegroundNotification()
        try {
            Log.d(TAG, "Starting foreground service...")
            // startForeground 会根据你的应用状态和 Android 版本，
            // 将服务提升到前台状态，防止被系统轻易杀死。
            // 针对 targetSdk 34 (Android 14)：必须在调用 getMediaProjection() 之前，
            // 以显式的 mediaProjection 类型启动前台服务，否则系统会立即停止投屏会话
            // （表现为 MediaProjection.Callback.onStop() 被立即回调）。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    FOREGROUND_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(FOREGROUND_NOTIFICATION_ID, notification)
            }
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
        // START_STICKY: 被杀后系统自动重建，但 intent 为 null。
        // START_NOT_STICKY: 被杀后不自动重建，需有新的 START 命令。
        // 本服务依赖一次性的 MediaProjection 授权（无法跨进程存活），被杀后即使重建也拿不到授权、
        // 只会空转成无法截屏的僵尸。故用 START_NOT_STICKY，等用户主动重开并重新授权。
        return Service.START_NOT_STICKY
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
                // Hoist the prefs read so we only hit SharedPreferences once per iteration; both
                // the work selection and the next-delay calculation share these values.
                val prefs = PreferencesManager.getInstance(this@ScreenCaptureService)
                val inPlace = prefs.inPlaceMode
                try {
                    val manual = consumeManualRequest()
                    val useA11y = prefs.useAccessibility && AccessibilityTextService.isConnected()
                    when {
                        manual != null -> handleManualRequest(manual, useA11y, prefs)
                        useA11y && autoMode -> accessibilityTranslate(force = false)
                        inPlace && autoMode -> inPlaceAutoStep()
                        autoMode -> mergedAutoStep()
                    }
                } catch (e: Exception) {
                    Log.e("ScreenCaptureService", "Capture cycle failed", e)
                    kotlinx.coroutines.delay(1000)
                }
                // While in-place scanning, poll fast (we only OCR, no LLM call) so we catch a new
                // dialog with minimal lag. Steady state and merged mode use the user's captureInterval.
                val nextDelay = when {
                    !autoMode -> 150L
                    inPlace && inPlaceScanning -> SCAN_POLL_MS
                    else -> captureInterval
                }
                kotlinx.coroutines.delay(nextDelay)
            }
        }
        Log.d("ScreenCaptureService", "Periodic capture loop exited.") // <-- 日志12
    }

    /**
     * Resolve a manual translate request.
     *
     * The user's mental model is "the button does what I want": if the screen is showing the same
     * dialog as last time, they want a fresh attempt at translating it (LLM is non-deterministic,
     * the new pass may pick better wording). If the dialog actually changed, they want it
     * translated now.
     *
     * We disambiguate cheaply via a frame fingerprint (~10ms) instead of a full OCR (~200ms):
     *   AUTO + cached blocks exist + fingerprint matches last translation → reuse cached blocks
     *       with bypassCache=true so the LLM doesn't return the identical cached string.
     *   FORCE_OCR (long-press) → always full pipeline.
     *   Anything else → full pipeline (capture, OCR, translate).
     *
     * Earlier this method blindly reused cached blocks whenever they existed, which caused the
     * "manual translate keeps showing the previous translation even after the dialog changed"
     * bug: feeding old OCR text to the LLM naturally produces (essentially) the old translation.
     */
    private suspend fun handleManualRequest(
        kind: ManualKind,
        useA11y: Boolean,
        prefs: PreferencesManager
    ) {
        if (useA11y) {
            accessibilityTranslate(force = true)
            return
        }

        val hadShown = lastShownResults.isNotEmpty()
        if (hadShown) {
            OverlayService.clearShownTranslation()   // 直接清空旧 box（GONE + removeAllViews）
            lastShownResults = emptyList()
            kotlinx.coroutines.delay(120)
        }
        val frame = captureScreen()
        if (frame == null) {
            Log.w(TAG, "Manual: captureScreen returned null")
            return
        }

        pendingOcrFingerprint = fingerprint(frame)

        // 总是 OCR，然后用文字内容做"同/异"判断 —— 比像素指纹可靠得多
        val snap = pipeline.ocrFrame(frame, activeTranslationArea, prefs.sourceLanguage)
        if (snap == null) {
            enterSteady()
            return
        }

        val newText = snap.blocks.joinToString("\n") { it.text.trim() }
        val oldText = lastShownResults.joinToString("\n") { it.originalText.trim() }
        val sameAsLast = kind == ManualKind.AUTO &&
                lastShownResults.isNotEmpty() &&
                newText.isNotEmpty() &&
                newText == oldText

        translationStartSession = sessionId
        if (sameAsLast) {
            Log.d(TAG, "Manual: OCR text identical → bypass LLM cache, retry translation")
            // 用刚 OCR 出的新 blocks（位置可能微调），bypassCache 强制 LLM 重译
            val blocks = snap.blocks
            // processBlocks 只会收 sampleFrame(=frame)，snap的cropped在此显式回收
            pipeline.processBlocks(
                blocks = blocks,
                sourceLanguage = prefs.sourceLanguage,
                targetLanguage = prefs.targetLanguage,
                force = true,
                sampleFrame = frame,
                bypassCache = true
            )
            snap.recycle()
        } else {
            Log.d(TAG, "Manual: ${if (kind == ManualKind.FORCE_OCR) "FORCE_OCR" else "content changed"} → translate")
            pipeline.translateSnapshot(
                snap = snap,
                sourceLanguage = prefs.sourceLanguage,
                targetLanguage = prefs.targetLanguage,
                force = true,
                bypassCache = (kind == ManualKind.FORCE_OCR)
            )
        }
        enterSteady()
    }

    // One real translation pass: hide our overlay, grab a clean frame, restore, then OCR/translate.
    private suspend fun performTranslation(force: Boolean) {
//        OverlayService.hideForCapture()
//        kotlinx.coroutines.delay(120)
//        val frame = captureScreen()
//        OverlayService.showAfterCapture()
        OverlayService.fadeOutForCapture()    // view.alpha = 0f
        kotlinx.coroutines.delay(33)
        val frame = captureScreen()
        OverlayService.fadeInAfterCapture()   // view.alpha = 1f
        frame?.let { processScreenCapture(it, force) }
        // Re-baseline against the screen now showing our box, so we detect the next real change
        lastFingerprint = null
    }

    // Tiny downscaled snapshot used to detect on-screen change without hiding the overlay
    private fun fingerprint(bmp: Bitmap): IntArray {
        return try {
            val small = Bitmap.createScaledBitmap(bmp, 32, 18, false)
            val px = IntArray(32 * 18)
            small.getPixels(px, 0, 32, 0, 0, 32, 18)
            small.recycle()
            px
        } catch (e: Exception) {
            IntArray(0)
        }
    }

    private fun fingerprintChanged(fp: IntArray): Boolean {
        val last = lastFingerprint
        lastFingerprint = fp
        if (last == null || last.size != fp.size || fp.isEmpty()) return true
        var diff = 0
        for (i in fp.indices) {
            val c = fp[i]; val l = last[i]
            val d = kotlin.math.abs(Color.red(c) - Color.red(l)) +
                    kotlin.math.abs(Color.green(c) - Color.green(l)) +
                    kotlin.math.abs(Color.blue(c) - Color.blue(l))
            if (d > 60) diff++
        }
        return diff > fp.size * 0.02   // >2% of cells changed
    }

    // Merged mode: translate when the screen settles after a change
    private suspend fun mergedAutoStep() {
        val raw = captureScreen() ?: return
        val changed = fingerprintChanged(fingerprint(raw))
        raw.recycle()
        if (changed) {
            stableCount = 0
            pendingRetranslate = true
        } else if (pendingRetranslate) {
            stableCount++
            if (stableCount >= STABLE_FRAMES) {
                pendingRetranslate = false
                performTranslation(force = false)
            }
        }
    }

    // In-place mode: scan (box hidden) until OCR text is stable, show, then stay until a real change
    private suspend fun inPlaceAutoStep() {
        val prefs = PreferencesManager.getInstance(this)
        if (inPlaceScanning) {
            val frame = captureScreen() ?: return
            // OCR-and-hold: keeps the cropped bitmap alive so if we proceed to translation we can
            // sample background colours and avoid a second captureScreen + OCR pass.
            val snap = pipeline.ocrFrame(frame, activeTranslationArea, prefs.sourceLanguage) ?: return
            val sig = snap.sig
            val now = android.os.SystemClock.elapsedRealtime()
            val handled: Boolean = when {
                sig.isEmpty() -> {
                    lastScanSig = ""
                    scanStableSince = now
                    false
                }
                !textSimilar(sig, lastScanSig) -> {
                    lastScanSig = sig
                    scanStableSince = now
                    false
                }
                now - scanStableSince < STABLE_TEXT_MS -> false
                else -> {
                    handleStableScan(snap, sig, now, prefs)
                    true
                }
            }
            if (!handled) snap.recycle()
        } else {
            // Steady: the box is shown. A tap re-scans immediately. Otherwise, after a grace window,
            // a big frame change triggers a *peek* rather than a full clear: hide the box for one
            // clean frame and OCR it — only if the original text actually changed do we re-scan.
            // This keeps the box on screen through animation that didn't change the text (no flicker).
            val frame = captureScreen() ?: return
            val fp = fingerprint(frame)
            frame.recycle()
            val now = android.os.SystemClock.elapsedRealtime()
            when {
                consumeUserInput() -> enterScanning(fromTap = true)
                now - steadyEnteredAt < STEADY_GRACE_MS -> {
                    lastFingerprint = fp                         // keep baselining (box now rendered)
                }
                fingerprintDiff(fp) > STEADY_CHANGE_RATIO -> {
                    if (textChangedSincePeek(prefs.sourceLanguage)) {
                        enterScanning()
                    } else {
                        // Same text — keep the box. Re-arm the grace window so we don't peek again
                        // immediately, and re-baseline against the current frame.
                        steadyEnteredAt = android.os.SystemClock.elapsedRealtime()
                        lastFingerprint = null
                    }
                }
            }
        }
    }

    /**
     * The scanning state has held the same OCR text for [STABLE_TEXT_MS]. Decide whether to:
     *   - restore the previously-shown translation from cache (text unchanged, no tap pending),
     *   - keep waiting (post-tap window, text hasn't actually changed yet — wait for new dialog),
     *   - translate fresh (any other case).
     *
     * On entry, [snap] is owned by this function: either consumed by translateSnapshot or recycled.
     * Returns nothing — but the caller relies on us to never leak [snap].
     */
    private suspend fun handleStableScan(
        snap: TranslationPipeline.OcrSnapshot,
        sig: String,
        now: Long,
        prefs: PreferencesManager
    ) {
        val sameAsPreviouslyShown = shownOcrSig.isNotEmpty() &&
                lastShownResults.isNotEmpty() &&
                textSimilar(sig, shownOcrSig)

        when {
            // Post-tap and the screen still shows the old text: hold off. The user explicitly
            // changed something; either the dialog is mid-transition (FGO loads ~3s) or the tap
            // didn't change anything. We don't restore (the old translation is now stale) and we
            // don't re-translate the same string (would just flash the same box back).
            lastShownIsStale && sameAsPreviouslyShown && now < postTapDeadline -> {
                Log.d(TAG, "post-tap wait: text unchanged, holding scan (${postTapDeadline - now}ms left)")
                snap.recycle()
            }
            // Outside the post-tap window (or no tap pending), and text matches what we previously
            // showed: this is the "background animation made us re-scan but the text really is
            // the same" case — fast-restore the cached box.
            sameAsPreviouslyShown && !lastShownIsStale -> {
                OverlayService.showTranslation(lastShownResults)
                consumeUserInput()
                snap.recycle()
                enterSteady()
            }
            // Either text actually changed, or we ran out the post-tap deadline. Translate fresh,
            // reusing the OCR we already did on [snap] — no second captureScreen, no second OCR.
            else -> {
                // Snapshot the pre-box frame fingerprint so a later manual re-translate can tell
                // whether the screen content has changed since we last translated.
                pendingOcrFingerprint = snap.frame?.let { f ->
                    if (!f.isRecycled) fingerprint(f) else null
                }
                translationStartSession = sessionId
                pipeline.translateSnapshot(
                    snap = snap,                       // snap is consumed (recycled) inside
                    sourceLanguage = prefs.sourceLanguage,
                    targetLanguage = prefs.targetLanguage,
                    force = true
                )
                if (consumeUserInput()) {
                    // A tap landed during the translation, so its result was dropped to avoid
                    // covering new text. Stay scanning and re-read the current screen instead of
                    // going steady with an empty box (strand).
                    enterScanning(fromTap = true)
                } else {
                    shownOcrSig = sig
                    lastShownIsStale = false
                    enterSteady()
                }
            }
        }
    }

    // Briefly hide the box, grab a clean frame and OCR the area. Returns true if the original text
    // differs from what the shown box was built from (or if it couldn't be read — re-scan to be safe).
    private suspend fun textChangedSincePeek(sourceLanguage: String): Boolean {
        if (shownOcrSig.isEmpty()) return true
        OverlayService.fadeOutForCapture()
        kotlinx.coroutines.delay(33)
        val clean = captureScreen()
        OverlayService.fadeInAfterCapture()
        val newSig = if (clean != null)
            pipeline.ocrSignature(clean, activeTranslationArea, sourceLanguage) else ""
        if (newSig.isEmpty()) return true
        return !textSimilar(newSig, shownOcrSig)
    }

    // Hide the box and start a fresh scan of the original text.
    // [fromTap] is true when the user just tapped (the previously-shown translation is now stale
    // because the user explicitly changed something). Arms the post-tap window so the scanning
    // logic waits for genuinely-new text instead of restoring the cached box (which would re-show
    // the old translation while the screen is still mid-transition — e.g. FGO's ~3s post-choice load).
    private fun enterScanning(fromTap: Boolean = false) {
        inPlaceScanning = true
        lastScanSig = ""
        val now = android.os.SystemClock.elapsedRealtime()
        scanStableSince = now
        if (fromTap) {
            lastShownIsStale = true
            postTapDeadline = now + POST_TAP_WAIT_MS
            Log.d(TAG, "enterScanning(fromTap=true): stale flag armed, deadline +${POST_TAP_WAIT_MS}ms")
        }
        OverlayService.showTranslation(emptyList())   // clear box, reveal original to re-read
    }

    // Box has just been shown; hold here until a tap or (after the grace window) a real change.
    private fun enterSteady() {
        inPlaceScanning = false
        steadyEnteredAt = android.os.SystemClock.elapsedRealtime()
        lastFingerprint = null   // re-baseline against the screen now showing our box
    }

    private fun fingerprintDiff(fp: IntArray): Double {
        val last = lastFingerprint
        lastFingerprint = fp
        if (last == null || last.size != fp.size || fp.isEmpty()) return 0.0
        var diff = 0
        for (i in fp.indices) {
            val c = fp[i]; val l = last[i]
            val d = kotlin.math.abs(Color.red(c) - Color.red(l)) +
                    kotlin.math.abs(Color.green(c) - Color.green(l)) +
                    kotlin.math.abs(Color.blue(c) - Color.blue(l))
            if (d > 60) diff++
        }
        return diff.toDouble() / fp.size
    }

    private fun textSimilar(a: String, b: String): Boolean {
        if (a == b) return true
        val maxLen = maxOf(a.length, b.length)
        if (maxLen == 0) return true
        return 1.0 - levenshtein(a, b).toDouble() / maxLen >= 0.95
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]; dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = if (a[i - 1] == b[j - 1]) prev else 1 + minOf(prev, dp[j], dp[j - 1])
                prev = tmp
            }
        }
        return dp[b.length]
    }

    // Enhanced-OCR path: read text from the accessibility tree (exact for native apps), translate it.
    // Falls back to nothing when the foreground app exposes no text nodes (e.g. canvas/GL games).
    private suspend fun accessibilityTranslate(force: Boolean) {
        translationStartSession = sessionId
        val prefs = PreferencesManager.getInstance(this)
        var nodes = AccessibilityTextService.getScreenText()
        // Honour an active crop area: keep only nodes overlapping it.
        val area = activeTranslationArea
        if (area != null) {
            nodes = nodes.filter {
                val b = it.boundingBox
                area.intersects(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
            }
        }
        // Only need a frame for background sampling when drawing in-place.
        val sampleFrame = if (prefs.inPlaceMode) captureScreen() else null
        // Save its fingerprint for manual re-translate's change-detection check.
        pendingOcrFingerprint = sampleFrame?.let { f ->
            if (!f.isRecycled) fingerprint(f) else null
        }
        pipeline.processBlocks(
            blocks = nodes,
            sourceLanguage = prefs.sourceLanguage,
            targetLanguage = prefs.targetLanguage,
            force = force,
            sampleFrame = sampleFrame
        )
    }

    private suspend fun processScreenCapture(bitmap: Bitmap, force: Boolean) {
        translationStartSession = sessionId   // remember which session this translation belongs to
        val prefs = PreferencesManager.getInstance(this)
        // Save the pre-box frame's fingerprint for manual re-translate's change-detection check.
        pendingOcrFingerprint = if (!bitmap.isRecycled) fingerprint(bitmap) else null
        pipeline.process(
            frame = bitmap,
            area = activeTranslationArea,
            sourceLanguage = prefs.sourceLanguage,
            targetLanguage = prefs.targetLanguage,
            force = force
        )
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

                // The buffer width has to account for stride padding; we'll crop it off below
                // so the returned bitmap matches the actual screen dimensions (otherwise OCR /
                // crop-area coordinates are off by a few px on the right edge).
                val bufferWidth = screenWidth + rowPadding / pixelStride
                val bufferHeight = screenHeight

                Log.d("ScreenCaptureService", "Creating bitmap with buffer dimensions: $bufferWidth x $bufferHeight (screen: $screenWidth x $screenHeight)")

                try {
                    val raw = Bitmap.createBitmap(
                        bufferWidth,
                        bufferHeight,
                        Bitmap.Config.ARGB_8888
                    )
                    raw.copyPixelsFromBuffer(buffer)

                    bitmap = if (bufferWidth > screenWidth) {
                        // Drop the stride padding strip on the right edge.
                        val cropped = Bitmap.createBitmap(raw, 0, 0, screenWidth, screenHeight)
                        if (cropped !== raw) raw.recycle()
                        cropped
                    } else raw
                } catch (e: Exception) {
                    Log.e("ScreenCaptureService", "Error creating or copying bitmap: ${e.message}")
                    bitmap?.recycle()
                    bitmap = null
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

    override fun onBind(intent: Intent?): IBinder? = null

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
        serviceScope.cancel()

        // 停止后台线程
        handlerThread?.quitSafely()
        handlerThread = null
        callbackHandler = null
        // stopForeground(boolean) is deprecated since API 33 — use the explicit constant.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }

    // ===== Save translations to text file (SAF) =====
    // Session file is named once per service run and re-used for the whole session.
    @Volatile private var sessionFileName: String? = null

    /**
     * Append `original → translation` lines to a text file in the user-picked SAF folder.
     * Silently no-ops when the feature is disabled, the folder URI is missing, or the user
     * revoked the persistable permission. We never raise — file output is best-effort and must
     * not interfere with the live translation flow.
     */
    private fun appendTranslationsToFile(results: List<TranslatedBlock>) {
        val prefs = PreferencesManager.getInstance(this)
        if (!prefs.saveToFileEnabled) return
        val folderUriStr = prefs.saveFolderUri
        if (folderUriStr.isEmpty()) return

        serviceScope.launch {
            try {
                val folderUri = Uri.parse(folderUriStr)
                val dir = DocumentFile.fromTreeUri(this@ScreenCaptureService, folderUri)
                if (dir == null || !dir.canWrite()) {
                    Log.w(TAG, "Save-to-file: folder unwritable, skipping")
                    return@launch
                }

                val fileName = sessionFileName ?: run {
                    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    "translations_$stamp.txt".also { sessionFileName = it }
                }
                val file = dir.findFile(fileName)
                    ?: dir.createFile("text/plain", fileName)
                    ?: run {
                        Log.w(TAG, "Save-to-file: createFile returned null for $fileName")
                        return@launch
                    }

                val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
                val sb = StringBuilder()
                sb.append("--- ").append(time).append(" ---\n")
                for (b in results) {
                    sb.append(b.originalText).append('\n')
                    sb.append("→ ").append(b.translatedText).append("\n\n")
                }
                // openOutputStream(uri, "wa") = write+append on SAF document providers that honour it
                contentResolver.openOutputStream(file.uri, "wa")?.use { out ->
                    out.write(sb.toString().toByteArray(Charsets.UTF_8))
                    out.flush()
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "Save-to-file: lost permission to ${prefs.saveFolderUri}", e)
            } catch (e: Exception) {
                Log.w(TAG, "Save-to-file failed", e)
            }
        }
    }
}