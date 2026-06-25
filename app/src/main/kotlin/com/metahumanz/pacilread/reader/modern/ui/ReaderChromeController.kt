package com.metahumanz.pacilread.reader.modern.ui

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import com.metahumanz.pacilread.R
import com.metahumanz.pacilread.reader.modern.ModernReaderActivity
import com.metahumanz.pacilread.reader.modern.ReaderRuntime
import com.metahumanz.pacilread.reader.modern.ReaderSessionState
import com.metahumanz.pacilread.reader.modern.ReaderUiUtils
import com.metahumanz.pacilread.reader.modern.ReaderViewRefs
import com.metahumanz.pacilread.reader.modern.content.ReaderContentController
import com.metahumanz.pacilread.reader.modern.paging.ReaderPagingAnimator
import com.metahumanz.pacilread.reader.modern.playback.ReaderAutoPageController
import com.metahumanz.pacilread.reader.modern.theme.ReaderDisplayModeHelper
import com.metahumanz.pacilread.reader.modern.theme.ReaderThemePalette
import com.metahumanz.pacilread.reader.modern.tts.ReaderTtsController
import com.metahumanz.pacilread.theme.ThemeModeHelper
import com.metahumanz.pacilread.ui.GlassUiHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

class ReaderChromeController(
    private val activity: ModernReaderActivity,
    private val runtime: ReaderRuntime,
    private val views: ReaderViewRefs,
    private val state: ReaderSessionState,
    private val ui: ReaderUiUtils,
) {
    private val autoHideRunnable = Runnable { setControlsVisible(false) }
    private var menuPanelColor = 0xFFF7F0E1.toInt()
    private var menuPanelStrokeColor = 0xFFE3D6C7.toInt()
    private var menuButtonColor = 0xFFF1E8D7.toInt()
    private var menuButtonStrokeColor = 0xFFD8CAB7.toInt()
    private var menuTextColor = 0xFF5C4B37.toInt()
    private var menuMutedTextColor = 0xFF6F5E46.toInt()
    private var menuButtonTextColor = 0xFF5C4B37.toInt()
    private var menuActiveFillColor = 0xFF1B61C9.toInt()
    private var menuActiveStrokeColor = 0xFF254FAD.toInt()
    private var menuActiveTextColor = 0xFFFFFFFF.toInt()
    private lateinit var content: ReaderContentController
    private lateinit var paging: ReaderPagingAnimator
    private var autoPage: ReaderAutoPageController? = null
    private var tts: ReaderTtsController? = null

    fun attachControllers(content: ReaderContentController, paging: ReaderPagingAnimator, autoPage: ReaderAutoPageController, tts: ReaderTtsController) {
        this.content = content
        this.paging = paging
        this.autoPage = autoPage
        this.tts = tts
    }

    fun configureReaderWindow() {
        val window = activity.window
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        updateSystemBarsVisibility(false)
    }

    fun applyEdgeToEdgeInsets() {
        views.readerRoot.setOnApplyWindowInsetsListener { view, windowInsets ->
            val previousInsetTop = state.systemInsetTop
            val previousInsetBottom = state.systemInsetBottom
            val previousContentInsetTop = state.readerContentInsetTop
            val previousContentInsetBottom = state.readerContentInsetBottom
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val systemBars = windowInsets.getInsets(WindowInsets.Type.systemBars())
                val stableSystemBars = windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
                val cutout = windowInsets.getInsets(WindowInsets.Type.displayCutout())
                val landscape = view.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                state.systemInsetLeft = if (landscape) systemBars.left else max(systemBars.left, cutout.left)
                state.systemInsetTop = max(systemBars.top, cutout.top)
                state.systemInsetRight = if (landscape) systemBars.right else max(systemBars.right, cutout.right)
                state.systemInsetBottom = max(systemBars.bottom, cutout.bottom)
                state.readerContentInsetLeft = if (landscape) stableSystemBars.left else max(stableSystemBars.left, cutout.left)
                state.readerContentInsetTop = max(stableSystemBars.top, cutout.top)
                state.readerContentInsetRight = if (landscape) stableSystemBars.right else max(stableSystemBars.right, cutout.right)
                state.readerContentInsetBottom = max(stableSystemBars.bottom, cutout.bottom)
            } else {
                @Suppress("DEPRECATION")
                state.systemInsetLeft = windowInsets.systemWindowInsetLeft
                @Suppress("DEPRECATION")
                state.systemInsetTop = windowInsets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                state.systemInsetRight = windowInsets.systemWindowInsetRight
                @Suppress("DEPRECATION")
                state.systemInsetBottom = windowInsets.systemWindowInsetBottom
                @Suppress("DEPRECATION")
                state.readerContentInsetLeft = windowInsets.stableInsetLeft
                @Suppress("DEPRECATION")
                state.readerContentInsetTop = windowInsets.stableInsetTop
                @Suppress("DEPRECATION")
                state.readerContentInsetRight = windowInsets.stableInsetRight
                @Suppress("DEPRECATION")
                state.readerContentInsetBottom = windowInsets.stableInsetBottom
            }
            updateReaderLayoutInsets()
            if (previousInsetTop != state.systemInsetTop || previousInsetBottom != state.systemInsetBottom ||
                previousContentInsetTop != state.readerContentInsetTop || previousContentInsetBottom != state.readerContentInsetBottom) {
                val paginationInsetsChanged = previousContentInsetTop != state.readerContentInsetTop || previousContentInsetBottom != state.readerContentInsetBottom
                if (::content.isInitialized) content.onReaderInsetsChanged(shouldSuppressInsetDrivenReflow(), paginationInsetsChanged)
            }
            windowInsets
        }
        views.readerRoot.requestApplyInsets()
    }

    fun updateReaderLayoutInsets() {
        val bottomPanelBottomMargin = ui.dp(8) + state.systemInsetBottom
        val menuBottomHeight = views.menuBottomPanel.height
        val infoBottomMargin = if (menuBottomHeight > 0) bottomPanelBottomMargin + menuBottomHeight + ui.dp(10) else ui.dp(148) + state.systemInsetBottom
        views.hudTopContainer.setPadding(ui.dp(12) + state.readerContentInsetLeft,
            ui.dp(runtime.settingsStore.hudTopMarginDp) + state.readerContentInsetTop,
            ui.dp(12) + state.readerContentInsetRight, 0)
        views.hudBottomContainer.setPadding(ui.dp(12) + state.readerContentInsetLeft, 0,
            ui.dp(12) + state.readerContentInsetRight,
            ui.dp(runtime.settingsStore.hudBottomMarginDp) + state.readerContentInsetBottom)
        views.pageStage.setPadding(0, 0, 0, 0)
        updateFrameLayoutMargins(views.menuTopPanel, ui.dp(10) + state.systemInsetLeft, ui.dp(10) + state.systemInsetTop, ui.dp(10) + state.systemInsetRight, 0)
        updateFrameLayoutMargins(views.remoteProgressBanner, ui.dp(12) + state.systemInsetLeft, ui.dp(12) + state.systemInsetTop, ui.dp(12) + state.systemInsetRight, 0)
        updateFrameLayoutMargins(views.menuInfoPanel, ui.dp(10) + state.systemInsetLeft, 0, ui.dp(10) + state.systemInsetRight, infoBottomMargin)
        updateFrameLayoutMargins(views.menuBottomPanel, ui.dp(10) + state.systemInsetLeft, 0, ui.dp(10) + state.systemInsetRight, bottomPanelBottomMargin)
    }

    fun updateUiAfterPageChange() {
        val book = state.book ?: return
        if (state.chapters.isEmpty()) return
        content.rememberCurrentPageAnchor()
        val chapter = state.chapters[state.currentChapterIndex]
        val safePageCount = max(content.getKnownPageCountForChapter(state.currentChapterIndex), 1)
        val pageCountComplete = content.isPageCountCompleteForChapter(state.currentChapterIndex)
        val statsEnabled = runtime.settingsStore.isReadingTimeTrackingEnabled
        val bookTitle = book.title?.trim()?.takeIf { it.isNotEmpty() } ?: "未命名书籍"
        val chapterTitle = chapter.title?.trim() ?: ""
        views.readerTitle.text = if (chapterTitle.isEmpty()) bookTitle else "$bookTitle | $chapterTitle"
        views.readerTitle.isEnabled = statsEnabled
        views.readerTitle.isClickable = statsEnabled
        views.readerTitle.alpha = if (statsEnabled) 1f else 0.9f
        views.chapterMeta.text = String.format(Locale.SIMPLIFIED_CHINESE, "第 %d/%d 章 · %s", state.currentChapterIndex + 1, state.chapters.size, chapter.title)
        if (runtime.settingsStore.readerSliderMode == "book") {
            views.pageMeta.text = "${currentChapterPageText()} · 全书章节"
            views.progressSeekBar.max = max(state.chapters.size - 1, 0)
            views.progressSeekBar.progress = state.currentChapterIndex
        } else {
            views.pageMeta.text = "${currentChapterPageText()} · 本章页数"
            views.progressSeekBar.max = if (pageCountComplete) max(safePageCount - 1, 0) else max(max(safePageCount - 1, 0), state.currentPageIndex)
            views.progressSeekBar.progress = state.currentPageIndex
        }
        updateReaderHud()
        styleReaderMenuButton(views.ttsButton, tts?.isActive() == true)
        styleReaderMenuButton(views.autoPageButton, autoPage?.isActive() == true)
        styleReaderMenuButton(views.themeToggleButton, isDarkReaderUi())
    }

    fun fetchCurrentProgressPercent(): Int {
        if (state.book == null || state.chapters.isEmpty()) return 0
        val totalLength = content.getTotalProcessedBookLength()
        if (totalLength <= 0L) return 0
        var readLength = 0L
        for (i in 0 until state.currentChapterIndex) readLength += content.getProcessedChapterLength(i)
        val currentLength = content.getProcessedChapterLength(state.currentChapterIndex)
        readLength += min(max(content.currentCharOffset(), 0), max(currentLength, 0)).toLong()
        return (readLength * 100.0 / totalLength).roundToInt()
    }

    fun updateReaderHud() {
        if (state.book == null || state.chapters.isEmpty()) return
        content.rememberCurrentPageAnchor()
        val showCenterSlots = isLandscapeHudMode()
        applyHudSlot(views.hudTopLeft, runtime.settingsStore.hudTopLeft)
        if (showCenterSlots) applyHudSlot(views.hudTopCenter, runtime.settingsStore.hudTopCenter) else hideHudSlot(views.hudTopCenter)
        applyHudSlot(views.hudTopRight, runtime.settingsStore.hudTopRight)
        applyHudSlot(views.hudBottomLeft, runtime.settingsStore.hudBottomLeft)
        if (showCenterSlots) applyHudSlot(views.hudBottomCenter, runtime.settingsStore.hudBottomCenter) else hideHudSlot(views.hudBottomCenter)
        applyHudSlot(views.hudBottomRight, runtime.settingsStore.hudBottomRight)
        if (::paging.isInitialized && !state.isAnimating && !state.interactivePaging && !state.simulationStableCoverVisible) paging.invalidatePreparedPagingSnapshots()
    }

    fun captureHudSnapshotState() = HudSnapshotState(
        captureHudSlotState(views.hudTopLeft), captureHudSlotState(views.hudTopCenter), captureHudSlotState(views.hudTopRight),
        captureHudSlotState(views.hudBottomLeft), captureHudSlotState(views.hudBottomCenter), captureHudSlotState(views.hudBottomRight),
    )

    fun restoreHudSnapshotState(snapshot: HudSnapshotState?) {
        snapshot ?: return
        restoreHudSlotState(views.hudTopLeft, snapshot.topLeft)
        restoreHudSlotState(views.hudTopCenter, snapshot.topCenter)
        restoreHudSlotState(views.hudTopRight, snapshot.topRight)
        restoreHudSlotState(views.hudBottomLeft, snapshot.bottomLeft)
        restoreHudSlotState(views.hudBottomCenter, snapshot.bottomCenter)
        restoreHudSlotState(views.hudBottomRight, snapshot.bottomRight)
    }

    fun updateReaderHudForPageSnapshot(chapterIndex: Int, pageIndex: Int) {
        if (state.book == null || state.chapters.isEmpty()) return
        val showCenterSlots = isLandscapeHudMode()
        applyHudSlot(views.hudTopLeft, runtime.settingsStore.hudTopLeft, chapterIndex, pageIndex)
        if (showCenterSlots) applyHudSlot(views.hudTopCenter, runtime.settingsStore.hudTopCenter, chapterIndex, pageIndex) else hideHudSlot(views.hudTopCenter)
        applyHudSlot(views.hudTopRight, runtime.settingsStore.hudTopRight, chapterIndex, pageIndex)
        applyHudSlot(views.hudBottomLeft, runtime.settingsStore.hudBottomLeft, chapterIndex, pageIndex)
        if (showCenterSlots) applyHudSlot(views.hudBottomCenter, runtime.settingsStore.hudBottomCenter, chapterIndex, pageIndex) else hideHudSlot(views.hudBottomCenter)
        applyHudSlot(views.hudBottomRight, runtime.settingsStore.hudBottomRight, chapterIndex, pageIndex)
    }

    fun toggleReaderUiTheme() {
        runtime.settingsStore.readerUiThemeMode = if (isDarkReaderUi()) "light" else "dark"
        activity.applyReaderUiThemeWithoutRecreate()
    }

    fun applyReaderMenuPalette(palette: ReaderThemePalette?, readerTextColor: Int) {
        palette ?: return
        menuPanelColor = opaqueColor(palette.pageColor)
        val darkPanel = isDarkColor(menuPanelColor)
        menuPanelStrokeColor = shiftSurfaceColor(menuPanelColor, if (darkPanel) 0.22f else 0.13f)
        menuButtonColor = shiftSurfaceColor(menuPanelColor, if (darkPanel) 0.08f else 0.04f)
        menuButtonStrokeColor = shiftSurfaceColor(menuPanelColor, if (darkPanel) 0.24f else 0.16f)
        menuTextColor = ensureReadableText(readerTextColor, menuPanelColor, 4.5)
        menuMutedTextColor = ensureReadableText(blendColors(menuTextColor, menuPanelColor, 0.18f), menuPanelColor, 4.5)
        menuButtonTextColor = ensureReadableText(menuTextColor, menuButtonColor, 4.5)
        menuActiveFillColor = opaqueColor(colorOrDefault(ThemeModeHelper.resolveThemeAttrColor(activity, R.attr.themeColorReaderMenuButtonActiveFill),
            if (darkPanel) shiftSurfaceColor(menuPanelColor, 0.32f) else ThemeModeHelper.resolveColor(activity, R.color.primary)))
        menuActiveStrokeColor = opaqueColor(colorOrDefault(ThemeModeHelper.resolveThemeAttrColor(activity, R.attr.themeColorReaderMenuButtonActiveStroke),
            shiftSurfaceColor(menuActiveFillColor, if (isDarkColor(menuActiveFillColor)) 0.18f else 0.16f)))
        menuActiveTextColor = ensureReadableText(ThemeModeHelper.resolveThemeAttrColor(activity, R.attr.themeColorReaderMenuButtonActiveText), menuActiveFillColor, 4.5)
        applyReaderMenuSurfaces()
    }

    fun styleReaderMenuButton(button: Button?, active: Boolean) {
        button ?: return
        button.setTag(R.id.tag_glass_background, false)
        button.background = createRoundedDrawable(if (active) menuActiveFillColor else menuButtonColor,
            if (active) menuActiveStrokeColor else menuButtonStrokeColor, resolveDimensionAttr(R.attr.themeRadiusReaderButton, 18))
        button.setTextColor(if (active) menuActiveTextColor else menuButtonTextColor)
    }

    fun applyGlassOpacity() {
        GlassUiHelper.applyToHierarchy(activity, views.menuTopPanel, runtime.settingsStore.glassOpacityPercent)
        GlassUiHelper.applyToHierarchy(activity, views.menuInfoPanel, runtime.settingsStore.glassOpacityPercent)
        GlassUiHelper.applyToHierarchy(activity, views.menuBottomPanel, runtime.settingsStore.glassOpacityPercent)
    }

    fun applyMenuLayoutMode() {
        val persistent = runtime.settingsStore.isReaderMenuPersistentActionsEnabled
        views.moreButton.visibility = if (persistent) View.GONE else View.VISIBLE
        views.menuTopActions.visibility = if (persistent) View.VISIBLE else View.GONE
    }

    fun updateReaderThemeButtons(paper: Button?, forest: Button?, night: Button?, current: String?) {
        styleThemeButton(paper, current == "paper")
        styleThemeButton(forest, current == "forest")
        styleThemeButton(night, current == "night")
    }

    fun styleThemeButton(button: Button?, active: Boolean) {
        button ?: return
        button.setTag(R.id.tag_glass_background, false)
        button.background = createRoundedDrawable(if (active) menuActiveFillColor else menuButtonColor,
            if (active) menuActiveStrokeColor else menuButtonStrokeColor, resolveDimensionAttr(R.attr.themeRadiusReaderButton, 18))
        button.setTextColor(if (active) menuActiveTextColor else menuMutedTextColor)
    }

    fun setControlsVisible(visible: Boolean) {
        if (visible && ::paging.isInitialized) paging.clearStableSimulationCoverForLiveView()
        if (visible) { state.pendingTapPagingDelta = 0; applyMenuLayoutMode() } else activity.dismissReaderPopupImmediate()
        if (state.controlsVisible == visible) {
            if (visible) { scheduleAutoHide(); views.readerRoot.post(::updateReaderLayoutInsets) } else runtime.mainHandler.removeCallbacks(autoHideRunnable)
            suppressInsetDrivenReflowTemporarily()
            updateSystemBarsVisibility(visible)
            return
        }
        state.controlsVisible = visible
        animatePanel(views.hudTopContainer, !visible, -ui.dp(12).toFloat())
        animatePanel(views.hudBottomContainer, !visible, ui.dp(12).toFloat())
        animatePanel(views.menuTopPanel, visible, -ui.dp(18).toFloat())
        animatePanel(views.menuInfoPanel, visible, ui.dp(14).toFloat())
        animatePanel(views.menuBottomPanel, visible, ui.dp(20).toFloat())
        suppressInsetDrivenReflowTemporarily()
        updateSystemBarsVisibility(visible)
        if (visible) { scheduleAutoHide(); views.readerRoot.post(::updateReaderLayoutInsets) }
        else { runtime.mainHandler.removeCallbacks(autoHideRunnable); if (::paging.isInitialized) paging.schedulePagingSnapshotWarmup() }
    }

    fun scheduleAutoHide() {
        cancelAutoHide()
        if (!state.controlsVisible || !runtime.settingsStore.isReaderMenuAutoHideEnabled) return
        runtime.mainHandler.postDelayed(autoHideRunnable, MENU_AUTO_HIDE_DELAY_MS)
    }

    fun cancelAutoHide() = runtime.mainHandler.removeCallbacks(autoHideRunnable)

    @Suppress("DEPRECATION")
    fun updateSystemBarsVisibility(showSystemBars: Boolean) {
        val window = activity.window
        val decorView = window.decorView
        var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        if (!isDarkReaderUi()) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        if (!showSystemBars) flags = flags or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        decorView.systemUiVisibility = flags
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) decorView.windowInsetsController?.let { controller ->
            controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            val types = WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()
            if (showSystemBars) controller.show(types) else controller.hide(types)
        }
    }

    fun isInsideView(event: MotionEvent, view: View): Boolean {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return event.rawX >= location[0] && event.rawX <= location[0] + view.width && event.rawY >= location[1] && event.rawY <= location[1] + view.height
    }

    private fun updateFrameLayoutMargins(view: View, left: Int, top: Int, right: Int, bottom: Int) {
        val params = view.layoutParams as FrameLayout.LayoutParams
        params.leftMargin = left; params.topMargin = top; params.rightMargin = right; params.bottomMargin = bottom
        view.layoutParams = params
    }

    private fun applyReaderMenuSurfaces() {
        applyReaderMenuPanel(views.menuTopPanel); applyReaderMenuPanel(views.menuInfoPanel); applyReaderMenuPanel(views.menuBottomPanel)
        applyReaderMenuHierarchy(views.menuTopPanel); applyReaderMenuHierarchy(views.menuInfoPanel); applyReaderMenuHierarchy(views.menuBottomPanel)
        styleReaderMenuButton(views.ttsButton, tts?.isActive() == true)
        styleReaderMenuButton(views.autoPageButton, autoPage?.isActive() == true)
        styleReaderMenuButton(views.themeToggleButton, isDarkReaderUi())
    }

    private fun isDarkReaderUi() = ThemeModeHelper.MODE_DARK == ThemeModeHelper.getResolvedReaderBucket(activity)

    private fun applyReaderMenuPanel(panel: View?) {
        panel ?: return
        panel.setTag(R.id.tag_glass_background, false)
        panel.background = createRoundedDrawable(menuPanelColor, menuPanelStrokeColor, resolveDimensionAttr(R.attr.themeRadiusReaderPanel, 24))
    }

    private fun applyReaderMenuHierarchy(view: View?) {
        view ?: return
        when (view) {
            is Button -> styleReaderMenuButton(view, false)
            is TextView -> view.setTextColor(if (isMutedMenuText(view)) menuMutedTextColor else menuTextColor)
        }
        if (view is ViewGroup) for (index in 0 until view.childCount) applyReaderMenuHierarchy(view.getChildAt(index))
    }

    private fun isMutedMenuText(view: View) = view.id == R.id.text_page_meta
    private fun createRoundedDrawable(fillColor: Int, strokeColor: Int, radiusPx: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; setColor(fillColor); cornerRadius = radiusPx; setStroke(max(1, ui.dp(1)), strokeColor)
    }
    private fun resolveDimensionAttr(attrResId: Int, fallbackDp: Int): Float {
        val value = TypedValue()
        return if (activity.theme.resolveAttribute(attrResId, value, true) && value.type == TypedValue.TYPE_DIMENSION)
            value.getDimension(activity.resources.displayMetrics) else ui.dp(fallbackDp).toFloat()
    }
    private fun colorOrDefault(color: Int, fallback: Int) = if (color == 0) fallback else color
    private fun opaqueColor(color: Int) = Color.rgb(Color.red(color), Color.green(color), Color.blue(color))
    private fun shiftSurfaceColor(color: Int, amount: Float) = blendColors(color, if (isDarkColor(color)) Color.WHITE else Color.BLACK, amount)
    private fun ensureReadableText(preferredColor: Int, backgroundColor: Int, minimumContrast: Double): Int {
        val preferred = opaqueColor(preferredColor)
        if (contrastRatio(preferred, backgroundColor) >= minimumContrast) return preferred
        val darkText = 0xFF181D26.toInt(); val lightText = 0xFFFFFFFF.toInt()
        return if (contrastRatio(darkText, backgroundColor) >= contrastRatio(lightText, backgroundColor)) darkText else lightText
    }
    private fun blendColors(fromColor: Int, toColor: Int, amount: Float): Int {
        val safeAmount = amount.coerceIn(0f, 1f); val inverse = 1f - safeAmount
        return Color.rgb((Color.red(fromColor) * inverse + Color.red(toColor) * safeAmount).roundToInt(),
            (Color.green(fromColor) * inverse + Color.green(toColor) * safeAmount).roundToInt(),
            (Color.blue(fromColor) * inverse + Color.blue(toColor) * safeAmount).roundToInt())
    }
    private fun isDarkColor(color: Int) = relativeLuminance(color) < 0.32
    private fun contrastRatio(firstColor: Int, secondColor: Int): Double {
        val first = relativeLuminance(firstColor) + 0.05; val second = relativeLuminance(secondColor) + 0.05
        return max(first, second) / min(first, second)
    }
    private fun relativeLuminance(color: Int): Double = 0.2126 * linearizeColorChannel(Color.red(color) / 255.0) +
        0.7152 * linearizeColorChannel(Color.green(color) / 255.0) + 0.0722 * linearizeColorChannel(Color.blue(color) / 255.0)
    private fun linearizeColorChannel(value: Double) = if (value <= 0.03928) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)

    private fun applyHudSlot(textView: TextView?, type: String?) = applyHudSlot(textView, type, state.currentChapterIndex, state.currentPageIndex)
    private fun applyHudSlot(textView: TextView?, type: String?, chapterIndex: Int, pageIndex: Int) {
        textView ?: return
        val text = hudTextForSlot(type, chapterIndex, pageIndex)
        textView.text = text
        textView.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }
    private fun hideHudSlot(textView: TextView?) { textView ?: return; textView.text = ""; textView.visibility = View.GONE }
    private fun hudTextForSlot(type: String?, chapterIndex: Int, pageIndex: Int): String = when (type) {
        "title" -> currentBookTitle(); "chapter" -> chapterTitleFor(chapterIndex); "title_chapter" -> titleOrChapterHudTextFor(chapterIndex, pageIndex)
        "time" -> currentTimeText(); "battery" -> currentBatteryText(); "chapter_page" -> chapterPageTextFor(chapterIndex, pageIndex)
        "book_progress" -> bookProgressTextFor(chapterIndex, pageIndex)
        "page_and_progress" -> joinHudSegments(chapterPageTextFor(chapterIndex, pageIndex), bookProgressPercentTextFor(chapterIndex, pageIndex), " · ")
        "time_and_battery" -> joinHudSegments(currentTimeText(), currentBatteryText(), " · ")
        else -> ""
    }
    private fun currentBookTitle() = trimToEmpty(state.book?.title)
    private fun currentChapterTitle() = chapterTitleFor(state.currentChapterIndex)
    private fun chapterTitleFor(chapterIndex: Int) = if (chapterIndex !in state.chapters.indices) "" else trimToEmpty(state.chapters[chapterIndex].title)
    private fun currentTimeText() = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
    private fun currentTitleOrChapterHudText() = titleOrChapterHudTextFor(state.currentChapterIndex, state.currentPageIndex)
    private fun titleOrChapterHudTextFor(chapterIndex: Int, pageIndex: Int): String {
        val bookTitle = currentBookTitle(); val chapterTitle = chapterTitleFor(chapterIndex)
        return if (pageIndex == 0) bookTitle.ifEmpty { chapterTitle } else chapterTitle.ifEmpty { bookTitle }
    }
    private fun currentBatteryText() = if (state.currentBatteryLevel >= 0) "${state.currentBatteryLevel}%" else ""
    private fun currentChapterPageText() = chapterPageTextFor(state.currentChapterIndex, state.currentPageIndex)

    private fun chapterPageTextFor(chapterIndex: Int, pageIndex: Int): String {
        if (state.chapters.isEmpty()) return ""
        val safeChapterIndex = ui.clamp(chapterIndex, 0, state.chapters.size - 1)
        val safePageIndex = max(pageIndex, 0)
        val safePageCount = max(content.getKnownPageCountForChapter(safeChapterIndex), 1)
        val pageCountComplete = content.isPageCountCompleteForChapter(safeChapterIndex)
        val pagesPerScreen = ReaderDisplayModeHelper.pagesPerScreen(activity, runtime.settingsStore, views.pageStage.width, views.pageStage.height)
        if (!pageCountComplete) {
            val startPage = max(safePageIndex + 1, 1); val endPage = max(startPage, safePageIndex + pagesPerScreen)
            return if (pagesPerScreen > 1 && endPage > startPage) String.format(Locale.SIMPLIFIED_CHINESE, "第 %d-%d 页 / 计算中", startPage, endPage)
            else String.format(Locale.SIMPLIFIED_CHINESE, "第 %d 页 / 计算中", startPage)
        }
        val startPage = min(safePageIndex + 1, safePageCount); val endPage = min(safePageIndex + pagesPerScreen, safePageCount)
        return if (pagesPerScreen > 1 && endPage > startPage) String.format(Locale.SIMPLIFIED_CHINESE, "第 %d-%d/%d 页", startPage, endPage, safePageCount)
        else String.format(Locale.SIMPLIFIED_CHINESE, "第 %d/%d 页", startPage, safePageCount)
    }

    private fun isLandscapeHudMode() = views.pageStage.width > 0 && views.pageStage.height > 0 && views.pageStage.width > views.pageStage.height
    private fun currentBookProgressPercentText() = "${fetchCurrentProgressPercent()}%"
    private fun currentBookProgressText() = "全书 ${currentBookProgressPercentText()}"
    private fun bookProgressPercentTextFor(chapterIndex: Int, pageIndex: Int) =
        if (chapterIndex == state.currentChapterIndex && pageIndex == state.currentPageIndex) currentBookProgressPercentText() else "${fetchProgressPercentFor(chapterIndex, pageIndex)}%"
    private fun bookProgressTextFor(chapterIndex: Int, pageIndex: Int) = "全书 ${bookProgressPercentTextFor(chapterIndex, pageIndex)}"
    private fun fetchProgressPercentFor(chapterIndex: Int, pageIndex: Int): Int {
        if (state.book == null || state.chapters.isEmpty()) return 0
        val safeChapterIndex = ui.clamp(chapterIndex, 0, state.chapters.size - 1)
        return content.bookProgressPercentFor(safeChapterIndex, chapterOffsetForPage(safeChapterIndex, pageIndex)).roundToInt()
    }
    private fun chapterOffsetForPage(chapterIndex: Int, pageIndex: Int): Int {
        val pages = content.getPagesForChapter(chapterIndex)
        if (pages.isNullOrEmpty()) return 0
        return max(0, pages[ui.clamp(pageIndex, 0, pages.size - 1)].start)
    }
    private fun joinHudSegments(first: String?, second: String?, divider: String): String = if (first.isNullOrEmpty()) second ?: "" else if (second.isNullOrEmpty()) first else first + divider + second
    private fun trimToEmpty(value: String?) = value?.trim() ?: ""
    private fun captureHudSlotState(textView: TextView?) = textView?.let { HudSlotSnapshot(it.text, it.visibility) }
    private fun restoreHudSlotState(textView: TextView?, snapshot: HudSlotSnapshot?) { if (textView != null && snapshot != null) { textView.text = snapshot.text; textView.visibility = snapshot.visibility } }

    class HudSnapshotState internal constructor(
        internal val topLeft: HudSlotSnapshot?, internal val topCenter: HudSlotSnapshot?, internal val topRight: HudSlotSnapshot?,
        internal val bottomLeft: HudSlotSnapshot?, internal val bottomCenter: HudSlotSnapshot?, internal val bottomRight: HudSlotSnapshot?,
    )
    internal class HudSlotSnapshot(val text: CharSequence, val visibility: Int)

    private fun suppressInsetDrivenReflowTemporarily() { state.suppressInsetReflowUntilUptimeMs = SystemClock.uptimeMillis() + INSET_REFLOW_SUPPRESS_WINDOW_MS }
    private fun shouldSuppressInsetDrivenReflow() = SystemClock.uptimeMillis() < state.suppressInsetReflowUntilUptimeMs
    private fun animatePanel(view: View, show: Boolean, hiddenTranslationY: Float) {
        view.animate().cancel()
        if (show) {
            view.visibility = View.VISIBLE; view.alpha = 0f; view.translationY = hiddenTranslationY
            view.animate().alpha(1f).translationY(0f).setDuration(220L).start(); return
        }
        if (view.visibility != View.VISIBLE) return
        view.animate().alpha(0f).translationY(hiddenTranslationY).setDuration(180L).withEndAction {
            if (!state.controlsVisible) { view.visibility = View.GONE; view.translationY = 0f }
        }.start()
    }

    companion object {
        private const val MENU_AUTO_HIDE_DELAY_MS = 2500L
        private const val INSET_REFLOW_SUPPRESS_WINDOW_MS = 450L
    }
}
