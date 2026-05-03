package com.metahumanz.pacilread.reader;

import android.annotation.SuppressLint;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.LeadingMarginSpan;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

public final class ReaderPaginator {
    private static final int PROGRESSIVE_WINDOW_CHARS = 8_000;
    private static final int PROGRESSIVE_WINDOW_LOOKAHEAD_CHARS = 2_000;

    private ReaderPaginator() {
    }

    public static final class ProgressiveResult {
        public final List<PageSlice> pages;
        public final int targetPageIndex;
        public final boolean complete;

        private ProgressiveResult(List<PageSlice> pages, int targetPageIndex, boolean complete) {
            this.pages = pages == null ? new ArrayList<>() : pages;
            this.targetPageIndex = Math.max(targetPageIndex, 0);
            this.complete = complete;
        }
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
                    && lineBottomForPageEnd(safeSource, layout, endLine + 1) - layout.getLineTop(startLine) <= pageHeight) {
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

    public static ProgressiveResult paginateUntilOffset(
            CharSequence source,
            TextPaint paint,
            int width,
            int firstPageHeight,
            int regularPageHeight,
            float lineSpacingExtra,
            int bodyStartIndex,
            int targetBodyOffset,
            int extraPagesAfterTarget
    ) {
        return paginateUntilOffset(
                source,
                paint,
                width,
                firstPageHeight,
                regularPageHeight,
                lineSpacingExtra,
                bodyStartIndex,
                targetBodyOffset,
                extraPagesAfterTarget,
                () -> false
        );
    }

    public static ProgressiveResult paginateUntilOffset(
            CharSequence source,
            TextPaint paint,
            int width,
            int firstPageHeight,
            int regularPageHeight,
            float lineSpacingExtra,
            int bodyStartIndex,
            int targetBodyOffset,
            int extraPagesAfterTarget,
            BooleanSupplier cancellationRequested
    ) {
        List<PageSlice> pages = new ArrayList<>();
        CharSequence safeSource = source == null ? "" : source;
        int safeBodyStartIndex = Math.max(0, Math.min(bodyStartIndex, safeSource.length()));
        int safeTargetOffset = Math.max(0, Math.min(targetBodyOffset, Math.max(0, safeSource.length() - safeBodyStartIndex)));
        int safeExtraPages = Math.max(0, extraPagesAfterTarget);
        throwIfCancelled(cancellationRequested);
        if (source == null) {
            pages.add(new PageSlice(0, 0, -1, -1, ""));
            return new ProgressiveResult(pages, 0, true);
        }
        if (safeSource.length() == 0 || width <= 0 || firstPageHeight <= 0 || regularPageHeight <= 0) {
            pages.add(buildPageSlice(safeSource, safeBodyStartIndex, 0, safeSource.length()));
            return new ProgressiveResult(pages, findPageForOffset(pages, safeTargetOffset), true);
        }

        int targetPageIndex = -1;
        int pageStart = 0;
        while (pageStart < safeSource.length()) {
            throwIfCancelled(cancellationRequested);
            int windowEnd = chooseProgressiveWindowEnd(safeSource, pageStart);
            StaticLayout layout = buildLayout(safeSource, paint, width, lineSpacingExtra, pageStart, windowEnd);
            throwIfCancelled(cancellationRequested);
            int lineCount = layout.getLineCount();
            if (lineCount == 0) {
                break;
            }
            boolean offsetsRelative = pageStart > 0 && layout.getLineStart(0) < pageStart;
            int startLine = 0;
            boolean advanced = false;
            while (startLine < lineCount) {
                throwIfCancelled(cancellationRequested);
                int pageHeight = pages.isEmpty() ? firstPageHeight : regularPageHeight;
                int endLine = startLine;
                while (endLine + 1 < lineCount
                        && lineBottomForPageEnd(safeSource, layout, endLine + 1, pageStart, windowEnd, offsetsRelative)
                        - layout.getLineTop(startLine) <= pageHeight) {
                    endLine++;
                }
                int start = layoutOffset(layout.getLineStart(startLine), pageStart, windowEnd, offsetsRelative);
                int end = layoutOffset(layout.getLineEnd(endLine), pageStart, windowEnd, offsetsRelative);
                if (end <= start) {
                    break;
                }
                PageSlice page = buildPageSlice(safeSource, safeBodyStartIndex, start, end);
                pages.add(page);
                int pageIndex = pages.size() - 1;
                if (targetPageIndex < 0
                        && page.hasBodyText()
                        && safeTargetOffset >= page.start
                        && safeTargetOffset < page.end) {
                    targetPageIndex = pageIndex;
                }
                if (targetPageIndex >= 0 && pages.size() > targetPageIndex + safeExtraPages) {
                    return new ProgressiveResult(pages, targetPageIndex, false);
                }
                pageStart = end;
                advanced = true;
                if (pageStart >= safeSource.length()) {
                    if (targetPageIndex < 0) {
                        targetPageIndex = findPageForOffset(pages, safeTargetOffset);
                    }
                    return new ProgressiveResult(pages, targetPageIndex, true);
                }
                startLine = endLine + 1;
            }
            if (!advanced) {
                pages.add(buildPageSlice(safeSource, safeBodyStartIndex, pageStart, safeSource.length()));
                targetPageIndex = targetPageIndex < 0 ? findPageForOffset(pages, safeTargetOffset) : targetPageIndex;
                return new ProgressiveResult(pages, targetPageIndex, true);
            }
        }

        if (pages.isEmpty()) {
            pages.add(buildPageSlice(safeSource, safeBodyStartIndex, 0, safeSource.length()));
        }
        if (targetPageIndex < 0) {
            targetPageIndex = findPageForOffset(pages, safeTargetOffset);
        }
        return new ProgressiveResult(pages, targetPageIndex, true);
    }

    private static void throwIfCancelled(BooleanSupplier cancellationRequested) {
        if (cancellationRequested != null && cancellationRequested.getAsBoolean()) {
            throw new CancellationException("progressive pagination cancelled");
        }
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
        return buildLayout(source, paint, width, lineSpacingExtra, 0, source.length());
    }

    @SuppressLint("WrongConstant")
    private static StaticLayout buildLayout(CharSequence source, TextPaint paint, int width, float lineSpacingExtra, int start, int end) {
        int safeStart = Math.max(0, Math.min(start, source.length()));
        int safeEnd = Math.max(safeStart, Math.min(end, source.length()));
        return StaticLayout.Builder.obtain(source, safeStart, safeEnd, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setLineSpacing(lineSpacingExtra, 1f)
                .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .build();
    }

    private static int chooseProgressiveWindowEnd(CharSequence source, int start) {
        int length = source.length();
        int minimumEnd = Math.min(length, start + PROGRESSIVE_WINDOW_CHARS);
        if (minimumEnd >= length) {
            return length;
        }
        int maximumEnd = Math.min(length, minimumEnd + PROGRESSIVE_WINDOW_LOOKAHEAD_CHARS);
        for (int index = minimumEnd; index < maximumEnd; index++) {
            if (source.charAt(index) == '\n') {
                return index + 1;
            }
        }
        return maximumEnd;
    }

    private static int layoutOffset(int rawOffset, int windowStart, int windowEnd, boolean offsetsRelative) {
        int absoluteOffset = offsetsRelative ? windowStart + rawOffset : rawOffset;
        return Math.max(windowStart, Math.min(absoluteOffset, windowEnd));
    }

    private static int lineBottomForPageEnd(CharSequence source, StaticLayout layout, int lineIndex) {
        return lineBottomForPageEnd(source, layout, lineIndex, 0, source.length(), false);
    }

    private static int lineBottomForPageEnd(
            CharSequence source,
            StaticLayout layout,
            int lineIndex,
            int windowStart,
            int windowEnd,
            boolean offsetsRelative
    ) {
        int lineBottom = layout.getLineBottom(lineIndex);
        if (!(source instanceof Spanned)) {
            return lineBottom;
        }
        int lineStart = layoutOffset(layout.getLineStart(lineIndex), windowStart, windowEnd, offsetsRelative);
        int visibleEnd = layoutOffset(layout.getLineVisibleEnd(lineIndex), windowStart, windowEnd, offsetsRelative);
        int lineEnd = layoutOffset(layout.getLineEnd(lineIndex), windowStart, windowEnd, offsetsRelative);
        if (lineStart >= visibleEnd || visibleEnd >= lineEnd) {
            return lineBottom;
        }
        Spanned spanned = (Spanned) source;
        ReaderParagraphBottomSpacingSpan[] spans = spanned.getSpans(
                visibleEnd,
                lineEnd,
                ReaderParagraphBottomSpacingSpan.class
        );
        int spacingPx = 0;
        for (ReaderParagraphBottomSpacingSpan span : spans) {
            int spanStart = spanned.getSpanStart(span);
            int spanEnd = spanned.getSpanEnd(span);
            if (spanStart < lineEnd && spanEnd > visibleEnd) {
                spacingPx += span.getSpacingPx();
            }
        }
        if (spacingPx <= 0) {
            return lineBottom;
        }
        return Math.max(layout.getLineTop(lineIndex), lineBottom - spacingPx);
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
