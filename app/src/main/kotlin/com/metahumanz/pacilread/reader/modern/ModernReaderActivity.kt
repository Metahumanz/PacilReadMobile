package com.metahumanz.pacilread.reader.modern

import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.os.BatteryManager
import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.WindowCompat
import com.metahumanz.pacilread.R
import com.metahumanz.pacilread.ReadingStatsActivity
import com.metahumanz.pacilread.model.BookRecord
import com.metahumanz.pacilread.model.BookmarkRecord
import com.metahumanz.pacilread.reader.PageSlice
import com.metahumanz.pacilread.reader.modern.content.ReaderContentController
import com.metahumanz.pacilread.reader.modern.dialog.ReaderDialogSupport
import com.metahumanz.pacilread.reader.modern.dialog.ReaderLibraryDialogs
import com.metahumanz.pacilread.reader.modern.dialog.ReaderOptionsDialogController
import com.metahumanz.pacilread.reader.modern.dialog.ReaderStyleDialogController
import com.metahumanz.pacilread.reader.modern.paging.ReaderNavigationController
import com.metahumanz.pacilread.reader.modern.paging.ReaderPagingAnimator
import com.metahumanz.pacilread.reader.modern.playback.ReaderAutoPageController
import com.metahumanz.pacilread.reader.modern.selection.ReaderTextSelectionController
import com.metahumanz.pacilread.reader.modern.stats.ReaderReadingStatsTracker
import com.metahumanz.pacilread.reader.modern.tts.ReaderTtsController
import com.metahumanz.pacilread.reader.modern.ui.ReaderChromeController
import com.metahumanz.pacilread.reader.modern.ui.ReaderStyleController
import com.metahumanz.pacilread.stats.ReadingStatsUtils
import com.metahumanz.pacilread.storage.SettingsStore
import com.metahumanz.pacilread.theme.ThemedReaderActivity
import com.metahumanz.pacilread.ui.ActivityTransitionCompat
import com.metahumanz.pacilread.ui.LaunchSourceTransition
import com.metahumanz.pacilread.ui.PredictiveBackScaleController
import com.metahumanz.pacilread.ui.ScreenCornerClipper
import java.util.Locale
import java.util.UUID

open class ModernReaderActivity : ThemedReaderActivity() {
    private lateinit var runtime: ReaderRuntime
    private lateinit var views: ReaderViewRefs
    private lateinit var state: ReaderSessionState
    private lateinit var ui: ReaderUiUtils
    private lateinit var dialogSupport: ReaderDialogSupport
    private lateinit var chrome: ReaderChromeController
    private lateinit var content: ReaderContentController
    private lateinit var navigation: ReaderNavigationController
    private lateinit var paging: ReaderPagingAnimator
    private lateinit var style: ReaderStyleController
    private lateinit var autoPage: ReaderAutoPageController
    private lateinit var tts: ReaderTtsController
    private lateinit var selection: ReaderTextSelectionController
    private lateinit var libraryDialogs: ReaderLibraryDialogs
    private lateinit var styleDialogs: ReaderStyleDialogController
    private lateinit var optionsDialogs: ReaderOptionsDialogController
    private lateinit var readingStatsTracker: ReaderReadingStatsTracker
    private var gestureDetector: GestureDetector? = null
    private var sysMetricsReceiver: BroadcastReceiver? = null
    private var readerPopupWindow: PopupWindow? = null
    private var readerPopupDismissingByCode = false
    private var readerExitFinishing = false
    private var readerExitProgressPersisted = false
    private var readerEnterTransitionActive = false
    private var readerEnterAnimationStarted = false
    private var readerExitFromBackGesture = false
    @Volatile private var readerDestroyed = false
    private var readerForegroundAnimator: ValueAnimator? = null
    private var readerEnterForegroundFadeRunnable: Runnable? = null
    private var launchSource: LaunchSourceTransition.Source? = null
    private var lastScrollPageTurnTime = 0L
    private var remoteProgressBannerTouch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyReaderOrientationPreference()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_reader)

        runtime = ReaderRuntime(this)
        state = ReaderSessionState()
        readingStatsTracker = ReaderReadingStatsTracker(runtime, state)
        state.pagingTouchSlop = ViewConfiguration.get(this).scaledTouchSlop
        state.bookId = intent.getLongExtra("book_id", -1L)
        launchSource = LaunchSourceTransition.fromIntentSource(intent)
        state.requestedChapterOrderIndex = intent.getIntExtra("bookmark_chapter_order_index", -1)
        state.requestedChapterOffset = intent.getIntExtra("bookmark_chapter_offset", -1)
        if (savedInstanceState != null) {
            state.restoredChapterIndex = savedInstanceState.getInt("restored_chapter_index", -1)
            state.restoredPageIndex = savedInstanceState.getInt("restored_page_index", -1)
            state.restoredProgressOffset = savedInstanceState.getInt("restored_progress_offset", -1)
        }

        views = ReaderViewRefs.bind(this)
        ui = ReaderUiUtils(this)
        initializeControllers()

        chrome.configureReaderWindow()
        chrome.applyEdgeToEdgeInsets()
        style.applyReaderSettings()
        setupGestures()
        setupControls()
        installPredictiveBack()

        val restoringReaderInstance = savedInstanceState != null
        val useFluidEnter = !restoringReaderInstance &&
            hasLaunchSource() &&
            com.metahumanz.pacilread.ui.TransitionMotionModeHelper.isFluidMode(runtime.settingsStore)
        if (useFluidEnter) {
            readerEnterTransitionActive = true
            ActivityTransitionCompat.overrideOpen(this, 0, 0)
            views.readerRoot.visibility = View.INVISIBLE
            views.readerRoot.alpha = 1f
            views.readerRoot.viewTreeObserver.addOnGlobalLayoutListener(object :
                android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    views.readerRoot.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    startFluidEnterAnimation()
                }
            })
            views.readerRoot.postDelayed({ startFluidEnterAnimation() }, 100L)
        }

        state.sessionStartTime = System.currentTimeMillis()
        state.sessionStartOffset = 0

        views.pageBodyCurrent.text = "正在载入..."
        if (useFluidEnter) {
            content.setDeferReflow(true)
        }
        content.loadBook()
    }

    override fun onResume() {
        super.onResume()
        applyReaderOrientationPreference()
        if (::readingStatsTracker.isInitialized) {
            readingStatsTracker.resume()
        }
        if (::views.isInitialized) {
            views.readerRoot.invalidateOutline()
        }
        if (::chrome.isInitialized && ::state.isInitialized) {
            chrome.updateSystemBarsVisibility(state.controlsVisible)
            chrome.applyGlassOpacity()
            if (state.controlsVisible) {
                chrome.scheduleAutoHide()
            } else {
                chrome.cancelAutoHide()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (::tts.isInitialized) tts.bindPlaybackService()
    }

    fun applyReaderUiThemeWithoutRecreate() {
        applyResolvedReaderThemeWithoutRecreate()
        if (::style.isInitialized) {
            style.applyReaderSettings()
        }
        if (::chrome.isInitialized) {
            chrome.updateUiAfterPageChange()
            chrome.updateSystemBarsVisibility(::state.isInitialized && state.controlsVisible)
        }
    }

    private fun applyReaderOrientationPreference() {
        when (SettingsStore(this).readerOrientationMode) {
            "portrait" -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "landscape" -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!::state.isInitialized) {
            return
        }
        val position = if (::content.isInitialized) content.captureCurrentReadingPosition() else null
        outState.putInt("restored_chapter_index", position?.chapterIndex ?: state.currentChapterIndex)
        outState.putInt("restored_page_index", position?.pageIndex ?: state.currentPageIndex)
        outState.putInt("restored_progress_offset", position?.chapterOffset ?: -1)
    }

    override fun onPause() {
        super.onPause()
        if (::readingStatsTracker.isInitialized) {
            readingStatsTracker.pause()
        }
        if (::chrome.isInitialized) {
            chrome.cancelAutoHide()
        }
        if (!readerExitProgressPersisted) {
            persistReaderProgress(true)
        }
        if (::paging.isInitialized) {
            paging.cancelInteractiveAnimator()
            paging.cancelInteractivePaging()
            paging.removeWarmupCallbacks()
        }
        if (::autoPage.isInitialized) {
            autoPage.stopAutoPage()
        }
        if (::content.isInitialized) {
            content.cancelPendingReflow()
        }
        if (::state.isInitialized) {
            state.pendingTapPagingDelta = 0
        }
    }

    override fun onStop() {
        if (!readerExitProgressPersisted) {
            persistReaderProgress(true)
        }
        if (::tts.isInitialized) tts.unbindPlaybackService()
        super.onStop()
    }

    override fun onDestroy() {
        readerDestroyed = true
        if (::content.isInitialized) {
            content.releasePendingRemoteProgressSuggestion()
        }
        super.onDestroy()
        cancelReaderForegroundTransition()
        sysMetricsReceiver?.let { receiver ->
            try {
                unregisterReceiver(receiver)
            } catch (error: IllegalArgumentException) {
                Log.d(TAG, "System metrics receiver was already unregistered", error)
            }
            sysMetricsReceiver = null
        }
        if (::readingStatsTracker.isInitialized) {
            readingStatsTracker.shutdown()
        }
        if (::tts.isInitialized) tts.unbindPlaybackService()
        if (::runtime.isInitialized) {
            runtime.shutdown()
        }
        if (::paging.isInitialized) {
            paging.cancelInteractiveAnimator()
            paging.recyclePagingSnapshots()
        }
    }

    val isReaderActive: Boolean
        get() = !readerDestroyed && !isFinishing && !isDestroyed

    val isReaderEnterTransitionActive: Boolean
        get() = readerEnterTransitionActive

    fun runOnReaderUiThread(action: Runnable?) {
        if (action == null || !isReaderActive) {
            return
        }
        runOnUiThread {
            if (!isReaderActive) {
                return@runOnUiThread
            }
            try {
                action.run()
            } catch (error: RuntimeException) {
                Log.w(TAG, "Reader UI task failed after lifecycle change", error)
            }
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            markReadingActivity()
            remoteProgressBannerTouch = views.remoteProgressBanner.visibility == View.VISIBLE &&
                chrome.isInsideView(event, views.remoteProgressBanner)
        }
        if (remoteProgressBannerTouch) {
            val handled = super.dispatchTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                remoteProgressBannerTouch = false
            }
            return handled
        }
        if (::selection.isInitialized && selection.handleTouchEvent(event)) {
            return true
        }
        if (paging.handleReaderPagingTouchEvent(event)) {
            return true
        }
        gestureDetector?.onTouchEvent(event)
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (paging.handleReaderVolumeKeyEvent(event)) {
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && !state.controlsVisible) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_PAGE_UP -> {
                    state.lastTapY = -1f
                    navigation.pageUp()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_PAGE_DOWN,
                KeyEvent.KEYCODE_SPACE,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    state.lastTapY = -1f
                    navigation.pageDown()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_CLASS_POINTER != 0 &&
            event.actionMasked == MotionEvent.ACTION_SCROLL &&
            !state.controlsVisible
        ) {
            val vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (Math.abs(vScroll) > 0.5f) {
                val now = System.currentTimeMillis()
                if (now - lastScrollPageTurnTime > 300L) {
                    lastScrollPageTurnTime = now
                    state.lastTapY = -1f
                    if (vScroll < 0) {
                        navigation.pageDown()
                    } else {
                        navigation.pageUp()
                    }
                }
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (::selection.isInitialized && selection.onActivityResult(requestCode, resultCode, data)) {
            return
        }
        if (resultCode != RESULT_OK || data?.data == null) {
            return
        }
        if (requestCode == REQUEST_PICK_BACKGROUND) {
            data.data?.let { style.attachBackground(it) }
        }
    }

    private fun hasLaunchSource(): Boolean = launchSource?.bounds() != null

    private fun scheduleReaderEnterForegroundFade() {
        cancelScheduledReaderEnterForegroundFade()
        if (!::runtime.isInitialized) {
            finishReaderEnterForegroundFade()
            return
        }
        val delayMs = Math.max(0L, ENTER_TRANSITION_DURATION_MS - ENTER_TEXT_FADE_DURATION_MS - 18L)
        readerEnterForegroundFadeRunnable = Runnable { startReaderEnterForegroundFade() }
        runtime.mainHandler.postDelayed(readerEnterForegroundFadeRunnable!!, delayMs)
    }

    private fun startReaderEnterForegroundFade() {
        readerEnterForegroundFadeRunnable = null
        animateReaderTransitionForegroundToAlpha(1f, ENTER_TEXT_FADE_DURATION_MS, null)
    }

    /** 启动流动进入动画。前提：readerRoot 已完成 layout 且 bounds 有效。 */
    private fun startFluidEnterAnimation(): Boolean {
        if (readerEnterAnimationStarted || readerExitFinishing) return false
        if (!::views.isInitialized) return false
        if (views.readerRoot.width <= 0 || views.readerRoot.height <= 0) return false
        readerEnterAnimationStarted = true
        Log.d(TAG, "[时序] 流体进入动画开始")
        views.readerRoot.visibility = View.VISIBLE
        views.readerRoot.alpha = 0f
        val started = LaunchSourceTransition.animateEnterFromSource(
            views.readerRoot,
            launchSource,
            LaunchSourceTransition.Options.defaults()
                .withDuration(ENTER_TRANSITION_DURATION_MS)
                .withEnterSnapshotOverlay(true)
                .withEnterContentFade(true)
                .withEnterAnimatesLiveContent(false)
                .withSnapshotFadeStartFraction(0.72f)
                .withInterpolator(android.view.animation.PathInterpolator(0.25f, 0.1f, 0.25f, 1.0f)),
            Runnable { finishReaderEnterForegroundFade() },
        )
        if (started) {
            views.readerRoot.postOnAnimation {
                if (::content.isInitialized) {
                    content.capturePaginationSnapshot()
                    content.prewarmChapterTextAfterSnapshot()
                }
            }
        } else {
            finishReaderEnterForegroundFade()
        }
        return started
    }

    private fun finishReaderEnterForegroundFade() {
        if (!isReaderActive) {
            return
        }
        Log.d(TAG, "[时序] 动画结束 finishReaderEnterForegroundFade - cacheHit=" + (::content.isInitialized && content.isCacheHit()))
        readerEnterTransitionActive = false
        cancelScheduledReaderEnterForegroundFade()
        if (::content.isInitialized) {
            content.performDeferredInitialReflow {
                if (::paging.isInitialized) {
                    paging.schedulePagingSnapshotWarmup()
                }
            }
        }
    }

    private fun fadeReaderForegroundForExit(onComplete: Runnable?) {
        cancelScheduledReaderEnterForegroundFade()
        animateReaderTransitionForegroundToAlpha(0f, EXIT_TEXT_FADE_DURATION_MS, onComplete)
    }

    private fun cancelReaderForegroundTransition() {
        cancelScheduledReaderEnterForegroundFade()
        readerForegroundAnimator?.cancel()
        readerForegroundAnimator = null
    }

    private fun cancelScheduledReaderEnterForegroundFade() {
        val scheduled = readerEnterForegroundFadeRunnable
        if (scheduled != null && ::runtime.isInitialized) {
            runtime.mainHandler.removeCallbacks(scheduled)
        }
        readerEnterForegroundFadeRunnable = null
    }

    private fun animateReaderTransitionForegroundToAlpha(
        targetAlpha: Float,
        durationMs: Long,
        onComplete: Runnable?,
    ) {
        readerForegroundAnimator?.cancel()
        readerForegroundAnimator = null
        val layers = readerTransitionForegroundLayers()
        val firstLayer = firstAvailableLayer(layers)
        if (firstLayer == null) {
            onComplete?.run()
            return
        }
        val startAlpha = firstLayer.alpha
        if (durationMs <= 0L || Math.abs(startAlpha - targetAlpha) < 0.01f) {
            setReaderTransitionForegroundAlpha(targetAlpha)
            onComplete?.run()
            return
        }
        val animator = ValueAnimator.ofFloat(startAlpha, targetAlpha)
        readerForegroundAnimator = animator
        animator.duration = durationMs
        animator.interpolator = android.view.animation.DecelerateInterpolator()
        animator.addUpdateListener { animation ->
            setReaderTransitionForegroundAlpha(animation.animatedValue as Float)
        }
        animator.addListener(object : android.animation.AnimatorListenerAdapter() {
            private var canceled = false

            override fun onAnimationCancel(animation: android.animation.Animator) {
                canceled = true
            }

            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (readerForegroundAnimator === animation) {
                    readerForegroundAnimator = null
                }
                if (!canceled) {
                    setReaderTransitionForegroundAlpha(targetAlpha)
                    onComplete?.run()
                }
            }
        })
        animator.start()
    }

    private fun setReaderTransitionForegroundAlpha(alpha: Float) {
        for (layer in readerTransitionForegroundLayers()) {
            layer.alpha = alpha
        }
    }

    private fun firstAvailableLayer(layers: Array<View>): View? {
        for (layer in layers) {
            return layer
        }
        return null
    }

    private fun readerTransitionForegroundLayers(): Array<View> {
        if (!::views.isInitialized) {
            return emptyArray()
        }
        return arrayOf(
            views.pageStage,
            views.hudTopContainer,
            views.hudBottomContainer,
            views.menuTopPanel,
            views.menuInfoPanel,
            views.menuBottomPanel,
        )
    }

    private fun initializeControllers() {
        dialogSupport = ReaderDialogSupport(this, runtime, ui)
        chrome = ReaderChromeController(this, runtime, views, state, ui)
        content = ReaderContentController(this, runtime, views, state, ui)
        navigation = ReaderNavigationController(this, runtime, views, state, ui)
        paging = ReaderPagingAnimator(this, runtime, views, state, ui)
        style = ReaderStyleController(this, runtime, views, state, ui)
        autoPage = ReaderAutoPageController(this, runtime, views, state, ui, dialogSupport)
        tts = ReaderTtsController(this, runtime, views, state, ui, dialogSupport)
        libraryDialogs = ReaderLibraryDialogs(this, runtime, state, ui, dialogSupport, content, navigation)
        styleDialogs = ReaderStyleDialogController(this, runtime, state, ui, dialogSupport, content, navigation, style, chrome)
        optionsDialogs = ReaderOptionsDialogController(this, runtime, state, ui, dialogSupport, content, navigation, style, chrome)
        selection = ReaderTextSelectionController(this, runtime, views, state, ui, content)

        content.attachControllers(navigation, style, paging, chrome)
        navigation.attachControllers(content, paging, chrome)
        paging.attachControllers(navigation, content, chrome)
        autoPage.attachControllers(navigation, chrome)
        tts.attachControllers(navigation, content, paging, chrome)
        selection.attachControllers(libraryDialogs, tts)
        chrome.attachControllers(content, paging, autoPage, tts)
        style.attachControllers(chrome, paging, content, tts)
    }

    private fun setupControls() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (Intent.ACTION_BATTERY_CHANGED == intent.action) {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    if (level >= 0 && scale > 0) {
                        state.currentBatteryLevel = level * 100 / scale
                    }
                }
                chrome.updateReaderHud()
            }
        }
        sysMetricsReceiver = receiver
        val filter = IntentFilter()
        filter.addAction(Intent.ACTION_TIME_TICK)
        filter.addAction(Intent.ACTION_BATTERY_CHANGED)
        registerReceiver(receiver, filter)

        findViewById<View>(R.id.button_back).setOnClickListener { finishReaderActivity() }
        findViewById<View>(R.id.button_prev_chapter).setOnClickListener {
            navigation.openChapterFromStart(state.currentChapterIndex - 1, true, -1)
        }
        findViewById<View>(R.id.button_next_chapter).setOnClickListener {
            navigation.openChapterFromStart(state.currentChapterIndex + 1, true, 1)
        }
        findViewById<View>(R.id.button_toc).setOnClickListener { v ->
            dialogSupport.setNextDismissSource(v)
            libraryDialogs.showTocDialog()
        }
        findViewById<View>(R.id.button_search).setOnClickListener { v ->
            dialogSupport.setNextDismissSource(v)
            libraryDialogs.showSearchDialog()
        }
        findViewById<View>(R.id.button_bookmark).setOnClickListener { v ->
            dialogSupport.setNextDismissSource(v)
            showBookmarkDialog()
        }
        findViewById<View>(R.id.button_rules).setOnClickListener { v ->
            dialogSupport.setNextDismissSource(v)
            libraryDialogs.showRulesDialog()
        }
        findViewById<View>(R.id.button_style).setOnClickListener { v ->
            dialogSupport.setNextDismissSource(v)
            styleDialogs.showStyleDialog(REQUEST_PICK_BACKGROUND)
        }
        findViewById<View>(R.id.button_reader_options).setOnClickListener { v ->
            dialogSupport.setNextDismissSource(v)
            optionsDialogs.showReaderOptionsDialog()
        }
        views.themeToggleButton.setOnClickListener { chrome.toggleReaderUiTheme() }
        views.ttsButton.setOnClickListener { v ->
            dialogSupport.setNextDismissSource(v)
            tts.showTtsDialog()
        }
        views.autoPageButton.setOnClickListener { v ->
            dialogSupport.setNextDismissSource(v)
            autoPage.showAutoPageDialog()
        }
        views.moreButton.setOnClickListener {
            val showingPopup = readerPopupWindow
            if (showingPopup != null && showingPopup.isShowing) {
                animatePopupWaterfallClose(showingPopup) {
                    readerPopupDismissingByCode = true
                    showingPopup.dismiss()
                }
                return@setOnClickListener
            }

            views.moreButton.animate().rotation(-90f).setDuration(200).start()

            val pad = ui.dp(10)
            val gap = ui.dp(8)
            val btnPadH = ui.dp(14)
            val btnPadV = ui.dp(8)
            val rowHeight = ui.dp(40) + btnPadV * 2

            val scrollView = ScrollView(this)
            scrollView.clipToPadding = false
            scrollView.isVerticalScrollBarEnabled = false

            val popupContent = LinearLayout(this)
            popupContent.orientation = LinearLayout.VERTICAL
            popupContent.setBackgroundResource(R.drawable.bg_reader_popup_menu)
            popupContent.setPadding(pad, pad, pad, pad)
            scrollView.addView(popupContent)

            val popupWindow = PopupWindow(
                scrollView,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                false,
            )
            popupWindow.setBackgroundDrawable(
                android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT),
            )
            popupWindow.isOutsideTouchable = false
            popupWindow.setOnDismissListener {
                views.moreButton.animate().rotation(0f).setDuration(200).start()
                readerPopupDismissingByCode = false
                readerPopupWindow = null
            }
            readerPopupWindow = popupWindow

            val rows = arrayOf(
                arrayOf("搜索", "替换"),
                arrayOf("排版", "翻页"),
                arrayOf("听书", "书签"),
            )
            val rowLayouts = ArrayList<LinearLayout>()
            for (r in rows.indices) {
                val rowLayout = LinearLayout(this)
                rowLayout.orientation = LinearLayout.HORIZONTAL
                val rowLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                if (r > 0) {
                    rowLp.topMargin = gap
                }
                popupContent.addView(rowLayout, rowLp)
                rowLayouts.add(rowLayout)

                for (c in rows[r].indices) {
                    val item = rows[r][c]
                    val btn = Button(this)
                    btn.text = item
                    btn.isAllCaps = false
                    btn.minHeight = ui.dp(40)
                    btn.minWidth = 0
                    btn.setPadding(btnPadH, btnPadV, btnPadH, btnPadV)
                    chrome.styleReaderMenuButton(btn, false)
                    btn.setOnClickListener { itemView ->
                        val itemSource = LaunchSourceTransition.captureSource(itemView)
                        animatePopupWaterfallClose(popupWindow) {
                            readerPopupDismissingByCode = true
                            popupWindow.dismiss()
                            handleReaderPopupAction(item, itemSource)
                        }
                    }

                    val btnLp = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f,
                    )
                    if (c > 0) {
                        btnLp.leftMargin = gap
                    }
                    rowLayout.addView(btn, btnLp)
                }
            }

            popupWindow.showAsDropDown(views.moreButton, 0, 0, Gravity.END)
            animatePopupWaterfallOpen(popupContent, rowLayouts, rowHeight)
        }
        views.readerTitle.setOnClickListener { v -> openReadingStatsForCurrentBook(v) }
        views.pageStage.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val width = right - left
            val height = bottom - top
            val oldWidth = oldRight - oldLeft
            val oldHeight = oldBottom - oldTop
            if (width <= 0 || height <= 0 || (width == oldWidth && height == oldHeight)) {
                return@addOnLayoutChangeListener
            }
            val anchorOffset = content.currentCharOffset()
            chrome.updateReaderHud()
            if (state.book != null && state.chapters.isNotEmpty()) {
                content.scheduleReflowAfterLayout(state.currentChapterIndex, anchorOffset)
            }
        }

        val keepMenuAliveListener = View.OnTouchListener { _, event ->
            if (state.controlsVisible && event.actionMasked == MotionEvent.ACTION_DOWN) {
                chrome.scheduleAutoHide()
                val popup = readerPopupWindow
                if (popup != null && popup.isShowing) {
                    readerPopupDismissingByCode = true
                    popup.dismiss()
                }
            }
            false
        }
        views.menuTopPanel.setOnTouchListener(keepMenuAliveListener)
        views.menuInfoPanel.setOnTouchListener(keepMenuAliveListener)
        views.menuBottomPanel.setOnTouchListener(keepMenuAliveListener)
        views.progressSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) = Unit

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                chrome.setControlsVisible(true)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                if (runtime.settingsStore.readerSliderMode == "book") {
                    val chapterIndex = ui.clamp(seekBar.progress, 0, state.chapters.size - 1)
                    val direction = if (chapterIndex >= state.currentChapterIndex) 1 else -1
                    navigation.openChapterFromStart(chapterIndex, true, direction)
                    return
                }
                val direction = if (seekBar.progress >= state.currentPageIndex) 1 else -1
                navigation.showPage(state.currentChapterIndex, seekBar.progress, true, direction)
            }
        })
    }

    private fun installPredictiveBack() {
        if (!com.metahumanz.pacilread.ui.TransitionMotionModeHelper.isFluidMode(runtime.settingsStore)) {
            return
        }
        PredictiveBackScaleController.install(
            this,
            views.readerRoot,
            PredictiveBackScaleController.Profile.reader(),
            object : PredictiveBackScaleController.Delegate {
                override fun shouldAnimateBack(): Boolean = !hasReaderBackConsumer()

                override fun consumeBack(): Boolean = consumeReaderBack()

                override fun commitBack() {
                    finishReaderActivity()
                }

                override fun commitBackFromGesture(): Boolean {
                    readerExitFromBackGesture = true
                    return true
                }
            },
        )
    }

    override fun onBackPressed() {
        if (!com.metahumanz.pacilread.ui.TransitionMotionModeHelper.isFluidMode(runtime.settingsStore)) {
            if (consumeReaderBack()) return
            finishReaderActivity()
            return
        }
        super.onBackPressed()
    }

    private fun hasReaderBackConsumer(): Boolean =
        (::selection.isInitialized && selection.hasSelection()) || (::state.isInitialized && state.controlsVisible)

    private fun consumeReaderBack(): Boolean {
        if (::selection.isInitialized && selection.hasSelection()) {
            selection.clearSelection()
            return true
        }
        if (::state.isInitialized && state.controlsVisible) {
            chrome.setControlsVisible(false)
            return true
        }
        return false
    }

    fun finishReaderActivity() {
        if (readerExitFinishing) {
            return
        }
        readerExitFinishing = true
        readerExitProgressPersisted = persistReaderProgress(true)
        animateReaderExitToSource()
    }

    private fun animateReaderExitToSource() {
        if (!::views.isInitialized) {
            finishReaderActivityNow()
            return
        }
        fadeReaderForegroundForExit(Runnable { runReaderExitToSource() })
    }

    private fun runReaderExitToSource() {
        if (!::views.isInitialized) {
            finishReaderActivityNow()
            return
        }
        if (!com.metahumanz.pacilread.ui.TransitionMotionModeHelper.isFluidMode(runtime.settingsStore)) {
            animateReaderExitToCenter()
            return
        }
        val options = LaunchSourceTransition.Options.defaults()
            .withDuration(EXIT_TRANSITION_DURATION_MS)
            .withSnapshotFadeStartFraction(0.72f)
            .withExitScreenCornerClip(readerExitFromBackGesture)
        if (readerExitFromBackGesture && LaunchSourceTransition.animateExitToSourceWithClip(
                views.readerRoot,
                launchSource,
                options,
                Runnable { finishReaderActivityNow() },
            )
        ) {
            return
        }
        if (!readerExitFromBackGesture) {
            ScreenCornerClipper.setClipEnabled(views.readerRoot, false)
        }
        if (LaunchSourceTransition.animateExitToSource(
                views.readerRoot,
                launchSource,
                options,
                Runnable { finishReaderActivityNow() },
            )
        ) {
            return
        }
        animateReaderExitToCenter()
    }

    private fun animateReaderExitToCenter() {
        val minScale = PredictiveBackScaleController.READER_MIN_SCALE
        views.readerRoot.animate().cancel()
        views.readerRoot.animate()
            .scaleX(minScale)
            .scaleY(minScale)
            .alpha(0f)
            .translationX(0f)
            .translationY(0f)
            .setDuration(160L)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .withEndAction { finishReaderActivityNow() }
            .start()
    }

    private fun finishReaderActivityNow() {
        if (!readerExitProgressPersisted) {
            readerExitProgressPersisted = persistReaderProgress(false)
        }
        finish()
        ActivityTransitionCompat.overrideClose(this, 0, 0)
    }

    private fun persistReaderProgress(settlePaging: Boolean): Boolean {
        if (!::content.isInitialized) {
            return false
        }
        return try {
            if (settlePaging && ::paging.isInitialized) {
                paging.settleInterruptedPagingAnimation()
            }
            content.cancelPendingProgressSave()
            content.persistProgress()
            true
        } catch (error: RuntimeException) {
            Log.w(TAG, "Failed to persist reader progress", error)
            false
        }
    }

    private fun handleReaderPopupAction(item: String, source: LaunchSourceTransition.Source?) {
        views.moreButton.animate().rotation(0f).setDuration(200).start()
        when (item) {
            "搜索" -> {
                dialogSupport.setNextDismissSource(source)
                libraryDialogs.showSearchDialog()
            }
            "替换" -> {
                dialogSupport.setNextDismissSource(source)
                libraryDialogs.showRulesDialog()
            }
            "排版" -> {
                dialogSupport.setNextDismissSource(source)
                styleDialogs.showStyleDialog(REQUEST_PICK_BACKGROUND)
            }
            "翻页" -> {
                dialogSupport.setNextDismissSource(source)
                autoPage.showAutoPageDialog()
            }
            "听书" -> {
                dialogSupport.setNextDismissSource(source)
                tts.showTtsDialog()
            }
            "书签" -> {
                dialogSupport.setNextDismissSource(source)
                showBookmarkDialog()
            }
        }
    }

    private fun setupGestures() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (state.controlsVisible) {
                    if (chrome.isInsideView(e, views.menuTopPanel) ||
                        chrome.isInsideView(e, views.menuInfoPanel) ||
                        chrome.isInsideView(e, views.menuBottomPanel)
                    ) {
                        return false
                    }
                    chrome.setControlsVisible(false)
                    return true
                }
                val width = views.readerRoot.width.toFloat()
                val height = views.readerRoot.height.toFloat()
                val x = e.x
                val y = e.y
                val thirdW = width / 3f
                val thirdH = height / 3f
                val col = ui.clamp((x / thirdW).toInt(), 0, 2)
                val row = ui.clamp((y / thirdH).toInt(), 0, 2)
                if (col == 1 && row == 1) {
                    state.lastTapY = -1f
                    chrome.setControlsVisible(true)
                } else if (state.ttsActive && ::tts.isInitialized) {
                    val offset = bodyCharOffsetFromTouch(e)
                    if (offset >= 0) {
                        state.lastTapY = -1f
                        tts.startTtsFrom(state.currentChapterIndex, offset)
                    } else if (col == 0 || (col == 1 && row == 0)) {
                        state.lastTapY = y
                        navigation.requestTapPageTurn(-1)
                        resumeTtsAfterPageTurn()
                    } else {
                        state.lastTapY = y
                        navigation.requestTapPageTurn(1)
                        resumeTtsAfterPageTurn()
                    }
                } else if (col == 0 || (col == 1 && row == 0)) {
                    state.lastTapY = y
                    navigation.requestTapPageTurn(-1)
                } else {
                    state.lastTapY = y
                    navigation.requestTapPageTurn(1)
                }
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                if (state.controlsVisible || state.isAnimating || state.interactivePaging) {
                    return false
                }
                if (Math.abs(velocityX) > Math.abs(velocityY) * 1.3f && Math.abs(velocityX) > 700f) {
                    val flipped = if (velocityX < 0) {
                        navigation.pageDown()
                    } else {
                        navigation.pageUp()
                    }
                    if (flipped) {
                        resumeTtsAfterPageTurn()
                    }
                    return true
                }
                return false
            }
        })
    }

    fun markReadingActivity() {
        if (::readingStatsTracker.isInitialized) {
            val pageKey = if (::content.isInitialized) content.currentReadingStatsPageKey() else ""
            val visibleChars = if (::content.isInitialized) content.currentVisibleBodyCharCount() else 0
            readingStatsTracker.markActivity(pageKey, visibleChars)
        }
    }

    fun onReaderBookLoaded() {
        val book = state.book
        if (book != null && (book.readingStatus == null ||
                book.readingStatus!!.isEmpty() ||
                BookRecord.STATUS_UNREAD == book.readingStatus)
        ) {
            runtime.databaseHelper.markBookReadingIfUnread(book.id)
            book.readingStatus = BookRecord.STATUS_READING
        }
        if (::readingStatsTracker.isInitialized) {
            readingStatsTracker.bindBook(state.book)
        }
    }

    fun onReaderPageReadyForLaunchPreview() {
    }

    fun openReadingStatsForCurrentBook() {
        openReadingStatsForCurrentBook(views.readerTitle)
    }

    fun openReadingStatsForCurrentBook(sourceView: View?) {
        val book = state.book
        if (!runtime.settingsStore.isReadingTimeTrackingEnabled || book == null) {
            return
        }
        val intent = Intent(this, ReadingStatsActivity::class.java)
        intent.putExtra("book_id", book.id)
        LaunchSourceTransition.attach(intent, sourceView)
        startActivity(intent)
    }

    fun clearTextSelection() {
        if (::selection.isInitialized) {
            selection.clearSelection()
        }
    }

    fun ensureLivePageLayerForTextSelection() {
        if (::paging.isInitialized) {
            paging.clearStableSimulationCoverForLiveView()
        }
    }

    private fun bodyCharOffsetFromTouch(e: MotionEvent): Int {
        val loc = IntArray(2)
        views.pageBodyCurrent.getLocationOnScreen(loc)
        val localX = e.rawX - loc[0]
        val localY = e.rawY - loc[1]
        val viewOffset = views.pageBodyCurrent.offsetForTouch(localX, localY)
        if (viewOffset < 0) return -1
        val pages: List<PageSlice> = content.getPagesForChapter(state.currentChapterIndex)
        if (pages.isEmpty()) return -1
        val slice = pages[ui.clamp(state.currentPageIndex, 0, pages.size - 1)]
        val bodyStartInSlice = Math.max(slice.bodyStartInSlice, 0)
        return slice.start + Math.max(0, viewOffset - bodyStartInSlice)
    }

    private fun resumeTtsAfterPageTurn() {
        if (!state.ttsActive || !::tts.isInitialized) return
        val delayMs = paging.readerFlipDurationMs() + 60L
        runtime.mainHandler.postDelayed({
            if (!state.ttsActive || state.isAnimating || state.interactivePaging) return@postDelayed
            val pages: List<PageSlice> = content.getPagesForChapter(state.currentChapterIndex)
            if (pages.isEmpty()) return@postDelayed
            val firstVisibleOffset = pages[ui.clamp(state.currentPageIndex, 0, pages.size - 1)].start
            tts.startTtsFrom(state.currentChapterIndex, firstVisibleOffset)
        }, delayMs)
    }

    fun dismissReaderPopupImmediate() {
        val popup = readerPopupWindow
        if (popup != null && popup.isShowing) {
            readerPopupDismissingByCode = true
            popup.dismiss()
            views.moreButton.animate().rotation(0f).setDuration(200).start()
        }
    }

    private fun animatePopupWaterfallOpen(popupRoot: View?, rows: List<LinearLayout>, rowHeight: Int) {
        if (popupRoot != null) {
            popupRoot.scaleX = PredictiveBackScaleController.READER_MIN_SCALE
            popupRoot.scaleY = PredictiveBackScaleController.READER_MIN_SCALE
            popupRoot.alpha = 0f
            popupRoot.animate()
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(180)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }
        val staggerMs = 20
        val dropDistance = rowHeight + ui.dp(8)
        for (i in rows.indices) {
            val row = rows[i]
            row.translationY = (-dropDistance).toFloat()
            row.alpha = 0f
            row.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(120)
                .setStartDelay((i * staggerMs).toLong())
                .start()
        }
    }

    private fun animatePopupWaterfallClose(popupWindow: PopupWindow?, onComplete: Runnable?) {
        val contentView = popupWindow?.contentView ?: return
        val popupRoot = if (contentView is ScrollView) contentView.getChildAt(0) else contentView
        if (popupRoot !is ViewGroup) {
            popupWindow.dismiss()
            onComplete?.run()
            return
        }
        val popupContent = popupRoot
        val childCount = popupContent.childCount
        if (childCount == 0) {
            popupWindow.dismiss()
            onComplete?.run()
            return
        }
        popupContent.animate()
            .scaleX(PredictiveBackScaleController.READER_MIN_SCALE)
            .scaleY(PredictiveBackScaleController.READER_MIN_SCALE)
            .alpha(0f)
            .setDuration(130)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .start()
        val staggerMs = 18
        val riseDistance = ui.dp(40) + ui.dp(8)
        for (i in 0 until childCount) {
            val child = popupContent.getChildAt(i)
            if (child is LinearLayout) {
                child.animate()
                    .translationY((-riseDistance).toFloat())
                    .alpha(0f)
                    .setDuration(90)
                    .setStartDelay((i * staggerMs).toLong())
                    .start()
            }
        }
        val totalDuration = ((childCount - 1) * staggerMs + 90 + 20).toLong()
        popupContent.postDelayed({
            onComplete?.run()
        }, totalDuration)
    }

    private fun showBookmarkDialog() {
        val book = state.book
        if (book == null) {
            ui.showToast("书籍尚未载入")
            return
        }
        try {
            val bookmarks = runtime.databaseHelper.getBookmarksForBook(
                book.id,
                book.readingStatsKey,
            )
            renderBookmarkDialog(bookmarks)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Failed to load reader bookmarks", error)
            ui.showToast("打开书签失败")
        }
    }

    private fun renderBookmarkDialog(bookmarks: List<BookmarkRecord>?) {
        val contentView = android.view.LayoutInflater.from(this)
            .inflate(R.layout.dialog_bookmarks, null, false)
        val addButton = contentView.findViewById<Button>(R.id.bookmark_button_add)
        val closeButton = contentView.findViewById<Button>(R.id.bookmark_button_close)
        val emptyText = contentView.findViewById<TextView>(R.id.bookmark_empty_text)
        val bookmarkBody = contentView.findViewById<FrameLayout>(R.id.bookmark_body)
        val scrollView = contentView.findViewById<ScrollView>(R.id.bookmark_scroll)
        val scrubberHost = contentView.findViewById<View>(R.id.bookmark_scrubber_host)
        val scrubberTrack = contentView.findViewById<View>(R.id.bookmark_scrubber_track)
        val scrubberThumb = contentView.findViewById<View>(R.id.bookmark_scrubber_thumb)
        val rows = contentView.findViewById<LinearLayout>(R.id.bookmark_rows)
        val dialogRef = arrayOfNulls<AlertDialog>(1)
        val empty = bookmarks.isNullOrEmpty()
        emptyText.visibility = if (empty) View.VISIBLE else View.GONE
        bookmarkBody.visibility = if (empty) View.GONE else View.VISIBLE
        scrollView.isVerticalScrollBarEnabled = false
        if (!empty && bookmarks != null) {
            for (bookmark in bookmarks) {
                rows.addView(createReaderBookmarkRow(bookmark) {
                    dialogRef[0]?.dismiss()
                    jumpToBookmark(bookmark)
                })
            }
            attachReaderBookmarkScrubber(scrollView, scrubberHost, scrubberTrack, scrubberThumb)
        }

        val dialog = AlertDialog.Builder(this)
            .setView(contentView)
            .create()
        dialogRef[0] = dialog
        addButton.setOnClickListener {
            dialog.dismiss()
            showAddBookmarkDialog()
        }
        closeButton.setOnClickListener { dialog.dismiss() }
        dialogSupport.showStyledDialog(dialog)
    }

    private fun attachReaderBookmarkScrubber(
        scrollView: ScrollView?,
        scrubberHost: View?,
        scrubberTrack: View?,
        scrubberThumb: View?,
    ) {
        if (scrollView == null || scrubberHost == null || scrubberTrack == null || scrubberThumb == null) {
            return
        }
        scrollView.setOnScrollChangeListener { _, _, _, _, _ ->
            refreshReaderBookmarkScrubber(scrollView, scrubberHost, scrubberTrack, scrubberThumb)
        }
        scrubberHost.setOnTouchListener { view, event ->
            val action = event.actionMasked
            if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_UP) {
                view.parent.requestDisallowInterceptTouchEvent(false)
                return@setOnTouchListener true
            }
            if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_MOVE) {
                return@setOnTouchListener false
            }
            val maxScroll = bookmarkMaxScroll(scrollView)
            if (maxScroll <= 0) {
                return@setOnTouchListener false
            }
            view.parent.requestDisallowInterceptTouchEvent(true)
            val fraction = clamp01((event.y - scrubberTrack.y) / Math.max(scrubberTrack.height, 1))
            scrollView.scrollTo(0, Math.round(maxScroll * fraction))
            positionReaderBookmarkThumb(scrubberTrack, scrubberThumb, fraction)
            true
        }
        scrollView.post {
            refreshReaderBookmarkScrubber(scrollView, scrubberHost, scrubberTrack, scrubberThumb)
        }
    }

    private fun refreshReaderBookmarkScrubber(
        scrollView: ScrollView,
        scrubberHost: View,
        scrubberTrack: View,
        scrubberThumb: View,
    ) {
        val maxScroll = bookmarkMaxScroll(scrollView)
        if (maxScroll <= 0) {
            scrubberHost.visibility = View.INVISIBLE
            positionReaderBookmarkThumb(scrubberTrack, scrubberThumb, 0f)
            return
        }
        scrubberHost.visibility = View.VISIBLE
        positionReaderBookmarkThumb(scrubberTrack, scrubberThumb, scrollView.scrollY / maxScroll.toFloat())
    }

    private fun bookmarkMaxScroll(scrollView: ScrollView?): Int {
        if (scrollView == null || scrollView.childCount == 0) {
            return 0
        }
        val child = scrollView.getChildAt(0)
        return Math.max(0, child.height - scrollView.height)
    }

    private fun positionReaderBookmarkThumb(scrubberTrack: View, scrubberThumb: View, fraction: Float) {
        scrubberTrack.post {
            val trackTop = scrubberTrack.y
            val travel = Math.max(0f, (scrubberTrack.height - scrubberThumb.height).toFloat())
            scrubberThumb.y = trackTop + travel * clamp01(fraction)
        }
    }

    private fun clamp01(value: Float): Float = Math.max(0f, Math.min(1f, value))

    private fun createReaderBookmarkRow(bookmark: BookmarkRecord, onClick: Runnable?): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.VERTICAL
        row.setBackgroundResource(R.drawable.bg_input)
        row.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
        row.isClickable = true
        row.isFocusable = true
        val rowParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        rowParams.setMargins(0, ui.dp(8), 0, 0)
        row.layoutParams = rowParams

        val title = TextView(this)
        title.text = if (bookmark.chapterTitle.isNullOrBlank()) "未命名章节" else bookmark.chapterTitle
        title.setTextColor(ui.themeColor(R.color.on_surface))
        title.textSize = 15f
        title.setTypeface(null, android.graphics.Typeface.BOLD)
        title.maxLines = 1
        title.ellipsize = android.text.TextUtils.TruncateAt.END

        val meta = TextView(this)
        meta.text = String.format(Locale.SIMPLIFIED_CHINESE, "%.1f%%", bookmark.progressPercent)
        meta.setTextColor(ui.themeColor(R.color.on_surface_muted))
        meta.textSize = 13f

        val summary = TextView(this)
        summary.text = if (bookmark.summary.isNullOrBlank()) "无摘要" else bookmark.summary
        summary.setTextColor(ui.themeColor(R.color.on_surface_muted))
        summary.textSize = 13f
        summary.maxLines = 2
        summary.ellipsize = android.text.TextUtils.TruncateAt.END

        row.addView(title)
        row.addView(meta)
        row.addView(summary)
        row.setOnClickListener {
            onClick?.run()
        }
        return row
    }

    private fun showAddBookmarkDialog() {
        if (state.book == null || state.chapters.isEmpty()) {
            ui.showToast("书籍尚未载入")
            return
        }
        val chapterIndex = Math.max(0, Math.min(state.currentChapterIndex, state.chapters.size - 1))
        val chapterOffset = content.currentCharOffset()
        val summary = content.buildBookmarkSummary(chapterIndex, chapterOffset, 120)

        val contentLayout = LinearLayout(this)
        contentLayout.orientation = LinearLayout.VERTICAL
        contentLayout.setBackgroundResource(R.drawable.bg_dialog)
        contentLayout.setPadding(ui.dp(18), ui.dp(18), ui.dp(18), ui.dp(18))

        val title = TextView(this)
        title.text = "添加书签"
        title.setTextColor(ui.themeColor(R.color.on_surface))
        title.textSize = 20f
        title.setTypeface(null, android.graphics.Typeface.BOLD)
        contentLayout.addView(
            title,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        val editText = EditText(this)
        editText.minLines = 3
        editText.maxLines = 6
        editText.setText(summary)
        editText.setSelection(editText.text.length)
        editText.setTextColor(ui.themeColor(R.color.on_surface))
        editText.setHintTextColor(ui.themeColor(R.color.on_surface_muted))
        editText.hint = "摘要"
        editText.setBackgroundResource(R.drawable.bg_input)
        editText.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))

        val inputParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        inputParams.setMargins(0, ui.dp(14), 0, 0)
        contentLayout.addView(editText, inputParams)

        val buttonRow = LinearLayout(this)
        buttonRow.orientation = LinearLayout.HORIZONTAL
        buttonRow.gravity = Gravity.END
        val rowParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        rowParams.setMargins(0, ui.dp(14), 0, 0)
        contentLayout.addView(buttonRow, rowParams)

        val cancelButton = Button(this)
        cancelButton.text = "取消"
        cancelButton.isAllCaps = false
        cancelButton.setBackgroundResource(R.drawable.bg_outline_button)
        cancelButton.setTextColor(ui.themeColor(R.color.on_surface))
        buttonRow.addView(
            cancelButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        val saveButton = Button(this)
        saveButton.text = "保存"
        saveButton.isAllCaps = false
        saveButton.setBackgroundResource(R.drawable.bg_primary_button)
        saveButton.setTextColor(android.graphics.Color.WHITE)
        val saveParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        saveParams.setMargins(ui.dp(10), 0, 0, 0)
        buttonRow.addView(saveButton, saveParams)

        val dialog = AlertDialog.Builder(this)
            .setView(contentLayout)
            .create()
        cancelButton.setOnClickListener { dialog.dismiss() }
        saveButton.setOnClickListener {
            dialog.dismiss()
            saveBookmark(
                chapterIndex,
                chapterOffset,
                editText.text?.toString() ?: "",
            )
        }
        dialogSupport.showStyledDialog(dialog)
    }

    private fun saveBookmark(chapterIndex: Int, chapterOffset: Int, summary: String?) {
        val book = state.book
        if (book == null || state.chapters.isEmpty()) {
            ui.showToast("书籍尚未载入")
            return
        }
        val safeChapterIndex = Math.max(0, Math.min(chapterIndex, state.chapters.size - 1))
        val chapter = state.chapters[safeChapterIndex]
        val bookmark = BookmarkRecord()
        val now = System.currentTimeMillis()
        bookmark.uuid = UUID.randomUUID().toString()
        bookmark.bookId = book.id
        bookmark.bookIdentity = book.readingStatsKey
        bookmark.bookTitle = ReadingStatsUtils.safeBookTitle(book.title)
        bookmark.bookAuthor = ReadingStatsUtils.safeBookAuthor(book.author)
        bookmark.chapterOrderIndex = chapter.orderIndex
        bookmark.chapterTitle = chapter.title ?: ""
        bookmark.chapterOffset = Math.max(chapterOffset, 0)
        bookmark.progressPercent = content.bookProgressPercentFor(safeChapterIndex, bookmark.chapterOffset)
        bookmark.summary = if (summary == null || summary.trim().isEmpty()) {
            content.buildBookmarkSummary(safeChapterIndex, bookmark.chapterOffset, 120)
        } else {
            summary.trim()
        }
        bookmark.createdAt = now
        bookmark.updatedAt = now
        runtime.safeExecute(
            {
                runtime.databaseHelper.upsertBookmark(bookmark)
                runOnReaderUiThread {
                    ui.showToast("已添加书签")
                    showBookmarkDialog()
                }
            },
            "save reader bookmark",
        )
    }

    private fun jumpToBookmark(bookmark: BookmarkRecord?) {
        if (bookmark == null || state.chapters.isEmpty()) {
            return
        }
        chrome.setControlsVisible(false)
        val chapterIndex = navigation.chapterIndexFromOrder(bookmark.chapterOrderIndex)
        val direction = if (chapterIndex >= state.currentChapterIndex) 1 else -1
        navigation.openChapter(chapterIndex, bookmark.chapterOffset, true, direction)
    }

    companion object {
        private const val TAG = "PacilReadReader"
        private const val REQUEST_PICK_BACKGROUND = 2001
        private const val ENTER_TRANSITION_DURATION_MS = 280L
        private const val ENTER_TEXT_FADE_DURATION_MS = 90L
        private const val EXIT_TRANSITION_DURATION_MS = 245L
        private const val EXIT_TEXT_FADE_DURATION_MS = 70L
    }
}


