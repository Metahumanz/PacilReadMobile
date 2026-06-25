package com.metahumanz.pacilread.reader.modern.content

import java.util.Objects

class ReaderLayoutSignature(
    private val availableWidthPx: Int,
    private val availableHeightPx: Int,
    private val chapterTitleVisible: Boolean,
    private val chapterTitleAlignment: String?,
    private val titleTextSizePx: Float,
    private val bodyTextSizePx: Float,
    private val bodyFontWeight: Int,
    private val bodyFontFamily: String?,
    private val lineSpacingExtraPx: Float,
    private val letterSpacing: Float,
    private val firstLineIndentDp: Int,
    private val paragraphSpacingDp: Int,
    private val leftPaddingDp: Int,
    private val rightPaddingDp: Int,
    private val topPaddingDp: Int,
    private val bottomPaddingDp: Int,
    private val systemInsetTopPx: Int,
    private val systemInsetBottomPx: Int,
    private val doublePageActive: Boolean,
) {
    fun isPaginationCompatibleWith(that: ReaderLayoutSignature?): Boolean {
        if (that == null) return false
        return availableWidthPx == that.availableWidthPx &&
            availableHeightPx == that.availableHeightPx &&
            chapterTitleVisible == that.chapterTitleVisible &&
            java.lang.Float.compare(that.titleTextSizePx, titleTextSizePx) == 0 &&
            java.lang.Float.compare(that.bodyTextSizePx, bodyTextSizePx) == 0 &&
            bodyFontWeight == that.bodyFontWeight &&
            java.lang.Float.compare(that.lineSpacingExtraPx, lineSpacingExtraPx) == 0 &&
            java.lang.Float.compare(that.letterSpacing, letterSpacing) == 0 &&
            firstLineIndentDp == that.firstLineIndentDp &&
            paragraphSpacingDp == that.paragraphSpacingDp &&
            leftPaddingDp == that.leftPaddingDp &&
            rightPaddingDp == that.rightPaddingDp &&
            topPaddingDp == that.topPaddingDp &&
            bottomPaddingDp == that.bottomPaddingDp &&
            doublePageActive == that.doublePageActive &&
            Objects.equals(chapterTitleAlignment, that.chapterTitleAlignment) &&
            Objects.equals(bodyFontFamily, that.bodyFontFamily)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ReaderLayoutSignature) return false
        return isPaginationCompatibleWith(other) &&
            systemInsetTopPx == other.systemInsetTopPx &&
            systemInsetBottomPx == other.systemInsetBottomPx
    }

    override fun hashCode(): Int = Objects.hash(
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
        doublePageActive,
    )
}
