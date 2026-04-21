package com.metahumanz.pacilread.reader.modern.dialog;

import android.app.AlertDialog;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import com.metahumanz.pacilread.R;
import com.metahumanz.pacilread.model.ReplacementRuleRecord;
import com.metahumanz.pacilread.reader.modern.ModernReaderActivity;
import com.metahumanz.pacilread.reader.modern.ReaderRuntime;
import com.metahumanz.pacilread.reader.modern.ReaderSessionState;
import com.metahumanz.pacilread.reader.modern.ReaderUiUtils;
import com.metahumanz.pacilread.reader.modern.content.ReaderContentController;
import com.metahumanz.pacilread.reader.modern.paging.ReaderNavigationController;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ReaderLibraryDialogs {
    private final ModernReaderActivity activity;
    private final ReaderRuntime runtime;
    private final ReaderSessionState state;
    private final ReaderUiUtils ui;
    private final ReaderDialogSupport dialogSupport;
    private final ReaderContentController content;
    private final ReaderNavigationController navigation;

    public ReaderLibraryDialogs(
            ModernReaderActivity activity,
            ReaderRuntime runtime,
            ReaderSessionState state,
            ReaderUiUtils ui,
            ReaderDialogSupport dialogSupport,
            ReaderContentController content,
            ReaderNavigationController navigation
    ) {
        this.activity = activity;
        this.runtime = runtime;
        this.state = state;
        this.ui = ui;
        this.dialogSupport = dialogSupport;
        this.content = content;
        this.navigation = navigation;
    }

    public void showTocDialog() {
        if (state.chapters.isEmpty()) {
            return;
        }
        View contentView = LayoutInflater.from(activity).inflate(R.layout.dialog_toc, null, false);
        ListView listView = contentView.findViewById(R.id.toc_list);
        List<String> items = new ArrayList<>();
        for (int i = 0; i < state.chapters.size(); i++) {
            items.add(String.format(Locale.SIMPLIFIED_CHINESE, "%03d  %s", i + 1, state.chapters.get(i).title));
        }
        ArrayAdapter<String> adapter = dialogSupport.buildDialogListAdapter(items);
        listView.setAdapter(adapter);
        listView.setSelection(state.currentChapterIndex);
        AlertDialog dialog = new AlertDialog.Builder(activity).setView(contentView).create();
        listView.setOnItemClickListener((parent, view, position, id) -> {
            dialog.dismiss();
            navigation.openChapter(position, 0, true, position >= state.currentChapterIndex ? 1 : -1);
        });
        dialogSupport.showFullscreenDialog(dialog);
    }

    public void showSearchDialog() {
        View contentView = LayoutInflater.from(activity).inflate(R.layout.dialog_search, null, false);
        EditText queryInput = contentView.findViewById(R.id.search_query_input);
        Button searchButton = contentView.findViewById(R.id.search_button_go);
        TextView resultCount = contentView.findViewById(R.id.search_result_count);
        ListView listView = contentView.findViewById(R.id.search_result_list);
        List<SearchResult> results = new ArrayList<>();
        ArrayAdapter<String> adapter = dialogSupport.buildDialogListAdapter(new ArrayList<>());
        listView.setAdapter(adapter);
        AlertDialog dialog = new AlertDialog.Builder(activity).setView(contentView).create();
        listView.setOnItemClickListener((parent, view, position, id) -> {
            SearchResult result = results.get(position);
            dialog.dismiss();
            navigation.openChapter(
                    result.chapterIndex,
                    result.charOffset,
                    true,
                    result.chapterIndex >= state.currentChapterIndex ? 1 : -1
            );
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
            runtime.executor.execute(() -> {
                List<SearchResult> tempResults = new ArrayList<>();
                for (int i = 0; i < state.chapters.size(); i++) {
                    String text = content.getProcessedChapterText(i);
                    int index = text.toLowerCase(Locale.ROOT).indexOf(query);
                    if (index >= 0) {
                        String snippet = text.substring(
                                Math.max(0, index - 18),
                                Math.min(text.length(), index + query.length() + 24)
                        ).replace('\n', ' ').trim();
                        tempResults.add(new SearchResult(i, state.chapters.get(i).title, snippet, index));
                    }
                }
                activity.runOnUiThread(() -> {
                    results.clear();
                    results.addAll(tempResults);
                    adapter.clear();
                    for (SearchResult result : results) {
                        adapter.add(result.chapterTitle + "\n" + result.snippet);
                    }
                    resultCount.setText(results.isEmpty() ? "没有找到匹配内容" : "找到 " + results.size() + " 条结果");
                });
            });
        });
        dialogSupport.showStyledDialog(dialog);
    }

    public void showRulesDialog() {
        View contentView = LayoutInflater.from(activity).inflate(R.layout.dialog_rules, null, false);
        EditText patternInput = contentView.findViewById(R.id.rules_input_pattern);
        EditText replacementInput = contentView.findViewById(R.id.rules_input_replacement);
        CheckBox globalCheck = contentView.findViewById(R.id.rules_check_global);
        CheckBox regexCheck = contentView.findViewById(R.id.rules_check_regex);
        Button addButton = contentView.findViewById(R.id.rules_button_add);
        TextView hintText = contentView.findViewById(R.id.rules_text_hint);
        ListView listView = contentView.findViewById(R.id.rules_list);
        ArrayAdapter<String> adapter = dialogSupport.buildDialogListAdapter(new ArrayList<>());
        listView.setAdapter(adapter);
        hintText.setText("点击列表切换启用状态，长按删除。");
        refreshRuleLabels(adapter);
        AlertDialog dialog = new AlertDialog.Builder(activity).setView(contentView).create();
        addButton.setOnClickListener(v -> {
            String pattern = patternInput.getText().toString();
            if (pattern.trim().isEmpty()) {
                ui.showToast("请先输入查找内容");
                return;
            }
            if (regexCheck.isChecked()) {
                try {
                    java.util.regex.Pattern.compile(pattern);
                } catch (Exception e) {
                    ui.showToast("正则表达式语法错误: " + e.getMessage());
                    return;
                }
            }
            int offset = content.currentCharOffset();
            runtime.executor.execute(() -> {
                runtime.databaseHelper.addReplacementRule(
                        pattern,
                        replacementInput.getText().toString(),
                        globalCheck.isChecked(),
                        state.bookId,
                        regexCheck.isChecked()
                );
                List<com.metahumanz.pacilread.model.ReplacementRuleRecord> rules = runtime.databaseHelper.getReplacementRules(state.bookId);
                activity.runOnUiThread(() -> {
                    state.replacementRules.clear();
                    state.replacementRules.addAll(rules);
                    content.clearAllReaderCaches();
                    refreshRuleLabels(adapter);
                    patternInput.setText("");
                    replacementInput.setText("");
                    regexCheck.setChecked(false);
                    navigation.openChapter(state.currentChapterIndex, offset, false, 0);
                });
            });
        });
        listView.setOnItemClickListener((parent, view, position, id) -> {
            ReplacementRuleRecord rule = state.replacementRules.get(position);
            int offset = content.currentCharOffset();
            runtime.executor.execute(() -> {
                runtime.databaseHelper.toggleReplacementRule(rule.id, !rule.active);
                List<com.metahumanz.pacilread.model.ReplacementRuleRecord> rules = runtime.databaseHelper.getReplacementRules(state.bookId);
                activity.runOnUiThread(() -> {
                    state.replacementRules.clear();
                    state.replacementRules.addAll(rules);
                    content.clearAllReaderCaches();
                    refreshRuleLabels(adapter);
                    navigation.openChapter(state.currentChapterIndex, offset, false, 0);
                });
            });
        });
        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            ReplacementRuleRecord rule = state.replacementRules.get(position);
            runtime.executor.execute(() -> {
                runtime.databaseHelper.deleteReplacementRule(rule.id);
                List<com.metahumanz.pacilread.model.ReplacementRuleRecord> rules = runtime.databaseHelper.getReplacementRules(state.bookId);
                activity.runOnUiThread(() -> {
                    state.replacementRules.clear();
                    state.replacementRules.addAll(rules);
                    content.clearAllReaderCaches();
                    refreshRuleLabels(adapter);
                });
            });
            return true;
        });
        dialogSupport.showStyledDialog(dialog);
    }

    private void refreshRuleLabels(ArrayAdapter<String> adapter) {
        adapter.clear();
        for (ReplacementRuleRecord rule : state.replacementRules) {
            String replacement = rule.replacement == null || rule.replacement.isEmpty() ? "(删除)" : rule.replacement;
            adapter.add((rule.active ? "[启用] " : "[停用] ") + rule.pattern + " -> " + replacement);
        }
    }

    private static final class SearchResult {
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
}
