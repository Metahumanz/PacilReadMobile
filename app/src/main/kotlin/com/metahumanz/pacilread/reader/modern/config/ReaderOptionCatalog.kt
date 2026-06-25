package com.metahumanz.pacilread.reader.modern.config

object ReaderOptionCatalog {
    @JvmField val READER_FONT_FAMILY_KEYS = arrayOf("system_default", "sans-serif", "monospace")
    @JvmField val READER_FONT_FAMILY_LABELS = arrayOf("系统默认", "无衬线", "等宽体")
    @JvmField val READER_FONT_WEIGHT_VALUES = intArrayOf(250, 400, 700)
    @JvmField val READER_FONT_WEIGHT_LABELS = arrayOf("细体", "标准", "粗体")
    @JvmField val READER_TEXT_COLOR_KEYS = arrayOf("theme_default", "ink_brown", "graphite", "warm_gray", "jade_ink", "forest_ink", "moon_white", "custom")
    @JvmField val READER_TEXT_COLOR_LABELS = arrayOf("跟随主题", "墨棕", "石墨", "暖灰", "青墨", "墨绿", "月白", "自定义")
    @JvmField val UI_THEME_KEYS = arrayOf("follow_app", "system", "light", "dark")
    @JvmField val DOUBLE_PAGE_MODE_KEYS = arrayOf("landscape", "always", "landscape_or_tablet")
    @JvmField val DOUBLE_PAGE_MODE_LABELS = arrayOf("仅横屏生效", "始终生效", "横屏或平板设备生效")
    @JvmField val DOUBLE_PAGE_TURN_STEP_KEYS = arrayOf("one", "two")
    @JvmField val DOUBLE_PAGE_TURN_STEP_LABELS = arrayOf("每次翻 1 页", "每次翻 2 页")
    @JvmField val SIMULATION_DOUBLE_PAGE_TURN_MODE_KEYS = arrayOf("outerPage", "spread")
    @JvmField val SIMULATION_DOUBLE_PAGE_TURN_MODE_LABELS = arrayOf("外侧单页", "整张双页")
    @JvmField val HUD_KEYS = arrayOf("none", "title", "chapter", "title_chapter", "time", "battery", "chapter_page", "book_progress", "page_and_progress", "time_and_battery")
    @JvmField val FLIP_KEYS = arrayOf("cover", "slide", "simulation", "scroll", "none")

    @JvmStatic
    fun fontWeightProgress(weight: Int): Int {
        for (index in READER_FONT_WEIGHT_VALUES.indices) {
            if (READER_FONT_WEIGHT_VALUES[index] == weight) return index
        }
        return 1
    }

    @JvmStatic
    fun fontWeightValueForProgress(progress: Int): Int =
        READER_FONT_WEIGHT_VALUES[progress.coerceIn(0, READER_FONT_WEIGHT_VALUES.lastIndex)]

    @JvmStatic
    fun readerFontWeightLabelForProgress(progress: Int): String =
        READER_FONT_WEIGHT_LABELS[progress.coerceIn(0, READER_FONT_WEIGHT_LABELS.lastIndex)]

    @JvmStatic
    fun indexOf(values: Array<String>, target: String?, fallback: Int): Int {
        for (index in values.indices) {
            if (values[index] == target) return index
        }
        return fallback
    }
}
