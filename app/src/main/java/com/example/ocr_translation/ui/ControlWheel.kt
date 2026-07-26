package com.example.ocr_translation.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.example.ocr_translation.R
import kotlin.math.abs

/**
 * The floating control bar, as design 5a: a chrome-less strip where only one action is ever
 * armed. The armed glyph sits at 38dp and full opacity with its neighbours peeking at 20dp /
 * 30%, so the armed one is unmistakable, so it is always clear which way the next action lives. Drag along the strip to bring
 * another action into focus — the contents follow your finger — then tap the centre to fire it.
 *
 * Why this shape rather than the previous five always-live buttons: the old bar permanently
 * occupied five buttons' worth of the game. The trade-off, which the design calls out, is that
 * firing an action becomes scroll-then-tap instead of one tap — so the order matters. Translate
 * sits next to Auto because those two are the frequent pair, and destructive Close is at the far
 * end.
 *
 * Gestures are split by *time*, not axis, because people park this bar vertically as often as
 * horizontally:
 *   - move straight away  -> cycles the focused action
 *   - hold [LONG_PRESS_MS] first -> the strip lifts and follows your finger
 * Splitting by axis instead would make "drag to move" unreachable in one of the two orientations.
 */
class ControlWheel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    /** The five actions of the old bar, in the order the wheel cycles them. Wraps at both ends. */
    enum class Action(val iconRes: Int, val labelRes: Int) {
        AUTO(R.drawable.ic_start, R.string.auto_mode),
        TRANSLATE(R.drawable.ic_translate, R.string.translate_now),
        SELECT_AREA(R.drawable.ic_crop, R.string.select_area),
        // Was FOLD; the wheel now folds itself after an idle timeout, so the manual action is
        // the one thing that couldn't be automatic — parking against the screen edge (design 4a).
        DOCK(R.drawable.ic_dock_edge, R.string.control_panel_dock),
        CLOSE(R.drawable.ic_close, R.string.close_translation)
    }

    /** Fired when the centre is tapped. */
    var onFire: ((Action) -> Unit)? = null

    /** Long-press drag: cumulative delta from where the press landed. */
    var onMoveStart: (() -> Unit)? = null
    var onMove: ((dx: Float, dy: Float) -> Unit)? = null
    var onMoveEnd: (() -> Unit)? = null

    /** Reports a new armed action so the caller can persist it. */
    var onFocusChanged: ((Action) -> Unit)? = null

    /** Names the armed action while the user is cycling; hidden the rest of the time. */
    var onLabel: ((CharSequence?) -> Unit)? = null


    private val prevGhost = ImageView(context)
    private val nextGhost = ImageView(context)
    private val focusIcon = ImageView(context)
    private val focusSlot = FrameLayout(context)
    private val leadChevron = ImageView(context)
    private val trailChevron = ImageView(context)

    private val handler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var focusIndex = 0
    private var folded = false
    private var autoRunning = false
    private var panelColor = Color.TRANSPARENT
    private var lifted = false

    // Gesture state
    private var downX = 0f
    private var downY = 0f
    private var cycling = false
    private var moving = false
    private var cycleAnchor = 0f
    private val longPress = Runnable { beginMove() }
    private val autoFold = Runnable { if (!folded) setFolded(true) }

    init {
        gravity = Gravity.CENTER
        clipChildren = false
        clipToPadding = false
        isClickable = true

        addView(leadChevron, chevronParams())
        addView(prevGhost, ghostParams())
        addView(focusSlot, focusParams())
        addView(nextGhost, ghostParams())
        addView(trailChevron, chevronParams())

        focusSlot.addView(
            focusIcon,
            FrameLayout.LayoutParams(dp(38), dp(38)).apply { gravity = Gravity.CENTER }
        )
        listOf(prevGhost, nextGhost).forEach { it.alpha = GHOST_ALPHA }
        leadChevron.imageTintList = android.content.res.ColorStateList.valueOf(CHEVRON_TINT)
        trailChevron.imageTintList = android.content.res.ColorStateList.valueOf(CHEVRON_TINT)

        setWheelOrientation(VERTICAL)
        refresh()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        armAutoFold()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacks(autoFold)
        handler.removeCallbacks(longPress)
    }

    /**
     * Follows the existing `controlPanelOrientation` preference. The cycle gesture uses whichever
     * axis the strip runs along, so the setting keeps meaning what it always did.
     */
    fun setWheelOrientation(newOrientation: Int) {
        orientation = newOrientation
        leadChevron.setImageResource(
            if (newOrientation == VERTICAL) R.drawable.ic_wheel_caret_up
            else R.drawable.ic_wheel_caret_left
        )
        trailChevron.setImageResource(
            if (newOrientation == VERTICAL) R.drawable.ic_wheel_caret_down
            else R.drawable.ic_wheel_caret_right
        )
        // Re-issue params so margins fall on the correct axis.
        (0 until childCount).forEach { i ->
            val child = getChildAt(i)
            child.layoutParams = when (child) {
                leadChevron, trailChevron -> chevronParams()
                focusSlot -> focusParams()
                else -> ghostParams()
            }
        }
        applyPadding()
        requestLayout()
    }

    var action: Action
        get() = Action.entries[focusIndex]
        set(value) {
            focusIndex = value.ordinal
            refresh()
        }

    /** Auto-scan state. The focus slot fills with the accent, so the wheel is its own indicator. */
    fun setAutoRunning(running: Boolean) {
        autoRunning = running
        refresh()
    }

    /**
     * Restores the `Panel background` / `Panel opacity` preferences, as a capsule behind the whole
     * strip rather than behind the focused glyph alone — over a white scene it's all three icons
     * that stop being readable, not just the centre one. Pass a fully transparent colour to get
     * the chrome-less look back.
     *
     * @param color background colour with the opacity preference already folded into its alpha.
     */
    fun setPanelBacking(color: Int) {
        panelColor = color
        applyBacking()
    }

    /**
     * Rounded rectangle rather than a capsule, and no elevation — a shadow draws outside the
     * view's bounds, which the WRAP_CONTENT overlay window clips away entirely.
     *
     * While the strip is being dragged it gains an accent rim. That replaces scaling it up:
     * the window wraps the view exactly, so any scale grew the background past the window and
     * the rounded corners came back clipped.
     */
    private fun applyBacking() {
        val visible = Color.alpha(panelColor) != 0
        background = if (!visible && !lifted) {
            null
        } else {
            GradientDrawable().apply {
                cornerRadius = dp(CORNER_RADIUS_DP).toFloat()
                setColor(if (visible) panelColor else Color.TRANSPARENT)
                if (lifted) {
                    setStroke(dp(2), AppTheme.colorPrimary(context))
                } else {
                    setStroke(dp(1), borderFor(panelColor))
                }
            }
        }
    }

    /** Folded: just the armed icon, no ghosts and no chevrons. */
    fun setFolded(value: Boolean) {
        folded = value
        refresh()
        if (value) handler.removeCallbacks(autoFold) else armAutoFold()
    }

    /**
     * Collapses to the single armed glyph after [AUTO_FOLD_MS] without a touch, so the bar stops
     * occupying the game once you've stopped using it. Any touch re-arms the timer; cycling or
     * tapping unfolds it again.
     */
    private fun armAutoFold() {
        handler.removeCallbacks(autoFold)
        if (!folded) handler.postDelayed(autoFold, AUTO_FOLD_MS)
    }

    fun isFolded() = folded

    /**
     * @param slideSign the direction the user dragged, or 0 for a silent state change. A cycle
     *   animates: the three glyphs enter from the side they conceptually came from and settle,
     *   so the strip reads as having scrolled by one notch rather than the icons simply swapping.
     */
    private fun refresh(slideSign: Int = 0) {
        val current = Action.entries[focusIndex]
        focusIcon.setImageDrawable(
            shadowed(
                // Auto is a toggle, so its glyph reports the state rather than the action.
                if (current == Action.AUTO && autoRunning) R.drawable.ic_pause else current.iconRes
            )
        )
        prevGhost.setImageDrawable(shadowed(Action.entries[wrap(focusIndex - 1)].iconRes))
        nextGhost.setImageDrawable(shadowed(Action.entries[wrap(focusIndex + 1)].iconRes))

        focusSlot.background = if (autoRunning) {
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(AppTheme.colorPrimary(context))
            }
        } else {
            null
        }

        val peripheral = if (folded) View.GONE else View.VISIBLE
        prevGhost.visibility = peripheral
        nextGhost.visibility = peripheral
        leadChevron.visibility = peripheral
        trailChevron.visibility = peripheral

        applyPadding()
        if (slideSign != 0) animateSlide(slideSign)
    }

    /**
     * The backing capsule fills the view including its padding, so padding is what decides how
     * much empty surface sits around the glyphs.
     *
     * Folded, it tightens to a ring around the single armed glyph — a circle, not a tall pill.
     * Unfolded it only needs enough slack along the strip for a sliding glyph to travel through
     * without the WRAP_CONTENT overlay window clipping it.
     */
    private fun applyPadding() {
        if (folded) {
            val p = dp(5)
            setPadding(p, p, p, p)
            return
        }
        val along = dp(8)
        val across = dp(2)
        if (orientation == VERTICAL) setPadding(across, along, across, along)
        else setPadding(along, across, along, across)
    }

    /**
     * Border colour for the backing capsule: light on a dark panel, dark on a light one.
     *
     * A fixed white hairline vanishes the moment the user picks a white panel background, which
     * is exactly when an edge is most needed — so the contrast is taken from the panel colour
     * itself rather than assumed.
     */
    private fun borderFor(panelColor: Int): Int {
        val opaque = panelColor or 0xFF000000.toInt()
        return (AppTheme.contrastOn(opaque) and 0x00FFFFFF) or (0x59 shl 24)
    }

    /**
     * Glyphs start one notch back along the drag direction and travel to their resting place, so
     * the wheel appears to scroll. The new focus also fades and scales up from slightly small,
     * which is what stops a cycle reading as an abrupt swap.
     */
    private fun animateSlide(slideSign: Int) {
        // Dragging down moves the contents down, so they enter from above: negative offset.
        val offset = -notchPx() * 0.35f * slideSign
        listOf(prevGhost, focusIcon, nextGhost).forEach { view ->
            view.animate().cancel()
            if (orientation == VERTICAL) {
                view.translationY = offset
                view.translationX = 0f
                view.animate().translationY(0f)
            } else {
                view.translationX = offset
                view.translationY = 0f
                view.animate().translationX(0f)
            }
            view.animate()
                .setDuration(SLIDE_MS)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f))
                .start()
        }

        focusIcon.alpha = 0.4f
        focusIcon.scaleX = 0.82f
        focusIcon.scaleY = 0.82f
        focusIcon.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(SLIDE_MS)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.1f))
            .start()
    }

    /**
     * A white glyph with a dark copy offset behind it.
     *
     * The design asks for `drop-shadow(0 2px 6px rgba(0,0,0,.85))`, but [View.setElevation] draws
     * nothing here — a shadow needs an opaque background to cast from, and the whole point of the
     * wheel is that it paints no panel over the game. Stacking an offset dark copy gives the same
     * separation and works on every API level. Without it the glyphs vanish against light art.
     */
    private fun shadowed(iconRes: Int): android.graphics.drawable.Drawable {
        val shadow = ContextCompat.getDrawable(context, iconRes)!!.mutate().apply {
            setTint(SHADOW_TINT)
        }
        val glyph = ContextCompat.getDrawable(context, iconRes)!!.mutate().apply {
            setTint(Color.WHITE)
        }
        // Both layers are the same size; only the shadow is displaced. Insetting the *glyph*
        // instead — which is what this did before — shifts the visible icon up and left, and it
        // reads as the whole wheel being off-centre.
        val offset = (1.5f * resources.displayMetrics.density).toInt()
        return android.graphics.drawable.LayerDrawable(arrayOf(shadow, glyph)).apply {
            setLayerInset(0, offset * 2, offset * 2, 0, 0)
            setLayerInset(1, offset, offset, offset, offset)
        }
    }

    private fun wrap(i: Int) = ((i % Action.entries.size) + Action.entries.size) % Action.entries.size

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                cycleAnchor = along(event)
                cycling = false
                moving = false
                handler.removeCallbacks(autoFold)
                handler.postDelayed(longPress, LONG_PRESS_MS)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (moving) {
                    onMove?.invoke(event.rawX - downX, event.rawY - downY)
                    return true
                }
                val travelled = abs(along(event) - cycleAnchor)
                if (!cycling && travelled > touchSlop) {
                    // Moved before the long-press fired, so this is a cycle, not a move.
                    handler.removeCallbacks(longPress)
                    cycling = true
                    if (folded) setFolded(false)
                }
                if (cycling) {
                    val steps = ((along(event) - cycleAnchor) / notchPx()).toInt()
                    if (steps != 0) {
                        // The contents follow the finger: dragging *down* carries the icon that
                        // was above into the centre, so the index moves against the drag.
                        focusIndex = wrap(focusIndex - steps)
                        cycleAnchor += steps * notchPx()
                        refresh(slideSign = if (steps > 0) 1 else -1)
                        onFocusChanged?.invoke(action)
                        onLabel?.invoke(context.getString(action.labelRes))
                    }
                    return true
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPress)
                armAutoFold()
                when {
                    moving -> {
                        moving = false
                        setLifted(false)
                        onMoveEnd?.invoke()
                    }
                    cycling -> hideLabelSoon()
                    // A press that neither cycled nor became a move is a tap: fire the centre.
                    else -> onFire?.invoke(action)
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPress)
                armAutoFold()
                if (moving) {
                    moving = false
                    setLifted(false)
                    onMoveEnd?.invoke()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun beginMove() {
        if (cycling) return
        moving = true
        setLifted(true)
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        onMoveStart?.invoke()
    }

    private fun setLifted(value: Boolean) {
        if (lifted == value) return
        lifted = value
        applyBacking()
    }

    private fun hideLabelSoon() {
        handler.postDelayed({ onLabel?.invoke(null) }, LABEL_LINGER_MS)
    }

    /** Position along the strip's own axis. */
    private fun along(event: MotionEvent) =
        if (orientation == VERTICAL) event.rawY else event.rawX

    /** One notch of travel per neighbouring action. */
    private fun notchPx() = dp(40).toFloat()

    private fun chevronParams() = LayoutParams(dp(12), dp(12))

    private fun ghostParams() = LayoutParams(dp(20), dp(20)).apply {
        if (orientation == VERTICAL) {
            topMargin = dp(2)
            bottomMargin = dp(2)
        } else {
            marginStart = dp(2)
            marginEnd = dp(2)
        }
    }

    private fun focusParams() = LayoutParams(dp(52), dp(52))

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        /** Below this the gesture is a cycle; past it the strip lifts and follows the finger. */
        const val LONG_PRESS_MS = 350L
        const val LABEL_LINGER_MS = 900L
        const val SLIDE_MS = 190L
        /** Idle time before the wheel collapses to its armed glyph. */
        const val AUTO_FOLD_MS = 5_000L
        /** Rounded-rectangle corner; large enough to read as soft, small enough not to be a pill. */
        const val CORNER_RADIUS_DP = 18
        const val GHOST_ALPHA = 0.3f
        val SHADOW_TINT = Color.parseColor("#D9000000")
        val CHEVRON_TINT = Color.parseColor("#38FFFFFF")
    }
}
