package com.metahumanz.pacilreadmobile.reader;

import android.os.Build;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import java.util.ArrayList;
import java.util.List;

public final class ReaderPaginator {
    private ReaderPaginator() {
    }

    public static List<PageSlice> paginate(CharSequence source, TextPaint paint, int width, int height, float lineSpacingExtra) {
        List<PageSlice> pages = new ArrayList<>();
        if (source == null) {
            pages.add(new PageSlice(0, 0, ""));
            return pages;
        }
        String text = source.toString();
        if (text.isEmpty() || width <= 0 || height <= 0) {
            pages.add(new PageSlice(0, text.length(), text));
            return pages;
        }

        StaticLayout layout = buildLayout(text, paint, width, lineSpacingExtra);
        int lineCount = layout.getLineCount();
        if (lineCount == 0) {
            pages.add(new PageSlice(0, text.length(), text));
            return pages;
        }

        int startLine = 0;
        while (startLine < lineCount) {
            int endLine = startLine;
            while (endLine + 1 < lineCount
                    && layout.getLineBottom(endLine + 1) - layout.getLineTop(startLine) <= height) {
                endLine++;
            }
            int start = layout.getLineStart(startLine);
            int end = layout.getLineEnd(endLine);
            if (end <= start) {
                break;
            }
            pages.add(new PageSlice(start, end, text.subSequence(start, end)));
            startLine = endLine + 1;
        }

        if (pages.isEmpty()) {
            pages.add(new PageSlice(0, text.length(), text));
        }
        return pages;
    }

    public static int findPageForOffset(List<PageSlice> pages, int offset) {
        if (pages == null || pages.isEmpty()) {
            return 0;
        }
        for (int i = 0; i < pages.size(); i++) {
            if (offset < pages.get(i).end) {
                return i;
            }
        }
        return pages.size() - 1;
    }

    private static StaticLayout buildLayout(CharSequence source, TextPaint paint, int width, float lineSpacingExtra) {
        StaticLayout.Builder builder = StaticLayout.Builder.obtain(source, 0, source.length(), paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setLineSpacing(lineSpacingExtra, 1f);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            builder.setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY);
            builder.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL);
        }
        return builder.build();
    }
}
