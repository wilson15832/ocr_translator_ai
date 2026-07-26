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
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.ocr_translation.ui.AppTheme
import com.example.ocr_translation.ui.ControlWheel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import android.view.Surface
import android.util.DisplayMetrics
import android.graphics.RectF
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.AnimationDrawable
import android.widget.ImageView

class OverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var controlPanel: View
    private lateinit var translationOverlay: FrameLayout
    private lateinit var inPlaceOverlay: FrameLayout
    private val inPlaceLoc = IntArray(2)
    private var inPlaceLocValid = false
    private var touchWatchView: View? = null
    private val captureHideHandler = Handler(Looper.getMainLooper())
    private var savedTransVis = View.VISIBLE
    private var savedInPlaceVis = View.GONE
    private var savedTransAlpha = 1f
    private var savedInPlaceAlpha = 1f
    private val translatedViews = mutableMapOf<Int, View>()
    private var isPaused = false

    // Settings
    private var textSizeMultiplier = 1.0f
    private var overlayOpacity = 0.8f
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

    private lateinit var spinnerOverlay: ImageView
    private lateinit var spinnerParams: WindowManager.LayoutParams
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /**
     * Service context + overlay theme + the user's accent. Anything drawing in the accent must be
     * constructed with this, not with `this` — see AppTheme.overlayContext.
     */
    private val themedContext: Context by lazy { AppTheme.overlayContext(this) }

    private lateinit var overlayLayoutParams: WindowManager.LayoutParams // <-- 添加这行声明

    private val translationObserver = androidx.lifecycle.Observer<List<TranslationService.TranslatedBlock>> { translations ->
        updateOverlays(translations)
    }

    private val rotationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.ocr_translation.ACTION_ROTATION_CHANGED") {
                val newRotation = intent.getIntExtra("rotation", Surface.ROTATION_0)
                screenWidth = intent.getIntExtra("screenWidth", screenWidth)
                screenHeight = intent.getIntExtra("screenHeight", screenHeight)

                Log.d(TAG, "Received rotation change: $newRotation, screen: ${screenWidth}x${screenHeight}")

                if (currentRotation != newRotation) {
                    currentRotation = newRotation

                    // A translation's boxes are positioned from OCR coordinates taken in the old
                    // orientation, so after a rotation they are stale by definition — re-rendering
                    // them just paints them in the wrong places. Drop them and let the next scan
                    // produce boxes that fit the new geometry.
                    //
                    // This is also what left boxes over the launcher: leaving a landscape game
                    // rotates the screen, and the replay put the old translation back up. Clearing
                    // the view alone wasn't enough because the data outlived it, so the next
                    // rotation — re-entering the game — replayed it again.
                    Handler(Looper.getMainLooper()).postDelayed({
                        clearShownTranslationImpl()
                    }, 300)
                }
            }
        }
    }

    private class AreaIndicatorView(context: Context) : View(context) {

        var activeArea: RectF? = null

        private val density = context.resources.displayMetrics.density

        /**
         * A dashed accent frame instead of the old solid green rectangle: the same information,
         * far less visual noise mid-game (design 3e).
         */
        private val borderPaint = Paint().apply {
            color = AppTheme.colorPrimary(context)
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
            isAntiAlias = true
            pathEffect = android.graphics.DashPathEffect(
                floatArrayOf(7f * density, 5f * density), 0f
            )
        }

        private val tagPaint = Paint().apply {
            color = AppTheme.colorPrimary(context)
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        private val tagTextPaint = Paint().apply {
            color = Color.WHITE
            textSize = 10f * density
            isAntiAlias = true
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        private val tagText = context.getString(R.string.scan_area_tag)

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val area = activeArea ?: return
            val radius = 10f * density
            canvas.drawRoundRect(area, radius, radius, borderPaint)

            // "Scan area" chip, sitting on the frame's top-left corner
            val padH = 6f * density
            val padV = 2.5f * density
            val textW = tagTextPaint.measureText(tagText)
            val metrics = tagTextPaint.fontMetrics
            val chipH = (metrics.descent - metrics.ascent) + padV * 2
            val left = area.left + 6f * density
            val bottom = area.top - 3f * density
            val top = bottom - chipH
            if (top < 0f) return
            val chip = RectF(left, top, left + textW + padH * 2, bottom)
            canvas.drawRoundRect(chip, 7f * density, 7f * density, tagPaint)
            canvas.drawText(tagText, left + padH, bottom - padV - metrics.descent, tagTextPaint)
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
        /** Arbitrary large size to read font metrics from; cancels out in the ratio. */
        private const val TEXT_PROBE_PX = 100f
        private const val MIN_TEXT_SP = 8f
        private const val MAX_TEXT_SP = 48f
        /**
         * Share of the line pitch the text is fitted to. The remainder becomes padding, which is
         * what covers the stroke and glow game text is painted with — those extend past the OCR
         * box, so a box matched exactly to the text leaves a halo showing.
         */
        private const val TEXT_SHARE = 0.88f
        /** Characters of extra width, so the original's trailing glyphs stay covered. */
        private const val WIDTH_SLACK_CHARS = 4f

        private val translationData = MutableLiveData<List<TranslationService.TranslatedBlock>>()
        private val mainHandler = Handler(Looper.getMainLooper())
        private const val TAG = "OverlayService"

        // Lets the capture service hide our translated overlays during a screen grab,
        // so we never OCR our own results back.
        @Volatile private var instance: OverlayService? = null
        fun hideForCapture() { instance?.hideOverlaysForCapture() }
        fun showAfterCapture() { instance?.showOverlaysAfterCapture() }

        fun fadeOutForCapture() { instance?.fadeOutOverlaysForCapture() }
        fun fadeInAfterCapture() { instance?.fadeInOverlaysAfterCapture() }

        fun getTranslationData(): LiveData<List<TranslationService.TranslatedBlock>> = translationData

        fun showTranslation(translations: List<TranslationService.TranslatedBlock>) {
            Log.d(TAG, "showTranslation called with ${translations.size} items.") // 添加日志
            if (translations.isNotEmpty()) {
                Log.d(TAG, "First item original: ${translations[0].originalText}, translated: ${translations[0].translatedText}") // 记录第一条看看
                PerfTrace.resultPending()
            }
            mainHandler.post { translationData.value = translations }
        }

        fun clearShownTranslation() { instance?.clearShownTranslationImpl() }

        fun showSpinner() { instance?.showSpinnerImpl() }
        fun hideSpinner() { instance?.hideSpinnerImpl() }
    }

    private val settingsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.ocr_translation.ACTION_UPDATE_OVERLAY_SETTINGS") {
                val textSize = intent.getFloatExtra("textSize", 1.0f)
                val opacity = intent.getFloatExtra("opacity", 0.8f)
                val alternativeStyle = intent.getBooleanExtra("alternativeStyle", false)

                updateSettings(textSize, opacity, alternativeStyle)

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

        areaIndicatorView = AreaIndicatorView(themedContext)

        // Create separate windows for control panel and translations
        createControlPanelWindow()
        createTranslationOverlay()
        createInPlaceOverlay()
        createTouchWatch()
        createSpinnerOverlay()

        // Observe translation data
        translationData.observeForever(translationObserver)

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

    // ===== CONTROL PANEL =====

    private fun createControlPanelWindow() {
        try {
            controlPanel = LayoutInflater.from(themedContext).inflate(
                R.layout.overlay_control_panel, null
            )

            val prefs = PreferencesManager.getInstance(this)
            val savedX = prefs.controlPanelX
            val savedY = prefs.controlPanelY

            val controlParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                getOverlayType(),
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
     * Applies the control panel's appearance prefs to the live wheel: orientation, and the
     * background colour + opacity as a capsule behind the strip.
     *
     * The backing exists because the glyphs' own drop shadow isn't always enough — over a white
     * scene all three icons wash out. Opacity 0 gives back the chrome-less look, so the user
     * picks where to sit between legible and unobtrusive.
     */
    private fun applyControlPanelStyle() {
        if (!::controlPanel.isInitialized) return
        val prefs = PreferencesManager.getInstance(this)
        val wheel = controlPanel as? ControlWheel ?: return

        wheel.setWheelOrientation(
            if (prefs.controlPanelOrientation == "vertical") LinearLayout.VERTICAL
            else LinearLayout.HORIZONTAL
        )

        val baseRgb = prefs.controlPanelBgColor and 0x00FFFFFF
        val alpha = (255f * prefs.controlPanelOpacity.coerceIn(0f, 1f)).toInt()
        wheel.setPanelBacking((alpha shl 24) or baseRgb)
    }

    /**
     * Drops the shown translation — views *and* the data behind them.
     *
     * [translationData] is static and outlives any single render, so clearing only the views left
     * a translation that anything re-reading the LiveData could resurrect. Emptying it makes
     * "nothing is shown" the actual state rather than just the current appearance.
     */
    private fun clearShownTranslationImpl() {
        translationData.postValue(emptyList())
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

    /**
     * Wires the wheel's single "fire the armed action" callback to the five actions the bar used
     * to expose as separate buttons.
     */
    private fun setupControlPanelButtons() {
        val wheel = controlPanel as? ControlWheel ?: return
        val prefs = PreferencesManager.getInstance(this)

        // Restore the armed action. First run has no stored focus, so foldFavorite — which used
        // to pick the icon shown when folded — seeds it, keeping that setting meaningful.
        val stored = prefs.wheelFocus
        wheel.action = if (stored in ControlWheel.Action.entries.indices) {
            ControlWheel.Action.entries[stored]
        } else if (prefs.foldFavorite == "auto") {
            ControlWheel.Action.AUTO
        } else {
            ControlWheel.Action.TRANSLATE
        }
        wheel.setAutoRunning(ScreenCaptureService.autoMode)

        wheel.onFocusChanged = { action -> prefs.wheelFocus = action.ordinal }
        wheel.onLabel = { label -> showWheelLabel(label) }

        wheel.onFire = { action ->
            when (action) {
                ControlWheel.Action.AUTO -> {
                    val enabled = !ScreenCaptureService.autoMode
                    ScreenCaptureService.autoMode = enabled
                    wheel.setAutoRunning(enabled)
                    if (!enabled) {
                        // Stopping auto translation: clear any result still on screen
                        clearInPlaceImmediately()
                        translationOverlay.visibility = View.GONE
                    }
                    Log.d(TAG, "Auto mode: $enabled")
                }

                ControlWheel.Action.TRANSLATE -> {
                    // Long-press used to be the "force a full OCR pass" variant, but the wheel
                    // needs long-press for dragging, so the tap takes over the forcing role —
                    // it's the behaviour worth having on the reachable gesture.
                    Log.d(TAG, "Manual translate (FORCE_OCR) requested")
                    ScreenCaptureService.requestManualTranslation(
                        ScreenCaptureService.ManualKind.FORCE_OCR
                    )
                }

                ControlWheel.Action.SELECT_AREA -> {
                    Log.d(TAG, "Select area clicked")
                    startAreaSelection()
                }

                ControlWheel.Action.DOCK -> dockControlPanel()

                ControlWheel.Action.CLOSE -> {
                    Log.d(TAG, "Close clicked — stopping translation")
                    prefs.setTranslationActive(false)
                    stopService(Intent(this, ScreenCaptureService::class.java))
                    stopSelf()
                }
            }
        }
    }

    // ===== EDGE DOCK (design 4a) =====

    private var dockSliverView: View? = null

    /**
     * Parks the wheel off-screen against whichever side edge it currently sits nearer to, leaving
     * a 7dp accent sliver behind. That takes the bar from ~60dp of the game down to 7dp for the
     * stretches where you aren't touching it.
     *
     * The sliver doubles as the state readout while the wheel is hidden: green while auto-scan is
     * running, accent otherwise.
     */
    private fun dockControlPanel() {
        if (dockSliverView != null) return
        val loc = IntArray(2)
        controlPanel.getLocationOnScreen(loc)
        val screenW = resources.displayMetrics.widthPixels
        val onLeft = loc[0] + controlPanel.width / 2 < screenW / 2
        val sliverTop = loc[1] + controlPanel.height / 2 - dp(38)

        controlPanel.visibility = View.GONE
        showWheelLabel(null)

        // A 24dp-wide transparent window so the 7dp sliver has a reachable touch target.
        val sliver = FrameLayout(themedContext).apply {
            isClickable = true
            setOnClickListener { undockControlPanel() }
        }
        sliver.addView(
            View(themedContext).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(
                        if (ScreenCaptureService.autoMode) Color.parseColor("#34C759")
                        else AppTheme.colorPrimary(themedContext)
                    )
                    // Rounded on the inboard side only, square against the screen edge.
                    val r = dp(5).toFloat()
                    cornerRadii = if (onLeft) {
                        floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
                    } else {
                        floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
                    }
                }
            },
            FrameLayout.LayoutParams(dp(7), dp(76)).apply {
                gravity = (if (onLeft) Gravity.START else Gravity.END) or Gravity.CENTER_VERTICAL
            }
        )

        val params = WindowManager.LayoutParams(
            dp(24), dp(76),
            getOverlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or (if (onLeft) Gravity.START else Gravity.END)
            x = 0
            y = sliverTop.coerceAtLeast(0)
        }

        try {
            windowManager.addView(sliver, params)
            dockSliverView = sliver
            Log.d(TAG, "Control panel docked to ${if (onLeft) "left" else "right"} edge")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dock control panel", e)
            controlPanel.visibility = View.VISIBLE
        }
    }

    /** Brings the wheel back and removes the sliver. */
    private fun undockControlPanel() {
        dockSliverView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        dockSliverView = null
        if (::controlPanel.isInitialized) controlPanel.visibility = View.VISIBLE
        Log.d(TAG, "Control panel undocked")
    }

    // ===== WHEEL LABEL =====

    private var wheelLabelView: TextView? = null

    /**
     * The transient pill naming the armed action while the user cycles. Deliberately not a
     * permanent status readout — it appears only during a cycle and removes itself afterwards,
     * so at rest the wheel costs nothing but its own 66dp strip.
     */
    private fun showWheelLabel(text: CharSequence?) {
        if (text == null) {
            wheelLabelView?.let {
                try { windowManager.removeView(it) } catch (_: Exception) {}
            }
            wheelLabelView = null
            return
        }

        val label = wheelLabelView ?: TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 11.5f
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(9).toFloat()
                setColor(Color.parseColor("#D11C1C1E"))
            }
            elevation = dp(4).toFloat()
        }

        label.text = text
        if (label.parent == null) {
            // Parked just inboard of the wheel, on the same edge it lives on.
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                getOverlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                val loc = IntArray(2)
                controlPanel.getLocationOnScreen(loc)
                x = (loc[0] - dp(96)).coerceAtLeast(dp(8))
                y = loc[1] + controlPanel.height / 2 - dp(14)
            }
            try {
                windowManager.addView(label, params)
                wheelLabelView = label
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't show wheel label", e)
            }
        }
    }

    // ===== IN-OVERLAY AREA SELECTION =====

    private fun startAreaSelection() {
        if (areaSelectionView != null) return

        val selector = AreaSelectionOverlay(themedContext)

        val hint = TextView(this).apply {
            text = getString(R.string.select_area_hint)
            setTextColor(Color.parseColor("#A6FFFFFF"))
            textSize = 13f
            gravity = Gravity.CENTER
        }

        // "OK / Cancel" becomes Cancel · Full screen · Use area, which finally gives the
        // clear_area string a place in the flow — previously it existed with nothing to trigger it.
        val cancelBtn = areaButton(getString(android.R.string.cancel), primary = false)
        val fullScreenBtn = areaButton(getString(R.string.clear_area_short), primary = false)
        val okBtn = areaButton(getString(R.string.use_area), primary = true)

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(cancelBtn, LinearLayout.LayoutParams(0, dp(44), 1f))
            addView(fullScreenBtn, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                marginStart = dp(10)
            })
            addView(okBtn, LinearLayout.LayoutParams(0, dp(44), 1.2f).apply {
                marginStart = dp(10)
            })
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#B8141416"))
            setPadding(dp(16), dp(14), dp(16), dp(18))
            addView(hint, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) })
            addView(buttonRow, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
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
        fullScreenBtn.setOnClickListener {
            clearTranslationArea()
            endAreaSelection()
        }
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

    /** Rounded 44dp action for the area-selection bar; the primary one carries the accent. */
    private fun areaButton(label: String, primary: Boolean): TextView = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = 16f
        setTextColor(
            if (primary) AppTheme.contrastOn(AppTheme.colorPrimary(themedContext))
            else Color.WHITE
        )
        setTypeface(
            typeface,
            if (primary) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
        )
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(
                if (primary) AppTheme.colorPrimary(themedContext)
                else Color.parseColor("#47767680")
            )
        }
        isClickable = true
    }

    /** Tells the capture service to go back to the full screen. */
    private fun clearTranslationArea() {
        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            action = "com.example.ocr_translation.ACTION_CLEAR_TRANSLATION_AREA"
        }
        try {
            startService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear translation area", e)
        }
        hideAreaIndicator()
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

    /**
     * Moving the bar. The wheel reports a move only after a ~350ms hold, because an immediate
     * drag along the strip is how you cycle the armed action — the two gestures share one surface
     * and are separated by time, not direction.
     */
    private fun setupControlPanelDrag(params: WindowManager.LayoutParams) {
        val wheel = controlPanel as? ControlWheel ?: return
        var initialX = 0
        var initialY = 0

        wheel.onMoveStart = {
            // First move from the corner-anchored initial layout: switch to absolute TOP|START so
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
            showWheelLabel(null)
        }

        wheel.onMove = { dx, dy ->
            params.x = initialX + dx.toInt()
            params.y = initialY + dy.toInt()
            try {
                windowManager.updateViewLayout(controlPanel, params)
            } catch (e: Exception) {
                Log.w(TAG, "updateViewLayout during drag failed", e)
            }
        }

        wheel.onMoveEnd = {
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
            getOverlayType(),
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
            getOverlayType(),
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

    private var faded = false

    private fun fadeOutOverlaysForCapture() {
        captureHideHandler.post {
            if (faded) return@post          // 已经淡出，别再覆盖 savedAlpha
            faded = true
            if (::translationOverlay.isInitialized) { savedTransAlpha = translationOverlay.alpha; translationOverlay.alpha = 0f }
            if (::inPlaceOverlay.isInitialized)   { savedInPlaceAlpha = inPlaceOverlay.alpha;   inPlaceOverlay.alpha = 0f }
        }
    }

    private fun fadeInOverlaysAfterCapture() {
        captureHideHandler.post {
            if (!faded) return@post
            faded = false
            if (::translationOverlay.isInitialized) translationOverlay.alpha = savedTransAlpha
            if (::inPlaceOverlay.isInitialized)   inPlaceOverlay.alpha = savedInPlaceAlpha
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
            inPlaceOverlay.viewTreeObserver.addOnGlobalLayoutListener {
                if (inPlaceOverlay.visibility == View.VISIBLE && inPlaceOverlay.width > 0) {
                    inPlaceOverlay.getLocationOnScreen(inPlaceLoc)
                    inPlaceLocValid = true
                    Log.d(TAG, "inPlaceLoc cached: ${inPlaceLoc[0]}, ${inPlaceLoc[1]}")   // ← 顺手加，验证用
                }
            }
            inPlaceOverlay.visibility = View.GONE
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create in-place overlay", e)
        }
    }

    /** 把几何重叠或紧邻（水平有交集且竖直间隙很小）的 block 并成组。 */
    private fun groupOverlapping(
        items: List<TranslationService.TranslatedBlock>
    ): List<List<TranslationService.TranslatedBlock>> {
        val n = items.size
        val parent = IntArray(n) { it }
        fun find(x: Int): Int { var r = x; while (parent[r] != r) r = parent[r]; return r }
        for (i in 0 until n) for (j in i + 1 until n)
            if (related(items[i].boundingBox, items[j].boundingBox)) parent[find(i)] = find(j)
        return items.indices.groupBy { find(it) }.values.map { idx -> idx.map { items[it] } }
    }

    private fun related(a: Rect, b: Rect): Boolean {
        val hOver = minOf(a.right, b.right) - maxOf(a.left, b.left)
        if (hOver <= 0) return false                                   // 水平无交集 → 不相关
        val vGap = maxOf(a.top, b.top) - minOf(a.bottom, b.bottom)     // <0 重叠，>0 间隙
        return vGap < minOf(a.height(), b.height()) * 0.5f             // 重叠或间隙很小才合并
    }

    // Draws a frosted box with the translation over each original text region
    /** A box that has been built and measured but not yet positioned. */
    private class PlannedBox(
        val view: View,
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
        /** Vertical padding, i.e. the distance from the box's edge to the text inside it. */
        val padV: Int
    )

    private fun renderInPlace(translations: List<TranslationService.TranslatedBlock>) {
        inPlaceOverlay.removeAllViews()
        if (translations.isEmpty()) { inPlaceOverlay.visibility = View.GONE; return }
        inPlaceOverlay.visibility = View.VISIBLE
        if (!inPlaceLocValid) {
            // 窗口还没被 WindowManager 定位过（首次显示），等布局回调把 loc 缓存好再渲染
            inPlaceOverlay.post { renderInPlace(translations) }
            return
        }
        val loc = inPlaceLoc
        val prefs = PreferencesManager.getInstance(this)
        val screenW = if (inPlaceOverlay.width > 0) inPlaceOverlay.width
        else resources.displayMetrics.widthPixels
        val refH = translations.maxOf { it.boundingBox.height() }.coerceAtLeast(1)

        // Build and measure every box first; positioning happens in one pass afterwards so
        // overlaps between boxes can be resolved with all of them known.
        val boxes = mutableListOf<PlannedBox>()
        for (group in groupOverlapping(translations)) {
            // One size for the whole group. Sizing each line from its own OCR box makes lines
            // that happen to contain Latin or tall glyphs ("Alterego") come out visibly larger
            // than their neighbours, even though the original renders them all the same.
            val pitch = typicalPitch(group)
            if (prefs.mergeOverlapBoxes) {
                // 模式B：合并重叠框、保留注音（单背景，组内各行透明叠加）
                boxes += buildMergedBox(group, pitch, screenW, loc, prefs)
            } else {
                // 模式A：独立框、丢弃注音（组内丢掉矮块，其余各自成框）
                val maxH = group.maxOf { it.boundingBox.height() }
                for (t in group.filter { it.boundingBox.height() >= maxH * 0.6f }) {
                    boxes += buildSeparateBox(t, pitch, screenW, loc, prefs)
                }
            }
        }
        placeWithoutOverlap(boxes)
        inPlaceOverlay.visibility = View.VISIBLE
    }

    /**
     * Representative line height for a group: the median, so one unusually tall OCR box doesn't
     * drag the whole paragraph's size with it.
     */
    private fun typicalHeight(group: List<TranslationService.TranslatedBlock>): Int =
        median(group.map { it.boundingBox.height() }).coerceAtLeast(1)

    /**
     * True median. `sorted[size / 2]` — which this used to be — returns the *upper* of the two
     * middle samples on an even-sized list, so a two-line group took the larger of its two
     * measurements every time. That biased pitch upward, and an inflated pitch inflates both the
     * font and the box: a three-block group measuring gaps of 50 and 35 came out at 50 instead
     * of 42, ~11% taller than the paragraph around it.
     */
    private fun median(values: List<Int>): Int {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    /**
     * Line pitch — the distance from one line's top to the next's — which is what a box has to
     * be to tile the original exactly.
     *
     * Sizing boxes to the OCR height instead was the source of the drift: an OCR box hugs its
     * glyphs, so it is shorter than the pitch by the line spacing. Fitting to the height meant
     * every box was that spacing too short to reach its neighbour (a visible stripe of original
     * between covers) while the padding needed to hide the glyph glow simultaneously pushed the
     * box past the next line's top, so the overlap pass shoved it down — and that shove
     * accumulated line after line.
     *
     * With the box exactly one pitch tall and anchored one padding above its OCR top, consecutive
     * boxes meet edge to edge: no stripe, and nothing for the overlap pass to resolve.
     */
    private fun typicalPitch(group: List<TranslationService.TranslatedBlock>): Int {
        val fallback = (typicalHeight(group) * 1.25f).toInt()
        val tops = group.map { it.boundingBox.top }.sorted()
        if (tops.size < 2) return fallback
        val gaps = tops.zipWithNext { a, b -> b - a }.filter { it > 0 }
        if (gaps.isEmpty()) return fallback
        return median(gaps).coerceAtLeast(typicalHeight(group))
    }

    /**
     * Text size that renders one line exactly as tall as the OCR box it replaces.
     *
     * The translation used a single 14sp for every block, but the original's own size varies —
     * a heading and body copy are different sizes, and body copy further down the screen is
     * often smaller still. A uniform size makes every box taller than the line it covers, and
     * in in-place mode those excesses accumulate into a large downward drift.
     *
     * Derived from the font's own metrics at a probe size rather than a guessed ratio, so it
     * holds for any typeface the user loads. [textSizeMultiplier] — the Text size preference —
     * is applied on top of the fitted value, so it still scales everything relative to the
     * original rather than replacing the fit.
     */
    private fun textSizeForBox(boxHeightPx: Int): Float {
        val probe = android.text.TextPaint().apply {
            typeface = resultTypeface()
            textSize = TEXT_PROBE_PX
        }
        val metrics = probe.fontMetrics
        val lineHeight = metrics.descent - metrics.ascent
        val density = resources.displayMetrics.scaledDensity
        if (lineHeight <= 0f) return 14f * density * textSizeMultiplier
        val fitted = TEXT_PROBE_PX * (boxHeightPx / lineHeight)
        return (fitted * textSizeMultiplier)
            .coerceIn(MIN_TEXT_SP * density, MAX_TEXT_SP * density)
    }

    /**
     * Box width: the original's width when the translation fits inside it, the translation's own
     * measured width when it doesn't — capped to the screen.
     *
     * Measuring the built view is what makes this exact: it accounts for the chosen typeface,
     * the text-size multiplier and the horizontal padding in one step, where the previous fixed
     * 2.0x / 1.1x multipliers of the original width were a guess that ran short on long
     * translations and left a wide empty box on short ones.
     */
    private fun measuredBoxWidth(
        view: View, originalWidth: Int, screenW: Int, textSizePx: Float
    ): Int {
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        view.measure(unspecified, unspecified)
        // A few extra characters: the original's own glyphs can overhang its reported box, and a
        // box sized exactly to the text leaves that last stroke uncovered. For CJK one character
        // is one em, i.e. the text size.
        val slack = (textSizePx * WIDTH_SLACK_CHARS).toInt()
        return (maxOf(originalWidth, view.measuredWidth) + slack).coerceAtMost(screenW)
    }

    /** Height the box needs once its width is fixed and the text has wrapped. */
    private fun measuredBoxHeight(view: View, width: Int): Int {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        return view.measuredHeight
    }

    /** 模式A：单个 block 独立框。 */
    private fun buildSeparateBox(
        t: TranslationService.TranslatedBlock, pitch: Int, screenW: Int,
        loc: IntArray, prefs: PreferencesManager
    ): PlannedBox {
        val rect = t.boundingBox
        val bg = (if (t.bgColor != 0) t.bgColor else prefs.translationBgColor) or 0xFF000000.toInt()
        // Fit the text to most of the pitch, leaving a slice for padding. The Text size preference
        // multiplies that, so it can push the text past what was reserved.
        val fitted = textSizeForBox((pitch * TEXT_SHARE).toInt())
        val padH = dp(4)
        val tv = TextView(this).apply {
            text = t.translatedText
            setTextColor(prefs.translationTextColor)
            typeface = resultTypeface()
            setTextSize(TypedValue.COMPLEX_UNIT_PX, fitted)
            // textSizeForBox fits ascent..descent; TextView lays out on the larger top..bottom
            // metrics unless this is off, which made every line ~30% taller than the one it
            // covers. Over a dozen lines that excess is what accumulated into the drift.
            includeFontPadding = false
            setPadding(padH, 0, padH, 0)
            background = inPlaceBoxBackground(bg)
            elevation = dp(3).toFloat()
            gravity = Gravity.CENTER_VERTICAL
        }

        // Padding is whatever the pitch has left over once the text has taken its share, so the
        // box lands on exactly one pitch. Letting padding be a fixed fraction instead meant a
        // raised Text size pushed the box past the pitch, and an over-tall box doesn't merely
        // drift — it covers the whole line below it, which is how a class line ends up with no
        // translation showing at all.
        val singleLineH = measuredBoxHeight(tv, Int.MAX_VALUE / 2)
        val padV = ((pitch - singleLineH) / 2).coerceAtLeast(0)
        tv.setPadding(padH, padV, padH, padV)
        tv.minHeight = pitch

        val boxWidth = measuredBoxWidth(tv, rect.width(), screenW, fitted)
        // One pitch unless the translation genuinely wrapped, which legitimately needs more.
        val boxHeight = maxOf(pitch, measuredBoxHeight(tv, boxWidth))
        // Offset by the padding so the *text* lands on the original, not the box's edge.
        val left = (rect.left - loc[0] - padH).coerceIn(0, (screenW - boxWidth).coerceAtLeast(0))
        val top = (rect.top - loc[1] - padV).coerceAtLeast(0)
        return PlannedBox(tv, left, top, boxWidth, boxHeight, padV)
    }

    /** 模式B：一组重叠 block 合一个框，单背景，组内各行透明叠加（保留注音）。 */
    private fun buildMergedBox(
        group: List<TranslationService.TranslatedBlock>, pitch: Int, screenW: Int,
        loc: IntArray, prefs: PreferencesManager
    ): PlannedBox {
        val union = Rect(group.first().boundingBox)
        group.forEach { union.union(it.boundingBox) }
        val bg = (group.firstOrNull { it.bgColor != 0 }?.bgColor
            ?: prefs.translationBgColor) or 0xFF000000.toInt()
        val fitted = textSizeForBox((pitch * TEXT_SHARE).toInt())
        val padV = ((pitch - fitted).toInt() / 2).coerceAtLeast(0)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), padV, dp(4), padV)
            background = inPlaceBoxBackground(bg)
            elevation = dp(3).toFloat()
        }
        for (t in group.sortedBy { it.boundingBox.top }) {
            container.addView(TextView(this).apply {
                text = t.translatedText
                setTextColor(prefs.translationTextColor)
                typeface = resultTypeface()
                setTextSize(TypedValue.COMPLEX_UNIT_PX, fitted)
                includeFontPadding = false
                gravity = Gravity.CENTER_VERTICAL
            })
        }

        val boxWidth = measuredBoxWidth(container, union.width(), screenW, fitted)
        val boxHeight = measuredBoxHeight(container, boxWidth)
        // Offset by the padding so the *text* lands on the original, not the box's edge.
        val left = (union.left - loc[0] - dp(4)).coerceIn(0, (screenW - boxWidth).coerceAtLeast(0))
        val top = (union.top - loc[1] - padV).coerceAtLeast(0)
        return PlannedBox(container, left, top, boxWidth, boxHeight, padV)
    }

    /**
     * Adds the boxes top-down, pushing any that would land on an already-placed one below it.
     *
     * A translation is routinely taller than the line it replaces — it wraps where the original
     * didn't — so anchoring every box to its source rect stacks them on top of each other. Since
     * in-place mode is meant to cover the original text, keeping a box's left edge and sliding it
     * down is the least disruptive way out.
     */
    private class Placed(val rect: Rect, val padV: Int)

    private fun placeWithoutOverlap(boxes: List<PlannedBox>) {
        val ordered = boxes.sortedWith(compareBy({ it.top }, { it.left }))
        val placed = mutableListOf<Placed>()
        val clearance = dp(2)

        // Pass 1 — resolve collisions, but only the ones that actually matter.
        //
        // A box's bottom padding is empty background, so the next box covering *that* hides
        // nothing. Only an overlap deep enough to come within `clearance` of the text below needs
        // a shove, and then only far enough to sit `clearance` under that text — not under the
        // whole box. Pushing to the box's edge instead counts the padding twice, and that
        // double-count is what accumulated into a large drift down the screen.
        //
        // Left edges are preserved throughout: in-place mode is about covering the original, and
        // moving sideways would break the correspondence.
        for (box in ordered) {
            val rect = Rect(box.left, box.top, box.left + box.width, box.top + box.height)
            var moved = true
            var guard = 0
            while (moved && guard++ < 32) {
                moved = false
                for (other in placed) {
                    if (!Rect.intersects(rect, other.rect)) continue
                    val overlap = other.rect.bottom - rect.top
                    if (overlap <= 0) continue
                    // How much of the box above we're allowed to cover before reaching its text.
                    val harmless = other.padV - clearance
                    if (overlap > harmless) {
                        rect.offsetTo(rect.left, other.rect.bottom - other.padV + clearance)
                        moved = true
                    }
                }
            }
            placed += Placed(rect, box.padV)
        }

        // Pass 2 — close the leading. OCR boxes hug their glyphs, so the space *between* the
        // original's lines belongs to no box and stays visible as a stripe of untranslated text
        // between two covers. Stretch each box down to meet the next one below it, but only
        // across a gap small enough to be line spacing rather than a genuine paragraph break.
        for (i in placed.indices) {
            val rect = placed[i].rect
            val next = placed.filterIndexed { j, other ->
                j != i && other.rect.top >= rect.bottom &&
                        minOf(rect.right, other.rect.right) > maxOf(rect.left, other.rect.left)
            }.minByOrNull { it.rect.top } ?: continue
            val gap = next.rect.top - rect.bottom
            // Up to three quarters of a line is spacing; beyond that it's a blank line or a
            // paragraph break, and stretching across it would paint over untranslated content.
            val fillable = (rect.height() * 3 / 4).coerceAtLeast(dp(8))
            if (gap in 1..fillable) rect.bottom = next.rect.top
        }

        for ((box, slot) in ordered.zip(placed)) {
            val rect = slot.rect
            inPlaceOverlay.addView(box.view, FrameLayout.LayoutParams(rect.width(), rect.height()).apply {
                leftMargin = rect.left
                topMargin = rect.top
            })
        }
    }

    private fun updateOverlays(translations: List<TranslationService.TranslatedBlock>) {
        if (isPaused) return
        if (::translationOverlay.isInitialized) translationOverlay.alpha = 1f
        if (::inPlaceOverlay.isInitialized)   inPlaceOverlay.alpha = 1f
        PerfTrace.displayed()
        if (PreferencesManager.getInstance(this).inPlaceMode) {
            translationOverlay.visibility = View.GONE
            renderInPlace(translations)
            return
        }
        if (::inPlaceOverlay.isInitialized) inPlaceOverlay.visibility = View.GONE
        val rotation = getCurrentRotation()
        Log.d(TAG, "Updating overlays with ${translations.size} translations, rotation: $rotation")

        // Remove all previous views from the translation overlay container
        translationOverlay.removeAllViews()
        translatedViews.clear() // Clear the map as we no longer store individual views

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
            val mergedTextView = buildResultCard(combinedText)

            // Add the merged result card to the translation overlay container
            translationOverlay.addView(mergedTextView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, // card size based on content
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))

            // Store the single view if needed, but clearing translatedViews might be sufficient
            // translatedViews[0] = mergedTextView // Example if you need to reference it later

            translationOverlay.visibility = View.VISIBLE // Show the overlay container
        } else {
            // No translations, hide the overlay container
            translationOverlay.visibility = View.GONE
        }

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
            // Drop the data too, otherwise a later replay puts the boxes straight back.
            translationData.postValue(emptyList())
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

    /**
     * In-place box background (design 3e): a hairline outline plus a real drop shadow, so a
     * translated line stays legible over busy art instead of blending into it. The previous flat
     * rounded rect disappeared against light or noisy backgrounds.
     */
    private fun inPlaceBoxBackground(color: Int): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(7).toFloat()
            setColor(color)
            setStroke(dp(1), Color.parseColor("#29FFFFFF"))
        }

    /**
     * The merged-mode result (design 3d): a glass card with a `JA → EN` header, a drag handle and
     * a copy affordance, rather than a bare dark rectangle. The header is what the old box left
     * you guessing about — which direction the text was translated in.
     */
    private fun buildResultCard(body: String): View {
        val prefs = PreferencesManager.getInstance(this)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = buildResultBackground()
            elevation = dp(8).toFloat()
            // The container's drag listener moves the overlay, so don't swallow touches.
            isClickable = false
            isLongClickable = false
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(9), dp(12), dp(8))
        }
        header.addView(View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(2).toFloat()
                setColor(Color.parseColor("#47FFFFFF"))
            }
        }, LinearLayout.LayoutParams(dp(26), dp(4)))
        header.addView(TextView(this).apply {
            text = languagePairLabel(prefs)
            setTextColor(Color.parseColor("#80FFFFFF"))
            textSize = 11f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(8), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_copy)
            imageTintList = android.content.res.ColorStateList.valueOf(
                Color.parseColor("#8CFFFFFF")
            )
            setOnClickListener { copyTranslationToClipboard(body) }
        }, LinearLayout.LayoutParams(dp(18), dp(18)))

        card.addView(header, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        card.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#1AFFFFFF"))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
        card.addView(TextView(this).apply {
            text = body
            setTextColor(prefs.translationTextColor)
            typeface = resultTypeface()
            textSize = 14f * textSizeMultiplier
            setPadding(dp(14), dp(12), dp(14), dp(14))
            isClickable = false
            isLongClickable = false
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        return card
    }

    /** e.g. "JA → EN"; falls back to "AUTO" for a source the user left on auto-detect. */
    private fun languagePairLabel(prefs: PreferencesManager): String {
        val source = prefs.sourceLanguage.uppercase()
        val target = prefs.targetLanguage.uppercase()
        return "$source \u2192 $target"
    }

    // Rounded background for the translation result, using the configured colour + opacity
    private fun buildResultBackground(): android.graphics.drawable.GradientDrawable {
        val base = PreferencesManager.getInstance(this).translationBgColor
        val alpha = (255 * overlayOpacity).toInt()
        val color = (base and 0x00FFFFFF) or (alpha shl 24)
        return android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(color)
            setStroke(dp(1), Color.parseColor("#24FFFFFF"))
        }
    }

    fun updateSettings(textSize: Float, opacity: Float, alternativeStyle: Boolean) {
        Log.d(TAG, "Updating settings: textSize=$textSize, opacity=$opacity, alternativeStyle=$alternativeStyle")
        textSizeMultiplier = textSize
        overlayOpacity = opacity
        useAlternativeStyle = alternativeStyle
        val prefs = PreferencesManager.getInstance(this)
        spinnerOverlay.alpha = prefs.spinnerAlpha
        spinnerParams.width = dp(prefs.spinnerSizeDp)
        spinnerParams.height = dp(prefs.spinnerSizeDp)
        if (::spinnerOverlay.isInitialized) windowManager.updateViewLayout(spinnerOverlay, spinnerParams)
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

    private fun createSpinnerOverlay() {
        val prefs = PreferencesManager.getInstance(this)
        val size = dp(prefs.spinnerSizeDp)
        spinnerOverlay = ImageView(this).apply {
            setImageResource(R.drawable.loading_anim)
            scaleType = ImageView.ScaleType.FIT_CENTER      // 保持帧的宽高比
            alpha = prefs.spinnerAlpha
            visibility = View.GONE
        }
        val dm = resources.displayMetrics
        spinnerParams = WindowManager.LayoutParams(
            size, size, getOverlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (prefs.spinnerX >= 0) prefs.spinnerX else dm.widthPixels  - size - dp(16)
            y = if (prefs.spinnerY >= 0) prefs.spinnerY else dm.heightPixels - size - dp(120)
        }
        setupSpinnerDrag()
        try { windowManager.addView(spinnerOverlay, spinnerParams) } catch (e: Exception) { Log.e(TAG, "spinner add failed", e) }
    }

    private fun setupSpinnerDrag() {
        var downX = 0; var downY = 0; var touchX = 0f; var touchY = 0f
        spinnerOverlay.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = spinnerParams.x; downY = spinnerParams.y; touchX = e.rawX; touchY = e.rawY; true }
                MotionEvent.ACTION_MOVE -> {
                    spinnerParams.x = (downX + (e.rawX - touchX)).toInt()
                    spinnerParams.y = (downY + (e.rawY - touchY)).toInt()
                    try { windowManager.updateViewLayout(spinnerOverlay, spinnerParams) } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val p = PreferencesManager.getInstance(this); p.spinnerX = spinnerParams.x; p.spinnerY = spinnerParams.y; true
                }
                else -> false
            }
        }
    }

    private fun showSpinnerImpl() = mainHandler.post {
        if (!::spinnerOverlay.isInitialized) return@post
        if (!PreferencesManager.getInstance(this).spinnerEnabled) return@post

        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(dm)
        val size = spinnerParams.width
        spinnerParams.x = spinnerParams.x.coerceIn(0, (dm.widthPixels  - size).coerceAtLeast(0))
        spinnerParams.y = spinnerParams.y.coerceIn(0, (dm.heightPixels - size).coerceAtLeast(0))
        try { windowManager.updateViewLayout(spinnerOverlay, spinnerParams) } catch (_: Exception) {}

        spinnerOverlay.visibility = View.VISIBLE
        (spinnerOverlay.drawable as? AnimationDrawable)?.start()
    }
    private fun hideSpinnerImpl() = mainHandler.post {
        if (!::spinnerOverlay.isInitialized) return@post
        (spinnerOverlay.drawable as? AnimationDrawable)?.stop()
        spinnerOverlay.visibility = View.GONE
    }

    override fun onDestroy() {
        undockControlPanel()
        Log.d(TAG, "Service onDestroy")
        instance = null
        super.onDestroy()

        // Clean up
        try {
            unregisterReceiver(settingsReceiver)
            unregisterReceiver(rotationReceiver) // Unregister rotation receiver
            areaSelectionView?.let { windowManager.removeView(it) }
            windowManager.removeView(translationOverlay)
            if (::inPlaceOverlay.isInitialized) windowManager.removeView(inPlaceOverlay)
            touchWatchView?.let { windowManager.removeView(it) }
            windowManager.removeView(controlPanel)
            translationData.removeObserver(translationObserver)
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

        if (::spinnerOverlay.isInitialized) windowManager.removeView(spinnerOverlay)

        translationData.value = emptyList()
    }
}