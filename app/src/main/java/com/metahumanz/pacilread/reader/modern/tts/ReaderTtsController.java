package com.metahumanz.pacilread.reader.modern.tts;

import android.app.AlertDialog;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ReaderTtsController {
    private static final Pattern TTS_SEGMENT_PATTERN = Pattern.compile("[^ \\n\\t。！？.!?,，;；、]+[。！？.!?,，;；、]*");

    private final ModernReaderActivity activity;
    private final ReaderRuntime runtime;
    private final ReaderViewRefs views;
    private final ReaderSessionState state;
    private final ReaderUiUtils ui;
    private final ReaderDialogSupport dialogSupport;
    private final List<SpeechUnit> ttsUnits = new ArrayList<>();

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
        if (state.ttsActive) {
            stopTts();
            return;
        }
        if (runtime.settingsStore.getTtsMimoApiKey().isBlank()) {
            ui.showToast("请先填写 MiMo API Key");
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

    public void stopTts() {
        state.ttsActive = false;
        state.ttsSessionId++;
        ttsUnits.clear();
        state.ttsChapterIndex = -1;
        state.currentTtsUnitIndex = -1;
        runtime.mimoTtsClient.cancel();
        state.ttsHighlightStart = -1;
        state.ttsHighlightEnd = -1;
        updateTtsHighlight();
        chrome.styleReaderMenuButton(views.ttsButton, false);
    }

    public void updateTtsHighlight() {
        if (state.ttsHighlightStart >= 0 && state.ttsHighlightEnd > state.ttsHighlightStart) {
            views.pageBodyCurrent.setHighlightRange(state.ttsHighlightStart, state.ttsHighlightEnd);
        } else {
            views.pageBodyCurrent.clearHighlight();
        }
        views.pageBodyCurrent.invalidate();
    }

    public void showTtsDialog() {
        View contentView = LayoutInflater.from(activity).inflate(R.layout.dialog_tts, null, false);
        SeekBar seekBar = contentView.findViewById(R.id.tts_seek_rate);
        TextView valueText = contentView.findViewById(R.id.tts_text_rate);
        EditText mimoKeyInput = contentView.findViewById(R.id.tts_input_mimo_api_key);
        TextView noteText = contentView.findViewById(R.id.tts_text_note);
        Button toggleButton = contentView.findViewById(R.id.tts_button_toggle);
        seekBar.setProgress(ui.clamp(Math.round((runtime.settingsStore.getTtsRate() - 0.5f) * 10f), 0, 15));
        valueText.setText(String.format(Locale.SIMPLIFIED_CHINESE, "%.1f 倍", runtime.settingsStore.getTtsRate()));
        mimoKeyInput.setText(runtime.settingsStore.getTtsMimoApiKey());
        noteText.setText("MiMo 模式会调用小米云端 TTS，模型固定为 mimo-v2-tts / mimo_default。");
        seekBar.setOnSeekBarChangeListener(new ReaderDialogSupport.SimpleSeekListener(() -> {
            float rate = 0.5f + (seekBar.getProgress() / 10f);
            valueText.setText(String.format(Locale.SIMPLIFIED_CHINESE, "%.1f 倍", rate));
            runtime.settingsStore.setTtsRate(rate);
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
                runtime.settingsStore.setTtsMimoApiKey(s == null ? "" : s.toString());
            }
        });
        toggleButton.setText(state.ttsActive ? "停止听书" : "开始听书");
        AlertDialog dialog = new AlertDialog.Builder(activity).setView(contentView).create();
        toggleButton.setOnClickListener(v -> {
            if (state.ttsActive) {
                stopTts();
            } else {
                toggleTts();
            }
            dialog.dismiss();
        });
        dialogSupport.showStyledDialog(dialog);
    }

    private void playCurrentTtsUnit() {
        if (!state.ttsActive) {
            return;
        }
        if (state.ttsChapterIndex < 0 || state.ttsChapterIndex >= state.chapters.size()) {
            stopTts();
            return;
        }
        if (state.isAnimating || state.interactivePaging) {
            scheduleTtsPlayback(paging.readerFlipDurationMs() + 60L);
            return;
        }
        if (state.currentTtsUnitIndex >= ttsUnits.size()) {
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
        PageSlice currentSlice = pages.get(ui.clamp(state.currentPageIndex, 0, pages.size() - 1));
        while (state.currentTtsUnitIndex < ttsUnits.size() && ttsUnits.get(state.currentTtsUnitIndex).end <= currentSlice.start) {
            state.currentTtsUnitIndex++;
        }
        if (state.currentTtsUnitIndex >= ttsUnits.size()) {
            advanceToNextTtsChapter();
            return;
        }
        SpeechUnit unit = ttsUnits.get(state.currentTtsUnitIndex);
        if (unit.start >= currentSlice.end) {
            if (navigation.pageDown()) {
                scheduleTtsPlayback(paging.readerFlipDurationMs() + 60L);
            } else {
                advanceToNextTtsChapter();
            }
            return;
        }
        PageSlice highlightSlice = pages.get(ui.clamp(state.currentPageIndex, 0, pages.size() - 1));
        state.ttsHighlightStart = unit.start - highlightSlice.start;
        state.ttsHighlightEnd = unit.end - highlightSlice.start;
        updateTtsHighlight();
        speakCurrentMimoGroup();
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
        boolean chapterTurning = state.currentChapterIndex == state.ttsChapterIndex;
        if (chapterTurning) {
            if (!navigation.pageDown()) {
                stopTts();
                return;
            }
        } else {
            navigation.openChapter(nextChapterIndex, 0, true, 1);
        }
        int sessionId = state.ttsSessionId;
        runtime.mainHandler.postDelayed(() -> {
            if (!state.ttsActive || sessionId != state.ttsSessionId) {
                return;
            }
            rebuildTtsUnitsForChapter(nextChapterIndex, 0);
            playCurrentTtsUnit();
        }, paging.readerFlipDurationMs() * 2L + 60L);
    }

    private void speakCurrentMimoGroup() {
        if (state.currentTtsUnitIndex < 0 || state.currentTtsUnitIndex >= ttsUnits.size()) {
            advanceToNextTtsChapter();
            return;
        }
        speakWithMimo();
    }

    private void speakWithMimo() {
        int groupCount = 1;
        StringBuilder builder = new StringBuilder(ttsUnits.get(state.currentTtsUnitIndex).text);
        while (state.currentTtsUnitIndex + groupCount < ttsUnits.size()
                && !endsWithFullSentence(ttsUnits.get(state.currentTtsUnitIndex + groupCount - 1).text)) {
            builder.append(ttsUnits.get(state.currentTtsUnitIndex + groupCount).text);
            groupCount++;
        }
        String groupText = builder.toString().trim();
        if (groupText.isEmpty()) {
            advanceTtsPlayback(groupCount);
            return;
        }
        int sessionId = state.ttsSessionId;
        int consumedUnits = groupCount;
        runtime.ttsExecutor.execute(() -> {
            try {
                runtime.mimoTtsClient.speak(groupText, runtime.settingsStore.getTtsMimoApiKey(), runtime.settingsStore.getTtsRate());
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
                    ui.showToast("MiMo 听书失败: " + error.getMessage());
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
}
