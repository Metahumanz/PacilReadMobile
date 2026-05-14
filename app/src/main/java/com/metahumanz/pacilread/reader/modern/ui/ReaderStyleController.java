package com.metahumanz.pacilread.reader.modern.ui;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;

import com.metahumanz.pacilread.reader.modern.ModernReaderActivity;
import com.metahumanz.pacilread.reader.modern.ReaderRuntime;
import com.metahumanz.pacilread.reader.modern.ReaderSessionState;
import com.metahumanz.pacilread.reader.modern.ReaderUiUtils;
import com.metahumanz.pacilread.reader.modern.ReaderViewRefs;
import com.metahumanz.pacilread.reader.modern.config.ReaderOptionCatalog;
import com.metahumanz.pacilread.reader.modern.content.ReaderContentController;
import com.metahumanz.pacilread.reader.modern.paging.ReaderPagingAnimator;
import com.metahumanz.pacilread.reader.modern.theme.ReaderDisplayModeHelper;
import com.metahumanz.pacilread.reader.modern.theme.ReaderThemePalette;
import com.metahumanz.pacilread.tts.MimoTtsClient;
import com.metahumanz.pacilread.util.FileAssetHelper;

import java.io.File;
import java.util.Locale;

public final class ReaderStyleController {
    private static final float CHAPTER_TITLE_SCALE = 1.4f;
    private static final int HUD_BAR_HEIGHT_DP = 22;
    private static final float HUD_TEXT_SIZE_SP = 12f;
    private static final float HUD_TEXT_ALPHA = 0.78f;

    private final ModernReaderActivity activity;
    private final ReaderRuntime runtime;
    private final ReaderViewRefs views;
    private final ReaderSessionState state;
    private final ReaderUiUtils ui;

    private ReaderChromeController chrome;
    private ReaderPagingAnimator paging;
    private ReaderContentController content;
    private com.metahumanz.pacilread.reader.modern.tts.ReaderTtsController tts;

    public ReaderStyleController(
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
            ReaderChromeController chrome,
            ReaderPagingAnimator paging,
            ReaderContentController content,
            com.metahumanz.pacilread.reader.modern.tts.ReaderTtsController tts
    ) {
        this.chrome = chrome;
        this.paging = paging;
        this.content = content;
        this.tts = tts;
    }

    public void applyReaderSettings() {
        ReaderThemePalette palette = ReaderDisplayModeHelper.resolvePalette(activity, runtime.settingsStore);
        Typeface bodyTypeface = buildReaderTypeface(
                runtime.settingsStore.getReaderFontFamily(),
                runtime.settingsStore.getReaderFontWeight()
        );
        Typeface titleTypeface = buildReaderTypeface(
                runtime.settingsStore.getReaderFontFamily(),
                Math.max(600, Math.min(900, runtime.settingsStore.getReaderFontWeight() + 200))
        );
        applyDoublePageVisibility();
        int resolvedTextColor = resolveReaderTextColor(palette);
        state.currentReaderPageColor = palette.pageColor;
        state.currentReaderTextColor = resolvedTextColor;
        views.readerRoot.setBackgroundColor(palette.backgroundColor);
        chrome.applyReaderMenuPalette(palette, resolvedTextColor);
        views.pageCurrent.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        views.pageIncoming.setBackgroundColor(android.graphics.Color.TRANSPARENT);

        int leftPadding = ui.dp(runtime.settingsStore.getLeftPaddingDp());
        int rightPadding = ui.dp(runtime.settingsStore.getRightPaddingDp());
        int topPadding = ui.dp(runtime.settingsStore.getTopPaddingDp())
                + state.readerContentInsetTop
                + computeHudReservedTopPx();
        int bottomPadding = ui.dp(runtime.settingsStore.getBottomPaddingDp())
                + state.readerContentInsetBottom
                + computeHudReservedBottomPx();
        ((ViewGroup) views.pageCurrent).setPadding(leftPadding, topPadding, rightPadding, bottomPadding);
        ((ViewGroup) views.pageIncoming).setPadding(leftPadding, topPadding, rightPadding, bottomPadding);

        stylePageTitleView(views.pageTitleCurrent, titleTypeface, resolvedTextColor);
        stylePageTitleView(views.pageTitleCurrentRight, titleTypeface, resolvedTextColor);
        stylePageTitleView(views.pageTitleIncoming, titleTypeface, resolvedTextColor);
        stylePageTitleView(views.pageTitleIncomingRight, titleTypeface, resolvedTextColor);
        stylePageBodyView(views.pageBodyCurrent, bodyTypeface, resolvedTextColor);
        stylePageBodyView(views.pageBodyCurrentRight, bodyTypeface, resolvedTextColor);
        stylePageBodyView(views.pageBodyIncoming, bodyTypeface, resolvedTextColor);
        stylePageBodyView(views.pageBodyIncomingRight, bodyTypeface, resolvedTextColor);
        styleHudTextView(views.hudTopLeft, bodyTypeface, resolvedTextColor);
        styleHudTextView(views.hudTopCenter, bodyTypeface, resolvedTextColor);
        styleHudTextView(views.hudTopRight, bodyTypeface, resolvedTextColor);
        styleHudTextView(views.hudBottomLeft, bodyTypeface, resolvedTextColor);
        styleHudTextView(views.hudBottomCenter, bodyTypeface, resolvedTextColor);
        styleHudTextView(views.hudBottomRight, bodyTypeface, resolvedTextColor);

        String alignment = runtime.settingsStore.getChapterTitleAlignment();
        int titleGravity = "center".equals(alignment) ? Gravity.CENTER : Gravity.LEFT;
        views.pageTitleCurrent.setGravity(titleGravity);
        views.pageTitleCurrentRight.setGravity(titleGravity);
        views.pageTitleIncoming.setGravity(titleGravity);
        views.pageTitleIncomingRight.setGravity(titleGravity);

        paging.invalidatePreparedPagingSnapshots();
        if (tts != null) {
            tts.updateTtsHighlight();
        }
        if (runtime.settingsStore.isKeepScreenOn()) {
            activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        applyBackgroundImage();
        chrome.updateReaderLayoutInsets();
        views.pageCurrent.requestLayout();
        views.pageIncoming.requestLayout();
        views.pageBodyCurrent.requestLayout();
        views.pageBodyCurrentRight.requestLayout();
        views.pageBodyIncoming.requestLayout();
        views.pageBodyIncomingRight.requestLayout();
        chrome.updateSystemBarsVisibility(state.controlsVisible);
        chrome.applyGlassOpacity();
        chrome.updateReaderHud();
        paging.invalidatePreparedPagingSnapshots();
    }

    public void attachBackground(Uri uri) {
        runtime.safeExecute(() -> {
            try {
                if (!activity.isReaderActive()) {
                    return;
                }
                String oldPath = runtime.settingsStore.getReaderBackgroundPath();
                File newFile = FileAssetHelper.copyUriToFolder(activity, uri, "backgrounds", "reader_bg");
                if (oldPath != null && !oldPath.isBlank()) {
                    FileAssetHelper.deleteIfExists(oldPath);
                }
                runtime.settingsStore.setReaderBackgroundPath(newFile.getAbsolutePath());
                activity.runOnReaderUiThread(this::applyReaderSettings);
            } catch (Exception error) {
                activity.runOnReaderUiThread(() -> ui.showToast("设置背景失败: " + error.getMessage()));
            }
        }, "attach reader background");
    }

    public void applyBackgroundImage() {
        String path = runtime.settingsStore.getReaderBackgroundPath();
        ReaderThemePalette palette = ReaderDisplayModeHelper.resolvePalette(activity, runtime.settingsStore);
        if (!shouldUseCustomBackground(path)) {
            int builtInRes = palette.backgroundDrawableRes;
            if (builtInRes != 0) {
                views.readerBackgroundImage.setImageResource(builtInRes);
                views.readerBackgroundImage.setVisibility(View.VISIBLE);
                applyBackgroundBlur();
            } else {
                views.readerBackgroundImage.setImageDrawable(null);
                views.readerBackgroundImage.setVisibility(View.GONE);
            }
            return;
        }
        Bitmap bitmap = BitmapFactory.decodeFile(path);
        if (bitmap == null) {
            int builtInRes = palette.backgroundDrawableRes;
            if (builtInRes != 0) {
                views.readerBackgroundImage.setImageResource(builtInRes);
                views.readerBackgroundImage.setVisibility(View.VISIBLE);
                applyBackgroundBlur();
            } else {
                views.readerBackgroundImage.setImageDrawable(null);
                views.readerBackgroundImage.setVisibility(View.GONE);
            }
            return;
        }
        views.readerBackgroundImage.setImageBitmap(bitmap);
        views.readerBackgroundImage.setVisibility(View.VISIBLE);
        applyBackgroundBlur();
    }

    public void applyBackgroundBlur() {
        int blurPercent = runtime.settingsStore.getBackgroundBlurPercent();
        if (blurPercent <= 0) {
            views.readerBackgroundImage.setAlpha(1.0f);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                views.readerBackgroundImage.setRenderEffect(null);
            }
            return;
        }
        float alpha = 1.0f - (blurPercent / 100f * 0.5f);
        views.readerBackgroundImage.setAlpha(alpha);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            float radius = blurPercent / 100f * 25f;
            android.graphics.RenderEffect blurEffect = android.graphics.RenderEffect.createBlurEffect(
                    radius,
                    radius,
                    android.graphics.Shader.TileMode.CLAMP
            );
            views.readerBackgroundImage.setRenderEffect(blurEffect);
        }
    }

    public void openBackgroundPicker(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        activity.startActivityForResult(intent, requestCode);
    }

    public String currentBackgroundLabel() {
        String path = runtime.settingsStore.getReaderBackgroundPath();
        ReaderThemePalette palette = ReaderDisplayModeHelper.resolvePalette(activity, runtime.settingsStore);
        if (!shouldUseCustomBackground(path)) {
            String suffix = ReaderDisplayModeHelper.isAutoNightActive(activity, runtime.settingsStore)
                    ? "（自动夜航）"
                    : "";
            return "当前背景：使用" + palette.displayName + "内置壁纸" + suffix;
        }
        return "当前背景：" + new File(path).getName();
    }

    public int resolveReaderTextColor(ReaderThemePalette palette) {
        String colorKey = runtime.settingsStore.getReaderTextColor();
        if (ReaderDisplayModeHelper.shouldOverrideCustomVisuals(activity, runtime.settingsStore, null)) {
            colorKey = "theme_default";
        }
        return resolveReaderTextColorValue(colorKey, palette);
    }

    public int resolveReaderTextColorValue(String colorKey, ReaderThemePalette palette) {
        if ("custom".equals(colorKey)) {
            String customColor = runtime.settingsStore.getCustomTextColor();
            if (customColor != null && !customColor.isEmpty()) {
                try {
                    return android.graphics.Color.parseColor(customColor);
                } catch (Exception ignore) {
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

    public void updateTextColorPreview(TextView preview, String colorKey, ReaderThemePalette palette) {
        if (preview == null) {
            return;
        }
        int index = ReaderOptionCatalog.indexOf(ReaderOptionCatalog.READER_TEXT_COLOR_KEYS, colorKey, 0);
        preview.setText("字色预览：" + ReaderOptionCatalog.READER_TEXT_COLOR_LABELS[index]);
        preview.setTextColor(resolveReaderTextColorValue(colorKey, palette));
        preview.setBackgroundColor(palette.pageColor);
    }

    private int computeHudReservedTopPx() {
        boolean showCenterSlots = isLandscapeHudMode();
        if (!hasVisibleHudSlot(
                runtime.settingsStore.getHudTopLeft(),
                showCenterSlots ? runtime.settingsStore.getHudTopCenter() : "none",
                runtime.settingsStore.getHudTopRight()
        )) {
            return 0;
        }
        return ui.dp(runtime.settingsStore.getHudTopMarginDp() + HUD_BAR_HEIGHT_DP);
    }

    private int computeHudReservedBottomPx() {
        boolean showCenterSlots = isLandscapeHudMode();
        if (!hasVisibleHudSlot(
                runtime.settingsStore.getHudBottomLeft(),
                showCenterSlots ? runtime.settingsStore.getHudBottomCenter() : "none",
                runtime.settingsStore.getHudBottomRight()
        )) {
            return 0;
        }
        return ui.dp(runtime.settingsStore.getHudBottomMarginDp() + HUD_BAR_HEIGHT_DP);
    }

    private boolean isLandscapeHudMode() {
        return views.pageStage != null
                && views.pageStage.getWidth() > 0
                && views.pageStage.getHeight() > 0
                && views.pageStage.getWidth() > views.pageStage.getHeight();
    }

    private boolean hasVisibleHudSlot(String... slots) {
        if (slots == null) {
            return false;
        }
        for (String slot : slots) {
            if (slot != null && !"none".equals(slot)) {
                return true;
            }
        }
        return false;
    }

    private void applyDoublePageVisibility() {
        boolean active = ReaderDisplayModeHelper.isDoublePageActive(
                activity,
                runtime.settingsStore,
                views.pageStage == null ? 0 : views.pageStage.getWidth(),
                views.pageStage == null ? 0 : views.pageStage.getHeight()
        );
        int visibility = active ? View.VISIBLE : View.GONE;
        views.pageCurrentRightPane.setVisibility(visibility);
        views.pageIncomingRightPane.setVisibility(visibility);
        views.pageCurrentGutter.setVisibility(visibility);
        views.pageIncomingGutter.setVisibility(visibility);
    }

    private boolean shouldUseCustomBackground(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        return !ReaderDisplayModeHelper.shouldOverrideCustomVisuals(activity, runtime.settingsStore, null);
    }

    private void stylePageTitleView(TextView textView, Typeface typeface, int textColor) {
        if (textView == null) {
            return;
        }
        textView.setTextColor(textColor);
        textView.setTypeface(typeface);
        textView.setIncludeFontPadding(false);
        textView.setTextSize(runtime.settingsStore.getFontSizeSp() * CHAPTER_TITLE_SCALE);
    }

    private void stylePageBodyView(com.metahumanz.pacilread.reader.JustifiedPageTextView textView, Typeface typeface, int textColor) {
        if (textView == null) {
            return;
        }
        textView.setTextColor(textColor);
        textView.setTypeface(typeface);
        textView.setTextSize(runtime.settingsStore.getFontSizeSp());
        textView.setLineSpacing(runtime.settingsStore.getLineSpacingExtraSp(), 1f);
        textView.setLetterSpacing(runtime.settingsStore.getLetterSpacing());
        textView.setFullJustifyEnabled(runtime.settingsStore.isBodyTextJustified());
        textView.setGravity(Gravity.START | Gravity.TOP);
        textView.setPadding(0, 0, 0, 0);
    }

    private void styleHudTextView(TextView textView, Typeface typeface, int textColor) {
        if (textView == null) {
            return;
        }
        textView.setTypeface(typeface);
        textView.setIncludeFontPadding(false);
        textView.setTextSize(HUD_TEXT_SIZE_SP);
        textView.setTextColor(applyAlpha(textColor, HUD_TEXT_ALPHA));
    }

    private int applyAlpha(int color, float alpha) {
        return Color.argb(
                Math.round(Color.alpha(color) * alpha),
                Color.red(color),
                Color.green(color),
                Color.blue(color)
        );
    }

    public void updateLetterSpacingLabel(TextView label, android.widget.SeekBar seekBar) {
        float spacing = seekBar.getProgress() / 20f;
        label.setText(String.format(Locale.SIMPLIFIED_CHINESE, "%.2f", spacing));
    }

    public void updateFirstLineIndentLabel(TextView label, android.widget.SeekBar seekBar) {
        label.setText(seekBar.getProgress() + " 字符");
    }

    public void updateParagraphSpacingLabel(TextView label, android.widget.SeekBar seekBar) {
        label.setText(seekBar.getProgress() + " dp");
    }

    public void updateBackgroundBlurLabel(TextView label, android.widget.SeekBar seekBar) {
        label.setText(seekBar.getProgress() + "%");
    }

    public Typeface buildReaderTypeface(String familyKey, int weight) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return Typeface.create(familyKey, weight);
        }
        return Typeface.create(familyKey, weight >= 600 ? Typeface.BOLD : Typeface.NORMAL);
    }
}
