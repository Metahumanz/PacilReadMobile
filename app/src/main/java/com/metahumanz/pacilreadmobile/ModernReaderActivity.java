package com.metahumanz.pacilread;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.TextPaint;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.metahumanz.pacilread.model.BookRecord;
import com.metahumanz.pacilread.model.ChapterRecord;
import com.metahumanz.pacilread.model.ReaderThemeRecord;
import com.metahumanz.pacilread.model.ReplacementRuleRecord;
import com.metahumanz.pacilread.reader.PageSlice;
import com.metahumanz.pacilread.reader.ReaderPaginator;
import com.metahumanz.pacilread.reader.ReaderThemeConfig;
import com.metahumanz.pacilread.reader.ReplacementEngine;
import com.metahumanz.pacilread.storage.ReaderDatabaseHelper;
import com.metahumanz.pacilread.storage.SettingsStore;
import com.metahumanz.pacilread.sync.WebDavClient;
import com.metahumanz.pacilread.theme.ThemeModeHelper;
import com.metahumanz.pacilread.theme.ThemedReaderActivity;
import com.metahumanz.pacilread.tts.MimoTtsClient;
import com.metahumanz.pacilread.util.FileAssetHelper;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ModernReaderActivity extends ThemedReaderActivity implements TextToSpeech.OnInitListener {
    private static final int REQUEST_PICK_BACKGROUND = 2001;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService ttsExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<String> ttsQueue = new ArrayDeque<>();
    private final List<ChapterRecord> chapters = new ArrayList<>();
    private final List<ReplacementRuleRecord> replacementRules = new ArrayList<>();
    private final Map<Integer, String> processedChapterCache = new HashMap<>();
    private final Map<Integer, List<PageSlice>> chapterPageCache = new HashMap<>();

    private ReaderDatabaseHelper databaseHelper;
    private SettingsStore settingsStore;
    private WebDavClient webDavClient;
    private MimoTtsClient mimoTtsClient;

    private View readerRoot;
    private View hudContainer;
    private View menuTopPanel;
    private View menuInfoPanel;
    private View menuBottomPanel;
    private View pageStage;
    private View pageCurrent;
    private View pageIncoming;
    private View readerBackgroundScrim;
    private android.widget.ImageView readerBackgroundImage;
    private TextView hudLeft;
    private TextView hudCenter;
    private TextView hudRight;
    private TextView readerTitle;
    private TextView readerProgress;
    private TextView chapterMeta;
    private TextView pageMeta;
    private TextView pageTitleCurrent;
    private TextView pageBodyCurrent;
    private TextView pageTitleIncoming;
    private TextView pageBodyIncoming;
    private SeekBar progressSeekBar;
    private Button progressModeBookButton;
    private Button progressModeChapterButton;
    private Button ttsButton;
    private Button autoPageButton;

    private long bookId;
    private BookRecord book;
    private int currentChapterIndex = 0;
    private int currentPageIndex = 0;
    private int restoredChapterIndex = -1;
    private int restoredPageIndex = -1;
    private int systemInsetTop = 0;
    private int systemInsetBottom = 0;
    private int systemInsetLeft = 0;
    private int systemInsetRight = 0;
    private boolean controlsVisible = false;
    private boolean autoPageActive = false;
    private boolean ttsReady = false;
    private boolean ttsActive = false;
    private boolean isAnimating = false;
    private long animationToken = 0L;

    private GestureDetector gestureDetector;
    private TextToSpeech textToSpeech;

    private final Runnable autoHideRunnable = () -> setControlsVisible(false);
    private final Runnable saveProgressRunnable = this::persistProgress;
    private final Runnable autoPageRunnable = new Runnable() {
        @Override
        public void run() {
            if (!autoPageActive) {
                return;
            }
            if (!pageDown()) {
                stopAutoPage();
                return;
            }
            mainHandler.postDelayed(this, settingsStore.getAutoPageSeconds() * 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reader);

        databaseHelper = ReaderDatabaseHelper.getInstance(this);
        settingsStore = new SettingsStore(this);
        webDavClient = new WebDavClient(settingsStore);
        mimoTtsClient = new MimoTtsClient();
        bookId = getIntent().getLongExtra("book_id", -1L);
        if (savedInstanceState != null) {
            restoredChapterIndex = savedInstanceState.getInt("restored_chapter_index", -1);
            restoredPageIndex = savedInstanceState.getInt("restored_page_index", -1);
        }

        bindViews();
        configureReaderWindow();
        applyEdgeToEdgeInsets();
        setupGestures();
        setupControls();

        textToSpeech = new TextToSpeech(this, this);
        pageBodyCurrent.setText("正在载入...");
        loadBook();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateSystemBarsVisibility(controlsVisible);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("restored_chapter_index", currentChapterIndex);
        outState.putInt("restored_page_index", currentPageIndex);
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopAutoPage();
        stopTts();
        persistProgress();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
        ttsExecutor.shutdownNow();
        mainHandler.removeCallbacks(autoHideRunnable);
        mainHandler.removeCallbacks(autoPageRunnable);
        mainHandler.removeCallbacks(saveProgressRunnable);
        if (mimoTtsClient != null) {
            mimoTtsClient.cancel();
        }
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
        }
    }

    @Override
    public void onBackPressed() {
        persistProgress();
        super.onBackPressed();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        gestureDetector.onTouchEvent(event);
        return super.dispatchTouchEvent(event);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        if (requestCode == REQUEST_PICK_BACKGROUND) {
            attachBackground(data.getData());
        }
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            ttsReady = true;
            textToSpeech.setLanguage(Locale.SIMPLIFIED_CHINESE);
            textToSpeech.setSpeechRate(settingsStore.getTtsRate());
            textToSpeech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) {
                }

                @Override
                public void onDone(String utteranceId) {
                    mainHandler.post(ModernReaderActivity.this::speakNextChunk);
                }

                @Override
                public void onError(String utteranceId) {
                    mainHandler.post(ModernReaderActivity.this::stopTts);
                }
            });
        }
    }

    private void bindViews() {
        readerRoot = findViewById(R.id.reader_root);
        hudContainer = findViewById(R.id.hud_container);
        menuTopPanel = findViewById(R.id.menu_top_panel);
        menuInfoPanel = findViewById(R.id.menu_info_panel);
        menuBottomPanel = findViewById(R.id.menu_bottom_panel);
        pageStage = findViewById(R.id.page_stage);
        pageCurrent = findViewById(R.id.page_current);
        pageIncoming = findViewById(R.id.page_incoming);
        readerBackgroundImage = findViewById(R.id.reader_background_image);
        readerBackgroundScrim = findViewById(R.id.reader_background_scrim);
        hudLeft = findViewById(R.id.text_hud_left);
        hudCenter = findViewById(R.id.text_hud_center);
        hudRight = findViewById(R.id.text_hud_right);
        readerTitle = findViewById(R.id.text_reader_title);
        readerProgress = findViewById(R.id.text_progress);
        chapterMeta = findViewById(R.id.text_chapter_meta);
        pageMeta = findViewById(R.id.text_page_meta);
        pageTitleCurrent = findViewById(R.id.text_page_title_current);
        pageBodyCurrent = findViewById(R.id.text_page_body_current);
        pageTitleIncoming = findViewById(R.id.text_page_title_incoming);
        pageBodyIncoming = findViewById(R.id.text_page_body_incoming);
        progressSeekBar = findViewById(R.id.seek_reader_progress);
        progressModeBookButton = findViewById(R.id.button_progress_book);
        progressModeChapterButton = findViewById(R.id.button_progress_chapter);
        ttsButton = findViewById(R.id.button_tts);
        autoPageButton = findViewById(R.id.button_auto_page);
    }

    private void configureReaderWindow() {
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(attributes);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
        }
        updateSystemBarsVisibility(false);
    }

    private void applyEdgeToEdgeInsets() {
        readerRoot.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Insets insets = windowInsets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                systemInsetLeft = insets.left;
                systemInsetTop = insets.top;
                systemInsetRight = insets.right;
                systemInsetBottom = insets.bottom;
            } else {
                systemInsetLeft = windowInsets.getSystemWindowInsetLeft();
                systemInsetTop = windowInsets.getSystemWindowInsetTop();
                systemInsetRight = windowInsets.getSystemWindowInsetRight();
                systemInsetBottom = windowInsets.getSystemWindowInsetBottom();
            }
            updateReaderLayoutInsets();
            return windowInsets;
        });
        readerRoot.requestApplyInsets();
    }

    private void updateReaderLayoutInsets() {
        hudContainer.setPadding(
                dp(14) + systemInsetLeft,
                dp(8) + systemInsetTop,
                dp(14) + systemInsetRight,
                dp(4)
        );
        pageStage.setPadding(
                dp(2) + systemInsetLeft,
                systemInsetTop,
                dp(2) + systemInsetRight,
                systemInsetBottom
        );
        updateFrameLayoutMargins(menuTopPanel,
                dp(10) + systemInsetLeft,
                dp(10) + systemInsetTop,
                dp(10) + systemInsetRight,
                0
        );
        updateFrameLayoutMargins(menuInfoPanel,
                dp(10) + systemInsetLeft,
                0,
                dp(10) + systemInsetRight,
                dp(110) + systemInsetBottom
        );
        updateFrameLayoutMargins(menuBottomPanel,
                dp(10) + systemInsetLeft,
                0,
                dp(10) + systemInsetRight,
                dp(8) + systemInsetBottom
        );
    }

    private void updateFrameLayoutMargins(View view, int left, int top, int right, int bottom) {
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) view.getLayoutParams();
        params.leftMargin = left;
        params.topMargin = top;
        params.rightMargin = right;
        params.bottomMargin = bottom;
        view.setLayoutParams(params);
    }

    private void setupControls() {
        findViewById(R.id.button_back).setOnClickListener(v -> finish());
        findViewById(R.id.button_prev_chapter).setOnClickListener(v -> openChapter(currentChapterIndex - 1, 0, true, -1));
        findViewById(R.id.button_next_chapter).setOnClickListener(v -> openChapter(currentChapterIndex + 1, 0, true, 1));
        findViewById(R.id.button_toc).setOnClickListener(v -> showTocDialog());
        findViewById(R.id.button_search).setOnClickListener(v -> showSearchDialog());
        findViewById(R.id.button_rules).setOnClickListener(v -> showRulesDialog());
        findViewById(R.id.button_style).setOnClickListener(v -> showStyleDialog());
        findViewById(R.id.button_reader_options).setOnClickListener(v -> showReaderOptionsDialog());
        findViewById(R.id.button_sync).setOnClickListener(v -> syncFromWebDav(false));
        progressModeBookButton.setOnClickListener(v -> {
            settingsStore.setReaderSliderMode("book");
            updateUiAfterPageChange();
            setControlsVisible(true);
        });
        progressModeChapterButton.setOnClickListener(v -> {
            settingsStore.setReaderSliderMode("chapter");
            updateUiAfterPageChange();
            setControlsVisible(true);
        });
        ttsButton.setOnClickListener(v -> showTtsDialog());
        autoPageButton.setOnClickListener(v -> showAutoPageDialog());
        View.OnTouchListener keepMenuAliveListener = (view, event) -> {
            if (controlsVisible && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                scheduleAutoHide();
            }
            return false;
        };
        menuTopPanel.setOnTouchListener(keepMenuAliveListener);
        menuInfoPanel.setOnTouchListener(keepMenuAliveListener);
        menuBottomPanel.setOnTouchListener(keepMenuAliveListener);
        progressSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                setControlsVisible(true);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if ("book".equals(settingsStore.getReaderSliderMode())) {
                    int chapterIndex = clamp(seekBar.getProgress(), 0, chapters.size() - 1);
                    int direction = chapterIndex >= currentChapterIndex ? 1 : -1;
                    openChapter(chapterIndex, 0, true, direction);
                    return;
                }
                int direction = seekBar.getProgress() >= currentPageIndex ? 1 : -1;
                showPage(currentChapterIndex, seekBar.getProgress(), true, direction);
            }
        });
    }

    private void setupGestures() {
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (controlsVisible) {
                    if (isInsideView(e, menuTopPanel) || isInsideView(e, menuInfoPanel) || isInsideView(e, menuBottomPanel)) {
                        return false;
                    }
                    setControlsVisible(false);
                    return true;
                }
                float width = readerRoot.getWidth();
                float third = width / 3f;
                if (e.getX() < third) {
                    pageUp();
                } else if (e.getX() > third * 2f) {
                    pageDown();
                } else {
                    setControlsVisible(true);
                }
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (controlsVisible || isAnimating) {
                    return false;
                }
                if (Math.abs(velocityX) > Math.abs(velocityY) * 1.3f && Math.abs(velocityX) > 700f) {
                    if (velocityX < 0) {
                        pageDown();
                    } else {
                        pageUp();
                    }
                    return true;
                }
                return false;
            }
        });
    }

    private void loadBook() {
        executor.execute(() -> {
            BookRecord loadedBook = databaseHelper.getBook(bookId);
            List<ChapterRecord> loadedChapters = databaseHelper.getChapters(bookId);
            List<ReplacementRuleRecord> loadedRules = databaseHelper.getReplacementRules(bookId);
            runOnUiThread(() -> {
                if (loadedBook == null || loadedChapters.isEmpty()) {
                    showToast("书籍不存在或内容为空");
                    finish();
                    return;
                }
                book = loadedBook;
                chapters.clear();
                chapters.addAll(loadedChapters);
                replacementRules.clear();
                replacementRules.addAll(loadedRules);
                currentChapterIndex = clamp(chapterIndexFromOrder(loadedBook.progressIndex), 0, chapters.size() - 1);
                applyReaderSettings();
                if (restoredChapterIndex >= 0) {
                    showPage(
                            clamp(restoredChapterIndex, 0, chapters.size() - 1),
                            Math.max(restoredPageIndex, 0),
                            false,
                            0
                    );
                    restoredChapterIndex = -1;
                    restoredPageIndex = -1;
                } else {
                    openChapter(currentChapterIndex, loadedBook.progressOffset, false, 0);
                    syncFromWebDav(true);
                }
            });
        });
    }

    private void openChapter(int chapterIndex, int charOffset, boolean animate, int direction) {
        if (chapters.isEmpty()) {
            return;
        }
        if (!ensurePageAreaReady(() -> openChapter(chapterIndex, charOffset, animate, direction))) {
            return;
        }
        int safeChapterIndex = clamp(chapterIndex, 0, chapters.size() - 1);
        List<PageSlice> pages = getPagesForChapter(safeChapterIndex);
        int pageIndex = ReaderPaginator.findPageForOffset(pages, Math.max(charOffset, 0));
        showPage(safeChapterIndex, pageIndex, animate, direction);
    }

    private void showPage(int chapterIndex, int pageIndex, boolean animate, int direction) {
        if (chapters.isEmpty()) {
            return;
        }
        if (!ensurePageAreaReady(() -> showPage(chapterIndex, pageIndex, animate, direction))) {
            return;
        }
        int safeChapterIndex = clamp(chapterIndex, 0, chapters.size() - 1);
        List<PageSlice> pages = getPagesForChapter(safeChapterIndex);
        int safePageIndex = clamp(pageIndex, 0, pages.size() - 1);
        if (!animate || isAnimating || book == null) {
            bindPage(pageTitleCurrent, pageBodyCurrent, safeChapterIndex, safePageIndex);
            currentChapterIndex = safeChapterIndex;
            currentPageIndex = safePageIndex;
            resetAnimatedPage(pageCurrent);
            resetAnimatedPage(pageIncoming);
            pageIncoming.setVisibility(View.GONE);
            updateUiAfterPageChange();
            scheduleProgressSave();
            scheduleAutoHide();
            return;
        }
        bindPage(pageTitleIncoming, pageBodyIncoming, safeChapterIndex, safePageIndex);
        pageIncoming.setVisibility(View.VISIBLE);
        animateTransition(safeChapterIndex, safePageIndex, direction == 0 ? 1 : direction);
    }

    private boolean pageDown() {
        if (chapters.isEmpty() || isAnimating) {
            return false;
        }
        List<PageSlice> pages = getPagesForChapter(currentChapterIndex);
        if (currentPageIndex < pages.size() - 1) {
            showPage(currentChapterIndex, currentPageIndex + 1, true, 1);
            return true;
        }
        if (currentChapterIndex < chapters.size() - 1) {
            openChapter(currentChapterIndex + 1, 0, true, 1);
            return true;
        }
        return false;
    }

    private boolean pageUp() {
        if (chapters.isEmpty() || isAnimating) {
            return false;
        }
        if (currentPageIndex > 0) {
            showPage(currentChapterIndex, currentPageIndex - 1, true, -1);
            return true;
        }
        if (currentChapterIndex > 0) {
            List<PageSlice> pages = getPagesForChapter(currentChapterIndex - 1);
            showPage(currentChapterIndex - 1, pages.size() - 1, true, -1);
            return true;
        }
        return false;
    }

    private void bindPage(TextView titleView, TextView bodyView, int chapterIndex, int pageIndex) {
        ChapterRecord chapter = chapters.get(chapterIndex);
        List<PageSlice> pages = getPagesForChapter(chapterIndex);
        PageSlice slice = pages.get(clamp(pageIndex, 0, pages.size() - 1));
        boolean showTitle = settingsStore.isChapterTitleVisible() && pageIndex == 0;
        titleView.setVisibility(showTitle ? View.VISIBLE : View.GONE);
        titleView.setText(chapter.title);
        updateBodyTopMargin(bodyView, showTitle ? dp(16) : 0);
        bodyView.setText(slice.text == null ? "" : slice.text.toString().trim());
    }

    private void updateUiAfterPageChange() {
        if (book == null || chapters.isEmpty()) {
            return;
        }
        List<PageSlice> pages = getPagesForChapter(currentChapterIndex);
        ChapterRecord chapter = chapters.get(currentChapterIndex);
        int safePageCount = Math.max(pages.size(), 1);
        readerTitle.setText(book.title);
        chapterMeta.setText(String.format(Locale.SIMPLIFIED_CHINESE, "第 %d/%d 章 · %s", currentChapterIndex + 1, chapters.size(), chapter.title));
        if ("book".equals(settingsStore.getReaderSliderMode())) {
            pageMeta.setText(String.format(Locale.SIMPLIFIED_CHINESE, "第 %d/%d 页 · 全书章节", currentPageIndex + 1, safePageCount));
            progressSeekBar.setMax(Math.max(chapters.size() - 1, 0));
            progressSeekBar.setProgress(currentChapterIndex);
        } else {
            pageMeta.setText(String.format(Locale.SIMPLIFIED_CHINESE, "第 %d/%d 页 · 本章页数", currentPageIndex + 1, safePageCount));
            progressSeekBar.setMax(Math.max(safePageCount - 1, 0));
            progressSeekBar.setProgress(currentPageIndex);
        }
        int percent = Math.round(((currentChapterIndex + (currentPageIndex / (float) safePageCount)) / Math.max(chapters.size(), 1)) * 100f);
        readerProgress.setText(percent + "%");
        hudLeft.setText(String.format(Locale.SIMPLIFIED_CHINESE, "第 %d 章", currentChapterIndex + 1));
        hudCenter.setText(book.title == null ? "" : book.title);
        hudRight.setText(percent + "%");
        styleReaderMenuButton(progressModeBookButton, "book".equals(settingsStore.getReaderSliderMode()));
        styleReaderMenuButton(progressModeChapterButton, "chapter".equals(settingsStore.getReaderSliderMode()));
        styleReaderMenuButton(ttsButton, ttsActive);
        styleReaderMenuButton(autoPageButton, autoPageActive);
    }

    private void animateTransition(int targetChapterIndex, int targetPageIndex, int direction) {
        long token = ++animationToken;
        isAnimating = true;
        String mode = settingsStore.getFlipMode();
        float width = Math.max(pageStage.getWidth(), dp(240));
        resetAnimatedPage(pageCurrent);
        resetAnimatedPage(pageIncoming);
        if ("none".equals(mode)) {
            finishAnimation(targetChapterIndex, targetPageIndex, token);
            return;
        }
        if ("fade".equals(mode)) {
            pageIncoming.setAlpha(0f);
            pageCurrent.animate().alpha(0f).setDuration(180L).start();
            pageIncoming.animate().alpha(1f).setDuration(220L).withEndAction(() -> finishAnimation(targetChapterIndex, targetPageIndex, token)).start();
            return;
        }
        if ("cover".equals(mode)) {
            pageIncoming.setTranslationX(direction > 0 ? width : -width);
            pageIncoming.animate().translationX(0f).setDuration(220L).withEndAction(() -> finishAnimation(targetChapterIndex, targetPageIndex, token)).start();
            return;
        }
        if ("flip".equals(mode)) {
            pageCurrent.setCameraDistance(width * 12f);
            pageIncoming.setCameraDistance(width * 12f);
            pageIncoming.setRotationY(direction > 0 ? -72f : 72f);
            pageIncoming.setAlpha(0.45f);
            pageCurrent.animate().rotationY(direction > 0 ? 72f : -72f).alpha(0.08f).setDuration(220L).start();
            pageIncoming.animate().rotationY(0f).alpha(1f).setDuration(220L).withEndAction(() -> finishAnimation(targetChapterIndex, targetPageIndex, token)).start();
            return;
        }
        pageIncoming.setTranslationX(direction > 0 ? width : -width);
        pageCurrent.animate().translationX(direction > 0 ? -width * 0.28f : width * 0.28f).alpha(0.12f).setDuration(220L).start();
        pageIncoming.animate().translationX(0f).setDuration(220L).withEndAction(() -> finishAnimation(targetChapterIndex, targetPageIndex, token)).start();
    }

    private void finishAnimation(int targetChapterIndex, int targetPageIndex, long token) {
        if (token != animationToken) {
            return;
        }
        bindPage(pageTitleCurrent, pageBodyCurrent, targetChapterIndex, targetPageIndex);
        currentChapterIndex = targetChapterIndex;
        currentPageIndex = targetPageIndex;
        resetAnimatedPage(pageCurrent);
        resetAnimatedPage(pageIncoming);
        pageIncoming.setVisibility(View.GONE);
        isAnimating = false;
        updateUiAfterPageChange();
        scheduleProgressSave();
        scheduleAutoHide();
    }

    private void showTocDialog() {
        if (chapters.isEmpty()) {
            return;
        }
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_toc, null, false);
        ListView listView = content.findViewById(R.id.toc_list);
        List<String> items = new ArrayList<>();
        for (int i = 0; i < chapters.size(); i++) {
            items.add(String.format(Locale.SIMPLIFIED_CHINESE, "%03d  %s", i + 1, chapters.get(i).title));
        }
        ArrayAdapter<String> adapter = buildDialogListAdapter(items);
        listView.setAdapter(adapter);
        listView.setSelection(currentChapterIndex);
        AlertDialog dialog = new AlertDialog.Builder(this).setView(content).create();
        listView.setOnItemClickListener((parent, view, position, id) -> {
            dialog.dismiss();
            openChapter(position, 0, true, position >= currentChapterIndex ? 1 : -1);
        });
        showStyledDialog(dialog);
    }

    private void toggleTts() {
        if (ttsActive) {
            stopTts();
            return;
        }
        String engine = settingsStore.getTtsEngine();
        if ("mimo".equals(engine) && settingsStore.getTtsMimoApiKey().isBlank()) {
            showToast("请先填写 MiMo API Key");
            return;
        }
        if (!"mimo".equals(engine) && (!ttsReady || textToSpeech == null)) {
            showToast("语音引擎尚未就绪");
            return;
        }
        ttsQueue.clear();
        ttsQueue.addAll(splitForSpeech(remainingTextFromCurrentPosition()));
        if (ttsQueue.isEmpty()) {
            showToast("当前位置没有可朗读的文本");
            return;
        }
        ttsActive = true;
        ttsButton.setText("停止朗读");
        styleReaderMenuButton(ttsButton, true);
        speakNextChunk();
    }

    private void speakNextChunk() {
        if (!ttsActive) {
            return;
        }
        if (ttsQueue.isEmpty()) {
            if (currentChapterIndex < chapters.size() - 1) {
                openChapter(currentChapterIndex + 1, 0, true, 1);
                ttsQueue.addAll(splitForSpeech(getProcessedChapterText(currentChapterIndex)));
            } else {
                stopTts();
                return;
            }
        }
        String chunk = ttsQueue.poll();
        if (chunk == null || chunk.isBlank()) {
            speakNextChunk();
            return;
        }
        if ("mimo".equals(settingsStore.getTtsEngine())) {
            ttsExecutor.execute(() -> {
                try {
                    mimoTtsClient.speak(chunk, settingsStore.getTtsMimoApiKey(), settingsStore.getTtsRate());
                    runOnUiThread(() -> {
                        if (ttsActive) {
                            speakNextChunk();
                        }
                    });
                } catch (Exception error) {
                    runOnUiThread(() -> {
                        if (ttsActive) {
                            stopTts();
                            showToast("MiMo 听书失败: " + error.getMessage());
                        }
                    });
                }
            });
            return;
        }
        if (textToSpeech == null) {
            stopTts();
            return;
        }
        textToSpeech.setSpeechRate(settingsStore.getTtsRate());
        textToSpeech.speak(chunk, TextToSpeech.QUEUE_FLUSH, null, "reader-chunk");
    }

    private void stopTts() {
        ttsActive = false;
        ttsQueue.clear();
        if (mimoTtsClient != null) {
            mimoTtsClient.cancel();
        }
        if (textToSpeech != null) {
            textToSpeech.stop();
        }
        ttsButton.setText(getString(R.string.reader_tts));
        styleReaderMenuButton(ttsButton, false);
    }

    private void stopAutoPage() {
        autoPageActive = false;
        mainHandler.removeCallbacks(autoPageRunnable);
        autoPageButton.setText(getString(R.string.reader_auto_page));
        styleReaderMenuButton(autoPageButton, false);
    }

    private void applyReaderSettings() {
        ReaderThemePalette palette = ReaderThemePalette.from(settingsStore.getReaderTheme());
        readerRoot.setBackgroundColor(palette.backgroundColor);
        readerBackgroundScrim.setBackgroundColor(palette.overlayColor);
        stylePageContainer(pageCurrent, palette.pageColor);
        stylePageContainer(pageIncoming, palette.pageColor);
        pageTitleCurrent.setTextColor(palette.textColor);
        pageTitleIncoming.setTextColor(palette.textColor);
        pageBodyCurrent.setTextColor(palette.textColor);
        pageBodyIncoming.setTextColor(palette.textColor);
        pageTitleCurrent.setTextSize(settingsStore.getFontSizeSp() + 2f);
        pageTitleIncoming.setTextSize(settingsStore.getFontSizeSp() + 2f);
        pageBodyCurrent.setTextSize(settingsStore.getFontSizeSp());
        pageBodyIncoming.setTextSize(settingsStore.getFontSizeSp());
        pageBodyCurrent.setLineSpacing(settingsStore.getLineSpacingExtraSp(), 1f);
        pageBodyIncoming.setLineSpacing(settingsStore.getLineSpacingExtraSp(), 1f);
        pageBodyCurrent.setJustificationMode(android.text.Layout.JUSTIFICATION_MODE_INTER_WORD);
        pageBodyIncoming.setJustificationMode(android.text.Layout.JUSTIFICATION_MODE_INTER_WORD);
        int sidePadding = dp(settingsStore.getSidePaddingDp());
        int verticalPadding = dp(settingsStore.getVerticalPaddingDp());
        ((ViewGroup) pageCurrent).setPadding(sidePadding, verticalPadding, sidePadding, verticalPadding);
        ((ViewGroup) pageIncoming).setPadding(sidePadding, verticalPadding, sidePadding, verticalPadding);
        if (settingsStore.isKeepScreenOn()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        applyBackgroundImage();
        updateSystemBarsVisibility(controlsVisible);
    }

    private void persistProgress() {
        if (book == null || chapters.isEmpty()) {
            return;
        }
        int offset = currentCharOffset();
        ChapterRecord chapter = chapters.get(currentChapterIndex);
        executor.execute(() -> {
            databaseHelper.updateProgress(book.id, chapter.orderIndex, offset);
            book.progressIndex = chapter.orderIndex;
            book.progressOffset = offset;
            book.lastReadAt = System.currentTimeMillis();
            if (settingsStore.isWebDavEnabled()) {
                try {
                    webDavClient.ensureProgressDirectory();
                    webDavClient.uploadProgress(book, chapter, offset);
                } catch (Exception ignore) {
                }
            }
        });
    }

    private void scheduleProgressSave() {
        mainHandler.removeCallbacks(saveProgressRunnable);
        mainHandler.postDelayed(saveProgressRunnable, 600L);
    }

    private void showSearchDialog() {
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_search, null, false);
        EditText queryInput = content.findViewById(R.id.search_query_input);
        Button searchButton = content.findViewById(R.id.search_button_go);
        TextView resultCount = content.findViewById(R.id.search_result_count);
        ListView listView = content.findViewById(R.id.search_result_list);
        List<SearchResult> results = new ArrayList<>();
        ArrayAdapter<String> adapter = buildDialogListAdapter(new ArrayList<>());
        listView.setAdapter(adapter);
        AlertDialog dialog = new AlertDialog.Builder(this).setView(content).create();
        listView.setOnItemClickListener((parent, view, position, id) -> {
            SearchResult result = results.get(position);
            dialog.dismiss();
            openChapter(result.chapterIndex, result.charOffset, true, result.chapterIndex >= currentChapterIndex ? 1 : -1);
        });
        searchButton.setOnClickListener(v -> {
            String query = queryInput.getText().toString().trim().toLowerCase(Locale.ROOT);
            if (query.isEmpty()) {
                resultCount.setText("请输入关键词");
                return;
            }
            results.clear();
            adapter.clear();
            for (int i = 0; i < chapters.size(); i++) {
                String text = getProcessedChapterText(i);
                int index = text.toLowerCase(Locale.ROOT).indexOf(query);
                if (index >= 0) {
                    String snippet = text.substring(Math.max(0, index - 18), Math.min(text.length(), index + query.length() + 24)).replace('\n', ' ').trim();
                    results.add(new SearchResult(i, chapters.get(i).title, snippet, index));
                    adapter.add(chapters.get(i).title + "\n" + snippet);
                }
            }
            resultCount.setText(results.isEmpty() ? "没有找到匹配内容" : "找到 " + results.size() + " 条结果");
        });
        showStyledDialog(dialog);
    }

    private void showRulesDialog() {
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_rules, null, false);
        EditText patternInput = content.findViewById(R.id.rules_input_pattern);
        EditText replacementInput = content.findViewById(R.id.rules_input_replacement);
        CheckBox globalCheck = content.findViewById(R.id.rules_check_global);
        CheckBox regexCheck = content.findViewById(R.id.rules_check_regex);
        Button addButton = content.findViewById(R.id.rules_button_add);
        TextView hintText = content.findViewById(R.id.rules_text_hint);
        ListView listView = content.findViewById(R.id.rules_list);
        ArrayAdapter<String> adapter = buildDialogListAdapter(new ArrayList<>());
        listView.setAdapter(adapter);
        hintText.setText("点击列表切换启用状态，长按删除。");
        refreshRuleLabels(adapter);
        AlertDialog dialog = new AlertDialog.Builder(this).setView(content).create();
        addButton.setOnClickListener(v -> {
            String pattern = patternInput.getText().toString();
            if (pattern.trim().isEmpty()) {
                showToast("请先输入查找内容");
                return;
            }
            int offset = currentCharOffset();
            executor.execute(() -> {
                databaseHelper.addReplacementRule(pattern, replacementInput.getText().toString(), globalCheck.isChecked(), bookId, regexCheck.isChecked());
                List<ReplacementRuleRecord> rules = databaseHelper.getReplacementRules(bookId);
                runOnUiThread(() -> {
                    replacementRules.clear();
                    replacementRules.addAll(rules);
                    clearAllReaderCaches();
                    refreshRuleLabels(adapter);
                    patternInput.setText("");
                    replacementInput.setText("");
                    regexCheck.setChecked(false);
                    openChapter(currentChapterIndex, offset, false, 0);
                });
            });
        });
        listView.setOnItemClickListener((parent, view, position, id) -> {
            ReplacementRuleRecord rule = replacementRules.get(position);
            int offset = currentCharOffset();
            executor.execute(() -> {
                databaseHelper.toggleReplacementRule(rule.id, !rule.active);
                List<ReplacementRuleRecord> rules = databaseHelper.getReplacementRules(bookId);
                runOnUiThread(() -> {
                    replacementRules.clear();
                    replacementRules.addAll(rules);
                    clearAllReaderCaches();
                    refreshRuleLabels(adapter);
                    openChapter(currentChapterIndex, offset, false, 0);
                });
            });
        });
        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            ReplacementRuleRecord rule = replacementRules.get(position);
            executor.execute(() -> {
                databaseHelper.deleteReplacementRule(rule.id);
                List<ReplacementRuleRecord> rules = databaseHelper.getReplacementRules(bookId);
                runOnUiThread(() -> {
                    replacementRules.clear();
                    replacementRules.addAll(rules);
                    clearAllReaderCaches();
                    refreshRuleLabels(adapter);
                });
            });
            return true;
        });
        showStyledDialog(dialog);
    }

    private void showStyleDialog() {
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_reader_style, null, false);
        SeekBar fontSeek = content.findViewById(R.id.style_seek_font);
        SeekBar lineSeek = content.findViewById(R.id.style_seek_line_spacing);
        SeekBar sideSeek = content.findViewById(R.id.style_seek_side_padding);
        SeekBar verticalSeek = content.findViewById(R.id.style_seek_vertical_padding);
        TextView fontValue = content.findViewById(R.id.style_text_font);
        TextView lineValue = content.findViewById(R.id.style_text_line_spacing);
        TextView sideValue = content.findViewById(R.id.style_text_side_padding);
        TextView verticalValue = content.findViewById(R.id.style_text_vertical_padding);
        Spinner uiThemeSpinner = content.findViewById(R.id.style_spinner_ui_theme_mode);
        CheckBox keepScreenOn = content.findViewById(R.id.style_check_keep_screen_on);
        CheckBox showTitleCheck = content.findViewById(R.id.style_check_show_title);
        TextView backgroundText = content.findViewById(R.id.style_text_background);
        LinearLayout customThemeList = content.findViewById(R.id.style_custom_theme_list);
        Button paperThemeButton = content.findViewById(R.id.style_button_theme_paper);
        Button forestThemeButton = content.findViewById(R.id.style_button_theme_forest);
        Button nightThemeButton = content.findViewById(R.id.style_button_theme_night);
        String[] uiThemeKeys = new String[]{"follow_app", "system", "light", "dark"};
        ArrayAdapter<String> uiThemeAdapter = buildSpinnerAdapter(new String[]{"跟随应用", "跟随系统", "浅色", "深色"});
        uiThemeSpinner.setAdapter(uiThemeAdapter);
        fontSeek.setProgress(Math.round(settingsStore.getFontSizeSp()) - 12);
        lineSeek.setProgress(Math.round(settingsStore.getLineSpacingExtraSp()));
        sideSeek.setProgress(settingsStore.getSidePaddingDp() - 8);
        verticalSeek.setProgress(settingsStore.getVerticalPaddingDp() - 8);
        keepScreenOn.setChecked(settingsStore.isKeepScreenOn());
        showTitleCheck.setChecked(settingsStore.isChapterTitleVisible());
        backgroundText.setText(currentBackgroundLabel());
        uiThemeSpinner.setSelection(indexOf(uiThemeKeys, settingsStore.getReaderUiThemeMode(), 0));
        final String[] selectedReaderTheme = new String[]{settingsStore.getReaderTheme()};
        updateReaderThemeButtons(paperThemeButton, forestThemeButton, nightThemeButton, selectedReaderTheme[0]);
        paperThemeButton.setOnClickListener(v -> {
            selectedReaderTheme[0] = "paper";
            updateReaderThemeButtons(paperThemeButton, forestThemeButton, nightThemeButton, selectedReaderTheme[0]);
        });
        forestThemeButton.setOnClickListener(v -> {
            selectedReaderTheme[0] = "forest";
            updateReaderThemeButtons(paperThemeButton, forestThemeButton, nightThemeButton, selectedReaderTheme[0]);
        });
        nightThemeButton.setOnClickListener(v -> {
            selectedReaderTheme[0] = "night";
            updateReaderThemeButtons(paperThemeButton, forestThemeButton, nightThemeButton, selectedReaderTheme[0]);
        });
        updateStyleLabels(fontValue, lineValue, sideValue, verticalValue, fontSeek, lineSeek, sideSeek, verticalSeek);
        SeekBar.OnSeekBarChangeListener listener = new SimpleSeekListener(() -> updateStyleLabels(fontValue, lineValue, sideValue, verticalValue, fontSeek, lineSeek, sideSeek, verticalSeek));
        fontSeek.setOnSeekBarChangeListener(listener);
        lineSeek.setOnSeekBarChangeListener(listener);
        sideSeek.setOnSeekBarChangeListener(listener);
        verticalSeek.setOnSeekBarChangeListener(listener);
        AlertDialog dialog = new AlertDialog.Builder(this).setView(content).create();
        renderThemeRows(customThemeList, dialog);
        content.findViewById(R.id.style_button_pick_background).setOnClickListener(v -> openBackgroundPicker());
        content.findViewById(R.id.style_button_clear_background).setOnClickListener(v -> {
            FileAssetHelper.deleteIfExists(settingsStore.getReaderBackgroundPath());
            settingsStore.setReaderBackgroundPath("");
            backgroundText.setText(currentBackgroundLabel());
            applyReaderSettings();
        });
        content.findViewById(R.id.style_button_save_theme).setOnClickListener(v -> promptSaveTheme(() -> renderThemeRows(customThemeList, dialog)));
        content.findViewById(R.id.style_button_cancel).setOnClickListener(v -> dialog.dismiss());
        content.findViewById(R.id.style_button_apply).setOnClickListener(v -> {
            String previousResolvedUiMode = ThemeModeHelper.getResolvedReaderThemeMode(this);
            settingsStore.setFontSizeSp(fontSeek.getProgress() + 12f);
            settingsStore.setLineSpacingExtraSp(lineSeek.getProgress());
            settingsStore.setSidePaddingDp(sideSeek.getProgress() + 8);
            settingsStore.setVerticalPaddingDp(verticalSeek.getProgress() + 8);
            settingsStore.setKeepScreenOn(keepScreenOn.isChecked());
            settingsStore.setChapterTitleVisible(showTitleCheck.isChecked());
            settingsStore.setReaderUiThemeMode(uiThemeKeys[uiThemeSpinner.getSelectedItemPosition()]);
            settingsStore.setReaderTheme(selectedReaderTheme[0]);
            String nextResolvedUiMode = ThemeModeHelper.getResolvedReaderThemeMode(this);
            clearPageCache();
            dialog.dismiss();
            if (!previousResolvedUiMode.equals(nextResolvedUiMode)) {
                recreate();
                return;
            }
            applyReaderSettings();
            openChapter(currentChapterIndex, currentCharOffset(), false, 0);
        });
        showStyledDialog(dialog);
    }

    private void showReaderOptionsDialog() {
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_reader_options, null, false);
        EditText titleInput = content.findViewById(R.id.options_input_title);
        EditText authorInput = content.findViewById(R.id.options_input_author);
        Spinner flipSpinner = content.findViewById(R.id.options_spinner_flip_mode);
        Button sliderBookButton = content.findViewById(R.id.options_button_slider_book);
        Button sliderChapterButton = content.findViewById(R.id.options_button_slider_chapter);
        CheckBox showTitleCheck = content.findViewById(R.id.options_check_show_title);
        titleInput.setText(book == null ? "" : book.title);
        authorInput.setText(book == null ? "" : book.author);
        showTitleCheck.setChecked(settingsStore.isChapterTitleVisible());
        String[] flipKeys = new String[]{"slide", "cover", "fade", "flip", "none"};
        ArrayAdapter<String> adapter = buildSpinnerAdapter(new String[]{"平移", "覆盖", "淡入", "翻折", "无动画"});
        flipSpinner.setAdapter(adapter);
        flipSpinner.setSelection(indexOf(flipKeys, settingsStore.getFlipMode(), 0));
        final String[] sliderMode = new String[]{settingsStore.getReaderSliderMode()};
        styleThemeButton(sliderBookButton, "book".equals(sliderMode[0]));
        styleThemeButton(sliderChapterButton, "chapter".equals(sliderMode[0]));
        sliderBookButton.setOnClickListener(v -> {
            sliderMode[0] = "book";
            styleThemeButton(sliderBookButton, true);
            styleThemeButton(sliderChapterButton, false);
        });
        sliderChapterButton.setOnClickListener(v -> {
            sliderMode[0] = "chapter";
            styleThemeButton(sliderBookButton, false);
            styleThemeButton(sliderChapterButton, true);
        });
        AlertDialog dialog = new AlertDialog.Builder(this).setView(content).create();
        content.findViewById(R.id.options_button_cancel).setOnClickListener(v -> dialog.dismiss());
        content.findViewById(R.id.options_button_apply).setOnClickListener(v -> {
            String title = titleInput.getText().toString().trim();
            String author = authorInput.getText().toString().trim();
            if (title.isEmpty()) {
                title = "未命名书籍";
            }
            String finalTitle = title;
            String finalAuthor = author;
            executor.execute(() -> {
                databaseHelper.updateBookInfo(bookId, finalTitle, finalAuthor);
                runOnUiThread(() -> {
                    if (book != null) {
                        book.title = finalTitle;
                        book.author = finalAuthor;
                    }
                    settingsStore.setFlipMode(flipKeys[flipSpinner.getSelectedItemPosition()]);
                    settingsStore.setReaderSliderMode(sliderMode[0]);
                    settingsStore.setChapterTitleVisible(showTitleCheck.isChecked());
                    clearPageCache();
                    applyReaderSettings();
                    openChapter(currentChapterIndex, currentCharOffset(), false, 0);
                    dialog.dismiss();
                });
            });
        });
        showStyledDialog(dialog);
    }

    private void showAutoPageDialog() {
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_auto_page, null, false);
        SeekBar seekBar = content.findViewById(R.id.auto_page_seek);
        TextView valueText = content.findViewById(R.id.auto_page_value);
        Button toggleButton = content.findViewById(R.id.auto_page_button_toggle);
        seekBar.setProgress(settingsStore.getAutoPageSeconds() - 3);
        valueText.setText(String.format(Locale.SIMPLIFIED_CHINESE, "%d 秒", settingsStore.getAutoPageSeconds()));
        seekBar.setOnSeekBarChangeListener(new SimpleSeekListener(() -> valueText.setText(String.format(Locale.SIMPLIFIED_CHINESE, "%d 秒", seekBar.getProgress() + 3))));
        toggleButton.setText(autoPageActive ? "停止自动翻页" : "开始自动翻页");
        AlertDialog dialog = new AlertDialog.Builder(this).setView(content).create();
        toggleButton.setOnClickListener(v -> {
            settingsStore.setAutoPageSeconds(seekBar.getProgress() + 3);
            if (autoPageActive) {
                stopAutoPage();
            } else {
                autoPageActive = true;
                autoPageButton.setText("停止自动");
                styleReaderMenuButton(autoPageButton, true);
                mainHandler.postDelayed(autoPageRunnable, settingsStore.getAutoPageSeconds() * 1000L);
            }
            dialog.dismiss();
        });
        showStyledDialog(dialog);
    }

    private void showTtsDialog() {
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_tts, null, false);
        Spinner engineSpinner = content.findViewById(R.id.tts_spinner_engine);
        SeekBar seekBar = content.findViewById(R.id.tts_seek_rate);
        TextView valueText = content.findViewById(R.id.tts_text_rate);
        EditText mimoKeyInput = content.findViewById(R.id.tts_input_mimo_api_key);
        TextView noteText = content.findViewById(R.id.tts_text_note);
        Button toggleButton = content.findViewById(R.id.tts_button_toggle);
        String[] engineKeys = new String[]{"system", "mimo"};
        ArrayAdapter<String> engineAdapter = buildSpinnerAdapter(new String[]{"本地系统 TTS", "小米 MiMo"});
        engineSpinner.setAdapter(engineAdapter);
        engineSpinner.setSelection(indexOf(engineKeys, settingsStore.getTtsEngine(), 0));
        seekBar.setProgress(clamp(Math.round((settingsStore.getTtsRate() - 0.5f) * 10f), 0, 15));
        valueText.setText(String.format(Locale.SIMPLIFIED_CHINESE, "%.1f 倍", settingsStore.getTtsRate()));
        mimoKeyInput.setText(settingsStore.getTtsMimoApiKey());
        updateTtsDialogState(noteText, mimoKeyInput, engineSpinner.getSelectedItemPosition() == 1);
        seekBar.setOnSeekBarChangeListener(new SimpleSeekListener(() -> valueText.setText(String.format(Locale.SIMPLIFIED_CHINESE, "%.1f 倍", 0.5f + (seekBar.getProgress() / 10f)))));
        engineSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                updateTtsDialogState(noteText, mimoKeyInput, position == 1);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
        toggleButton.setText(ttsActive ? "停止听书" : "开始听书");
        AlertDialog dialog = new AlertDialog.Builder(this).setView(content).create();
        toggleButton.setOnClickListener(v -> {
            settingsStore.setTtsEngine(engineKeys[engineSpinner.getSelectedItemPosition()]);
            settingsStore.setTtsRate(0.5f + (seekBar.getProgress() / 10f));
            settingsStore.setTtsMimoApiKey(mimoKeyInput.getText().toString());
            if (ttsActive) {
                stopTts();
            } else {
                toggleTts();
            }
            dialog.dismiss();
        });
        showStyledDialog(dialog);
    }

    private void updateTtsDialogState(TextView noteText, EditText mimoKeyInput, boolean usingMimo) {
        mimoKeyInput.setEnabled(usingMimo);
        noteText.setText(usingMimo
                ? "MiMo 模式会调用小米云端 TTS，模型固定为 mimo-v2-tts / mimo_default。"
                : "系统模式使用 Android 原生 TTS 引擎。");
    }

    private ArrayAdapter<String> buildSpinnerAdapter(String[] items) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.item_spinner_selected, items);
        adapter.setDropDownViewResource(R.layout.item_spinner_dropdown);
        return adapter;
    }

    private ArrayAdapter<String> buildDialogListAdapter(List<String> items) {
        return new ArrayAdapter<String>(this, R.layout.item_dialog_list_row, android.R.id.text1, items) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView textView = view.findViewById(android.R.id.text1);
                textView.setTextColor(themeColor(R.color.on_surface));
                return view;
            }
        };
    }

    private void showStyledDialog(AlertDialog dialog) {
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
        }
    }

    private void syncFromWebDav(boolean silent) {
        if (!settingsStore.isWebDavEnabled()) {
            if (!silent) {
                showToast("尚未启用 WebDAV 进度同步");
            }
            return;
        }
        executor.execute(() -> {
            try {
                WebDavClient.ProgressPayload payload = webDavClient.downloadProgress(book);
                if (payload == null) {
                    if (!silent) {
                        runOnUiThread(() -> showToast("云端暂时没有可恢复的进度"));
                    }
                    return;
                }
                boolean shouldApply = payload.chapterTime > book.lastReadAt + 5000 || (book.progressIndex == 0 && book.progressOffset == 0);
                if (!shouldApply) {
                    return;
                }
                int remoteIndex = clamp(chapterIndexFromOrder(payload.chapterIndex), 0, chapters.size() - 1);
                databaseHelper.updateProgress(book.id, chapters.get(remoteIndex).orderIndex, payload.chapterPosition);
                book.lastReadAt = payload.chapterTime;
                runOnUiThread(() -> openChapter(remoteIndex, payload.chapterPosition, false, 0));
            } catch (Exception error) {
                if (!silent) {
                    runOnUiThread(() -> showToast("同步失败: " + error.getMessage()));
                }
            }
        });
    }

    private void attachBackground(Uri uri) {
        executor.execute(() -> {
            try {
                String oldPath = settingsStore.getReaderBackgroundPath();
                File newFile = FileAssetHelper.copyUriToFolder(this, uri, "backgrounds", "reader_bg");
                if (oldPath != null && !oldPath.isBlank()) {
                    FileAssetHelper.deleteIfExists(oldPath);
                }
                settingsStore.setReaderBackgroundPath(newFile.getAbsolutePath());
                runOnUiThread(this::applyReaderSettings);
            } catch (Exception error) {
                runOnUiThread(() -> showToast("设置背景失败: " + error.getMessage()));
            }
        });
    }

    private void applyBackgroundImage() {
        String path = settingsStore.getReaderBackgroundPath();
        if (path == null || path.isBlank()) {
            int builtInRes = ReaderThemePalette.from(settingsStore.getReaderTheme()).backgroundDrawableRes;
            if (builtInRes != 0) {
                readerBackgroundImage.setImageResource(builtInRes);
                readerBackgroundImage.setVisibility(View.VISIBLE);
            } else {
                readerBackgroundImage.setImageDrawable(null);
                readerBackgroundImage.setVisibility(View.GONE);
            }
            return;
        }
        Bitmap bitmap = BitmapFactory.decodeFile(path);
        if (bitmap == null) {
            int builtInRes = ReaderThemePalette.from(settingsStore.getReaderTheme()).backgroundDrawableRes;
            if (builtInRes != 0) {
                readerBackgroundImage.setImageResource(builtInRes);
                readerBackgroundImage.setVisibility(View.VISIBLE);
            } else {
                readerBackgroundImage.setImageDrawable(null);
                readerBackgroundImage.setVisibility(View.GONE);
            }
            return;
        }
        readerBackgroundImage.setImageBitmap(bitmap);
        readerBackgroundImage.setVisibility(View.VISIBLE);
    }

    private void openBackgroundPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, REQUEST_PICK_BACKGROUND);
    }

    private List<PageSlice> getPagesForChapter(int chapterIndex) {
        List<PageSlice> cached = chapterPageCache.get(chapterIndex);
        if (cached != null) {
            return cached;
        }
        String text = getProcessedChapterText(chapterIndex);
        if (pageBodyCurrent.getWidth() <= 0 || pageBodyCurrent.getHeight() <= 0) {
            List<PageSlice> fallback = new ArrayList<>();
            fallback.add(new PageSlice(0, text.length(), text));
            return fallback;
        }
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        paint.setTextSize(pageBodyCurrent.getTextSize());
        paint.setTypeface(pageBodyCurrent.getTypeface());
        List<PageSlice> pages = ReaderPaginator.paginate(text, paint, pageBodyCurrent.getWidth(), pageBodyCurrent.getHeight(), pageBodyCurrent.getLineSpacingExtra());
        chapterPageCache.put(chapterIndex, pages);
        return pages;
    }

    private String getProcessedChapterText(int chapterIndex) {
        String cached = processedChapterCache.get(chapterIndex);
        if (cached != null) {
            return cached;
        }
        String body = chapters.get(chapterIndex).bodyText == null ? "" : chapters.get(chapterIndex).bodyText;
        String processed = ReplacementEngine.apply(body, replacementRules);
        processedChapterCache.put(chapterIndex, processed);
        return processed;
    }

    private String remainingTextFromCurrentPosition() {
        String text = getProcessedChapterText(currentChapterIndex);
        return text.substring(clamp(currentCharOffset(), 0, text.length()));
    }

    private int currentCharOffset() {
        List<PageSlice> pages = getPagesForChapter(currentChapterIndex);
        if (pages.isEmpty()) {
            return 0;
        }
        return pages.get(clamp(currentPageIndex, 0, pages.size() - 1)).start;
    }

    private List<String> splitForSpeech(String text) {
        List<String> parts = new ArrayList<>();
        if (text == null) {
            return parts;
        }
        String[] segments = text.trim().split("(?<=[。！？!?；;\\n])");
        StringBuilder builder = new StringBuilder();
        for (String segment : segments) {
            String trimmed = segment.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (builder.length() + trimmed.length() > 180 && builder.length() > 0) {
                parts.add(builder.toString().trim());
                builder.setLength(0);
            }
            builder.append(trimmed).append(' ');
        }
        if (builder.length() > 0) {
            parts.add(builder.toString().trim());
        }
        return parts;
    }

    private boolean ensurePageAreaReady(Runnable action) {
        if (pageBodyCurrent.getWidth() > 0 && pageBodyCurrent.getHeight() > 0) {
            return true;
        }
        pageStage.post(action);
        return false;
    }

    private void refreshRuleLabels(ArrayAdapter<String> adapter) {
        adapter.clear();
        for (ReplacementRuleRecord rule : replacementRules) {
            String replacement = rule.replacement == null || rule.replacement.isEmpty() ? "(删除)" : rule.replacement;
            adapter.add((rule.active ? "[启用] " : "[停用] ") + rule.pattern + " -> " + replacement);
        }
    }

    private void renderThemeRows(LinearLayout container, AlertDialog dialog) {
        container.removeAllViews();
        executor.execute(() -> {
            List<ReaderThemeRecord> themes = databaseHelper.getCustomThemes();
            runOnUiThread(() -> {
                if (!dialog.isShowing()) {
                    return;
                }
                container.removeAllViews();
                for (ReaderThemeRecord theme : themes) {
                    LinearLayout row = new LinearLayout(this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    Button applyButton = new Button(this);
                    applyButton.setText(theme.name);
                    applyButton.setBackgroundResource(R.drawable.bg_outline_button);
                    applyButton.setTextColor(getColor(R.color.primary));
                    Button deleteButton = new Button(this);
                    deleteButton.setText("删除");
                    deleteButton.setBackgroundResource(R.drawable.bg_danger_button);
                    deleteButton.setTextColor(0xFFFFFFFF);
                    row.addView(applyButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                    LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    deleteParams.leftMargin = dp(8);
                    row.addView(deleteButton, deleteParams);
                    applyButton.setOnClickListener(v -> {
                        try {
                            ReaderThemeConfig.apply(settingsStore, new JSONObject(theme.configJson));
                            clearPageCache();
                            applyReaderSettings();
                            openChapter(currentChapterIndex, currentCharOffset(), false, 0);
                        } catch (Exception ignore) {
                            showToast("主题配置损坏");
                        }
                    });
                    deleteButton.setOnClickListener(v -> executor.execute(() -> {
                        databaseHelper.deleteCustomTheme(theme.id);
                        runOnUiThread(() -> renderThemeRows(container, dialog));
                    }));
                    container.addView(row);
                }
            });
        });
    }

    private void promptSaveTheme(Runnable onSaved) {
        EditText input = new EditText(this);
        input.setHint("主题名称");
        new AlertDialog.Builder(this)
                .setTitle("保存当前主题")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        showToast("请输入主题名称");
                        return;
                    }
                    executor.execute(() -> {
                        databaseHelper.saveCustomTheme(name, ReaderThemeConfig.export(settingsStore).toString());
                        runOnUiThread(onSaved);
                    });
                })
                .show();
    }

    private void clearPageCache() {
        chapterPageCache.clear();
    }

    private void clearAllReaderCaches() {
        processedChapterCache.clear();
        chapterPageCache.clear();
    }

    private String currentBackgroundLabel() {
        String path = settingsStore.getReaderBackgroundPath();
        if (path == null || path.isBlank()) {
            return "当前背景：使用" + ReaderThemePalette.from(settingsStore.getReaderTheme()).displayName + "内置壁纸";
        }
        return "当前背景：" + new File(path).getName();
    }

    private void updateStyleLabels(TextView fontValue, TextView lineValue, TextView sideValue, TextView verticalValue,
                                   SeekBar fontSeek, SeekBar lineSeek, SeekBar sideSeek, SeekBar verticalSeek) {
        fontValue.setText((fontSeek.getProgress() + 12) + " sp");
        lineValue.setText(lineSeek.getProgress() + " px");
        sideValue.setText((sideSeek.getProgress() + 8) + " dp");
        verticalValue.setText((verticalSeek.getProgress() + 8) + " dp");
    }

    private void updateReaderThemeButtons(Button paperButton, Button forestButton, Button nightButton, String selectedTheme) {
        styleThemeButton(paperButton, "paper".equals(selectedTheme));
        styleThemeButton(forestButton, "forest".equals(selectedTheme));
        styleThemeButton(nightButton, "night".equals(selectedTheme));
    }

    private void styleThemeButton(Button button, boolean active) {
        button.setBackgroundResource(active ? R.drawable.bg_primary_button : R.drawable.bg_outline_button);
        button.setTextColor(active ? getColor(android.R.color.white) : themeColor(R.color.on_surface));
    }

    private void styleReaderMenuButton(Button button, boolean active) {
        button.setBackgroundResource(active ? R.drawable.bg_reader_menu_button_active : R.drawable.bg_reader_menu_button);
        button.setTextColor(getColor(android.R.color.white));
    }

    private int indexOf(String[] values, String target, int fallback) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(target)) {
                return i;
            }
        }
        return fallback;
    }

    private void resetAnimatedPage(View view) {
        view.animate().cancel();
        view.setTranslationX(0f);
        view.setRotationY(0f);
        view.setAlpha(1f);
    }

    private void stylePageContainer(View view, int pageColor) {
        view.setBackgroundColor(Color.TRANSPARENT);
    }

    private void updateBodyTopMargin(TextView bodyView, int topMargin) {
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) bodyView.getLayoutParams();
        params.topMargin = topMargin;
        bodyView.setLayoutParams(params);
    }

    private void setControlsVisible(boolean visible) {
        if (controlsVisible == visible) {
            if (visible) {
                scheduleAutoHide();
            } else {
                mainHandler.removeCallbacks(autoHideRunnable);
            }
            updateSystemBarsVisibility(visible);
            return;
        }
        controlsVisible = visible;
        animatePanel(hudContainer, !visible, -dp(12));
        animatePanel(menuTopPanel, visible, -dp(18));
        animatePanel(menuInfoPanel, visible, dp(14));
        animatePanel(menuBottomPanel, visible, dp(20));
        updateSystemBarsVisibility(visible);
        if (visible) {
            scheduleAutoHide();
        } else {
            mainHandler.removeCallbacks(autoHideRunnable);
        }
    }

    private void scheduleAutoHide() {
        mainHandler.removeCallbacks(autoHideRunnable);
    }

    private void animatePanel(View view, boolean show, float hiddenTranslationY) {
        view.animate().cancel();
        if (show) {
            view.setVisibility(View.VISIBLE);
            view.setAlpha(0f);
            view.setTranslationY(hiddenTranslationY);
            view.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(220L)
                    .start();
            return;
        }
        if (view.getVisibility() != View.VISIBLE) {
            return;
        }
        view.animate()
                .alpha(0f)
                .translationY(hiddenTranslationY)
                .setDuration(180L)
                .withEndAction(() -> {
                    if (!controlsVisible) {
                        view.setVisibility(View.GONE);
                        view.setTranslationY(0f);
                    }
                })
                .start();
    }

    private void updateSystemBarsVisibility(boolean showSystemBars) {
        Window window = getWindow();
        View decorView = window.getDecorView();
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (!isDarkReaderUi()) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
        }
        if (!showSystemBars) {
            flags |= View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        }
        decorView.setSystemUiVisibility(flags);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = decorView.getWindowInsetsController();
            if (controller != null) {
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                if (showSystemBars) {
                    controller.show(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                } else {
                    controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                }
            }
        }
    }

    private int chapterIndexFromOrder(int orderIndex) {
        for (int i = 0; i < chapters.size(); i++) {
            if (chapters.get(i).orderIndex == orderIndex) {
                return i;
            }
        }
        return orderIndex;
    }

    private boolean isInsideView(MotionEvent event, View view) {
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        float rawX = event.getRawX();
        float rawY = event.getRawY();
        return rawX >= location[0] && rawX <= location[0] + view.getWidth() && rawY >= location[1] && rawY <= location[1] + view.getHeight();
    }

    private int themeColor(int resId) {
        return getColor(resId);
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void showToast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private static class SearchResult {
        final int chapterIndex;
        final String chapterTitle;
        final String snippet;
        final int charOffset;

        private SearchResult(int chapterIndex, String chapterTitle, String snippet, int charOffset) {
            this.chapterIndex = chapterIndex;
            this.chapterTitle = chapterTitle;
            this.snippet = snippet;
            this.charOffset = charOffset;
        }
    }

    private static class ReaderThemePalette {
        final int backgroundColor;
        final int pageColor;
        final int textColor;
        final int overlayColor;
        final int backgroundDrawableRes;
        final String displayName;

        private ReaderThemePalette(int backgroundColor, int pageColor, int textColor, int overlayColor, int backgroundDrawableRes, String displayName) {
            this.backgroundColor = backgroundColor;
            this.pageColor = pageColor;
            this.textColor = textColor;
            this.overlayColor = overlayColor;
            this.backgroundDrawableRes = backgroundDrawableRes;
            this.displayName = displayName;
        }

        private static ReaderThemePalette from(String key) {
            if ("forest".equals(key)) {
                return new ReaderThemePalette(
                        0xFFDCEAD7,
                        0xFFEAF4E6,
                        0xFF2A4B2A,
                        0xB8EEF6E9,
                        R.drawable.theme_bg_forest,
                        "护眼"
                );
            }
            if ("night".equals(key)) {
                return new ReaderThemePalette(
                        0xFF0F172A,
                        0xFF172033,
                        0xFFE2E8F0,
                        0xCC0A0F17,
                        R.drawable.theme_bg_night,
                        "夜航"
                );
            }
            return new ReaderThemePalette(
                    0xFFF4ECD8,
                    0xFFF7F0E1,
                    0xFF5C4B37,
                    0xA6FFF8ED,
                    R.drawable.theme_bg_paper,
                    "纸控"
            );
        }
    }

    private static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        private final Runnable callback;

        private SimpleSeekListener(Runnable callback) {
            this.callback = callback;
        }

        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            callback.run();
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    }
}
