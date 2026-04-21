package com.metahumanz.pacilread;

import android.app.AlertDialog;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import androidx.core.view.WindowCompat;
import android.view.GestureDetector;
import android.view.KeyEvent;
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

import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.util.LruCache;
import com.metahumanz.pacilread.model.BookRecord;
import com.metahumanz.pacilread.model.ChapterRecord;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import com.metahumanz.pacilread.model.ReaderThemeRecord;
import com.metahumanz.pacilread.model.ReplacementRuleRecord;
import com.metahumanz.pacilread.reader.JustifiedPageTextView;
import com.metahumanz.pacilread.reader.PageSlice;
import com.metahumanz.pacilread.reader.ReaderPaginator;
import com.metahumanz.pacilread.reader.ReaderThemeConfig;
import com.metahumanz.pacilread.reader.ReplacementEngine;
import com.metahumanz.pacilread.reader.SimulationPageTurnView;
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

public class ModernReaderActivity extends ThemedReaderActivity {
    private static final String TAG = "PacilReadReader";
    private static final int CHAPTER_TITLE_BODY_MARGIN_DP = 16;
    private static final int REQUEST_PICK_BACKGROUND = 2001;
    // 与 Win11 版完全一致的分句正则：[^ \n\t。！？.!?,，;；、]+[。！？.!?,，;；、]*
    private static final Pattern TTS_SEGMENT_PATTERN = Pattern.compile("[^ \\n\\t。！？.!?,，;；、]+[。！？.!?,，;；、]*");
    private static final DecelerateInterpolator PAGE_SLIDE_INTERPOLATOR = new DecelerateInterpolator(1.35f);
    private static final AccelerateDecelerateInterpolator PAGE_TURN_INTERPOLATOR = new AccelerateDecelerateInterpolator();
    private static final int MAX_PENDING_TAP_PAGE_STEPS = 2;
    private static final String[] READER_FONT_FAMILY_KEYS = new String[]{"system_default", "sans-serif", "monospace"};
    private static final String[] READER_FONT_FAMILY_LABELS = new String[]{"系统默认", "无衬线", "等宽体"};
    private static final int[] READER_FONT_WEIGHT_VALUES = new int[]{250, 400, 700};
    private static final String[] READER_FONT_WEIGHT_LABELS = new String[]{"细体", "标准", "粗体"};
    private static final String[] READER_TEXT_COLOR_KEYS = new String[]{"theme_default", "ink_brown", "graphite", "warm_gray", "jade_ink", "forest_ink", "moon_white"};
    private static final String[] READER_TEXT_COLOR_LABELS = new String[]{"跟随主题", "墨棕", "石墨", "暖灰", "青墨", "墨绿", "月白"};
    private static final String[] UI_THEME_KEYS = new String[]{"follow_app", "system", "light", "dark"};
    private static final String[] HUD_KEYS = new String[]{"none", "title", "chapter", "title_chapter", "time", "battery", "chapter_page", "book_progress", "page_and_progress", "time_and_battery"};
    private static final String[] FLIP_KEYS = new String[]{"cover", "slide", "simulation", "scroll", "none"};

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService ttsExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<SpeechUnit> ttsUnits = new ArrayList<>();
    private final List<ChapterRecord> chapters = new ArrayList<>();
    private final List<ReplacementRuleRecord> replacementRules = new ArrayList<>();
    // 使用 LruCache 优化内存占用，限制缓存条目数
    private final LruCache<Integer, String> processedChapterLruCache = new LruCache<>(100);
    private final Map<Integer, Integer> processedChapterLengthCache = new HashMap<>();


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
    private android.widget.ImageView pageSnapshotCurrent;
    private android.widget.ImageView pageSnapshotIncoming;
    private SimulationPageTurnView simulationPageTurnView;
    private View pageShadow;
    private View pageFoldShadow;
    private View pageFoldHighlight;

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
    private boolean ttsActive = false;
    private int ttsChapterIndex = -1;
    private int currentTtsUnitIndex = -1;
    private int ttsHighlightStart = -1;
    private int ttsHighlightEnd = -1;
    private int ttsSessionId = 0;
    private boolean isAnimating = false;
    private long animationToken = 0L;
    private int pagingTouchSlop;
    private boolean pagingGestureCandidate = false;
    private boolean interactivePaging = false;
    private boolean pagingSnapshotsVisible = false;
    private float pagingDownX = 0f;
    private float pagingDownY = 0f;
    private float pagingLastX = 0f;
    private float pagingVelocityX = 0f;
    private float interactiveProgress = 0f;
    private float interactiveStartX = 0f;
    private float interactiveStartY = 0f;
    private float interactiveTouchX = 0f;
    private float interactiveTouchY = 0f;
    private long pagingLastEventTime = 0L;
    private int interactiveDirection = 0;
    private int interactiveTargetChapterIndex = -1;
    private int interactiveTargetPageIndex = -1;
    private ValueAnimator interactiveAnimator;
    private int totalProcessedBookLength = -1;
    private int currentReaderPageColor = 0xFFF7F0E1;
    private int currentReaderTextColor = 0xFF5C4B37;
    private int pendingTapPagingDelta = 0;
    private float lastTapY = -1f;
    private final String[] selectedReaderTheme = new String[]{"paper"};

    private Spinner fontFamilySpinner;
    private Spinner textColorSpinner;
    private SeekBar fontSeek;
    private SeekBar fontWeightSeek;
    private SeekBar lineSeek;
    private SeekBar leftSeek;
    private SeekBar rightSeek;
    private SeekBar topSeek;
    private SeekBar bottomSeek;
    private SeekBar letterSpacingSeek;
    private SeekBar firstLineIndentSeek;
    private SeekBar backgroundBlurSeek;
    private CheckBox keepScreenOn;
    private CheckBox showTitleCheck;
    private Spinner uiThemeSpinner;
    private TextView textColorValue;
    private TextView fontValue;
    private TextView fontWeightValue;
    private TextView lineValue;
    private TextView leftValue;
    private TextView rightValue;
    private TextView topValue;
    private TextView bottomValue;
    private TextView letterSpacingValue;
    private TextView firstLineIndentValue;
    private TextView backgroundBlurValue;

    private EditText titleInput;
    private EditText authorInput;
    private Spinner flipSpinner;
    private Button sliderBookButton;
    private Button sliderChapterButton;

    private GestureDetector gestureDetector;

    private Bitmap currentPageSnapshotBitmap;
    private Bitmap incomingPageSnapshotBitmap;
    private final Canvas pagingSnapshotCanvas = new Canvas();
    private int preparedCurrentSnapshotChapterIndex = -1;
    private int preparedCurrentSnapshotPageIndex = -1;
    private int preparedIncomingSnapshotChapterIndex = -1;
    private int preparedIncomingSnapshotPageIndex = -1;

    // Static cache to avoid reloading book data and re-paginating during theme changes/recreations
    private static long lastCachedBookId = -1L;
    private static BookRecord cachedBook;
    private static final List<ChapterRecord> cachedChapters = new ArrayList<>();
    private static final List<ReplacementRuleRecord> cachedRules = new ArrayList<>();
    private static final Map<Integer, List<PageSlice>> cachedPageSlicesMap = new HashMap<>();
    
    // Layout fingerprint to invalidate page slices cache
    private static String cachedLayoutFontFamily;
    private static int cachedLayoutFontWeight;
    private static float cachedLayoutFontSize;
    private static float cachedLayoutLineSpacing;
    private static int cachedLayoutLeftPadding;
    private static int cachedLayoutRightPadding;
    private static int cachedLayoutTopPadding;
    private static int cachedLayoutBottomPadding;
    private static int cachedLayoutWidth;
    private static int cachedLayoutHeight;
    private final Runnable pagingSnapshotWarmupRunnable = this::warmPreparedPagingSnapshots;

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

    private long sessionStartTime = 0;
    private int sessionStartOffset = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
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

        // 记录会话开始
        sessionStartTime = System.currentTimeMillis();
        sessionStartOffset = 0; // 将在 loadBook 后更新

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
        recordSessionStats();
        pendingTapPagingDelta = 0;
        if (pageStage != null) {
            pageStage.removeCallbacks(pagingSnapshotWarmupRunnable);
        }
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
        recyclePagingSnapshots();
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
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (handleReaderVolumeKeyEvent(event)) {
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
            attachBackground(data.getData());
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
        pageSnapshotCurrent = findViewById(R.id.page_snapshot_current);
        pageSnapshotIncoming = findViewById(R.id.page_snapshot_incoming);
        simulationPageTurnView = findViewById(R.id.page_simulation_turn);
        pageShadow = findViewById(R.id.view_page_shadow);
        pageFoldShadow = findViewById(R.id.view_page_fold_shadow);
        pageFoldHighlight = findViewById(R.id.view_page_fold_highlight);
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
                dp(settingsStore.getHudVerticalMarginDp()) + systemInsetTop,
                dp(12) + systemInsetRight,
                0
        );
        hudBottomContainer.setPadding(
                dp(12) + systemInsetLeft,
                0,
                dp(12) + systemInsetRight,
                dp(settingsStore.getHudVerticalMarginDp()) + systemInsetBottom
        );
        pageStage.setPadding(0, 0, 0, 0);
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
        invalidatePreparedPagingSnapshots();
        schedulePagingSnapshotWarmup();
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
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                if (controlsVisible) {
                    if (isInsideView(e, menuTopPanel) || isInsideView(e, menuInfoPanel) || isInsideView(e, menuBottomPanel)) {
                        return false;
                    }
                    setControlsVisible(false);
                    return true;
                }
                float width = readerRoot.getWidth();
                float height = readerRoot.getHeight();
                float x = e.getX();
                float y = e.getY();
                
                float thirdW = width / 3f;
                float thirdH = height / 3f;
                
                // Grid coordinates (0, 1, or 2)
                int col = (int) (x / thirdW);
                int row = (int) (y / thirdH);
                
                lastTapY = y;
                
                if (col == 1 && row == 1) {
                    // Center (1,1): Toggle Controls
                    setControlsVisible(true);
                } else if (col == 0 || (col == 1 && row == 0)) {
                    // Left column (0,*) OR Top-Center (1,0): Previous Page
                    requestTapPageTurn(-1);
                } else {
                    // Right column (2,*) OR Bottom-Center (1,2): Next Page
                    requestTapPageTurn(1);
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
                if (interactivePaging) {
                    cancelInteractiveAnimator();
                }
                pagingGestureCandidate = isInsideView(event, pageStage) && !isAnimating;
                pagingDownX = localTouchX(event);
                pagingDownY = localTouchY(event);
                pagingLastX = pagingDownX;
                pagingLastEventTime = event.getEventTime();
                pagingVelocityX = 0f;
                captureInteractiveStartPoint(pagingDownX, pagingDownY);
                return false;
            case MotionEvent.ACTION_MOVE:
                if (!pagingGestureCandidate && !interactivePaging) {
                    return false;
                }
                updatePagingVelocity(event);
                float currentTouchX = localTouchX(event);
                float currentTouchY = localTouchY(event);
                if (!interactivePaging) {
                    float deltaX = currentTouchX - pagingDownX;
                    float deltaY = currentTouchY - pagingDownY;
                    if (Math.abs(deltaY) > pagingTouchSlop && Math.abs(deltaY) > Math.abs(deltaX)) {
                        pagingGestureCandidate = false;
                        return false;
                    }
                    if (Math.abs(deltaX) <= pagingTouchSlop || Math.abs(deltaX) <= Math.abs(deltaY)) {
                        return false;
                    }
                    int direction = deltaX < 0f ? 1 : -1;
                    if (!prepareInteractivePaging(direction, pagingDownX, pagingDownY)) {
                        pagingGestureCandidate = false;
                        return false;
                    }
                }
                updateInteractiveTouchPoint(currentTouchX, currentTouchY);
                float width = Math.max(pageStage.getWidth(), dp(240));
                float deltaX = currentTouchX - pagingDownX;
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
        float currentX = localTouchX(event);
        pagingVelocityX = (currentX - pagingLastX) / elapsed;
        pagingLastX = currentX;
        pagingLastEventTime = now;
    }

    private boolean handleReaderVolumeKeyEvent(KeyEvent event) {
        if (event == null || controlsVisible) {
            return false;
        }
        String action = null;
        if (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP) {
            action = settingsStore == null ? "page_up" : settingsStore.getVolumeKeyUpAction();
        } else if (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN) {
            action = settingsStore == null ? "page_down" : settingsStore.getVolumeKeyDownAction();
        }
        if (action == null || "system".equals(action)) {
            return false;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) {
                requestTapPageTurn("page_up".equals(action) ? -1 : 1);
            }
            return true;
        }
        return event.getAction() == KeyEvent.ACTION_UP;
    }

    private boolean prepareInteractivePaging(int direction, float startX, float startY) {
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
        captureInteractiveStartPoint(startX, startY);
        bindPage(pageTitleIncoming, pageBodyIncoming, target.chapterIndex, target.pageIndex);
        pageIncoming.setVisibility(View.VISIBLE);
        resetAnimatedPage(pageCurrent);
        resetAnimatedPage(pageIncoming);
        resetShadowView();
        preparePagingSnapshots(target.chapterIndex, target.pageIndex);
        arrangePagingLayers(settingsStore.getFlipMode());
        applyInteractivePagingProgress(0f, interactiveTouchY);
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

    private void updateInteractiveShadow(float edgeX, int direction, float alpha) {
        updatePagingOverlay(pageShadow, edgeX, direction, alpha, 1f, 1f, 0f, 0.56f);
    }

    private void updateInteractiveFoldShadow(float edgeX, int direction, float alpha, float scaleX, float rotation) {
        updatePagingOverlay(pageFoldShadow, edgeX, direction, alpha, 1f, scaleX, rotation, 0.8f);
    }

    private void updateInteractiveFoldHighlight(float edgeX, int direction, float alpha, float scaleX, float rotation) {
        updatePagingOverlay(pageFoldHighlight, edgeX, direction, alpha, 0.58f, scaleX, rotation, 0.34f);
    }

    private void hideInteractiveFoldEffects() {
        resetOverlayView(pageFoldShadow);
        resetOverlayView(pageFoldHighlight);
    }

    private void updatePagingOverlay(View overlay, float edgeX, int direction, float alpha, float anchorRatio, float scaleX, float rotation, float maxAlpha) {
        if (overlay == null) {
            return;
        }
        overlay.animate().cancel();
        float safeAlpha = Math.max(0f, Math.min(maxAlpha, alpha));
        if (safeAlpha <= 0f) {
            resetOverlayView(overlay);
            return;
        }
        float overlayWidth = Math.max(overlay.getWidth(), 1f);
        float safeAnchorRatio = Math.max(0f, Math.min(1f, anchorRatio));
        float anchorX = overlayWidth * safeAnchorRatio;
        overlay.setVisibility(View.VISIBLE);
        overlay.setPivotX(anchorX);
        overlay.setPivotY(Math.max(overlay.getHeight(), 1) * 0.5f);
        overlay.setTranslationX(edgeX - anchorX);
        overlay.setScaleX(direction > 0 ? scaleX : -scaleX);
        overlay.setScaleY(1f);
        overlay.setRotation(rotation);
        overlay.setAlpha(safeAlpha);
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
        final float startTouchX = interactiveTouchX;
        final float startTouchY = interactiveTouchY;
        final float targetTouchX = "simulation".equals(mode)
                ? resolveSimulationTargetTouchX(interactiveDirection, commit)
                : interactiveTouchX;
        final float targetTouchY = "simulation".equals(mode)
                ? resolveSimulationTargetTouchY(interactiveDirection)
                : interactiveTouchY;
        final boolean[] cancelled = new boolean[]{false};
        interactiveAnimator.addUpdateListener(animation -> {
            if ("simulation".equals(mode)) {
                float fraction = animation.getAnimatedFraction();
                interactiveTouchX = lerp(startTouchX, targetTouchX, fraction);
                interactiveTouchY = lerp(startTouchY, targetTouchY, fraction);
            }
            applyInteractivePagingProgress((float) animation.getAnimatedValue(), interactiveTouchY);
        });
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
        resetInteractiveTouchState();
        restoreLivePageLayers(false);
        resetAnimatedPage(pageCurrent);
        resetAnimatedPage(pageIncoming);
        pageIncoming.setVisibility(View.GONE);
        resetShadowView();
        isAnimating = false;
    }

    private float localTouchX(MotionEvent event) {
        int[] stageLocation = new int[2];
        pageStage.getLocationOnScreen(stageLocation);
        return event.getRawX() - stageLocation[0];
    }

    private float localTouchY(MotionEvent event) {
        int[] stageLocation = new int[2];
        pageStage.getLocationOnScreen(stageLocation);
        return event.getRawY() - stageLocation[1];
    }

    private void loadBook() {
        // Fast path: Reuse cached data if same book
        if (lastCachedBookId == bookId && cachedBook != null && !cachedChapters.isEmpty()) {
            book = cachedBook;
            chapters.clear();
            chapters.addAll(cachedChapters);
            replacementRules.clear();
            replacementRules.addAll(cachedRules);
            
            // Check if layout fingerprint changed to decide whether to clear slice cache
            if (!isLayoutFingerprintSame()) {
                cachedPageSlicesMap.clear();
                updateLayoutFingerprint();
            }
            
            int targetChapterIndex = clamp(chapterIndexFromOrder(book.progressIndex), 0, chapters.size() - 1);
            currentChapterIndex = targetChapterIndex;
            applyReaderSettings();
            
            if (restoredChapterIndex >= 0) {
                showPage(clamp(restoredChapterIndex, 0, chapters.size() - 1), Math.max(restoredPageIndex, 0), false, 0);
                restoredChapterIndex = -1;
                restoredPageIndex = -1;
            } else {
                openChapter(currentChapterIndex, book.progressOffset, false, 0);
            }
            return;
        }

        // Slow path: Load from DB
        executor.execute(() -> {
            try {
                BookRecord loadedBook = databaseHelper.getBook(bookId);
                List<ChapterRecord> loadedChapters = databaseHelper.getChapters(bookId, false);
                List<ReplacementRuleRecord> loadedRules = databaseHelper.getReplacementRules(bookId);
                runOnUiThread(() -> {
                    if (loadedBook == null || loadedChapters.isEmpty()) {
                        showToast("书籍不存在或内容为空");
                        finish();
                        return;
                    }
                    
                    // Update cache
                    lastCachedBookId = bookId;
                    cachedBook = loadedBook;
                    cachedChapters.clear();
                    cachedChapters.addAll(loadedChapters);
                    cachedRules.clear();
                    cachedRules.addAll(loadedRules);
                    cachedPageSlicesMap.clear();
                    updateLayoutFingerprint();

                    book = loadedBook;
                    chapters.clear();
                    chapters.addAll(loadedChapters);
                    replacementRules.clear();
                    replacementRules.addAll(loadedRules);
                    
                    int targetChapterIndex = clamp(chapterIndexFromOrder(loadedBook.progressIndex), 0, chapters.size() - 1);
                    currentChapterIndex = targetChapterIndex;
                    applyReaderSettings();
                    if (restoredChapterIndex >= 0) {
                        showPage(clamp(restoredChapterIndex, 0, chapters.size() - 1), Math.max(restoredPageIndex, 0), false, 0);
                        restoredChapterIndex = -1;
                        restoredPageIndex = -1;
                    } else {
                        openChapter(currentChapterIndex, loadedBook.progressOffset, false, 0);
                        sessionStartOffset = loadedBook.progressOffset;
                        mainHandler.postDelayed(() -> syncFromWebDav(true), 2000);
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

    private boolean isLayoutFingerprintSame() {
        if (pageStage == null) return true;
        return settingsStore.getReaderFontFamily().equals(cachedLayoutFontFamily)
                && settingsStore.getReaderFontWeight() == cachedLayoutFontWeight
                && settingsStore.getFontSizeSp() == cachedLayoutFontSize
                && settingsStore.getLineSpacingExtraSp() == cachedLayoutLineSpacing
                && settingsStore.getLeftPaddingDp() == cachedLayoutLeftPadding
                && settingsStore.getRightPaddingDp() == cachedLayoutRightPadding
                && settingsStore.getTopPaddingDp() == cachedLayoutTopPadding
                && settingsStore.getBottomPaddingDp() == cachedLayoutBottomPadding
                && pageStage.getWidth() == cachedLayoutWidth
                && pageStage.getHeight() == cachedLayoutHeight;
    }

    private void updateLayoutFingerprint() {
        if (pageStage == null) return;
        cachedLayoutFontFamily = settingsStore.getReaderFontFamily();
        cachedLayoutFontWeight = settingsStore.getReaderFontWeight();
        cachedLayoutFontSize = settingsStore.getFontSizeSp();
        cachedLayoutLineSpacing = settingsStore.getLineSpacingExtraSp();
        cachedLayoutLeftPadding = settingsStore.getLeftPaddingDp();
        cachedLayoutRightPadding = settingsStore.getRightPaddingDp();
        cachedLayoutTopPadding = settingsStore.getTopPaddingDp();
        cachedLayoutBottomPadding = settingsStore.getBottomPaddingDp();
        cachedLayoutWidth = pageStage.getWidth();
        cachedLayoutHeight = pageStage.getHeight();
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
            pendingTapPagingDelta = 0;
            invalidatePreparedPagingSnapshots();
            bindPage(pageTitleCurrent, pageBodyCurrent, safeChapterIndex, safePageIndex);
            currentChapterIndex = safeChapterIndex;
            currentPageIndex = safePageIndex;
            restoreLivePageLayers(false);
            resetAnimatedPage(pageCurrent);
            resetAnimatedPage(pageIncoming);
            pageIncoming.setVisibility(View.GONE);
            updateUiAfterPageChange();
            scheduleProgressSave();
            scheduleAutoHide();
            schedulePagingSnapshotWarmup();
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

    private boolean requestTapPageTurn(int direction) {
        if (direction == 0 || chapters.isEmpty()) {
            return false;
        }
        if (isAnimating || interactivePaging) {
            cancelInteractiveAnimator();
            isAnimating = false;
            interactivePaging = false;
            pendingTapPagingDelta = 0;
        }
        return direction > 0 ? pageDown() : pageUp();
    }

    private void consumePendingTapPageTurn() {
        if (pendingTapPagingDelta == 0 || controlsVisible || isAnimating || interactivePaging) {
            return;
        }
        boolean moved;
        if (pendingTapPagingDelta > 0) {
            moved = pageDown();
            if (moved) {
                pendingTapPagingDelta--;
            }
        } else {
            moved = pageUp();
            if (moved) {
                pendingTapPagingDelta++;
            }
        }
        if (!moved) {
            pendingTapPagingDelta = 0;
        }
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
                return currentTitleOrChapterHudText();
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

    private String currentTitleOrChapterHudText() {
        String bookTitle = currentBookTitle();
        String chapterTitle = currentChapterTitle();
        if (currentPageIndex == 0) {
            return bookTitle.isEmpty() ? chapterTitle : bookTitle;
        }
        return chapterTitle.isEmpty() ? bookTitle : chapterTitle;
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
        float width = Math.max(pageStage.getWidth(), dp(240));
        float height = Math.max(pageStage.getHeight(), dp(320));
        cancelInteractiveAnimator();
        resetAnimatedPage(pageCurrent);
        resetAnimatedPage(pageIncoming);
        if (simulationPageTurnView != null) {
            simulationPageTurnView.clear();
        }
        resetShadowView();
        pageIncoming.setVisibility(View.GONE);
        if ("none".equals(mode)) {
            finishAnimation(targetChapterIndex, targetPageIndex, token);
            return;
        }
        if ("simulation".equals(mode)) {
            initializeSimulationAutoStart(direction, width, height);
        } else {
            resetInteractiveTouchState();
        }
        // Ensure the incoming page is bound before taking snapshots
        bindPage(pageTitleIncoming, pageBodyIncoming, targetChapterIndex, targetPageIndex);
        preparePagingSnapshots(targetChapterIndex, targetPageIndex);
        arrangePagingLayers(mode);
        applyPagingVisuals(mode, direction, 0f, "simulation".equals(mode) ? interactiveTouchY : height * 0.5f);
        interactiveAnimator = ValueAnimator.ofFloat(0f, 1f);
        interactiveAnimator.setDuration(readerFlipDurationMs());
        interactiveAnimator.setInterpolator("simulation".equals(mode) ? PAGE_TURN_INTERPOLATOR : PAGE_SLIDE_INTERPOLATOR);
        final float startTouchX = interactiveTouchX;
        final float startTouchY = interactiveTouchY;
        final float targetTouchX = "simulation".equals(mode)
                ? resolveSimulationTargetTouchX(direction, true)
                : 0f;
        final float targetTouchY = "simulation".equals(mode)
                ? resolveSimulationTargetTouchY(direction)
                : height * 0.5f;
        final boolean[] cancelled = new boolean[]{false};
        interactiveAnimator.addUpdateListener(animation -> {
            if ("simulation".equals(mode)) {
                float fraction = animation.getAnimatedFraction();
                interactiveTouchX = lerp(startTouchX, targetTouchX, fraction);
                interactiveTouchY = lerp(startTouchY, targetTouchY, fraction);
            }
            applyPagingVisuals(
                    mode,
                    direction,
                    (float) animation.getAnimatedValue(),
                    "simulation".equals(mode) ? interactiveTouchY : height * 0.5f
            );
        });
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
        currentChapterIndex = targetChapterIndex;
        currentPageIndex = targetPageIndex;
        promoteIncomingSnapshotToCurrent(targetChapterIndex, targetPageIndex);
        bindPage(pageTitleCurrent, pageBodyCurrent, targetChapterIndex, targetPageIndex);
        boolean keepIncomingCover = pageIncoming != null && pageIncoming.getVisibility() == View.VISIBLE;
        restoreLivePageLayers(keepIncomingCover);
        resetAnimatedPage(pageCurrent);
        resetAnimatedPage(pageIncoming);
        resetShadowView();
        pagingGestureCandidate = false;
        interactivePaging = false;
        interactiveDirection = 0;
        interactiveProgress = 0f;
        interactiveTargetChapterIndex = -1;
        interactiveTargetPageIndex = -1;
        if (keepIncomingCover) {
            pageIncoming.bringToFront();
            pageCurrent.setVisibility(View.INVISIBLE);
            pageStage.post(() -> completeFinishedAnimationSwap(targetChapterIndex, targetPageIndex, token));
            return;
        }
        completeFinishedAnimationSwap(targetChapterIndex, targetPageIndex, token);
    }

    private void completeFinishedAnimationSwap(int targetChapterIndex, int targetPageIndex, long token) {
        if (token != animationToken) {
            return;
        }
        pageCurrent.setVisibility(View.VISIBLE);
        pageCurrent.bringToFront();
        pageIncoming.setVisibility(View.GONE);
        resetInteractiveTouchState();
        isAnimating = false;
        updateUiAfterPageChange();
        scheduleProgressSave();
        scheduleAutoHide();
        schedulePagingSnapshotWarmup();
        if (pageStage != null) {
            pageStage.post(this::consumePendingTapPageTurn);
        } else {
            consumePendingTapPageTurn();
        }
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
        showFullscreenDialog(dialog);
    }

    private void toggleTts() {
        if (ttsActive) {
            stopTts();
            return;
        }
        if (settingsStore.getTtsMimoApiKey().isBlank()) {
            showToast("请先填写 MiMo API Key");
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

        // Update highlight state — convert chapter-level offsets to page-local offsets
        PageSlice highlightSlice = pages.get(clamp(currentPageIndex, 0, pages.size() - 1));
        ttsHighlightStart = unit.start - highlightSlice.start;
        ttsHighlightEnd = unit.end - highlightSlice.start;
        updateTtsHighlight();

        speakCurrentMimoGroup();
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
        ttsHighlightStart = -1;
        ttsHighlightEnd = -1;
        updateTtsHighlight();
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
        Typeface bodyTypeface = buildReaderTypeface(settingsStore.getReaderFontFamily(), settingsStore.getReaderFontWeight());
        Typeface titleTypeface = buildReaderTypeface(
                settingsStore.getReaderFontFamily(),
                Math.max(600, Math.min(900, settingsStore.getReaderFontWeight() + 200))
        );
        int resolvedTextColor = resolveReaderTextColor(palette);
        currentReaderPageColor = palette.pageColor;
        currentReaderTextColor = resolvedTextColor;
        readerRoot.setBackgroundColor(palette.backgroundColor);
        stylePageContainer(pageCurrent, palette.pageColor);
        stylePageContainer(pageIncoming, palette.pageColor);
        pageTitleCurrent.setTextColor(resolvedTextColor);
        pageTitleIncoming.setTextColor(resolvedTextColor);
        pageBodyCurrent.setTextColor(resolvedTextColor);
        pageBodyIncoming.setTextColor(resolvedTextColor);
        pageTitleCurrent.setTypeface(titleTypeface);
        pageTitleIncoming.setTypeface(titleTypeface);
        pageBodyCurrent.setTypeface(bodyTypeface);
        pageBodyIncoming.setTypeface(bodyTypeface);
        pageTitleCurrent.setIncludeFontPadding(false);
        pageTitleIncoming.setIncludeFontPadding(false);
        pageTitleCurrent.setTextSize(settingsStore.getFontSizeSp() + 2f);
        pageTitleIncoming.setTextSize(settingsStore.getFontSizeSp() + 2f);
        pageBodyCurrent.setTextSize(settingsStore.getFontSizeSp());
        pageBodyIncoming.setTextSize(settingsStore.getFontSizeSp());
        pageBodyCurrent.setLineSpacing(settingsStore.getLineSpacingExtraSp(), 1f);
        pageBodyIncoming.setLineSpacing(settingsStore.getLineSpacingExtraSp(), 1f);
        pageBodyCurrent.setLetterSpacing(settingsStore.getLetterSpacing());
        pageBodyIncoming.setLetterSpacing(settingsStore.getLetterSpacing());
        pageBodyCurrent.setFullJustifyEnabled(settingsStore.isBodyTextJustified());
        pageBodyIncoming.setFullJustifyEnabled(settingsStore.isBodyTextJustified());
        
        // Apply first line indent
        int indentPx = dp(settingsStore.getFirstLineIndentDp());
        pageBodyCurrent.setPadding(
            ((ViewGroup) pageCurrent).getPaddingLeft() + indentPx,
            ((ViewGroup) pageCurrent).getPaddingTop(),
            ((ViewGroup) pageCurrent).getPaddingRight(),
            ((ViewGroup) pageCurrent).getPaddingBottom()
        );
        pageBodyIncoming.setPadding(
            ((ViewGroup) pageIncoming).getPaddingLeft() + indentPx,
            ((ViewGroup) pageIncoming).getPaddingTop(),
            ((ViewGroup) pageIncoming).getPaddingRight(),
            ((ViewGroup) pageIncoming).getPaddingBottom()
        );
        
        // Apply chapter title alignment
        String alignment = settingsStore.getChapterTitleAlignment();
        pageTitleCurrent.setGravity("center".equals(alignment) ? android.view.Gravity.CENTER : android.view.Gravity.LEFT);
        pageTitleIncoming.setGravity("center".equals(alignment) ? android.view.Gravity.CENTER : android.view.Gravity.LEFT);
        
        invalidatePreparedPagingSnapshots();
        updateTtsHighlight();
        int leftPadding = dp(settingsStore.getLeftPaddingDp());
        int rightPadding = dp(settingsStore.getRightPaddingDp());
        int topPadding = dp(settingsStore.getTopPaddingDp() + 32) + systemInsetTop;
        int bottomPadding = dp(settingsStore.getBottomPaddingDp() + 32) + systemInsetBottom;
        ((ViewGroup) pageCurrent).setPadding(leftPadding, topPadding, rightPadding, bottomPadding);
        ((ViewGroup) pageIncoming).setPadding(leftPadding, topPadding, rightPadding, bottomPadding);
        if (settingsStore.isKeepScreenOn()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        applyBackgroundImage();
        updateSystemBarsVisibility(controlsVisible);
        applyGlassOpacity();
        updateReaderHud();
        invalidatePreparedPagingSnapshots();
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
            resultCount.setText("正在搜索...");
            
            executor.execute(() -> {
                List<SearchResult> tempResults = new ArrayList<>();
                for (int i = 0; i < chapters.size(); i++) {
                    String text = getProcessedChapterText(i);
                    int index = text.toLowerCase(Locale.ROOT).indexOf(query);
                    if (index >= 0) {
                        String snippet = text.substring(Math.max(0, index - 18), Math.min(text.length(), index + query.length() + 24)).replace('\n', ' ').trim();
                        tempResults.add(new SearchResult(i, chapters.get(i).title, snippet, index));
                    }
                }
                
                runOnUiThread(() -> {
                    results.clear();
                    results.addAll(tempResults);
                    adapter.clear();
                    for (SearchResult r : results) {
                        adapter.add(r.chapterTitle + "\n" + r.snippet);
                    }
                    resultCount.setText(results.isEmpty() ? "没有找到匹配内容" : "找到 " + results.size() + " 条结果");
                });
            });
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
            // 简单的正则语法预检
            if (regexCheck.isChecked()) {
                try {
                    java.util.regex.Pattern.compile(pattern);
                } catch (Exception e) {
                    showToast("正则表达式语法错误: " + e.getMessage());
                    return;
                }
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
        fontFamilySpinner = content.findViewById(R.id.style_spinner_font_family);
        textColorSpinner = content.findViewById(R.id.style_spinner_text_color);
        fontSeek = content.findViewById(R.id.style_seek_font);
        fontWeightSeek = content.findViewById(R.id.style_seek_font_weight);
        lineSeek = content.findViewById(R.id.style_seek_line_spacing);
        leftSeek = content.findViewById(R.id.style_seek_left_padding);
        rightSeek = content.findViewById(R.id.style_seek_right_padding);
        topSeek = content.findViewById(R.id.style_seek_top_padding);
        bottomSeek = content.findViewById(R.id.style_seek_bottom_padding);
        letterSpacingSeek = content.findViewById(R.id.style_seek_letter_spacing);
        firstLineIndentSeek = content.findViewById(R.id.style_seek_first_line_indent);
        backgroundBlurSeek = content.findViewById(R.id.style_seek_background_blur);
        textColorValue = content.findViewById(R.id.style_text_text_color);
        fontValue = content.findViewById(R.id.style_text_font);
        fontWeightValue = content.findViewById(R.id.style_text_font_weight);
        lineValue = content.findViewById(R.id.style_text_line_spacing);
        leftValue = content.findViewById(R.id.style_text_left_padding);
        rightValue = content.findViewById(R.id.style_text_right_padding);
        topValue = content.findViewById(R.id.style_text_top_padding);
        bottomValue = content.findViewById(R.id.style_text_bottom_padding);
        letterSpacingValue = content.findViewById(R.id.style_text_letter_spacing);
        firstLineIndentValue = content.findViewById(R.id.style_text_first_line_indent);
        backgroundBlurValue = content.findViewById(R.id.style_text_background_blur);
        uiThemeSpinner = content.findViewById(R.id.style_spinner_ui_theme_mode);
        keepScreenOn = content.findViewById(R.id.style_check_keep_screen_on);
        showTitleCheck = content.findViewById(R.id.style_check_show_title);
        TextView backgroundText = content.findViewById(R.id.style_text_background);
        LinearLayout customThemeList = content.findViewById(R.id.style_custom_theme_list);
        Button paperThemeButton = content.findViewById(R.id.style_button_theme_paper);
        Button forestThemeButton = content.findViewById(R.id.style_button_theme_forest);
        Button nightThemeButton = content.findViewById(R.id.style_button_theme_night);
        Button titleLeftButton = content.findViewById(R.id.style_button_title_left);
        Button titleCenterButton = content.findViewById(R.id.style_button_title_center);
        Button bodyJustifyButton = content.findViewById(R.id.style_button_body_justify);
        Button bodyLeftButton = content.findViewById(R.id.style_button_body_left);
        Button customColorButton = content.findViewById(R.id.style_button_custom_color);
        String[] uiThemeKeys = new String[]{"follow_app", "system", "light", "dark"};
        ArrayAdapter<String> uiThemeAdapter = buildSpinnerAdapter(new String[]{"跟随应用", "跟随系统", "浅色", "深色"});
        ArrayAdapter<String> fontFamilyAdapter = buildSpinnerAdapter(READER_FONT_FAMILY_LABELS);
        ArrayAdapter<String> textColorAdapter = buildSpinnerAdapter(READER_TEXT_COLOR_LABELS);
        uiThemeSpinner.setAdapter(uiThemeAdapter);
        fontFamilySpinner.setAdapter(fontFamilyAdapter);
        textColorSpinner.setAdapter(textColorAdapter);
        fontFamilySpinner.setSelection(indexOf(READER_FONT_FAMILY_KEYS, settingsStore.getReaderFontFamily(), 0), false);
        textColorSpinner.setSelection(indexOf(READER_TEXT_COLOR_KEYS, settingsStore.getReaderTextColor(), 0), false);
        fontSeek.setProgress(Math.round(settingsStore.getFontSizeSp()) - 12);
        fontWeightSeek.setProgress(fontWeightProgress(settingsStore.getReaderFontWeight()));
        lineSeek.setProgress(Math.round(settingsStore.getLineSpacingExtraSp()));
        leftSeek.setProgress(settingsStore.getLeftPaddingDp());
        rightSeek.setProgress(settingsStore.getRightPaddingDp());
        topSeek.setProgress(settingsStore.getTopPaddingDp());
        bottomSeek.setProgress(settingsStore.getBottomPaddingDp());
        letterSpacingSeek.setProgress(Math.round(settingsStore.getLetterSpacing() * 10f));
        firstLineIndentSeek.setProgress(settingsStore.getFirstLineIndentDp());
        backgroundBlurSeek.setProgress(settingsStore.getBackgroundBlurPercent());
        keepScreenOn.setChecked(settingsStore.isKeepScreenOn());
        showTitleCheck.setChecked(settingsStore.isChapterTitleVisible());
        backgroundText.setText(currentBackgroundLabel());
        uiThemeSpinner.setSelection(indexOf(uiThemeKeys, settingsStore.getReaderUiThemeMode(), 0), false);
        selectedReaderTheme[0] = settingsStore.getReaderTheme();
        updateReaderThemeButtons(paperThemeButton, forestThemeButton, nightThemeButton, selectedReaderTheme[0]);
        String chapterTitleAlignment = settingsStore.getChapterTitleAlignment();
        styleThemeButton(titleLeftButton, "left".equals(chapterTitleAlignment));
        styleThemeButton(titleCenterButton, "center".equals(chapterTitleAlignment));
        styleThemeButton(bodyJustifyButton, settingsStore.isBodyTextJustified());
        styleThemeButton(bodyLeftButton, !settingsStore.isBodyTextJustified());
        updateLetterSpacingLabel(letterSpacingValue, letterSpacingSeek);
        updateFirstLineIndentLabel(firstLineIndentValue, firstLineIndentSeek);
        updateBackgroundBlurLabel(backgroundBlurValue, backgroundBlurSeek);
        Runnable refreshTextColorPreview = () -> updateTextColorPreview(
                textColorValue,
                READER_TEXT_COLOR_KEYS[textColorSpinner.getSelectedItemPosition()],
                ReaderThemePalette.from(selectedReaderTheme[0])
        );
        refreshTextColorPreview.run();
        Runnable autoApply = () -> {
            int anchorOffset = currentCharOffset();
            String previousResolvedUiMode = ThemeModeHelper.getResolvedReaderThemeMode(this);
            
            settingsStore.setReaderFontFamily(READER_FONT_FAMILY_KEYS[fontFamilySpinner.getSelectedItemPosition()]);
            settingsStore.setReaderTextColor(READER_TEXT_COLOR_KEYS[textColorSpinner.getSelectedItemPosition()]);
            settingsStore.setFontSizeSp(fontSeek.getProgress() + 12);
            settingsStore.setReaderFontWeight(fontWeightValueForProgress(fontWeightSeek.getProgress()));
            settingsStore.setLineSpacingExtraSp(lineSeek.getProgress());
            settingsStore.setLeftPaddingDp(leftSeek.getProgress());
            settingsStore.setRightPaddingDp(rightSeek.getProgress());
            settingsStore.setTopPaddingDp(topSeek.getProgress());
            settingsStore.setBottomPaddingDp(bottomSeek.getProgress());
            settingsStore.setLetterSpacing(letterSpacingSeek.getProgress() / 10f);
            settingsStore.setFirstLineIndentDp(firstLineIndentSeek.getProgress());
            settingsStore.setBackgroundBlurPercent(backgroundBlurSeek.getProgress());
            settingsStore.setKeepScreenOn(keepScreenOn.isChecked());
            settingsStore.setChapterTitleVisible(showTitleCheck.isChecked());
            settingsStore.setReaderTheme(selectedReaderTheme[0]);
            settingsStore.setReaderUiThemeMode(UI_THEME_KEYS[uiThemeSpinner.getSelectedItemPosition()]);
            
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
            refreshTextColorPreview.run();
            autoApply.run();
        });
        forestThemeButton.setOnClickListener(v -> {
            selectedReaderTheme[0] = "forest";
            updateReaderThemeButtons(paperThemeButton, forestThemeButton, nightThemeButton, selectedReaderTheme[0]);
            refreshTextColorPreview.run();
            autoApply.run();
        });
        nightThemeButton.setOnClickListener(v -> {
            selectedReaderTheme[0] = "night";
            updateReaderThemeButtons(paperThemeButton, forestThemeButton, nightThemeButton, selectedReaderTheme[0]);
            refreshTextColorPreview.run();
            autoApply.run();
        });
        updateStyleLabels(fontValue, fontWeightValue, lineValue, leftValue, rightValue, topValue, bottomValue, fontSeek, fontWeightSeek, lineSeek, leftSeek, rightSeek, topSeek, bottomSeek);
        SeekBar.OnSeekBarChangeListener listener = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateStyleLabels(fontValue, fontWeightValue, lineValue, leftValue, rightValue, topValue, bottomValue, fontSeek, fontWeightSeek, lineSeek, leftSeek, rightSeek, topSeek, bottomSeek);
                if (fromUser) {
                    autoApply.run();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                autoApply.run();
            }
        };
        fontWeightSeek.setOnSeekBarChangeListener(listener);
        fontSeek.setOnSeekBarChangeListener(listener);
        lineSeek.setOnSeekBarChangeListener(listener);
        leftSeek.setOnSeekBarChangeListener(listener);
        rightSeek.setOnSeekBarChangeListener(listener);
        topSeek.setOnSeekBarChangeListener(listener);
        bottomSeek.setOnSeekBarChangeListener(listener);
        
        SeekBar.OnSeekBarChangeListener simpleListener = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (seekBar == letterSpacingSeek) {
                    updateLetterSpacingLabel(letterSpacingValue, letterSpacingSeek);
                } else if (seekBar == firstLineIndentSeek) {
                    updateFirstLineIndentLabel(firstLineIndentValue, firstLineIndentSeek);
                } else if (seekBar == backgroundBlurSeek) {
                    updateBackgroundBlurLabel(backgroundBlurValue, backgroundBlurSeek);
                }
                if (fromUser) {
                    autoApply.run();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                autoApply.run();
            }
        };
        letterSpacingSeek.setOnSeekBarChangeListener(simpleListener);
        firstLineIndentSeek.setOnSeekBarChangeListener(simpleListener);
        backgroundBlurSeek.setOnSeekBarChangeListener(simpleListener);

        keepScreenOn.setOnCheckedChangeListener((v, isChecked) -> autoApply.run());
        showTitleCheck.setOnCheckedChangeListener((v, isChecked) -> autoApply.run());
        fontFamilySpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                autoApply.run();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        textColorSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                refreshTextColorPreview.run();
                autoApply.run();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        uiThemeSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                autoApply.run();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        titleLeftButton.setOnClickListener(v -> {
            settingsStore.setChapterTitleAlignment("left");
            styleThemeButton(titleLeftButton, true);
            styleThemeButton(titleCenterButton, false);
            autoApply.run();
        });
        titleCenterButton.setOnClickListener(v -> {
            settingsStore.setChapterTitleAlignment("center");
            styleThemeButton(titleLeftButton, false);
            styleThemeButton(titleCenterButton, true);
            autoApply.run();
        });
        bodyJustifyButton.setOnClickListener(v -> {
            settingsStore.setBodyTextJustified(true);
            styleThemeButton(bodyJustifyButton, true);
            styleThemeButton(bodyLeftButton, false);
            autoApply.run();
        });
        bodyLeftButton.setOnClickListener(v -> {
            settingsStore.setBodyTextJustified(false);
            styleThemeButton(bodyJustifyButton, false);
            styleThemeButton(bodyLeftButton, true);
            autoApply.run();
        });
        
        customColorButton.setOnClickListener(v -> showCustomColorPickerDialog(() -> {
            refreshTextColorPreview.run();
            autoApply.run();
        }));

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
        titleInput = content.findViewById(R.id.options_input_title);
        authorInput = content.findViewById(R.id.options_input_author);
        showTitleCheck = content.findViewById(R.id.options_check_show_title);
        flipSpinner = content.findViewById(R.id.options_spinner_flip_mode);
        Spinner flipSpeedSpinner = content.findViewById(R.id.options_spinner_flip_speed);
        sliderBookButton = content.findViewById(R.id.options_button_slider_book);
        sliderChapterButton = content.findViewById(R.id.options_button_slider_chapter);
        SeekBar hudMarginSeek = content.findViewById(R.id.options_seek_hud_vertical_margin);
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
        
        String[] speedKeys = new String[]{"fast", "medium", "slow"};
        ArrayAdapter<String> speedAdapter = buildSpinnerAdapter(new String[]{"较快", "适中", "较慢"});
        flipSpeedSpinner.setAdapter(speedAdapter);
        flipSpeedSpinner.setSelection(indexOf(speedKeys, settingsStore.getFlipSpeed(), 1), false);
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
        
        hudMarginSeek.setProgress(settingsStore.getHudVerticalMarginDp());

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
            settingsStore.setFlipSpeed(speedKeys[flipSpeedSpinner.getSelectedItemPosition()]);
            settingsStore.setReaderSliderMode(sliderMode[0]);
            settingsStore.setChapterTitleVisible(showTitleCheck.isChecked());
            settingsStore.setHudTopLeft(hudKeys[tLSpinner.getSelectedItemPosition()]);
            settingsStore.setHudTopCenter(hudKeys[tCSpinner.getSelectedItemPosition()]);
            settingsStore.setHudTopRight(hudKeys[tRSpinner.getSelectedItemPosition()]);
            settingsStore.setHudBottomLeft(hudKeys[bLSpinner.getSelectedItemPosition()]);
            settingsStore.setHudBottomCenter(hudKeys[bCSpinner.getSelectedItemPosition()]);
            settingsStore.setHudBottomRight(hudKeys[bRSpinner.getSelectedItemPosition()]);
            settingsStore.setHudVerticalMarginDp(hudMarginSeek.getProgress());
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
        
        hudMarginSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { if (fromUser) autoApply.run(); }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        android.widget.AdapterView.OnItemSelectedListener flipListener = new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) { autoApply.run(); }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        };
        flipSpinner.setOnItemSelectedListener(flipListener);
        flipSpeedSpinner.setOnItemSelectedListener(flipListener);

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
        SeekBar seekBar = content.findViewById(R.id.tts_seek_rate);
        TextView valueText = content.findViewById(R.id.tts_text_rate);
        EditText mimoKeyInput = content.findViewById(R.id.tts_input_mimo_api_key);
        TextView noteText = content.findViewById(R.id.tts_text_note);
        Button toggleButton = content.findViewById(R.id.tts_button_toggle);
        seekBar.setProgress(clamp(Math.round((settingsStore.getTtsRate() - 0.5f) * 10f), 0, 15));
        valueText.setText(String.format(Locale.SIMPLIFIED_CHINESE, "%.1f 倍", settingsStore.getTtsRate()));
        mimoKeyInput.setText(settingsStore.getTtsMimoApiKey());
        noteText.setText("MiMo 模式会调用小米云端 TTS，模型固定为 mimo-v2-tts / mimo_default。");
        seekBar.setOnSeekBarChangeListener(new SimpleSeekListener(() -> {
            float rate = 0.5f + (seekBar.getProgress() / 10f);
            valueText.setText(String.format(Locale.SIMPLIFIED_CHINESE, "%.1f 倍", rate));
            settingsStore.setTtsRate(rate);
        }));
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

    private void showFullscreenDialog(AlertDialog dialog) {
        showStyledDialog(dialog);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
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
                applyBackgroundBlur();
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
                applyBackgroundBlur();
            } else {
                readerBackgroundImage.setImageDrawable(null);
                readerBackgroundImage.setVisibility(View.GONE);
            }
            return;
        }
        readerBackgroundImage.setImageBitmap(bitmap);
        readerBackgroundImage.setVisibility(View.VISIBLE);
        applyBackgroundBlur();
    }

    private void applyBackgroundBlur() {
        int blurPercent = settingsStore.getBackgroundBlurPercent();
        if (blurPercent <= 0) {
            readerBackgroundImage.setAlpha(1.0f);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                readerBackgroundImage.setRenderEffect(null);
            }
            return;
        }
        
        float alpha = 1.0f - (blurPercent / 100f * 0.5f); // 最多降低50%透明度
        readerBackgroundImage.setAlpha(alpha);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            float radius = blurPercent / 100f * 25f; // 最大模糊半径25px
            android.graphics.RenderEffect blurEffect = android.graphics.RenderEffect.createBlurEffect(
                radius, radius, android.graphics.Shader.TileMode.CLAMP
            );
            readerBackgroundImage.setRenderEffect(blurEffect);
        }
    }

    private void openBackgroundPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, REQUEST_PICK_BACKGROUND);
    }

    private List<PageSlice> getPagesForChapter(int chapterIndex) {
        if (cachedPageSlicesMap.containsKey(chapterIndex)) {
            return cachedPageSlicesMap.get(chapterIndex);
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
        cachedPageSlicesMap.put(chapterIndex, pages);
        return pages;
    }

    private int getReaderPageTextWidth() {
        // Use the actual body text view width — it is the true usable area for
        // pagination. Fall back to subtracting container padding only when the
        // body hasn't been laid out yet.
        if (pageBodyCurrent != null && pageBodyCurrent.getWidth() > 0) {
            return pageBodyCurrent.getWidth()
                    - pageBodyCurrent.getPaddingLeft()
                    - pageBodyCurrent.getPaddingRight();
        }
        if (pageCurrent != null && pageCurrent.getWidth() > 0) {
            return pageCurrent.getWidth() - pageCurrent.getPaddingLeft() - pageCurrent.getPaddingRight();
        }
        return 0;
    }

    private int getRegularReaderPageHeight() {
        // The body view's measured height is the only reliable source: it
        // already accounts for the LinearLayout weight, marginTop, and any
        // other spacing.  Using the container height minus padding is wrong
        // because it ignores the 14dp marginTop between title and body, which
        // causes every page to overflow by that margin and creates gaps when
        // turning pages.
        if (pageBodyCurrent != null && pageBodyCurrent.getHeight() > 0) {
            return pageBodyCurrent.getHeight();
        }
        // Fallback before first layout pass
        if (pageCurrent != null && pageCurrent.getHeight() > 0) {
            return pageCurrent.getHeight()
                    - pageCurrent.getPaddingTop()
                    - pageCurrent.getPaddingBottom()
                    - dp(14); // subtract body marginTop
        }
        return 0;
    }

    private int measureChapterTitleOccupiedHeight(String title, int width) {
        if (title == null || title.isBlank() || width <= 0) {
            return 0;
        }
        TextView measureView = new TextView(this);
        measureView.setIncludeFontPadding(false);
        measureView.setMaxLines(2);
        measureView.setTypeface(pageTitleCurrent.getTypeface());
        measureView.setTextSize(TypedValue.COMPLEX_UNIT_PX, pageTitleCurrent.getTextSize());
        measureView.setText(title);
        int widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY);
        int heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        measureView.measure(widthSpec, heightSpec);
        int safetyBuffer = Math.max(dp(2), Math.round(pageBodyCurrent.getPaint().getFontSpacing() * 0.08f));
        return measureView.getMeasuredHeight() + dp(CHAPTER_TITLE_BODY_MARGIN_DP) + safetyBuffer;
    }
    private String getProcessedChapterText(int chapterIndex) {
        String cached = processedChapterLruCache.get(chapterIndex);
        if (cached != null) {
            return cached;
        }
        ChapterRecord chapter = chapters.get(chapterIndex);
        if (chapter.bodyText == null) {
            ChapterRecord fullChapter = databaseHelper.getChapterContent(chapter.id);
            if (fullChapter != null) {
                chapter.bodyText = fullChapter.bodyText;
                chapter.bodyHtml = fullChapter.bodyHtml;
            }
        }
        String body = chapter.bodyText == null ? "" : chapter.bodyText;
        String processed = ReplacementEngine.apply(body, replacementRules);
        processedChapterLruCache.put(chapterIndex, processed);
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
        speakWithMimo();
    }


    private void speakWithMimo() {
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
        long baseDuration = 180L;
        if ("none".equals(mode)) {
            return 0L;
        } else if ("cover".equals(mode)) {
            baseDuration = 220L;
        } else if ("simulation".equals(mode)) {
            baseDuration = 300L;
        } else if ("scroll".equals(mode)) {
            baseDuration = 190L;
        }
        
        String speed = settingsStore.getFlipSpeed();
        if ("fast".equals(speed)) {
            return (long) (baseDuration * 0.6f);
        } else if ("slow".equals(speed)) {
            return (long) (baseDuration * 1.5f);
        }
        return baseDuration;
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
                    Runnable refreshTextColorPreview = () -> updateTextColorPreview(
                            textColorValue,
                            READER_TEXT_COLOR_KEYS[textColorSpinner.getSelectedItemPosition()],
                            ReaderThemePalette.from(selectedReaderTheme[0])
                    );
                    refreshTextColorPreview.run();

                    Runnable autoApply = () -> {
                        int anchorOffset = currentCharOffset();
                        String previousResolvedUiMode = ThemeModeHelper.getResolvedReaderThemeMode(this);
                        settingsStore.setFontSizeSp(fontSeek.getProgress() + 12f);
                        settingsStore.setReaderFontFamily(READER_FONT_FAMILY_KEYS[fontFamilySpinner.getSelectedItemPosition()]);
                        settingsStore.setReaderFontWeight(fontWeightValueForProgress(fontWeightSeek.getProgress()));
                        settingsStore.setReaderTextColor(READER_TEXT_COLOR_KEYS[textColorSpinner.getSelectedItemPosition()]);
                        settingsStore.setLineSpacingExtraSp(lineSeek.getProgress());
                        settingsStore.setLeftPaddingDp(leftSeek.getProgress());
                        settingsStore.setRightPaddingDp(rightSeek.getProgress());
                        settingsStore.setTopPaddingDp(topSeek.getProgress());
                        settingsStore.setBottomPaddingDp(bottomSeek.getProgress());
                        settingsStore.setLetterSpacing(letterSpacingSeek.getProgress() / 10f);
                        settingsStore.setFirstLineIndentDp(firstLineIndentSeek.getProgress());
                        settingsStore.setBackgroundBlurPercent(backgroundBlurSeek.getProgress());
                        settingsStore.setKeepScreenOn(keepScreenOn.isChecked());
                        settingsStore.setChapterTitleVisible(showTitleCheck.isChecked());
                        settingsStore.setReaderUiThemeMode(UI_THEME_KEYS[uiThemeSpinner.getSelectedItemPosition()]);
                        settingsStore.setReaderTheme(selectedReaderTheme[0]);
                        refreshTextColorPreview.run();
                        String nextResolvedUiMode = ThemeModeHelper.getResolvedReaderThemeMode(this);
                        clearPageCache();
                        if (!previousResolvedUiMode.equals(nextResolvedUiMode)) {
                            recreate();
                            return;
                        }
                        applyReaderSettings();
                        openChapter(currentChapterIndex, anchorOffset, false, 0);
                    };
                    applyButton.setOnClickListener(v -> autoApply.run());
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

    private void showCustomColorPickerDialog(Runnable onApply) {
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_color_picker, null, false);
        SeekBar redSeek = content.findViewById(R.id.color_seek_red);
        SeekBar greenSeek = content.findViewById(R.id.color_seek_green);
        SeekBar blueSeek = content.findViewById(R.id.color_seek_blue);
        TextView redText = content.findViewById(R.id.color_text_red);
        TextView greenText = content.findViewById(R.id.color_text_green);
        TextView blueText = content.findViewById(R.id.color_text_blue);
        View colorPreview = content.findViewById(R.id.color_preview);
        Button applyButton = content.findViewById(R.id.color_button_apply);

        // Parse current custom color or use default
        String customColor = settingsStore.getCustomTextColor();
        int currentColor = 0xFF374151; // Default graphite
        if (customColor != null && !customColor.isEmpty()) {
            try {
                currentColor = android.graphics.Color.parseColor(customColor);
            } catch (Exception e) {
                // Use default
            }
        }

        redSeek.setProgress(android.graphics.Color.red(currentColor));
        greenSeek.setProgress(android.graphics.Color.green(currentColor));
        blueSeek.setProgress(android.graphics.Color.blue(currentColor));

        Runnable updatePreview = () -> {
            int r = redSeek.getProgress();
            int g = greenSeek.getProgress();
            int b = blueSeek.getProgress();
            int color = android.graphics.Color.rgb(r, g, b);
            redText.setText("R: " + r);
            greenText.setText("G: " + g);
            blueText.setText("B: " + b);
            colorPreview.setBackgroundColor(color);
        };
        updatePreview.run();

        SeekBar.OnSeekBarChangeListener colorListener = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updatePreview.run();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        };
        redSeek.setOnSeekBarChangeListener(colorListener);
        greenSeek.setOnSeekBarChangeListener(colorListener);
        blueSeek.setOnSeekBarChangeListener(colorListener);

        AlertDialog dialog = new AlertDialog.Builder(this).setView(content).create();
        applyButton.setOnClickListener(v -> {
            int r = redSeek.getProgress();
            int g = greenSeek.getProgress();
            int b = blueSeek.getProgress();
            String hexColor = String.format("#%02X%02X%02X", r, g, b);
            settingsStore.setCustomTextColor(hexColor);
            settingsStore.setReaderTextColor("custom");
            dialog.dismiss();
            if (onApply != null) {
                onApply.run();
            }
        });
        showStyledDialog(dialog);
    }

    private void clearPageCache() {
        cachedPageSlicesMap.clear();
    }

    private void clearAllReaderCaches() {
        processedChapterLruCache.evictAll();
        processedChapterLengthCache.clear();
        cachedPageSlicesMap.clear();
        totalProcessedBookLength = -1;
    }

    private String currentBackgroundLabel() {
        String path = settingsStore.getReaderBackgroundPath();
        if (path == null || path.isBlank()) {
            return "当前背景：使用" + ReaderThemePalette.from(settingsStore.getReaderTheme()).displayName + "内置壁纸";
        }
        return "当前背景：" + new File(path).getName();
    }

    private void updateStyleLabels(TextView fontValue, TextView fontWeightValue, TextView lineValue, TextView leftValue, TextView rightValue, TextView topValue, TextView bottomValue,
                                   SeekBar fontSeek, SeekBar fontWeightSeek, SeekBar lineSeek, SeekBar leftSeek, SeekBar rightSeek, SeekBar topSeek, SeekBar bottomSeek) {
        fontValue.setText((fontSeek.getProgress() + 12) + " sp");
        fontWeightValue.setText(readerFontWeightLabelForProgress(fontWeightSeek.getProgress()) + " (" + fontWeightValueForProgress(fontWeightSeek.getProgress()) + ")");
        lineValue.setText(lineSeek.getProgress() + " px");
        leftValue.setText(leftSeek.getProgress() + " dp");
        rightValue.setText(rightSeek.getProgress() + " dp");
        topValue.setText(topSeek.getProgress() + " dp");
        bottomValue.setText(bottomSeek.getProgress() + " dp");
    }

    private int fontWeightProgress(int weight) {
        for (int i = 0; i < READER_FONT_WEIGHT_VALUES.length; i++) {
            if (READER_FONT_WEIGHT_VALUES[i] == weight) {
                return i;
            }
        }
        return 1;
    }

    private int fontWeightValueForProgress(int progress) {
        int safeProgress = clamp(progress, 0, READER_FONT_WEIGHT_VALUES.length - 1);
        return READER_FONT_WEIGHT_VALUES[safeProgress];
    }

    private String readerFontWeightLabelForProgress(int progress) {
        int safeProgress = clamp(progress, 0, READER_FONT_WEIGHT_LABELS.length - 1);
        return READER_FONT_WEIGHT_LABELS[safeProgress];
    }

    private int resolveReaderTextColor(ReaderThemePalette palette) {
        return resolveReaderTextColorValue(settingsStore.getReaderTextColor(), palette);
    }

    private int resolveReaderTextColorValue(String colorKey, ReaderThemePalette palette) {
        if ("custom".equals(colorKey)) {
            String customColor = settingsStore.getCustomTextColor();
            if (customColor != null && !customColor.isEmpty()) {
                try {
                    return android.graphics.Color.parseColor(customColor);
                } catch (Exception e) {
                    // Fall through to default
                }
            }
            return palette.textColor;
        }
        if ("ink_brown".equals(colorKey)) {
            return 0xFF5A4330;
        }
        if ("graphite".equals(colorKey)) {
            return 0xFF374151;
        }
        if ("warm_gray".equals(colorKey)) {
            return 0xFF635B52;
        }
        if ("jade_ink".equals(colorKey)) {
            return 0xFF255B57;
        }
        if ("forest_ink".equals(colorKey)) {
            return 0xFF274235;
        }
        if ("moon_white".equals(colorKey)) {
            return 0xFFF5F7FA;
        }
        return palette.textColor;
    }

    private void updateTextColorPreview(TextView preview, String colorKey, ReaderThemePalette palette) {
        if (preview == null) {
            return;
        }
        int index = indexOf(READER_TEXT_COLOR_KEYS, colorKey, 0);
        preview.setText("字色预览：" + READER_TEXT_COLOR_LABELS[index]);
        preview.setTextColor(resolveReaderTextColorValue(colorKey, palette));
        preview.setBackgroundColor(palette.pageColor);
    }

    private void updateLetterSpacingLabel(TextView label, SeekBar seekBar) {
        float spacing = seekBar.getProgress() / 10f;
        label.setText(String.format(Locale.SIMPLIFIED_CHINESE, "%.1f", spacing));
    }

    private void updateFirstLineIndentLabel(TextView label, SeekBar seekBar) {
        label.setText(seekBar.getProgress() + " 字符");
    }

    private void updateBackgroundBlurLabel(TextView label, SeekBar seekBar) {
        label.setText(seekBar.getProgress() + "%");
    }

    private Typeface buildReaderTypeface(String familyKey, int weight) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return Typeface.create(familyKey, weight);
        }
        return Typeface.create(familyKey, weight >= 600 ? Typeface.BOLD : Typeface.NORMAL);
    }

    private void styleReaderMenuButton(Button button, boolean active) {
        button.setBackgroundResource(active ? R.drawable.bg_reader_menu_button_active : R.drawable.bg_reader_menu_button_solid);
        button.setTag(R.id.tag_glass_background, Boolean.FALSE);
        button.setTextColor(getColor(android.R.color.white));
    }

    private void applyGlassOpacity() {
        GlassUiHelper.applyToHierarchy(this, menuTopPanel, settingsStore.getGlassOpacityPercent());
        GlassUiHelper.applyToHierarchy(this, menuInfoPanel, settingsStore.getGlassOpacityPercent());
        GlassUiHelper.applyToHierarchy(this, menuBottomPanel, settingsStore.getGlassOpacityPercent());
    }

    private void updateReaderThemeButtons(Button paper, Button forest, Button night, String current) {
        styleThemeButton(paper, "paper".equals(current));
        styleThemeButton(forest, "forest".equals(current));
        styleThemeButton(night, "night".equals(current));
    }

    private void styleThemeButton(Button button, boolean active) {
        button.setBackgroundResource(active ? R.drawable.bg_reader_menu_button_active : R.drawable.bg_reader_menu_button_solid);
        button.setTextColor(active ? Color.WHITE : Color.parseColor("#94A3B8"));
        button.setTag(R.id.tag_glass_background, !active);
    }

    private int indexOf(String[] values, String target, int fallback) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(target)) {
                return i;
            }
        }
        return fallback;
    }

    private void preparePagingSnapshots(int targetChapterIndex, int targetPageIndex) {
        if (pageSnapshotCurrent == null || pageSnapshotIncoming == null) {
            pagingSnapshotsVisible = false;
            return;
        }
        clearSimulationPagingLayer();
        if (!hasPreparedCurrentSnapshot(currentChapterIndex, currentPageIndex)) {
            // Ensure the view is bound and ready for snapshot
            bindPage(pageTitleCurrent, pageBodyCurrent, currentChapterIndex, currentPageIndex);
            layoutPageLayerForSnapshot(pageCurrent);
            currentPageSnapshotBitmap = screenshotPageLayer(pageCurrent, currentPageSnapshotBitmap);
            if (currentPageSnapshotBitmap != null) {
                preparedCurrentSnapshotChapterIndex = currentChapterIndex;
                preparedCurrentSnapshotPageIndex = currentPageIndex;
            }
        }
        if (!hasPreparedIncomingSnapshot(targetChapterIndex, targetPageIndex)) {
            Bitmap preparedBitmap = capturePreparedIncomingSnapshot(targetChapterIndex, targetPageIndex);
            if (preparedBitmap != null) {
                incomingPageSnapshotBitmap = preparedBitmap;
                preparedIncomingSnapshotChapterIndex = targetChapterIndex;
                preparedIncomingSnapshotPageIndex = targetPageIndex;
            }
        }
        if (currentPageSnapshotBitmap == null || incomingPageSnapshotBitmap == null) {
            restoreLivePageLayers(true);
            return;
        }
        pageSnapshotCurrent.setImageBitmap(currentPageSnapshotBitmap);
        pageSnapshotIncoming.setImageBitmap(incomingPageSnapshotBitmap);
        pageSnapshotCurrent.setVisibility(View.VISIBLE);
        pageSnapshotIncoming.setVisibility(View.VISIBLE);
        pageCurrent.setVisibility(View.INVISIBLE);
        pageIncoming.setVisibility(View.INVISIBLE);
        pagingSnapshotsVisible = true;
    }

    private Bitmap screenshotPageLayer(View source, Bitmap reuse) {
        int width = source.getWidth();
        int height = source.getHeight();
        if (width <= 0 || height <= 0) {
            return null;
        }
        Bitmap targetBitmap = reuse;
        if (targetBitmap == null || targetBitmap.getWidth() != width || targetBitmap.getHeight() != height) {
            if (targetBitmap != null && !targetBitmap.isRecycled()) {
                targetBitmap.recycle();
            }
            targetBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        } else {
            targetBitmap.eraseColor(Color.TRANSPARENT);
        }
        pagingSnapshotCanvas.setBitmap(targetBitmap);
        drawSnapshotBaseLayer(pagingSnapshotCanvas, source);
        pagingSnapshotCanvas.save();
        pagingSnapshotCanvas.translate(-source.getScrollX(), -source.getScrollY());
        source.draw(pagingSnapshotCanvas);
        pagingSnapshotCanvas.restore();
        pagingSnapshotCanvas.setBitmap(null);
        targetBitmap.prepareToDraw();
        return targetBitmap;
    }

    private void schedulePagingSnapshotWarmup() {
        if (pageStage == null) {
            return;
        }
        pageStage.removeCallbacks(pagingSnapshotWarmupRunnable);
        if (chapters.isEmpty() || isAnimating || interactivePaging) {
            return;
        }
        pageStage.post(pagingSnapshotWarmupRunnable);
    }

    private void warmPreparedPagingSnapshots() {
        if (pageStage == null || chapters.isEmpty() || isAnimating || interactivePaging) {
            return;
        }
        if (!ensurePageAreaReady(this::schedulePagingSnapshotWarmup)) {
            return;
        }
        if (!hasPreparedCurrentSnapshot(currentChapterIndex, currentPageIndex)) {
            currentPageSnapshotBitmap = screenshotPageLayer(pageCurrent, currentPageSnapshotBitmap);
            if (currentPageSnapshotBitmap != null) {
                preparedCurrentSnapshotChapterIndex = currentChapterIndex;
                preparedCurrentSnapshotPageIndex = currentPageIndex;
            }
        }
        PageTarget target = resolveInteractiveTarget(1);
        if (target == null) {
            target = resolveInteractiveTarget(-1);
        }
        if (target == null || hasPreparedIncomingSnapshot(target.chapterIndex, target.pageIndex)) {
            return;
        }
        Bitmap preparedBitmap = capturePreparedIncomingSnapshot(target.chapterIndex, target.pageIndex);
        if (preparedBitmap == null) {
            preparedIncomingSnapshotChapterIndex = -1;
            preparedIncomingSnapshotPageIndex = -1;
            return;
        }
        incomingPageSnapshotBitmap = preparedBitmap;
        preparedIncomingSnapshotChapterIndex = target.chapterIndex;
        preparedIncomingSnapshotPageIndex = target.pageIndex;
    }

    private Bitmap capturePreparedIncomingSnapshot(int chapterIndex, int pageIndex) {
        if (pageIncoming == null) {
            return null;
        }
        int previousVisibility = pageIncoming.getVisibility();
        float previousAlpha = pageIncoming.getAlpha();
        bindPage(pageTitleIncoming, pageBodyIncoming, chapterIndex, pageIndex);
        resetAnimatedPage(pageIncoming);
        if (pageCurrent != null) {
            pageCurrent.bringToFront();
        }
        pageIncoming.setVisibility(View.VISIBLE);
        if (!layoutPageLayerForSnapshot(pageIncoming)) {
            pageIncoming.setAlpha(previousAlpha);
            pageIncoming.setVisibility(previousVisibility);
            return null;
        }
        Bitmap bitmap = screenshotPageLayer(pageIncoming, incomingPageSnapshotBitmap);
        pageIncoming.setAlpha(previousAlpha);
        pageIncoming.setVisibility(previousVisibility);
        resetAnimatedPage(pageIncoming);
        if (pageCurrent != null) {
            pageCurrent.bringToFront();
        }
        return bitmap;
    }

    private boolean layoutPageLayerForSnapshot(View source) {
        int width = snapshotDimensionFor(source, true);
        int height = snapshotDimensionFor(source, false);
        if (width <= 0 || height <= 0) {
            return false;
        }
        int widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY);
        int heightSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY);
        source.measure(widthSpec, heightSpec);
        source.layout(0, 0, width, height);
        return true;
    }

    private int snapshotDimensionFor(View source, boolean width) {
        if (source == null) {
            return 0;
        }
        int currentValue = width ? source.getWidth() : source.getHeight();
        if (currentValue > 0) {
            return currentValue;
        }
        if (pageCurrent != null) {
            int currentPageValue = width ? pageCurrent.getWidth() : pageCurrent.getHeight();
            if (currentPageValue > 0) {
                return currentPageValue;
            }
        }
        if (pageStage == null) {
            return 0;
        }
        return width
                ? Math.max(0, pageStage.getWidth() - pageStage.getPaddingLeft() - pageStage.getPaddingRight())
                : Math.max(0, pageStage.getHeight() - pageStage.getPaddingTop() - pageStage.getPaddingBottom());
    }

    private boolean hasPreparedCurrentSnapshot(int chapterIndex, int pageIndex) {
        return hasPreparedSnapshot(
                currentPageSnapshotBitmap,
                pageCurrent,
                preparedCurrentSnapshotChapterIndex,
                preparedCurrentSnapshotPageIndex,
                chapterIndex,
                pageIndex
        );
    }

    private boolean hasPreparedIncomingSnapshot(int chapterIndex, int pageIndex) {
        return hasPreparedSnapshot(
                incomingPageSnapshotBitmap,
                pageIncoming,
                preparedIncomingSnapshotChapterIndex,
                preparedIncomingSnapshotPageIndex,
                chapterIndex,
                pageIndex
        );
    }

    private boolean hasPreparedSnapshot(Bitmap bitmap, View source, int preparedChapterIndex, int preparedPageIndex, int chapterIndex, int pageIndex) {
        if (bitmap == null
                || bitmap.isRecycled()
                || source == null
                || preparedChapterIndex != chapterIndex
                || preparedPageIndex != pageIndex) {
            return false;
        }
        int expectedWidth = snapshotDimensionFor(source, true);
        int expectedHeight = snapshotDimensionFor(source, false);
        return expectedWidth > 0
                && expectedHeight > 0
                && bitmap.getWidth() == expectedWidth
                && bitmap.getHeight() == expectedHeight;
    }

    private void invalidatePreparedPagingSnapshots() {
        preparedCurrentSnapshotChapterIndex = -1;
        preparedCurrentSnapshotPageIndex = -1;
        preparedIncomingSnapshotChapterIndex = -1;
        preparedIncomingSnapshotPageIndex = -1;
        if (pageStage != null) {
            pageStage.removeCallbacks(pagingSnapshotWarmupRunnable);
        }
    }

    private void promoteIncomingSnapshotToCurrent(int chapterIndex, int pageIndex) {
        if (!hasPreparedIncomingSnapshot(chapterIndex, pageIndex)) {
            preparedCurrentSnapshotChapterIndex = -1;
            preparedCurrentSnapshotPageIndex = -1;
            return;
        }
        Bitmap previousCurrentBitmap = currentPageSnapshotBitmap;
        currentPageSnapshotBitmap = incomingPageSnapshotBitmap;
        incomingPageSnapshotBitmap = previousCurrentBitmap;
        preparedCurrentSnapshotChapterIndex = chapterIndex;
        preparedCurrentSnapshotPageIndex = pageIndex;
        preparedIncomingSnapshotChapterIndex = -1;
        preparedIncomingSnapshotPageIndex = -1;
    }

    private void drawSnapshotBaseLayer(Canvas canvas, View source) {
        canvas.drawColor(currentReaderPageColor);
        if (readerBackgroundImage == null
                || readerBackgroundImage.getVisibility() != View.VISIBLE
                || readerBackgroundImage.getDrawable() == null) {
            return;
        }
        int[] sourceLocation = new int[2];
        int[] backgroundLocation = new int[2];
        source.getLocationOnScreen(sourceLocation);
        readerBackgroundImage.getLocationOnScreen(backgroundLocation);
        canvas.save();
        canvas.translate(
                backgroundLocation[0] - sourceLocation[0],
                backgroundLocation[1] - sourceLocation[1]
        );
        readerBackgroundImage.draw(canvas);
        canvas.restore();
    }

    private void restoreLivePageLayers(boolean incomingVisible) {
        pagingSnapshotsVisible = false;
        clearSimulationPagingLayer();
        if (pageSnapshotCurrent != null) {
            resetAnimatedPage(pageSnapshotCurrent);
            pageSnapshotCurrent.setVisibility(View.GONE);
        }
        if (pageSnapshotIncoming != null) {
            resetAnimatedPage(pageSnapshotIncoming);
            pageSnapshotIncoming.setVisibility(View.GONE);
        }
        pageCurrent.setVisibility(View.VISIBLE);
        pageIncoming.setVisibility(incomingVisible ? View.VISIBLE : View.GONE);
    }

    private View activeCurrentPageLayer() {
        if (pagingSnapshotsVisible && pageSnapshotCurrent != null) {
            return pageSnapshotCurrent;
        }
        return pageCurrent;
    }

    private View activeIncomingPageLayer() {
        if (pagingSnapshotsVisible && pageSnapshotIncoming != null) {
            return pageSnapshotIncoming;
        }
        return pageIncoming;
    }

    private void recyclePagingSnapshots() {
        invalidatePreparedPagingSnapshots();
        if (currentPageSnapshotBitmap != null && !currentPageSnapshotBitmap.isRecycled()) {
            currentPageSnapshotBitmap.recycle();
        }
        if (incomingPageSnapshotBitmap != null && !incomingPageSnapshotBitmap.isRecycled()) {
            incomingPageSnapshotBitmap.recycle();
        }
        currentPageSnapshotBitmap = null;
        incomingPageSnapshotBitmap = null;
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

    private void arrangePagingLayers(String mode) {
        if ("simulation".equals(mode)) {
            if (simulationPageTurnView != null) {
                simulationPageTurnView.bringToFront();
            }
            return;
        }
        View currentLayer = activeCurrentPageLayer();
        View incomingLayer = activeIncomingPageLayer();
        if ("scroll".equals(mode)) {
            currentLayer.bringToFront();
            incomingLayer.bringToFront();
        } else {
            incomingLayer.bringToFront();
            currentLayer.bringToFront();
        }
        if (pageShadow != null) {
            pageShadow.bringToFront();
        }
        if (pageFoldShadow != null) {
            pageFoldShadow.bringToFront();
        }
        if (pageFoldHighlight != null) {
            pageFoldHighlight.bringToFront();
        }
    }

    private void applyPagingVisuals(String mode, int direction, float progress, float touchY) {
        float width = Math.max(pageStage.getWidth(), dp(240));
        float height = Math.max(pageStage.getHeight(), dp(320));
        float safeProgress = Math.max(0f, Math.min(1f, progress));
        float safeTouchY = Math.max(0f, Math.min(height, touchY));
        float touchRatio = safeTouchY / height;
        float turnProgress = 1f - (float) Math.pow(1f - safeProgress, 1.18f);
        float diagonalBias = touchRatio - 0.5f;
        int widthPx = Math.max(1, Math.round(width));
        int heightPx = Math.max(1, Math.round(height));
        View currentLayer = activeCurrentPageLayer();
        View incomingLayer = activeIncomingPageLayer();
        resetAnimatedPage(currentLayer);
        resetAnimatedPage(incomingLayer);
        incomingLayer.setVisibility(View.VISIBLE);
        if (!"simulation".equals(mode)) {
            clearSimulationPagingLayer();
            hideInteractiveFoldEffects();
        }

        if ("simulation".equals(mode)) {
            resetShadowView();
            if (pageSnapshotCurrent != null) {
                pageSnapshotCurrent.setVisibility(View.GONE);
            }
            if (pageSnapshotIncoming != null) {
                pageSnapshotIncoming.setVisibility(View.GONE);
            }
            if (simulationPageTurnView != null
                    && currentPageSnapshotBitmap != null
                    && incomingPageSnapshotBitmap != null) {
                simulationPageTurnView.setPagingState(
                        direction,
                        currentPageSnapshotBitmap,
                        incomingPageSnapshotBitmap,
                        interactiveStartX,
                        interactiveStartY,
                        interactiveTouchX,
                        interactiveTouchY,
                        currentReaderPageColor
                );
            }
            return;
        }

        if ("cover".equals(mode)) {
            float revealWidth = width * safeProgress;
            currentLayer.setTranslationX((direction > 0 ? -1f : 1f) * width * safeProgress);
            applyRevealedIncomingClip(incomingLayer, direction, revealWidth, widthPx, heightPx);
            incomingLayer.setAlpha(1f);
            float edgeX = direction > 0 ? width + currentLayer.getTranslationX() : currentLayer.getTranslationX();
            updateInteractiveShadow(edgeX, direction, 0.18f + 0.24f * safeProgress);
            return;
        }

        if ("scroll".equals(mode)) {
            float offsetY = (direction > 0 ? 1f : -1f) * height * safeProgress;
            currentLayer.setTranslationY(-offsetY);
            incomingLayer.setTranslationY((direction > 0 ? 1f : -1f) * height * (1f - safeProgress));
            incomingLayer.setAlpha(0.94f + 0.06f * safeProgress);
            updateInteractiveShadow(width * 0.5f, direction, 0f);
            return;
        }

        float revealWidth = width * safeProgress;
        currentLayer.setTranslationX((direction > 0 ? -1f : 1f) * revealWidth);
        incomingLayer.setTranslationX(direction > 0 ? width - revealWidth : -width + revealWidth);
        incomingLayer.setAlpha(0.95f + 0.05f * safeProgress);
        if (direction > 0) {
            applyPageClip(incomingLayer, 0, Math.round(revealWidth), heightPx);
            updateInteractiveShadow(width - revealWidth, direction, "none".equals(mode) ? 0f : 0.14f + 0.16f * safeProgress);
        } else {
            applyPageClip(incomingLayer, widthPx - Math.round(revealWidth), widthPx, heightPx);
            updateInteractiveShadow(revealWidth, direction, "none".equals(mode) ? 0f : 0.14f + 0.16f * safeProgress);
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

    private void applyRevealedIncomingClip(View view, int direction, float revealWidth, int width, int height) {
        int revealPx = Math.max(0, Math.round(revealWidth));
        int seamBleedPx = revealPx > 0 ? dp(2) : 0;
        if (direction > 0) {
            applyPageClip(view, width - revealPx - seamBleedPx, width, height);
            return;
        }
        applyPageClip(view, 0, revealPx + seamBleedPx, height);
    }

    private void clearSimulationPagingLayer() {
        if (simulationPageTurnView != null) {
            simulationPageTurnView.clear();
        }
    }

    private void captureInteractiveStartPoint(float startX, float startY) {
        interactiveStartX = sanitizeStageTouchX(startX);
        interactiveStartY = sanitizeStageTouchY(startY);
        interactiveTouchX = interactiveStartX;
        interactiveTouchY = interactiveStartY;
    }

    private void updateInteractiveTouchPoint(float touchX, float touchY) {
        interactiveTouchX = sanitizeStageTouchX(touchX);
        interactiveTouchY = sanitizeStageTouchY(touchY);
    }

    private void initializeSimulationAutoStart(int direction, float width, float height) {
        // Start closer to the actual edge for a smoother "sweep" effect
        float startX = direction > 0 ? width - 5f : 5f;
        float startY;
        
        float tapY = lastTapY >= 0 ? lastTapY : height / 2f;
        
        if (tapY < height / 3f) {
            // Top zone: Start from top corner
            startY = 5f;
        } else if (tapY > height * 2f / 3f) {
            // Bottom zone: Start from bottom corner
            startY = height - 5f;
        } else {
            // Middle zone: Start from horizontal center
            startY = height / 2f;
        }
        
        captureInteractiveStartPoint(startX, startY);
        lastTapY = -1f; // Consume tap Y
    }

    private float sanitizeStageTouchX(float value) {
        float width = Math.max(pageStage == null ? 0f : pageStage.getWidth(), 1f);
        if (settingsStore != null && "simulation".equals(settingsStore.getFlipMode())) {
            // Simulation needs wider range to allow the fold to cross the entire screen
            return Math.max(-width * 3f, Math.min(width * 4f, value));
        }
        return Math.max(0.1f, Math.min(width - 0.1f, value));
    }

    private float sanitizeStageTouchY(float value) {
        float height = Math.max(pageStage == null ? 0f : pageStage.getHeight(), 1f);
        if (settingsStore != null && "simulation".equals(settingsStore.getFlipMode())) {
            return Math.max(-height * 3f, Math.min(height * 4f, value));
        }
        return Math.max(0.1f, Math.min(height - 0.1f, value));
    }

    private float resolveSimulationTargetTouchX(int direction, boolean commit) {
        float width = Math.max(pageStage == null ? 0f : pageStage.getWidth(), dp(240));
        if (commit) {
            // Make target point far enough to ensure fold line and shadow clear the screen entirely
            return direction > 0 ? -width * 2.5f : width * 3.5f;
        }
        return direction > 0 ? width * 1.5f : -width * 0.5f;
    }

    private float resolveSimulationTargetTouchY(int direction) {
        float height = Math.max(pageStage == null ? 0f : pageStage.getHeight(), dp(320));
        
        // Push Y further out to maintain the diagonal pull vector
        if (interactiveStartY < height / 3f) {
            return height * 1.5f;
        } else if (interactiveStartY > height * 2f / 3f) {
            return -height * 0.5f;
        } else {
            return height / 2f + (direction > 0 ? 10f : -10f);
        }
    }

    private void resetInteractiveTouchState() {
        interactiveStartX = 0f;
        interactiveStartY = 0f;
        interactiveTouchX = 0f;
        interactiveTouchY = 0f;
    }

    private float lerp(float start, float end, float fraction) {
        return start + (end - start) * fraction;
    }

    private void resetShadowView() {
        resetOverlayView(pageShadow);
        resetOverlayView(pageFoldShadow);
        resetOverlayView(pageFoldHighlight);
    }

    private void resetOverlayView(View view) {
        if (view == null) {
            return;
        }
        view.animate().cancel();
        view.setAlpha(0f);
        view.setVisibility(View.GONE);
        view.setTranslationX(0f);
        view.setRotation(0f);
        view.setScaleX(1f);
        view.setScaleY(1f);
    }

    private void setControlsVisible(boolean visible) {
        if (visible) {
            pendingTapPagingDelta = 0;
        }
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

    private void updateTtsHighlight() {
        if (ttsHighlightStart >= 0 && ttsHighlightEnd > ttsHighlightStart) {
            pageBodyCurrent.setHighlightRange(ttsHighlightStart, ttsHighlightEnd);
        } else {
            pageBodyCurrent.clearHighlight();
        }
        pageBodyCurrent.invalidate();
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
    private void recordSessionStats() {
        if (sessionStartTime <= 0) {
            return;
        }
        long durationMs = System.currentTimeMillis() - sessionStartTime;
        if (durationMs < 2000) {
            return;
        }
        int seconds = (int) (durationMs / 1000);
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
        executor.execute(() -> databaseHelper.recordReadingStats(today, seconds, 0));
        sessionStartTime = System.currentTimeMillis();
    }
}
