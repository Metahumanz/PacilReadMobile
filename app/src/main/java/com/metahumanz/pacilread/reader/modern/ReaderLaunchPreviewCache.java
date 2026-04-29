package com.metahumanz.pacilread.reader.modern;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import com.metahumanz.pacilread.reader.modern.theme.ReaderDisplayModeHelper;
import com.metahumanz.pacilread.reader.modern.theme.ReaderThemePalette;
import com.metahumanz.pacilread.storage.SettingsStore;
import com.metahumanz.pacilread.theme.ThemeModeHelper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public final class ReaderLaunchPreviewCache {
    private static final String DIR_NAME = "reader_launch_previews";

    private ReaderLaunchPreviewCache() {
    }

    public static String buildKey(Context context, SettingsStore settingsStore, int width, int height) {
        String raw = width + "x" + height
                + "|" + context.getResources().getConfiguration().orientation
                + "|" + ThemeModeHelper.getResolvedReaderAppearanceLabel(context)
                + "|" + settingsStore.getReaderTheme()
                + "|" + settingsStore.getReaderUiThemeMode()
                + "|" + settingsStore.getReaderFontFamily()
                + "|" + settingsStore.getReaderFontWeight()
                + "|" + settingsStore.getReaderTextColor()
                + "|" + settingsStore.getFontSizeSp()
                + "|" + settingsStore.getLineSpacingExtraSp()
                + "|" + settingsStore.getLetterSpacing()
                + "|" + settingsStore.getFirstLineIndentDp()
                + "|" + settingsStore.getParagraphSpacingDp()
                + "|" + settingsStore.getLeftPaddingDp()
                + "|" + settingsStore.getRightPaddingDp()
                + "|" + settingsStore.getTopPaddingDp()
                + "|" + settingsStore.getBottomPaddingDp()
                + "|" + settingsStore.isChapterTitleVisible()
                + "|" + settingsStore.getChapterTitleAlignment()
                + "|" + settingsStore.isBodyTextJustified()
                + "|" + settingsStore.isReaderDoublePageEnabled()
                + "|" + settingsStore.getReaderDoublePageMode()
                + "|" + settingsStore.getReaderDoublePageTurnStep()
                + "|" + settingsStore.getReaderBackgroundPath()
                + "|" + settingsStore.getBackgroundBlurPercent();
        return Integer.toHexString(raw.hashCode());
    }

    public static Bitmap load(Context context, long bookId, String key) {
        File file = fileFor(context, bookId, key);
        if (!file.isFile()) {
            return null;
        }
        return BitmapFactory.decodeFile(file.getAbsolutePath());
    }

    public static void saveBitmap(Context context, long bookId, String key, Bitmap bitmap) {
        if (context == null || bookId <= 0 || key == null || key.isBlank()
                || bitmap == null || bitmap.isRecycled()) {
            return;
        }
        try {
            File file = fileFor(context, bookId, key);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return;
            }
            try (FileOutputStream output = new FileOutputStream(file)) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 84, output);
            }
        } catch (RuntimeException | IOException ignored) {
        } finally {
            if (!bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
    }

    public static Bitmap createPlaceholder(Context context, SettingsStore settingsStore, int width, int height) {
        if (context == null || settingsStore == null || width <= 0 || height <= 0) {
            return null;
        }
        try {
            ReaderThemePalette palette = ReaderDisplayModeHelper.resolvePalette(context, settingsStore);
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(palette.backgroundColor);

            Paint pagePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            pagePaint.setColor(palette.pageColor);
            RectF page = new RectF(0, 0, width, height);
            canvas.drawRect(page, pagePaint);

            Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            linePaint.setColor(applyAlpha(palette.textColor, 0.18f));
            linePaint.setStrokeWidth(Math.max(2f, width / 360f));
            linePaint.setStrokeCap(Paint.Cap.ROUND);
            float insetX = Math.max(28f, width * 0.08f);
            float y = Math.max(44f, height * 0.12f);
            float lineGap = Math.max(18f, height * 0.035f);
            int lineCount = Math.max(7, Math.min(18, Math.round(height / lineGap) - 4));
            for (int i = 0; i < lineCount; i++) {
                float right = width - insetX - (i % 4 == 3 ? width * 0.18f : 0f);
                canvas.drawLine(insetX, y, right, y, linePaint);
                y += lineGap;
            }
            return bitmap;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static File fileFor(Context context, long bookId, String key) {
        File dir = new File(context.getCacheDir(), DIR_NAME);
        return new File(dir, "book_" + bookId + "_" + key + ".jpg");
    }

    private static int applyAlpha(int color, float alpha) {
        return Color.argb(
                Math.round(Color.alpha(color) * Math.max(0f, Math.min(1f, alpha))),
                Color.red(color),
                Color.green(color),
                Color.blue(color)
        );
    }
}
