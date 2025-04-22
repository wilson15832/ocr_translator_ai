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
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import android.view.Surface
import android.util.DisplayMetrics
import android.graphics.RectF
import android.graphics.Canvas
import android.graphics.Paint
import android.widget.ImageButton


class OverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var controlPanel: View
    private lateinit var translationOverlay: FrameLayout
    private val translatedViews = mutableMapOf<Int, View>()

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

    private var initialOverlayX: Int = 0
    private var initialOverlayY: Int = 0
    private var initialOverlayTouchX: Float = 0f
    private var initialOverlayTouchY: Float = 0f

    private var isControlPanelExpanded = false

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

        // Add a variable to store the last translation results
        private var lastTranslationResults: List<TranslationService.TranslatedBlock>? = null

        fun getTranslationData(): LiveData<List<TranslationService.TranslatedBlock>> = translationData

        // Modified to store the last results
        fun showTranslation(translations: List<TranslationService.TranslatedBlock>) {
            try {
                Log.d(TAG, "showTranslation called with ${translations.size} items.")
                if (translations.isNotEmpty()) {
                    Log.d(TAG, "First item original: ${translations[0].originalText}, translated: ${translations[0].translatedText}")
                }
                lastTranslationResults = translations
                translationData.postValue(translations)
            } catch (e: Exception) {
                Log.e(TAG, "Error showing translation, OverlayService may be destroyed", e)
            }
        }

        // Add a method to get the last translation results
        fun getLastTranslationResults(): List<TranslationService.TranslatedBlock>? {
            return lastTranslationResults
        }
    }

    private val settingsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.ocr_translation.ACTION_UPDATE_OVERLAY_SETTINGS") {
                val textSize = intent.getFloatExtra("textSize", 1.0f)
                val opacity = intent.getFloatExtra("opacity", 0.8f)
                val highlight = intent.getBooleanExtra("highlight", true)
                val alternativeStyle = intent.getBooleanExtra("alternativeStyle", false)

                updateSettings(textSize, opacity, highlight, alternativeStyle)
            }
        }
    }

    private lateinit var screenOrientationListener: OrientationEventListener

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")

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

        // Register rotation receiver
        val rotationFilter = IntentFilter("com.example.ocr_translation.ACTION_ROTATION_CHANGED")
        registerReceiver(rotationReceiver, rotationFilter, Context.RECEIVER_NOT_EXPORTED)

        // Register for settings updates
        val filter = IntentFilter("com.example.ocr_translation.ACTION_UPDATE_OVERLAY_SETTINGS")
        registerReceiver(settingsReceiver, filter, Context.RECEIVER_NOT_EXPORTED)

        areaIndicatorView = AreaIndicatorView(this)

        // Create separate windows for control panel and translations
        createControlPanelWindow()
        createTranslationOverlay()

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

            val controlParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                getOverlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,  // Keep this flag
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = 0
                y = 100
            }

            setupControlPanelButtons()
            setupControlPanelDrag(controlParams)

            windowManager.addView(controlPanel, controlParams)
            updateControlPanelState()
            Log.d(TAG, "Control panel added successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create control panel: ${e.message}", e)
            createFallbackControlPanel()
        }
    }

    private fun setupControlPanelButtons() {
        controlPanel.findViewById<ImageButton>(R.id.btnExpand).setOnClickListener {
            isControlPanelExpanded = !isControlPanelExpanded
            updateControlPanelState()
        }

        // Close button
        controlPanel.findViewById<ImageButton>(R.id.btnClose).setOnClickListener {
            Log.d(TAG, "Close button clicked")

            // Create an explicit intent to stop ScreenCaptureService
            val captureIntent = Intent(this, ScreenCaptureService::class.java)
            stopService(captureIntent)

            // Give a small delay before stopping self, to allow ScreenCaptureService to clean up
            Handler(Looper.getMainLooper()).postDelayed({
                stopSelf()
            }, 100)
        }

        // Settings button (only visible when expanded)
        controlPanel.findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            Log.d(TAG, "Settings button clicked")
            val intent = Intent(this, SettingsActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }

        // Pause button
        controlPanel.findViewById<View>(R.id.btnPause).setOnClickListener { view ->
            val isPaused = view.tag as? Boolean ?: false
            Log.d(TAG, "Pause button clicked, isPaused: $isPaused")

            if (isPaused) {
                // Resume translations
                translationOverlay.visibility = View.VISIBLE
                view.tag = false

                val pauseButton = view as ImageButton  // or Button, depending on your UI
                pauseButton.setImageResource(R.drawable.ic_pause)  // Set to pause icon

                // Send broadcast to resume screen capture
                val intent = Intent("com.example.ocr_translation.ACTION_TOGGLE_CAPTURE")
                intent.putExtra("paused", false)
                sendBroadcast(intent)
                // Update button icon to pause
            } else {
                // Pause translations
                translationOverlay.visibility = View.INVISIBLE
                view.tag = true

                // Update button icon to play
                val pauseButton = view as ImageButton  // or Button, depending on your UI
                pauseButton.setImageResource(R.drawable.ic_start)  // Set to play icon

                // Send broadcast to pause screen capture
                val intent = Intent("com.example.ocr_translation.ACTION_TOGGLE_CAPTURE")
                intent.putExtra("paused", true)
                sendBroadcast(intent)
                // Update button icon to play
            }
        }
    }

    private fun updateControlPanelState() {
        // Always visible buttons
        val btnPause = controlPanel.findViewById<ImageButton>(R.id.btnPause)
        val btnExpand = controlPanel.findViewById<ImageButton>(R.id.btnExpand)

        // Conditionally visible buttons
        val btnSettings = controlPanel.findViewById<ImageButton>(R.id.btnSettings)
        val btnClose = controlPanel.findViewById<ImageButton>(R.id.btnClose)

        if (isControlPanelExpanded) {
            // Show all buttons
            btnSettings.visibility = View.VISIBLE
            btnClose.visibility = View.VISIBLE

            // Change expand icon to collapse
            btnExpand.setImageResource(R.drawable.ic_collapse)
        } else {
            // Hide settings and close buttons
            btnSettings.visibility = View.GONE
            btnClose.visibility = View.GONE

            // Change collapse icon to expand
            btnExpand.setImageResource(R.drawable.ic_expand_more)
        }
    }

    private fun setupControlPanelDrag(params: WindowManager.LayoutParams) {
        var initialX: Int = 0
        var initialY: Int = 0
        var initialTouchX: Float = 0f
        var initialTouchY: Float = 0f

        controlPanel.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(controlPanel, params)
                    true
                }
                else -> false
            }
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

    private fun updateOverlays(translations: List<TranslationService.TranslatedBlock>) {
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
                    setTextColor(ContextCompat.getColor(context, R.color.translation_text_color))
                    setBackgroundColor(getBackgroundColorWithOpacity()) // Apply background with opacity
                    textSize = 14f * textSizeMultiplier // Apply text size setting
                    setPadding(16, 12, 16, 12) // Add some padding
                    // maxWidth = (screenWidth * 0.8).toInt() // Optional: limit width

                    // Styling (reusing your existing styling logic)
                    applyTextViewStyling(this)

                    // Make it respond to long clicks (if needed for combined text)
                    isClickable = false // Normal clicks don't select text for dragging
                    isLongClickable = true // Long clicks can still be used for context menu

                    setOnLongClickListener {
                            // TODO: Implement context menu for combined text if needed
                            // You might pass the combined text or the list of original blocks
                            // showContextMenuForCombinedText(combinedText, it)
                            Log.d(TAG, "Long clicked combined translation block")
                            true
                        }
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
        super.onDestroy()

        // Clean up
        try {
            unregisterReceiver(settingsReceiver)
            unregisterReceiver(rotationReceiver) // Unregister rotation receiver
            screenOrientationListener.disable()
            windowManager.removeView(translationOverlay)
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