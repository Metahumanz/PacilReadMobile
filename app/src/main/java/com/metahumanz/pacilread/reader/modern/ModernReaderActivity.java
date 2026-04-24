package com.metahumanz.pacilread.reader.modern;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.SeekBar;

import androidx.core.view.WindowCompat;

import com.metahumanz.pacilread.R;
import com.metahumanz.pacilread.ReadingStatsActivity;
import com.metahumanz.pacilread.reader.modern.content.ReaderContentController;
import com.metahumanz.pacilread.reader.modern.dialog.ReaderDialogSupport;
import com.metahumanz.pacilread.reader.modern.dialog.ReaderLibraryDialogs;
import com.metahumanz.pacilread.reader.modern.dialog.ReaderOptionsDialogController;
import com.metahumanz.pacilread.reader.modern.dialog.ReaderStyleDialogController;
import com.metahumanz.pacilread.reader.modern.paging.ReaderNavigationController;
import com.metahumanz.pacilread.reader.modern.paging.ReaderPagingAnimator;
import com.metahumanz.pacilread.reader.modern.playback.ReaderAutoPageController;
import com.metahumanz.pacilread.reader.modern.stats.ReaderReadingStatsTracker;
import com.metahumanz.pacilread.reader.modern.tts.ReaderTtsController;
import com.metahumanz.pacilread.reader.modern.ui.ReaderChromeController;
import com.metahumanz.pacilread.reader.modern.ui.ReaderStyleController;
import com.metahumanz.pacilread.theme.ThemedReaderActivity;

public class ModernReaderActivity extends ThemedReaderActivity {
    private static final int REQUEST_PICK_BACKGROUND = 2001;

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
    private ReaderLibraryDialogs libraryDialogs;
    private ReaderStyleDialogController styleDialogs;
    private ReaderOptionsDialogController optionsDialogs;
    private ReaderReadingStatsTracker readingStatsTracker;
    private GestureDetector gestureDetector;
    private BroadcastReceiver sysMetricsReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_reader);

        runtime = new ReaderRuntime(this);
        state = new ReaderSessionState();
        readingStatsTracker = new ReaderReadingStatsTracker(runtime, state);
        state.pagingTouchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        state.bookId = getIntent().getLongExtra("book_id", -1L);
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
        setupGestures();
        setupControls();

        state.sessionStartTime = System.currentTimeMillis();
        state.sessionStartOffset = 0;

        views.pageBodyCurrent.setText("正在载入...");
        content.loadBook();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (readingStatsTracker != null) {
            readingStatsTracker.resume();
        }
        chrome.updateSystemBarsVisibility(state.controlsVisible);
        chrome.applyGlassOpacity();
        if (state.controlsVisible) {
            chrome.scheduleAutoHide();
        } else {
            chrome.cancelAutoHide();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("restored_chapter_index", state.currentChapterIndex);
        outState.putInt("restored_page_index", state.currentPageIndex);
        outState.putInt("restored_progress_offset", content == null ? -1 : content.currentCharOffset());
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (readingStatsTracker != null) {
            readingStatsTracker.pause();
        }
        chrome.cancelAutoHide();
        paging.cancelInteractiveAnimator();
        paging.cancelInteractivePaging();
        autoPage.stopAutoPage();
        tts.stopTts();
        content.cancelPendingProgressSave();
        content.cancelPendingReflow();
        content.persistProgress();
        state.pendingTapPagingDelta = 0;
        paging.removeWarmupCallbacks();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (sysMetricsReceiver != null) {
            unregisterReceiver(sysMetricsReceiver);
        }
        if (readingStatsTracker != null) {
            readingStatsTracker.shutdown();
        }
        runtime.shutdown();
        paging.cancelInteractiveAnimator();
        paging.recyclePagingSnapshots();
    }

    @Override
    public void onBackPressed() {
        if (state.controlsVisible) {
            chrome.setControlsVisible(false);
            return;
        }
        content.persistProgress();
        super.onBackPressed();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            markReadingActivity();
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
        return super.dispatchKeyEvent(event);
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

        content.attachControllers(navigation, style, paging, chrome);
        navigation.attachControllers(content, paging, chrome);
        paging.attachControllers(navigation, content, chrome);
        autoPage.attachControllers(navigation, chrome);
        tts.attachControllers(navigation, content, paging, chrome);
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

        findViewById(R.id.button_back).setOnClickListener(v -> finish());
        findViewById(R.id.button_prev_chapter).setOnClickListener(v -> navigation.openChapterFromStart(state.currentChapterIndex - 1, true, -1));
        findViewById(R.id.button_next_chapter).setOnClickListener(v -> navigation.openChapterFromStart(state.currentChapterIndex + 1, true, 1));
        findViewById(R.id.button_toc).setOnClickListener(v -> libraryDialogs.showTocDialog());
        findViewById(R.id.button_search).setOnClickListener(v -> libraryDialogs.showSearchDialog());
        findViewById(R.id.button_rules).setOnClickListener(v -> libraryDialogs.showRulesDialog());
        findViewById(R.id.button_style).setOnClickListener(v -> styleDialogs.showStyleDialog(REQUEST_PICK_BACKGROUND));
        findViewById(R.id.button_reader_options).setOnClickListener(v -> optionsDialogs.showReaderOptionsDialog());
        views.themeToggleButton.setOnClickListener(v -> chrome.toggleReaderUiTheme());
        views.ttsButton.setOnClickListener(v -> tts.showTtsDialog());
        views.autoPageButton.setOnClickListener(v -> autoPage.showAutoPageDialog());
        views.readerTitle.setOnClickListener(v -> openReadingStatsForCurrentBook());
        views.pageStage.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int width = right - left;
            int height = bottom - top;
            int oldWidth = oldRight - oldLeft;
            int oldHeight = oldBottom - oldTop;
            if (width <= 0 || height <= 0 || (width == oldWidth && height == oldHeight)) {
                return;
            }
            chrome.updateReaderHud();
            if (state.book != null && !state.chapters.isEmpty()) {
                content.scheduleReflowAfterLayout(state.currentChapterIndex, content.currentCharOffset());
            }
        });

        View.OnTouchListener keepMenuAliveListener = (view, event) -> {
            if (state.controlsVisible && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                chrome.scheduleAutoHide();
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
                int col = (int) (x / thirdW);
                int row = (int) (y / thirdH);
                state.lastTapY = y;
                if (col == 1 && row == 1) {
                    chrome.setControlsVisible(true);
                } else if (col == 0 || (col == 1 && row == 0)) {
                    navigation.requestTapPageTurn(-1);
                } else {
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
                    if (velocityX < 0) {
                        navigation.pageDown();
                    } else {
                        navigation.pageUp();
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

    public void openReadingStatsForCurrentBook() {
        if (!runtime.settingsStore.isReadingTimeTrackingEnabled() || state.book == null) {
            return;
        }
        Intent intent = new Intent(this, ReadingStatsActivity.class);
        intent.putExtra("book_id", state.book.id);
        startActivity(intent);
    }
}
