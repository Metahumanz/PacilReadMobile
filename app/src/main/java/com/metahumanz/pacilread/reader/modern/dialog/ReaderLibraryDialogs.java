package com.metahumanz.pacilread.reader.modern.dialog;

import android.app.AlertDialog;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
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
        View contentContainer = contentView.findViewById(R.id.toc_content);
        FrameLayout tocBody = contentView.findViewById(R.id.toc_body);
        ListView listView = contentView.findViewById(R.id.toc_list);
        View scrubberHost = contentView.findViewById(R.id.toc_scrubber_host);
        View scrubberTrack = contentView.findViewById(R.id.toc_scrubber_track);
        View scrubberThumb = contentView.findViewById(R.id.toc_scrubber_thumb);
        TextView scrubberPreview = contentView.findViewById(R.id.toc_scrubber_preview);
        List<String> items = new ArrayList<>();
        for (int i = 0; i < state.chapters.size(); i++) {
            items.add(String.format(Locale.SIMPLIFIED_CHINESE, "%03d  %s", i + 1, state.chapters.get(i).title));
        }
        ArrayAdapter<String> adapter = new TocListAdapter(items, state.currentChapterIndex);
        listView.setAdapter(adapter);
        AlertDialog dialog = new AlertDialog.Builder(activity).setView(contentView).create();
        dialogSupport.applyTocStyleFullscreenInsets(contentView, contentContainer);
        dialogSupport.addAlignedCloseButton(contentView, R.id.toc_title, contentContainer, dialog);
        attachListScrubber(
                listView,
                tocBody,
                scrubberHost,
                scrubberTrack,
                scrubberThumb,
                scrubberPreview,
                new ScrubberItems() {
                    @Override
                    public int size() {
                        return items.size();
                    }

                    @Override
                    public CharSequence previewText(int index) {
                        return items.get(index);
                    }
                }
        );
        listView.setOnItemClickListener((parent, view, position, id) -> {
            dialog.dismiss();
            navigation.openChapterFromStart(position, true, position >= state.currentChapterIndex ? 1 : -1);
        });
        dialogSupport.showImmersiveFullscreenDialog(dialog, state.controlsVisible);
        contentView.requestApplyInsets();
        listView.post(() -> {
            listView.setSelectionFromTop(state.currentChapterIndex, 0);
            positionScrubberThumb(scrubberTrack, scrubberThumb, fractionForIndex(state.currentChapterIndex, items.size()));
        });
    }

    public void showSearchDialog() {
        showSearchDialog("", false);
    }

    public void showSearchDialog(String initialQuery, boolean autoRun) {
        View contentView = LayoutInflater.from(activity).inflate(R.layout.dialog_search, null, false);
        EditText queryInput = contentView.findViewById(R.id.search_query_input);
        Button searchButton = contentView.findViewById(R.id.search_button_go);
        TextView resultCount = contentView.findViewById(R.id.search_result_count);
        FrameLayout searchBody = contentView.findViewById(R.id.search_result_body);
        ListView listView = contentView.findViewById(R.id.search_result_list);
        View scrubberHost = contentView.findViewById(R.id.search_scrubber_host);
        View scrubberTrack = contentView.findViewById(R.id.search_scrubber_track);
        View scrubberThumb = contentView.findViewById(R.id.search_scrubber_thumb);
        TextView scrubberPreview = contentView.findViewById(R.id.search_scrubber_preview);
        List<SearchResult> results = new ArrayList<>();
        ArrayAdapter<String> adapter = dialogSupport.buildDialogListAdapter(new ArrayList<>());
        listView.setAdapter(adapter);
        AlertDialog dialog = new AlertDialog.Builder(activity).setView(contentView).create();
        attachListScrubber(
                listView,
                searchBody,
                scrubberHost,
                scrubberTrack,
                scrubberThumb,
                scrubberPreview,
                new ScrubberItems() {
                    @Override
                    public int size() {
                        return results.size();
                    }

                    @Override
                    public CharSequence previewText(int index) {
                        return searchScrubberPreviewText(results.get(index));
                    }
                }
        );
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
        Runnable runSearch = () -> {
            String query = queryInput.getText().toString().trim().toLowerCase(Locale.ROOT);
            if (query.isEmpty()) {
                results.clear();
                adapter.clear();
                resultCount.setText("请输入关键词");
                refreshListScrubber(listView, scrubberHost, scrubberTrack, scrubberThumb, scrubberPreview, results.size());
                return;
            }
            results.clear();
            adapter.clear();
            resultCount.setText("正在搜索...");
            refreshListScrubber(listView, scrubberHost, scrubberTrack, scrubberThumb, scrubberPreview, results.size());
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
                    listView.setSelectionFromTop(0, 0);
                    listView.post(() -> refreshListScrubber(
                            listView,
                            scrubberHost,
                            scrubberTrack,
                            scrubberThumb,
                            scrubberPreview,
                            results.size()
                    ));
                });
            });
        };
        searchButton.setOnClickListener(v -> runSearch.run());
        String safeInitialQuery = initialQuery == null ? "" : initialQuery.trim();
        if (!safeInitialQuery.isEmpty()) {
            queryInput.setText(safeInitialQuery);
            queryInput.setSelection(queryInput.getText().length());
        }
        dialogSupport.showStyledDialog(dialog);
        if (autoRun && !safeInitialQuery.isEmpty()) {
            resultCount.post(runSearch);
        }
    }

    public void showRulesDialog() {
        showRulesDialog("");
    }

    public void showRulesDialog(String initialPattern) {
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
        String safeInitialPattern = initialPattern == null ? "" : initialPattern;
        if (!safeInitialPattern.isEmpty()) {
            patternInput.setText(safeInitialPattern);
            patternInput.setSelection(patternInput.getText().length());
            replacementInput.requestFocus();
        }
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

    private void attachListScrubber(
            ListView listView,
            View body,
            View scrubberHost,
            View scrubberTrack,
            View scrubberThumb,
            TextView scrubberPreview,
            ScrubberItems items
    ) {
        if (listView == null || body == null || scrubberHost == null
                || scrubberTrack == null || scrubberThumb == null || scrubberPreview == null
                || items == null) {
            return;
        }
        final boolean[] scrubberDragging = new boolean[]{false};
        final int[] lastDraggedIndex = new int[]{-1};
        listView.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
            }

            @Override
            public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
                if (scrubberDragging[0]) {
                    return;
                }
                refreshListScrubber(
                        listView,
                        scrubberHost,
                        scrubberTrack,
                        scrubberThumb,
                        scrubberPreview,
                        items.size()
                );
            }
        });
        scrubberHost.setOnTouchListener((view, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_UP) {
                scrubberDragging[0] = false;
                lastDraggedIndex[0] = -1;
                scrubberPreview.setVisibility(View.INVISIBLE);
                view.getParent().requestDisallowInterceptTouchEvent(false);
                view.post(() -> refreshListScrubber(
                        listView,
                        scrubberHost,
                        scrubberTrack,
                        scrubberThumb,
                        scrubberPreview,
                        items.size()
                ));
                return true;
            }
            if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_MOVE) {
                return false;
            }
            int itemCount = items.size();
            if (itemCount <= 1) {
                scrubberPreview.setVisibility(View.INVISIBLE);
                return false;
            }
            scrubberDragging[0] = true;
            view.getParent().requestDisallowInterceptTouchEvent(true);
            float fraction = touchFractionForScrubber(event, scrubberTrack);
            int index = fractionToItemIndex(fraction, itemCount);
            positionScrubberThumb(scrubberTrack, scrubberThumb, fraction);
            if (index != lastDraggedIndex[0]) {
                lastDraggedIndex[0] = index;
                listView.setSelectionFromTop(index, 0);
            }
            scrubberPreview.setText(items.previewText(index));
            scrubberPreview.setVisibility(View.VISIBLE);
            positionScrubberPreview(scrubberPreview, body, scrubberTrack, fraction);
            return true;
        });
        refreshListScrubber(listView, scrubberHost, scrubberTrack, scrubberThumb, scrubberPreview, items.size());
    }

    private void refreshListScrubber(
            ListView listView,
            View scrubberHost,
            View scrubberTrack,
            View scrubberThumb,
            TextView scrubberPreview,
            int itemCount
    ) {
        if (scrubberHost == null || scrubberTrack == null || scrubberThumb == null || scrubberPreview == null) {
            return;
        }
        if (itemCount <= 1) {
            scrubberHost.setVisibility(View.INVISIBLE);
            scrubberPreview.setVisibility(View.INVISIBLE);
            positionScrubberThumb(scrubberTrack, scrubberThumb, 0f);
            return;
        }
        scrubberHost.setVisibility(View.VISIBLE);
        positionScrubberThumb(scrubberTrack, scrubberThumb, firstVisibleFraction(listView, itemCount));
    }

    private void positionScrubberPreview(TextView preview, View body, View scrubberTrack, float fraction) {
        if (body.getHeight() <= 0) {
            return;
        }
        int widthSpec = View.MeasureSpec.makeMeasureSpec(body.getWidth(), View.MeasureSpec.AT_MOST);
        int heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        preview.measure(widthSpec, heightSpec);
        float anchorY = scrubberTrack.getY() + (clampFraction(fraction) * Math.max(scrubberTrack.getHeight(), 1));
        float targetY = anchorY - (preview.getMeasuredHeight() / 2f);
        float maxY = Math.max(body.getHeight() - preview.getMeasuredHeight(), 0);
        preview.setY(Math.max(0f, Math.min(targetY, maxY)));
    }

    private void positionScrubberThumb(View scrubberTrack, View scrubberThumb, float fraction) {
        scrubberTrack.post(() -> {
            float clampedFraction = clampFraction(fraction);
            float trackTop = scrubberTrack.getY();
            float travel = Math.max(scrubberTrack.getHeight() - scrubberThumb.getHeight(), 0);
            scrubberThumb.setY(trackTop + (travel * clampedFraction));
        });
    }

    private float touchFractionForScrubber(MotionEvent event, View scrubberTrack) {
        float trackTop = scrubberTrack.getY();
        float trackHeight = Math.max(scrubberTrack.getHeight(), 1);
        return clampFraction((event.getY() - trackTop) / trackHeight);
    }

    private float firstVisibleFraction(ListView listView, int itemCount) {
        if (itemCount <= 1) {
            return 0f;
        }
        View firstChild = listView.getChildAt(0);
        float firstRowOffset = 0f;
        if (firstChild != null && firstChild.getHeight() > 0) {
            firstRowOffset = -firstChild.getTop() / (float) firstChild.getHeight();
        }
        return clampFraction((listView.getFirstVisiblePosition() + firstRowOffset) / (itemCount - 1f));
    }

    private float fractionForIndex(int index, int itemCount) {
        if (itemCount <= 1) {
            return 0f;
        }
        return clampFraction(index / (float) (itemCount - 1));
    }

    private int fractionToItemIndex(float fraction, int itemCount) {
        if (itemCount <= 1) {
            return 0;
        }
        return ui.clamp(Math.round(clampFraction(fraction) * (itemCount - 1)), 0, itemCount - 1);
    }

    private float clampFraction(float fraction) {
        return Math.max(0f, Math.min(1f, fraction));
    }

    private String searchScrubberPreviewText(SearchResult result) {
        if (result == null) {
            return "";
        }
        return String.format(
                Locale.SIMPLIFIED_CHINESE,
                "%03d  %s\n%s",
                result.chapterIndex + 1,
                result.chapterTitle,
                result.snippet
        );
    }

    private interface ScrubberItems {
        int size();

        CharSequence previewText(int index);
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

    private final class TocListAdapter extends ArrayAdapter<String> {
        private final int currentChapterIndex;

        private TocListAdapter(List<String> items, int currentChapterIndex) {
            super(activity, R.layout.item_toc_list_row, R.id.toc_row_text, items);
            this.currentChapterIndex = currentChapterIndex;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;
            if (view == null) {
                view = LayoutInflater.from(getContext()).inflate(R.layout.item_toc_list_row, parent, false);
            }
            View rowContent = view.findViewById(R.id.toc_row_content);
            View indicator = view.findViewById(R.id.toc_row_indicator);
            TextView textView = view.findViewById(R.id.toc_row_text);
            textView.setText(getItem(position));
            boolean isCurrent = position == currentChapterIndex;
            rowContent.setBackgroundResource(isCurrent ? R.drawable.bg_toc_row_current : 0);
            indicator.setVisibility(isCurrent ? View.VISIBLE : View.INVISIBLE);
            textView.setTextColor(ui.themeColor(isCurrent ? R.color.primary : R.color.on_surface));
            textView.setTypeface(Typeface.DEFAULT, isCurrent ? Typeface.BOLD : Typeface.NORMAL);
            textView.setGravity(Gravity.CENTER_VERTICAL);
            return view;
        }
    }
}
