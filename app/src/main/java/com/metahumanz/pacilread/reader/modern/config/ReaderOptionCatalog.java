package com.metahumanz.pacilread.reader.modern.config;

public final class ReaderOptionCatalog {
    public static final String[] READER_FONT_FAMILY_KEYS = new String[]{"system_default", "sans-serif", "monospace"};
    public static final String[] READER_FONT_FAMILY_LABELS = new String[]{"系统默认", "无衬线", "等宽体"};
    public static final int[] READER_FONT_WEIGHT_VALUES = new int[]{250, 400, 700};
    public static final String[] READER_FONT_WEIGHT_LABELS = new String[]{"细体", "标准", "粗体"};
    public static final String[] READER_TEXT_COLOR_KEYS = new String[]{"theme_default", "ink_brown", "graphite", "warm_gray", "jade_ink", "forest_ink", "moon_white", "custom"};
    public static final String[] READER_TEXT_COLOR_LABELS = new String[]{"跟随主题", "墨棕", "石墨", "暖灰", "青墨", "墨绿", "月白", "自定义"};
    public static final String[] UI_THEME_KEYS = new String[]{"follow_app", "system", "light", "dark"};
    public static final String[] DOUBLE_PAGE_MODE_KEYS = new String[]{"landscape", "always", "landscape_or_tablet"};
    public static final String[] DOUBLE_PAGE_MODE_LABELS = new String[]{"仅横屏生效", "始终生效", "横屏或平板设备生效"};
    public static final String[] DOUBLE_PAGE_TURN_STEP_KEYS = new String[]{"one", "two"};
    public static final String[] DOUBLE_PAGE_TURN_STEP_LABELS = new String[]{"每次翻 1 页", "每次翻 2 页"};
    public static final String[] SIMULATION_DOUBLE_PAGE_TURN_MODE_KEYS = new String[]{"outerPage", "spread"};
    public static final String[] SIMULATION_DOUBLE_PAGE_TURN_MODE_LABELS = new String[]{"外侧单页", "整张双页"};
    public static final String[] HUD_KEYS = new String[]{"none", "title", "chapter", "title_chapter", "time", "battery", "chapter_page", "book_progress", "page_and_progress", "time_and_battery"};
    public static final String[] FLIP_KEYS = new String[]{"cover", "slide", "simulation", "scroll", "none"};

    private ReaderOptionCatalog() {
    }

    public static int fontWeightProgress(int weight) {
        for (int i = 0; i < READER_FONT_WEIGHT_VALUES.length; i++) {
            if (READER_FONT_WEIGHT_VALUES[i] == weight) {
                return i;
            }
        }
        return 1;
    }

    public static int fontWeightValueForProgress(int progress) {
        int safeProgress = Math.max(0, Math.min(progress, READER_FONT_WEIGHT_VALUES.length - 1));
        return READER_FONT_WEIGHT_VALUES[safeProgress];
    }

    public static String readerFontWeightLabelForProgress(int progress) {
        int safeProgress = Math.max(0, Math.min(progress, READER_FONT_WEIGHT_LABELS.length - 1));
        return READER_FONT_WEIGHT_LABELS[safeProgress];
    }

    public static int indexOf(String[] values, String target, int fallback) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(target)) {
                return i;
            }
        }
        return fallback;
    }
}
