package com.metahumanz.pacilread.reader.modern.ui;

import android.graphics.Color;
import android.graphics.Insets;
import android.os.Build;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.metahumanz.pacilread.R;
import com.metahumanz.pacilread.reader.PageSlice;
import com.metahumanz.pacilread.reader.modern.ModernReaderActivity;
import com.metahumanz.pacilread.reader.modern.ReaderRuntime;
import com.metahumanz.pacilread.reader.modern.ReaderSessionState;
import com.metahumanz.pacilread.reader.modern.ReaderUiUtils;
import com.metahumanz.pacilread.reader.modern.ReaderViewRefs;
import com.metahumanz.pacilread.reader.modern.content.ReaderContentController;
import com.metahumanz.pacilread.reader.modern.paging.ReaderPagingAnimator;
import com.metahumanz.pacilread.theme.ThemeModeHelper;
import com.metahumanz.pacilread.ui.GlassUiHelper;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class ReaderChromeController {
    private static final long MENU_AUTO_HIDE_DELAY_MS = 2500L;

    private final ModernReaderActivity activity;
    private final ReaderRuntime runtime;
    private final ReaderViewRefs views;
    private final ReaderSessionState state;
    private final ReaderUiUtils ui;
    private final Runnable autoHideRunnable = () -> setControlsVisible(false);

    private ReaderContentController content;
    private ReaderPagingAnimator paging;
    private com.metahumanz.pacilread.reader.modern.playback.ReaderAutoPageController autoPage;
    private com.metahumanz.pacilread.reader.modern.tts.ReaderTtsController tts;

    public ReaderChromeController(
            ModernReaderActivity activity,
            ReaderRuntime runtime,
            ReaderViewRefs views,
            ReaderSessionState state,
            ReaderUiUtils ui
    ) {
        this.activity = activity;
        this.runtime = runtime;
        this.views = views;
        this.state = state;
        this.ui = ui;
    }

    public void attachControllers(
            ReaderContentController content,
            ReaderPagingAnimator paging,
            com.metahumanz.pacilread.reader.modern.playback.ReaderAutoPageController autoPage,
            com.metahumanz.pacilread.reader.modern.tts.ReaderTtsController tts
    ) {
        this.content = content;
        this.paging = paging;
        this.autoPage = autoPage;
        this.tts = tts;
    }

    public void configureReaderWindow() {
        Window window = activity.getWindow();
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

    public void applyEdgeToEdgeInsets() {
        views.readerRoot.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Insets insets = windowInsets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                state.systemInsetLeft = insets.left;
                state.systemInsetTop = insets.top;
                state.systemInsetRight = insets.right;
                state.systemInsetBottom = insets.bottom;
            } else {
                state.systemInsetLeft = windowInsets.getSystemWindowInsetLeft();
                state.systemInsetTop = windowInsets.getSystemWindowInsetTop();
                state.systemInsetRight = windowInsets.getSystemWindowInsetRight();
                state.systemInsetBottom = windowInsets.getSystemWindowInsetBottom();
            }
            updateReaderLayoutInsets();
            return windowInsets;
        });
        views.readerRoot.requestApplyInsets();
    }

    public void updateReaderLayoutInsets() {
        int bottomPanelBottomMargin = ui.dp(8) + state.systemInsetBottom;
        int menuBottomHeight = views.menuBottomPanel == null ? 0 : views.menuBottomPanel.getHeight();
        int infoBottomMargin = menuBottomHeight > 0
                ? bottomPanelBottomMargin + menuBottomHeight + ui.dp(10)
                : ui.dp(148) + state.systemInsetBottom;
        views.hudTopContainer.setPadding(
                ui.dp(12) + state.systemInsetLeft,
                ui.dp(runtime.settingsStore.getHudVerticalMarginDp()) + state.systemInsetTop,
                ui.dp(12) + state.systemInsetRight,
                0
        );
        views.hudBottomContainer.setPadding(
                ui.dp(12) + state.systemInsetLeft,
                0,
                ui.dp(12) + state.systemInsetRight,
                ui.dp(runtime.settingsStore.getHudVerticalMarginDp()) + state.systemInsetBottom
        );
        views.pageStage.setPadding(0, 0, 0, 0);
        updateFrameLayoutMargins(
                views.menuTopPanel,
                ui.dp(10) + state.systemInsetLeft,
                ui.dp(10) + state.systemInsetTop,
                ui.dp(10) + state.systemInsetRight,
                0
        );
        updateFrameLayoutMargins(
                views.menuInfoPanel,
                ui.dp(10) + state.systemInsetLeft,
                0,
                ui.dp(10) + state.systemInsetRight,
                infoBottomMargin
        );
        updateFrameLayoutMargins(
                views.menuBottomPanel,
                ui.dp(10) + state.systemInsetLeft,
                0,
                ui.dp(10) + state.systemInsetRight,
                bottomPanelBottomMargin
        );
        paging.invalidatePreparedPagingSnapshots();
        paging.schedulePagingSnapshotWarmup();
    }

    public void updateUiAfterPageChange() {
        if (state.book == null || state.chapters.isEmpty()) {
            return;
        }
        List<PageSlice> pages = content.getPagesForChapter(state.currentChapterIndex);
        com.metahumanz.pacilread.model.ChapterRecord chapter = state.chapters.get(state.currentChapterIndex);
        int safePageCount = Math.max(pages.size(), 1);
        boolean statsEnabled = runtime.settingsStore.isReadingTimeTrackingEnabled();
        views.readerTitle.setText(state.book.title == null || state.book.title.isBlank() ? "未命名书籍" : state.book.title);
        views.readerTitle.setEnabled(statsEnabled);
        views.readerTitle.setClickable(statsEnabled);
        views.readerTitle.setAlpha(statsEnabled ? 1f : 0.9f);
        views.chapterMeta.setText(String.format(
                Locale.SIMPLIFIED_CHINESE,
                "第 %d/%d 章 · %s",
                state.currentChapterIndex + 1,
                state.chapters.size(),
                chapter.title
        ));
        if ("book".equals(runtime.settingsStore.getReaderSliderMode())) {
            views.pageMeta.setText(String.format(Locale.SIMPLIFIED_CHINESE, "第 %d/%d 页 · 全书章节", state.currentPageIndex + 1, safePageCount));
            views.progressSeekBar.setMax(Math.max(state.chapters.size() - 1, 0));
            views.progressSeekBar.setProgress(state.currentChapterIndex);
        } else {
            views.pageMeta.setText(String.format(Locale.SIMPLIFIED_CHINESE, "第 %d/%d 页 · 本章页数", state.currentPageIndex + 1, safePageCount));
            views.progressSeekBar.setMax(Math.max(safePageCount - 1, 0));
            views.progressSeekBar.setProgress(state.currentPageIndex);
        }
        int percent = Math.round(((state.currentChapterIndex + (state.currentPageIndex / (float) safePageCount)) / Math.max(state.chapters.size(), 1)) * 100f);
        views.readerProgress.setText(percent + "%");
        updateReaderHud();
        styleReaderMenuButton(views.ttsButton, tts != null && tts.isActive());
        styleReaderMenuButton(views.autoPageButton, autoPage != null && autoPage.isActive());
        styleReaderMenuButton(views.themeToggleButton, ThemeModeHelper.isDark(activity.getResources()));
    }

    public int fetchCurrentProgressPercent() {
        if (state.book == null || state.chapters.isEmpty()) {
            return 0;
        }
        long totalLength = content.getTotalProcessedBookLength();
        if (totalLength <= 0L) {
            return 0;
        }
        long readLength = 0L;
        for (int i = 0; i < state.currentChapterIndex; i++) {
            readLength += content.getProcessedChapterLength(i);
        }
        int currentLength = content.getProcessedChapterLength(state.currentChapterIndex);
        readLength += Math.min(Math.max(content.currentCharOffset(), 0), Math.max(currentLength, 0));
        return (int) Math.round((readLength * 100d) / totalLength);
    }

    public void updateReaderHud() {
        if (state.book == null || state.chapters.isEmpty()) {
            return;
        }
        applyHudSlot(views.hudTopLeft, runtime.settingsStore.getHudTopLeft());
        applyHudSlot(views.hudTopCenter, runtime.settingsStore.getHudTopCenter());
        applyHudSlot(views.hudTopRight, runtime.settingsStore.getHudTopRight());
        applyHudSlot(views.hudBottomLeft, runtime.settingsStore.getHudBottomLeft());
        applyHudSlot(views.hudBottomCenter, runtime.settingsStore.getHudBottomCenter());
        applyHudSlot(views.hudBottomRight, runtime.settingsStore.getHudBottomRight());
    }

    public void toggleReaderUiTheme() {
        boolean darkUi = ThemeModeHelper.isDark(activity.getResources());
        runtime.settingsStore.setReaderUiThemeMode(darkUi ? "light" : "dark");
        activity.recreate();
    }

    public void styleReaderMenuButton(Button button, boolean active) {
        button.setBackgroundResource(active ? R.drawable.bg_reader_menu_button_active : R.drawable.bg_reader_menu_button_solid);
        button.setTag(R.id.tag_glass_background, Boolean.FALSE);
        button.setTextColor(activity.getColor(android.R.color.white));
    }

    public void applyGlassOpacity() {
        GlassUiHelper.applyToHierarchy(activity, views.menuTopPanel, runtime.settingsStore.getGlassOpacityPercent());
        GlassUiHelper.applyToHierarchy(activity, views.menuInfoPanel, runtime.settingsStore.getGlassOpacityPercent());
        GlassUiHelper.applyToHierarchy(activity, views.menuBottomPanel, runtime.settingsStore.getGlassOpacityPercent());
    }

    public void updateReaderThemeButtons(Button paper, Button forest, Button night, String current) {
        styleThemeButton(paper, "paper".equals(current));
        styleThemeButton(forest, "forest".equals(current));
        styleThemeButton(night, "night".equals(current));
    }

    public void styleThemeButton(Button button, boolean active) {
        button.setBackgroundResource(active ? R.drawable.bg_reader_menu_button_active : R.drawable.bg_reader_menu_button_solid);
        button.setTextColor(active ? Color.WHITE : Color.parseColor("#94A3B8"));
        button.setTag(R.id.tag_glass_background, !active);
    }

    public void setControlsVisible(boolean visible) {
        if (visible) {
            state.pendingTapPagingDelta = 0;
        }
        if (state.controlsVisible == visible) {
            if (visible) {
                scheduleAutoHide();
                views.readerRoot.post(this::updateReaderLayoutInsets);
            } else {
                runtime.mainHandler.removeCallbacks(autoHideRunnable);
            }
            updateSystemBarsVisibility(visible);
            return;
        }
        state.controlsVisible = visible;
        animatePanel(views.hudTopContainer, !visible, -ui.dp(12));
        animatePanel(views.hudBottomContainer, !visible, ui.dp(12));
        animatePanel(views.menuTopPanel, visible, -ui.dp(18));
        animatePanel(views.menuInfoPanel, visible, ui.dp(14));
        animatePanel(views.menuBottomPanel, visible, ui.dp(20));
        updateSystemBarsVisibility(visible);
        if (visible) {
            scheduleAutoHide();
            views.readerRoot.post(this::updateReaderLayoutInsets);
        } else {
            runtime.mainHandler.removeCallbacks(autoHideRunnable);
        }
    }

    public void scheduleAutoHide() {
        cancelAutoHide();
        if (!state.controlsVisible || !runtime.settingsStore.isReaderMenuAutoHideEnabled()) {
            return;
        }
        runtime.mainHandler.postDelayed(autoHideRunnable, MENU_AUTO_HIDE_DELAY_MS);
    }

    public void cancelAutoHide() {
        runtime.mainHandler.removeCallbacks(autoHideRunnable);
    }

    public void updateSystemBarsVisibility(boolean showSystemBars) {
        Window window = activity.getWindow();
        View decorView = window.getDecorView();
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (!ThemeModeHelper.isDark(activity.getResources())) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
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

    public boolean isInsideView(MotionEvent event, View view) {
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        float rawX = event.getRawX();
        float rawY = event.getRawY();
        return rawX >= location[0]
                && rawX <= location[0] + view.getWidth()
                && rawY >= location[1]
                && rawY <= location[1] + view.getHeight();
    }

    private void updateFrameLayoutMargins(View view, int left, int top, int right, int bottom) {
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) view.getLayoutParams();
        params.leftMargin = left;
        params.topMargin = top;
        params.rightMargin = right;
        params.bottomMargin = bottom;
        view.setLayoutParams(params);
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
        return trimToEmpty(state.book == null ? null : state.book.title);
    }

    private String currentChapterTitle() {
        if (state.currentChapterIndex < 0 || state.currentChapterIndex >= state.chapters.size()) {
            return "";
        }
        return trimToEmpty(state.chapters.get(state.currentChapterIndex).title);
    }

    private String currentTimeText() {
        return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
    }

    private String currentTitleOrChapterHudText() {
        String bookTitle = currentBookTitle();
        String chapterTitle = currentChapterTitle();
        if (state.currentPageIndex == 0) {
            return bookTitle.isEmpty() ? chapterTitle : bookTitle;
        }
        return chapterTitle.isEmpty() ? bookTitle : chapterTitle;
    }

    private String currentBatteryText() {
        return state.currentBatteryLevel >= 0 ? state.currentBatteryLevel + "%" : "";
    }

    private String currentChapterPageText() {
        int safePageCount = Math.max(content.getPagesForChapter(state.currentChapterIndex).size(), 1);
        return String.format(Locale.SIMPLIFIED_CHINESE, "第 %d/%d 页", state.currentPageIndex + 1, safePageCount);
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
                    if (!state.controlsVisible) {
                        view.setVisibility(View.GONE);
                        view.setTranslationY(0f);
                    }
                })
                .start();
    }
}
