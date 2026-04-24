package com.metahumanz.pacilread.reader;

import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.LeadingMarginSpan;

import java.util.ArrayList;
import java.util.List;

public final class ReaderPaginator {
    private ReaderPaginator() {
    }

    public static List<PageSlice> paginate(CharSequence source, TextPaint paint, int width, int height, float lineSpacingExtra) {
        return paginate(source, paint, width, height, height, lineSpacingExtra, 0);
    }

    public static List<PageSlice> paginate(CharSequence source, TextPaint paint, int width, int firstPageHeight, int regularPageHeight, float lineSpacingExtra) {
        return paginate(source, paint, width, firstPageHeight, regularPageHeight, lineSpacingExtra, 0);
    }

    public static List<PageSlice> paginate(
            CharSequence source,
            TextPaint paint,
            int width,
            int firstPageHeight,
            int regularPageHeight,
            float lineSpacingExtra,
            int bodyStartIndex
    ) {
        List<PageSlice> pages = new ArrayList<>();
        CharSequence safeSource = source == null ? "" : source;
        int safeBodyStartIndex = Math.max(0, Math.min(bodyStartIndex, safeSource.length()));
        if (source == null) {
            pages.add(new PageSlice(0, 0, -1, -1, ""));
            return pages;
        }
        if (safeSource.length() == 0 || width <= 0 || firstPageHeight <= 0 || regularPageHeight <= 0) {
            pages.add(buildPageSlice(safeSource, safeBodyStartIndex, 0, safeSource.length()));
            return pages;
        }

        StaticLayout layout = buildLayout(safeSource, paint, width, lineSpacingExtra);
        int lineCount = layout.getLineCount();
        if (lineCount == 0) {
            pages.add(buildPageSlice(safeSource, safeBodyStartIndex, 0, safeSource.length()));
            return pages;
        }

        int startLine = 0;
        while (startLine < lineCount) {
            int pageHeight = pages.isEmpty() ? firstPageHeight : regularPageHeight;
            int endLine = startLine;
            while (endLine + 1 < lineCount
                    && layout.getLineBottom(endLine + 1) - layout.getLineTop(startLine) <= pageHeight) {
                endLine++;
            }
            int start = layout.getLineStart(startLine);
            int end = layout.getLineEnd(endLine);
            if (end <= start) {
                break;
            }
            pages.add(buildPageSlice(safeSource, safeBodyStartIndex, start, end));
            startLine = endLine + 1;
        }

        if (pages.isEmpty()) {
            pages.add(buildPageSlice(safeSource, safeBodyStartIndex, 0, safeSource.length()));
        }
        return pages;
    }

    public static int findPageForOffset(List<PageSlice> pages, int offset) {
        if (pages == null || pages.isEmpty()) {
            return 0;
        }
        int safeOffset = Math.max(offset, 0);
        int firstBodyPageIndex = -1;
        int lastBodyPageIndex = -1;
        for (int i = 0; i < pages.size(); i++) {
            PageSlice page = pages.get(i);
            if (!page.hasBodyText()) {
                continue;
            }
            if (firstBodyPageIndex < 0) {
                firstBodyPageIndex = i;
            }
            lastBodyPageIndex = i;
            if (safeOffset < page.end) {
                return i;
            }
        }
        if (safeOffset == 0 && firstBodyPageIndex >= 0) {
            return firstBodyPageIndex;
        }
        return lastBodyPageIndex >= 0 ? lastBodyPageIndex : 0;
    }

    private static StaticLayout buildLayout(CharSequence source, TextPaint paint, int width, float lineSpacingExtra) {
        return StaticLayout.Builder.obtain(source, 0, source.length(), paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setLineSpacing(lineSpacingExtra, 1f)
                .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .build();
    }

    private static PageSlice buildPageSlice(CharSequence source, int bodyStartIndex, int contentStart, int contentEnd) {
        int safeContentStart = Math.max(0, Math.min(contentStart, source.length()));
        int safeContentEnd = Math.max(safeContentStart, Math.min(contentEnd, source.length()));
        int bodyTextStart = Math.max(safeContentStart, bodyStartIndex);
        int bodyTextEnd = Math.max(bodyTextStart, safeContentEnd);
        boolean hasBodyText = bodyTextEnd > bodyTextStart;
        return new PageSlice(
                Math.max(0, bodyTextStart - bodyStartIndex),
                Math.max(0, bodyTextEnd - bodyStartIndex),
                hasBodyText ? bodyTextStart - safeContentStart : -1,
                hasBodyText ? bodyTextEnd - safeContentStart : -1,
                buildSliceText(source, safeContentStart, safeContentEnd)
        );
    }

    private static CharSequence buildSliceText(CharSequence source, int start, int end) {
        if (!(source instanceof Spanned)) {
            return source.subSequence(start, end);
        }
        Spanned spanned = (Spanned) source;
        SpannableStringBuilder slice = new SpannableStringBuilder(source.subSequence(start, end).toString());
        Object[] spans = spanned.getSpans(start, end, Object.class);
        for (Object span : spans) {
            int spanStart = spanned.getSpanStart(span);
            int spanEnd = spanned.getSpanEnd(span);
            int clippedStart = Math.max(spanStart, start) - start;
            int clippedEnd = Math.min(spanEnd, end) - start;
            if (spanStart < 0 || clippedStart >= clippedEnd) {
                continue;
            }
            Object displaySpan = span;
            if (span instanceof LeadingMarginSpan && spanStart < start) {
                int continuationMargin = ((LeadingMarginSpan) span).getLeadingMargin(false);
                displaySpan = new LeadingMarginSpan.Standard(continuationMargin, continuationMargin);
            }
            slice.setSpan(displaySpan, clippedStart, clippedEnd, spanned.getSpanFlags(span));
        }
        return slice;
    }
}
