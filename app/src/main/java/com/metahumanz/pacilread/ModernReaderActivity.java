package com.metahumanz.pacilread;

import android.app.AlertDialog;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.Editable;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextWatcher;
import android.util.Log;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.ViewConfiguration;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
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
import com.metahumanz.pacilread.reader.JustifiedPageTextView;
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
import com.metahumanz.pacilread.ui.GlassUiHelper;
import com.metahumanz.pacilread.util.FileAssetHelper;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ModernReaderActivity extends ThemedReaderActivity implements TextToSpeech.OnInitListener {
    private static final String TAG = "PacilReadReader";
    private static final int CHAPTER_TITLE_BODY_MARGIN_DP = 16;
    private static final int REQUEST_PICK_BACKGROUND = 2001;
    private static final Pattern TTS_SEGMENT_PATTERN = Pattern.compile("[^ \\n\\t。！？.!?,，;；、]+[。！？.!?,，;；、]*");
    private static final String TTS_UTTERANCE_PREFIX = "reader-tts-";
    private static final DecelerateInterpolator PAGE_SLIDE_INTERPOLATOR = new DecelerateInterpolator(1.35f);
    private static final AccelerateDecelerateInterpolator PAGE_TURN_INTERPOLATOR = new AccelerateDecelerateInterpolator();

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService ttsExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<SpeechUnit> ttsUnits = new ArrayList<>();
    private final List<ChapterRecord> chapters = new ArrayList<>();
    private final List<ReplacementRuleRecord> replacementRules = new ArrayList<>();
    private final Map<Integer, String> processedChapterCache = new HashMap<>();
    private final Map<Integer, Integer> processedChapterLengthCache = new HashMap<>();
    private final Map<Integer, List<PageSlice>> chapterPageCache = new HashMap<>();

    private ReaderDatabaseHelper databaseHelper;
    private SettingsStore settingsStore;
    private WebDavClient webDavClient;
    private MimoTtsClient mimoTtsClient;


    private View readerRoot;
    private View hudTopContainer;
    private View hudBottomContainer;
    private View menuTopPanel;
    private View menuInfoPanel;
    private View menuBottomPanel;
    private View pageStage;
    private View pageCurrent;
    private View pageIncoming;
    private View pageShadow;

    private android.widget.ImageView readerBackgroundImage;
    private TextView hudTopLeft;
    private TextView hudTopCenter;
    private TextView hudTopRight;
    private TextView hudBottomLeft;
    private TextView hudBottomCenter;
    private TextView hudBottomRight;

    private android.content.BroadcastReceiver sysMetricsReceiver;
    private int currentBatteryLevel = -1;

    private TextView readerTitle;
    private TextView readerProgress;
    private TextView chapterMeta;
    private TextView pageMeta;
    private TextView pageTitleCurrent;
    private JustifiedPageTextView pageBodyCurrent;
    private TextView pageTitleIncoming;
    private JustifiedPageTextView pageBodyIncoming;
    private SeekBar progressSeekBar;
    private Button ttsButton;
    private Button autoPageButton;
    private Button themeToggleButton;

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
    private int ttsChapterIndex = -1;
    private int currentTtsUnitIndex = -1;
    private int ttsSessionId = 0;
    private boolean isAnimating = false;
    private long animationToken = 0L;
    private int pagingTouchSlop;
    private boolean pagingGestureCandidate = false;
    private boolean interactivePaging = false;
    private float pagingDownX = 0f;
    private float pagingDownY = 0f;
    private float pagingLastX = 0f;
    private float pagingVelocityX = 0f;
    private float interactiveProgress = 0f;
    private float interactiveTouchY = 0f;
    private long pagingLastEventTime = 0L;
    private int interactiveDirection = 0;
    private int interactiveTargetChapterIndex = -1;
    private int interactiveTargetPageIndex = -1;
    private ValueAnimator interactiveAnimator;
    private int totalProcessedBookLength = -1;

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
            if (!controlsVisible && !isAnimating && !interactivePaging) {
                pageDown();
            }
            scheduleNextAutoPageTick();
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
        pagingTouchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
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
        applyGlassOpacity();
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
        cancelInteractiveAnimator();
        cancelInteractivePaging();
        stopAutoPage();
        stopTts();
        persistProgress();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (sysMetricsReceiver != null) {
            unregisterReceiver(sysMetricsReceiver);
        }
        mainHandler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
        ttsExecutor.shutdownNow();
        cancelInteractiveAnimator();
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
        if (controlsVisible) {
            setControlsVisible(false);
            return;
        }
        persistProgress();
        super.onBackPressed();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (handleReaderPagingTouchEvent(event)) {
            return true;
        }
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
                    handleSystemTtsAdvance(utteranceId);
                }

                @Override
                public void onError(String utteranceId) {
                    handleSystemTtsAdvance(utteranceId);
                }

                @Override
                public void onError(String utteranceId, int errorCode) {
                    handleSystemTtsAdvance(utteranceId);
                }
            });
        }
    }

    private void bindViews() {
        readerRoot = findViewById(R.id.reader_root);
        menuTopPanel = findViewById(R.id.menu_top_panel);
        menuInfoPanel = findViewById(R.id.menu_info_panel);
        menuBottomPanel = findViewById(R.id.menu_bottom_panel);
        pageStage = findViewById(R.id.page_stage);
        pageCurrent = findViewById(R.id.page_current);
        pageIncoming = findViewById(R.id.page_incoming);
        pageShadow = findViewById(R.id.view_page_shadow);
        readerBackgroundImage = findViewById(R.id.reader_background_image);
        hudTopContainer = findViewById(R.id.hud_container_top);
        hudBottomContainer = findViewById(R.id.hud_container_bottom);
        hudTopLeft = findViewById(R.id.text_hud_top_left);
        hudTopCenter = findViewById(R.id.text_hud_top_center);
        hudTopRight = findViewById(R.id.text_hud_top_right);
        hudBottomLeft = findViewById(R.id.text_hud_bottom_left);
        hudBottomCenter = findViewById(R.id.text_hud_bottom_center);
        hudBottomRight = findViewById(R.id.text_hud_bottom_right);
        readerTitle = findViewById(R.id.text_reader_title);
        readerProgress = findViewById(R.id.text_progress);
        chapterMeta = findViewById(R.id.text_chapter_meta);
        pageMeta = findViewById(R.id.text_page_meta);
        pageTitleCurrent = findViewById(R.id.text_page_title_current);
        pageBodyCurrent = findViewById(R.id.text_page_body_current);
        pageTitleIncoming = findViewById(R.id.text_page_title_incoming);
        pageBodyIncoming = findViewById(R.id.text_page_body_incoming);
        progressSeekBar = findViewById(R.id.seek_reader_progress);
        ttsButton = findViewById(R.id.button_tts);
        autoPageButton = findViewById(R.id.button_auto_page);
        themeToggleButton = findViewById(R.id.button_theme_toggle);
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
        int bottomPanelBottomMargin = dp(8) + systemInsetBottom;
        int menuBottomHeight = menuBottomPanel == null ? 0 : menuBottomPanel.getHeight();
        int infoBottomMargin = menuBottomHeight > 0
                ? bottomPanelBottomMargin + menuBottomHeight + dp(10)
                : dp(148) + systemInsetBottom;
        hudTopContainer.setPadding(
                dp(12) + systemInsetLeft,
                dp(8) + systemInsetTop,
                dp(12) + systemInsetRight,
                0
        );
        hudBottomContainer.setPadding(
                dp(12) + systemInsetLeft,
                0,
                dp(12) + systemInsetRight,
                dp(8) + systemInsetBottom
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
                infoBottomMargin
        );
        updateFrameLayoutMargins(menuBottomPanel,
                dp(10) + systemInsetLeft,
                0,
                dp(10) + systemInsetRight,
                bottomPanelBottomMargin
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
        sysMetricsReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context context, android.content.Intent intent) {
                if (android.content.Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) {
                    int level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
                    int scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1);
                    if (level >= 0 && scale > 0) {
                        currentBatteryLevel = (level * 100) / scale;
                    }
                }
                updateReaderHud();
            }
        };
        android.content.IntentFilter filter = new android.content.IntentFilter();
        filter.addAction(android.content.Intent.ACTION_TIME_TICK);
        filter.addAction(android.content.Intent.ACTION_BATTERY_CHANGED);
        registerReceiver(sysMetricsReceiver, filter);

        findViewById(R.id.button_back).setOnClickListener(v -> finish());
        findViewById(R.id.button_prev_chapter).setOnClickListener(v -> openChapter(currentChapterIndex - 1, 0, true, -1));
        findViewById(R.id.button_next_chapter).setOnClickListener(v -> openChapter(currentChapterIndex + 1, 0, true, 1));
        findViewById(R.id.button_toc).setOnClickListener(v -> showTocDialog());
        findViewById(R.id.button_search).setOnClickListener(v -> showSearchDialog());
        findViewById(R.id.button_rules).setOnClickListener(v -> showRulesDialog());
        findViewById(R.id.button_style).setOnClickListener(v -> showStyleDialog());
        findViewById(R.id.button_reader_options).setOnClickListener(v -> showReaderOptionsDialog());
        themeToggleButton.setOnClickListener(v -> toggleReaderUiTheme());
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
                if (controlsVisible || isAnimating || interactivePaging) {
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

    private boolean handleReaderPagingTouchEvent(MotionEvent event) {
        if (controlsVisible || chapters.isEmpty() || pageStage == null || pageStage.getWidth() == 0 || pageStage.getHeight() == 0) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                cancelInteractiveAnimator();
                pagingGestureCandidate = isInsideView(event, pageStage) && !isAnimating;
                pagingDownX = event.getX();
                pagingDownY = event.getY();
                pagingLastX = event.getX();
                pagingLastEventTime = event.getEventTime();
                pagingVelocityX = 0f;
                interactiveTouchY = localTouchY(event);
                return false;
            case MotionEvent.ACTION_MOVE:
                if (!pagingGestureCandidate && !interactivePaging) {
                    return false;
                }
                updatePagingVelocity(event);
                if (!interactivePaging) {
                    float deltaX = event.getX() - pagingDownX;
                    float deltaY = event.getY() - pagingDownY;
                    if (Math.abs(deltaY) > pagingTouchSlop && Math.abs(deltaY) > Math.abs(deltaX)) {
                        pagingGestureCandidate = false;
                        return false;
                    }
                    if (Math.abs(deltaX) <= pagingTouchSlop || Math.abs(deltaX) <= Math.abs(deltaY)) {
                        return false;
                    }
                    int direction = deltaX < 0f ? 1 : -1;
                    if (!prepareInteractivePaging(direction, localTouchY(event))) {
                        pagingGestureCandidate = false;
                        return false;
                    }
                }
                interactiveTouchY = localTouchY(event);
                float width = Math.max(pageStage.getWidth(), dp(240));
                float deltaX = event.getX() - pagingDownX;
                float progress = interactiveDirection > 0 ? -deltaX / width : deltaX / width;
                applyInteractivePagingProgress(progress, interactiveTouchY);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!interactivePaging) {
                    pagingGestureCandidate = false;
                    return false;
                }
                updatePagingVelocity(event);
                boolean commit = event.getActionMasked() != MotionEvent.ACTION_CANCEL && shouldCommitInteractivePaging();
                finishInteractivePaging(commit);
                return true;
            default:
                return false;
        }
    }

    private void updatePagingVelocity(MotionEvent event) {
        long now = event.getEventTime();
        long elapsed = Math.max(1L, now - pagingLastEventTime);
        pagingVelocityX = (event.getX() - pagingLastX) / elapsed;
        pagingLastX = event.getX();
        pagingLastEventTime = now;
    }

    private boolean prepareInteractivePaging(int direction, float touchY) {
        PageTarget target = resolveInteractiveTarget(direction);
        if (target == null) {
            return false;
        }
        cancelInteractiveAnimator();
        interactivePaging = true;
        isAnimating = true;
        interactiveDirection = direction;
        interactiveTargetChapterIndex = target.chapterIndex;
        interactiveTargetPageIndex = target.pageIndex;
        interactiveProgress = 0f;
        interactiveTouchY = touchY;
        bindPage(pageTitleIncoming, pageBodyIncoming, target.chapterIndex, target.pageIndex);
        pageIncoming.setVisibility(View.VISIBLE);
        resetAnimatedPage(pageCurrent);
        resetAnimatedPage(pageIncoming);
        resetShadowView();
        arrangePagingLayers(settingsStore.getFlipMode());
        applyInteractivePagingProgress(0f, touchY);
        return true;
    }

    private PageTarget resolveInteractiveTarget(int direction) {
        if (direction > 0) {
            List<PageSlice> pages = getPagesForChapter(currentChapterIndex);
            if (currentPageIndex < pages.size() - 1) {
                return new PageTarget(currentChapterIndex, currentPageIndex + 1);
            }
            if (currentChapterIndex < chapters.size() - 1) {
                return new PageTarget(currentChapterIndex + 1, 0);
            }
            return null;
        }
        if (currentPageIndex > 0) {
            return new PageTarget(currentChapterIndex, currentPageIndex - 1);
        }
        if (currentChapterIndex > 0) {
            List<PageSlice> previousPages = getPagesForChapter(currentChapterIndex - 1);
            return new PageTarget(currentChapterIndex - 1, previousPages.size() - 1);
        }
        return null;
    }

    private void applyInteractivePagingProgress(float progress, float touchY) {
        if (!interactivePaging) {
            return;
        }
        interactiveProgress = Math.max(0f, Math.min(1f, progress));
        applyPagingVisuals(settingsStore.getFlipMode(), interactiveDirection, interactiveProgress, touchY);
    }

    private void updateInteractiveShadow(float edgeX, float alpha) {
        if (pageShadow == null) {
            return;
        }
        float shadowWidth = Math.max(pageShadow.getWidth(), dp(48));
        pageShadow.setVisibility(alpha <= 0f ? View.GONE : View.VISIBLE);
        if (alpha <= 0f) {
            pageShadow.setAlpha(0f);
            return;
        }
        pageShadow.setScaleX(interactiveDirection > 0 ? 1f : -1f);
        pageShadow.setTranslationX(interactiveDirection > 0 ? edgeX - shadowWidth : edgeX);
        pageShadow.setAlpha(Math.max(0f, Math.min(0.56f, alpha)));
    }

    private boolean shouldCommitInteractivePaging() {
        float directionalVelocity = interactiveDirection > 0 ? -pagingVelocityX : pagingVelocityX;
        String mode = settingsStore.getFlipMode();
        float progressThreshold = "cover".equals(mode) ? 0.18f
                : ("simulation".equals(mode) ? 0.24f : ("scroll".equals(mode) ? 0.28f : 0.22f));
        float velocityThreshold = "scroll".equals(mode) ? 0.7f : 0.85f;
        return interactiveProgress >= progressThreshold || directionalVelocity > velocityThreshold;
    }

    private void finishInteractivePaging(boolean commit) {
        float start = interactiveProgress;
        float end = commit ? 1f : 0f;
        long token = ++animationToken;
        cancelInteractiveAnimator();
        interactiveAnimator = ValueAnimator.ofFloat(start, end);
        String mode = settingsStore.getFlipMode();
        float remainingDistance = Math.max(0.2f, Math.abs(end - start));
        long duration = Math.max(110L, Math.round(readerFlipDurationMs() * remainingDistance));
        if (Math.abs(pagingVelocityX) > 0.7f) {
            duration = Math.max(90L, Math.round(duration / Math.min(Math.abs(pagingVelocityX), 2.4f)));
        }
        interactiveAnimator.setDuration(duration);
        interactiveAnimator.setInterpolator("simulation".equals(mode) ? PAGE_TURN_INTERPOLATOR : PAGE_SLIDE_INTERPOLATOR);
        final boolean[] cancelled = new boolean[]{false};
        interactiveAnimator.addUpdateListener(animation ->
                applyInteractivePagingProgress((float) animation.getAnimatedValue(), interactiveTouchY)
        );
        interactiveAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled[0] = true;
                interactiveAnimator = null;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                interactiveAnimator = null;
                if (cancelled[0] || token != animationToken) {
                    return;
                }
                if (commit) {
                    finishAnimation(interactiveTargetChapterIndex, interactiveTargetPageIndex, token);
                } else {
                    cancelInteractivePaging();
                }
            }
        });
        interactiveAnimator.start();
    }

    private void cancelInteractiveAnimator() {
        if (interactiveAnimator != null) {
            interactiveAnimator.cancel();
            interactiveAnimator = null;
        }
    }

    private void cancelInteractivePaging() {
        pagingGestureCandidate = false;
        interactivePaging = false;
        interactiveDirection = 0;
        interactiveProgress = 0f;
        interactiveTargetChapterIndex = -1;
        interactiveTargetPageIndex = -1;
        resetAnimatedPage(pageCurrent);
        resetAnimatedPage(pageIncoming);
        pageIncoming.setVisibility(View.GONE);
        resetShadowView();
        isAnimating = false;
    }

    private float localTouchY(MotionEvent event) {
        int[] stageLocation = new int[2];
        pageStage.getLocationOnScreen(stageLocation);
        return event.getRawY() - stageLocation[1];
    }

    private void loadBook() {
        executor.execute(() -> {
            try {
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
            } catch (Exception error) {
                Log.e(TAG, "Failed to load reader state", error);
                runOnUiThread(() -> {
                    showToast("打开书籍失败: " + readableError(error));
                    finish();
                });
            }
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
        updateBodyTopMargin(bodyView, showTitle ? dp(CHAPTER_TITLE_BODY_MARGIN_DP) : 0);
        bodyView.setText(slice.text == null ? "" : slice.text);
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

        updateReaderHud();

        styleReaderMenuButton(ttsButton, ttsActive);
        styleReaderMenuButton(autoPageButton, autoPageActive);
        styleReaderMenuButton(themeToggleButton, isDarkReaderUi());
    }

    private int fetchCurrentProgressPercent() {
        if (book == null || chapters.isEmpty()) {
            return 0;
        }
        long totalLength = getTotalProcessedBookLength();
        if (totalLength <= 0L) {
            return 0;
        }
        long readLength = 0L;
        for (int i = 0; i < currentChapterIndex; i++) {
            readLength += getProcessedChapterLength(i);
        }
        int currentLength = getProcessedChapterLength(currentChapterIndex);
        readLength += Math.min(Math.max(currentCharOffset(), 0), Math.max(currentLength, 0));
        return (int) Math.round((readLength * 100d) / totalLength);
    }

    private void updateReaderHud() {
        if (book == null || chapters.isEmpty()) return;

        applyHudSlot(hudTopLeft, settingsStore.getHudTopLeft());
        applyHudSlot(hudTopCenter, settingsStore.getHudTopCenter());
        applyHudSlot(hudTopRight, settingsStore.getHudTopRight());
        applyHudSlot(hudBottomLeft, settingsStore.getHudBottomLeft());
        applyHudSlot(hudBottomCenter, settingsStore.getHudBottomCenter());
        applyHudSlot(hudBottomRight, settingsStore.getHudBottomRight());
    }

    private void applyHudSlot(TextView textView, String type) {
        if (textView == null) {
            return;
        }
        String text = hudTextForSlot(type);
        if (text.isEmpty()) {
            textView.setText("");
            textView.setVisibility(View.GONE);
            return;
        }
        textView.setText(text);
        textView.setVisibility(View.VISIBLE);
    }

    private String hudTextForSlot(String type) {
        switch (type) {
            case "title":
                return currentBookTitle();
            case "chapter":
                return currentChapterTitle();
            case "title_chapter":
                return joinHudSegments(currentBookTitle(), currentChapterTitle(), " / ");
            case "time":
                return currentTimeText();
            case "battery":
                return currentBatteryText();
            case "chapter_page":
                return currentChapterPageText();
            case "book_progress":
                return currentBookProgressText();
            case "page_and_progress":
                return joinHudSegments(currentChapterPageText(), currentBookProgressPercentText(), " · ");
            case "time_and_battery":
                return joinHudSegments(currentTimeText(), currentBatteryText(), " · ");
            default:
                return "";
        }
    }

    private String currentBookTitle() {
        return trimToEmpty(book == null ? null : book.title);
    }

    private String currentChapterTitle() {
        if (currentChapterIndex < 0 || currentChapterIndex >= chapters.size()) {
            return "";
        }
        return trimToEmpty(chapters.get(currentChapterIndex).title);
    }

    private String currentTimeText() {
        return new java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(new java.util.Date());
    }

    private String currentBatteryText() {
        return currentBatteryLevel >= 0 ? currentBatteryLevel + "%" : "";
    }

    private String currentChapterPageText() {
        int safePageCount = Math.max(getPagesForChapter(currentChapterIndex).size(), 1);
        return String.format(Locale.SIMPLIFIED_CHINESE, "第 %d/%d 页", currentPageIndex + 1, safePageCount);
    }

    private String currentBookProgressPercentText() {
        return fetchCurrentProgressPercent() + "%";
    }

    private String currentBookProgressText() {
        return "全书 " + currentBookProgressPercentText();
    }

    private String joinHudSegments(String first, String second, String divider) {
        if (first == null || first.isEmpty()) {
            return second == null ? "" : second;
        }
        if (second == null || second.isEmpty()) {
            return first;
        }
        return first + divider + second;
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private void animateTransition(int targetChapterIndex, int targetPageIndex, int direction) {
        long token = ++animationToken;
        isAnimating = true;
        String mode = settingsStore.getFlipMode();
        float height = Math.max(pageStage.getHeight(), dp(320));
        cancelInteractiveAnimator();
        resetAnimatedPage(pageCurrent);
        resetAnimatedPage(pageIncoming);
        resetShadowView();
        if ("none".equals(mode)) {
            finishAnimation(targetChapterIndex, targetPageIndex, token);
            return;
        }
        arrangePagingLayers(mode);
        applyPagingVisuals(mode, direction, 0f, height * 0.5f);
        interactiveAnimator = ValueAnimator.ofFloat(0f, 1f);
        interactiveAnimator.setDuration(readerFlipDurationMs());
        interactiveAnimator.setInterpolator("simulation".equals(mode) ? PAGE_TURN_INTERPOLATOR : PAGE_SLIDE_INTERPOLATOR);
        final boolean[] cancelled = new boolean[]{false};
        interactiveAnimator.addUpdateListener(animation ->
                applyPagingVisuals(mode, direction, (float) animation.getAnimatedValue(), height * 0.5f)
        );
        interactiveAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled[0] = true;
                interactiveAnimator = null;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                interactiveAnimator = null;
                if (cancelled[0] || token != animationToken) {
                    return;
                }
                finishAnimation(targetChapterIndex, targetPageIndex, token);
            }
        });
        interactiveAnimator.start();
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
        resetShadowView();
        pagingGestureCandidate = false;
        interactivePaging = false;
        interactiveDirection = 0;
        interactiveProgress = 0f;
        interactiveTargetChapterIndex = -1;
        interactiveTargetPageIndex = -1;
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
        boolean hasCurrentUnits = rebuildTtsUnitsForChapter(currentChapterIndex, currentCharOffset());
        if (!hasCurrentUnits && currentChapterIndex >= chapters.size() - 1) {
            showToast("当前位置没有可朗读的文本");
            return;
        }
        ttsActive = true;
        ttsSessionId++;
        styleReaderMenuButton(ttsButton, true);
        if (hasCurrentUnits) {
            playCurrentTtsUnit();
            return;
        }
        advanceToNextTtsChapter();
    }

    private void playCurrentTtsUnit() {
        if (!ttsActive) {
            return;
        }
        if (ttsChapterIndex < 0 || ttsChapterIndex >= chapters.size()) {
            stopTts();
            return;
        }
        if (isAnimating || interactivePaging) {
            scheduleTtsPlayback(readerFlipDurationMs() + 60L);
            return;
        }
        if (currentTtsUnitIndex >= ttsUnits.size()) {
            advanceToNextTtsChapter();
            return;
        }

        if (currentChapterIndex != ttsChapterIndex) {
            SpeechUnit pendingUnit = ttsUnits.get(clamp(currentTtsUnitIndex, 0, Math.max(ttsUnits.size() - 1, 0)));
            openChapter(ttsChapterIndex, pendingUnit.start, true, ttsChapterIndex >= currentChapterIndex ? 1 : -1);
            scheduleTtsPlayback(readerFlipDurationMs() + 60L);
            return;
        }

        List<PageSlice> pages = getPagesForChapter(ttsChapterIndex);
        if (pages.isEmpty()) {
            advanceToNextTtsChapter();
            return;
        }

        PageSlice currentSlice = pages.get(clamp(currentPageIndex, 0, pages.size() - 1));
        while (currentTtsUnitIndex < ttsUnits.size() && ttsUnits.get(currentTtsUnitIndex).end <= currentSlice.start) {
            currentTtsUnitIndex++;
        }
        if (currentTtsUnitIndex >= ttsUnits.size()) {
            advanceToNextTtsChapter();
            return;
        }

        SpeechUnit unit = ttsUnits.get(currentTtsUnitIndex);
        if (unit.start >= currentSlice.end) {
            if (pageDown()) {
                scheduleTtsPlayback(readerFlipDurationMs() + 60L);
            } else {
                advanceToNextTtsChapter();
            }
            return;
        }

        if ("mimo".equals(settingsStore.getTtsEngine())) {
            speakCurrentMimoGroup();
            return;
        }
        if (textToSpeech == null) {
            stopTts();
            return;
        }
        textToSpeech.setSpeechRate(settingsStore.getTtsRate());
        int result = textToSpeech.speak(unit.text, TextToSpeech.QUEUE_FLUSH, null, TTS_UTTERANCE_PREFIX + ttsSessionId);
        if (result != TextToSpeech.SUCCESS) {
            advanceTtsPlayback(1);
        }
    }

    private void stopTts() {
        ttsActive = false;
        ttsSessionId++;
        ttsUnits.clear();
        ttsChapterIndex = -1;
        currentTtsUnitIndex = -1;
        if (mimoTtsClient != null) {
            mimoTtsClient.cancel();
        }
        if (textToSpeech != null) {
            textToSpeech.stop();
        }
        styleReaderMenuButton(ttsButton, false);
    }

    private void toggleReaderUiTheme() {
        boolean darkUi = isDarkReaderUi();
        settingsStore.setReaderUiThemeMode(darkUi ? "light" : "dark");
        recreate();
    }



    private void stopAutoPage() {
        autoPageActive = false;
        mainHandler.removeCallbacks(autoPageRunnable);
        styleReaderMenuButton(autoPageButton, false);
    }

    private void startAutoPage() {
        autoPageActive = true;
        styleReaderMenuButton(autoPageButton, true);
        scheduleNextAutoPageTick();
    }

    private void scheduleNextAutoPageTick() {
        mainHandler.removeCallbacks(autoPageRunnable);
        if (!autoPageActive) {
            return;
        }
        mainHandler.postDelayed(autoPageRunnable, settingsStore.getAutoPageSeconds() * 1000L);
    }

    private void applyReaderSettings() {
        ReaderThemePalette palette = ReaderThemePalette.from(settingsStore.getReaderTheme());
        readerRoot.setBackgroundColor(palette.backgroundColor);
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
        pageBodyCurrent.setFullJustifyEnabled(true);
        pageBodyIncoming.setFullJustifyEnabled(true);
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
        applyGlassOpacity();
        updateReaderHud();
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
        uiThemeSpinner.setSelection(indexOf(uiThemeKeys, settingsStore.getReaderUiThemeMode(), 0), false);
        final String[] selectedReaderTheme = new String[]{settingsStore.getReaderTheme()};
        updateReaderThemeButtons(paperThemeButton, forestThemeButton, nightThemeButton, selectedReaderTheme[0]);

        Runnable autoApply = () -> {
            int anchorOffset = currentCharOffset();
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
            if (!previousResolvedUiMode.equals(nextResolvedUiMode)) {
                recreate();
                return;
            }
            applyReaderSettings();
            openChapter(currentChapterIndex, anchorOffset, false, 0);
        };
        paperThemeButton.setOnClickListener(v -> {
            selectedReaderTheme[0] = "paper";
            updateReaderThemeButtons(paperThemeButton, forestThemeButton, nightThemeButton, selectedReaderTheme[0]);
            autoApply.run();
        });
        forestThemeButton.setOnClickListener(v -> {
            selectedReaderTheme[0] = "forest";
            updateReaderThemeButtons(paperThemeButton, forestThemeButton, nightThemeButton, selectedReaderTheme[0]);
            autoApply.run();
        });
        nightThemeButton.setOnClickListener(v -> {
            selectedReaderTheme[0] = "night";
            updateReaderThemeButtons(paperThemeButton, forestThemeButton, nightThemeButton, selectedReaderTheme[0]);
            autoApply.run();
        });
        updateStyleLabels(fontValue, lineValue, sideValue, verticalValue, fontSeek, lineSeek, sideSeek, verticalSeek);
        SeekBar.OnSeekBarChangeListener listener = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateStyleLabels(fontValue, lineValue, sideValue, verticalValue, fontSeek, lineSeek, sideSeek, verticalSeek);
                if (fromUser) {
                    autoApply.run();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                autoApply.run();
            }
        };
        fontSeek.setOnSeekBarChangeListener(listener);
        lineSeek.setOnSeekBarChangeListener(listener);
        sideSeek.setOnSeekBarChangeListener(listener);
        verticalSeek.setOnSeekBarChangeListener(listener);

        keepScreenOn.setOnCheckedChangeListener((v, isChecked) -> autoApply.run());
        showTitleCheck.setOnCheckedChangeListener((v, isChecked) -> autoApply.run());

        uiThemeSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                autoApply.run();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

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

        Spinner tLSpinner = content.findViewById(R.id.options_spinner_hud_tl);
        Spinner tCSpinner = content.findViewById(R.id.options_spinner_hud_tc);
        Spinner tRSpinner = content.findViewById(R.id.options_spinner_hud_tr);
        Spinner bLSpinner = content.findViewById(R.id.options_spinner_hud_bl);
        Spinner bCSpinner = content.findViewById(R.id.options_spinner_hud_bc);
        Spinner bRSpinner = content.findViewById(R.id.options_spinner_hud_br);
        titleInput.setText(book == null ? "" : book.title);
        authorInput.setText(book == null ? "" : book.author);
        showTitleCheck.setChecked(settingsStore.isChapterTitleVisible());
        String[] flipKeys = new String[]{"cover", "slide", "simulation", "scroll", "none"};
        ArrayAdapter<String> adapter = buildSpinnerAdapter(new String[]{"覆盖", "平移", "仿真", "滚动", "无动画"});
        flipSpinner.setAdapter(adapter);
        flipSpinner.setSelection(indexOf(flipKeys, settingsStore.getFlipMode(), 0), false);
        final String[] sliderMode = new String[]{settingsStore.getReaderSliderMode()};
        AlertDialog dialog = new AlertDialog.Builder(this).setView(content).create();
        styleThemeButton(sliderBookButton, "book".equals(sliderMode[0]));
        styleThemeButton(sliderChapterButton, "chapter".equals(sliderMode[0]));

        String[] hudKeys = new String[]{"none", "title", "chapter", "title_chapter", "time", "battery", "chapter_page", "book_progress", "page_and_progress", "time_and_battery"};
        ArrayAdapter<String> hudAdapter = buildSpinnerAdapter(new String[]{"无", "书名", "章节名", "书名 / 章节名", "现在时间", "系统电量", "本章页数进度", "全书进度", "页数及进度", "时间及电量"});
        tLSpinner.setAdapter(hudAdapter); tLSpinner.setSelection(indexOf(hudKeys, settingsStore.getHudTopLeft(), 0), false);
        tCSpinner.setAdapter(hudAdapter); tCSpinner.setSelection(indexOf(hudKeys, settingsStore.getHudTopCenter(), 0), false);
        tRSpinner.setAdapter(hudAdapter); tRSpinner.setSelection(indexOf(hudKeys, settingsStore.getHudTopRight(), 0), false);
        bLSpinner.setAdapter(hudAdapter); bLSpinner.setSelection(indexOf(hudKeys, settingsStore.getHudBottomLeft(), 0), false);
        bCSpinner.setAdapter(hudAdapter); bCSpinner.setSelection(indexOf(hudKeys, settingsStore.getHudBottomCenter(), 0), false);
        bRSpinner.setAdapter(hudAdapter); bRSpinner.setSelection(indexOf(hudKeys, settingsStore.getHudBottomRight(), 0), false);
        Runnable autoApply = () -> {
            String title = titleInput.getText().toString().trim();
            String author = authorInput.getText().toString().trim();
            if (title.isEmpty()) {
                title = "未命名书籍";
            }
            String finalTitle = title;
            String finalAuthor = author;
            int anchorOffset = currentCharOffset();
            if (book != null) {
                book.title = finalTitle;
                book.author = finalAuthor;
            }
            settingsStore.setFlipMode(flipKeys[flipSpinner.getSelectedItemPosition()]);
            settingsStore.setReaderSliderMode(sliderMode[0]);
            settingsStore.setChapterTitleVisible(showTitleCheck.isChecked());
            settingsStore.setHudTopLeft(hudKeys[tLSpinner.getSelectedItemPosition()]);
            settingsStore.setHudTopCenter(hudKeys[tCSpinner.getSelectedItemPosition()]);
            settingsStore.setHudTopRight(hudKeys[tRSpinner.getSelectedItemPosition()]);
            settingsStore.setHudBottomLeft(hudKeys[bLSpinner.getSelectedItemPosition()]);
            settingsStore.setHudBottomCenter(hudKeys[bCSpinner.getSelectedItemPosition()]);
            settingsStore.setHudBottomRight(hudKeys[bRSpinner.getSelectedItemPosition()]);
            clearPageCache();
            applyReaderSettings();
            openChapter(currentChapterIndex, anchorOffset, false, 0);
            executor.execute(() -> databaseHelper.updateBookInfo(bookId, finalTitle, finalAuthor));
        };

        TextWatcher textWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { autoApply.run(); }
        };
        titleInput.addTextChangedListener(textWatcher);
        authorInput.addTextChangedListener(textWatcher);
        showTitleCheck.setOnCheckedChangeListener((v, isChecked) -> autoApply.run());
        flipSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) { autoApply.run(); }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        android.widget.AdapterView.OnItemSelectedListener hudListener = new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) { autoApply.run(); }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        };
        tLSpinner.setOnItemSelectedListener(hudListener);
        tCSpinner.setOnItemSelectedListener(hudListener);
        tRSpinner.setOnItemSelectedListener(hudListener);
        bLSpinner.setOnItemSelectedListener(hudListener);
        bCSpinner.setOnItemSelectedListener(hudListener);
        bRSpinner.setOnItemSelectedListener(hudListener);

        sliderBookButton.setOnClickListener(v -> {
            sliderMode[0] = "book";
            styleThemeButton(sliderBookButton, true);
            styleThemeButton(sliderChapterButton, false);
            autoApply.run();
        });
        sliderChapterButton.setOnClickListener(v -> {
            sliderMode[0] = "chapter";
            styleThemeButton(sliderBookButton, false);
            styleThemeButton(sliderChapterButton, true);
            autoApply.run();
        });
        showStyledDialog(dialog);
    }

    private void showAutoPageDialog() {
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_auto_page, null, false);
        SeekBar seekBar = content.findViewById(R.id.auto_page_seek);
        TextView valueText = content.findViewById(R.id.auto_page_value);
        Button toggleButton = content.findViewById(R.id.auto_page_button_toggle);
        seekBar.setProgress(settingsStore.getAutoPageSeconds() - 1);
        valueText.setText(String.format(Locale.SIMPLIFIED_CHINESE, "%d 秒", settingsStore.getAutoPageSeconds()));
        seekBar.setOnSeekBarChangeListener(new SimpleSeekListener(() -> {
            int seconds = seekBar.getProgress() + 1;
            valueText.setText(String.format(Locale.SIMPLIFIED_CHINESE, "%d 秒", seconds));
            settingsStore.setAutoPageSeconds(seconds);
            if (autoPageActive) {
                scheduleNextAutoPageTick();
            }
        }));
        toggleButton.setText(autoPageActive ? "停止自动翻页" : "开始自动翻页");
        AlertDialog dialog = new AlertDialog.Builder(this).setView(content).create();
        toggleButton.setOnClickListener(v -> {
            if (autoPageActive) {
                stopAutoPage();
            } else {
                startAutoPage();
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
        seekBar.setOnSeekBarChangeListener(new SimpleSeekListener(() -> {
            float rate = 0.5f + (seekBar.getProgress() / 10f);
            valueText.setText(String.format(Locale.SIMPLIFIED_CHINESE, "%.1f 倍", rate));
            settingsStore.setTtsRate(rate);
            if (textToSpeech != null) {
                textToSpeech.setSpeechRate(rate);
            }
        }));
        engineSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                settingsStore.setTtsEngine(engineKeys[position]);
                updateTtsDialogState(noteText, mimoKeyInput, position == 1);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
        mimoKeyInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                settingsStore.setTtsMimoApiKey(s == null ? "" : s.toString());
            }
        });
        toggleButton.setText(ttsActive ? "停止听书" : "开始听书");
        AlertDialog dialog = new AlertDialog.Builder(this).setView(content).create();
        toggleButton.setOnClickListener(v -> {
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
        GlassUiHelper.applyToHierarchy(this, dialog.findViewById(android.R.id.content), settingsStore.getGlassOpacityPercent());
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
        int pageWidth = getReaderPageTextWidth();
        int regularPageHeight = getRegularReaderPageHeight();
        if (pageWidth <= 0 || regularPageHeight <= 0) {
            List<PageSlice> fallback = new ArrayList<>();
            fallback.add(new PageSlice(0, text.length(), text));
            return fallback;
        }
        int firstPageHeight = settingsStore.isChapterTitleVisible()
                ? Math.max(1, regularPageHeight - measureChapterTitleOccupiedHeight(chapters.get(chapterIndex).title, pageWidth))
                : regularPageHeight;
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        paint.setTextSize(pageBodyCurrent.getTextSize());
        paint.setTypeface(pageBodyCurrent.getTypeface());
        List<PageSlice> pages = ReaderPaginator.paginate(
                text,
                paint,
                pageWidth,
                firstPageHeight,
                regularPageHeight,
                pageBodyCurrent.getLineSpacingExtra()
        );
        chapterPageCache.put(chapterIndex, pages);
        return pages;
    }

    private int getReaderPageTextWidth() {
        if (pageCurrent != null && pageCurrent.getWidth() > 0) {
            return pageCurrent.getWidth() - pageCurrent.getPaddingLeft() - pageCurrent.getPaddingRight();
        }
        return pageBodyCurrent.getWidth();
    }

    private int getRegularReaderPageHeight() {
        if (pageCurrent != null && pageCurrent.getHeight() > 0) {
            return pageCurrent.getHeight() - pageCurrent.getPaddingTop() - pageCurrent.getPaddingBottom();
        }
        return pageBodyCurrent.getHeight();
    }

    private int measureChapterTitleOccupiedHeight(String title, int width) {
        if (title == null || title.isBlank() || width <= 0) {
            return 0;
        }
        TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        titlePaint.setTextSize(pageTitleCurrent.getTextSize());
        titlePaint.setTypeface(pageTitleCurrent.getTypeface());
        StaticLayout layout = StaticLayout.Builder.obtain(title, 0, title.length(), titlePaint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setMaxLines(2)
                .build();
        if (layout.getLineCount() == 0) {
            return 0;
        }
        int visibleLines = Math.min(layout.getLineCount(), 2);
        return layout.getLineBottom(visibleLines - 1) + dp(CHAPTER_TITLE_BODY_MARGIN_DP);
    }

    private String getProcessedChapterText(int chapterIndex) {
        String cached = processedChapterCache.get(chapterIndex);
        if (cached != null) {
            return cached;
        }
        String body = chapters.get(chapterIndex).bodyText == null ? "" : chapters.get(chapterIndex).bodyText;
        String processed = ReplacementEngine.apply(body, replacementRules);
        processedChapterCache.put(chapterIndex, processed);
        processedChapterLengthCache.put(chapterIndex, processed.length());
        return processed;
    }

    private int getProcessedChapterLength(int chapterIndex) {
        Integer cached = processedChapterLengthCache.get(chapterIndex);
        if (cached != null) {
            return cached;
        }
        int length = getProcessedChapterText(chapterIndex).length();
        processedChapterLengthCache.put(chapterIndex, length);
        return length;
    }

    private int getTotalProcessedBookLength() {
        if (totalProcessedBookLength >= 0) {
            return totalProcessedBookLength;
        }
        int total = 0;
        for (int i = 0; i < chapters.size(); i++) {
            total += getProcessedChapterLength(i);
        }
        totalProcessedBookLength = total;
        return totalProcessedBookLength;
    }

    private int currentCharOffset() {
        List<PageSlice> pages = getPagesForChapter(currentChapterIndex);
        if (pages.isEmpty()) {
            return 0;
        }
        return pages.get(clamp(currentPageIndex, 0, pages.size() - 1)).start;
    }

    private boolean rebuildTtsUnitsForChapter(int chapterIndex, int minOffset) {
        ttsUnits.clear();
        if (chapters.isEmpty()) {
            ttsChapterIndex = -1;
            currentTtsUnitIndex = -1;
            return false;
        }
        ttsChapterIndex = clamp(chapterIndex, 0, chapters.size() - 1);
        Matcher matcher = TTS_SEGMENT_PATTERN.matcher(getProcessedChapterText(ttsChapterIndex));
        while (matcher.find()) {
            String segment = matcher.group();
            if (segment == null || segment.trim().isEmpty()) {
                continue;
            }
            ttsUnits.add(new SpeechUnit(matcher.start(), matcher.end(), segment));
        }
        currentTtsUnitIndex = 0;
        while (currentTtsUnitIndex < ttsUnits.size() && ttsUnits.get(currentTtsUnitIndex).end <= minOffset) {
            currentTtsUnitIndex++;
        }
        return currentTtsUnitIndex < ttsUnits.size();
    }

    private void handleSystemTtsAdvance(String utteranceId) {
        int sessionId = parseTtsSessionId(utteranceId);
        mainHandler.post(() -> {
            if (!ttsActive || sessionId != ttsSessionId) {
                return;
            }
            advanceTtsPlayback(1);
        });
    }

    private int parseTtsSessionId(String utteranceId) {
        if (utteranceId == null || !utteranceId.startsWith(TTS_UTTERANCE_PREFIX)) {
            return -1;
        }
        try {
            return Integer.parseInt(utteranceId.substring(TTS_UTTERANCE_PREFIX.length()));
        } catch (NumberFormatException error) {
            return -1;
        }
    }

    private void advanceTtsPlayback(int consumedUnits) {
        if (!ttsActive) {
            return;
        }
        currentTtsUnitIndex += Math.max(consumedUnits, 0);
        playCurrentTtsUnit();
    }

    private void scheduleTtsPlayback(long delayMillis) {
        int sessionId = ttsSessionId;
        mainHandler.postDelayed(() -> {
            if (!ttsActive || sessionId != ttsSessionId) {
                return;
            }
            playCurrentTtsUnit();
        }, Math.max(delayMillis, 20L));
    }

    private void advanceToNextTtsChapter() {
        if (!ttsActive) {
            return;
        }
        if (ttsChapterIndex >= chapters.size() - 1) {
            stopTts();
            return;
        }
        int nextChapterIndex = ttsChapterIndex + 1;
        boolean chapterTurning = currentChapterIndex == ttsChapterIndex;
        if (chapterTurning) {
            if (!pageDown()) {
                stopTts();
                return;
            }
        } else {
            openChapter(nextChapterIndex, 0, true, 1);
        }
        int sessionId = ttsSessionId;
        mainHandler.postDelayed(() -> {
            if (!ttsActive || sessionId != ttsSessionId) {
                return;
            }
            rebuildTtsUnitsForChapter(nextChapterIndex, 0);
            playCurrentTtsUnit();
        }, readerFlipDurationMs() * 2L + 60L);
    }

    private void speakCurrentMimoGroup() {
        if (currentTtsUnitIndex < 0 || currentTtsUnitIndex >= ttsUnits.size()) {
            advanceToNextTtsChapter();
            return;
        }
        int groupCount = 1;
        StringBuilder builder = new StringBuilder(ttsUnits.get(currentTtsUnitIndex).text);
        while (currentTtsUnitIndex + groupCount < ttsUnits.size()
                && !endsWithFullSentence(ttsUnits.get(currentTtsUnitIndex + groupCount - 1).text)) {
            builder.append(ttsUnits.get(currentTtsUnitIndex + groupCount).text);
            groupCount++;
        }
        String groupText = builder.toString().trim();
        if (groupText.isEmpty()) {
            advanceTtsPlayback(groupCount);
            return;
        }
        int sessionId = ttsSessionId;
        int consumedUnits = groupCount;
        ttsExecutor.execute(() -> {
            try {
                mimoTtsClient.speak(groupText, settingsStore.getTtsMimoApiKey(), settingsStore.getTtsRate());
                runOnUiThread(() -> {
                    if (!ttsActive || sessionId != ttsSessionId) {
                        return;
                    }
                    advanceTtsPlayback(consumedUnits);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (!ttsActive || sessionId != ttsSessionId) {
                        return;
                    }
                    stopTts();
                    showToast("MiMo 听书失败: " + error.getMessage());
                });
            }
        });
    }

    private boolean endsWithFullSentence(String text) {
        if (text == null) {
            return false;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        char lastChar = trimmed.charAt(trimmed.length() - 1);
        return lastChar == '。' || lastChar == '！' || lastChar == '？' || lastChar == '!' || lastChar == '?';
    }

    private long readerFlipDurationMs() {
        if (settingsStore == null) {
            return 260L;
        }
        String mode = settingsStore.getFlipMode();
        if ("none".equals(mode)) {
            return 0L;
        }
        if ("cover".equals(mode)) {
            return 280L;
        }
        if ("simulation".equals(mode)) {
            return 320L;
        }
        if ("scroll".equals(mode)) {
            return 240L;
        }
        return 230L;
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
                    GlassUiHelper.applyToView(this, applyButton, settingsStore.getGlassOpacityPercent());
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
                            int anchorOffset = currentCharOffset();
                            String previousResolvedUiMode = ThemeModeHelper.getResolvedReaderThemeMode(this);
                            ReaderThemeConfig.apply(settingsStore, new JSONObject(theme.configJson));
                            clearPageCache();
                            String nextResolvedUiMode = ThemeModeHelper.getResolvedReaderThemeMode(this);
                            if (!previousResolvedUiMode.equals(nextResolvedUiMode)) {
                                recreate();
                                return;
                            }
                            applyReaderSettings();
                            openChapter(currentChapterIndex, anchorOffset, false, 0);
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
        processedChapterLengthCache.clear();
        chapterPageCache.clear();
        totalProcessedBookLength = -1;
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
        button.setTag(R.id.tag_glass_background, !active);
        button.setTextColor(active ? getColor(android.R.color.white) : themeColor(R.color.on_surface));
        GlassUiHelper.applyToView(this, button, settingsStore.getGlassOpacityPercent());
    }

    private void styleReaderMenuButton(Button button, boolean active) {
        button.setBackgroundResource(active ? R.drawable.bg_reader_menu_button_active : R.drawable.bg_reader_menu_button);
        button.setTag(R.id.tag_glass_background, !active);
        button.setTextColor(getColor(android.R.color.white));
        GlassUiHelper.applyToView(this, button, settingsStore.getGlassOpacityPercent());
    }

    private void applyGlassOpacity() {
        GlassUiHelper.applyToHierarchy(this, menuTopPanel, settingsStore.getGlassOpacityPercent());
        GlassUiHelper.applyToHierarchy(this, menuInfoPanel, settingsStore.getGlassOpacityPercent());
        GlassUiHelper.applyToHierarchy(this, menuBottomPanel, settingsStore.getGlassOpacityPercent());
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
        view.setTranslationY(0f);
        view.setRotationX(0f);
        view.setRotationY(0f);
        view.setScaleX(1f);
        view.setScaleY(1f);
        view.setPivotX(view.getWidth() * 0.5f);
        view.setPivotY(view.getHeight() * 0.5f);
        view.setAlpha(1f);
        view.setClipBounds(null);
    }

    private void stylePageContainer(View view, int pageColor) {
        view.setBackgroundColor(Color.TRANSPARENT);
    }

    private void updateBodyTopMargin(TextView bodyView, int topMargin) {
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) bodyView.getLayoutParams();
        params.topMargin = topMargin;
        bodyView.setLayoutParams(params);
    }

    private void prepareShadow(float startTranslationX) {
        if (pageShadow == null) {
            return;
        }
        pageShadow.animate().cancel();
        pageShadow.setVisibility(View.VISIBLE);
        pageShadow.setAlpha(0f);
        pageShadow.setTranslationX(startTranslationX);
    }

    private void arrangePagingLayers(String mode) {
        if ("scroll".equals(mode)) {
            pageCurrent.bringToFront();
            pageIncoming.bringToFront();
        } else {
            pageIncoming.bringToFront();
            pageCurrent.bringToFront();
        }
        if (pageShadow != null) {
            pageShadow.bringToFront();
        }
    }

    private void applyPagingVisuals(String mode, int direction, float progress, float touchY) {
        float width = Math.max(pageStage.getWidth(), dp(240));
        float height = Math.max(pageStage.getHeight(), dp(320));
        float safeProgress = Math.max(0f, Math.min(1f, progress));
        float safeTouchY = Math.max(0f, Math.min(height, touchY));
        int widthPx = Math.max(1, Math.round(width));
        int heightPx = Math.max(1, Math.round(height));
        resetAnimatedPage(pageCurrent);
        resetAnimatedPage(pageIncoming);
        pageIncoming.setVisibility(View.VISIBLE);

        if ("simulation".equals(mode)) {
            float revealWidth = width * 0.82f * safeProgress;
            float visibleWidth = Math.max(width * 0.18f, width - revealWidth);
            pageCurrent.setCameraDistance(width * 14f);
            pageCurrent.setPivotX(direction > 0 ? width : 0f);
            pageCurrent.setPivotY(safeTouchY);
            pageCurrent.setTranslationX((direction > 0 ? -1f : 1f) * width * 0.14f * safeProgress);
            pageCurrent.setTranslationY((safeTouchY / height - 0.5f) * height * 0.035f * safeProgress);
            pageCurrent.setRotationY((direction > 0 ? -1f : 1f) * 58f * safeProgress);
            pageCurrent.setRotationX((0.5f - (safeTouchY / height)) * 16f * safeProgress);
            pageCurrent.setScaleX(1f - 0.05f * safeProgress);
            pageCurrent.setScaleY(1f - 0.015f * safeProgress);
            pageCurrent.setAlpha(1f - 0.24f * safeProgress);
            if (direction > 0) {
                applyPageClip(pageCurrent, 0, Math.round(visibleWidth), heightPx);
            } else {
                applyPageClip(pageCurrent, widthPx - Math.round(visibleWidth), widthPx, heightPx);
            }
            pageIncoming.setAlpha(0.82f + 0.18f * safeProgress);
            pageIncoming.setScaleX(0.985f + 0.015f * safeProgress);
            pageIncoming.setScaleY(0.985f + 0.015f * safeProgress);
            pageIncoming.setTranslationX((direction > 0 ? 1f : -1f) * width * 0.035f * (1f - safeProgress));
            float edgeX = direction > 0
                    ? visibleWidth + pageCurrent.getTranslationX()
                    : (width - visibleWidth) + pageCurrent.getTranslationX();
            updateInteractiveShadow(edgeX, 0.16f + 0.30f * safeProgress);
            return;
        }

        if ("cover".equals(mode)) {
            pageCurrent.setTranslationX((direction > 0 ? -1f : 1f) * width * safeProgress);
            pageIncoming.setAlpha(1f);
            float edgeX = direction > 0 ? width + pageCurrent.getTranslationX() : pageCurrent.getTranslationX();
            updateInteractiveShadow(edgeX, 0.18f + 0.24f * safeProgress);
            return;
        }

        if ("scroll".equals(mode)) {
            float offsetY = (direction > 0 ? 1f : -1f) * height * safeProgress;
            pageCurrent.setTranslationY(-offsetY);
            pageIncoming.setTranslationY((direction > 0 ? 1f : -1f) * height * (1f - safeProgress));
            pageIncoming.setAlpha(0.94f + 0.06f * safeProgress);
            updateInteractiveShadow(width * 0.5f, 0f);
            return;
        }

        float revealWidth = width * safeProgress;
        pageCurrent.setTranslationX((direction > 0 ? -1f : 1f) * revealWidth);
        pageIncoming.setTranslationX(direction > 0 ? width - revealWidth : -width + revealWidth);
        if (direction > 0) {
            applyPageClip(pageIncoming, 0, Math.round(revealWidth), heightPx);
            updateInteractiveShadow(width - revealWidth, "none".equals(mode) ? 0f : 0.14f + 0.16f * safeProgress);
        } else {
            applyPageClip(pageIncoming, widthPx - Math.round(revealWidth), widthPx, heightPx);
            updateInteractiveShadow(revealWidth, "none".equals(mode) ? 0f : 0.14f + 0.16f * safeProgress);
        }
    }

    private void applyPageClip(View view, int left, int right, int height) {
        int width = Math.max(view.getWidth(), 1);
        int safeLeft = clamp(left, 0, width);
        int safeRight = clamp(right, 0, width);
        int safeHeight = Math.max(height, 1);
        if (safeRight <= safeLeft) {
            view.setClipBounds(new Rect(0, 0, 0, safeHeight));
            return;
        }
        view.setClipBounds(new Rect(safeLeft, 0, safeRight, safeHeight));
    }

    private void resetShadowView() {
        if (pageShadow == null) {
            return;
        }
        pageShadow.animate().cancel();
        pageShadow.setAlpha(0f);
        pageShadow.setVisibility(View.GONE);
        pageShadow.setTranslationX(0f);
        pageShadow.setScaleX(1f);
    }

    private void setControlsVisible(boolean visible) {
        if (controlsVisible == visible) {
            if (visible) {
                scheduleAutoHide();
                readerRoot.post(this::updateReaderLayoutInsets);
            } else {
                mainHandler.removeCallbacks(autoHideRunnable);
            }
            updateSystemBarsVisibility(visible);
            return;
        }
        controlsVisible = visible;
        animatePanel(hudTopContainer, !visible, -dp(12));
        animatePanel(hudBottomContainer, !visible, dp(12));
        animatePanel(menuTopPanel, visible, -dp(18));
        animatePanel(menuInfoPanel, visible, dp(14));
        animatePanel(menuBottomPanel, visible, dp(20));
        updateSystemBarsVisibility(visible);
        if (visible) {
            scheduleAutoHide();
            readerRoot.post(this::updateReaderLayoutInsets);
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

    private String readableError(Throwable error) {
        if (error == null || error.getMessage() == null || error.getMessage().isBlank()) {
            return "未知错误";
        }
        return error.getMessage();
    }

    private static class PageTarget {
        final int chapterIndex;
        final int pageIndex;

        private PageTarget(int chapterIndex, int pageIndex) {
            this.chapterIndex = chapterIndex;
            this.pageIndex = pageIndex;
        }
    }

    private static class SpeechUnit {
        final int start;
        final int end;
        final String text;

        private SpeechUnit(int start, int end, String text) {
            this.start = start;
            this.end = end;
            this.text = text;
        }
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
