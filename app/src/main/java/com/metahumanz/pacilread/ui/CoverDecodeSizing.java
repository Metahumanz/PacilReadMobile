package com.metahumanz.pacilread.ui;

final class CoverDecodeSizing {
    private static final int DEFAULT_COVER_SIZE_PX = 360;

    private CoverDecodeSizing() {
    }

    static int sampleSizeFor(int width, int height, int targetWidth, int targetHeight) {
        int sampleSize = 1;
        int safeTargetWidth = Math.max(targetWidth, 1);
        int safeTargetHeight = Math.max(targetHeight, 1);
        while (width / (sampleSize * 2) >= safeTargetWidth
                && height / (sampleSize * 2) >= safeTargetHeight) {
            sampleSize *= 2;
        }
        return sampleSize;
    }

    static int targetSize(int measured) {
        return measured > 0 ? measured : DEFAULT_COVER_SIZE_PX;
    }
}
