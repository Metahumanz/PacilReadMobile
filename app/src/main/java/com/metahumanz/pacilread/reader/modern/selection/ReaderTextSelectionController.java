package com.metahumanz.pacilread.reader.modern.selection;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.PopupWindow;

import com.metahumanz.pacilread.R;
import com.metahumanz.pacilread.reader.JustifiedPageTextView;
import com.metahumanz.pacilread.reader.PageSlice;
import com.metahumanz.pacilread.reader.modern.ModernReaderActivity;
import com.metahumanz.pacilread.reader.modern.ReaderRuntime;
import com.metahumanz.pacilread.reader.modern.ReaderSessionState;
import com.metahumanz.pacilread.reader.modern.ReaderUiUtils;
import com.metahumanz.pacilread.reader.modern.ReaderViewRefs;
import com.metahumanz.pacilread.reader.modern.content.ReaderContentController;
import com.metahumanz.pacilread.reader.modern.dialog.ReaderLibraryDialogs;
import com.metahumanz.pacilread.reader.modern.tts.ReaderTtsController;
import com.metahumanz.pacilread.ui.PredictiveBackScaleController;

import java.text.BreakIterator;
import java.util.List;
import java.util.Locale;

public final class ReaderTextSelectionController {
    private static final long LONG_PRESS_TIMEOUT_MS = 600L;
    private static final int HANDLE_NONE = 0;
    private static final int HANDLE_START = 1;
    private static final int HANDLE_END = 2;

    private final ModernReaderActivity activity;
    private final ReaderRuntime runtime;
    private final ReaderViewRefs views;
    private final ReaderSessionState state;
    private final ReaderUiUtils ui;
    private final ReaderContentController content;
    private final int touchSlop;
    private final Runnable longPressRunnable = this::beginSelectionFromPending;

    private ReaderLibraryDialogs libraryDialogs;
    private ReaderTtsController tts;
    private PopupWindow popupWindow;
    private boolean popupAnimatingDismiss;
    private Target pendingTarget;
    private Target activeTarget;
    private boolean longPressPending;
    private boolean selectionActive;
    private float downRawX;
    private float downRawY;
    private float lastRawX;
    private float lastRawY;
    private int anchorStart;
    private int anchorEnd;
    private int selectionStart;
    private int selectionEnd;
    private int draggingHandle = HANDLE_NONE;

    public ReaderTextSelectionController(
            ModernReaderActivity activity,
            ReaderRuntime runtime,
            ReaderViewRefs views,
            ReaderSessionState state,
            ReaderUiUtils ui,
            ReaderContentController content
    ) {
        this.activity = activity;
        this.runtime = runtime;
        this.views = views;
        this.state = state;
        this.ui = ui;
        this.content = content;
        this.touchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();
    }

    public void attachControllers(ReaderLibraryDialogs libraryDialogs, ReaderTtsController tts) {
        this.libraryDialogs = libraryDialogs;
        this.tts = tts;
    }

    public boolean handleTouchEvent(MotionEvent event) {
        if (event == null) {
            return false;
        }
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN && selectionActive && !isInsideActiveText(event)) {
            clearSelection();
            return true;
        }
        if (state.controlsVisible || state.isAnimating || state.interactivePaging || state.chapters.isEmpty()) {
            cancelPendingLongPress();
            return false;
        }
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                return handleDown(event);
            case MotionEvent.ACTION_MOVE:
                return handleMove(event);
            case MotionEvent.ACTION_UP:
                return handleUp();
            case MotionEvent.ACTION_CANCEL:
                cancelPendingLongPress();
                return selectionActive;
            default:
                return selectionActive;
        }
    }

    public boolean hasSelection() {
        return selectionActive;
    }

    public void clearSelection() {
        cancelPendingLongPress();
        draggingHandle = HANDLE_NONE;
        if (activeTarget != null && activeTarget.textView != null) {
            activeTarget.textView.clearSelectionHighlight();
        }
        activeTarget = null;
        selectionActive = false;
        state.pagingGestureCandidate = false;
        selectionStart = -1;
        selectionEnd = -1;
        anchorStart = -1;
        anchorEnd = -1;
        dismissPopup();
    }

    private boolean handleDown(MotionEvent event) {
        if (selectionActive) {
            lastRawX = event.getRawX();
            lastRawY = event.getRawY();
            state.pagingGestureCandidate = false;

            int handle = hitTestHandle(event);
            if (handle != HANDLE_NONE) {
                draggingHandle = handle;
                dismissPopup();
                return true;
            }

            if (isInsideActiveText(event)) {
                int tapOffset = bodyOffsetForTouch(activeTarget, event.getRawX(), event.getRawY(), false);
                if (tapOffset >= selectionStart && tapOffset < selectionEnd) {
                    dismissPopup();
                } else {
                    clearSelection();
                }
                return true;
            }

            clearSelection();
            return true;
        }
        Target target = findTextTarget(event);
        if (target == null) {
            return false;
        }
        pendingTarget = target;
        longPressPending = true;
        downRawX = event.getRawX();
        downRawY = event.getRawY();
        lastRawX = downRawX;
        lastRawY = downRawY;
        target.textView.postDelayed(longPressRunnable, LONG_PRESS_TIMEOUT_MS);
        return false;
    }

    private boolean handleMove(MotionEvent event) {
        lastRawX = event.getRawX();
        lastRawY = event.getRawY();
        if (longPressPending) {
            float dx = event.getRawX() - downRawX;
            float dy = event.getRawY() - downRawY;
            if (dx * dx + dy * dy > touchSlop * touchSlop) {
                cancelPendingLongPress();
            }
            return false;
        }
        if (!selectionActive) {
            return false;
        }
        state.pagingGestureCandidate = false;
        if (draggingHandle != HANDLE_NONE) {
            updateHandleDrag(event.getRawX(), event.getRawY());
            return true;
        }
        updateSelectionFromTouch(event.getRawX(), event.getRawY());
        return true;
    }

    private boolean handleUp() {
        if (longPressPending) {
            cancelPendingLongPress();
            return false;
        }
        if (draggingHandle != HANDLE_NONE) {
            draggingHandle = HANDLE_NONE;
            showOrUpdatePopup();
            return true;
        }
        if (!selectionActive) {
            return false;
        }
        state.pagingGestureCandidate = false;
        showOrUpdatePopup();
        return true;
    }

    private void beginSelectionFromPending() {
        if (!longPressPending || pendingTarget == null) {
            return;
        }
        Target target = pendingTarget;
        longPressPending = false;
        pendingTarget = null;
        int localOffset = bodyOffsetForTouch(target, downRawX, downRawY, false);
        if (localOffset < 0) {
            return;
        }
        WordRange range = wordRangeFor(target, localOffset);
        if (range == null) {
            return;
        }
        activeTarget = target;
        selectionActive = true;
        state.pagingGestureCandidate = false;
        anchorStart = range.start;
        anchorEnd = range.end;
        applySelectionRange(range.start, range.end);
        target.textView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        activity.markReadingActivity();
        showOrUpdatePopup();
    }

    private void updateSelectionFromTouch(float rawX, float rawY) {
        if (activeTarget == null) {
            return;
        }
        int focus = bodyOffsetForTouch(activeTarget, rawX, rawY, true);
        if (focus < 0) {
            return;
        }
        int start;
        int end;
        if (focus < anchorStart) {
            start = focus;
            end = anchorEnd;
        } else if (focus > anchorEnd) {
            start = anchorStart;
            end = focus;
        } else {
            start = anchorStart;
            end = anchorEnd;
        }
        int bodyStart = Math.max(activeTarget.slice.bodyStartInSlice, 0);
        int bodyEnd = Math.max(bodyStart, activeTarget.slice.bodyEndInSlice);
        if (end <= start) {
            end = Math.min(bodyEnd, start + 1);
        }
        applySelectionRange(start, end);
        showOrUpdatePopup();
    }

    private int hitTestHandle(MotionEvent event) {
        if (activeTarget == null || activeTarget.textView == null) {
            return HANDLE_NONE;
        }
        RectF startBounds = activeTarget.textView.getSelectionHandleScreenBounds(selectionStart);
        if (startBounds != null && startBounds.contains(event.getRawX(), event.getRawY())) {
            return HANDLE_START;
        }
        RectF endBounds = activeTarget.textView.getSelectionHandleScreenBounds(selectionEnd);
        if (endBounds != null && endBounds.contains(event.getRawX(), event.getRawY())) {
            return HANDLE_END;
        }
        return HANDLE_NONE;
    }

    private void updateHandleDrag(float rawX, float rawY) {
        if (activeTarget == null) return;
        int focus = bodyOffsetForTouch(activeTarget, rawX, rawY, true);
        if (focus < 0) return;
        int bodyStart = Math.max(activeTarget.slice.bodyStartInSlice, 0);
        int bodyEnd = Math.max(bodyStart, activeTarget.slice.bodyEndInSlice);
        if (draggingHandle == HANDLE_START) {
            int newStart = ui.clamp(focus, bodyStart, selectionEnd - 1);
            applySelectionRange(newStart, selectionEnd);
            anchorStart = newStart;
            anchorEnd = selectionEnd;
        } else {
            int newEnd = ui.clamp(focus, selectionStart + 1, bodyEnd);
            applySelectionRange(selectionStart, newEnd);
            anchorStart = selectionStart;
            anchorEnd = newEnd;
        }
    }

    private void applySelectionRange(int start, int end) {
        if (activeTarget == null || activeTarget.textView == null) {
            return;
        }
        int bodyStart = Math.max(activeTarget.slice.bodyStartInSlice, 0);
        int bodyEnd = Math.max(bodyStart, activeTarget.slice.bodyEndInSlice);
        selectionStart = ui.clamp(start, bodyStart, bodyEnd);
        selectionEnd = ui.clamp(end, selectionStart, bodyEnd);
        activeTarget.textView.setSelectionHighlightRange(selectionStart, selectionEnd);
    }

    private Target findTextTarget(MotionEvent event) {
        if (event == null || state.currentChapterIndex < 0 || state.currentChapterIndex >= state.chapters.size()) {
            return null;
        }
        List<PageSlice> pages = content.getPagesForChapter(state.currentChapterIndex);
        if (pages == null || pages.isEmpty()) {
            return null;
        }
        if (isInsideView(event, views.pageBodyCurrent)) {
            int pageIndex = ui.clamp(state.currentPageIndex, 0, pages.size() - 1);
            PageSlice slice = pages.get(pageIndex);
            return slice.hasBodyText()
                    ? new Target(views.pageBodyCurrent, state.currentChapterIndex, pageIndex, slice)
                    : null;
        }
        if (isInsideView(event, views.pageBodyCurrentRight)) {
            int pageIndex = state.currentPageIndex + 1;
            if (pageIndex >= 0 && pageIndex < pages.size()) {
                PageSlice slice = pages.get(pageIndex);
                return slice.hasBodyText()
                        ? new Target(views.pageBodyCurrentRight, state.currentChapterIndex, pageIndex, slice)
                        : null;
            }
        }
        return null;
    }

    private boolean isInsideActiveText(MotionEvent event) {
        return activeTarget != null && isInsideView(event, activeTarget.textView);
    }

    private boolean isInsideView(MotionEvent event, View view) {
        if (event == null || view == null || view.getVisibility() != View.VISIBLE || view.getWidth() <= 0 || view.getHeight() <= 0) {
            return false;
        }
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        float rawX = event.getRawX();
        float rawY = event.getRawY();
        return rawX >= location[0]
                && rawX <= location[0] + view.getWidth()
                && rawY >= location[1]
                && rawY <= location[1] + view.getHeight();
    }

    private int bodyOffsetForTouch(Target target, float rawX, float rawY, boolean clampToBody) {
        if (target == null || target.textView == null || target.slice == null || !target.slice.hasBodyText()) {
            return -1;
        }
        int[] location = new int[2];
        target.textView.getLocationOnScreen(location);
        int localOffset = target.textView.offsetForTouch(rawX - location[0], rawY - location[1]);
        int bodyStart = Math.max(target.slice.bodyStartInSlice, 0);
        int bodyEnd = Math.max(bodyStart, target.slice.bodyEndInSlice);
        if (bodyEnd <= bodyStart) {
            return -1;
        }
        if (clampToBody) {
            return ui.clamp(localOffset, bodyStart, bodyEnd);
        }
        if (localOffset < bodyStart || localOffset >= bodyEnd) {
            return -1;
        }
        return localOffset;
    }

    private WordRange wordRangeFor(Target target, int localOffset) {
        CharSequence text = target.textView.getText();
        if (text == null || text.length() == 0) {
            return null;
        }
        int bodyStart = Math.max(target.slice.bodyStartInSlice, 0);
        int bodyEnd = Math.max(bodyStart, target.slice.bodyEndInSlice);
        if (bodyEnd <= bodyStart) {
            return null;
        }
        String source = text.toString();
        int offset = ui.clamp(localOffset, bodyStart, bodyEnd - 1);
        if (Character.isWhitespace(source.charAt(offset))) {
            if (offset + 1 < bodyEnd && !Character.isWhitespace(source.charAt(offset + 1))) {
                offset++;
            } else if (offset > bodyStart && !Character.isWhitespace(source.charAt(offset - 1))) {
                offset--;
            }
        }
        BreakIterator iterator = BreakIterator.getWordInstance(Locale.getDefault());
        iterator.setText(source);
        int start = iterator.preceding(offset + 1);
        int end = iterator.following(offset);
        if (start == BreakIterator.DONE || end == BreakIterator.DONE || start >= end) {
            return fallbackRange(source, offset, bodyStart, bodyEnd);
        }
        start = ui.clamp(start, bodyStart, bodyEnd);
        end = ui.clamp(end, bodyStart, bodyEnd);
        while (start < end && Character.isWhitespace(source.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(source.charAt(end - 1))) {
            end--;
        }
        if (start >= end) {
            return fallbackRange(source, offset, bodyStart, bodyEnd);
        }
        return new WordRange(start, end);
    }

    private WordRange fallbackRange(String source, int offset, int bodyStart, int bodyEnd) {
        int safeOffset = ui.clamp(offset, bodyStart, bodyEnd - 1);
        int end = safeOffset + Character.charCount(source.codePointAt(safeOffset));
        return new WordRange(safeOffset, ui.clamp(end, safeOffset + 1, bodyEnd));
    }

    private void showOrUpdatePopup() {
        if (!selectionActive || selectedText().isEmpty()) {
            dismissPopup();
            return;
        }
        ensurePopupWindow();
        View root = views.readerRoot;
        if (root == null || root.getWidth() <= 0 || root.getHeight() <= 0) {
            return;
        }
        boolean wasShowing = popupWindow.isShowing();
        boolean shouldAnimateOpen = !wasShowing || popupAnimatingDismiss;
        if (!wasShowing) {
            popupAnimatingDismiss = false;
            popupWindow.showAtLocation(root, Gravity.NO_GRAVITY, 0, 0);
        }
        View popupContent = popupWindow.getContentView();
        if (shouldAnimateOpen) {
            popupContent.animate().cancel();
            popupAnimatingDismiss = false;
        }
        popupContent.measure(
                View.MeasureSpec.makeMeasureSpec(root.getWidth(), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(root.getHeight(), View.MeasureSpec.AT_MOST)
        );
        int popupWidth = popupContent.getMeasuredWidth();
        int popupHeight = popupContent.getMeasuredHeight();
        int[] rootLocation = new int[2];
        root.getLocationOnScreen(rootLocation);
        int x = Math.round(lastRawX - rootLocation[0] - popupWidth / 2f);
        int y = Math.round(lastRawY - rootLocation[1] - popupHeight - ui.dp(18));
        x = ui.clamp(x, ui.dp(8), Math.max(ui.dp(8), root.getWidth() - popupWidth - ui.dp(8)));
        y = ui.clamp(y, ui.dp(8), Math.max(ui.dp(8), root.getHeight() - popupHeight - ui.dp(8)));
        popupWindow.update(x, y, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (shouldAnimateOpen) {
            popupContent.setPivotX(popupWidth / 2f);
            popupContent.setPivotY(popupHeight / 2f);
            popupContent.setScaleX(PredictiveBackScaleController.READER_MIN_SCALE);
            popupContent.setScaleY(PredictiveBackScaleController.READER_MIN_SCALE);
            popupContent.setAlpha(0f);
            popupContent.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(180)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
        }
    }

    private void ensurePopupWindow() {
        if (popupWindow != null) {
            return;
        }
        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(ui.dp(8), ui.dp(7), ui.dp(8), ui.dp(7));
        container.setBackgroundResource(R.drawable.bg_reader_menu_panel_solid);

        // Row 1: 复制 | 替换
        LinearLayout row1 = new LinearLayout(activity);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER);
        row1.addView(createActionButton("复制", this::copySelection, true));
        row1.addView(createActionButton("替换", this::replaceSelection, false));
        container.addView(row1);

        // Row 2: 搜索 | 朗读
        LinearLayout row2 = new LinearLayout(activity);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER);
        row2.addView(createActionButton("搜索", this::searchSelection, true));
        row2.addView(createActionButton("朗读", this::speakSelection, false));
        LinearLayout.LayoutParams row2Params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        row2Params.topMargin = ui.dp(4);
        container.addView(row2, row2Params);

        popupWindow = new PopupWindow(
                container,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                false
        );
        popupWindow.setOutsideTouchable(false);
        popupWindow.setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
        popupWindow.setClippingEnabled(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            popupWindow.setElevation(ui.dp(8));
        }
    }

    private Button createActionButton(String text, Runnable action, boolean marginEnd) {
        Button button = new Button(activity);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(12f);
        button.setMinWidth(0);
        button.setMinHeight(ui.dp(38));
        button.setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8));
        button.setBackgroundResource(R.drawable.bg_reader_menu_button_solid);
        button.setTextColor(ui.themeColor(R.color.on_surface));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        );
        if (marginEnd) {
            params.setMargins(0, 0, ui.dp(6), 0);
        }
        button.setLayoutParams(params);
        button.setOnClickListener(v -> {
            dismissPopup();
            if (action != null) {
                action.run();
            }
        });
        return button;
    }

    private void copySelection() {
        String text = selectedText();
        if (text.isEmpty()) {
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("选中文字", text));
        }
        ui.showToast("已复制");
        clearSelection();
    }

    private void replaceSelection() {
        String text = selectedText();
        if (text.isEmpty() || libraryDialogs == null) {
            return;
        }
        clearSelection();
        libraryDialogs.showRulesDialog(text);
    }

    private void searchSelection() {
        String text = selectedText();
        if (text.isEmpty() || libraryDialogs == null) {
            return;
        }
        clearSelection();
        libraryDialogs.showSearchDialog(text, true);
    }

    private void speakSelection() {
        if (activeTarget == null || tts == null) {
            return;
        }
        int chapterIndex = activeTarget.chapterIndex;
        int offset = selectedChapterOffset();
        clearSelection();
        tts.startTtsFrom(chapterIndex, offset);
    }

    private String selectedText() {
        if (!selectionActive || activeTarget == null || activeTarget.textView == null || selectionEnd <= selectionStart) {
            return "";
        }
        CharSequence text = activeTarget.textView.getText();
        if (text == null) {
            return "";
        }
        int safeStart = ui.clamp(selectionStart, 0, text.length());
        int safeEnd = ui.clamp(selectionEnd, safeStart, text.length());
        return text.subSequence(safeStart, safeEnd).toString();
    }

    private int selectedChapterOffset() {
        if (activeTarget == null || activeTarget.slice == null) {
            return 0;
        }
        int bodyStart = Math.max(activeTarget.slice.bodyStartInSlice, 0);
        return activeTarget.slice.start + Math.max(0, selectionStart - bodyStart);
    }

    private void cancelPendingLongPress() {
        if (pendingTarget != null && pendingTarget.textView != null) {
            pendingTarget.textView.removeCallbacks(longPressRunnable);
        }
        pendingTarget = null;
        longPressPending = false;
    }

    private void dismissPopup() {
        if (popupWindow != null && popupWindow.isShowing()) {
            if (popupAnimatingDismiss) {
                return;
            }
            popupAnimatingDismiss = true;
            View popupContent = popupWindow.getContentView();
            popupContent.animate().cancel();
            popupContent.animate()
                    .scaleX(PredictiveBackScaleController.READER_MIN_SCALE)
                    .scaleY(PredictiveBackScaleController.READER_MIN_SCALE)
                    .alpha(0f)
                    .setDuration(130)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator())
                    .withEndAction(() -> {
                        popupAnimatingDismiss = false;
                        if (popupWindow != null && popupWindow.isShowing()) {
                            popupWindow.dismiss();
                        }
                    })
                    .start();
        }
    }

    private static final class Target {
        final JustifiedPageTextView textView;
        final int chapterIndex;
        final int pageIndex;
        final PageSlice slice;

        private Target(JustifiedPageTextView textView, int chapterIndex, int pageIndex, PageSlice slice) {
            this.textView = textView;
            this.chapterIndex = chapterIndex;
            this.pageIndex = pageIndex;
            this.slice = slice;
        }
    }

    private static final class WordRange {
        final int start;
        final int end;

        private WordRange(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }
}
