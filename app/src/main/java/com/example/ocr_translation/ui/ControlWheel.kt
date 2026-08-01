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

    /**
     * The actions, in the order the wheel cycles them. Wraps at both ends.
     *
     * Order is the whole ergonomics of this control, since reaching an action means dragging past
     * the ones before it. The pair you use constantly — Auto and Translate — is at the near end,
     * and destructive Close is at the far one. New actions go in front of Close, which shifts its
     * ordinal; `wheelFocus` persists ordinals, so someone who had Close armed comes back with
     * whatever took its number. Harmless in that direction, which is the reason for the rule.
     */
    enum class Action(val iconRes: Int, val labelRes: Int) {
        AUTO(R.drawable.ic_start, R.string.auto_mode),
        TRANSLATE(R.drawable.ic_translate, R.string.translate_now),
        // Straight after Translate, because the size you want is judged against a translation you
        // just triggered — and next to each other, because overshooting by one is the normal way
        // to find it, so the correction should be one notch back rather than a lap of the wheel.
        TEXT_LARGER(R.drawable.ic_text_larger, R.string.text_size_larger),
        TEXT_SMALLER(R.drawable.ic_text_smaller, R.string.text_size_smaller),
        SELECT_AREA(R.drawable.ic_crop, R.string.select_area),
        // Was FOLD; the wheel now folds itself after an idle timeout, so the manual action is
        // the one thing that couldn't be automatic — parking against the screen edge (design 4a).
        DOCK(R.drawable.ic_dock_edge, R.string.control_panel_dock),
        MERGE_COVERS(R.drawable.ic_merge_covers, R.string.merge_covers),
        COPY(R.drawable.ic_copy, R.string.copy_round),
        OPEN_APP(R.drawable.ic_open_app, R.string.open_app),
        CLOSE(R.drawable.ic_close, R.string.close_translation)
    }

    /** Fired when the centre is tapped. */
    var onFire: ((Action) -> Unit)? = null

    /**
     * Fired the moment a touch lands on the strip, before it is known what kind of gesture it is.
     *
     * The owner watches for touches outside its own windows to tell when the user has moved the
     * game on, and this is how it learns that this one was aimed at the bar instead.
     */
    var onTouched: (() -> Unit)? = null

    /** Long-press drag: cumulative delta from where the press landed. */
    var onMoveStart: (() -> Unit)? = null
    var onMove: ((dx: Float, dy: Float) -> Unit)? = null
    var onMoveEnd: (() -> Unit)? = null

    /** Reports a new armed action so the caller can persist it. */
    var onFocusChanged: ((Action) -> Unit)? = null

    /** Names the armed action while the user is cycling; hidden the rest of the time. */
    var onLabel: ((CharSequence?) -> Unit)? = null

    /**
     * Fired when the strip folds or unfolds. The window wraps this view exactly, so collapsing it
     * changes the window's size — and since the window is anchored by an edge, not by its middle,
     * the armed glyph would slide towards that edge. The owner uses this to put it back.
     */
    var onFoldChanged: ((folded: Boolean) -> Unit)? = null


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
    private var mergeCovers = false
    private var panelColor = Color.TRANSPARENT
    private var lifted = false
    private var sizeScale = 1f

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
            FrameLayout.LayoutParams(sdp(38), sdp(38)).apply { gravity = Gravity.CENTER }
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

    /**
     * Scales every dimension of the strip together — glyphs, slot, chevrons, padding and the
     * travel per notch — so the whole bar grows or shrinks as one object rather than the icons
     * rattling around inside a fixed frame.
     */
    fun setSizeScale(scale: Float) {
        val clamped = scale.coerceIn(0.6f, 1.8f)
        if (clamped == sizeScale) return
        sizeScale = clamped
        setWheelOrientation(orientation)   // re-issues every child's params at the new scale
        applyPadding()
        refresh()
    }

    /** Auto-scan state. The focus slot fills with the accent, so the wheel is its own indicator. */
    fun setAutoRunning(running: Boolean) {
        autoRunning = running
        refresh()
    }

    /**
     * Merged-covers state, so the glyph can show what a tap would do rather than a fixed icon.
     * Kept in sync from the service, which owns the preference — Settings can change it too.
     */
    fun setMergeCovers(on: Boolean) {
        mergeCovers = on
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
            glassPane(if (visible) panelColor else Color.TRANSPARENT).apply {
                cornerRadius = sdp(CORNER_RADIUS_DP).toFloat()
                if (lifted) {
                    setStroke(dp(2), AppTheme.colorPrimary(context))
                } else {
                    setStroke(dp(1), borderFor(panelColor))
                }
            }
        }
    }

    /**
     * The panel fill as a pane of frosted glass rather than a flat rectangle of colour.
     *
     * Painted, not sampled. Real frosted glass means blurring what is behind the window, and the
     * only API for that — `FLAG_BLUR_BEHIND` — blurs the whole window rectangle, which cannot be
     * clipped to these rounded corners; it also wants Android 12 and gets switched off globally by
     * battery saver. What actually sells glass at this size is the lighting, not the blur: a sheen
     * along the top edge fading out by a third of the way down, and a slightly denser foot. Those
     * follow the corner radius for free, cost nothing per frame, and look the same on every
     * device.
     *
     * Alpha is left as the user set it, since here it is still the only thing keeping the glyphs
     * legible over a bright scene.
     */
    private fun glassPane(color: Int): GradientDrawable {
        if (Color.alpha(color) == 0) return GradientDrawable().apply { setColor(color) }
        val sheen = shift(color, Color.WHITE, SHEEN_STRENGTH)
        val foot = shift(color, Color.BLACK, FOOT_STRENGTH)
        // Four stops rather than three: the repeated body colour holds the sheen to the top third,
        // where a light source would actually catch the edge. Spread evenly it reads as a gradient
        // fill instead.
        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(sheen, color, color, foot)
        )
    }

    /** Moves [base] towards [towards] by [amount], keeping its alpha. */
    private fun shift(base: Int, towards: Int, amount: Float): Int = Color.argb(
        Color.alpha(base),
        Color.red(base) + ((Color.red(towards) - Color.red(base)) * amount).toInt(),
        Color.green(base) + ((Color.green(towards) - Color.green(base)) * amount).toInt(),
        Color.blue(base) + ((Color.blue(towards) - Color.blue(base)) * amount).toInt()
    )

    /** Folded: just the armed icon, no ghosts and no chevrons. */
    fun setFolded(value: Boolean) {
        if (folded == value) return
        folded = value
        refresh()
        if (value) handler.removeCallbacks(autoFold) else armAutoFold()
        onFoldChanged?.invoke(value)
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
    /**
     * The glyph an action currently shows. The two toggles swap theirs for the opposite state's,
     * so what you see is what the tap does — the ghosts get the same treatment, since an icon that
     * changed as it slid into the centre would read as the wheel having moved somewhere else.
     */
    private fun glyphFor(action: Action): Int = when {
        action == Action.AUTO && autoRunning -> R.drawable.ic_pause
        action == Action.MERGE_COVERS && mergeCovers -> R.drawable.ic_split_covers
        else -> action.iconRes
    }

    private fun refresh(slideSign: Int = 0) {
        val current = Action.entries[focusIndex]
        focusIcon.setImageDrawable(shadowed(glyphFor(current)))
        prevGhost.setImageDrawable(shadowed(glyphFor(Action.entries[wrap(focusIndex - 1)])))
        nextGhost.setImageDrawable(shadowed(glyphFor(Action.entries[wrap(focusIndex + 1)])))

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
     *
     * The folded inset is the same one the strip already carried across its width, on all four
     * sides. A larger one made the bar *wider* as it collapsed: the strip's width is the focus slot
     * plus its across padding either way, so any folded inset above that adds to it — the fold read
     * as the bar growing rather than shrinking.
     */
    private fun applyPadding() {
        val along = sdp(PADDING_ALONG_DP)
        val across = sdp(PADDING_ACROSS_DP)
        if (folded) {
            setPadding(across, across, across, across)
            return
        }
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
                onTouched?.invoke()
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
    private fun notchPx() = sdp(40).toFloat()

    private fun chevronParams() = LayoutParams(sdp(12), sdp(12))

    private fun ghostParams() = LayoutParams(sdp(20), sdp(20)).apply {
        if (orientation == VERTICAL) {
            topMargin = sdp(2)
            bottomMargin = sdp(2)
        } else {
            marginStart = sdp(2)
            marginEnd = sdp(2)
        }
    }

    private fun focusParams() = LayoutParams(sdp(52), sdp(52))

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** dp scaled by the user's control-panel size preference. */
    private fun sdp(v: Int) = (v * sizeScale * resources.displayMetrics.density).toInt()

    private companion object {
        /** Below this the gesture is a cycle; past it the strip lifts and follows the finger. */
        const val LONG_PRESS_MS = 350L
        const val LABEL_LINGER_MS = 900L
        const val SLIDE_MS = 190L
        /** Idle time before the wheel collapses to its armed glyph. */
        const val AUTO_FOLD_MS = 5_000L
        /**
         * Inset around the glyphs, and so the size of the backing capsule — the background fills
         * the view including its padding, and the glyphs' own dimensions are fixed. `along` runs
         * with the strip and also has to leave a sliding glyph room to travel through without the
         * WRAP_CONTENT window clipping it; `across` sets the width (and, folded, all four sides).
         * Both are scaled by the size preference.
         */
        const val PADDING_ALONG_DP = 12
        const val PADDING_ACROSS_DP = 6

        /** Rounded-rectangle corner; large enough to read as soft, small enough not to be a pill. */
        const val CORNER_RADIUS_DP = 18
        /** How far the top edge is lifted towards white, and the foot pushed towards black. */
        const val SHEEN_STRENGTH = 0.28f
        const val FOOT_STRENGTH = 0.12f
        const val GHOST_ALPHA = 0.3f
        val SHADOW_TINT = Color.parseColor("#D9000000")
        val CHEVRON_TINT = Color.parseColor("#38FFFFFF")
    }
}
