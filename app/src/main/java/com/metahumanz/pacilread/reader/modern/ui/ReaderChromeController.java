package com.metahumanz.pacilread.reader.modern.ui;

import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.util.TypedValue;
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
import com.metahumanz.pacilread.reader.modern.theme.ReaderDisplayModeHelper;
import com.metahumanz.pacilread.reader.modern.theme.ReaderThemePalette;
import com.metahumanz.pacilread.theme.ThemeModeHelper;
import com.metahumanz.pacilread.ui.GlassUiHelper;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class ReaderChromeController {
    private static final long MENU_AUTO_HIDE_DELAY_MS = 2500L;
    private static final long INSET_REFLOW_SUPPRESS_WINDOW_MS = 450L;

    private final ModernReaderActivity activity;
    private final ReaderRuntime runtime;
    private final ReaderViewRefs views;
    private final ReaderSessionState state;
    private final ReaderUiUtils ui;
    private final Runnable autoHideRunnable = () -> setControlsVisible(false);

    private int menuPanelColor = 0xFFF7F0E1;
    private int menuPanelStrokeColor = 0xFFE3D6C7;
    private int menuButtonColor = 0xFFF1E8D7;
    private int menuButtonStrokeColor = 0xFFD8CAB7;
    private int menuTextColor = 0xFF5C4B37;
    private int menuMutedTextColor = 0xFF6F5E46;
    private int menuButtonTextColor = 0xFF5C4B37;
    private int menuActiveFillColor = 0xFF1B61C9;
    private int menuActiveStrokeColor = 0xFF254FAD;
    private int menuActiveTextColor = 0xFFFFFFFF;

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
            int previousInsetTop = state.systemInsetTop;
            int previousInsetBottom = state.systemInsetBottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Insets systemBars = windowInsets.getInsets(WindowInsets.Type.systemBars());
                Insets cutout = windowInsets.getInsets(WindowInsets.Type.displayCutout());
                boolean landscape = view.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
                state.systemInsetLeft = landscape ? systemBars.left : Math.max(systemBars.left, cutout.left);
                state.systemInsetTop = Math.max(systemBars.top, cutout.top);
                state.systemInsetRight = landscape ? systemBars.right : Math.max(systemBars.right, cutout.right);
                state.systemInsetBottom = Math.max(systemBars.bottom, cutout.bottom);
            } else {
                state.systemInsetLeft = windowInsets.getSystemWindowInsetLeft();
                state.systemInsetTop = windowInsets.getSystemWindowInsetTop();
                state.systemInsetRight = windowInsets.getSystemWindowInsetRight();
                state.systemInsetBottom = windowInsets.getSystemWindowInsetBottom();
            }
            updateReaderLayoutInsets();
            if ((previousInsetTop != state.systemInsetTop || previousInsetBottom != state.systemInsetBottom)
                    && state.book != null
                    && !state.chapters.isEmpty()
                    && !shouldSuppressInsetDrivenReflow()) {
                content.scheduleReflowAfterLayout(state.currentChapterIndex, content.currentCharOffset());
            }
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
                ui.dp(runtime.settingsStore.getHudTopMarginDp()) + state.systemInsetTop,
                ui.dp(12) + state.systemInsetRight,
                0
        );
        views.hudBottomContainer.setPadding(
                ui.dp(12) + state.systemInsetLeft,
                0,
                ui.dp(12) + state.systemInsetRight,
                ui.dp(runtime.settingsStore.getHudBottomMarginDp()) + state.systemInsetBottom
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
    }

    public void updateUiAfterPageChange() {
        if (state.book == null || state.chapters.isEmpty()) {
            return;
        }
        List<PageSlice> pages = content.getPagesForChapter(state.currentChapterIndex);
        com.metahumanz.pacilread.model.ChapterRecord chapter = state.chapters.get(state.currentChapterIndex);
        int safePageCount = Math.max(pages.size(), 1);
        boolean statsEnabled = runtime.settingsStore.isReadingTimeTrackingEnabled();
        String bookTitle = state.book.title == null || state.book.title.isBlank()
                ? "未命名书籍"
                : state.book.title.trim();
        String chapterTitle = chapter.title == null ? "" : chapter.title.trim();
        views.readerTitle.setText(chapterTitle.isEmpty() ? bookTitle : bookTitle + " | " + chapterTitle);
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
            views.pageMeta.setText(currentChapterPageText() + " · 全书章节");
            views.progressSeekBar.setMax(Math.max(state.chapters.size() - 1, 0));
            views.progressSeekBar.setProgress(state.currentChapterIndex);
        } else {
            views.pageMeta.setText(currentChapterPageText() + " · 本章页数");
            views.progressSeekBar.setMax(Math.max(safePageCount - 1, 0));
            views.progressSeekBar.setProgress(state.currentPageIndex);
        }
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
        boolean showCenterSlots = isLandscapeHudMode();
        applyHudSlot(views.hudTopLeft, runtime.settingsStore.getHudTopLeft());
        if (showCenterSlots) {
            applyHudSlot(views.hudTopCenter, runtime.settingsStore.getHudTopCenter());
        } else {
            hideHudSlot(views.hudTopCenter);
        }
        applyHudSlot(views.hudTopRight, runtime.settingsStore.getHudTopRight());
        applyHudSlot(views.hudBottomLeft, runtime.settingsStore.getHudBottomLeft());
        if (showCenterSlots) {
            applyHudSlot(views.hudBottomCenter, runtime.settingsStore.getHudBottomCenter());
        } else {
            hideHudSlot(views.hudBottomCenter);
        }
        applyHudSlot(views.hudBottomRight, runtime.settingsStore.getHudBottomRight());
    }

    public void toggleReaderUiTheme() {
        boolean darkUi = ThemeModeHelper.isDark(activity.getResources());
        runtime.settingsStore.setReaderUiThemeMode(darkUi ? "light" : "dark");
        activity.recreate();
    }

    public void applyReaderMenuPalette(ReaderThemePalette palette, int readerTextColor) {
        if (palette == null) {
            return;
        }
        menuPanelColor = opaqueColor(palette.pageColor);
        boolean darkPanel = isDarkColor(menuPanelColor);
        menuPanelStrokeColor = shiftSurfaceColor(menuPanelColor, darkPanel ? 0.22f : 0.13f);
        menuButtonColor = shiftSurfaceColor(menuPanelColor, darkPanel ? 0.08f : 0.04f);
        menuButtonStrokeColor = shiftSurfaceColor(menuPanelColor, darkPanel ? 0.24f : 0.16f);
        menuTextColor = ensureReadableText(readerTextColor, menuPanelColor, 4.5d);
        menuMutedTextColor = ensureReadableText(blendColors(menuTextColor, menuPanelColor, 0.18f), menuPanelColor, 4.5d);
        menuButtonTextColor = ensureReadableText(menuTextColor, menuButtonColor, 4.5d);
        menuActiveFillColor = opaqueColor(colorOrDefault(
                ThemeModeHelper.resolveThemeAttrColor(activity, R.attr.themeColorReaderMenuButtonActiveFill),
                darkPanel ? shiftSurfaceColor(menuPanelColor, 0.32f) : ThemeModeHelper.resolveColor(activity, R.color.primary)
        ));
        menuActiveStrokeColor = opaqueColor(colorOrDefault(
                ThemeModeHelper.resolveThemeAttrColor(activity, R.attr.themeColorReaderMenuButtonActiveStroke),
                shiftSurfaceColor(menuActiveFillColor, isDarkColor(menuActiveFillColor) ? 0.18f : 0.16f)
        ));
        menuActiveTextColor = ensureReadableText(
                ThemeModeHelper.resolveThemeAttrColor(activity, R.attr.themeColorReaderMenuButtonActiveText),
                menuActiveFillColor,
                4.5d
        );
        applyReaderMenuSurfaces();
    }

    public void styleReaderMenuButton(Button button, boolean active) {
        if (button == null) {
            return;
        }
        button.setTag(R.id.tag_glass_background, Boolean.FALSE);
        button.setBackground(createRoundedDrawable(
                active ? menuActiveFillColor : menuButtonColor,
                active ? menuActiveStrokeColor : menuButtonStrokeColor,
                resolveDimensionAttr(R.attr.themeRadiusReaderButton, 18)
        ));
        button.setTextColor(active ? menuActiveTextColor : menuButtonTextColor);
    }

    public void applyGlassOpacity() {
        GlassUiHelper.applyToHierarchy(activity, views.menuTopPanel, runtime.settingsStore.getGlassOpacityPercent());
        GlassUiHelper.applyToHierarchy(activity, views.menuInfoPanel, runtime.settingsStore.getGlassOpacityPercent());
        GlassUiHelper.applyToHierarchy(activity, views.menuBottomPanel, runtime.settingsStore.getGlassOpacityPercent());
    }

    public void applyMenuLayoutMode() {
        boolean persistent = runtime.settingsStore.isReaderMenuPersistentActionsEnabled();
        views.moreButton.setVisibility(persistent ? View.GONE : View.VISIBLE);
        views.menuTopActions.setVisibility(persistent ? View.VISIBLE : View.GONE);
    }

    public void updateReaderThemeButtons(Button paper, Button forest, Button night, String current) {
        styleThemeButton(paper, "paper".equals(current));
        styleThemeButton(forest, "forest".equals(current));
        styleThemeButton(night, "night".equals(current));
    }

    public void styleThemeButton(Button button, boolean active) {
        if (button == null) {
            return;
        }
        button.setTag(R.id.tag_glass_background, Boolean.FALSE);
        button.setBackground(createRoundedDrawable(
                active ? menuActiveFillColor : menuButtonColor,
                active ? menuActiveStrokeColor : menuButtonStrokeColor,
                resolveDimensionAttr(R.attr.themeRadiusReaderButton, 18)
        ));
        button.setTextColor(active ? menuActiveTextColor : menuMutedTextColor);
    }

    public void setControlsVisible(boolean visible) {
        if (visible) {
            state.pendingTapPagingDelta = 0;
            applyMenuLayoutMode();
        } else {
            activity.dismissReaderPopupImmediate();
        }
        if (state.controlsVisible == visible) {
            if (visible) {
                scheduleAutoHide();
                views.readerRoot.post(this::updateReaderLayoutInsets);
            } else {
                runtime.mainHandler.removeCallbacks(autoHideRunnable);
            }
            suppressInsetDrivenReflowTemporarily();
            updateSystemBarsVisibility(visible);
            return;
        }
        state.controlsVisible = visible;
        animatePanel(views.hudTopContainer, !visible, -ui.dp(12));
        animatePanel(views.hudBottomContainer, !visible, ui.dp(12));
        animatePanel(views.menuTopPanel, visible, -ui.dp(18));
        animatePanel(views.menuInfoPanel, visible, ui.dp(14));
        animatePanel(views.menuBottomPanel, visible, ui.dp(20));
        suppressInsetDrivenReflowTemporarily();
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

    private void applyReaderMenuSurfaces() {
        applyReaderMenuPanel(views.menuTopPanel);
        applyReaderMenuPanel(views.menuInfoPanel);
        applyReaderMenuPanel(views.menuBottomPanel);
        applyReaderMenuHierarchy(views.menuTopPanel);
        applyReaderMenuHierarchy(views.menuInfoPanel);
        applyReaderMenuHierarchy(views.menuBottomPanel);
        styleReaderMenuButton(views.ttsButton, tts != null && tts.isActive());
        styleReaderMenuButton(views.autoPageButton, autoPage != null && autoPage.isActive());
        styleReaderMenuButton(views.themeToggleButton, ThemeModeHelper.isDark(activity.getResources()));
    }

    private void applyReaderMenuPanel(View panel) {
        if (panel == null) {
            return;
        }
        panel.setTag(R.id.tag_glass_background, Boolean.FALSE);
        panel.setBackground(createRoundedDrawable(
                menuPanelColor,
                menuPanelStrokeColor,
                resolveDimensionAttr(R.attr.themeRadiusReaderPanel, 24)
        ));
    }

    private void applyReaderMenuHierarchy(View view) {
        if (view == null) {
            return;
        }
        if (view instanceof Button) {
            styleReaderMenuButton((Button) view, false);
        } else if (view instanceof TextView) {
            ((TextView) view).setTextColor(isMutedMenuText(view) ? menuMutedTextColor : menuTextColor);
        }
        if (!(view instanceof ViewGroup)) {
            return;
        }
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            applyReaderMenuHierarchy(group.getChildAt(index));
        }
    }

    private boolean isMutedMenuText(View view) {
        return view.getId() == R.id.text_page_meta;
    }

    private GradientDrawable createRoundedDrawable(int fillColor, int strokeColor, float radiusPx) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(fillColor);
        drawable.setCornerRadius(radiusPx);
        drawable.setStroke(Math.max(1, ui.dp(1)), strokeColor);
        return drawable;
    }

    private float resolveDimensionAttr(int attrResId, int fallbackDp) {
        TypedValue value = new TypedValue();
        if (activity.getTheme().resolveAttribute(attrResId, value, true)
                && value.type == TypedValue.TYPE_DIMENSION) {
            return value.getDimension(activity.getResources().getDisplayMetrics());
        }
        return ui.dp(fallbackDp);
    }

    private int colorOrDefault(int color, int fallback) {
        return color == 0 ? fallback : color;
    }

    private int opaqueColor(int color) {
        return Color.rgb(Color.red(color), Color.green(color), Color.blue(color));
    }

    private int shiftSurfaceColor(int color, float amount) {
        return blendColors(color, isDarkColor(color) ? Color.WHITE : Color.BLACK, amount);
    }

    private int ensureReadableText(int preferredColor, int backgroundColor, double minimumContrast) {
        int preferred = opaqueColor(preferredColor);
        if (contrastRatio(preferred, backgroundColor) >= minimumContrast) {
            return preferred;
        }
        int darkText = 0xFF181D26;
        int lightText = 0xFFFFFFFF;
        return contrastRatio(darkText, backgroundColor) >= contrastRatio(lightText, backgroundColor)
                ? darkText
                : lightText;
    }

    private int blendColors(int fromColor, int toColor, float amount) {
        float safeAmount = Math.max(0f, Math.min(1f, amount));
        float inverse = 1f - safeAmount;
        return Color.rgb(
                Math.round(Color.red(fromColor) * inverse + Color.red(toColor) * safeAmount),
                Math.round(Color.green(fromColor) * inverse + Color.green(toColor) * safeAmount),
                Math.round(Color.blue(fromColor) * inverse + Color.blue(toColor) * safeAmount)
        );
    }

    private boolean isDarkColor(int color) {
        return relativeLuminance(color) < 0.32d;
    }

    private double contrastRatio(int firstColor, int secondColor) {
        double first = relativeLuminance(firstColor) + 0.05d;
        double second = relativeLuminance(secondColor) + 0.05d;
        return Math.max(first, second) / Math.min(first, second);
    }

    private double relativeLuminance(int color) {
        double red = linearizeColorChannel(Color.red(color) / 255d);
        double green = linearizeColorChannel(Color.green(color) / 255d);
        double blue = linearizeColorChannel(Color.blue(color) / 255d);
        return 0.2126d * red + 0.7152d * green + 0.0722d * blue;
    }

    private double linearizeColorChannel(double value) {
        return value <= 0.03928d
                ? value / 12.92d
                : Math.pow((value + 0.055d) / 1.055d, 2.4d);
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

    private void hideHudSlot(TextView textView) {
        if (textView == null) {
            return;
        }
        textView.setText("");
        textView.setVisibility(View.GONE);
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
        int pagesPerScreen = ReaderDisplayModeHelper.pagesPerScreen(
                activity,
                runtime.settingsStore,
                views.pageStage == null ? 0 : views.pageStage.getWidth(),
                views.pageStage == null ? 0 : views.pageStage.getHeight()
        );
        int startPage = Math.min(state.currentPageIndex + 1, safePageCount);
        int endPage = Math.min(state.currentPageIndex + pagesPerScreen, safePageCount);
        if (pagesPerScreen > 1 && endPage > startPage) {
            return String.format(Locale.SIMPLIFIED_CHINESE, "第 %d-%d/%d 页", startPage, endPage, safePageCount);
        }
        return String.format(Locale.SIMPLIFIED_CHINESE, "第 %d/%d 页", startPage, safePageCount);
    }

    private boolean isLandscapeHudMode() {
        return views.pageStage != null
                && views.pageStage.getWidth() > 0
                && views.pageStage.getHeight() > 0
                && views.pageStage.getWidth() > views.pageStage.getHeight();
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

    private void suppressInsetDrivenReflowTemporarily() {
        state.suppressInsetReflowUntilUptimeMs =
                SystemClock.uptimeMillis() + INSET_REFLOW_SUPPRESS_WINDOW_MS;
    }

    private boolean shouldSuppressInsetDrivenReflow() {
        return SystemClock.uptimeMillis() < state.suppressInsetReflowUntilUptimeMs;
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
