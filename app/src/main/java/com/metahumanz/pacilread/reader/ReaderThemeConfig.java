package com.metahumanz.pacilread.reader;

import com.metahumanz.pacilread.storage.SettingsStore;

import org.json.JSONObject;

public final class ReaderThemeConfig {
    private ReaderThemeConfig() {
    }

    public static JSONObject export(SettingsStore settingsStore) {
        JSONObject jsonObject = new JSONObject();
        try {
            jsonObject.put("font_size_sp", settingsStore.getFontSizeSp());
            jsonObject.put("font_family", settingsStore.getReaderFontFamily());
            jsonObject.put("font_weight", settingsStore.getReaderFontWeight());
            jsonObject.put("text_color", settingsStore.getReaderTextColor());
            jsonObject.put("line_spacing_extra", settingsStore.getLineSpacingExtraSp());
            jsonObject.put("side_padding_dp", settingsStore.getSidePaddingDp());
            jsonObject.put("vertical_padding_dp", settingsStore.getVerticalPaddingDp());
            jsonObject.put("reader_theme", settingsStore.getReaderTheme());
            jsonObject.put("reader_background_path", settingsStore.getReaderBackgroundPath());
            jsonObject.put("keep_screen_on", settingsStore.isKeepScreenOn());
            jsonObject.put("flip_mode", settingsStore.getFlipMode());
            jsonObject.put("chapter_title_visibility", settingsStore.isChapterTitleVisible());
            jsonObject.put("auto_page_seconds", settingsStore.getAutoPageSeconds());
            jsonObject.put("tts_rate", settingsStore.getTtsRate());
        } catch (Exception ignore) {
        }
        return jsonObject;
    }

    public static void apply(SettingsStore settingsStore, JSONObject jsonObject) {
        if (jsonObject == null) {
            return;
        }
        settingsStore.setFontSizeSp((float) jsonObject.optDouble("font_size_sp", settingsStore.getFontSizeSp()));
        settingsStore.setReaderFontFamily(jsonObject.optString("font_family", settingsStore.getReaderFontFamily()));
        settingsStore.setReaderFontWeight(jsonObject.optInt("font_weight", settingsStore.getReaderFontWeight()));
        settingsStore.setReaderTextColor(jsonObject.optString("text_color", settingsStore.getReaderTextColor()));
        settingsStore.setLineSpacingExtraSp((float) jsonObject.optDouble("line_spacing_extra", settingsStore.getLineSpacingExtraSp()));
        settingsStore.setSidePaddingDp(jsonObject.optInt("side_padding_dp", settingsStore.getSidePaddingDp()));
        settingsStore.setVerticalPaddingDp(jsonObject.optInt("vertical_padding_dp", settingsStore.getVerticalPaddingDp()));
        settingsStore.setReaderTheme(jsonObject.optString("reader_theme", settingsStore.getReaderTheme()));
        settingsStore.setReaderBackgroundPath(jsonObject.optString("reader_background_path", settingsStore.getReaderBackgroundPath()));
        settingsStore.setKeepScreenOn(jsonObject.optBoolean("keep_screen_on", settingsStore.isKeepScreenOn()));
        settingsStore.setFlipMode(jsonObject.optString("flip_mode", settingsStore.getFlipMode()));
        settingsStore.setChapterTitleVisible(jsonObject.optBoolean("chapter_title_visibility", settingsStore.isChapterTitleVisible()));
        settingsStore.setAutoPageSeconds(jsonObject.optInt("auto_page_seconds", settingsStore.getAutoPageSeconds()));
        settingsStore.setTtsRate((float) jsonObject.optDouble("tts_rate", settingsStore.getTtsRate()));
    }
}
