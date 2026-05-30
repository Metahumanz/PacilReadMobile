package com.metahumanz.pacilread.reader.modern;

import android.animation.ValueAnimator;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.os.BatteryManager;
import android.os.Bundle;
import android.util.Log;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.core.view.WindowCompat;

import com.metahumanz.pacilread.R;
import com.metahumanz.pacilread.ReadingStatsActivity;
import com.metahumanz.pacilread.model.BookmarkRecord;
import com.metahumanz.pacilread.model.ChapterRecord;
import com.metahumanz.pacilread.reader.PageSlice;
import com.metahumanz.pacilread.stats.ReadingStatsUtils;
import com.metahumanz.pacilread.reader.modern.content.ReaderContentController;
import com.metahumanz.pacilread.reader.modern.dialog.ReaderDialogSupport;
import com.metahumanz.pacilread.reader.modern.dialog.ReaderLibraryDialogs;
import com.metahumanz.pacilread.reader.modern.dialog.ReaderOptionsDialogController;
import com.metahumanz.pacilread.reader.modern.dialog.ReaderStyleDialogController;
import com.metahumanz.pacilread.reader.modern.paging.ReaderNavigationController;
import com.metahumanz.pacilread.reader.modern.paging.ReaderPagingAnimator;
import com.metahumanz.pacilread.reader.modern.playback.ReaderAutoPageController;
import com.metahumanz.pacilread.reader.modern.selection.ReaderTextSelectionController;
import com.metahumanz.pacilread.reader.modern.stats.ReaderReadingStatsTracker;
import com.metahumanz.pacilread.reader.modern.tts.ReaderTtsController;
import com.metahumanz.pacilread.reader.modern.ui.ReaderChromeController;
import com.metahumanz.pacilread.reader.modern.ui.ReaderStyleController;
import com.metahumanz.pacilread.storage.SettingsStore;
import com.metahumanz.pacilread.theme.ThemedReaderActivity;
import com.metahumanz.pacilread.ui.ActivityTransitionCompat;
import com.metahumanz.pacilread.ui.LaunchSourceTransition;
import com.metahumanz.pacilread.ui.PredictiveBackScaleController;
import com.metahumanz.pacilread.ui.ScreenCornerClipper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class ModernReaderActivity extends ThemedReaderActivity {
    private static final String TAG = "PacilReadReader";
    private static final int REQUEST_PICK_BACKGROUND = 2001;
    private static final long ENTER_TRANSITION_DURATION_MS = 280L;
    private static final long ENTER_TEXT_FADE_DURATION_MS = 90L;
    private static final long EXIT_TRANSITION_DURATION_MS = 245L;
    private static final long EXIT_TEXT_FADE_DURATION_MS = 70L;

    private ReaderRuntime runtime;
    private ReaderViewRefs views;
    private ReaderSessionState state;
    private ReaderUiUtils ui;
    private ReaderDialogSupport dialogSupport;
    private ReaderChromeController chrome;
    private ReaderContentController content;
    private ReaderNavigationController navigation;
    private ReaderPagingAnimator paging;
    private ReaderStyleController style;
    private ReaderAutoPageController autoPage;
    private ReaderTtsController tts;
    private ReaderTextSelectionController selection;
    private ReaderLibraryDialogs libraryDialogs;
    private ReaderStyleDialogController styleDialogs;
    private ReaderOptionsDialogController optionsDialogs;
    private ReaderReadingStatsTracker readingStatsTracker;
    private GestureDetector gestureDetector;
    private BroadcastReceiver sysMetricsReceiver;
    private PopupWindow readerPopupWindow;
    private boolean readerPopupDismissingByCode;
    private boolean readerExitFinishing;
    private boolean readerExitProgressPersisted;
    private boolean readerEnterTransitionActive;
    private boolean readerEnterAnimationStarted;
    private boolean readerExitFromBackGesture;
    private volatile boolean readerDestroyed;
    private ValueAnimator readerForegroundAnimator;
    private Runnable readerEnterForegroundFadeRunnable;
    private LaunchSourceTransition.Source launchSource;
    private long lastScrollPageTurnTime;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applyReaderOrientationPreference();
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_reader);

        runtime = new ReaderRuntime(this);
        state = new ReaderSessionState();
        readingStatsTracker = new ReaderReadingStatsTracker(runtime, state);
        state.pagingTouchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        state.bookId = getIntent().getLongExtra("book_id", -1L);
        launchSource = LaunchSourceTransition.fromIntentSource(getIntent());
        state.requestedChapterOrderIndex = getIntent().getIntExtra("bookmark_chapter_order_index", -1);
        state.requestedChapterOffset = getIntent().getIntExtra("bookmark_chapter_offset", -1);
        if (savedInstanceState != null) {
            state.restoredChapterIndex = savedInstanceState.getInt("restored_chapter_index", -1);
            state.restoredPageIndex = savedInstanceState.getInt("restored_page_index", -1);
            state.restoredProgressOffset = savedInstanceState.getInt("restored_progress_offset", -1);
        }

        views = ReaderViewRefs.bind(this);
        ui = new ReaderUiUtils(this);
        initializeControllers();

        chrome.configureReaderWindow();
        chrome.applyEdgeToEdgeInsets();
        style.applyReaderSettings();
        setupGestures();
        setupControls();
        installPredictiveBack();

        boolean restoringReaderInstance = savedInstanceState != null;
        boolean useFluidEnter = !restoringReaderInstance
                && hasLaunchSource()
                && com.metahumanz.pacilread.ui.TransitionMotionModeHelper.isFluidMode(runtime.settingsStore);
        if (useFluidEnter) {
            readerEnterTransitionActive = true;
            ActivityTransitionCompat.overrideOpen(this, 0, 0);
            // 先隐藏，等 layout 就绪后再显示并启动动画，防止未就绪时闪现全屏
            views.readerRoot.setVisibility(View.INVISIBLE);
            views.readerRoot.setAlpha(1f);
            views.readerRoot.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    views.readerRoot.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                    startFluidEnterAnimation();
                }
            });
            // 安全兜底：如果 GlobalLayoutListener 一直没触发，100ms 后强制启动
            views.readerRoot.postDelayed(this::startFluidEnterAnimation, 100L);
        }

        state.sessionStartTime = System.currentTimeMillis();
        state.sessionStartOffset = 0;

        views.pageBodyCurrent.setText("正在载入...");
        if (useFluidEnter) {
            content.setDeferReflow(true);
        }
        content.loadBook();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyReaderOrientationPreference();
        if (readingStatsTracker != null) {
            readingStatsTracker.resume();
        }
        if (views != null && views.readerRoot != null) {
            views.readerRoot.invalidateOutline();
        }
        chrome.updateSystemBarsVisibility(state.controlsVisible);
        chrome.applyGlassOpacity();
        if (state.controlsVisible) {
            chrome.scheduleAutoHide();
        } else {
            chrome.cancelAutoHide();
        }
    }

    public void applyReaderUiThemeWithoutRecreate() {
        applyResolvedReaderThemeWithoutRecreate();
        if (style != null) {
            style.applyReaderSettings();
        }
        if (chrome != null) {
            chrome.updateUiAfterPageChange();
            chrome.updateSystemBarsVisibility(state != null && state.controlsVisible);
        }
    }

    private void applyReaderOrientationPreference() {
        String mode = new SettingsStore(this).getReaderOrientationMode();
        if ("portrait".equals(mode)) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        } else if ("landscape".equals(mode)) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        ReaderContentController.ReadingPosition position = content == null
                ? null
                : content.captureCurrentReadingPosition();
        outState.putInt("restored_chapter_index", position == null ? state.currentChapterIndex : position.chapterIndex);
        outState.putInt("restored_page_index", position == null ? state.currentPageIndex : position.pageIndex);
        outState.putInt("restored_progress_offset", position == null ? -1 : position.chapterOffset);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (readingStatsTracker != null) {
            readingStatsTracker.pause();
        }
        if (chrome != null) {
            chrome.cancelAutoHide();
        }
        if (!readerExitProgressPersisted) {
            persistReaderProgress(true);
        }
        if (paging != null) {
            paging.cancelInteractiveAnimator();
            paging.cancelInteractivePaging();
            paging.removeWarmupCallbacks();
        }
        if (autoPage != null) {
            autoPage.stopAutoPage();
        }
        if (tts != null) {
            tts.stopTts();
        }
        if (content != null) {
            content.cancelPendingReflow();
        }
        state.pendingTapPagingDelta = 0;
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!readerExitProgressPersisted) {
            persistReaderProgress(true);
        }
    }

    @Override
    protected void onDestroy() {
        readerDestroyed = true;
        super.onDestroy();
        cancelReaderForegroundTransition();
        if (sysMetricsReceiver != null) {
            try {
                unregisterReceiver(sysMetricsReceiver);
            } catch (IllegalArgumentException error) {
                Log.d(TAG, "System metrics receiver was already unregistered", error);
            }
            sysMetricsReceiver = null;
        }
        if (readingStatsTracker != null) {
            readingStatsTracker.shutdown();
        }
        if (runtime != null) {
            runtime.shutdown();
        }
        if (paging != null) {
            paging.cancelInteractiveAnimator();
            paging.recyclePagingSnapshots();
        }
    }

    public boolean isReaderActive() {
        return !readerDestroyed && !isFinishing() && !isDestroyed();
    }

    public boolean isReaderEnterTransitionActive() {
        return readerEnterTransitionActive;
    }

    public void runOnReaderUiThread(Runnable action) {
        if (action == null || !isReaderActive()) {
            return;
        }
        runOnUiThread(() -> {
            if (!isReaderActive()) {
                return;
            }
            try {
                action.run();
            } catch (RuntimeException error) {
                Log.w(TAG, "Reader UI task failed after lifecycle change", error);
            }
        });
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            markReadingActivity();
        }
        if (selection != null && selection.handleTouchEvent(event)) {
            return true;
        }
        if (paging.handleReaderPagingTouchEvent(event)) {
            return true;
        }
        if (gestureDetector != null) {
            gestureDetector.onTouchEvent(event);
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (paging.handleReaderVolumeKeyEvent(event)) {
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && !state.controlsVisible) {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_PAGE_UP:
                    state.lastTapY = -1f;
                    navigation.pageUp();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_PAGE_DOWN:
                case KeyEvent.KEYCODE_SPACE:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    state.lastTapY = -1f;
                    navigation.pageDown();
                    return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if ((event.getSource() & InputDevice.SOURCE_CLASS_POINTER) != 0
                && event.getActionMasked() == MotionEvent.ACTION_SCROLL
                && !state.controlsVisible) {
            float vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
            if (Math.abs(vScroll) > 0.5f) {
                long now = System.currentTimeMillis();
                if (now - lastScrollPageTurnTime > 300L) {
                    lastScrollPageTurnTime = now;
                    state.lastTapY = -1f;
                    if (vScroll < 0) {
                        navigation.pageDown();
                    } else {
                        navigation.pageUp();
                    }
                }
                return true;
            }
        }
        return super.onGenericMotionEvent(event);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        if (requestCode == REQUEST_PICK_BACKGROUND) {
            style.attachBackground(data.getData());
        }
    }

    private boolean hasLaunchSource() {
        return launchSource != null && launchSource.bounds() != null;
    }

    private void scheduleReaderEnterForegroundFade() {
        cancelScheduledReaderEnterForegroundFade();
        if (runtime == null || runtime.mainHandler == null) {
            finishReaderEnterForegroundFade();
            return;
        }
        long delayMs = Math.max(0L, ENTER_TRANSITION_DURATION_MS - ENTER_TEXT_FADE_DURATION_MS - 18L);
        readerEnterForegroundFadeRunnable = this::startReaderEnterForegroundFade;
        runtime.mainHandler.postDelayed(readerEnterForegroundFadeRunnable, delayMs);
    }

    private void startReaderEnterForegroundFade() {
        readerEnterForegroundFadeRunnable = null;
        animateReaderTransitionForegroundToAlpha(1f, ENTER_TEXT_FADE_DURATION_MS, null);
    }

    /** 启动流动进入动画。前提：readerRoot 已完成 layout 且 bounds 有效。 */
    private boolean startFluidEnterAnimation() {
        if (readerEnterAnimationStarted || readerExitFinishing) return false;
        if (views == null || views.readerRoot == null) return false;
        if (views.readerRoot.getWidth() <= 0 || views.readerRoot.getHeight() <= 0) return false;
        readerEnterAnimationStarted = true;
        Log.d("PacilReadReader", "[时序] 流体进入动画开始");
        views.readerRoot.setVisibility(View.VISIBLE);
        // 初始透明：前台由 snapshot overlay 承揽放大动画，真实阅读页在背后准备正文
        views.readerRoot.setAlpha(0f);
        boolean started = LaunchSourceTransition.animateEnterFromSource(
                views.readerRoot,
                launchSource,
                LaunchSourceTransition.Options.defaults()
                        .withDuration(ENTER_TRANSITION_DURATION_MS)
                        .withEnterSnapshotOverlay(true)
                        .withEnterContentFade(true)
                        .withEnterAnimatesLiveContent(false)
                        .withSnapshotFadeStartFraction(0.72f)
                        .withInterpolator(new android.view.animation.PathInterpolator(0.25f, 0.1f, 0.25f, 1.0f)),
                this::finishReaderEnterForegroundFade
        );
        if (started) {
            // 动画启动后再触发分页准备，避免首帧被分页快照/布局工作抢占
            views.readerRoot.postOnAnimation(() -> {
                if (content != null) {
                    content.capturePaginationSnapshot();
                    content.prewarmChapterTextAfterSnapshot();
                }
            });
        } else {
            finishReaderEnterForegroundFade();
        }
        return started;
    }

    private void finishReaderEnterForegroundFade() {
        if (!isReaderActive()) {
            return;
        }
        Log.d("PacilReadReader", "[时序] 动画结束 finishReaderEnterForegroundFade - cacheHit=" + (content != null && content.isCacheHit()));
        readerEnterTransitionActive = false;
        cancelScheduledReaderEnterForegroundFade();
        if (content != null) {
            // 正文与背景一起缩放，动画结束时文字大概率已就绪，直接完成排版即可
            content.performDeferredInitialReflow(() -> {
                if (paging != null) {
                    paging.schedulePagingSnapshotWarmup();
                }
            });
        }
    }

    private void fadeReaderForegroundForExit(Runnable onComplete) {
        cancelScheduledReaderEnterForegroundFade();
        animateReaderTransitionForegroundToAlpha(0f, EXIT_TEXT_FADE_DURATION_MS, onComplete);
    }

    private void cancelReaderForegroundTransition() {
        cancelScheduledReaderEnterForegroundFade();
        if (readerForegroundAnimator != null) {
            readerForegroundAnimator.cancel();
            readerForegroundAnimator = null;
        }
    }

    private void cancelScheduledReaderEnterForegroundFade() {
        if (readerEnterForegroundFadeRunnable != null && runtime != null && runtime.mainHandler != null) {
            runtime.mainHandler.removeCallbacks(readerEnterForegroundFadeRunnable);
        }
        readerEnterForegroundFadeRunnable = null;
    }

    private void animateReaderTransitionForegroundToAlpha(float targetAlpha, long durationMs, Runnable onComplete) {
        if (readerForegroundAnimator != null) {
            readerForegroundAnimator.cancel();
            readerForegroundAnimator = null;
        }
        View[] layers = readerTransitionForegroundLayers();
        View firstLayer = firstAvailableLayer(layers);
        if (firstLayer == null) {
            if (onComplete != null) {
                onComplete.run();
            }
            return;
        }
        float startAlpha = firstLayer.getAlpha();
        if (durationMs <= 0L || Math.abs(startAlpha - targetAlpha) < 0.01f) {
            setReaderTransitionForegroundAlpha(targetAlpha);
            if (onComplete != null) {
                onComplete.run();
            }
            return;
        }
        ValueAnimator animator = ValueAnimator.ofFloat(startAlpha, targetAlpha);
        readerForegroundAnimator = animator;
        animator.setDuration(durationMs);
        animator.setInterpolator(new android.view.animation.DecelerateInterpolator());
        animator.addUpdateListener(animation ->
                setReaderTransitionForegroundAlpha((float) animation.getAnimatedValue()));
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean canceled;

            @Override
            public void onAnimationCancel(android.animation.Animator animation) {
                canceled = true;
            }

            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (readerForegroundAnimator == animation) {
                    readerForegroundAnimator = null;
                }
                if (!canceled) {
                    setReaderTransitionForegroundAlpha(targetAlpha);
                    if (onComplete != null) {
                        onComplete.run();
                    }
                }
            }
        });
        animator.start();
    }

    private void setReaderTransitionForegroundAlpha(float alpha) {
        for (View layer : readerTransitionForegroundLayers()) {
            if (layer != null) {
                layer.setAlpha(alpha);
            }
        }
    }

    private View firstAvailableLayer(View[] layers) {
        for (View layer : layers) {
            if (layer != null) {
                return layer;
            }
        }
        return null;
    }

    private View[] readerTransitionForegroundLayers() {
        if (views == null) {
            return new View[0];
        }
        return new View[]{
                views.pageStage,
                views.hudTopContainer,
                views.hudBottomContainer,
                views.menuTopPanel,
                views.menuInfoPanel,
                views.menuBottomPanel
        };
    }

    private void initializeControllers() {
        dialogSupport = new ReaderDialogSupport(this, runtime, ui);
        chrome = new ReaderChromeController(this, runtime, views, state, ui);
        content = new ReaderContentController(this, runtime, views, state, ui);
        navigation = new ReaderNavigationController(this, runtime, views, state, ui);
        paging = new ReaderPagingAnimator(this, runtime, views, state, ui);
        style = new ReaderStyleController(this, runtime, views, state, ui);
        autoPage = new ReaderAutoPageController(this, runtime, views, state, ui, dialogSupport);
        tts = new ReaderTtsController(this, runtime, views, state, ui, dialogSupport);
        libraryDialogs = new ReaderLibraryDialogs(this, runtime, state, ui, dialogSupport, content, navigation);
        styleDialogs = new ReaderStyleDialogController(this, runtime, state, ui, dialogSupport, content, navigation, style, chrome);
        optionsDialogs = new ReaderOptionsDialogController(this, runtime, state, ui, dialogSupport, content, navigation, style, chrome);
        selection = new ReaderTextSelectionController(this, runtime, views, state, ui, content);

        content.attachControllers(navigation, style, paging, chrome);
        navigation.attachControllers(content, paging, chrome);
        paging.attachControllers(navigation, content, chrome);
        autoPage.attachControllers(navigation, chrome);
        tts.attachControllers(navigation, content, paging, chrome);
        selection.attachControllers(libraryDialogs, tts);
        chrome.attachControllers(content, paging, autoPage, tts);
        style.attachControllers(chrome, paging, content, tts);
    }

    private void setupControls() {
        sysMetricsReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) {
                    int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                    int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                    if (level >= 0 && scale > 0) {
                        state.currentBatteryLevel = (level * 100) / scale;
                    }
                }
                chrome.updateReaderHud();
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_TIME_TICK);
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        registerReceiver(sysMetricsReceiver, filter);

        findViewById(R.id.button_back).setOnClickListener(v -> finishReaderActivity());
        findViewById(R.id.button_prev_chapter).setOnClickListener(v -> navigation.openChapterFromStart(state.currentChapterIndex - 1, true, -1));
        findViewById(R.id.button_next_chapter).setOnClickListener(v -> navigation.openChapterFromStart(state.currentChapterIndex + 1, true, 1));
        findViewById(R.id.button_toc).setOnClickListener(v -> {
            dialogSupport.setNextDismissSource(v);
            libraryDialogs.showTocDialog();
        });
        findViewById(R.id.button_search).setOnClickListener(v -> {
            dialogSupport.setNextDismissSource(v);
            libraryDialogs.showSearchDialog();
        });
        findViewById(R.id.button_bookmark).setOnClickListener(v -> {
            dialogSupport.setNextDismissSource(v);
            showBookmarkDialog();
        });
        findViewById(R.id.button_rules).setOnClickListener(v -> {
            dialogSupport.setNextDismissSource(v);
            libraryDialogs.showRulesDialog();
        });
        findViewById(R.id.button_style).setOnClickListener(v -> {
            dialogSupport.setNextDismissSource(v);
            styleDialogs.showStyleDialog(REQUEST_PICK_BACKGROUND);
        });
        findViewById(R.id.button_reader_options).setOnClickListener(v -> {
            dialogSupport.setNextDismissSource(v);
            optionsDialogs.showReaderOptionsDialog();
        });
        views.themeToggleButton.setOnClickListener(v -> chrome.toggleReaderUiTheme());
        views.ttsButton.setOnClickListener(v -> {
            dialogSupport.setNextDismissSource(v);
            tts.showTtsDialog();
        });
        views.autoPageButton.setOnClickListener(v -> {
            dialogSupport.setNextDismissSource(v);
            autoPage.showAutoPageDialog();
        });
        views.moreButton.setOnClickListener(v -> {
            if (readerPopupWindow != null && readerPopupWindow.isShowing()) {
                PopupWindow pw = readerPopupWindow;
                animatePopupWaterfallClose(pw, () -> {
                    readerPopupDismissingByCode = true;
                    pw.dismiss();
                });
                return;
            }

            views.moreButton.animate().rotation(-90f).setDuration(200).start();

            int pad = ui.dp(10);
            int gap = ui.dp(8);
            int btnPadH = ui.dp(14);
            int btnPadV = ui.dp(8);
            int rowHeight = ui.dp(40) + btnPadV * 2;

            ScrollView scrollView = new ScrollView(this);
            scrollView.setClipToPadding(false);

            LinearLayout popupContent = new LinearLayout(this);
            popupContent.setOrientation(LinearLayout.VERTICAL);
            popupContent.setBackgroundResource(R.drawable.bg_reader_popup_menu);
            popupContent.setPadding(pad, pad, pad, pad);
            scrollView.addView(popupContent);

            PopupWindow popupWindow = new PopupWindow(scrollView,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT, false);
            popupWindow.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            popupWindow.setOutsideTouchable(false);
            popupWindow.setOnDismissListener(() -> {
                views.moreButton.animate().rotation(0f).setDuration(200).start();
                readerPopupDismissingByCode = false;
                readerPopupWindow = null;
            });
            readerPopupWindow = popupWindow;

            String[][] rows = {
                    {"搜索", "替换"},
                    {"排版", "翻页"},
                    {"听书", "书签"}
            };
            List<LinearLayout> rowLayouts = new ArrayList<>();
            for (int r = 0; r < rows.length; r++) {
                LinearLayout rowLayout = new LinearLayout(this);
                rowLayout.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                if (r > 0) {
                    rowLp.topMargin = gap;
                }
                popupContent.addView(rowLayout, rowLp);
                rowLayouts.add(rowLayout);

                for (int c = 0; c < rows[r].length; c++) {
                    String item = rows[r][c];
                    Button btn = new Button(this);
                    btn.setText(item);
                    btn.setAllCaps(false);
                    btn.setMinHeight(ui.dp(40));
                    btn.setMinWidth(0);
                    btn.setPadding(btnPadH, btnPadV, btnPadH, btnPadV);
                    chrome.styleReaderMenuButton(btn, false);
                    btn.setOnClickListener(itemView -> {
                        LaunchSourceTransition.Source itemSource = LaunchSourceTransition.captureSource(itemView);
                        animatePopupWaterfallClose(popupWindow, () -> {
                            readerPopupDismissingByCode = true;
                            popupWindow.dismiss();
                            handleReaderPopupAction(item, itemSource);
                        });
                    });

                    LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                    if (c > 0) {
                        btnLp.leftMargin = gap;
                    }
                    rowLayout.addView(btn, btnLp);
                }
            }

            popupWindow.showAsDropDown(views.moreButton, 0, 0, Gravity.END);
            animatePopupWaterfallOpen(popupContent, rowLayouts, rowHeight);
        });
        views.readerTitle.setOnClickListener(v -> openReadingStatsForCurrentBook(v));
        views.pageStage.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int width = right - left;
            int height = bottom - top;
            int oldWidth = oldRight - oldLeft;
            int oldHeight = oldBottom - oldTop;
            if (width <= 0 || height <= 0 || (width == oldWidth && height == oldHeight)) {
                return;
            }
            int anchorOffset = content.currentCharOffset();
            chrome.updateReaderHud();
            if (state.book != null && !state.chapters.isEmpty()) {
                content.scheduleReflowAfterLayout(state.currentChapterIndex, anchorOffset);
            }
        });

        View.OnTouchListener keepMenuAliveListener = (view, event) -> {
            if (state.controlsVisible && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                chrome.scheduleAutoHide();
                if (readerPopupWindow != null && readerPopupWindow.isShowing()) {
                    readerPopupDismissingByCode = true;
                    readerPopupWindow.dismiss();
                }
            }
            return false;
        };
        views.menuTopPanel.setOnTouchListener(keepMenuAliveListener);
        views.menuInfoPanel.setOnTouchListener(keepMenuAliveListener);
        views.menuBottomPanel.setOnTouchListener(keepMenuAliveListener);
        views.progressSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                chrome.setControlsVisible(true);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if ("book".equals(runtime.settingsStore.getReaderSliderMode())) {
                    int chapterIndex = ui.clamp(seekBar.getProgress(), 0, state.chapters.size() - 1);
                    int direction = chapterIndex >= state.currentChapterIndex ? 1 : -1;
                    navigation.openChapterFromStart(chapterIndex, true, direction);
                    return;
                }
                int direction = seekBar.getProgress() >= state.currentPageIndex ? 1 : -1;
                navigation.showPage(state.currentChapterIndex, seekBar.getProgress(), true, direction);
            }
        });
    }

    private void installPredictiveBack() {
        if (!com.metahumanz.pacilread.ui.TransitionMotionModeHelper.isFluidMode(runtime.settingsStore)) {
            return;
        }
        PredictiveBackScaleController.install(this, views.readerRoot, PredictiveBackScaleController.Profile.reader(),
                new PredictiveBackScaleController.Delegate() {
                    @Override
                    public boolean shouldAnimateBack() {
                        return !hasReaderBackConsumer();
                    }

                    @Override
                    public boolean consumeBack() {
                        return consumeReaderBack();
                    }

                    @Override
                    public void commitBack() {
                        finishReaderActivity();
                    }

                    @Override
                    public boolean commitBackFromGesture() {
                        readerExitFromBackGesture = true;
                        return true;
                    }
                });
    }

    @Override
    public void onBackPressed() {
        if (!com.metahumanz.pacilread.ui.TransitionMotionModeHelper.isFluidMode(runtime.settingsStore)) {
            if (consumeReaderBack()) return;
            finishReaderActivity();
            return;
        }
        super.onBackPressed();
    }

    private boolean hasReaderBackConsumer() {
        return (selection != null && selection.hasSelection()) || (state != null && state.controlsVisible);
    }

    private boolean consumeReaderBack() {
        if (selection != null && selection.hasSelection()) {
            selection.clearSelection();
            return true;
        }
        if (state != null && state.controlsVisible) {
            chrome.setControlsVisible(false);
            return true;
        }
        return false;
    }

    public void finishReaderActivity() {
        if (readerExitFinishing) {
            return;
        }
        readerExitFinishing = true;
        readerExitProgressPersisted = persistReaderProgress(true);
        animateReaderExitToSource();
    }

    private void animateReaderExitToSource() {
        if (views == null || views.readerRoot == null) {
            finishReaderActivityNow();
            return;
        }
        fadeReaderForegroundForExit(this::runReaderExitToSource);
    }

    private void runReaderExitToSource() {
        if (views == null || views.readerRoot == null) {
            finishReaderActivityNow();
            return;
        }
        // 非流动模式直接走居中缩放淡出
        if (!com.metahumanz.pacilread.ui.TransitionMotionModeHelper.isFluidMode(runtime.settingsStore)) {
            animateReaderExitToCenter();
            return;
        }
        LaunchSourceTransition.Options options = LaunchSourceTransition.Options.defaults()
                .withDuration(EXIT_TRANSITION_DURATION_MS)
                .withSnapshotFadeStartFraction(0.72f)
                .withExitScreenCornerClip(readerExitFromBackGesture);
        if (readerExitFromBackGesture && LaunchSourceTransition.animateExitToSourceWithClip(
                views.readerRoot,
                launchSource,
                options,
                this::finishReaderActivityNow
        )) {
            return;
        }
        if (!readerExitFromBackGesture) {
            ScreenCornerClipper.setClipEnabled(views.readerRoot, false);
        }
        if (LaunchSourceTransition.animateExitToSource(
                views.readerRoot,
                launchSource,
                options,
                this::finishReaderActivityNow
        )) {
            return;
        }
        animateReaderExitToCenter();
    }

    private void animateReaderExitToCenter() {
        float minScale = PredictiveBackScaleController.READER_MIN_SCALE;
        views.readerRoot.animate().cancel();
        views.readerRoot.animate()
                .scaleX(minScale)
                .scaleY(minScale)
                .alpha(0f)
                .translationX(0f)
                .translationY(0f)
                .setDuration(160L)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .withEndAction(this::finishReaderActivityNow)
                .start();
    }

    private void finishReaderActivityNow() {
        if (!readerExitProgressPersisted) {
            readerExitProgressPersisted = persistReaderProgress(false);
        }
        finish();
        ActivityTransitionCompat.overrideClose(this, 0, 0);
    }

    private boolean persistReaderProgress(boolean settlePaging) {
        if (content == null) {
            return false;
        }
        try {
            if (settlePaging && paging != null) {
                paging.settleInterruptedPagingAnimation();
            }
            content.cancelPendingProgressSave();
            content.persistProgress();
            return true;
        } catch (RuntimeException error) {
            Log.w(TAG, "Failed to persist reader progress", error);
            return false;
        }
    }

    private void handleReaderPopupAction(String item, LaunchSourceTransition.Source source) {
        views.moreButton.animate().rotation(0f).setDuration(200).start();
        switch (item) {
            case "搜索":
                dialogSupport.setNextDismissSource(source);
                libraryDialogs.showSearchDialog();
                break;
            case "替换":
                dialogSupport.setNextDismissSource(source);
                libraryDialogs.showRulesDialog();
                break;
            case "排版":
                dialogSupport.setNextDismissSource(source);
                styleDialogs.showStyleDialog(REQUEST_PICK_BACKGROUND);
                break;
            case "翻页":
                dialogSupport.setNextDismissSource(source);
                autoPage.showAutoPageDialog();
                break;
            case "听书":
                dialogSupport.setNextDismissSource(source);
                tts.showTtsDialog();
                break;
            case "书签":
                dialogSupport.setNextDismissSource(source);
                showBookmarkDialog();
                break;
        }
    }

    private void setupGestures() {
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                if (state.controlsVisible) {
                    if (chrome.isInsideView(e, views.menuTopPanel)
                            || chrome.isInsideView(e, views.menuInfoPanel)
                            || chrome.isInsideView(e, views.menuBottomPanel)) {
                        return false;
                    }
                    chrome.setControlsVisible(false);
                    return true;
                }
                float width = views.readerRoot.getWidth();
                float height = views.readerRoot.getHeight();
                float x = e.getX();
                float y = e.getY();
                float thirdW = width / 3f;
                float thirdH = height / 3f;
                int col = ui.clamp((int) (x / thirdW), 0, 2);
                int row = ui.clamp((int) (y / thirdH), 0, 2);
                if (col == 1 && row == 1) {
                    state.lastTapY = -1f;
                    chrome.setControlsVisible(true);
                } else if (state.ttsActive && tts != null) {
                    int offset = bodyCharOffsetFromTouch(e);
                    if (offset >= 0) {
                        state.lastTapY = -1f;
                        tts.startTtsFrom(state.currentChapterIndex, offset);
                    } else if (col == 0 || (col == 1 && row == 0)) {
                        state.lastTapY = y;
                        navigation.requestTapPageTurn(-1);
                        resumeTtsAfterPageTurn();
                    } else {
                        state.lastTapY = y;
                        navigation.requestTapPageTurn(1);
                        resumeTtsAfterPageTurn();
                    }
                } else if (col == 0 || (col == 1 && row == 0)) {
                    state.lastTapY = y;
                    navigation.requestTapPageTurn(-1);
                } else {
                    state.lastTapY = y;
                    navigation.requestTapPageTurn(1);
                }
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (state.controlsVisible || state.isAnimating || state.interactivePaging) {
                    return false;
                }
                if (Math.abs(velocityX) > Math.abs(velocityY) * 1.3f && Math.abs(velocityX) > 700f) {
                    boolean flipped;
                    if (velocityX < 0) {
                        flipped = navigation.pageDown();
                    } else {
                        flipped = navigation.pageUp();
                    }
                    if (flipped) {
                        resumeTtsAfterPageTurn();
                    }
                    return true;
                }
                return false;
            }
        });
    }

    public void markReadingActivity() {
        if (readingStatsTracker != null) {
            readingStatsTracker.markActivity();
        }
    }

    public void onReaderBookLoaded() {
        if (readingStatsTracker != null) {
            readingStatsTracker.bindBook(state.book);
        }
    }

    public void onReaderPageReadyForLaunchPreview() {
    }

    public void openReadingStatsForCurrentBook() {
        openReadingStatsForCurrentBook(views.readerTitle);
    }

    public void openReadingStatsForCurrentBook(View sourceView) {
        if (!runtime.settingsStore.isReadingTimeTrackingEnabled() || state.book == null) {
            return;
        }
        Intent intent = new Intent(this, ReadingStatsActivity.class);
        intent.putExtra("book_id", state.book.id);
        LaunchSourceTransition.attach(intent, sourceView);
        startActivity(intent);
    }

    public void clearTextSelection() {
        if (selection != null) {
            selection.clearSelection();
        }
    }

    private int bodyCharOffsetFromTouch(MotionEvent e) {
        if (views.pageBodyCurrent == null) return -1;
        int[] loc = new int[2];
        views.pageBodyCurrent.getLocationOnScreen(loc);
        float localX = e.getRawX() - loc[0];
        float localY = e.getRawY() - loc[1];
        int viewOffset = views.pageBodyCurrent.offsetForTouch(localX, localY);
        if (viewOffset < 0) return -1;
        List<PageSlice> pages = content.getPagesForChapter(state.currentChapterIndex);
        if (pages.isEmpty()) return -1;
        PageSlice slice = pages.get(ui.clamp(state.currentPageIndex, 0, pages.size() - 1));
        int bodyStartInSlice = Math.max(slice.bodyStartInSlice, 0);
        return slice.start + Math.max(0, viewOffset - bodyStartInSlice);
    }

    private void resumeTtsAfterPageTurn() {
        if (!state.ttsActive || tts == null) return;
        long delayMs = paging.readerFlipDurationMs() + 60L;
        runtime.mainHandler.postDelayed(() -> {
            if (!state.ttsActive || state.isAnimating || state.interactivePaging) return;
            List<PageSlice> pages = content.getPagesForChapter(state.currentChapterIndex);
            if (pages.isEmpty()) return;
            int firstVisibleOffset = pages.get(ui.clamp(state.currentPageIndex, 0, pages.size() - 1)).start;
            tts.startTtsFrom(state.currentChapterIndex, firstVisibleOffset);
        }, delayMs);
    }

    public void dismissReaderPopupImmediate() {
        if (readerPopupWindow != null && readerPopupWindow.isShowing()) {
            readerPopupDismissingByCode = true;
            readerPopupWindow.dismiss();
            views.moreButton.animate().rotation(0f).setDuration(200).start();
        }
    }

    private void animatePopupWaterfallOpen(View popupRoot, List<LinearLayout> rows, int rowHeight) {
        if (popupRoot != null) {
            popupRoot.setScaleX(PredictiveBackScaleController.READER_MIN_SCALE);
            popupRoot.setScaleY(PredictiveBackScaleController.READER_MIN_SCALE);
            popupRoot.setAlpha(0f);
            popupRoot.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(180)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
        }
        int staggerMs = 20;
        int dropDistance = rowHeight + ui.dp(8);
        for (int i = 0; i < rows.size(); i++) {
            LinearLayout row = rows.get(i);
            row.setTranslationY(-dropDistance);
            row.setAlpha(0f);
            row.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(120)
                    .setStartDelay(i * staggerMs)
                    .start();
        }
    }

    private void animatePopupWaterfallClose(PopupWindow popupWindow, Runnable onComplete) {
        if (popupWindow == null || popupWindow.getContentView() == null) return;
        View content = popupWindow.getContentView();
        View popupRoot = content instanceof ScrollView ? ((ScrollView) content).getChildAt(0) : content;
        if (!(popupRoot instanceof ViewGroup)) {
            popupWindow.dismiss();
            if (onComplete != null) onComplete.run();
            return;
        }
        ViewGroup popupContent = (ViewGroup) popupRoot;
        int childCount = popupContent.getChildCount();
        if (childCount == 0) {
            popupWindow.dismiss();
            if (onComplete != null) onComplete.run();
            return;
        }
        popupContent.animate()
                .scaleX(PredictiveBackScaleController.READER_MIN_SCALE)
                .scaleY(PredictiveBackScaleController.READER_MIN_SCALE)
                .alpha(0f)
                .setDuration(130)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .start();
        int staggerMs = 18;
        int riseDistance = ui.dp(40) + ui.dp(8);
        for (int i = 0; i < childCount; i++) {
            View child = popupContent.getChildAt(i);
            if (child instanceof LinearLayout) {
                child.animate()
                        .translationY(-riseDistance)
                        .alpha(0f)
                        .setDuration(90)
                        .setStartDelay(i * staggerMs)
                        .start();
            }
        }
        long totalDuration = (childCount - 1) * staggerMs + 90 + 20;
        popupContent.postDelayed(() -> {
            if (onComplete != null) onComplete.run();
        }, totalDuration);
    }

    private void showBookmarkDialog() {
        if (state.book == null) {
            ui.showToast("书籍尚未载入");
            return;
        }
        runtime.safeExecute(() -> {
            List<BookmarkRecord> bookmarks = runtime.databaseHelper.getBookmarksForBook(
                    state.book.id,
                    state.book.readingStatsKey
            );
            runOnReaderUiThread(() -> renderBookmarkDialog(bookmarks));
        }, "load reader bookmarks");
    }

    private void renderBookmarkDialog(List<BookmarkRecord> bookmarks) {
        View contentView = android.view.LayoutInflater.from(this).inflate(R.layout.dialog_bookmarks, null, false);
        Button addButton = contentView.findViewById(R.id.bookmark_button_add);
        Button closeButton = contentView.findViewById(R.id.bookmark_button_close);
        TextView emptyText = contentView.findViewById(R.id.bookmark_empty_text);
        ScrollView scrollView = contentView.findViewById(R.id.bookmark_scroll);
        LinearLayout rows = contentView.findViewById(R.id.bookmark_rows);
        AlertDialog[] dialogRef = new AlertDialog[1];
        boolean empty = bookmarks == null || bookmarks.isEmpty();
        emptyText.setVisibility(empty ? View.VISIBLE : View.GONE);
        scrollView.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (!empty) {
            for (BookmarkRecord bookmark : bookmarks) {
                rows.addView(createReaderBookmarkRow(bookmark, () -> {
                    if (dialogRef[0] != null) {
                        dialogRef[0].dismiss();
                    }
                    jumpToBookmark(bookmark);
                }));
            }
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(contentView)
                .create();
        dialogRef[0] = dialog;
        addButton.setOnClickListener(v -> {
            dialog.dismiss();
            showAddBookmarkDialog();
        });
        closeButton.setOnClickListener(v -> dialog.dismiss());
        dialogSupport.showStyledDialog(dialog);
    }

    private View createReaderBookmarkRow(BookmarkRecord bookmark, Runnable onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackgroundResource(R.drawable.bg_input);
        row.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
        row.setClickable(true);
        row.setFocusable(true);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        rowParams.setMargins(0, ui.dp(8), 0, 0);
        row.setLayoutParams(rowParams);

        TextView title = new TextView(this);
        title.setText(bookmark.chapterTitle == null || bookmark.chapterTitle.isBlank()
                ? "未命名章节"
                : bookmark.chapterTitle);
        title.setTextColor(ui.themeColor(R.color.on_surface));
        title.setTextSize(15f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);

        TextView meta = new TextView(this);
        meta.setText(String.format(Locale.SIMPLIFIED_CHINESE, "%.1f%%", bookmark.progressPercent));
        meta.setTextColor(ui.themeColor(R.color.on_surface_muted));
        meta.setTextSize(13f);

        TextView summary = new TextView(this);
        summary.setText(bookmark.summary == null || bookmark.summary.isBlank() ? "无摘要" : bookmark.summary);
        summary.setTextColor(ui.themeColor(R.color.on_surface_muted));
        summary.setTextSize(13f);
        summary.setMaxLines(2);
        summary.setEllipsize(android.text.TextUtils.TruncateAt.END);

        row.addView(title);
        row.addView(meta);
        row.addView(summary);
        row.setOnClickListener(v -> {
            if (onClick != null) {
                onClick.run();
            }
        });
        return row;
    }

    private void showAddBookmarkDialog() {
        if (state.book == null || state.chapters.isEmpty()) {
            ui.showToast("书籍尚未载入");
            return;
        }
        int chapterIndex = Math.max(0, Math.min(state.currentChapterIndex, state.chapters.size() - 1));
        int chapterOffset = content.currentCharOffset();
        String summary = content.buildBookmarkSummary(chapterIndex, chapterOffset, 120);

        LinearLayout contentLayout = new LinearLayout(this);
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        contentLayout.setBackgroundResource(R.drawable.bg_dialog);
        contentLayout.setPadding(ui.dp(18), ui.dp(18), ui.dp(18), ui.dp(18));

        TextView title = new TextView(this);
        title.setText("添加书签");
        title.setTextColor(ui.themeColor(R.color.on_surface));
        title.setTextSize(20f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        contentLayout.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        EditText editText = new EditText(this);
        editText.setMinLines(3);
        editText.setMaxLines(6);
        editText.setText(summary);
        editText.setSelection(editText.getText().length());
        editText.setTextColor(ui.themeColor(R.color.on_surface));
        editText.setHintTextColor(ui.themeColor(R.color.on_surface_muted));
        editText.setHint("摘要");
        editText.setBackgroundResource(R.drawable.bg_input);
        editText.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));

        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        inputParams.setMargins(0, ui.dp(14), 0, 0);
        contentLayout.addView(editText, inputParams);

        LinearLayout buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.END);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        rowParams.setMargins(0, ui.dp(14), 0, 0);
        contentLayout.addView(buttonRow, rowParams);

        Button cancelButton = new Button(this);
        cancelButton.setText("取消");
        cancelButton.setAllCaps(false);
        cancelButton.setBackgroundResource(R.drawable.bg_outline_button);
        cancelButton.setTextColor(ui.themeColor(R.color.on_surface));
        buttonRow.addView(cancelButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        Button saveButton = new Button(this);
        saveButton.setText("保存");
        saveButton.setAllCaps(false);
        saveButton.setBackgroundResource(R.drawable.bg_primary_button);
        saveButton.setTextColor(android.graphics.Color.WHITE);
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        saveParams.setMargins(ui.dp(10), 0, 0, 0);
        buttonRow.addView(saveButton, saveParams);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(contentLayout)
                .create();
        cancelButton.setOnClickListener(v -> dialog.dismiss());
        saveButton.setOnClickListener(v -> {
            dialog.dismiss();
            saveBookmark(
                    chapterIndex,
                    chapterOffset,
                    editText.getText() == null ? "" : editText.getText().toString()
            );
        });
        dialogSupport.showStyledDialog(dialog);
    }

    private void saveBookmark(int chapterIndex, int chapterOffset, String summary) {
        if (state.book == null || state.chapters.isEmpty()) {
            ui.showToast("书籍尚未载入");
            return;
        }
        int safeChapterIndex = Math.max(0, Math.min(chapterIndex, state.chapters.size() - 1));
        ChapterRecord chapter = state.chapters.get(safeChapterIndex);
        BookmarkRecord bookmark = new BookmarkRecord();
        long now = System.currentTimeMillis();
        bookmark.uuid = UUID.randomUUID().toString();
        bookmark.bookId = state.book.id;
        bookmark.bookIdentity = state.book.readingStatsKey;
        bookmark.bookTitle = ReadingStatsUtils.safeBookTitle(state.book.title);
        bookmark.bookAuthor = ReadingStatsUtils.safeBookAuthor(state.book.author);
        bookmark.chapterOrderIndex = chapter.orderIndex;
        bookmark.chapterTitle = chapter.title == null ? "" : chapter.title;
        bookmark.chapterOffset = Math.max(chapterOffset, 0);
        bookmark.progressPercent = content.bookProgressPercentFor(safeChapterIndex, bookmark.chapterOffset);
        bookmark.summary = summary == null || summary.trim().isEmpty()
                ? content.buildBookmarkSummary(safeChapterIndex, bookmark.chapterOffset, 120)
                : summary.trim();
        bookmark.createdAt = now;
        bookmark.updatedAt = now;
        runtime.safeExecute(() -> {
            runtime.databaseHelper.upsertBookmark(bookmark);
            runOnReaderUiThread(() -> {
                ui.showToast("已添加书签");
                showBookmarkDialog();
            });
        }, "save reader bookmark");
    }

    private void jumpToBookmark(BookmarkRecord bookmark) {
        if (bookmark == null || state.chapters.isEmpty()) {
            return;
        }
        chrome.setControlsVisible(false);
        int chapterIndex = navigation.chapterIndexFromOrder(bookmark.chapterOrderIndex);
        int direction = chapterIndex >= state.currentChapterIndex ? 1 : -1;
        navigation.openChapter(chapterIndex, bookmark.chapterOffset, true, direction);
    }
}
