package com.metahumanz.pacilread;
/*

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.Html;
import android.text.Layout;
import android.text.Spanned;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.metahumanz.pacilread.model.BookRecord;
import com.metahumanz.pacilread.model.ChapterRecord;
import com.metahumanz.pacilread.storage.ReaderDatabaseHelper;
import com.metahumanz.pacilread.storage.SettingsStore;
import com.metahumanz.pacilread.sync.WebDavClient;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ReaderActivity extends Activity implements TextToSpeech.OnInitListener {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<String> ttsQueue = new ArrayDeque<>();

    private ReaderDatabaseHelper databaseHelper;
    private SettingsStore settingsStore;
    private WebDavClient webDavClient;

    private View readerRoot;
    private View topBar;
    private View bottomPanel;
    private ScrollView readerScroll;
    private TextView readerText;
    private TextView readerTitle;
    private TextView readerProgress;
    private TextView chapterMeta;
    private Button ttsButton;
    private Button autoPageButton;

    private long bookId;
    private BookRecord book;
    private final List<ChapterRecord> chapters = new ArrayList<>();
    private int currentChapterIndex = 0;

    private GestureDetector gestureDetector;
    private TextToSpeech textToSpeech;
    private boolean ttsReady = false;
    private boolean ttsActive = false;
    private boolean autoPageActive = false;
    private boolean controlsVisible = true;

    private final Runnable autoHideRunnable = () -> setControlsVisible(false);
    private final Runnable autoPageRunnable = new Runnable() {
        @Override
        public void run() {
            if (!autoPageActive) {
                return;
            }
            pageDown();
            mainHandler.postDelayed(this, settingsStore.getAutoPageSeconds() * 1000L);
        }
    };
    private final Runnable saveProgressRunnable = this::persistProgress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reader);

        databaseHelper = ReaderDatabaseHelper.getInstance(this);
        settingsStore = new SettingsStore(this);
        webDavClient = new WebDavClient(settingsStore);
        bookId = getIntent().getLongExtra("book_id", -1L);

        readerRoot = findViewById(R.id.reader_root);
        topBar = findViewById(R.id.top_bar);
        bottomPanel = findViewById(R.id.bottom_panel);
        readerScroll = findViewById(R.id.reader_scroll);
        readerText = findViewById(R.id.reader_text);
        readerTitle = findViewById(R.id.text_reader_title);
        readerProgress = findViewById(R.id.text_progress);
        chapterMeta = findViewById(R.id.text_chapter_meta);
        ttsButton = findViewById(R.id.button_tts);
        autoPageButton = findViewById(R.id.button_auto_page);

        readerText.setText("正在载入...");

        findViewById(R.id.button_back).setOnClickListener(v -> finish());
        findViewById(R.id.button_prev_chapter).setOnClickListener(v -> openChapter(currentChapterIndex - 1, Integer.MAX_VALUE));
        findViewById(R.id.button_next_chapter).setOnClickListener(v -> openChapter(currentChapterIndex + 1, 0));
        findViewById(R.id.button_toc).setOnClickListener(v -> showTocDialog());
        findViewById(R.id.button_search).setOnClickListener(v -> showSearchDialog());
        findViewById(R.id.button_style).setOnClickListener(v -> showStyleDialog());
        ttsButton.setOnClickListener(v -> toggleTts());
        autoPageButton.setOnClickListener(v -> toggleAutoPage());
        findViewById(R.id.button_sync).setOnClickListener(v -> syncFromWebDav(false));

        readerScroll.getViewTreeObserver().addOnScrollChangedListener(() -> {
            updateProgressBadge();
            scheduleProgressSave();
        });

        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (controlsVisible && (isInsideView(e, topBar) || isInsideView(e, bottomPanel))) {
                    return false;
                }
                float width = readerRoot.getWidth();
                float third = width / 3f;
                float x = e.getX();
                if (x < third) {
                    pageUp();
                } else if (x > third * 2f) {
                    pageDown();
                } else {
                    setControlsVisible(!controlsVisible);
                }
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (Math.abs(velocityX) > Math.abs(velocityY) * 1.4f && Math.abs(velocityX) > 800f) {
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

        textToSpeech = new TextToSpeech(this, this);
        loadBook();
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
                    mainHandler.post(ReaderActivity.this::speakNextChunk);
                }

                @Override
                public void onError(String utteranceId) {
                    mainHandler.post(ReaderActivity.this::stopTts);
                }
            });
        }
    }

    private void loadBook() {
        executor.execute(() -> {
            BookRecord loadedBook = databaseHelper.getBook(bookId);
            List<ChapterRecord> loadedChapters = databaseHelper.getChapters(bookId);
            runOnUiThread(() -> {
                if (loadedBook == null || loadedChapters.isEmpty()) {
                    showToast("书籍不存在或内容为空");
                    finish();
                    return;
                }
                book = loadedBook;
                chapters.clear();
                chapters.addAll(loadedChapters);
                currentChapterIndex = clamp(loadedBook.progressIndex, 0, chapters.size() - 1);
                applyReaderSettings();
                openChapter(currentChapterIndex, loadedBook.progressOffset);
                syncFromWebDav(true);
            });
        });
    }

    private void openChapter(int index, int charOffset) {
        if (chapters.isEmpty()) {
            return;
        }
        currentChapterIndex = clamp(index, 0, chapters.size() - 1);
        ChapterRecord chapter = chapters.get(currentChapterIndex);

        String html = "<h2>" + TextUtils.htmlEncode(chapter.title) + "</h2>" + chapter.bodyHtml;
        Spanned spanned = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                ? Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY)
                : Html.fromHtml(html);
        readerText.setText(spanned);
        chapterMeta.setText(String.format(Locale.SIMPLIFIED_CHINESE, "第 %d/%d 章 · %s", currentChapterIndex + 1, chapters.size(), chapter.title));
        readerTitle.setText(book.title);
        updateProgressBadge();

        readerText.post(() -> {
            if (charOffset == Integer.MAX_VALUE) {
                readerScroll.scrollTo(0, Math.max(0, readerText.getHeight() - readerScroll.getHeight()));
            } else {
                scrollToCharOffset(charOffset);
            }
            updateProgressBadge();
            scheduleProgressSave();
        });
        scheduleAutoHide();
    }

    private void pageDown() {
        int viewport = Math.max(readerScroll.getHeight() - dp(72), dp(180));
        int maxScroll = Math.max(readerText.getHeight() - readerScroll.getHeight(), 0);
        int current = readerScroll.getScrollY();
        if (current >= maxScroll - dp(20)) {
            if (currentChapterIndex < chapters.size() - 1) {
                openChapter(currentChapterIndex + 1, 0);
            }
            return;
        }
        readerScroll.smoothScrollTo(0, Math.min(current + viewport, maxScroll));
        scheduleAutoHide();
    }

    private void pageUp() {
        int viewport = Math.max(readerScroll.getHeight() - dp(72), dp(180));
        int current = readerScroll.getScrollY();
        if (current <= dp(12)) {
            if (currentChapterIndex > 0) {
                openChapter(currentChapterIndex - 1, Integer.MAX_VALUE);
            }
            return;
        }
        readerScroll.smoothScrollTo(0, Math.max(0, current - viewport));
        scheduleAutoHide();
    }

    private void showTocDialog() {
        if (chapters.isEmpty()) {
            return;
        }
        String[] items = new String[chapters.size()];
        for (int i = 0; i < chapters.size(); i++) {
            items[i] = chapters.get(i).title;
        }
        new AlertDialog.Builder(this)
                .setTitle("目录")
                .setSingleChoiceItems(items, currentChapterIndex, (dialog, which) -> {
                    dialog.dismiss();
                    openChapter(which, 0);
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void showSearchDialog() {
        Dialog dialog = new Dialog(this);
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_search, null, false);
        dialog.setContentView(content);

        EditText queryInput = content.findViewById(R.id.search_query_input);
        Button searchButton = content.findViewById(R.id.search_button_go);
        TextView resultCount = content.findViewById(R.id.search_result_count);
        android.widget.ListView listView = content.findViewById(R.id.search_result_list);

        List<SearchResult> resultModels = new ArrayList<>();
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            SearchResult result = resultModels.get(position);
            dialog.dismiss();
            openChapter(result.chapterIndex, result.charOffset);
        });

        searchButton.setOnClickListener(v -> {
            String query = queryInput.getText().toString().trim();
            if (query.isEmpty()) {
                resultCount.setText("请输入关键词");
                return;
            }
            executor.execute(() -> {
                List<SearchResult> matches = searchAll(query);
                runOnUiThread(() -> {
                    resultModels.clear();
                    resultModels.addAll(matches);
                    adapter.clear();
                    for (SearchResult match : matches) {
                        adapter.add(match.chapterTitle + "\n" + match.snippet);
                    }
                    resultCount.setText(matches.isEmpty() ? "没有找到匹配内容" : "找到 " + matches.size() + " 条结果");
                });
            });
        });

        dialog.show();
    }

    private List<SearchResult> searchAll(String query) {
        List<SearchResult> results = new ArrayList<>();
        String lower = query.toLowerCase(Locale.ROOT);
        for (int i = 0; i < chapters.size(); i++) {
            ChapterRecord chapter = chapters.get(i);
            String text = chapter.bodyText == null ? "" : chapter.bodyText;
            String lowerBody = text.toLowerCase(Locale.ROOT);
            int first = lowerBody.indexOf(lower);
            if (first >= 0) {
                results.add(new SearchResult(i, chapter.title, excerpt(text, first, query.length()), first));
            }
        }
        return results;
    }

    private void showStyleDialog() {
        Dialog dialog = new Dialog(this);
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_reader_style, null, false);
        dialog.setContentView(content);

        SeekBar fontSeek = content.findViewById(R.id.style_seek_font);
        SeekBar lineSeek = content.findViewById(R.id.style_seek_line_spacing);
        SeekBar sideSeek = content.findViewById(R.id.style_seek_side_padding);
        SeekBar verticalSeek = content.findViewById(R.id.style_seek_vertical_padding);
        TextView fontValue = content.findViewById(R.id.style_text_font);
        TextView lineValue = content.findViewById(R.id.style_text_line_spacing);
        TextView sideValue = content.findViewById(R.id.style_text_side_padding);
        TextView verticalValue = content.findViewById(R.id.style_text_vertical_padding);
        Spinner themeSpinner = content.findViewById(R.id.style_spinner_theme);
        CheckBox keepScreenOn = content.findViewById(R.id.style_check_keep_screen_on);

        ArrayAdapter<String> themeAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new String[]{"纸感米白", "森林暮色", "夜航深蓝"});
        themeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        themeSpinner.setAdapter(themeAdapter);

        fontSeek.setProgress(Math.round(settingsStore.getFontSizeSp()) - 12);
        lineSeek.setProgress(Math.round(settingsStore.getLineSpacingExtraSp()));
        sideSeek.setProgress(settingsStore.getSidePaddingDp() - 8);
        verticalSeek.setProgress(settingsStore.getVerticalPaddingDp() - 8);
        keepScreenOn.setChecked(settingsStore.isKeepScreenOn());

        Map<String, Integer> themeSelection = new HashMap<>();
        themeSelection.put("paper", 0);
        themeSelection.put("forest", 1);
        themeSelection.put("night", 2);
        themeSpinner.setSelection(themeSelection.getOrDefault(settingsStore.getReaderTheme(), 0));

        updateStyleValueLabels(fontValue, lineValue, sideValue, verticalValue, fontSeek, lineSeek, sideSeek, verticalSeek);
        fontSeek.setOnSeekBarChangeListener(new SeekLabelListener(() -> updateStyleValueLabels(fontValue, lineValue, sideValue, verticalValue, fontSeek, lineSeek, sideSeek, verticalSeek)));
        lineSeek.setOnSeekBarChangeListener(new SeekLabelListener(() -> updateStyleValueLabels(fontValue, lineValue, sideValue, verticalValue, fontSeek, lineSeek, sideSeek, verticalSeek)));
        sideSeek.setOnSeekBarChangeListener(new SeekLabelListener(() -> updateStyleValueLabels(fontValue, lineValue, sideValue, verticalValue, fontSeek, lineSeek, sideSeek, verticalSeek)));
        verticalSeek.setOnSeekBarChangeListener(new SeekLabelListener(() -> updateStyleValueLabels(fontValue, lineValue, sideValue, verticalValue, fontSeek, lineSeek, sideSeek, verticalSeek)));

        content.findViewById(R.id.style_button_cancel).setOnClickListener(v -> dialog.dismiss());
        content.findViewById(R.id.style_button_apply).setOnClickListener(v -> {
            int currentOffset = currentCharOffset();
            settingsStore.setFontSizeSp(fontSeek.getProgress() + 12f);
            settingsStore.setLineSpacingExtraSp(lineSeek.getProgress());
            settingsStore.setSidePaddingDp(sideSeek.getProgress() + 8);
            settingsStore.setVerticalPaddingDp(verticalSeek.getProgress() + 8);
            settingsStore.setKeepScreenOn(keepScreenOn.isChecked());
            String[] themes = new String[]{"paper", "forest", "night"};
            settingsStore.setReaderTheme(themes[themeSpinner.getSelectedItemPosition()]);
            applyReaderSettings();
            openChapter(currentChapterIndex, currentOffset);
            dialog.dismiss();
        });

        dialog.show();
    }

    private void syncFromWebDav(boolean silent) {
        if (!settingsStore.isWebDavEnabled()) {
            if (!silent) {
                showToast("尚未启用 WebDAV 同步");
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
                boolean shouldApply = payload.chapterTime > book.lastReadAt + 5000
                        || (book.progressIndex == 0 && book.progressOffset == 0);
                if (!shouldApply) {
                    if (!silent) {
                        runOnUiThread(() -> showToast("本地进度已经较新"));
                    }
                    return;
                }
                int remoteChapter = clamp(payload.chapterIndex, 0, chapters.size() - 1);
                databaseHelper.updateProgress(book.id, remoteChapter, payload.chapterPosition);
                book.lastReadAt = payload.chapterTime;
                runOnUiThread(() -> {
                    openChapter(remoteChapter, payload.chapterPosition);
                    if (!silent) {
                        showToast("已恢复云端进度");
                    }
                });
            } catch (Exception error) {
                if (!silent) {
                    runOnUiThread(() -> showToast("同步失败: " + error.getMessage()));
                }
            }
        });
    }

    private void toggleTts() {
        if (ttsActive) {
            stopTts();
            return;
        }
        if (!ttsReady || textToSpeech == null) {
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
        speakNextChunk();
    }

    private void speakNextChunk() {
        if (!ttsActive || textToSpeech == null) {
            return;
        }
        if (ttsQueue.isEmpty()) {
            if (currentChapterIndex < chapters.size() - 1) {
                openChapter(currentChapterIndex + 1, 0);
                ttsQueue.addAll(splitForSpeech(chapters.get(currentChapterIndex).bodyText));
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
        textToSpeech.setSpeechRate(settingsStore.getTtsRate());
        textToSpeech.speak(chunk, TextToSpeech.QUEUE_FLUSH, null, "reader-chunk");
    }

    private void stopTts() {
        ttsActive = false;
        ttsQueue.clear();
        if (textToSpeech != null) {
            textToSpeech.stop();
        }
        ttsButton.setText(getString(R.string.reader_tts));
    }

    private void toggleAutoPage() {
        if (autoPageActive) {
            stopAutoPage();
        } else {
            autoPageActive = true;
            autoPageButton.setText("停止自动");
            mainHandler.postDelayed(autoPageRunnable, settingsStore.getAutoPageSeconds() * 1000L);
        }
    }

    private void stopAutoPage() {
        autoPageActive = false;
        mainHandler.removeCallbacks(autoPageRunnable);
        autoPageButton.setText(getString(R.string.reader_auto_page));
    }

    private void applyReaderSettings() {
        float fontSize = settingsStore.getFontSizeSp();
        float lineSpacing = settingsStore.getLineSpacingExtraSp();
        int sidePadding = dp(settingsStore.getSidePaddingDp());
        int verticalPadding = dp(settingsStore.getVerticalPaddingDp());

        readerText.setTextSize(fontSize);
        readerText.setLineSpacing(lineSpacing, 1f);
        readerText.setPadding(sidePadding, verticalPadding, sidePadding, dp(56));

        ThemePalette palette = ThemePalette.from(settingsStore.getReaderTheme());
        readerRoot.setBackgroundColor(palette.background);
        readerText.setTextColor(palette.foreground);

        if (settingsStore.isKeepScreenOn()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void persistProgress() {
        if (book == null || chapters.isEmpty()) {
            return;
        }
        int charOffset = currentCharOffset();
        ChapterRecord chapter = chapters.get(currentChapterIndex);
        executor.execute(() -> {
            databaseHelper.updateProgress(book.id, currentChapterIndex, charOffset);
            book.progressIndex = currentChapterIndex;
            book.progressOffset = charOffset;
            book.lastReadAt = System.currentTimeMillis();
            if (settingsStore.isWebDavEnabled()) {
                try {
                    webDavClient.ensureProgressDirectory();
                    webDavClient.uploadProgress(book, chapter, charOffset);
                } catch (Exception ignore) {
                }
            }
        });
    }

    private void scheduleProgressSave() {
        mainHandler.removeCallbacks(saveProgressRunnable);
        mainHandler.postDelayed(saveProgressRunnable, 1000L);
    }

    private void updateProgressBadge() {
        if (chapters.isEmpty()) {
            return;
        }
        int offset = currentCharOffset();
        int chapterLength = Math.max(chapters.get(currentChapterIndex).bodyText.length(), 1);
        float perBook = (currentChapterIndex + Math.min(offset / (float) chapterLength, 0.999f)) / (float) chapters.size();
        int percent = Math.round(perBook * 100f);
        readerProgress.setText(percent + "%");
    }

    private int currentCharOffset() {
        Layout layout = readerText.getLayout();
        if (layout == null) {
            return 0;
        }
        int line = layout.getLineForVertical(readerScroll.getScrollY());
        return clamp(layout.getLineStart(line), 0, readerText.getText().length());
    }

    private void scrollToCharOffset(int charOffset) {
        Layout layout = readerText.getLayout();
        if (layout == null) {
            return;
        }
        int safeOffset = clamp(charOffset, 0, readerText.getText().length());
        int line = layout.getLineForOffset(safeOffset);
        int top = Math.max(0, layout.getLineTop(line) - dp(12));
        readerScroll.scrollTo(0, top);
    }

    private List<String> splitForSpeech(String text) {
        List<String> parts = new ArrayList<>();
        if (text == null) {
            return parts;
        }
        String normalized = text.replace("\r", "").trim();
        if (normalized.isEmpty()) {
            return parts;
        }
        String[] segments = normalized.split("(?<=[。！？!?；;\\n])");
        StringBuilder builder = new StringBuilder();
        for (String segment : segments) {
            String trimmed = segment.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (builder.length() + trimmed.length() > 180 && builder.length() > 0) {
                parts.add(builder.toString());
                builder.setLength(0);
            }
            builder.append(trimmed).append(' ');
        }
        if (builder.length() > 0) {
            parts.add(builder.toString().trim());
        }
        return parts;
    }

    private String remainingTextFromCurrentPosition() {
        if (chapters.isEmpty()) {
            return "";
        }
        String body = chapters.get(currentChapterIndex).bodyText;
        int offset = clamp(currentCharOffset(), 0, body.length());
        return body.substring(offset);
    }

    private String excerpt(String text, int start, int keywordLength) {
        int excerptStart = Math.max(0, start - 18);
        int excerptEnd = Math.min(text.length(), start + keywordLength + 24);
        return text.substring(excerptStart, excerptEnd).replace('\n', ' ').trim();
    }

    private boolean isInsideView(MotionEvent event, View view) {
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        float rawX = event.getRawX();
        float rawY = event.getRawY();
        return rawX >= location[0]
                && rawX <= location[0] + view.getWidth()
                && rawY >= location[1]
                && rawY <= location[1] + view.getHeight();
    }

    private void setControlsVisible(boolean visible) {
        controlsVisible = visible;
        topBar.setVisibility(visible ? View.VISIBLE : View.GONE);
        bottomPanel.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (visible) {
            scheduleAutoHide();
        } else {
            mainHandler.removeCallbacks(autoHideRunnable);
        }
    }

    private void scheduleAutoHide() {
        if (!controlsVisible) {
            return;
        }
        mainHandler.removeCallbacks(autoHideRunnable);
        mainHandler.postDelayed(autoHideRunnable, 2800L);
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

    private void updateStyleValueLabels(TextView fontValue, TextView lineValue, TextView sideValue, TextView verticalValue,
                                        SeekBar fontSeek, SeekBar lineSeek, SeekBar sideSeek, SeekBar verticalSeek) {
        fontValue.setText((fontSeek.getProgress() + 12) + " sp");
        lineValue.setText(lineSeek.getProgress() + " px");
        sideValue.setText((sideSeek.getProgress() + 8) + " dp");
        verticalValue.setText((verticalSeek.getProgress() + 8) + " dp");
    }

    private static class SearchResult {
        final int chapterIndex;
        final String chapterTitle;
        final String snippet;
        final int charOffset;

        SearchResult(int chapterIndex, String chapterTitle, String snippet, int charOffset) {
            this.chapterIndex = chapterIndex;
            this.chapterTitle = chapterTitle;
            this.snippet = snippet;
            this.charOffset = charOffset;
        }
    }

    private static class SeekLabelListener implements SeekBar.OnSeekBarChangeListener {
        private final Runnable onChange;

        SeekLabelListener(Runnable onChange) {
            this.onChange = onChange;
        }

        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            onChange.run();
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    }

    private static class ThemePalette {
        final int background;
        final int foreground;

        ThemePalette(int background, int foreground) {
            this.background = background;
            this.foreground = foreground;
        }

        static ThemePalette from(String key) {
            if ("forest".equals(key)) {
                return new ThemePalette(0xFF1E322C, 0xFFE3EFE5);
            }
            if ("night".equals(key)) {
                return new ThemePalette(0xFF15202B, 0xFFE7EDF3);
            }
            return new ThemePalette(0xFFF6EEDF, 0xFF2B2115);
        }
    }
}
*/

public class ReaderActivity extends ModernReaderActivity {
}
