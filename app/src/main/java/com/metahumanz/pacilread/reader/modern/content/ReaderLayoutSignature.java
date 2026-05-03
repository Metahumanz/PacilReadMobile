package com.metahumanz.pacilread.reader.modern.content;

import java.util.Objects;

final class ReaderLayoutSignature {
    private final int availableWidthPx;
    private final int availableHeightPx;
    private final boolean chapterTitleVisible;
    private final String chapterTitleAlignment;
    private final float titleTextSizePx;
    private final float bodyTextSizePx;
    private final int bodyFontWeight;
    private final String bodyFontFamily;
    private final float lineSpacingExtraPx;
    private final float letterSpacing;
    private final int firstLineIndentDp;
    private final int paragraphSpacingDp;
    private final int leftPaddingDp;
    private final int rightPaddingDp;
    private final int topPaddingDp;
    private final int bottomPaddingDp;
    private final int systemInsetTopPx;
    private final int systemInsetBottomPx;
    private final boolean doublePageActive;

    ReaderLayoutSignature(
            int availableWidthPx,
            int availableHeightPx,
            boolean chapterTitleVisible,
            String chapterTitleAlignment,
            float titleTextSizePx,
            float bodyTextSizePx,
            int bodyFontWeight,
            String bodyFontFamily,
            float lineSpacingExtraPx,
            float letterSpacing,
            int firstLineIndentDp,
            int paragraphSpacingDp,
            int leftPaddingDp,
            int rightPaddingDp,
            int topPaddingDp,
            int bottomPaddingDp,
            int systemInsetTopPx,
            int systemInsetBottomPx,
            boolean doublePageActive
    ) {
        this.availableWidthPx = availableWidthPx;
        this.availableHeightPx = availableHeightPx;
        this.chapterTitleVisible = chapterTitleVisible;
        this.chapterTitleAlignment = chapterTitleAlignment;
        this.titleTextSizePx = titleTextSizePx;
        this.bodyTextSizePx = bodyTextSizePx;
        this.bodyFontWeight = bodyFontWeight;
        this.bodyFontFamily = bodyFontFamily;
        this.lineSpacingExtraPx = lineSpacingExtraPx;
        this.letterSpacing = letterSpacing;
        this.firstLineIndentDp = firstLineIndentDp;
        this.paragraphSpacingDp = paragraphSpacingDp;
        this.leftPaddingDp = leftPaddingDp;
        this.rightPaddingDp = rightPaddingDp;
        this.topPaddingDp = topPaddingDp;
        this.bottomPaddingDp = bottomPaddingDp;
        this.systemInsetTopPx = systemInsetTopPx;
        this.systemInsetBottomPx = systemInsetBottomPx;
        this.doublePageActive = doublePageActive;
    }

    boolean isPaginationCompatibleWith(ReaderLayoutSignature that) {
        if (that == null) {
            return false;
        }
        return availableWidthPx == that.availableWidthPx
                && availableHeightPx == that.availableHeightPx
                && chapterTitleVisible == that.chapterTitleVisible
                && Float.compare(that.titleTextSizePx, titleTextSizePx) == 0
                && Float.compare(that.bodyTextSizePx, bodyTextSizePx) == 0
                && bodyFontWeight == that.bodyFontWeight
                && Float.compare(that.lineSpacingExtraPx, lineSpacingExtraPx) == 0
                && Float.compare(that.letterSpacing, letterSpacing) == 0
                && firstLineIndentDp == that.firstLineIndentDp
                && paragraphSpacingDp == that.paragraphSpacingDp
                && leftPaddingDp == that.leftPaddingDp
                && rightPaddingDp == that.rightPaddingDp
                && topPaddingDp == that.topPaddingDp
                && bottomPaddingDp == that.bottomPaddingDp
                && doublePageActive == that.doublePageActive
                && Objects.equals(chapterTitleAlignment, that.chapterTitleAlignment)
                && Objects.equals(bodyFontFamily, that.bodyFontFamily);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ReaderLayoutSignature)) {
            return false;
        }
        ReaderLayoutSignature that = (ReaderLayoutSignature) other;
        return isPaginationCompatibleWith(that)
                && systemInsetTopPx == that.systemInsetTopPx
                && systemInsetBottomPx == that.systemInsetBottomPx;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                availableWidthPx,
                availableHeightPx,
                chapterTitleVisible,
                chapterTitleAlignment,
                titleTextSizePx,
                bodyTextSizePx,
                bodyFontWeight,
                bodyFontFamily,
                lineSpacingExtraPx,
                letterSpacing,
                firstLineIndentDp,
                paragraphSpacingDp,
                leftPaddingDp,
                rightPaddingDp,
                topPaddingDp,
                bottomPaddingDp,
                systemInsetTopPx,
                systemInsetBottomPx,
                doublePageActive
        );
    }
}
