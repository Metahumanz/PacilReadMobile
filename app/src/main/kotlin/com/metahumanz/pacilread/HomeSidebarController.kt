package com.metahumanz.pacilread

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.metahumanz.pacilread.storage.SettingsStore
import com.metahumanz.pacilread.theme.ThemeModeHelper
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

internal class HomeSidebarController(
    private val activity: Activity,
    private val settingsStore: SettingsStore,
    private val resolver: HomeNavigationResolver,
    private val callback: Callback,
) {
    fun interface Callback {
        fun onSidebarPageSelected(page: Int, animate: Boolean)
    }

    private val fixedSidebar: LinearLayout? = activity.findViewById(R.id.home_fixed_sidebar)
    private val sidebarScrim: View? = activity.findViewById(R.id.home_sidebar_scrim)
    private val slideSidebarContainer: FrameLayout? = activity.findViewById(R.id.home_slide_sidebar_container)
    private val touchSlop = ViewConfiguration.get(activity).scaledTouchSlop
    private val activePages = ArrayList<Int>()
    private val navRows = ArrayList<NavRow>()

    private var effectiveMode = HomeNavigationResolver.MODE_BOTTOM
    private var currentPage = HomeNavigationController.PAGE_BOOKSHELF
    private var drawerOpen = false
    private var drawerCandidate = false
    private var drawerDragging = false
    private var drawerDownX = 0f
    private var drawerDownY = 0f
    private var drawerStartOffset = 0f
    private var drawerLastX = 0f
    private var drawerLastEventTime = 0L
    private var drawerVelocityX = 0f
    private var pendingChildTouchCancel = false
    private var drawerGestureStartedOpen = false
    private var drawerAnimating = false
    private var drawerAnimationToken = 0L

    init {
        setup()
    }

    fun setActivePages(pages: List<Int>) {
        activePages.clear()
        activePages.addAll(pages)
    }

    fun updateShell(mode: Int, modeChanged: Boolean) {
        effectiveMode = mode
        fixedSidebar?.let { sidebar ->
            sidebar.visibility = if (effectiveMode == HomeNavigationResolver.MODE_FIXED_SIDEBAR) View.VISIBLE else View.GONE
            val width = if (effectiveMode == HomeNavigationResolver.MODE_FIXED_SIDEBAR) resolver.fixedSidebarWidth() else 0
            val params = sidebar.layoutParams
            if (params != null && params.width != width) {
                params.width = width
                sidebar.layoutParams = params
            }
        }
        if (modeChanged || effectiveMode != HomeNavigationResolver.MODE_SLIDE_SIDEBAR) closeDrawerImmediate()
        else syncDrawerOffsetToCurrentState()
    }

    fun rebuild() {
        navRows.clear()
        buildFixedSidebar()
        buildSlideSidebar()
        updateSelection(currentPage)
    }

    fun updateSelection(page: Int) {
        currentPage = page
        for (row in navRows) {
            val selected = row.page == currentPage
            row.container.setBackgroundResource(if (selected) R.drawable.bg_nav_item_active else R.drawable.bg_nav_item_idle)
            val color = ThemeModeHelper.resolveColor(
                activity,
                if (selected) R.color.app_nav_text_active else R.color.app_nav_text_idle,
            )
            row.icon.setColorFilter(color)
            row.label.setTextColor(color)
        }
    }

    val isDrawerOpen: Boolean
        get() = isDrawerVisible()

    fun onBackPressed(): Boolean {
        if (!isDrawerVisible()) return false
        closeDrawer()
        return true
    }

    fun consumePendingChildTouchCancel(): Boolean {
        val shouldCancel = pendingChildTouchCancel
        pendingChildTouchCancel = false
        return shouldCancel
    }

    fun handleTouchEvent(event: MotionEvent): Boolean =
        effectiveMode == HomeNavigationResolver.MODE_SLIDE_SIDEBAR && handleSidebarTouch(event)

    fun closeDrawer() {
        if (slideSidebarContainer == null || sidebarScrim == null) return
        animateDrawerTo(-drawerWidth().toFloat(), 240L)
    }

    private fun isDrawerVisible(): Boolean = slideSidebarContainer != null &&
        (drawerOpen || drawerDragging || slideSidebarContainer.visibility == View.VISIBLE)

    private fun setup() {
        sidebarScrim?.let { scrim ->
            scrim.setOnClickListener { closeDrawer() }
            scrim.visibility = View.GONE
            scrim.alpha = 0f
        }
        slideSidebarContainer?.let { container ->
            container.translationX = -9999f
            container.visibility = View.INVISIBLE
            container.alpha = 0f
            container.scaleY = 0.986f
            container.post { if (!drawerOpen) applyDrawerOffsetState(-drawerWidth().toFloat(), false) }
        }
    }

    private fun buildFixedSidebar() {
        val sidebar = fixedSidebar ?: return
        sidebar.removeAllViews()
        sidebar.orientation = LinearLayout.VERTICAL
        sidebar.setBackgroundResource(R.drawable.bg_sidebar_panel)
        sidebar.setPadding(
            AppUiUtils.dp(activity, 10),
            AppUiUtils.dp(activity, 20),
            AppUiUtils.dp(activity, 10),
            AppUiUtils.dp(activity, 16),
        )
        val iconOnly = settingsStore.homeFixedSidebarStyle == "icons"
        addBrand(sidebar, iconOnly)
        addNavigationRows(sidebar, iconOnly, true)
    }

    private fun buildSlideSidebar() {
        val container = slideSidebarContainer ?: return
        container.removeAllViews()
        val width = AppUiUtils.dp(activity, 320)
        val containerParams = container.layoutParams
        if (containerParams != null && containerParams.width != width) {
            containerParams.width = width
            container.layoutParams = containerParams
        }
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_sidebar_panel)
            isClickable = true
            setPadding(
                AppUiUtils.dp(activity, 16),
                AppUiUtils.dp(activity, 24),
                AppUiUtils.dp(activity, 16),
                AppUiUtils.dp(activity, 18),
            )
        }
        container.addView(panel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        addBrand(panel, false)
        addNavigationRows(panel, false, false)
        if (!drawerOpen) applyDrawerOffsetState(-drawerWidth().toFloat(), false)
    }

    private fun addBrand(parent: LinearLayout, iconOnly: Boolean) {
        val brand = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (iconOnly) Gravity.CENTER else Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, AppUiUtils.dp(activity, 18))
        }
        val mark = TextView(activity).apply {
            gravity = Gravity.CENTER
            text = "P"
            textSize = 18f
            setTypeface(null, Typeface.BOLD_ITALIC)
            setTextColor(ThemeModeHelper.resolveColor(activity, R.color.app_button_primary_text))
            setBackgroundResource(R.drawable.bg_app_primary_button)
        }
        brand.addView(mark, LinearLayout.LayoutParams(AppUiUtils.dp(activity, 36), AppUiUtils.dp(activity, 36)))
        if (!iconOnly) {
            val label = TextView(activity).apply {
                text = "PacilRead"
                textSize = 18f
                setTypeface(null, Typeface.BOLD)
                setTextColor(ThemeModeHelper.resolveColor(activity, R.color.app_text_primary))
            }
            val labelParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(AppUiUtils.dp(activity, 12), 0, 0, 0) }
            brand.addView(label, labelParams)
        }
        parent.addView(brand, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
    }

    private fun addNavigationRows(parent: LinearLayout, iconOnly: Boolean, fixed: Boolean) {
        for (page in activePages) {
            if (page != HomeNavigationController.PAGE_SETTINGS) parent.addView(createSidebarRow(page, iconOnly, fixed))
        }
        parent.addView(View(activity), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        parent.addView(createSidebarRow(HomeNavigationController.PAGE_SETTINGS, iconOnly, fixed))
    }

    private fun createSidebarRow(page: Int, iconOnly: Boolean, fixed: Boolean): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (iconOnly) Gravity.CENTER else Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            contentDescription = resolver.labelForPage(page)
            tooltipText = resolver.labelForPage(page)
            setPadding(
                if (iconOnly) 0 else AppUiUtils.dp(activity, 10),
                AppUiUtils.dp(activity, 12),
                if (iconOnly) 0 else AppUiUtils.dp(activity, 12),
                AppUiUtils.dp(activity, 12),
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                if (iconOnly) AppUiUtils.dp(activity, 48) else LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, AppUiUtils.dp(activity, 4), 0, 0) }
        }
        val icon = ImageView(activity).apply {
            setImageResource(resolver.iconResForPage(page))
            scaleType = ImageView.ScaleType.CENTER
        }
        row.addView(icon, LinearLayout.LayoutParams(
            if (iconOnly) LinearLayout.LayoutParams.MATCH_PARENT else AppUiUtils.dp(activity, 28),
            AppUiUtils.dp(activity, 24),
        ))
        val label = TextView(activity).apply {
            text = resolver.labelForPage(page)
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            visibility = if (iconOnly) View.GONE else View.VISIBLE
        }
        if (!iconOnly) {
            val labelParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(AppUiUtils.dp(activity, 12), 0, 0, 0) }
            row.addView(label, labelParams)
        }
        row.setOnClickListener { callback.onSidebarPageSelected(page, !fixed) }
        navRows.add(NavRow(page, row, icon, label))
        return row
    }

    private fun handleSidebarTouch(event: MotionEvent): Boolean {
        val container = slideSidebarContainer ?: return false
        val x = event.x
        val y = event.y
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pendingChildTouchCancel = false
                drawerCandidate = shouldStartDrawerGesture(x)
                drawerDragging = false
                drawerVelocityX = 0f
                drawerGestureStartedOpen = isDrawerVisible()
                if (drawerCandidate) {
                    drawerDownX = x
                    drawerDownY = y
                    drawerLastX = x
                    drawerLastEventTime = event.eventTime
                    drawerStartOffset = currentDrawerOffset()
                }
                false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!drawerCandidate) return false
                val deltaX = x - drawerDownX
                val deltaY = y - drawerDownY
                if (!drawerDragging) {
                    val horizontalThreshold = if (isDrawerVisible()) touchSlop else max(4, touchSlop / 2)
                    if (abs(deltaY) > touchSlop && abs(deltaY) > abs(deltaX)) {
                        resetDrawerGesture()
                        return false
                    }
                    if (abs(deltaX) <= horizontalThreshold || abs(deltaX) <= abs(deltaY)) return false
                    if (!isDrawerVisible() && deltaX < 0f) {
                        resetDrawerGesture()
                        return false
                    }
                    drawerDragging = true
                    pendingChildTouchCancel = true
                    prepareDrawerForInteraction()
                }
                updateDrawerOffset(drawerStartOffset + deltaX)
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
                } else if (drawerCandidate && isDrawerVisible() && x > drawerWidth()) {
                    closeDrawer()
                    resetDrawerGesture()
                    true
                } else {
                    resetDrawerGesture()
                    false
                }
            }
            else -> false
        }
    }

    private fun prepareDrawerForInteraction() {
        val container = slideSidebarContainer ?: return
        val scrim = sidebarScrim ?: return
        drawerAnimationToken++
        drawerAnimating = false
        container.animate().cancel()
        scrim.animate().cancel()
        scrim.bringToFront()
        container.bringToFront()
        applyDrawerOffsetState(currentDrawerOffset(), true)
    }

    private fun finishDrawerGesture() {
        val container = slideSidebarContainer
        if (container == null) {
            resetDrawerGesture()
            return
        }
        val width = drawerWidth()
        val offset = container.translationX
        var shouldOpen: Boolean
        if (drawerGestureStartedOpen) {
            shouldOpen = !(offset <= -width * CLOSE_THRESHOLD_RATIO || drawerVelocityX < -FLING_VELOCITY_THRESHOLD)
            if (drawerVelocityX > FLING_VELOCITY_THRESHOLD) shouldOpen = true
        } else {
            shouldOpen = offset > -width * (1f - OPEN_THRESHOLD_RATIO) || drawerVelocityX > FLING_VELOCITY_THRESHOLD
            if (drawerVelocityX < -FLING_VELOCITY_THRESHOLD) shouldOpen = false
        }
        resetDrawerGesture()
        if (shouldOpen) openDrawer() else closeDrawer()
    }

    private fun resetDrawerGesture() {
        drawerCandidate = false
        drawerDragging = false
        drawerVelocityX = 0f
        drawerGestureStartedOpen = false
    }

    fun openDrawer() {
        val container = slideSidebarContainer
        if (effectiveMode != HomeNavigationResolver.MODE_SLIDE_SIDEBAR || container == null || sidebarScrim == null) return
        if (container.width == 0) {
            container.post(::openDrawer)
            return
        }
        animateDrawerTo(0f, 320L)
    }

    private fun closeDrawerImmediate() {
        drawerOpen = false
        drawerAnimating = false
        drawerAnimationToken++
        resetDrawerGesture()
        slideSidebarContainer?.let { container ->
            container.animate().cancel()
            applyDrawerOffsetState(-drawerWidth().toFloat(), false)
        }
        sidebarScrim?.let { scrim ->
            scrim.animate().cancel()
            scrim.visibility = View.GONE
            scrim.alpha = 0f
        }
    }

    private fun updateDrawerOffset(offset: Float) {
        if (slideSidebarContainer == null || sidebarScrim == null) return
        val width = drawerWidth()
        applyDrawerOffsetState(max(-width.toFloat(), min(0f, offset)), false)
    }

    private fun animateDrawerTo(targetOffset: Float, durationMs: Long) {
        val container = slideSidebarContainer ?: return
        val scrim = sidebarScrim ?: return
        if (drawerWidth() == 0) return
        container.animate().cancel()
        scrim.animate().cancel()
        val animationToken = ++drawerAnimationToken
        drawerAnimating = true
        val width = drawerWidth()
        val startOffset = max(-width.toFloat(), min(0f, currentDrawerOffset()))
        val remainingRatio = abs(targetOffset - startOffset) / max(width.toFloat(), 1f)
        if (remainingRatio <= 0.015f) {
            drawerAnimating = false
            applyDrawerOffsetState(targetOffset, false)
            return
        }
        val opening = targetOffset == 0f
        val interpolator: Interpolator = if (opening) DecelerateInterpolator(1.3f) else AccelerateInterpolator(1.2f)
        val targetProgress = drawerProgressForOffset(targetOffset)
        val targetScrimAlpha = drawerScrimAlpha(targetProgress)
        val targetPanelAlpha = drawerPanelAlpha(targetProgress)
        val targetPanelScaleY = drawerPanelScaleY(targetProgress)
        container.visibility = View.VISIBLE
        if (scrim.visibility != View.VISIBLE) {
            scrim.visibility = View.VISIBLE
            scrim.alpha = drawerScrimAlpha(drawerProgressForOffset(startOffset))
        }
        scrim.bringToFront()
        container.bringToFront()
        container.animate()
            .translationX(targetOffset)
            .alpha(targetPanelAlpha)
            .scaleY(targetPanelScaleY)
            .setDuration(durationMs)
            .setInterpolator(interpolator)
            .withEndAction {
                if (animationToken != drawerAnimationToken) return@withEndAction
                drawerAnimating = false
                applyDrawerOffsetState(targetOffset, false)
            }
            .start()
        scrim.animate()
            .alpha(targetScrimAlpha)
            .setDuration(durationMs)
            .setInterpolator(interpolator)
            .withEndAction {
                if (animationToken != drawerAnimationToken) return@withEndAction
                if (targetScrimAlpha <= 0.01f) scrim.visibility = View.GONE
            }
            .start()
    }

    private fun shouldStartDrawerGesture(x: Float): Boolean =
        !isDrawerVisible() || x <= drawerWidth() + AppUiUtils.dp(activity, OPEN_DRAWER_DRAG_EXTRA_DP)

    private fun currentDrawerOffset(): Float {
        val container = slideSidebarContainer ?: return -drawerWidth().toFloat()
        val offset = container.translationX
        return if (offset < -drawerWidth() || offset > 0f) {
            if (drawerOpen) 0f else -drawerWidth().toFloat()
        } else offset
    }

    private fun applyDrawerOffsetState(offset: Float, keepVisible: Boolean) {
        val container = slideSidebarContainer ?: return
        val scrim = sidebarScrim ?: return
        val width = drawerWidth()
        val clamped = max(-width.toFloat(), min(0f, offset))
        val progress = drawerProgressForOffset(clamped)
        val showPanel = keepVisible || progress > 0.001f
        val scrimAlpha = drawerScrimAlpha(progress)
        val showScrim = keepVisible || scrimAlpha > 0f
        container.pivotX = 0f
        container.pivotY = container.height * 0.5f
        container.translationX = clamped
        container.visibility = if (showPanel) View.VISIBLE else View.INVISIBLE
        container.alpha = if (showPanel) drawerPanelAlpha(progress) else 0f
        container.scaleY = drawerPanelScaleY(progress)
        if (showScrim) {
            scrim.visibility = View.VISIBLE
            scrim.alpha = scrimAlpha
        } else {
            scrim.visibility = View.GONE
            scrim.alpha = 0f
        }
        drawerOpen = progress > 0.95f
    }

    private fun drawerProgressForOffset(offset: Float): Float {
        val width = drawerWidth()
        val clamped = max(-width.toFloat(), min(0f, offset))
        return 1f - -clamped / max(width.toFloat(), 1f)
    }

    private fun drawerPanelAlpha(progress: Float): Float {
        val safeProgress = max(0f, min(1f, progress))
        if (safeProgress <= 0f) return 0f
        val easedProgress = 1f - (1f - safeProgress).pow(1.18f)
        return 0.74f + 0.26f * easedProgress
    }

    private fun drawerPanelScaleY(progress: Float): Float {
        val safeProgress = max(0f, min(1f, progress))
        if (safeProgress <= 0f) return 0.986f
        val easedProgress = 1f - (1f - safeProgress).pow(1.08f)
        return 0.986f + 0.014f * easedProgress
    }

    private fun drawerScrimAlpha(progress: Float): Float {
        val safeProgress = max(0f, min(1f, progress))
        if (safeProgress <= 0.04f) return 0f
        var easedProgress = (safeProgress - 0.04f) / 0.96f
        easedProgress = easedProgress.pow(1.35f)
        return min(SCRIM_MAX_ALPHA, SCRIM_MAX_ALPHA * easedProgress)
    }

    private fun drawerWidth(): Int {
        val container = slideSidebarContainer ?: return AppUiUtils.dp(activity, 320)
        if (container.width > 0) return container.width
        val params = container.layoutParams
        if (params != null && params.width > 0) return params.width
        return AppUiUtils.dp(activity, 320)
    }

    private fun syncDrawerOffsetToCurrentState() {
        if (slideSidebarContainer == null || drawerDragging || drawerAnimating) return
        if (effectiveMode != HomeNavigationResolver.MODE_SLIDE_SIDEBAR) return
        applyDrawerOffsetState(if (drawerOpen) 0f else -drawerWidth().toFloat(), false)
    }

    private class NavRow(
        val page: Int,
        val container: LinearLayout,
        val icon: ImageView,
        val label: TextView,
    )

    private companion object {
        const val OPEN_DRAWER_DRAG_EXTRA_DP = 72
        const val OPEN_THRESHOLD_RATIO = 0.34f
        const val CLOSE_THRESHOLD_RATIO = 0.22f
        const val FLING_VELOCITY_THRESHOLD = 0.55f
        const val SCRIM_MAX_ALPHA = 0.34f
    }
}
