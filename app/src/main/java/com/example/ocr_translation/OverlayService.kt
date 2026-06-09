package com.example.ocr_translation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.cardview.widget.CardView
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import android.view.Surface
import android.util.DisplayMetrics
import android.graphics.RectF
import android.graphics.Canvas
import android.graphics.Paint



class OverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var controlPanel: View
    private lateinit var translationOverlay: FrameLayout
    private lateinit var inPlaceOverlay: FrameLayout
    private var touchWatchView: View? = null
    private val captureHideHandler = Handler(Looper.getMainLooper())
    private var savedTransVis = View.VISIBLE
    private var savedInPlaceVis = View.GONE
    private val translatedViews = mutableMapOf<Int, View>()
    private var isPaused = false

    // Settings
    private var textSizeMultiplier = 1.0f
    private var overlayOpacity = 0.8f
    private var highlightOriginalText = true
    private var useAlternativeStyle = false

    private var currentRotation = Surface.ROTATION_0
    private var screenWidth = 0
    private var screenHeight = 0
    private var activeTranslationArea: RectF? = null
    private var areaIndicatorView: AreaIndicatorView? = null
    private var areaSelectionView: View? = null

    private var initialOverlayX: Int = 0
    private var initialOverlayY: Int = 0
    private var initialOverlayTouchX: Float = 0f
    private var initialOverlayTouchY: Float = 0f

    private lateinit var overlayLayoutParams: WindowManager.LayoutParams // <-- 添加这行声明


    private val rotationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.ocr_translation.ACTION_ROTATION_CHANGED") {
                val newRotation = intent.getIntExtra("rotation", Surface.ROTATION_0)
                screenWidth = intent.getIntExtra("screenWidth", screenWidth)
                screenHeight = intent.getIntExtra("screenHeight", screenHeight)

                Log.d(TAG, "Received rotation change: $newRotation, screen: ${screenWidth}x${screenHeight}")

                if (currentRotation != newRotation) {
                    currentRotation = newRotation

                    // Update overlay positions with a delay to let the system stabilize
                    Handler(Looper.getMainLooper()).postDelayed({
                        translationData.value?.let { updateOverlays(it) }
                    }, 300)
                }
            }
        }
    }

    private class AreaIndicatorView(context: Context) : View(context) {

        var activeArea: RectF? = null

        private val borderPaint = Paint().apply {
            color = Color.GREEN
            style = Paint.Style.STROKE
            strokeWidth = 3f
            alpha = 180
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            activeArea?.let { area ->
                canvas.drawRect(area, borderPaint)
            }
        }
    }

    private val areaUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "com.example.ocr_translation.ACTION_AREA_STATUS_UPDATE") {
                val hasActiveArea = intent.getBooleanExtra("has_active_area", false)

                if (hasActiveArea) {
                    val rectF = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra("area_rect", RectF::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra("area_rect") as? RectF
                    }

                    if (rectF != null) {
                        updateAreaIndicator(rectF)
                    }
                } else {
                    hideAreaIndicator()
                }
            }
        }
    }

    private fun updateAreaIndicator(area: RectF) {
        activeTranslationArea = area

        if (!PreferencesManager.getInstance(this).showAreaBorder) {
            if (areaIndicatorView?.parent != null) {
                try { windowManager.removeView(areaIndicatorView) } catch (e: Exception) {}
            }
            return
        }

        if (areaIndicatorView?.parent == null && windowManager != null) {
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                getOverlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )

            try {
                areaIndicatorView?.activeArea = area
                windowManager.addView(areaIndicatorView, params)
            } catch (e: Exception) {
                Log.e("OverlayService", "Error adding area indicator view", e)
            }
        } else {
            areaIndicatorView?.activeArea = area
            areaIndicatorView?.invalidate()
        }
    }

    private fun hideAreaIndicator() {
        activeTranslationArea = null

        if (areaIndicatorView?.parent != null && windowManager != null) {
            try {
                windowManager.removeView(areaIndicatorView)
            } catch (e: Exception) {
                Log.e("OverlayService", "Error removing area indicator view", e)
            }
        }
    }

    private fun getOverlayType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    // Companion object for communication
    companion object {
        private val translationData = MutableLiveData<List<TranslationService.TranslatedBlock>>()
        private const val TAG = "OverlayService"

        // Lets the capture service hide our translated overlays during a screen grab,
        // so we never OCR our own results back.
        @Volatile private var instance: OverlayService? = null
        fun hideForCapture() { instance?.hideOverlaysForCapture() }
        fun showAfterCapture() { instance?.showOverlaysAfterCapture() }

        fun getTranslationData(): LiveData<List<TranslationService.TranslatedBlock>> = translationData

        fun showTranslation(translations: List<TranslationService.TranslatedBlock>) {
            Log.d(TAG, "showTranslation called with ${translations.size} items.") // 添加日志
            if (translations.isNotEmpty()) {
                Log.d(TAG, "First item original: ${translations[0].originalText}, translated: ${translations[0].translatedText}") // 记录第一条看看
            }
            translationData.postValue(translations)
        }

        fun clearShownTranslation() { instance?.clearShownTranslationImpl() }
    }

    private val settingsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.ocr_translation.ACTION_UPDATE_OVERLAY_SETTINGS") {
                val textSize = intent.getFloatExtra("textSize", 1.0f)
                val opacity = intent.getFloatExtra("opacity", 0.8f)
                val highlight = intent.getBooleanExtra("highlight", true)
                val alternativeStyle = intent.getBooleanExtra("alternativeStyle", false)

                updateSettings(textSize, opacity, highlight, alternativeStyle)

                val showBorder = intent.getBooleanExtra("showAreaBorder", true)
                if (!showBorder) {
                    if (areaIndicatorView?.parent != null) {
                        try { windowManager.removeView(areaIndicatorView) } catch (e: Exception) {}
                    }
                } else {
                    activeTranslationArea?.let { updateAreaIndicator(it) }
                }

                // Control panel styling — applied live so the user sees orientation / colour /
                // opacity changes immediately without having to toggle the overlay off and on.
                applyControlPanelStyle()
                // Font choice may have changed — drop the cache so the next translation re-resolves.
                cachedTypeface = null
            }
        }
    }

    private lateinit var screenOrientationListener: OrientationEventListener

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        instance = this

        // Initialize screen dimensions
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = wm.defaultDisplay
        currentRotation = display.rotation

        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels

        // Register rotation receiver
        registerReceiver(
            rotationReceiver,
            IntentFilter("com.example.ocr_translation.ACTION_ROTATION_CHANGED"),
            Context.RECEIVER_NOT_EXPORTED
        )

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // Load preferences first
        val preferencesManager = PreferencesManager.getInstance(this)
        textSizeMultiplier = preferencesManager.textSizeMultiplier
        overlayOpacity = preferencesManager.overlayOpacity
        highlightOriginalText = preferencesManager.highlightOriginalText
        useAlternativeStyle = preferencesManager.useAlternativeStyle

        // Start foreground to keep service alive
        createNotificationChannel()
        startForeground(1001, createNotification())

        // Get initial rotation
        //val display = (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        //currentRotation = display.rotation

        // Register for settings updates
        val filter = IntentFilter("com.example.ocr_translation.ACTION_UPDATE_OVERLAY_SETTINGS")
        registerReceiver(settingsReceiver, filter, Context.RECEIVER_NOT_EXPORTED)

        areaIndicatorView = AreaIndicatorView(this)

        // Create separate windows for control panel and translations
        createControlPanelWindow()
        createTranslationOverlay()
        createInPlaceOverlay()
        createTouchWatch()

        // Set up orientation listener
        setupOrientationListener()

        // Observe translation data
        translationData.observeForever { translations ->
            updateOverlays(translations)
        }
        val areaFilter = IntentFilter("com.example.ocr_translation.ACTION_AREA_STATUS_UPDATE")
        registerReceiver(areaUpdateReceiver, areaFilter, Context.RECEIVER_NOT_EXPORTED)
    }

    private fun getCurrentRotation(): Int {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = wm.defaultDisplay
        return display.rotation
    }


    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "translator_channel",
                "Screen Translator",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification() = NotificationCompat.Builder(this, "translator_channel")
        .setContentTitle(getString(R.string.app_name))
        .setContentText(getString(R.string.translation_active))
        .setSmallIcon(R.drawable.ic_translate)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    private fun setupOrientationListener() {
        screenOrientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return

                // Only update when crossing orientation thresholds
                if (orientation % 90 < 10 || orientation % 90 > 80) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        translationData.value?.let { updateOverlays(it) }
                    }, 300)
                }
            }
        }

        if (screenOrientationListener.canDetectOrientation()) {
            screenOrientationListener.enable()
        }
    }

    // ===== CONTROL PANEL =====

    private fun createControlPanelWindow() {
        try {
            val contextThemeWrapper = ContextThemeWrapper(this, R.style.Theme_ScreenTranslator_Overlay)
            controlPanel = LayoutInflater.from(contextThemeWrapper).inflate(
                R.layout.overlay_control_panel, null
            )

            val prefs = PreferencesManager.getInstance(this)
            val savedX = prefs.controlPanelX
            val savedY = prefs.controlPanelY

            val controlParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,  // Keep this flag
                PixelFormat.TRANSLUCENT
            ).apply {
                // First launch: place against the top-right edge as before. Once the user drags
                // the panel anywhere, setupControlPanelDrag persists the absolute position and we
                // use TOP|START on subsequent boots so the saved coords interpret consistently.
                if (savedX == Int.MIN_VALUE || savedY == Int.MIN_VALUE) {
                    gravity = Gravity.TOP or Gravity.END
                    x = 0; y = 100
                } else {
                    gravity = Gravity.TOP or Gravity.START
                    x = savedX; y = savedY
                }
            }

            applyControlPanelStyle()        // orientation + bg color + opacity from prefs
            setupControlPanelButtons()
            setupControlPanelDrag(controlParams)

            windowManager.addView(controlPanel, controlParams)
            Log.d(TAG, "Control panel added successfully (x=${controlParams.x}, y=${controlParams.y})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create control panel: ${e.message}", e)
            createFallbackControlPanel()
        }
    }

    /**
     * Reads the control panel's appearance prefs (orientation / bg color / opacity) and applies
     * them to the live view. Safe to call repeatedly (e.g. after Settings broadcasts an update).
     */
    private fun applyControlPanelStyle() {
        if (!::controlPanel.isInitialized) return
        val prefs = PreferencesManager.getInstance(this)
        // Orientation: swap the inner LinearLayout's axis. Buttons stay the same; the parent
        // CardView re-measures to wrap_content so the bar becomes a vertical strip.
        val bar = controlPanel.findViewById<LinearLayout>(R.id.barContent)
        bar?.orientation = if (prefs.controlPanelOrientation == "vertical")
            LinearLayout.VERTICAL else LinearLayout.HORIZONTAL

        // Bg color + opacity: combine RGB with the slider-controlled alpha. The CardView's own
        // setCardBackgroundColor honours alpha for the surface, including the rounded corners.
        val baseRgb = prefs.controlPanelBgColor and 0x00FFFFFF
        val alpha = (255f * prefs.controlPanelOpacity.coerceIn(0f, 1f)).toInt()
        val combined = (alpha shl 24) or baseRgb
        (controlPanel as? CardView)?.setCardBackgroundColor(combined)
    }

    private fun clearShownTranslationImpl() {
        captureHideHandler.post {
            if (::translationOverlay.isInitialized) {
                translationOverlay.removeAllViews()
                translationOverlay.visibility = View.GONE
            }
            if (::inPlaceOverlay.isInitialized) {
                inPlaceOverlay.removeAllViews()
                inPlaceOverlay.visibility = View.GONE
            }
        }
    }

    private fun setupControlPanelButtons() {
        // Auto/manual toggle — play icon = manual (default, off), pause icon = auto (on)
        val btnAutoToggle = controlPanel.findViewById<ImageButton>(R.id.btnAutoToggle)
        btnAutoToggle.setImageResource(
            if (ScreenCaptureService.autoMode) R.drawable.ic_pause else R.drawable.ic_start
        )
        btnAutoToggle.setOnClickListener {
            val enabled = !ScreenCaptureService.autoMode
            ScreenCaptureService.autoMode = enabled
            btnAutoToggle.setImageResource(if (enabled) R.drawable.ic_pause else R.drawable.ic_start)
            if (!enabled) {
                // Stopping auto translation: clear any result still on screen
                clearInPlaceImmediately()
                translationOverlay.visibility = View.GONE
            }
            Log.d(TAG, "Auto mode: $enabled")
        }

        // Manual translate — short tap re-translates cached OCR blocks if a translation is showing
        // (skips OCR, forces a fresh LLM call). Long-press forces a full OCR+translate (use when
        // the screen content has actually changed since the last translation).
        val btnTranslate = controlPanel.findViewById<View>(R.id.btnTranslateNow)
        btnTranslate.setOnClickListener {
            Log.d(TAG, "Manual translate (AUTO) requested")
            ScreenCaptureService.requestManualTranslation(ScreenCaptureService.ManualKind.AUTO)
        }
        btnTranslate.setOnLongClickListener {
            Log.d(TAG, "Manual translate (FORCE_OCR) requested")
            ScreenCaptureService.requestManualTranslation(ScreenCaptureService.ManualKind.FORCE_OCR)
            true
        }

        // Select translation area — draw a box directly over the current screen
        controlPanel.findViewById<View>(R.id.btnSelectArea).setOnClickListener {
            Log.d(TAG, "Select area clicked")
            startAreaSelection()
        }

        // Dedicated collapse / expand button (in addition to the double-tap gesture)
        val btnFold = controlPanel.findViewById<ImageButton>(R.id.btnFold)
        btnFold?.setOnClickListener { toggleControlPanelFold() }

        // Close — stop translation entirely (overlay + capture)
        controlPanel.findViewById<View>(R.id.btnClose).setOnClickListener {
            Log.d(TAG, "Close clicked — stopping translation")
            PreferencesManager.getInstance(this).setTranslationActive(false)
            stopService(Intent(this, ScreenCaptureService::class.java))
            stopSelf()
        }

        // Double-tap the bar to fold / unfold it
        (controlPanel as? GestureControlPanel)?.onDoubleTap = { toggleControlPanelFold() }
    }

    private var controlPanelFolded = false

    private fun toggleControlPanelFold() {
        controlPanelFolded = !controlPanelFolded
        val auto = controlPanel.findViewById<View>(R.id.btnAutoToggle)
        val translate = controlPanel.findViewById<View>(R.id.btnTranslateNow)
        val crop = controlPanel.findViewById<View>(R.id.btnSelectArea)
        val fold = controlPanel.findViewById<ImageButton>(R.id.btnFold)
        val close = controlPanel.findViewById<View>(R.id.btnClose)
        if (controlPanelFolded) {
            val favorite = PreferencesManager.getInstance(this).foldFavorite
            auto.visibility = if (favorite == "auto") View.VISIBLE else View.GONE
            translate.visibility = if (favorite == "auto") View.GONE else View.VISIBLE
            crop.visibility = View.GONE
            close.visibility = View.GONE
            // Fold button stays visible so the user can expand again with a single tap.
            fold?.visibility = View.VISIBLE
            fold?.setImageResource(R.drawable.ic_expand)
        } else {
            auto.visibility = View.VISIBLE
            translate.visibility = View.VISIBLE
            crop.visibility = View.VISIBLE
            close.visibility = View.VISIBLE
            fold?.visibility = View.VISIBLE
            fold?.setImageResource(R.drawable.ic_collapse)
        }
        Log.d(TAG, "Control panel folded=$controlPanelFolded")
    }

    // ===== IN-OVERLAY AREA SELECTION =====

    private fun startAreaSelection() {
        if (areaSelectionView != null) return

        val selector = AreaSelectionOverlay(this)

        val hint = TextView(this).apply {
            text = getString(R.string.select_area_hint)
            setTextColor(Color.WHITE)
            textSize = 14f
        }
        val cancelBtn = Button(this).apply { text = getString(android.R.string.cancel) }
        val okBtn = Button(this).apply { text = getString(android.R.string.ok) }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#CC000000"))
            setPadding(32, 20, 32, 20)
            addView(hint, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(cancelBtn)
            addView(okBtn)
        }

        val container = FrameLayout(this).apply {
            addView(
                selector,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            addView(
                bar,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply { gravity = Gravity.BOTTOM }
            )
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            getOverlayType(),
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        cancelBtn.setOnClickListener { endAreaSelection() }
        okBtn.setOnClickListener {
            val rect = selector.selectedRect
            if (rect.width() > 10 && rect.height() > 10) {
                sendAreaToCapture(rect)
            }
            endAreaSelection()
        }

        // Hide the whole control bar while dragging so it never blocks the area being drawn;
        // bring it back when the finger lifts.
        selector.onDragStateChanged = { dragging ->
            bar.visibility = if (dragging) View.GONE else View.VISIBLE
        }

        // Hide our own overlays so they don't get in the way while selecting
        controlPanel.visibility = View.GONE
        translationOverlay.visibility = View.INVISIBLE

        try {
            windowManager.addView(container, params)
            areaSelectionView = container
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show area selector", e)
            endAreaSelection()
        }
    }

    private fun endAreaSelection() {
        areaSelectionView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing area selector", e)
            }
        }
        areaSelectionView = null
        controlPanel.visibility = View.VISIBLE
        if (!isPaused) translationOverlay.visibility = View.VISIBLE
    }

    private fun sendAreaToCapture(rect: RectF) {
        Log.d(TAG, "Setting OCR area: $rect")
        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            action = "com.example.ocr_translation.ACTION_SET_TRANSLATION_AREA"
            putExtra("area_left", rect.left)
            putExtra("area_top", rect.top)
            putExtra("area_right", rect.right)
            putExtra("area_bottom", rect.bottom)
            putExtra("area_name", "Custom Area")
        }
        androidx.core.content.ContextCompat.startForegroundService(this, intent)
    }

    private fun setupControlPanelDrag(params: WindowManager.LayoutParams) {
        // Drag is owned by GestureControlPanel: it watches touch slop and only intercepts when
        // the user actually starts dragging (so button taps still work). We get three callbacks —
        // start (where the down landed), move (cumulative delta), end. We translate them into
        // WindowManager.LayoutParams updates and persist the final position on release.
        val panel = controlPanel as? GestureControlPanel ?: return
        var initialX = 0
        var initialY = 0

        panel.onDragStart = { _, _ ->
            // First drag from the corner-anchored initial layout: switch to absolute TOP|START so
            // the saved coordinates make sense across sessions. Snapshot the current on-screen
            // position before flipping the anchor, otherwise the panel would jump.
            if (params.gravity != (Gravity.TOP or Gravity.START)) {
                val loc = IntArray(2)
                controlPanel.getLocationOnScreen(loc)
                params.gravity = Gravity.TOP or Gravity.START
                params.x = loc[0]
                params.y = loc[1]
            }
            initialX = params.x
            initialY = params.y
        }

        panel.onDragMove = { dx, dy ->
            params.x = initialX + dx.toInt()
            params.y = initialY + dy.toInt()
            try {
                windowManager.updateViewLayout(controlPanel, params)
            } catch (e: Exception) {
                Log.w(TAG, "updateViewLayout during drag failed", e)
            }
        }

        panel.onDragEnd = {
            val prefs = PreferencesManager.getInstance(this)
            prefs.controlPanelX = params.x
            prefs.controlPanelY = params.y
            Log.d(TAG, "Control panel position saved: x=${params.x}, y=${params.y}")
        }
    }

    private fun createFallbackControlPanel() {
        Log.d(TAG, "Creating fallback control panel")
        val controlPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = ColorDrawable(Color.parseColor("#CC333333"))
            setPadding(24, 12, 24, 12)
        }

        val controlParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
        }

        // Add buttons
        val closeButton = Button(this).apply {
            text = "✕"
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(Color.WHITE)
            setOnClickListener { stopSelf() }
        }

        val settingsButton = Button(this).apply {
            text = "⚙"
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(Color.WHITE)
            setOnClickListener {
                val intent = Intent(this@OverlayService, SettingsActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            }
        }

        val pauseButton = Button(this).apply {
            text = "⏸"
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(Color.WHITE)
            tag = false
            setOnClickListener {
                val isPaused = tag as Boolean
                if (isPaused) {
                    translationOverlay.visibility = View.VISIBLE
                    tag = false
                    text = "⏸"
                } else {
                    translationOverlay.visibility = View.INVISIBLE
                    tag = true
                    text = "▶"
                }
            }
        }

        // Add buttons to panel
        controlPanel.addView(pauseButton)
        controlPanel.addView(settingsButton)
        controlPanel.addView(closeButton)

        // Set up drag functionality
        setupControlPanelDrag(controlParams)

        // Add to window
        windowManager.addView(controlPanel, controlParams)
    }

    // ===== TRANSLATION OVERLAY =====

    private fun createTranslationOverlay() {
        translationOverlay = FrameLayout(this).apply {
            background = null // Keep background null/transparent
            // These might not be strictly necessary if the window itself is not touchable
            // isClickable = false
            // isFocusable = false
            isClickable = true
        }

        val params = WindowManager.LayoutParams(
            //WindowManager.LayoutParams.MATCH_PARENT,
            //WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT, // <-- Use WRAP_CONTENT for the draggable block
            WindowManager.LayoutParams.WRAP_CONTENT, // <-- Use WRAP_CONTENT for the draggable block
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Add FLAG_NOT_TOUCHABLE here
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or // Keep for good measure
                    //WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    //WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, // <- Add this flag
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or // Keep this flag
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, // Add this flag to allow dragging outside screen bounds temporarily
            PixelFormat.TRANSLUCENT
        ).apply {
            // Remove gravity if you are setting x, y directly
            // gravity = Gravity.TOP or Gravity.START // Remove or adjust
            // x and y will be managed by dragging
            gravity = Gravity.TOP or Gravity.START // Set initial gravity
            x = 100 // Set initial X position (adjust as needed)
            y = 300 // Set initial Y position (adjust as needed)
        }

        this.overlayLayoutParams  = params // Assign to class member
        windowManager.addView(translationOverlay, params)
        Log.d(TAG, "Translation overlay added successfully (draggable)")

        // Setup drag listener for the translation overlay
        setupTranslationOverlayDrag() // Call the new drag setup method
    }

    private fun hideOverlaysForCapture() {
        captureHideHandler.post {
            if (::translationOverlay.isInitialized) {
                savedTransVis = translationOverlay.visibility
                translationOverlay.visibility = View.INVISIBLE
            }
            if (::inPlaceOverlay.isInitialized) {
                savedInPlaceVis = inPlaceOverlay.visibility
                inPlaceOverlay.visibility = View.INVISIBLE
            }
        }
    }

    private fun showOverlaysAfterCapture() {
        captureHideHandler.post {
            if (::translationOverlay.isInitialized) translationOverlay.visibility = savedTransVis
            if (::inPlaceOverlay.isInitialized) inPlaceOverlay.visibility = savedInPlaceVis
        }
    }

    // Tiny window that gets ACTION_OUTSIDE for any tap elsewhere (without consuming it),
    // so we can detect when the user advances/changes the screen — even under the box.
    private fun createTouchWatch() {
        val watch = View(this)
        val params = WindowManager.LayoutParams(
            1, 1,
            getOverlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 0
        }
        watch.setOnTouchListener { _, event ->
            Log.d(TAG, "touchWatch action=${event.action}")
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                clearInPlaceImmediately()      // hide stale box at once, before the screen changes
                ScreenCaptureService.onUserInput()
            }
            false // never consume — the tap still reaches the app underneath
        }
        try {
            windowManager.addView(watch, params)
            touchWatchView = watch
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add touch watch", e)
        }
    }

    // Full-screen, non-touchable overlay used for in-place translation (boxes over the original text)
    private fun createInPlaceOverlay() {
        inPlaceOverlay = FrameLayout(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            getOverlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        try {
            windowManager.addView(inPlaceOverlay, params)
            inPlaceOverlay.visibility = View.GONE
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create in-place overlay", e)
        }
    }

    // Draws a frosted box with the translation over each original text region
    private fun renderInPlace(translations: List<TranslationService.TranslatedBlock>) {
        inPlaceOverlay.removeAllViews()
        if (translations.isEmpty()) {
            inPlaceOverlay.visibility = View.GONE
            return
        }
        val prefs = PreferencesManager.getInstance(this)
        val loc = IntArray(2)
        inPlaceOverlay.getLocationOnScreen(loc)
        Log.d(TAG, "inPlaceOverlay loc=(${loc[0]},${loc[1]}) size=${inPlaceOverlay.width}x${inPlaceOverlay.height}")
        val screenW = if (inPlaceOverlay.width > 0) inPlaceOverlay.width
        else resources.displayMetrics.widthPixels

        for (t in translations) {
            val rect = t.boundingBox

            // 把 box 宽度放大到原文 bounding box 的 1.5 倍，给较长的译文留排版空间
            val origW = rect.width()
            // 原文窄 → 给 1.5× 让长译文舒展；原文已经很宽 → 只放 1.1×，避免撞屏幕右沿后被钳回去
            val factor = if (origW < screenW * 0.5f) 1.5f else 1.1f
            val expandedW = (origW * factor).toInt()
            val boxWidth = expandedW + 8  // +8 是原来的 padding 余量

            val tv = TextView(this).apply {
                text = t.translatedText
                setTextColor(prefs.translationTextColor)
                typeface = resultTypeface()
                textSize = 14f * textSizeMultiplier
                setPadding(12, 6, 12, 6)
                background = if (t.bgColor != 0) {
                    android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = 8f
                        setColor(t.bgColor or 0xFF000000.toInt())
                    }
                } else {
                    android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = 8f
                        setColor(prefs.translationBgColor or 0xFF000000.toInt())
                    }
                }
                minWidth = boxWidth
                minHeight = rect.height() + 8
                gravity = Gravity.CENTER_VERTICAL
            }

            // 默认左边对齐原文左沿（往右扩 0.5×），如果右沿会超出屏幕则整体往左移
            var leftAbs = (rect.left - loc[0] - 4)
            if (leftAbs + boxWidth > screenW) {
                leftAbs = screenW - boxWidth
            }
            leftAbs = leftAbs.coerceAtLeast(0)

            Log.d(TAG, "in-place block: rect=$rect boxW=$boxWidth overlay=${inPlaceOverlay.width}x${inPlaceOverlay.height}")
            val lp = FrameLayout.LayoutParams(
                boxWidth,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = leftAbs
                topMargin = (rect.top - loc[1] - 4).coerceAtLeast(0)
            }
            inPlaceOverlay.addView(tv, lp)
        }
        inPlaceOverlay.visibility = View.VISIBLE
    }

    private fun updateOverlays(translations: List<TranslationService.TranslatedBlock>) {
        if (isPaused) return
        if (PreferencesManager.getInstance(this).inPlaceMode) {
            translationOverlay.visibility = View.GONE
            renderInPlace(translations)
            return
        }
        if (::inPlaceOverlay.isInitialized) inPlaceOverlay.visibility = View.GONE
        val rotation = getCurrentRotation()
        Log.d(TAG, "Updating overlays with ${translations.size} translations, rotation: $rotation")

        // Remove previous overlays
        /*for (view in translatedViews.values) {
            translationOverlay.removeView(view)
        }
        translatedViews.clear()*/

        // Remove all previous views from the translation overlay container
        translationOverlay.removeAllViews()
        translatedViews.clear() // Clear the map as we no longer store individual views

        // If we have an active translation area, adjust the positions
        /*val areaOffset = if (activeTranslationArea != null) {
            activeTranslationArea
        } else {
            null
        }

        // Add new translations with current rotation
        for (translation in translations) {
            // If we have an active area, check if the text is inside it
            val shouldShow = if (areaOffset != null) {
                val rect = translation.boundingBox
                RectF(rect).intersects(areaOffset.left, areaOffset.top, areaOffset.right, areaOffset.bottom)
            } else {
                true
            }

            if (shouldShow) {
                createOverlayForTranslation(translation, rotation)
            }
        }*/
        // === New Logic: Merge Translations and Create a Single Draggable Block ===
        if (translations.isNotEmpty()) {
            val stringBuilder = StringBuilder()
            val combinedOriginalRect = Rect() // Optional: calculate combined bounding box if needed

            for (translation in translations) {
                stringBuilder.append(translation.translatedText).append("\n") // Combine translated text
                // Optional: update combinedOriginalRect to encompass all block bounding boxes
                // if (combinedOriginalRect.isEmpty) {
                //    combinedOriginalRect.set(translation.boundingBox)
                // } else {
                //    combinedOriginalRect.union(translation.boundingBox)
                // }
            }

            val combinedText = stringBuilder.toString().trim()

            // Create a single TextView (or other container like ScrollView  TextView)
            val mergedTextView = TextView(this).apply {
                text = combinedText
                setTextColor(PreferencesManager.getInstance(context).translationTextColor)
                typeface = resultTypeface()
                textSize = 14f * textSizeMultiplier // Apply text size setting
                setPadding(20, 14, 20, 14)
                background = buildResultBackground()
                elevation = 6f

                // Make it respond to long clicks (if needed for combined text)
                // Don't consume touches, so the container's drag listener can move the overlay
                isClickable = false
                isLongClickable = false
            }

            // Add the merged TextView to the translation overlay container
            translationOverlay.addView(mergedTextView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, // TextView size based on content
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))

            // Store the single view if needed, but clearing translatedViews might be sufficient
            // translatedViews[0] = mergedTextView // Example if you need to reference it later

            // Add highlights for original blocks if enabled
            if (highlightOriginalText) {
                // Keep adding highlight views for original blocks
                for (translation in translations) {
                    val rect = translation.boundingBox
                    // You might need to adjust rect for rotation here if addHighlight doesn't
                    val highlightRect = getRotatedRect(rect, rotation) // Reuse your rotation adjustment logic
                    addHighlight(highlightRect)
                }
            }


            translationOverlay.visibility = View.VISIBLE // Show the overlay container
        } else {
            // No translations, hide the overlay container
            translationOverlay.visibility = View.GONE
            // Also ensure no highlight views remain
            // Note: removeAllViews() above handles clearing highlights if they were added to translationOverlay
        }

    }

    /*private fun createOverlayForTranslation(translation: TranslationService.TranslatedBlock, rotation: Int) {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = wm.defaultDisplay
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels

        // Create text view for translation
        val textView = TextView(this).apply {
            text = translation.translatedText
            setTextColor(ContextCompat.getColor(context, R.color.translation_text_color))
            setBackgroundColor(getBackgroundColorWithOpacity())
            textSize = 14f * textSizeMultiplier
            setPadding(8, 4, 8, 4)
            maxWidth = if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
                // In landscape, limit width to 40% of screen width
                (screenWidth * 0.4).toInt()
            } else {
                (screenWidth * 0.8).toInt()
            }

            // Make it respond to long clicks but not normal clicks
            isClickable = false
            isLongClickable = true

            setOnLongClickListener {
                showContextMenu(translation, it)
                true
            }
        }

        // Apply styling
        applyTextViewStyling(textView)

        // Position differently based on orientation
        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        // Get original rect
        val rect = translation.boundingBox

        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            // For landscape orientation, create a sidebar layout
            // Use a different approach - line up translations on the right side
            val isRightSide = rotation == Surface.ROTATION_90

            // Use index to calculate vertical position
            val index = translatedViews.size
            val verticalSpacing = 15 // Pixels between entries
            var topMargin = 100 + (index * (textView.lineHeight + verticalSpacing))

            if (isRightSide) {
                // Right side of screen
                params.gravity = Gravity.TOP or Gravity.END
                params.rightMargin = 50
                params.topMargin = topMargin
            } else {
                // Left side of screen
                params.gravity = Gravity.TOP or Gravity.START
                params.leftMargin = 50
                params.topMargin = topMargin
            }

            // Add small visual indicator connecting to original text
            val indicator = View(this).apply {
                setBackgroundColor(ContextCompat.getColor(context, R.color.translation_text_color))
                alpha = 0.5f
            }

            var indicatorLeftMargin = rect.centerX()
            var indicatorTopMargin = rect.centerY()
            var indicatorWidth = if (isRightSide) {
                screenWidth - params.rightMargin - indicatorLeftMargin
            } else {
                params.leftMargin - indicatorLeftMargin
            }
            var indicatorHeight = Math.abs(topMargin - params.topMargin)

            val indicatorParams = FrameLayout.LayoutParams(
                indicatorWidth,
                indicatorHeight
            ).apply {
                leftMargin = indicatorLeftMargin
                topMargin = indicatorTopMargin
            }

            translationOverlay.addView(indicator, indicatorParams)
            translatedViews[rect.hashCode() + 2000] = indicator

        } else {
            // Portrait mode - position below original text
            params.leftMargin = rect.left
            params.topMargin = rect.bottom + 5

            // Prevent extending beyond screen edges
            if (params.leftMargin + textView.width > screenWidth - 10) {
                params.leftMargin = screenWidth - textView.width - 10
            }
            if (params.leftMargin < 10) {
                params.leftMargin = 10
            }
        }

        // Add to overlay
        translationOverlay.addView(textView, params)
        translatedViews[rect.hashCode()] = textView

        // Add highlight if enabled
        if (highlightOriginalText) {
            addHighlight(rect)
        }
    }*/

    /*private fun addOverlayForTranslation(translation: TranslationService.TranslatedBlock, rotation: Int) {
        //val displayMetrics = resources.displayMetrics
        //val screenWidth = displayMetrics.widthPixels
        //val screenHeight = displayMetrics.heightPixels

        val rect = translation.boundingBox
        val rotation = getCurrentRotation()

        // Get screen dimensions
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = wm.defaultDisplay
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels

        val adjustedRect = Rect(rect)

        // Adjust rect based on rotation
        if (rotation == Surface.ROTATION_90) {
            // For 90-degree rotation (landscape right):
            // Calculate new coordinates by mapping:
            // New X = Y (from top)
            // New Y = screenWidth - Right X
            val newLeft = rect.top
            val newTop = screenWidth - rect.right
            val newRight = rect.bottom
            val newBottom = screenWidth - rect.left

            adjustedRect.set(newLeft, newTop, newRight, newBottom)
        } else if (rotation == Surface.ROTATION_270) {
            // For 270-degree rotation (landscape left):
            // New X = screenHeight - Bottom Y
            // New Y = Left X
            val newLeft = screenHeight - rect.bottom
            val newTop = rect.left
            val newRight = screenHeight - rect.top
            val newBottom = rect.right

            adjustedRect.set(newLeft, newTop, newRight, newBottom)
        } else if (rotation == Surface.ROTATION_180) {
            // For 180-degree rotation (upside down):
            // New X = screenWidth - Right X
            // New Y = screenHeight - Bottom Y
            val newLeft = screenWidth - rect.right
            val newTop = screenHeight - rect.bottom
            val newRight = screenWidth - rect.left
            val newBottom = screenHeight - rect.top

            adjustedRect.set(newLeft, newTop, newRight, newBottom)
        }

        // Get the original bounding box
        //val originalRect = translation.boundingBox

        // Adjust the bounding box based on rotation
        //val adjustedRect = adjustRectForRotation(originalRect, currentRotation, screenWidth, screenHeight)

        Log.d(TAG, "Adding overlay for text: ${translation.translatedText}")
        Log.d(TAG, "Original rect: $rect, Adjusted rect: $adjustedRect")

        // Create text view for translation
        val textView = TextView(this).apply {
            text = translation.translatedText
            setTextColor(ContextCompat.getColor(context, R.color.translation_text_color))
            setBackgroundColor(getBackgroundColorWithOpacity())
            textSize = 14f * textSizeMultiplier
            setPadding(8, 4, 8, 4)

            // Make it respond to long clicks but not normal clicks
            isClickable = false
            isLongClickable = true

            setOnLongClickListener {
                showContextMenu(translation, it)
                true
            }
        }

        // Apply styling
        applyTextViewStyling(textView)

        // Position based on adjusted rect
        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        // Adjust the positioning based on rotation
        if (rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180) {
            // In portrait mode, position below the original text
            params.leftMargin = adjustedRect.left
            params.topMargin = adjustedRect.bottom + 5
        } else {
            // In landscape mode, position to the right of the original text
            params.leftMargin = adjustedRect.right + 5
            params.topMargin = adjustedRect.top
        }

        // Ensure the text stays within screen bounds
        val currentScreenWidth = if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            screenHeight
        } else {
            screenWidth
        }

        // Add safety margin to prevent text from going off-screen
        val safetyMargin = 50
        if (params.leftMargin > currentScreenWidth - safetyMargin) {
            params.leftMargin = currentScreenWidth - safetyMargin
        }
        if (params.leftMargin < safetyMargin) {
            params.leftMargin = safetyMargin
        }

        // Add to overlay
        translationOverlay.addView(textView, params)
        translatedViews[adjustedRect.hashCode()] = textView

        // Add highlight if enabled
        if (highlightOriginalText) {
            addHighlight(adjustedRect)
        }
    }*/

    private fun getRotatedRect(rect: Rect, rotation: Int): Rect {
        if (rotation == Surface.ROTATION_0) {
            return Rect(rect)
        }

        val rotatedRect = Rect()
        val display = (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)

        when (rotation) {
            Surface.ROTATION_90 -> {
                rotatedRect.left = rect.top
                rotatedRect.top = metrics.widthPixels - rect.right
                rotatedRect.right = rect.bottom
                rotatedRect.bottom = metrics.widthPixels - rect.left
            }
            Surface.ROTATION_180 -> {
                rotatedRect.left = metrics.widthPixels - rect.right
                rotatedRect.top = metrics.heightPixels - rect.bottom
                rotatedRect.right = metrics.widthPixels - rect.left
                rotatedRect.bottom = metrics.heightPixels - rect.top
            }
            Surface.ROTATION_270 -> {
                rotatedRect.left = metrics.heightPixels - rect.bottom
                rotatedRect.top = rect.left
                rotatedRect.right = metrics.heightPixels - rect.top
                rotatedRect.bottom = rect.right
            }
        }

        return rotatedRect
    }

    // Helper method to adjust rect coordinates based on rotation
    private fun adjustRectForRotation(rect: Rect, rotation: Int, screenWidth: Int, screenHeight: Int): Rect {
        // Return original rect if no rotation
        if (rotation == Surface.ROTATION_0) {
            return rect
        }

        val adjustedRect = Rect(rect)

        when (rotation) {
            Surface.ROTATION_90 -> {
                // 90 degrees clockwise: (x, y) -> (y, screenWidth - x)
                val left = rect.top
                val top = screenWidth - rect.right
                val right = rect.bottom
                val bottom = screenWidth - rect.left
                adjustedRect.set(left, top, right, bottom)
            }
            Surface.ROTATION_180 -> {
                // 180 degrees: (x, y) -> (screenWidth - x, screenHeight - y)
                val left = screenWidth - rect.right
                val top = screenHeight - rect.bottom
                val right = screenWidth - rect.left
                val bottom = screenHeight - rect.top
                adjustedRect.set(left, top, right, bottom)
            }
            Surface.ROTATION_270 -> {
                // 270 degrees clockwise: (x, y) -> (screenHeight - y, x)
                val left = screenHeight - rect.bottom
                val top = rect.left
                val right = screenHeight - rect.top
                val bottom = rect.right
                adjustedRect.set(left, top, right, bottom)
            }
        }

        return adjustedRect
    }

    private fun addHighlight(rect: Rect) {
        val highlight = View(this).apply {
            setBackgroundColor(ContextCompat.getColor(context, R.color.highlight_color))
            alpha = 0.3f
            isClickable = false
            isFocusable = false
        }

        val params = FrameLayout.LayoutParams(
            rect.width(),
            rect.height()
        ).apply {
            leftMargin = rect.left
            topMargin = rect.top
        }

        translationOverlay.addView(highlight, params)
        translatedViews[rect.hashCode() + 1000] = highlight
    }

    private fun showContextMenu(translation: TranslationService.TranslatedBlock, view: View) {
        Log.d(TAG, "Showing context menu")
        val popup = PopupMenu(this, view)
        popup.menuInflater.inflate(R.menu.translation_context_menu, popup.menu)

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_copy_translation -> {
                    copyTranslationToClipboard(translation.translatedText)
                    true
                }
                R.id.action_copy_original -> {
                    copyTranslationToClipboard(translation.originalText)
                    true
                }
                R.id.action_share -> {
                    shareTranslation(translation)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun shareTranslation(translation: TranslationService.TranslatedBlock) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "${translation.originalText}\n${translation.translatedText}")
        }
        shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Use a constant string instead of a resource reference
        startActivity(Intent.createChooser(shareIntent, "Share Translation").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun applyTextViewStyling(textView: TextView) {
        if (useAlternativeStyle) {
            textView.setBackgroundResource(R.drawable.speech_bubble_background)
            textView.elevation = 10f
        } else {
            textView.setBackgroundResource(R.drawable.translation_background)
            textView.elevation = 5f
        }
        textView.alpha = overlayOpacity
    }

    private fun getBackgroundColorWithOpacity(): Int {
        val baseColor = ContextCompat.getColor(this, R.color.overlay_background)
        val alpha = (255 * overlayOpacity).toInt()
        return (baseColor and 0x00FFFFFF) or (alpha shl 24)
    }

    // Instantly remove the in-place boxes (called on a tap, on the main thread)
    private fun clearInPlaceImmediately() {
        if (::inPlaceOverlay.isInitialized && inPlaceOverlay.childCount > 0) {
            inPlaceOverlay.removeAllViews()
            inPlaceOverlay.visibility = View.GONE
        }
    }

    // Caches the user's selected typeface so we don't re-resolve it (and possibly re-parse a TTF
    // file) for every TextView we create. Invalidated when settings change — see settingsReceiver.
    @Volatile private var cachedTypeface: android.graphics.Typeface? = null

    private fun resultTypeface(): android.graphics.Typeface {
        cachedTypeface?.let { return it }
        val prefs = PreferencesManager.getInstance(this)
        // 1) User-loaded font wins if the file is still present (the user may have cleared/replaced
        //    it through settings; we tolerate stale paths by falling back to the spinner choice).
        val customPath = prefs.customFontPath
        if (customPath.isNotEmpty()) {
            try {
                val f = java.io.File(customPath)
                if (f.exists() && f.canRead()) {
                    val tf = android.graphics.Typeface.createFromFile(f)
                    cachedTypeface = tf
                    return tf
                }
            } catch (e: Exception) {
                Log.w(TAG, "Custom font load failed; falling back", e)
            }
        }
        // 2) Bundled .ttf in res/font/<name>.ttf
        val name = prefs.translationFont
        val resId = resources.getIdentifier(name, "font", packageName)
        val tf = if (resId != 0) {
            androidx.core.content.res.ResourcesCompat.getFont(this, resId)
                ?: android.graphics.Typeface.DEFAULT
        } else {
            // 3) System family name fallback (e.g. "sans-serif", "monospace")
            android.graphics.Typeface.create(name, android.graphics.Typeface.NORMAL)
        }
        cachedTypeface = tf
        return tf
    }

    // Rounded background for the translation result, using the configured colour + opacity
    private fun buildResultBackground(): android.graphics.drawable.GradientDrawable {
        val base = PreferencesManager.getInstance(this).translationBgColor
        val alpha = (255 * overlayOpacity).toInt()
        val color = (base and 0x00FFFFFF) or (alpha shl 24)
        return android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 16f
            setColor(color)
        }
    }

    fun updateSettings(textSize: Float, opacity: Float, highlight: Boolean, alternativeStyle: Boolean) {
        Log.d(TAG, "Updating settings: textSize=$textSize, opacity=$opacity, highlight=$highlight, alternativeStyle=$alternativeStyle")
        textSizeMultiplier = textSize
        overlayOpacity = opacity
        highlightOriginalText = highlight
        useAlternativeStyle = alternativeStyle

        // Refresh overlays with new settings
        translationData.value?.let { updateOverlays(it) }
    }

    fun copyTranslationToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Translated Text", text)
        clipboard.setPrimaryClip(clip)

        // Show toast
        android.widget.Toast.makeText(
            this,
            R.string.text_copied_to_clipboard,
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun setupTranslationOverlayDrag() {
        translationOverlay.setOnTouchListener(object : View.OnTouchListener {
            // Store the LayoutParams reference as a class member when creating the overlay
            // private lateinit var layoutParams: WindowManager.LayoutParams // Already added in step 1

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                // Ensure layoutParams is initialized (should be done in createTranslationOverlay)
                if (!::overlayLayoutParams.isInitialized) {
                    Log.e(TAG, "Translation overlay layoutParams not initialized!")
                    return false // Cannot drag if layoutParams is not ready
                }

                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        // Record initial position of the overlay window and touch point
                        initialOverlayX = overlayLayoutParams.x
                        initialOverlayY = overlayLayoutParams.y
                        initialOverlayTouchX = event.rawX
                        initialOverlayTouchY = event.rawY
                        Log.d(TAG, "Overlay Drag: ACTION_DOWN at ${event.rawX}, ${event.rawY}")
                        return true // Consume the event to start drag
                    }
                    MotionEvent.ACTION_MOVE -> {
                        // Calculate the drag offset
                        val deltaX = event.rawX - initialOverlayTouchX
                        val deltaY = event.rawY - initialOverlayTouchY

                        // Calculate the new position
                        overlayLayoutParams.x = (initialOverlayX + deltaX).toInt()
                        overlayLayoutParams.y = (initialOverlayY + deltaY).toInt()

                        // Update the view's position in the window
                        windowManager.updateViewLayout(translationOverlay, overlayLayoutParams)
                        Log.d(TAG, "Overlay Drag: ACTION_MOVE to ${overlayLayoutParams.x}, ${overlayLayoutParams.y}")

                        return true // Consume the event for dragging
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        // Touch ended, optional: save the final position
                        Log.d(TAG, "Overlay Drag: ACTION_UP or CANCEL")
                        // saveOverlayPosition(layoutParams.x, layoutParams.y) // Implement if persistence is needed
                        return true // Consume the event
                    }
                    else -> return false // Don't handle other actions
                }
            }
        })
        Log.d(TAG, "Translation overlay touch listener set.")
    }

    override fun onDestroy() {
        Log.d(TAG, "Service onDestroy")
        instance = null
        super.onDestroy()

        // Clean up
        try {
            unregisterReceiver(settingsReceiver)
            unregisterReceiver(rotationReceiver) // Unregister rotation receiver
            screenOrientationListener.disable()
            areaSelectionView?.let { windowManager.removeView(it) }
            windowManager.removeView(translationOverlay)
            if (::inPlaceOverlay.isInitialized) windowManager.removeView(inPlaceOverlay)
            touchWatchView?.let { windowManager.removeView(it) }
            windowManager.removeView(controlPanel)
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup: ${e.message}", e)
        }

        try {
            unregisterReceiver(areaUpdateReceiver)
        } catch (e: Exception) {
            Log.w("OverlayService", "Error unregistering area receiver", e)
        }

        // Remove area indicator view
        if (areaIndicatorView?.parent != null && windowManager != null) {
            try {
                windowManager.removeView(areaIndicatorView)
            } catch (e: Exception) {
                Log.e("OverlayService", "Error removing area indicator view on destroy", e)
            }
        }

        translationData.value = emptyList()
    }
}