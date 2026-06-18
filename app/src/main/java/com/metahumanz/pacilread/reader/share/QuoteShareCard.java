package com.metahumanz.pacilread.reader.share;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.ContextThemeWrapper;

import androidx.core.content.FileProvider;

import com.metahumanz.pacilread.R;
import com.metahumanz.pacilread.theme.ThemeModeHelper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;

public final class QuoteShareCard {
    private static final int WIDTH = 1080;
    private static final int HORIZONTAL_PADDING = 96;
    private static final int QUOTE_TOP = 222;
    private static final int TARGET_QUOTE_HEIGHT = 1720;
    private static final int CONTEXT_LIMIT = 40;
    private static final long CACHE_MAX_AGE_MILLIS = 24L * 60L * 60L * 1000L;

    private QuoteShareCard() {
    }

    public static GeneratedCard generate(Context context, String quote, String contextBefore,
                                         String contextAfter, String title, String author,
                                         String chapter) throws Exception {
        File directory = new File(context.getCacheDir(), "share_cards");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("无法创建分享缓存");
        }
        File[] oldFiles = directory.listFiles();
        if (oldFiles != null) {
            long cutoff = System.currentTimeMillis() - CACHE_MAX_AGE_MILLIS;
            for (File oldFile : oldFiles) {
                if (oldFile.lastModified() < cutoff) oldFile.delete();
            }
        }
        Bitmap bitmap = render(context, quote, contextBefore, contextAfter, title, author, chapter);
        File target = new File(directory, "quote_" + System.currentTimeMillis() + ".png");
        try (FileOutputStream output = new FileOutputStream(target)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw new IllegalStateException("生成分享图片失败");
            }
        } catch (Exception error) {
            bitmap.recycle();
            target.delete();
            throw error;
        }
        Uri uri = FileProvider.getUriForFile(
                context, context.getPackageName() + ".fileprovider", target);
        return new GeneratedCard(bitmap, target, uri);
    }

    public static Intent createShareIntent(GeneratedCard card) {
        if (card == null) throw new IllegalArgumentException("分享图片不存在");
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("image/png");
        intent.putExtra(Intent.EXTRA_STREAM, card.uri);
        intent.setClipData(ClipData.newRawUri("引用分享卡", card.uri));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return Intent.createChooser(intent, "分享引用");
    }

    public static ContextExcerpt contextExcerpt(String chapterText, int selectionStart, int selectionEnd) {
        String source = chapterText == null ? "" : chapterText;
        int safeStart = Math.max(0, Math.min(selectionStart, source.length()));
        int safeEnd = Math.max(safeStart, Math.min(selectionEnd, source.length()));

        int beforeStart = Math.max(0, safeStart - CONTEXT_LIMIT);
        int afterEnd = Math.min(source.length(), safeEnd + CONTEXT_LIMIT);
        String before = normalizeContext(source.substring(beforeStart, safeStart));
        String after = normalizeContext(source.substring(safeEnd, afterEnd));
        return new ContextExcerpt(before, after);
    }

    static Bitmap render(Context context, String quote, String contextBefore, String contextAfter,
                         String title, String author, String chapter) {
        ContextThemeWrapper appTheme = new ContextThemeWrapper(
                ThemeModeHelper.wrapForApp(context), ThemeModeHelper.resolveAppThemeResId(context));
        int backgroundColor = themedColor(appTheme, R.color.app_surface, Color.rgb(249, 249, 247));
        int quoteColor = themedColor(appTheme, R.color.app_text_primary, Color.rgb(35, 37, 40));
        int contextColor = blendColor(
                backgroundColor,
                themedColor(appTheme, R.color.app_text_muted, Color.rgb(151, 153, 156)),
                0.34f);
        int sourceColor = themedColor(appTheme, R.color.app_text_secondary, Color.rgb(68, 70, 73));
        int metaColor = themedColor(appTheme, R.color.app_text_muted, Color.rgb(120, 122, 124));
        int dividerColor = themedColor(appTheme, R.color.app_border, Color.rgb(218, 219, 216));
        int accentColor = themedColor(appTheme, R.color.app_primary, Color.rgb(27, 97, 201));

        String safeQuote = quote == null ? "" : quote.trim();
        String safeBefore = normalizeContext(contextBefore);
        String safeAfter = normalizeContext(contextAfter);
        TextPaint quotePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        quotePaint.setColor(quoteColor);
        quotePaint.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.SERIF,
                android.graphics.Typeface.NORMAL));
        StaticLayout quoteLayout = null;
        float textSize = 56f;
        int contentWidth = WIDTH - HORIZONTAL_PADDING * 2;
        while (textSize >= 34f) {
            quotePaint.setTextSize(textSize);
            quoteLayout = layout(safeQuote, quotePaint, contentWidth, 1.35f);
            if (quoteLayout.getHeight() <= TARGET_QUOTE_HEIGHT) break;
            textSize -= 2f;
        }
        if (quoteLayout == null) quoteLayout = layout(safeQuote, quotePaint, contentWidth, 1.35f);

        TextPaint contextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        contextPaint.setColor(contextColor);
        contextPaint.setTextSize(36f);
        contextPaint.setTypeface(android.graphics.Typeface.create(
                android.graphics.Typeface.SERIF, android.graphics.Typeface.NORMAL));
        StaticLayout beforeLayout = safeBefore.isEmpty()
                ? null : layout(safeBefore, contextPaint, contentWidth, 1.32f);
        StaticLayout afterLayout = safeAfter.isEmpty()
                ? null : layout(safeAfter, contextPaint, contentWidth, 1.32f);

        TextPaint sourcePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        sourcePaint.setColor(sourceColor);
        sourcePaint.setTextSize(34f);
        sourcePaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        String source = "《" + safe(title, "未命名书籍") + "》";
        StaticLayout sourceLayout = layout(source, sourcePaint, contentWidth, 1.1f);

        TextPaint metaPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        metaPaint.setColor(metaColor);
        metaPaint.setTextSize(26f);
        String meta = joinMeta(chapter, author);
        StaticLayout metaLayout = layout(meta, metaPaint, contentWidth, 1.15f);

        int contentY = QUOTE_TOP;
        int beforeY = contentY;
        if (beforeLayout != null) contentY += beforeLayout.getHeight() + 34;
        int quoteY = contentY;
        contentY += quoteLayout.getHeight();
        int afterY = contentY + (afterLayout == null ? 0 : 34);
        if (afterLayout != null) contentY = afterY + afterLayout.getHeight();

        int footerHeight = 42 + sourceLayout.getHeight() + 24 + metaLayout.getHeight() + 72;
        int height = contentY + 88 + footerHeight;
        int dividerY = height - footerHeight;
        int sourceY = dividerY + 42;
        int metaY = sourceY + sourceLayout.getHeight() + 24;

        Bitmap bitmap = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(backgroundColor);

        Paint accentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        accentPaint.setColor(accentColor);
        canvas.drawRect(HORIZONTAL_PADDING, 92, HORIZONTAL_PADDING + 10, 196, accentPaint);

        TextPaint quoteMarkPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        quoteMarkPaint.setColor(accentPaint.getColor());
        quoteMarkPaint.setTextSize(112f);
        quoteMarkPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        canvas.drawText("“", HORIZONTAL_PADDING + 28, 180, quoteMarkPaint);

        if (beforeLayout != null) {
            canvas.save();
            canvas.translate(HORIZONTAL_PADDING, beforeY);
            beforeLayout.draw(canvas);
            canvas.restore();
        }

        canvas.save();
        canvas.translate(HORIZONTAL_PADDING, quoteY);
        quoteLayout.draw(canvas);
        canvas.restore();

        if (afterLayout != null) {
            canvas.save();
            canvas.translate(HORIZONTAL_PADDING, afterY);
            afterLayout.draw(canvas);
            canvas.restore();
        }

        Paint divider = new Paint(Paint.ANTI_ALIAS_FLAG);
        divider.setColor(dividerColor);
        divider.setStrokeWidth(2f);
        canvas.drawLine(HORIZONTAL_PADDING, dividerY, WIDTH - HORIZONTAL_PADDING, dividerY, divider);

        canvas.save();
        canvas.translate(HORIZONTAL_PADDING, sourceY);
        sourceLayout.draw(canvas);
        canvas.restore();

        canvas.save();
        canvas.translate(HORIZONTAL_PADDING, metaY);
        metaLayout.draw(canvas);
        canvas.restore();
        return bitmap;
    }

    private static StaticLayout layout(String text, TextPaint paint, int width, float spacingMultiplier) {
        return StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setLineSpacing(0f, spacingMultiplier)
                .build();
    }

    private static String joinMeta(String chapter, String author) {
        String safeChapter = safe(chapter, "");
        String safeAuthor = safe(author, "");
        if (safeChapter.isEmpty()) return safeAuthor;
        if (safeAuthor.isEmpty()) return safeChapter;
        return safeChapter + "  ·  " + safeAuthor;
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String normalizeContext(String value) {
        if (value == null) return "";
        return value.trim().replaceAll("[\\t\\x0B\\f\\r ]+", " ")
                .replaceAll("\\n{3,}", "\\n\\n");
    }

    private static int themedColor(Context context, int colorRes, int fallback) {
        int color = ThemeModeHelper.resolveColor(context, colorRes);
        return color == 0 ? fallback : color;
    }

    private static int blendColor(int background, int foreground, float foregroundRatio) {
        float ratio = Math.max(0f, Math.min(1f, foregroundRatio));
        float backgroundRatio = 1f - ratio;
        return Color.rgb(
                Math.round(Color.red(background) * backgroundRatio + Color.red(foreground) * ratio),
                Math.round(Color.green(background) * backgroundRatio + Color.green(foreground) * ratio),
                Math.round(Color.blue(background) * backgroundRatio + Color.blue(foreground) * ratio));
    }

    public static final class ContextExcerpt {
        public final String before;
        public final String after;

        ContextExcerpt(String before, String after) {
            this.before = before == null ? "" : before;
            this.after = after == null ? "" : after;
        }
    }

    public static final class GeneratedCard {
        public final Bitmap bitmap;
        public final File file;
        public final Uri uri;

        GeneratedCard(Bitmap bitmap, File file, Uri uri) {
            this.bitmap = bitmap;
            this.file = file;
            this.uri = uri;
        }

        public String fileName() {
            return file == null ? "PacilRead-引用分享.png" : file.getName();
        }

        public void writeTo(Context context, Uri destination) throws Exception {
            if (context == null || destination == null || file == null || !file.isFile()) {
                throw new IllegalStateException("分享图片不存在");
            }
            try (FileInputStream input = new FileInputStream(file);
                 OutputStream output = context.getContentResolver().openOutputStream(destination, "w")) {
                if (output == null) throw new IllegalStateException("无法打开保存位置");
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            }
        }

        public void recyclePreview() {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }
    }
}
