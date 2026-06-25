package com.metahumanz.pacilread.ui

internal object CoverDecodeSizing {
    private const val DEFAULT_COVER_SIZE_PX = 360

    @JvmStatic
    fun sampleSizeFor(width: Int, height: Int, targetWidth: Int, targetHeight: Int): Int {
        var sampleSize = 1
        val safeTargetWidth = targetWidth.coerceAtLeast(1)
        val safeTargetHeight = targetHeight.coerceAtLeast(1)
        while (width / (sampleSize * 2) >= safeTargetWidth &&
            height / (sampleSize * 2) >= safeTargetHeight
        ) {
            sampleSize *= 2
        }
        return sampleSize
    }

    @JvmStatic
    fun targetSize(measured: Int): Int = if (measured > 0) measured else DEFAULT_COVER_SIZE_PX
}
