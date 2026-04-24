package com.metahumanz.pacilread.reader.modern.theme;

import com.metahumanz.pacilread.R;

public final class ReaderThemePalette {
    public final int backgroundColor;
    public final int pageColor;
    public final int textColor;
    public final int overlayColor;
    public final int backgroundDrawableRes;
    public final String displayName;

    private ReaderThemePalette(int backgroundColor, int pageColor, int textColor, int overlayColor, int backgroundDrawableRes, String displayName) {
        this.backgroundColor = backgroundColor;
        this.pageColor = pageColor;
        this.textColor = textColor;
        this.overlayColor = overlayColor;
        this.backgroundDrawableRes = backgroundDrawableRes;
        this.displayName = displayName;
    }

    public static ReaderThemePalette from(String key) {
        if ("forest".equals(key)) {
            return new ReaderThemePalette(
                    0xFFDCEAD7,
                    0xFFEAF4E6,
                    0xFF2A4B2A,
                    0xB8EEF6E9,
                    R.drawable.theme_bg_forest,
                    "护眼"
            );
        }
        if ("night".equals(key)) {
            return new ReaderThemePalette(
                    0xFF0B1320,
                    0xFF121C2B,
                    0xFFDDE7EE,
                    0xD20B121D,
                    R.drawable.theme_bg_night,
                    "夜航"
            );
        }
        return new ReaderThemePalette(
                0xFFF4ECD8,
                0xFFF7F0E1,
                0xFF5C4B37,
                0xA6FFF8ED,
                R.drawable.theme_bg_paper,
                "纸控"
        );
    }
}
