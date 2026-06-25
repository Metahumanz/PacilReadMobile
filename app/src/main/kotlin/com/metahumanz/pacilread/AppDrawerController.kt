package com.metahumanz.pacilread

import android.app.Activity
import android.graphics.Rect
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import android.widget.TextView
import com.metahumanz.pacilread.theme.ThemeModeHelper
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

open class AppDrawerController(
    private val activity: Activity,
    private val rootView: View?,
    private val navigationListener: NavigationListener,
) {
    fun interface NavigationListener {
        fun onDrawerDestinationSelected(destination: Int)
    }

    private val drawerPanel: View? = activity.findViewById(R.id.drawer_panel)
    private val drawerScrim: View? = activity.findViewById(R.id.drawer_scrim)
    private val navBookshelf: View? = activity.findViewById(R.id.nav_bookshelf)
    private val navPreview: View? = activity.findViewById(R.id.nav_preview)
    private val navSettings: View? = activity.findViewById(R.id.nav_settings)
    private val navBookshelfText: TextView? = activity.findViewById(R.id.text_nav_bookshelf)
    private val navPreviewText: TextView? = activity.findViewById(R.id.text_nav_preview)
    private val navSettingsText: TextView? = activity.findViewById(R.id.text_nav_settings)
    private val statusText: TextView? = activity.findViewById(R.id.text_drawer_status)
    private val touchSlop = ViewConfiguration.get(activity).scaledTouchSlop

    private var currentSection = SECTION_NONE
    private var drawerOpen = false
    private var drawerAnimating = false
    private var drawerGestureCandidate = false
    private var drawerDragging = false
    private var drawerDownX = 0f
    private var drawerDownY = 0f
    private var drawerBaseOffset = 0f
    private var drawerLastX = 0f
    private var drawerLastEventTime = 0L
    private var drawerVelocityX = 0f
    private var pendingChildTouchCancel = false
    private var drawerGestureStartedOpen = false
    private var drawerAnimationToken = 0L

    init {
        setupViews()
    }

    private fun setupViews() {
        val panel = drawerPanel ?: return
        val scrim = drawerScrim ?: return
        panel.translationX = -9999f
        panel.visibility = View.INVISIBLE
        scrim.visibility = View.GONE
        scrim.alpha = 0f
        scrim.setOnClickListener { closeDrawer() }
        navBookshelf!!.setOnClickListener {
            navigationListener.onDrawerDestinationSelected(SECTION_BOOKSHELF)
            closeDrawer()
        }
        navPreview!!.setOnClickListener {
            navigationListener.onDrawerDestinationSelected(SECTION_PREVIEW)
            closeDrawer()
        }
        navSettings!!.setOnClickListener {
            navigationListener.onDrawerDestinationSelected(SECTION_SETTINGS)
            closeDrawer()
        }
        panel.post {
            updateDrawerPanelWidth()
            setDrawerOffset(-panel.width.toFloat())
            panel.visibility = View.INVISIBLE
            scrim.visibility = View.GONE
            scrim.alpha = 0f
        }
        rootView?.let { root ->
            root.post {
                updateDrawerPanelWidth()
                syncDrawerOffsetToCurrentState()
                updateDrawerGestureExclusion()
            }
            root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> handleRootLayoutChanged() }
        }
        updateNavigationState()
    }

    fun bindMenuButton(viewId: Int) {
        activity.findViewById<View>(viewId)?.setOnClickListener { openDrawer() }
    }

    fun setCurrentSection(section: Int) {
        currentSection = section
        updateNavigationState()
    }

    fun setStatusText(text: String?) {
        statusText?.text = text.orEmpty()
    }

    fun onBackPressed(): Boolean {
        if (!isDrawerVisible()) return false
        closeDrawer()
        return true
    }

    fun handleTouchEvent(event: MotionEvent): Boolean {
        val panel = drawerPanel ?: return false
        if (panel.width == 0) return false
        val x = event.x
        val y = event.y
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pendingChildTouchCancel = false
                drawerGestureCandidate = shouldStartDrawerGesture(x)
                drawerDragging = false
                drawerVelocityX = 0f
                drawerGestureStartedOpen = isDrawerVisible()
                if (drawerGestureCandidate) {
                    drawerDownX = x
                    drawerDownY = y
                    drawerLastX = x
                    drawerLastEventTime = event.eventTime
                    drawerBaseOffset = panel.translationX
                }
                false
            }

            MotionEvent.ACTION_MOVE -> {
                if (!drawerGestureCandidate) return false
                val deltaX = x - drawerDownX
                val deltaY = y - drawerDownY
                if (!drawerDragging) {
                    val horizontalThreshold = if (isDrawerVisible()) touchSlop else max(4, touchSlop / 2)
                    if (abs(deltaY) > touchSlop && abs(deltaY) > abs(deltaX)) {
                        drawerGestureCandidate = false
                        return false
                    }
                    if (abs(deltaX) <= horizontalThreshold || abs(deltaX) <= abs(deltaY)) return false
                    if (!isDrawerVisible() && deltaX < 0f) {
                        drawerGestureCandidate = false
                        return false
                    }
                    drawerDragging = true
                    pendingChildTouchCancel = true
                    prepareDrawerForInteraction(false)
                }
                setDrawerOffset(clamp(drawerBaseOffset + deltaX, -panel.width.toFloat(), 0f))
                val now = event.eventTime
                val elapsed = max(1L, now - drawerLastEventTime)
                drawerVelocityX = (x - drawerLastX) / elapsed
                drawerLastX = x
                drawerLastEventTime = now
                true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (drawerDragging) {
                    finishDrawerGesture()
                    true
                } else if (drawerGestureCandidate && isDrawerVisible() && x > panel.width) {
                    closeDrawer()
                    drawerGestureCandidate = false
                    drawerGestureStartedOpen = false
                    true
                } else {
                    drawerGestureCandidate = false
                    drawerGestureStartedOpen = false
                    false
                }
            }

            else -> false
        }
    }

    fun openDrawer() {
        val panel = drawerPanel ?: return
        if (panel.width == 0) {
            panel.post(::openDrawer)
            return
        }
        animateDrawerTo(0f, 320L)
    }

    fun closeDrawer() {
        val panel = drawerPanel ?: return
        if (panel.width == 0) return
        animateDrawerTo(-panel.width.toFloat(), 240L)
    }

    fun isDrawerVisible(): Boolean =
        drawerPanel != null && (drawerPanel.visibility == View.VISIBLE || drawerDragging)

    fun consumePendingChildTouchCancel(): Boolean {
        val shouldCancel = pendingChildTouchCancel
        pendingChildTouchCancel = false
        return shouldCancel
    }

    private fun updateNavigationState() {
        styleNavItem(navBookshelf, navBookshelfText, currentSection == SECTION_BOOKSHELF)
        styleNavItem(navPreview, navPreviewText, currentSection == SECTION_PREVIEW)
        styleNavItem(navSettings, navSettingsText, currentSection == SECTION_SETTINGS)
    }

    private fun styleNavItem(container: View?, textView: TextView?, selected: Boolean) {
        container ?: return
        container.setBackgroundResource(if (selected) R.drawable.bg_nav_item_active else R.drawable.bg_nav_item_idle)
        textView?.setTextColor(
            ThemeModeHelper.resolveColor(
                activity,
                if (selected) R.color.app_nav_text_active else R.color.app_nav_text_idle,
            ),
        )
    }

    private fun shouldStartDrawerGesture(x: Float): Boolean {
        if (!isDrawerVisible()) {
            if (currentSection == SECTION_BOOKSHELF || currentSection == SECTION_PREVIEW) return true
            return x <= closedDrawerActivationWidth()
        }
        return x <= drawerPanel!!.width + dp(OPEN_DRAWER_DRAG_EXTRA_DP)
    }

    private fun finishDrawerGesture() {
        val panel = drawerPanel!!
        val offset = panel.translationX
        val panelWidth = max(panel.width, 1)
        var shouldOpen: Boolean
        if (drawerGestureStartedOpen) {
            val closeThreshold = -panelWidth * CLOSE_THRESHOLD_RATIO
            shouldOpen = !(offset <= closeThreshold || drawerVelocityX < -FLING_VELOCITY_THRESHOLD)
            if (drawerVelocityX > FLING_VELOCITY_THRESHOLD) shouldOpen = true
        } else {
            val openThreshold = -panelWidth * (1f - OPEN_THRESHOLD_RATIO)
            shouldOpen = offset > openThreshold || drawerVelocityX > FLING_VELOCITY_THRESHOLD
            if (drawerVelocityX < -FLING_VELOCITY_THRESHOLD) shouldOpen = false
        }
        drawerGestureCandidate = false
        drawerDragging = false
        drawerGestureStartedOpen = false
        if (shouldOpen) openDrawer() else closeDrawer()
    }

    private fun animateDrawerTo(targetOffset: Float, durationMs: Long) {
        val panel = drawerPanel!!
        val scrim = drawerScrim!!
        panel.animate().cancel()
        scrim.animate().cancel()
        val animationToken = ++drawerAnimationToken
        drawerAnimating = true
        val startOffset = clamp(panel.translationX, -panel.width.toFloat(), 0f)
        val panelWidth = max(panel.width.toFloat(), 1f)
        val remainingRatio = abs(targetOffset - startOffset) / panelWidth
        if (remainingRatio <= 0.015f) {
            val progress = drawerProgressForOffset(targetOffset)
            drawerOpen = progress > 0.95f
            drawerAnimating = false
            applyDrawerOffsetState(targetOffset, false)
            return
        }
        val targetProgress = drawerProgressForOffset(targetOffset)
        val targetScrimAlpha = drawerScrimAlpha(targetProgress)
        val targetPanelAlpha = drawerPanelAlpha(targetProgress)
        val targetPanelScaleY = drawerPanelScaleY(targetProgress)
        val isOpening = targetOffset == 0f
        val interpolator: Interpolator = if (isOpening) DecelerateInterpolator(1.3f) else AccelerateInterpolator(1.2f)
        if (isOpening) {
            panel.visibility = View.VISIBLE
            if (scrim.visibility != View.VISIBLE) {
                scrim.visibility = View.VISIBLE
                scrim.alpha = 0f
            }
        } else {
            panel.visibility = View.VISIBLE
            if (scrim.visibility != View.VISIBLE) {
                scrim.visibility = View.VISIBLE
                scrim.alpha = drawerScrimAlpha(drawerProgressForOffset(startOffset))
            }
        }
        scrim.bringToFront()
        panel.bringToFront()
        panel.animate()
            .translationX(targetOffset)
            .alpha(targetPanelAlpha)
            .scaleY(targetPanelScaleY)
            .setDuration(durationMs)
            .setInterpolator(interpolator)
            .withEndAction {
                if (animationToken != drawerAnimationToken) return@withEndAction
                drawerOpen = targetProgress > 0.95f
                drawerAnimating = false
                if (!drawerOpen) panel.visibility = View.INVISIBLE
            }
            .start()
        scrim.animate()
            .alpha(targetScrimAlpha)
            .setDuration(durationMs)
            .setInterpolator(if (isOpening) DecelerateInterpolator(1.3f) else AccelerateInterpolator(1.2f))
            .withEndAction {
                if (animationToken != drawerAnimationToken) return@withEndAction
                if (!isOpening && targetScrimAlpha <= 0.01f) scrim.visibility = View.GONE
            }
            .start()
    }

    private fun prepareDrawerForInteraction(forceVisible: Boolean) {
        val panel = drawerPanel!!
        val scrim = drawerScrim!!
        drawerAnimationToken++
        drawerAnimating = false
        scrim.bringToFront()
        panel.bringToFront()
        panel.animate().cancel()
        scrim.animate().cancel()
        val panelWidth = max(panel.width, 1)
        val currentOffset = clamp(panel.translationX, -panelWidth.toFloat(), 0f)
        applyDrawerOffsetState(currentOffset, forceVisible)
        if (forceVisible && scrim.visibility != View.VISIBLE) {
            scrim.visibility = View.VISIBLE
            scrim.alpha = 0f
        }
    }

    private fun setDrawerOffset(offset: Float) {
        val panel = drawerPanel!!
        applyDrawerOffsetState(clamp(offset, -panel.width.toFloat(), 0f), false)
    }

    private fun clamp(value: Float, low: Float, high: Float): Float = max(low, min(high, value))

    private fun dp(value: Int): Int = (activity.resources.displayMetrics.density * value).roundToInt()

    private fun closedDrawerActivationWidth(): Int {
        val panelWidth = drawerPanel?.width ?: 0
        val preferred = if (panelWidth > 0) (panelWidth * 0.56f).roundToInt() else dp(EDGE_ACTIVATION_MIN_DP)
        return clamp(preferred, dp(EDGE_ACTIVATION_MIN_DP), dp(EDGE_ACTIVATION_MAX_DP))
    }

    private fun clamp(value: Int, low: Int, high: Int): Int = max(low, min(high, value))

    private fun applyDrawerOffsetState(offset: Float, keepVisible: Boolean) {
        val panel = drawerPanel ?: return
        val scrim = drawerScrim ?: return
        val panelWidth = max(panel.width.toFloat(), 1f)
        val clampedOffset = clamp(offset, -panelWidth, 0f)
        val progress = drawerProgressForOffset(clampedOffset)
        val scrimAlpha = drawerScrimAlpha(progress)
        val showPanel = keepVisible || progress > 0.001f
        val showScrim = keepVisible || scrimAlpha > 0f
        panel.pivotX = 0f
        panel.pivotY = panel.height * 0.5f
        panel.visibility = if (showPanel) View.VISIBLE else View.INVISIBLE
        panel.translationX = clampedOffset
        panel.alpha = if (showPanel) drawerPanelAlpha(progress) else 0f
        panel.scaleY = drawerPanelScaleY(progress)
        if (showScrim) {
            scrim.visibility = View.VISIBLE
            scrim.alpha = scrimAlpha
        } else if (scrimAlpha <= 0.01f) {
            scrim.visibility = View.GONE
            scrim.alpha = 0f
        } else {
            scrim.visibility = View.VISIBLE
            scrim.alpha = scrimAlpha
        }
        drawerOpen = progress > 0.95f
    }

    private fun drawerProgressForOffset(offset: Float): Float {
        val panelWidth = max(drawerPanel!!.width.toFloat(), 1f)
        val clampedOffset = clamp(offset, -panelWidth, 0f)
        return 1f - -clampedOffset / panelWidth
    }

    private fun drawerPanelAlpha(progress: Float): Float {
        val safeProgress = clamp(progress, 0f, 1f)
        if (safeProgress <= 0f) return 0f
        val easedProgress = 1f - (1f - safeProgress).pow(1.18f)
        return 0.74f + 0.26f * easedProgress
    }

    private fun drawerPanelScaleY(progress: Float): Float {
        val safeProgress = clamp(progress, 0f, 1f)
        if (safeProgress <= 0f) return 0.986f
        val easedProgress = 1f - (1f - safeProgress).pow(1.08f)
        return 0.986f + 0.014f * easedProgress
    }

    private fun drawerScrimAlpha(progress: Float): Float {
        val safeProgress = clamp(progress, 0f, 1f)
        if (safeProgress <= 0.04f) return 0f
        var easedProgress = (safeProgress - 0.04f) / 0.96f
        easedProgress = easedProgress.pow(1.35f)
        return min(0.92f, easedProgress)
    }

    private fun handleRootLayoutChanged() {
        updateDrawerPanelWidth()
        syncDrawerOffsetToCurrentState()
        updateDrawerGestureExclusion()
    }

    private fun updateDrawerPanelWidth() {
        val panel = drawerPanel ?: return
        val containerWidth = rootView?.width ?: 0
        if (containerWidth <= 0) return
        val desiredWidth = clamp(
            (containerWidth * DRAWER_WIDTH_RATIO).roundToInt(),
            dp(DRAWER_MIN_WIDTH_DP),
            dp(DRAWER_MAX_WIDTH_DP),
        )
        val layoutParams = panel.layoutParams
        if (layoutParams == null || layoutParams.width == desiredWidth) return
        layoutParams.width = desiredWidth
        panel.layoutParams = layoutParams
    }

    private fun syncDrawerOffsetToCurrentState() {
        val panel = drawerPanel ?: return
        if (panel.width == 0 || drawerDragging || drawerAnimating) return
        setDrawerOffset(if (drawerOpen) 0f else -panel.width.toFloat())
    }

    private fun updateDrawerGestureExclusion() {
        val root = rootView ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val exclusionRects = ArrayList<Rect>(1)
        exclusionRects.add(Rect(0, 0, max(dp(EDGE_EXCLUSION_DP), closedDrawerActivationWidth()), root.height))
        root.systemGestureExclusionRects = exclusionRects
    }

    companion object {
        const val SECTION_NONE = -1
        const val SECTION_BOOKSHELF = 0
        const val SECTION_PREVIEW = 1
        const val SECTION_SETTINGS = 2
        private const val DRAWER_WIDTH_RATIO = 0.84f
        private const val DRAWER_MIN_WIDTH_DP = 300
        private const val DRAWER_MAX_WIDTH_DP = 420
        private const val EDGE_ACTIVATION_MIN_DP = 112
        private const val EDGE_ACTIVATION_MAX_DP = 156
        private const val EDGE_EXCLUSION_DP = 156
        private const val OPEN_DRAWER_DRAG_EXTRA_DP = 72
        private const val OPEN_THRESHOLD_RATIO = 0.34f
        private const val CLOSE_THRESHOLD_RATIO = 0.22f
        private const val FLING_VELOCITY_THRESHOLD = 0.55f
    }
}
