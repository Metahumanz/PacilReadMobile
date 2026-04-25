package com.metahumanz.pacilread.reader.modern.dialog;

import android.app.AlertDialog;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.os.Build;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
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
        final boolean[] scrubberDragging = new boolean[]{false};
        final int[] lastDraggedIndex = new int[]{-1};
        AlertDialog dialog = new AlertDialog.Builder(activity).setView(contentView).create();
        contentView.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            int leftInset;
            int topInset;
            int rightInset;
            int bottomInset;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Insets systemBars = windowInsets.getInsets(WindowInsets.Type.systemBars());
                Insets cutout = windowInsets.getInsets(WindowInsets.Type.displayCutout());
                boolean landscape = view.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
                leftInset = landscape ? systemBars.left : Math.max(systemBars.left, cutout.left);
                topInset = Math.max(systemBars.top, cutout.top);
                rightInset = landscape ? systemBars.right : Math.max(systemBars.right, cutout.right);
                bottomInset = Math.max(systemBars.bottom, cutout.bottom);
            } else {
                leftInset = windowInsets.getSystemWindowInsetLeft();
                topInset = windowInsets.getSystemWindowInsetTop();
                rightInset = windowInsets.getSystemWindowInsetRight();
                bottomInset = windowInsets.getSystemWindowInsetBottom();
            }
            contentContainer.setPadding(
                    ui.dp(20) + leftInset,
                    ui.dp(18) + topInset,
                    ui.dp(16) + rightInset,
                    ui.dp(16) + bottomInset
            );
            return windowInsets;
        });
        listView.setOnItemClickListener((parent, view, position, id) -> {
            dialog.dismiss();
            navigation.openChapterFromStart(position, true, position >= state.currentChapterIndex ? 1 : -1);
        });
        listView.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
            }

            @Override
            public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
                if (scrubberDragging[0]) {
                    return;
                }
                float fraction = firstVisibleFraction(listView, items.size());
                positionScrubberThumb(scrubberTrack, scrubberThumb, fraction);
            }
        });
        scrubberHost.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_CANCEL || event.getActionMasked() == MotionEvent.ACTION_UP) {
                scrubberDragging[0] = false;
                lastDraggedIndex[0] = -1;
                scrubberPreview.setVisibility(View.INVISIBLE);
                view.getParent().requestDisallowInterceptTouchEvent(false);
                view.post(() -> positionScrubberThumb(scrubberTrack, scrubberThumb, firstVisibleFraction(listView, items.size())));
                return true;
            }
            if (event.getActionMasked() != MotionEvent.ACTION_DOWN && event.getActionMasked() != MotionEvent.ACTION_MOVE) {
                return false;
            }
            scrubberDragging[0] = true;
            view.getParent().requestDisallowInterceptTouchEvent(true);
            float fraction = touchFractionForScrubber(event, scrubberTrack);
            int index = fractionToChapterIndex(fraction, items.size());
            positionScrubberThumb(scrubberTrack, scrubberThumb, fraction);
            if (index != lastDraggedIndex[0]) {
                lastDraggedIndex[0] = index;
                listView.setSelectionFromTop(index, 0);
            }
            scrubberPreview.setText(items.get(index));
            scrubberPreview.setVisibility(View.VISIBLE);
            positionScrubberPreview(scrubberPreview, tocBody, scrubberTrack, fraction);
            return true;
        });
        dialogSupport.showImmersiveFullscreenDialog(dialog, state.controlsVisible);
        contentView.requestApplyInsets();
        listView.post(() -> {
            listView.setSelectionFromTop(state.currentChapterIndex, 0);
            positionScrubberThumb(scrubberTrack, scrubberThumb, fractionForIndex(state.currentChapterIndex, items.size()));
        });
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

    private int fractionToChapterIndex(float fraction, int itemCount) {
        if (itemCount <= 1) {
            return 0;
        }
        return ui.clamp(Math.round(clampFraction(fraction) * (itemCount - 1)), 0, itemCount - 1);
    }

    private float clampFraction(float fraction) {
        return Math.max(0f, Math.min(1f, fraction));
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
