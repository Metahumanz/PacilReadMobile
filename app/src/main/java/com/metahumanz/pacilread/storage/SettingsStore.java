package com.metahumanz.pacilread.storage;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.Map;
import java.util.Iterator;

public class SettingsStore {
    private static final String PREFS_NAME = "pacil_read_settings";

    private static final String KEY_AUTO_OPEN = "auto_open_last";
    private static final String KEY_WEB_DAV_ENABLED = "webdav_enabled";
    private static final String KEY_WEB_DAV_URL = "webdav_url";
    private static final String KEY_WEB_DAV_DIR = "webdav_dir";
    private static final String KEY_WEB_DAV_USER = "webdav_user";
    private static final String KEY_WEB_DAV_PASSWORD = "webdav_password";
    private static final String KEY_WEB_DAV_LAST_FULL = "webdav_last_full";
    private static final String KEY_WEB_DAV_LAST_LITE = "webdav_last_lite";
    private static final String KEY_WEB_DAV_SYNC_BOOKSHELF = "webdav_sync_bookshelf";
    private static final String KEY_WEB_DAV_SYNC_FILES = "webdav_sync_files";
    private static final String KEY_WEB_DAV_SYNC_UI_SETTINGS = "webdav_sync_ui_settings";
    private static final String KEY_WEB_DAV_SYNC_THEMES = "webdav_sync_themes";
    private static final String KEY_WEB_DAV_SYNC_BACKGROUNDS = "webdav_sync_backgrounds";
    private static final String KEY_FONT_SIZE = "font_size_sp";
    private static final String KEY_FONT_FAMILY = "font_family";
    private static final String KEY_FONT_WEIGHT = "font_weight";
    private static final String KEY_TEXT_COLOR = "reader_text_color";
    private static final String KEY_LINE_SPACING = "line_spacing_extra";
    private static final String KEY_SIDE_PADDING = "side_padding_dp";
    private static final String KEY_VERTICAL_PADDING = "vertical_padding_dp";
    private static final String KEY_APP_THEME_MODE = "app_theme_mode";
    private static final String KEY_READER_UI_THEME_MODE = "reader_ui_theme_mode";
    private static final String KEY_THEME = "reader_theme";
    private static final String KEY_BACKGROUND_PATH = "reader_background_path";
    private static final String KEY_KEEP_SCREEN_ON = "keep_screen_on";
    private static final String KEY_AUTO_PAGE_SECONDS = "auto_page_seconds";
    private static final String KEY_TTS_ENGINE = "tts_engine";
    private static final String KEY_TTS_RATE = "tts_rate";
    private static final String KEY_TTS_MIMO_API_KEY = "tts_mimo_api_key";
    private static final String KEY_FLIP_MODE = "flip_mode";
    private static final String KEY_READER_SLIDER_MODE = "reader_slider_mode";
    private static final String KEY_VOLUME_KEY_UP_ACTION = "volume_key_up_action";
    private static final String KEY_VOLUME_KEY_DOWN_ACTION = "volume_key_down_action";
    private static final String KEY_CHAPTER_TITLE_VISIBILITY = "chapter_title_visibility";
    private static final String KEY_BOOKSHELF_VIEW_MODE = "bookshelf_view_mode";
    private static final String KEY_GLASS_OPACITY_PERCENT = "glass_opacity_percent";
    private static final String KEY_HUD_TOP_LEFT = "hud_top_left";
    private static final String KEY_HUD_TOP_CENTER = "hud_top_center";
    private static final String KEY_HUD_TOP_RIGHT = "hud_top_right";
    private static final String KEY_HUD_BOTTOM_LEFT = "hud_bottom_left";
    private static final String KEY_HUD_BOTTOM_CENTER = "hud_bottom_center";
    private static final String KEY_HUD_BOTTOM_RIGHT = "hud_bottom_right";

    private final SharedPreferences preferences;

    public SettingsStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public boolean isAutoOpenLastBook() {
        return preferences.getBoolean(KEY_AUTO_OPEN, false);
    }

    public void setAutoOpenLastBook(boolean enabled) {
        preferences.edit().putBoolean(KEY_AUTO_OPEN, enabled).apply();
    }

    public boolean isWebDavEnabled() {
        return preferences.getBoolean(KEY_WEB_DAV_ENABLED, false);
    }

    public void setWebDavEnabled(boolean enabled) {
        preferences.edit().putBoolean(KEY_WEB_DAV_ENABLED, enabled).apply();
    }

    public String getWebDavUrl() {
        return preferences.getString(KEY_WEB_DAV_URL, "");
    }

    public void setWebDavUrl(String value) {
        preferences.edit().putString(KEY_WEB_DAV_URL, normalizeBaseUrl(value)).apply();
    }

    public String getWebDavDir() {
        return normalizeDirectory(preferences.getString(KEY_WEB_DAV_DIR, "Books"));
    }

    public void setWebDavDir(String value) {
        preferences.edit().putString(KEY_WEB_DAV_DIR, normalizeDirectory(value)).apply();
    }

    public String getWebDavUser() {
        return preferences.getString(KEY_WEB_DAV_USER, "");
    }

    public void setWebDavUser(String value) {
        preferences.edit().putString(KEY_WEB_DAV_USER, value == null ? "" : value.trim()).apply();
    }

    public String getWebDavPassword() {
        return preferences.getString(KEY_WEB_DAV_PASSWORD, "");
    }

    public void setWebDavPassword(String value) {
        preferences.edit().putString(KEY_WEB_DAV_PASSWORD, value == null ? "" : value.trim()).apply();
    }

    public long getWebDavLastFullBackupAt() {
        return preferences.getLong(KEY_WEB_DAV_LAST_FULL, 0L);
    }

    public void setWebDavLastFullBackupAt(long value) {
        preferences.edit().putLong(KEY_WEB_DAV_LAST_FULL, value).apply();
    }

    public long getWebDavLastLiteBackupAt() {
        return preferences.getLong(KEY_WEB_DAV_LAST_LITE, 0L);
    }

    public void setWebDavLastLiteBackupAt(long value) {
        preferences.edit().putLong(KEY_WEB_DAV_LAST_LITE, value).apply();
    }

    public boolean isWebDavSyncBookshelfEnabled() {
        return preferences.getBoolean(KEY_WEB_DAV_SYNC_BOOKSHELF, true);
    }

    public void setWebDavSyncBookshelfEnabled(boolean enabled) {
        preferences.edit().putBoolean(KEY_WEB_DAV_SYNC_BOOKSHELF, enabled).apply();
    }

    public boolean isWebDavSyncFilesEnabled() {
        return preferences.getBoolean(KEY_WEB_DAV_SYNC_FILES, true);
    }

    public void setWebDavSyncFilesEnabled(boolean enabled) {
        preferences.edit().putBoolean(KEY_WEB_DAV_SYNC_FILES, enabled).apply();
    }

    public boolean isWebDavSyncUiSettingsEnabled() {
        return preferences.getBoolean(KEY_WEB_DAV_SYNC_UI_SETTINGS, true);
    }

    public void setWebDavSyncUiSettingsEnabled(boolean enabled) {
        preferences.edit().putBoolean(KEY_WEB_DAV_SYNC_UI_SETTINGS, enabled).apply();
    }

    public boolean isWebDavSyncThemesEnabled() {
        return preferences.getBoolean(KEY_WEB_DAV_SYNC_THEMES, true);
    }

    public void setWebDavSyncThemesEnabled(boolean enabled) {
        preferences.edit().putBoolean(KEY_WEB_DAV_SYNC_THEMES, enabled).apply();
    }

    public boolean isWebDavSyncBackgroundsEnabled() {
        return preferences.getBoolean(KEY_WEB_DAV_SYNC_BACKGROUNDS, true);
    }

    public void setWebDavSyncBackgroundsEnabled(boolean enabled) {
        preferences.edit().putBoolean(KEY_WEB_DAV_SYNC_BACKGROUNDS, enabled).apply();
    }

    public float getFontSizeSp() {
        return preferences.getFloat(KEY_FONT_SIZE, 18f);
    }

    public void setFontSizeSp(float value) {
        preferences.edit().putFloat(KEY_FONT_SIZE, clamp(value, 12f, 34f)).apply();
    }

    public String getReaderFontFamily() {
        return normalizeReaderFontFamily(preferences.getString(KEY_FONT_FAMILY, "system_default"));
    }

    public void setReaderFontFamily(String value) {
        preferences.edit().putString(KEY_FONT_FAMILY, normalizeReaderFontFamily(value)).apply();
    }

    public int getReaderFontWeight() {
        return normalizeReaderFontWeight(preferences.getInt(KEY_FONT_WEIGHT, 400));
    }

    public void setReaderFontWeight(int value) {
        preferences.edit().putInt(KEY_FONT_WEIGHT, normalizeReaderFontWeight(value)).apply();
    }

    public String getReaderTextColor() {
        return normalizeReaderTextColor(preferences.getString(KEY_TEXT_COLOR, "theme_default"));
    }

    public void setReaderTextColor(String value) {
        preferences.edit().putString(KEY_TEXT_COLOR, normalizeReaderTextColor(value)).apply();
    }

    public float getLineSpacingExtraSp() {
        return preferences.getFloat(KEY_LINE_SPACING, 8f);
    }

    public void setLineSpacingExtraSp(float value) {
        preferences.edit().putFloat(KEY_LINE_SPACING, clamp(value, 0f, 28f)).apply();
    }

    public int getSidePaddingDp() {
        return preferences.getInt(KEY_SIDE_PADDING, 18);
    }

    public void setSidePaddingDp(int value) {
        preferences.edit().putInt(KEY_SIDE_PADDING, clamp(value, 8, 48)).apply();
    }

    public int getVerticalPaddingDp() {
        return preferences.getInt(KEY_VERTICAL_PADDING, 24);
    }

    public void setVerticalPaddingDp(int value) {
        preferences.edit().putInt(KEY_VERTICAL_PADDING, clamp(value, 8, 72)).apply();
    }

    public String getAppThemeMode() {
        return normalizeAppThemeMode(preferences.getString(KEY_APP_THEME_MODE, "system"));
    }

    public void setAppThemeMode(String value) {
        preferences.edit().putString(KEY_APP_THEME_MODE, normalizeAppThemeMode(value)).apply();
    }

    public String getReaderUiThemeMode() {
        return normalizeReaderUiThemeMode(preferences.getString(KEY_READER_UI_THEME_MODE, "follow_app"));
    }

    public void setReaderUiThemeMode(String value) {
        preferences.edit().putString(KEY_READER_UI_THEME_MODE, normalizeReaderUiThemeMode(value)).apply();
    }

    public String getReaderTheme() {
        return preferences.getString(KEY_THEME, "paper");
    }

    public void setReaderTheme(String value) {
        preferences.edit().putString(KEY_THEME, value == null ? "paper" : value).apply();
    }

    public String getReaderBackgroundPath() {
        return preferences.getString(KEY_BACKGROUND_PATH, "");
    }

    public void setReaderBackgroundPath(String value) {
        preferences.edit().putString(KEY_BACKGROUND_PATH, value == null ? "" : value).apply();
    }

    public boolean isKeepScreenOn() {
        return preferences.getBoolean(KEY_KEEP_SCREEN_ON, true);
    }

    public void setKeepScreenOn(boolean keepScreenOn) {
        preferences.edit().putBoolean(KEY_KEEP_SCREEN_ON, keepScreenOn).apply();
    }

    public int getAutoPageSeconds() {
        return clamp(preferences.getInt(KEY_AUTO_PAGE_SECONDS, 10), 1, 30);
    }

    public void setAutoPageSeconds(int value) {
        preferences.edit().putInt(KEY_AUTO_PAGE_SECONDS, clamp(value, 1, 30)).apply();
    }

    public String getTtsEngine() {
        return "mimo";
    }

    public void setTtsEngine(String value) {
        // Always use mimo, ignore other values
    }

    public float getTtsRate() {
        return preferences.getFloat(KEY_TTS_RATE, 1f);
    }

    public void setTtsRate(float value) {
        preferences.edit().putFloat(KEY_TTS_RATE, clamp(value, 0.5f, 2f)).apply();
    }

    public String getTtsMimoApiKey() {
        return preferences.getString(KEY_TTS_MIMO_API_KEY, "");
    }

    public void setTtsMimoApiKey(String value) {
        preferences.edit().putString(KEY_TTS_MIMO_API_KEY, value == null ? "" : value.trim()).apply();
    }

    public String getFlipMode() {
        return normalizeFlipMode(preferences.getString(KEY_FLIP_MODE, "slide"));
    }

    public void setFlipMode(String value) {
        preferences.edit().putString(KEY_FLIP_MODE, normalizeFlipMode(value)).apply();
    }

    public String getReaderSliderMode() {
        String value = preferences.getString(KEY_READER_SLIDER_MODE, "book");
        return "chapter".equals(value) ? "chapter" : "book";
    }

    public void setReaderSliderMode(String value) {
        preferences.edit().putString(KEY_READER_SLIDER_MODE, "chapter".equals(value) ? "chapter" : "book").apply();
    }

    public String getVolumeKeyUpAction() {
        return normalizeVolumeKeyAction(preferences.getString(KEY_VOLUME_KEY_UP_ACTION, "page_up"), "page_up");
    }

    public void setVolumeKeyUpAction(String value) {
        preferences.edit().putString(KEY_VOLUME_KEY_UP_ACTION, normalizeVolumeKeyAction(value, "page_up")).apply();
    }

    public String getVolumeKeyDownAction() {
        return normalizeVolumeKeyAction(preferences.getString(KEY_VOLUME_KEY_DOWN_ACTION, "page_down"), "page_down");
    }

    public void setVolumeKeyDownAction(String value) {
        preferences.edit().putString(KEY_VOLUME_KEY_DOWN_ACTION, normalizeVolumeKeyAction(value, "page_down")).apply();
    }

    public boolean isChapterTitleVisible() {
        return preferences.getBoolean(KEY_CHAPTER_TITLE_VISIBILITY, true);
    }

    public void setChapterTitleVisible(boolean visible) {
        preferences.edit().putBoolean(KEY_CHAPTER_TITLE_VISIBILITY, visible).apply();
    }

    public String getBookshelfViewMode() {
        String value = preferences.getString(KEY_BOOKSHELF_VIEW_MODE, "card");
        return "list".equals(value) ? "list" : "card";
    }

    public void setBookshelfViewMode(String value) {
        preferences.edit().putString(KEY_BOOKSHELF_VIEW_MODE, "list".equals(value) ? "list" : "card").apply();
    }

    public int getGlassOpacityPercent() {
        return clamp(preferences.getInt(KEY_GLASS_OPACITY_PERCENT, 80), 20, 100);
    }

    public void setGlassOpacityPercent(int value) {
        preferences.edit().putInt(KEY_GLASS_OPACITY_PERCENT, clamp(value, 20, 100)).apply();
    }

    public String getHudTopLeft() {
        return normalizeHudSlot(preferences.getString(KEY_HUD_TOP_LEFT, "title"));
    }

    public void setHudTopLeft(String value) {
        preferences.edit().putString(KEY_HUD_TOP_LEFT, normalizeHudSlot(value)).apply();
    }

    public String getHudTopCenter() {
        return normalizeHudSlot(preferences.getString(KEY_HUD_TOP_CENTER, "none"));
    }

    public void setHudTopCenter(String value) {
        preferences.edit().putString(KEY_HUD_TOP_CENTER, normalizeHudSlot(value)).apply();
    }

    public String getHudTopRight() {
        return normalizeHudSlot(preferences.getString(KEY_HUD_TOP_RIGHT, "time"));
    }

    public void setHudTopRight(String value) {
        preferences.edit().putString(KEY_HUD_TOP_RIGHT, normalizeHudSlot(value)).apply();
    }

    public String getHudBottomLeft() {
        return normalizeHudSlot(preferences.getString(KEY_HUD_BOTTOM_LEFT, "chapter"));
    }

    public void setHudBottomLeft(String value) {
        preferences.edit().putString(KEY_HUD_BOTTOM_LEFT, normalizeHudSlot(value)).apply();
    }

    public String getHudBottomCenter() {
        return normalizeHudSlot(preferences.getString(KEY_HUD_BOTTOM_CENTER, "none"));
    }

    public void setHudBottomCenter(String value) {
        preferences.edit().putString(KEY_HUD_BOTTOM_CENTER, normalizeHudSlot(value)).apply();
    }

    public String getHudBottomRight() {
        return normalizeHudSlot(preferences.getString(KEY_HUD_BOTTOM_RIGHT, "page_and_progress"));
    }

    public void setHudBottomRight(String value) {
        preferences.edit().putString(KEY_HUD_BOTTOM_RIGHT, normalizeHudSlot(value)).apply();
    }

    public String getWebDavProgressBaseUrl() {
        String base = normalizeBaseUrl(getWebDavUrl());
        String dir = normalizeDirectory(getWebDavDir());
        return base + dir;
    }

    public JSONObject exportAsJson() {
        JSONObject object = new JSONObject();
        try {
            Map<String, ?> all = preferences.getAll();
            for (Map.Entry<String, ?> entry : all.entrySet()) {
                object.put(entry.getKey(), entry.getValue());
            }
        } catch (Exception ignore) {
        }
        return object;
    }

    public void importFromJson(JSONObject jsonObject) {
        SharedPreferences.Editor editor = preferences.edit();
        editor.clear();
        Iterator<String> iterator = jsonObject.keys();
        while (iterator.hasNext()) {
            String key = iterator.next();
            Object value = jsonObject.opt(key);
            if (value instanceof Boolean) {
                editor.putBoolean(key, (Boolean) value);
            } else if (value instanceof Integer) {
                editor.putInt(key, (Integer) value);
            } else if (value instanceof Long) {
                editor.putLong(key, (Long) value);
            } else if (value instanceof Double) {
                editor.putFloat(key, ((Double) value).floatValue());
            } else if (value instanceof String) {
                editor.putString(key, (String) value);
            }
        }
        editor.apply();
    }

    private String normalizeBaseUrl(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (!trimmed.isEmpty() && !trimmed.endsWith("/")) {
            return trimmed + "/";
        }
        return trimmed;
    }

    private String normalizeDirectory(String value) {
        String trimmed = value == null ? "" : value.trim();
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        if (!trimmed.isEmpty() && !trimmed.endsWith("/")) {
            trimmed = trimmed + "/";
        }
        return trimmed;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String normalizeAppThemeMode(String value) {
        if ("light".equals(value) || "dark".equals(value)) {
            return value;
        }
        return "system";
    }

    private static String normalizeReaderUiThemeMode(String value) {
        if ("system".equals(value) || "light".equals(value) || "dark".equals(value)) {
            return value;
        }
        return "follow_app";
    }

    private static String normalizeReaderFontFamily(String value) {
        if ("sans-serif".equals(value)
                || "monospace".equals(value)
                || "system_default".equals(value)) {
            return value;
        }
        if ("serif".equals(value) || "system-ui".equals(value)) {
            return "system_default";
        }
        return "system_default";
    }

    private static int normalizeReaderFontWeight(int value) {
        if (value <= 325) {
            return 250;
        }
        if (value >= 550) {
            return 700;
        }
        return 400;
    }

    private static String normalizeReaderTextColor(String value) {
        if ("ink_brown".equals(value)
                || "graphite".equals(value)
                || "warm_gray".equals(value)
                || "jade_ink".equals(value)
                || "forest_ink".equals(value)
                || "moon_white".equals(value)
                || "theme_default".equals(value)) {
            return value;
        }
        return "theme_default";
    }

    private static String normalizeFlipMode(String value) {
        if ("cover".equals(value) || "slide".equals(value) || "simulation".equals(value) || "scroll".equals(value) || "none".equals(value)) {
            return value;
        }
        if ("flip".equals(value)) {
            return "simulation";
        }
        if ("fade".equals(value)) {
            return "scroll";
        }
        return "slide";
    }

    private static String normalizeVolumeKeyAction(String value, String fallback) {
        if ("system".equals(value) || "page_up".equals(value) || "page_down".equals(value)) {
            return value;
        }
        return fallback;
    }

    private static String normalizeHudSlot(String value) {
        if ("progress".equals(value)) {
            return "book_progress";
        }
        if ("title".equals(value)
                || "chapter".equals(value)
                || "title_chapter".equals(value)
                || "time".equals(value)
                || "battery".equals(value)
                || "chapter_page".equals(value)
                || "book_progress".equals(value)
                || "page_and_progress".equals(value)
                || "time_and_battery".equals(value)) {
            return value;
        }
        return "none";
    }
}
