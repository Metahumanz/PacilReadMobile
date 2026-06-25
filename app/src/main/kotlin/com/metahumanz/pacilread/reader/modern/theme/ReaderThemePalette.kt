package com.metahumanz.pacilread.reader.modern.theme

import com.metahumanz.pacilread.R

class ReaderThemePalette private constructor(
    @JvmField val backgroundColor: Int,
    @JvmField val pageColor: Int,
    @JvmField val textColor: Int,
    @JvmField val overlayColor: Int,
    @JvmField val backgroundDrawableRes: Int,
    @JvmField val displayName: String,
) {
    companion object {
        @JvmStatic
        fun from(key: String?): ReaderThemePalette {
            if (key == "forest") {
                return ReaderThemePalette(
                    0xFFDCEAD7.toInt(),
                    0xFFEAF4E6.toInt(),
                    0xFF2A4B2A.toInt(),
                    0xB8EEF6E9.toInt(),
                    R.drawable.theme_bg_forest,
                    "护眼",
                )
            }
            if (key == "night") {
                return ReaderThemePalette(
                    0xFF0B1320.toInt(),
                    0xFF121C2B.toInt(),
                    0xFFDDE7EE.toInt(),
                    0xD20B121D.toInt(),
                    R.drawable.theme_bg_night,
                    "夜航",
                )
            }
            return ReaderThemePalette(
                0xFFF4ECD8.toInt(),
                0xFFF7F0E1.toInt(),
                0xFF5C4B37.toInt(),
                0xA6FFF8ED.toInt(),
                R.drawable.theme_bg_paper,
                "纸控",
            )
        }
    }
}
