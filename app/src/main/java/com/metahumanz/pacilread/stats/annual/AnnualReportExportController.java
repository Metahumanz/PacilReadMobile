package com.metahumanz.pacilread.stats.annual;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import com.metahumanz.pacilread.AppUiUtils;
import com.metahumanz.pacilread.R;
import com.metahumanz.pacilread.storage.SettingsStore;
import com.metahumanz.pacilread.theme.ThemeModeHelper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class AnnualReportExportController {
    public static final int REQUEST_SAVE_ANNUAL_REPORT = 1204;

    private final Activity activity;
    private final AnnualReportRenderer renderer = new AnnualReportRenderer();
    private final SettingsStore settingsStore;

    private AnnualReportData pendingReport;
    private AnnualReportStyle selectedStyle = AnnualReportStyle.QUIET;
    private AnnualReportTheme selectedTheme = AnnualReportTheme.LIGHT;
    private final List<AnnualReportMetric> selectedMetrics = new ArrayList<>();
    private final Map<AnnualReportMetric, Button> metricButtons = new LinkedHashMap<>();
    private Bitmap pendingBitmap;
    private ImageView previewImage;
    private Button quietButton;
    private Button highlightButton;
    private Button lightButton;
    private Button darkButton;
    private LinearLayout metricGrid;

    public AnnualReportExportController(Activity activity) {
        this.activity = activity;
        this.settingsStore = new SettingsStore(activity);
    }

    public void showPreview(AnnualReportData report) {
        if (report == null || !report.hasReadingData()) {
            AppUiUtils.showToast(activity, "暂无可生成的年度报告");
            return;
        }
        pendingReport = report;
        selectedStyle = AnnualReportStyle.QUIET;
        selectedTheme = ThemeModeHelper.MODE_DARK.equals(ThemeModeHelper.getResolvedAppBucket(activity))
                ? AnnualReportTheme.DARK
                : AnnualReportTheme.LIGHT;
        selectedMetrics.clear();
        selectedMetrics.addAll(AnnualReportMetric.selectionFromKeys(
                pendingReport,
                AnnualReportMetric.parseKeys(settingsStore.getAnnualReportMetricSelection(pendingReport.isBookScope()))
        ));
        saveSelectedMetrics();
        pendingBitmap = renderer.render(pendingReport, selectedStyle, selectedTheme, selectedMetrics);

        ScrollView scrollView = new ScrollView(activity);
        scrollView.setFillViewport(false);
        scrollView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scrollView.setVerticalScrollBarEnabled(false);

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundResource(R.drawable.bg_app_dialog);
        int pad = AppUiUtils.dp(activity, 18);
        content.setPadding(pad, pad, pad, pad);
        scrollView.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));

        TextView title = title("年度报告预览");
        content.addView(title);

        LinearLayout styleRow = new LinearLayout(activity);
        styleRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams styleRowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        styleRowParams.setMargins(0, AppUiUtils.dp(activity, 18), 0, 0);
        quietButton = styleButton(AnnualReportStyle.QUIET);
        highlightButton = styleButton(AnnualReportStyle.HIGHLIGHT);
        styleRow.addView(quietButton, weightedButtonParams(0));
        styleRow.addView(highlightButton, weightedButtonParams(AppUiUtils.dp(activity, 10)));
        content.addView(styleRow, styleRowParams);

        LinearLayout themeRow = new LinearLayout(activity);
        themeRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams themeRowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        themeRowParams.setMargins(0, AppUiUtils.dp(activity, 10), 0, 0);
        lightButton = themeButton(AnnualReportTheme.LIGHT);
        darkButton = themeButton(AnnualReportTheme.DARK);
        themeRow.addView(lightButton, weightedButtonParams(0));
        themeRow.addView(darkButton, weightedButtonParams(AppUiUtils.dp(activity, 10)));
        content.addView(themeRow, themeRowParams);
        syncOptionButtons();

        TextView metricTitle = sectionTitle("年度摘要");
        LinearLayout.LayoutParams metricTitleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        metricTitleParams.setMargins(0, AppUiUtils.dp(activity, 16), 0, 0);
        content.addView(metricTitle, metricTitleParams);

        metricGrid = new LinearLayout(activity);
        metricGrid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams metricGridParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        metricGridParams.setMargins(0, AppUiUtils.dp(activity, 8), 0, 0);
        content.addView(metricGrid, metricGridParams);
        rebuildMetricButtons();
        syncMetricButtons();

        FrameLayout previewFrame = new FrameLayout(activity);
        previewFrame.setPadding(AppUiUtils.dp(activity, 10), AppUiUtils.dp(activity, 10),
                AppUiUtils.dp(activity, 10), AppUiUtils.dp(activity, 10));
        previewFrame.setBackgroundResource(R.drawable.bg_app_card);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                AppUiUtils.dp(activity, 480)
        );
        previewParams.setMargins(0, AppUiUtils.dp(activity, 16), 0, 0);

        previewImage = new ImageView(activity);
        previewImage.setAdjustViewBounds(true);
        previewImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        previewImage.setImageBitmap(pendingBitmap);
        previewFrame.addView(previewImage, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
        ));
        content.addView(previewFrame, previewParams);

        LinearLayout actionRow = new LinearLayout(activity);
        actionRow.setGravity(Gravity.END);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionRowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        actionRowParams.setMargins(0, AppUiUtils.dp(activity, 16), 0, 0);

        Button saveButton = actionButton("保存到本地", false);
        Button shareButton = actionButton("分享", true);
        actionRow.addView(saveButton, actionButtonParams(0));
        actionRow.addView(shareButton, actionButtonParams(AppUiUtils.dp(activity, 10)));
        content.addView(actionRow, actionRowParams);

        Button closeButton = actionButton("关闭", false);
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        closeParams.setMargins(0, AppUiUtils.dp(activity, 12), 0, 0);
        content.addView(closeButton, closeParams);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setView(scrollView)
                .create();
        saveButton.setOnClickListener(v -> saveCurrent());
        shareButton.setOnClickListener(v -> shareCurrent());
        closeButton.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
    }

    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_SAVE_ANNUAL_REPORT) {
            return false;
        }
        if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
            try {
                writeBitmap(data.getData());
                AppUiUtils.showToast(activity, "年度报告已保存");
            } catch (Exception error) {
                AppUiUtils.showToast(activity, "保存失败: " + error.getMessage());
            }
        }
        return true;
    }

    private Button styleButton(AnnualReportStyle style) {
        Button button = new Button(activity);
        button.setText(style.label);
        button.setAllCaps(false);
        styleOptionButton(button);
        button.setOnClickListener(v -> {
            selectedStyle = style;
            refreshPreviewBitmap();
            syncOptionButtons();
        });
        return button;
    }

    private Button themeButton(AnnualReportTheme theme) {
        Button button = new Button(activity);
        button.setText(theme.label);
        button.setAllCaps(false);
        styleOptionButton(button);
        button.setOnClickListener(v -> {
            selectedTheme = theme;
            refreshPreviewBitmap();
            syncOptionButtons();
        });
        return button;
    }

    private Button metricButton(AnnualReportMetric metric) {
        Button button = new Button(activity);
        button.setText(metric.label(pendingReport) + "\n" + metric.value(pendingReport));
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setSingleLine(false);
        button.setIncludeFontPadding(true);
        button.setTextSize(13f);
        button.setMinHeight(AppUiUtils.dp(activity, 62));
        button.setMinimumHeight(AppUiUtils.dp(activity, 62));
        button.setPadding(AppUiUtils.dp(activity, 8), AppUiUtils.dp(activity, 7),
                AppUiUtils.dp(activity, 8), AppUiUtils.dp(activity, 7));
        button.setOnClickListener(v -> selectMetric(metric));
        return button;
    }

    private Button actionButton(String text, boolean primary) {
        Button button = new Button(activity);
        button.setText(text);
        button.setAllCaps(false);
        button.setMinHeight(AppUiUtils.dp(activity, 52));
        button.setMinimumHeight(AppUiUtils.dp(activity, 52));
        button.setTextSize(15f);
        button.setGravity(Gravity.CENTER);
        button.setSingleLine(false);
        button.setIncludeFontPadding(true);
        button.setPadding(AppUiUtils.dp(activity, 12), AppUiUtils.dp(activity, 8),
                AppUiUtils.dp(activity, 12), AppUiUtils.dp(activity, 8));
        button.setBackgroundResource(primary ? R.drawable.bg_app_primary_button : R.drawable.bg_app_outline_button);
        button.setTextColor(ThemeModeHelper.resolveColor(activity,
                primary ? R.color.app_button_primary_text : R.color.app_button_outline_text));
        return button;
    }

    private void styleOptionButton(Button button) {
        button.setMinHeight(AppUiUtils.dp(activity, 54));
        button.setMinimumHeight(AppUiUtils.dp(activity, 54));
        button.setTextSize(16f);
        button.setGravity(Gravity.CENTER);
        button.setSingleLine(false);
        button.setIncludeFontPadding(true);
        button.setPadding(AppUiUtils.dp(activity, 10), AppUiUtils.dp(activity, 8),
                AppUiUtils.dp(activity, 10), AppUiUtils.dp(activity, 8));
    }

    private TextView title(String text) {
        TextView title = new TextView(activity);
        title.setText(text);
        title.setTextColor(ThemeModeHelper.resolveColor(activity, R.color.app_text_primary));
        title.setTextSize(24f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setIncludeFontPadding(true);
        return title;
    }

    private TextView sectionTitle(String text) {
        TextView title = new TextView(activity);
        title.setText(text);
        title.setTextColor(ThemeModeHelper.resolveColor(activity, R.color.app_text_primary));
        title.setTextSize(15f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setIncludeFontPadding(true);
        return title;
    }

    private LinearLayout.LayoutParams weightedButtonParams(int marginStart) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        );
        params.setMargins(marginStart, 0, 0, 0);
        return params;
    }

    private LinearLayout.LayoutParams actionButtonParams(int marginStart) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        );
        params.setMargins(marginStart, 0, 0, 0);
        return params;
    }

    private void syncOptionButtons() {
        AppUiUtils.styleToggleButton(activity, quietButton, selectedStyle == AnnualReportStyle.QUIET);
        AppUiUtils.styleToggleButton(activity, highlightButton, selectedStyle == AnnualReportStyle.HIGHLIGHT);
        AppUiUtils.styleToggleButton(activity, lightButton, selectedTheme == AnnualReportTheme.LIGHT);
        AppUiUtils.styleToggleButton(activity, darkButton, selectedTheme == AnnualReportTheme.DARK);
    }

    private void rebuildMetricButtons() {
        if (metricGrid == null) {
            return;
        }
        metricButtons.clear();
        metricGrid.removeAllViews();
        List<AnnualReportMetric> metrics = AnnualReportMetric.availableFor(pendingReport);
        LinearLayout row = null;
        for (int i = 0; i < metrics.size(); i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );
                if (i > 0) {
                    rowParams.setMargins(0, AppUiUtils.dp(activity, 8), 0, 0);
                }
                metricGrid.addView(row, rowParams);
            }
            AnnualReportMetric metric = metrics.get(i);
            Button button = metricButton(metric);
            metricButtons.put(metric, button);
            row.addView(button, weightedButtonParams(i % 2 == 0 ? 0 : AppUiUtils.dp(activity, 8)));
        }
    }

    private void selectMetric(AnnualReportMetric metric) {
        if (metric == null || selectedMetrics.contains(metric)) {
            return;
        }
        if (selectedMetrics.size() >= 3) {
            selectedMetrics.remove(0);
        }
        selectedMetrics.add(metric);
        List<AnnualReportMetric> normalized = AnnualReportMetric.sanitizeMetrics(pendingReport, selectedMetrics);
        selectedMetrics.clear();
        selectedMetrics.addAll(normalized);
        saveSelectedMetrics();
        syncMetricButtons();
        refreshPreviewBitmap();
    }

    private void syncMetricButtons() {
        for (Map.Entry<AnnualReportMetric, Button> entry : metricButtons.entrySet()) {
            AppUiUtils.styleToggleButton(activity, entry.getValue(), selectedMetrics.contains(entry.getKey()));
        }
    }

    private void saveSelectedMetrics() {
        if (pendingReport == null) {
            return;
        }
        settingsStore.setAnnualReportMetricSelection(
                pendingReport.isBookScope(),
                AnnualReportMetric.serialize(selectedMetrics)
        );
    }

    private void refreshPreviewBitmap() {
        pendingBitmap = renderer.render(pendingReport, selectedStyle, selectedTheme, selectedMetrics);
        if (previewImage != null) {
            previewImage.setImageBitmap(pendingBitmap);
        }
    }

    private void saveCurrent() {
        if (!ensureBitmap()) {
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/png");
        intent.putExtra(Intent.EXTRA_TITLE, fileName());
        activity.startActivityForResult(intent, REQUEST_SAVE_ANNUAL_REPORT);
    }

    private void shareCurrent() {
        if (!ensureBitmap()) {
            return;
        }
        try {
            File dir = new File(activity.getCacheDir(), "reports");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            File file = new File(dir, fileName());
            try (FileOutputStream output = new FileOutputStream(file)) {
                pendingBitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
            }
            Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("image/png");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(Intent.createChooser(intent, "分享年度报告"));
        } catch (Exception error) {
            AppUiUtils.showToast(activity, "分享失败: " + error.getMessage());
        }
    }

    private boolean ensureBitmap() {
        if (pendingReport == null || !pendingReport.hasReadingData()) {
            AppUiUtils.showToast(activity, "暂无可生成的年度报告");
            return false;
        }
        if (pendingBitmap == null) {
            pendingBitmap = renderer.render(pendingReport, selectedStyle, selectedTheme, selectedMetrics);
        }
        return true;
    }

    private void writeBitmap(Uri uri) throws Exception {
        if (!ensureBitmap()) {
            return;
        }
        try (OutputStream output = activity.getContentResolver().openOutputStream(uri)) {
            if (output == null) {
                throw new IllegalStateException("无法打开保存位置");
            }
            pendingBitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        }
    }

    private String fileName() {
        int year = pendingReport == null ? 0 : pendingReport.year;
        String scope = pendingReport != null && pendingReport.isBookScope() ? "book" : "global";
        return String.format(Locale.ROOT, "pacilread-annual-report-%d-%s-%s-%s.png",
                year, scope, selectedStyle.slug, selectedTheme.slug);
    }
}
