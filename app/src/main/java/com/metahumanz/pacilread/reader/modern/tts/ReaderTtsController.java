package com.metahumanz.pacilread.reader.modern.tts;

import android.app.AlertDialog;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import com.metahumanz.pacilread.R;
import com.metahumanz.pacilread.reader.PageSlice;
import com.metahumanz.pacilread.reader.modern.ModernReaderActivity;
import com.metahumanz.pacilread.reader.modern.ReaderRuntime;
import com.metahumanz.pacilread.reader.modern.ReaderSessionState;
import com.metahumanz.pacilread.reader.modern.ReaderUiUtils;
import com.metahumanz.pacilread.reader.modern.ReaderViewRefs;
import com.metahumanz.pacilread.reader.modern.content.ReaderContentController;
import com.metahumanz.pacilread.reader.modern.dialog.ReaderDialogSupport;
import com.metahumanz.pacilread.reader.modern.paging.ReaderNavigationController;
import com.metahumanz.pacilread.reader.modern.paging.ReaderPagingAnimator;
import com.metahumanz.pacilread.reader.modern.ui.ReaderChromeController;
import com.metahumanz.pacilread.tts.SystemTtsClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ReaderTtsController {
    private static final Pattern TTS_SEGMENT_PATTERN = Pattern.compile("[^ \\n\\t。！？.!?,，;；、]+[。！？.!?,，;；、]*");
    private static final String[] TTS_ENGINE_KEYS = new String[]{"system", "mimo"};
    private static final String[] TTS_ENGINE_LABELS = new String[]{"系统 TTS", "小米 MiMo"};
    private static final String[] TTS_MIMO_VOICE_KEYS = new String[]{"冰糖", "茉莉", "苏打", "白桦"};
    private static final String[] TTS_MIMO_VOICE_LABELS = new String[]{"冰糖（女声）", "茉莉（女声）", "苏打（男声）", "白桦（男声）"};
    private static final int MIMO_PRECACHE_AHEAD = 2;

    private final ModernReaderActivity activity;
    private final ReaderRuntime runtime;
    private final ReaderViewRefs views;
    private final ReaderSessionState state;
    private final ReaderUiUtils ui;
    private final ReaderDialogSupport dialogSupport;
    private final List<SpeechUnit> ttsUnits = new ArrayList<>();
    private final Map<Integer, byte[]> mimoPcmCache = new LinkedHashMap<>();
    private final List<Runnable> pendingHighlightTasks = new CopyOnWriteArrayList<>();
    private boolean batchQueued = false;

    private ReaderNavigationController navigation;
    private ReaderContentController content;
    private ReaderPagingAnimator paging;
    private ReaderChromeController chrome;

    public ReaderTtsController(
            ModernReaderActivity activity,
            ReaderRuntime runtime,
            ReaderViewRefs views,
            ReaderSessionState state,
            ReaderUiUtils ui,
            ReaderDialogSupport dialogSupport
    ) {
        this.activity = activity;
        this.runtime = runtime;
        this.views = views;
        this.state = state;
        this.ui = ui;
        this.dialogSupport = dialogSupport;
    }

    public void attachControllers(
            ReaderNavigationController navigation,
            ReaderContentController content,
            ReaderPagingAnimator paging,
            ReaderChromeController chrome
    ) {
        this.navigation = navigation;
        this.content = content;
        this.paging = paging;
        this.chrome = chrome;
    }

    public boolean isActive() {
        return state.ttsActive;
    }

    public void toggleTts() {
        if (state.ttsPaused) {
            resumeTts();
            return;
        }
        if (state.ttsActive) {
            pauseTts();
            return;
        }
        if ("mimo".equals(runtime.settingsStore.getTtsEngine()) && runtime.settingsStore.getTtsMimoApiKey().isBlank()) {
            ui.showToast("请先在设置页填写 MiMo API Key");
            return;
        }
        boolean hasCurrentUnits = rebuildTtsUnitsForChapter(state.currentChapterIndex, content.currentCharOffset());
        if (!hasCurrentUnits && state.currentChapterIndex >= state.chapters.size() - 1) {
            ui.showToast("当前位置没有可朗读的文本");
            return;
        }
        state.ttsActive = true;
        state.ttsSessionId++;
        chrome.styleReaderMenuButton(views.ttsButton, true);
        if (hasCurrentUnits) {
            playCurrentTtsUnit();
            return;
        }
        advanceToNextTtsChapter();
    }

    public void startTtsFrom(int chapterIndex, int charOffset) {
        if ("mimo".equals(runtime.settingsStore.getTtsEngine()) && runtime.settingsStore.getTtsMimoApiKey().isBlank()) {
            ui.showToast("请先在设置页填写 MiMo API Key");
            return;
        }
        stopTts();
        boolean hasUnits = rebuildTtsUnitsForChapter(chapterIndex, Math.max(charOffset, 0));
        if (!hasUnits) {
            ui.showToast("当前位置没有可朗读的文本");
            return;
        }
        state.ttsActive = true;
        state.ttsSessionId++;
        chrome.styleReaderMenuButton(views.ttsButton, true);
        playCurrentTtsUnit();
    }

    public void stopTts() {
        state.ttsActive = false;
        state.ttsPaused = false;
        state.ttsSessionId++;
        ttsUnits.clear();
        state.ttsChapterIndex = -1;
        state.currentTtsUnitIndex = -1;
        batchQueued = false;
        runtime.systemTtsClient.stop();
        runtime.mimoTtsClient.cancel();
        cancelHighlightProgression();
        synchronized (mimoPcmCache) {
            mimoPcmCache.clear();
        }
        state.ttsHighlightPageIndex = -1;
        state.ttsHighlightStart = -1;
        state.ttsHighlightEnd = -1;
        updateTtsHighlight();
        chrome.styleReaderMenuButton(views.ttsButton, false);
    }

    public void pauseTts() {
        if (!state.ttsActive || state.ttsPaused) return;
        batchQueued = false;
        state.ttsPaused = true;
        runtime.systemTtsClient.pause();
        runtime.mimoTtsClient.cancel();
        cancelHighlightProgression();
        synchronized (mimoPcmCache) {
            mimoPcmCache.clear();
        }
        state.ttsHighlightPageIndex = -1;
        state.ttsHighlightStart = -1;
        state.ttsHighlightEnd = -1;
        updateTtsHighlight();
        chrome.styleReaderMenuButton(views.ttsButton, true);
    }

    public void resumeTts() {
        if (!state.ttsActive || !state.ttsPaused) return;
        state.ttsPaused = false;
        if ("mimo".equals(runtime.settingsStore.getTtsEngine())) {
            playCurrentTtsUnit();
        } else {
            playCurrentTtsUnit();
        }
    }

    public void updateTtsHighlight() {
        if (views.pageBodyCurrent != null) {
            views.pageBodyCurrent.clearHighlight();
        }
        if (views.pageBodyCurrentRight != null) {
            views.pageBodyCurrentRight.clearHighlight();
        }
        if (state.ttsHighlightStart < 0 || state.ttsHighlightEnd <= state.ttsHighlightStart) {
            return;
        }
        if (state.ttsHighlightPageIndex == state.currentPageIndex + 1
                && views.pageBodyCurrentRight != null
                && views.pageBodyCurrentRight.getVisibility() == View.VISIBLE) {
            views.pageBodyCurrentRight.setHighlightRange(state.ttsHighlightStart, state.ttsHighlightEnd);
            return;
        }
        if (views.pageBodyCurrent != null) {
            views.pageBodyCurrent.setHighlightRange(state.ttsHighlightStart, state.ttsHighlightEnd);
        }
    }

    public void showTtsDialog() {
        View contentView = LayoutInflater.from(activity).inflate(R.layout.dialog_tts, null, false);
        Spinner engineSpinner = contentView.findViewById(R.id.tts_spinner_engine);
        SeekBar seekBar = contentView.findViewById(R.id.tts_seek_rate);
        View mimoVoiceLayout = contentView.findViewById(R.id.tts_layout_mimo_voice);
        Spinner mimoVoiceSpinner = contentView.findViewById(R.id.tts_spinner_mimo_voice);
        View systemEngineLayout = contentView.findViewById(R.id.tts_layout_system_engine);
        Spinner systemEngineSpinner = contentView.findViewById(R.id.tts_spinner_system_engine);
        TextView valueText = contentView.findViewById(R.id.tts_text_rate);
        TextView noteText = contentView.findViewById(R.id.tts_text_note);
        Button toggleButton = contentView.findViewById(R.id.tts_button_toggle);
        Button stopButton = contentView.findViewById(R.id.tts_button_stop);

        // Engine spinner
        engineSpinner.setAdapter(dialogSupport.buildSpinnerAdapter(TTS_ENGINE_LABELS));
        engineSpinner.setSelection(indexOf(TTS_ENGINE_KEYS, runtime.settingsStore.getTtsEngine(), 0), false);

        // MiMo voice spinner
        mimoVoiceSpinner.setAdapter(dialogSupport.buildSpinnerAdapter(TTS_MIMO_VOICE_LABELS));
        mimoVoiceSpinner.setSelection(indexOf(TTS_MIMO_VOICE_KEYS, runtime.settingsStore.getTtsMimoVoice(), 0), false);

        // System engine spinner
        List<SystemTtsClient.EngineInfo> engines = SystemTtsClient.queryAvailableEngines(activity);
        String[] engineLabels = new String[engines.size()];
        for (int i = 0; i < engines.size(); i++) {
            engineLabels[i] = engines.get(i).label;
        }
        systemEngineSpinner.setAdapter(dialogSupport.buildSpinnerAdapter(engineLabels));
        String currentEngine = runtime.settingsStore.getTtsSystemEnginePackage();
        int currentEngineIndex = 0;
        for (int i = 0; i < engines.size(); i++) {
            if (engines.get(i).packageName.equals(currentEngine)) {
                currentEngineIndex = i;
                break;
            }
        }
        systemEngineSpinner.setSelection(currentEngineIndex, false);

        // Rate seekbar
        seekBar.setProgress(ui.clamp(Math.round((runtime.settingsStore.getTtsRate() - 0.5f) * 10f), 0, 15));
        valueText.setText(String.format(Locale.SIMPLIFIED_CHINESE, "%.1f 倍", runtime.settingsStore.getTtsRate()));

        updateTtsDialogViews(runtime.settingsStore.getTtsEngine(), mimoVoiceLayout, systemEngineLayout, noteText);

        engineSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                String engine = TTS_ENGINE_KEYS[position];
                runtime.settingsStore.setTtsEngine(engine);
                updateTtsDialogViews(engine, mimoVoiceLayout, systemEngineLayout, noteText);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });

        systemEngineSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < engines.size()) {
                    runtime.settingsStore.setTtsSystemEnginePackage(engines.get(position).packageName);
                }
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });

        mimoVoiceSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                runtime.settingsStore.setTtsMimoVoice(TTS_MIMO_VOICE_KEYS[position]);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });

        seekBar.setOnSeekBarChangeListener(new ReaderDialogSupport.SimpleSeekListener(() -> {
            float rate = 0.5f + (seekBar.getProgress() / 10f);
            valueText.setText(String.format(Locale.SIMPLIFIED_CHINESE, "%.1f 倍", rate));
            runtime.settingsStore.setTtsRate(rate);
        }));

        // Button text
        if (state.ttsPaused) {
            toggleButton.setText("继续听书");
        } else if (state.ttsActive) {
            toggleButton.setText("暂停听书");
        } else {
            toggleButton.setText("开始听书");
        }
        stopButton.setVisibility((state.ttsActive || state.ttsPaused) ? View.VISIBLE : View.GONE);

        AlertDialog dialog = new AlertDialog.Builder(activity).setView(contentView).create();

        toggleButton.setOnClickListener(v -> {
            if (state.ttsPaused) {
                resumeTts();
            } else if (state.ttsActive) {
                pauseTts();
            } else {
                toggleTts();
            }
            dialog.dismiss();
        });

        stopButton.setOnClickListener(v -> {
            stopTts();
            dialog.dismiss();
        });

        dialogSupport.showStyledDialog(dialog);
    }

    private void playCurrentTtsUnit() {
        if (!state.ttsActive) {
            Log.d("TtsHighlight", "playCurrentTtsUnit: ttsActive=false, return");
            return;
        }
        if (state.ttsChapterIndex < 0 || state.ttsChapterIndex >= state.chapters.size()) {
            Log.d("TtsHighlight", "playCurrentTtsUnit: bad chapter index, stop");
            stopTts();
            return;
        }
        if (state.isAnimating || state.interactivePaging) {
            Log.d("TtsHighlight", "playCurrentTtsUnit: isAnimating=" + state.isAnimating + " interactivePaging=" + state.interactivePaging + ", delay retry");
            scheduleTtsPlayback(paging.readerFlipDurationMs() + 60L);
            return;
        }
        if (state.currentTtsUnitIndex >= ttsUnits.size()) {
            Log.d("TtsHighlight", "playCurrentTtsUnit: index=" + state.currentTtsUnitIndex + " >= size=" + ttsUnits.size() + ", next chapter");
            advanceToNextTtsChapter();
            return;
        }
        if (state.currentChapterIndex != state.ttsChapterIndex) {
            SpeechUnit pendingUnit = ttsUnits.get(ui.clamp(state.currentTtsUnitIndex, 0, Math.max(ttsUnits.size() - 1, 0)));
            navigation.openChapter(
                    state.ttsChapterIndex,
                    pendingUnit.start,
                    true,
                    state.ttsChapterIndex >= state.currentChapterIndex ? 1 : -1
            );
            scheduleTtsPlayback(paging.readerFlipDurationMs() + 60L);
            return;
        }
        List<PageSlice> pages = content.getPagesForChapter(state.ttsChapterIndex);
        if (pages.isEmpty()) {
            advanceToNextTtsChapter();
            return;
        }
        PageSlice firstVisibleSlice = pages.get(ui.clamp(state.currentPageIndex, 0, pages.size() - 1));
        while (state.currentTtsUnitIndex < ttsUnits.size() && ttsUnits.get(state.currentTtsUnitIndex).end <= firstVisibleSlice.start) {
            state.currentTtsUnitIndex++;
        }
        if (state.currentTtsUnitIndex >= ttsUnits.size()) {
            advanceToNextTtsChapter();
            return;
        }
        int groupCount = computeGroupUnitCount();
        SpeechUnit unit = ttsUnits.get(state.currentTtsUnitIndex);
        SpeechUnit lastUnit = ttsUnits.get(state.currentTtsUnitIndex + groupCount - 1);
        VisiblePage visiblePage = findVisiblePageForUnit(pages, unit);
        if (visiblePage == null) {
            PageSlice lastVisibleSlice = pages.get(ui.clamp(
                    state.currentPageIndex + content.pagesPerScreen() - 1,
                    0,
                    pages.size() - 1
            ));
            if (unit.start >= lastVisibleSlice.end) {
                if (navigation.pageDown()) {
                    scheduleTtsPlayback(paging.readerFlipDurationMs() + 60L);
                } else {
                    advanceToNextTtsChapter();
                }
                return;
            }
            navigation.openChapter(state.ttsChapterIndex, unit.start, true, -1);
            scheduleTtsPlayback(paging.readerFlipDurationMs() + 60L);
            return;
        }
        PageSlice highlightSlice = visiblePage.slice;
        int highlightStartOffset = Math.max(textStartWithoutLeadingSymbols(unit), highlightSlice.start);
        int highlightEndOffset = Math.min(textEndWithoutTrailingPunctuation(unit), highlightSlice.end);
        if (highlightEndOffset <= highlightStartOffset) {
            if (navigation.pageDown()) {
                scheduleTtsPlayback(paging.readerFlipDurationMs() + 60L);
            } else {
                advanceToNextTtsChapter();
            }
            return;
        }
        int bodyOffsetInSlice = Math.max(highlightSlice.bodyStartInSlice, 0);
        state.ttsHighlightPageIndex = visiblePage.pageIndex;
        state.ttsHighlightStart = bodyOffsetInSlice + (highlightStartOffset - highlightSlice.start);
        state.ttsHighlightEnd = bodyOffsetInSlice + (highlightEndOffset - highlightSlice.start);
        Log.d("TtsHighlight", "playCurrentTtsUnit: set highlight unitIdx=" + state.currentTtsUnitIndex +
                " pageIndex=" + state.ttsHighlightPageIndex + " start=" + state.ttsHighlightStart + " end=" + state.ttsHighlightEnd);
        updateTtsHighlight();
        activity.markReadingActivity();
        speakCurrentTtsGroup(groupCount);
    }

    private boolean rebuildTtsUnitsForChapter(int chapterIndex, int minOffset) {
        ttsUnits.clear();
        if (state.chapters.isEmpty()) {
            state.ttsChapterIndex = -1;
            state.currentTtsUnitIndex = -1;
            return false;
        }
        state.ttsChapterIndex = ui.clamp(chapterIndex, 0, state.chapters.size() - 1);
        Matcher matcher = TTS_SEGMENT_PATTERN.matcher(content.getProcessedChapterText(state.ttsChapterIndex));
        while (matcher.find()) {
            String segment = matcher.group();
            if (segment == null || segment.trim().isEmpty()) {
                continue;
            }
            ttsUnits.add(new SpeechUnit(matcher.start(), matcher.end(), segment));
        }
        state.currentTtsUnitIndex = 0;
        while (state.currentTtsUnitIndex < ttsUnits.size() && ttsUnits.get(state.currentTtsUnitIndex).end <= minOffset) {
            state.currentTtsUnitIndex++;
        }
        return state.currentTtsUnitIndex < ttsUnits.size();
    }

    private void advanceTtsPlayback(int consumedUnits) {
        if (!state.ttsActive) {
            return;
        }
        state.currentTtsUnitIndex += Math.max(consumedUnits, 0);
        playCurrentTtsUnit();
    }

    private VisiblePage findVisiblePageForUnit(List<PageSlice> pages, SpeechUnit unit) {
        if (pages == null || pages.isEmpty() || unit == null) {
            return null;
        }
        int firstPageIndex = ui.clamp(state.currentPageIndex, 0, pages.size() - 1);
        int lastPageIndex = ui.clamp(firstPageIndex + content.pagesPerScreen() - 1, firstPageIndex, pages.size() - 1);
        for (int pageIndex = firstPageIndex; pageIndex <= lastPageIndex; pageIndex++) {
            PageSlice slice = pages.get(pageIndex);
            if (unit.start < slice.end && unit.end > slice.start) {
                return new VisiblePage(pageIndex, slice);
            }
        }
        return null;
    }

    private void scheduleTtsPlayback(long delayMillis) {
        int sessionId = state.ttsSessionId;
        runtime.mainHandler.postDelayed(() -> {
            if (!state.ttsActive || sessionId != state.ttsSessionId) {
                return;
            }
            playCurrentTtsUnit();
        }, Math.max(delayMillis, 20L));
    }

    private void advanceToNextTtsChapter() {
        if (!state.ttsActive) {
            return;
        }
        if (state.ttsChapterIndex >= state.chapters.size() - 1) {
            stopTts();
            return;
        }
        int nextChapterIndex = state.ttsChapterIndex + 1;
        navigation.openChapter(nextChapterIndex, 0, true, 1);
        int sessionId = state.ttsSessionId;
        runtime.mainHandler.postDelayed(() -> {
            if (!state.ttsActive || sessionId != state.ttsSessionId) {
                return;
            }
            rebuildTtsUnitsForChapter(nextChapterIndex, 0);
            playCurrentTtsUnit();
        }, paging.readerFlipDurationMs() * 2L + 60L);
    }

    private int computeGroupUnitCount() {
        return computeGroupUnitCountAt(state.currentTtsUnitIndex);
    }

    private int computeGroupUnitCountAt(int startIndex) {
        int groupCount = 1;
        while (startIndex + groupCount < ttsUnits.size()
                && !endsWithFullSentence(ttsUnits.get(startIndex + groupCount - 1).text)) {
            groupCount++;
        }
        return groupCount;
    }

    private String buildGroupText(int startIndex, int groupCount) {
        StringBuilder builder = new StringBuilder(ttsUnits.get(startIndex).text);
        for (int i = 1; i < groupCount; i++) {
            builder.append(ttsUnits.get(startIndex + i).text);
        }
        return builder.toString().trim();
    }

    private void preloadMimoGroups(int afterUnitIndex, int afterGroupCount, int sessionId,
                                    String apiKey, String voice) {
        int nextStart = afterUnitIndex + afterGroupCount;
        for (int i = 0; i < MIMO_PRECACHE_AHEAD && nextStart < ttsUnits.size(); i++) {
            int gc = computeGroupUnitCountAt(nextStart);
            String text = buildGroupText(nextStart, gc);
            int cacheKey = nextStart;
            runtime.synthesisExecutor.execute(() -> {
                if (!state.ttsActive || sessionId != state.ttsSessionId) {
                    return;
                }
                try {
                    byte[] pcm = runtime.mimoTtsClient.synthesize(text, apiKey, voice);
                    synchronized (mimoPcmCache) {
                        if (state.ttsActive && sessionId == state.ttsSessionId) {
                            mimoPcmCache.put(cacheKey, pcm);
                        }
                    }
                } catch (Exception ignored) {
                }
            });
            nextStart += gc;
        }
    }

    private void scheduleHighlightProgression(int startIdx, int groupCount, int sessionId,
                                               byte[] pcm, float rate) {
        if (groupCount <= 1 || !state.ttsActive || sessionId != state.ttsSessionId) return;
        int sampleRate = com.metahumanz.pacilread.tts.MimoTtsClient.getSampleRate();
        long totalMs = Math.round((pcm.length / 2f / sampleRate) / Math.max(rate, 0.5f) * 1000f);
        int totalLen = 0;
        for (int i = 0; i < groupCount && startIdx + i < ttsUnits.size(); i++) {
            totalLen += ttsUnits.get(startIdx + i).text.length();
        }
        if (totalLen <= 0 || totalMs <= 200) return;

        int cumulativeLen = 0;
        for (int i = 0; i < groupCount && startIdx + i < ttsUnits.size(); i++) {
            long delayMs = Math.round((float) cumulativeLen / totalLen * totalMs);
            cumulativeLen += ttsUnits.get(startIdx + i).text.length();
            SpeechUnit unit = ttsUnits.get(startIdx + i);

            Runnable task = buildHighlightTask(sessionId, unit, startIdx, i, groupCount, delayMs);
            pendingHighlightTasks.add(task);
            runtime.mainHandler.postDelayed(task, Math.max(delayMs, 50L));
        }
    }

    private Runnable buildHighlightTask(int sessionId, SpeechUnit unit,
                                         int startIdx, int unitOffset, int groupCount,
                                         long originalDelayMs) {
        return new Runnable() {
            private long retryDelayMs = originalDelayMs;
            private int retries = 0;

            @Override
            public void run() {
                if (!state.ttsActive || sessionId != state.ttsSessionId) return;
                if (state.isAnimating || state.interactivePaging) {
                    if (retries++ < 30) {
                        runtime.mainHandler.postDelayed(this, paging.readerFlipDurationMs() + 20L);
                    }
                    return;
                }
                List<PageSlice> pages = content.getPagesForChapter(state.ttsChapterIndex);
                if (pages == null || pages.isEmpty()) return;
                VisiblePage vp = findVisiblePageForUnit(pages, unit);
                if (vp != null) {
                    state.ttsHighlightPageIndex = vp.pageIndex;
                    int bos = Math.max(vp.slice.bodyStartInSlice, 0);
                    int hs = Math.max(textStartWithoutLeadingSymbols(unit), vp.slice.start);
                    int he = Math.min(textEndWithoutTrailingPunctuation(unit), vp.slice.end);
                    state.ttsHighlightStart = bos + (hs - vp.slice.start);
                    state.ttsHighlightEnd = bos + (he - vp.slice.start);
                    if (state.ttsHighlightEnd > state.ttsHighlightStart) {
                        updateTtsHighlight();
                    }
                    return;
                }
                int firstVisible = ui.clamp(state.currentPageIndex, 0, pages.size() - 1);
                if (unit.start >= pages.get(firstVisible).end) {
                    if (navigation.pageDown()) {
                        if (retries++ < 30) {
                            runtime.mainHandler.postDelayed(this,
                                    paging.readerFlipDurationMs() + 20L);
                        }
                    }
                }
            }
        };
    }

    private void highlightUnit(int unitIndex) {
        if (unitIndex < 0 || unitIndex >= ttsUnits.size()) return;
        List<PageSlice> pages = content.getPagesForChapter(state.ttsChapterIndex);
        if (pages == null || pages.isEmpty()) return;
        SpeechUnit unit = ttsUnits.get(unitIndex);
        VisiblePage vp = findVisiblePageForUnit(pages, unit);
        if (vp == null) {
            PageSlice lastSlice = pages.get(ui.clamp(
                    state.currentPageIndex + content.pagesPerScreen() - 1,
                    0, pages.size() - 1));
            if (unit.start >= lastSlice.end) {
                if (navigation.pageDown()) {
                    scheduleTtsPlayback(paging.readerFlipDurationMs() + 60L);
                }
                return;
            }
            return;
        }
        int hs = Math.max(textStartWithoutLeadingSymbols(unit), vp.slice.start);
        int he = Math.min(textEndWithoutTrailingPunctuation(unit), vp.slice.end);
        if (he <= hs) return;
        int bos = Math.max(vp.slice.bodyStartInSlice, 0);
        state.ttsHighlightPageIndex = vp.pageIndex;
        state.ttsHighlightStart = bos + (hs - vp.slice.start);
        state.ttsHighlightEnd = bos + (he - vp.slice.start);
        updateTtsHighlight();
    }

    private void scheduleSystemHighlightProgression(int startIdx, int groupCount, int sessionId,
                                                     float rate) {
        if (groupCount <= 1 || !state.ttsActive || sessionId != state.ttsSessionId) return;
        int totalLen = 0;
        for (int i = 0; i < groupCount && startIdx + i < ttsUnits.size(); i++) {
            totalLen += ttsUnits.get(startIdx + i).text.length();
        }
        if (totalLen <= 0) return;
        long totalMs = Math.round(totalLen * 250f / Math.max(rate, 0.5f));
        if (totalMs <= 200) return;

        int cumulativeLen = 0;
        for (int i = 0; i < groupCount && startIdx + i < ttsUnits.size(); i++) {
            long delayMs = Math.round((float) cumulativeLen / totalLen * totalMs);
            cumulativeLen += ttsUnits.get(startIdx + i).text.length();
            SpeechUnit unit = ttsUnits.get(startIdx + i);
            Runnable task = buildHighlightTask(sessionId, unit, startIdx, i, groupCount, delayMs);
            pendingHighlightTasks.add(task);
            runtime.mainHandler.postDelayed(task, Math.max(delayMs, 50L));
        }
    }

    private void cancelHighlightProgression() {
        for (Runnable task : pendingHighlightTasks) {
            runtime.mainHandler.removeCallbacks(task);
        }
        pendingHighlightTasks.clear();
    }

    private void speakCurrentTtsGroup(int groupCount) {
        if (state.currentTtsUnitIndex < 0 || state.currentTtsUnitIndex >= ttsUnits.size()) {
            advanceToNextTtsChapter();
            return;
        }
        speakWithCurrentEngine(groupCount);
    }

    private void speakWithCurrentEngine(int groupCount) {
        String groupText = buildGroupText(state.currentTtsUnitIndex, groupCount);
        if (groupText.isEmpty()) {
            advanceTtsPlayback(groupCount);
            return;
        }
        int sessionId = state.ttsSessionId;
        int consumedUnits = groupCount;
        String engine = runtime.settingsStore.getTtsEngine();
        String engineLabel = engineLabel(engine);

        if ("mimo".equals(engine)) {
            runtime.ttsExecutor.execute(() -> {
                try {
                    if (!state.ttsActive || sessionId != state.ttsSessionId) {
                        return;
                    }
                    String apiKey = runtime.settingsStore.getTtsMimoApiKey();
                    String voice = runtime.settingsStore.getTtsMimoVoice();
                    float playbackRate = runtime.settingsStore.getTtsRate();
                    byte[] pcm;
                    synchronized (mimoPcmCache) {
                        pcm = mimoPcmCache.remove(state.currentTtsUnitIndex);
                    }
                    if (pcm != null) {
                        preloadMimoGroups(state.currentTtsUnitIndex, consumedUnits,
                                sessionId, apiKey, voice);
                    } else {
                        if (!state.ttsActive || sessionId != state.ttsSessionId) {
                            return;
                        }
                        pcm = runtime.mimoTtsClient.synthesize(groupText, apiKey, voice);
                        preloadMimoGroups(state.currentTtsUnitIndex, consumedUnits,
                                sessionId, apiKey, voice);
                    }
                    scheduleHighlightProgression(state.currentTtsUnitIndex, consumedUnits,
                            sessionId, pcm, playbackRate);
                    runtime.mimoTtsClient.playPcm(pcm, playbackRate);
                    cancelHighlightProgression();
                    activity.runOnUiThread(() -> {
                        if (!state.ttsActive || sessionId != state.ttsSessionId) {
                            return;
                        }
                        advanceTtsPlayback(consumedUnits);
                    });
                } catch (Exception error) {
                    activity.runOnUiThread(() -> {
                        if (!state.ttsActive || sessionId != state.ttsSessionId) {
                            return;
                        }
                        stopTts();
                        ui.showToast(engineLabel + " 听书失败: " + error.getMessage());
                    });
                }
            });
            return;
        }

        // === System TTS path: batch-queue all groups like Legado ===
        if (batchQueued) {
            return;
        }

        if (!runtime.systemTtsClient.requestAudioFocus()) {
            ui.showToast("未获取到音频焦点");
            stopTts();
            return;
        }

        List<String> allTexts = new ArrayList<>();
        List<Integer> allGroupStartIndices = new ArrayList<>();
        List<Integer> allGroupCounts = new ArrayList<>();
        int idx = state.currentTtsUnitIndex;
        while (idx < ttsUnits.size()) {
            int gc = computeGroupUnitCountAt(idx);
            String t = buildGroupText(idx, gc);
            if (!t.isEmpty()) {
                allTexts.add(t);
                allGroupStartIndices.add(idx);
                allGroupCounts.add(gc);
            }
            idx += gc;
        }

        if (allTexts.isEmpty()) {
            advanceToNextTtsChapter();
            return;
        }

        batchQueued = true;
        float rate = runtime.settingsStore.getTtsRate();
        int[] completed = new int[]{0};
        // Schedule per-unit highlighting for the first group
        scheduleSystemHighlightProgression(
                allGroupStartIndices.get(0), allGroupCounts.get(0), sessionId, rate);
        runtime.systemTtsClient.speakAll(allTexts, rate, new SystemTtsClient.SpeakCallback() {
            @Override
            public void onStart() {
            }

            @Override
            public void onDone() {
                int i = completed[0];
                completed[0] = i + 1;
                int units = allGroupCounts.get(i);
                Log.d("TtsHighlight", "onDone: i=" + i + " units=" + units + " totalGroups=" + allTexts.size());
                activity.runOnUiThread(() -> {
                    if (!state.ttsActive || sessionId != state.ttsSessionId) {
                        Log.d("TtsHighlight", "onDone ui: state inactive, skip");
                        return;
                    }
                    cancelHighlightProgression();
                    if (i + 1 >= allTexts.size()) {
                        batchQueued = false;
                        Log.d("TtsHighlight", "onDone ui: last group, batchQueued=false");
                    } else {
                        scheduleSystemHighlightProgression(
                                allGroupStartIndices.get(i + 1), allGroupCounts.get(i + 1),
                                sessionId, rate);
                    }
                    advanceTtsPlayback(units);
                });
            }

            @Override
            public void onError(String message) {
                batchQueued = false;
                activity.runOnUiThread(() -> {
                    if (!state.ttsActive || sessionId != state.ttsSessionId) return;
                    cancelHighlightProgression();
                    stopTts();
                    ui.showToast("系统 TTS 听书失败: " + message);
                });
            }
        });
    }

    private int textEndWithoutTrailingPunctuation(SpeechUnit unit) {
        String text = unit.text;
        int len = text.length();
        while (len > 0) {
            char c = text.charAt(len - 1);
            if (c == '。' || c == '！' || c == '？' || c == '!' || c == '?' ||
                c == '，' || c == ',' || c == '；' || c == ';' || c == '、' || c == '.' ||
                c == '：' || c == '"' || c == '"' || c == '"' || c == '」' || c == '』') {
                len--;
            } else {
                break;
            }
        }
        return len == 0 ? unit.end : unit.start + len;
    }

    private int textStartWithoutLeadingSymbols(SpeechUnit unit) {
        String text = unit.text;
        int offset = 0;
        while (offset < text.length()) {
            char c = text.charAt(offset);
            if (c == '"' || c == '"' || c == '"' || c == '\'' ||
                c == '「' || c == '『') {
                offset++;
            } else {
                break;
            }
        }
        return unit.start + offset;
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

    private void updateTtsDialogViews(String engine, View mimoVoiceLayout, View systemEngineLayout,
                                      TextView noteText) {
        if (mimoVoiceLayout != null) {
            mimoVoiceLayout.setVisibility("mimo".equals(engine) ? View.VISIBLE : View.GONE);
        }
        if (systemEngineLayout != null) {
            systemEngineLayout.setVisibility(!"mimo".equals(engine) ? View.VISIBLE : View.GONE);
        }
        if (noteText == null) {
            return;
        }
        if ("mimo".equals(engine)) {
            noteText.setText("MiMo 模式会调用小米云端 TTS，模型为 mimo-v2.5-tts，API Key 请在设置页维护。\nMiMo 听书不推荐调整语速倍率。");
            return;
        }
        noteText.setText("系统 TTS 使用设备内置语音引擎，无需联网。");
    }

    private String engineLabel(String engine) {
        return "mimo".equals(engine) ? "MiMo" : "系统 TTS";
    }

    private int indexOf(String[] values, String target, int fallback) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(target)) {
                return i;
            }
        }
        return fallback;
    }

    private static final class SpeechUnit {
        final int start;
        final int end;
        final String text;

        private SpeechUnit(int start, int end, String text) {
            this.start = start;
            this.end = end;
            this.text = text;
        }
    }

    private static final class VisiblePage {
        final int pageIndex;
        final PageSlice slice;

        private VisiblePage(int pageIndex, PageSlice slice) {
            this.pageIndex = pageIndex;
            this.slice = slice;
        }
    }
}
