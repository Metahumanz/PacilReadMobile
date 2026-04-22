package com.metahumanz.pacilread.storage;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.Map;
import java.util.Iterator;
import java.util.UUID;

public class SettingsStore {
    private static final String PREFS_NAME = "pacil_read_settings";

    private static final String KEY_AUTO_OPEN = "auto_open_last";
    private static final String KEY_WEB_DAV_ENABLED = "webdav_enabled";
    private static final String KEY_WEB_DAV_URL = "webdav_url";
    private static final String KEY_WEB_DAV_DIR = "webdav_dir";
    private static final String KEY_WEB_DAV_SETTINGS_SUBDIR = "webdav_settings_subdir";
    private static final String KEY_WEB_DAV_USER = "webdav_user";
    private static final String KEY_WEB_DAV_PASSWORD = "webdav_password";
    private static final String KEY_WEB_DAV_LAST_FULL = "webdav_last_full";
    private static final String KEY_WEB_DAV_LAST_LITE = "webdav_last_lite";
    private static final String KEY_WEB_DAV_SYNC_BOOKSHELF = "webdav_sync_bookshelf";
    private static final String KEY_WEB_DAV_SYNC_FILES = "webdav_sync_files";
    private static final String KEY_WEB_DAV_SYNC_UI_SETTINGS = "webdav_sync_ui_settings";
    private static final String KEY_WEB_DAV_SYNC_THEMES = "webdav_sync_themes";
    private static final String KEY_WEB_DAV_SYNC_BACKGROUNDS = "webdav_sync_backgrounds";
    private static final String KEY_WEB_DAV_SYNC_READING_STATS = "webdav_sync_reading_stats";
    private static final String KEY_FONT_SIZE = "font_size_sp";
    private static final String KEY_FONT_FAMILY = "font_family";
    private static final String KEY_FONT_WEIGHT = "font_weight";
    private static final String KEY_TEXT_COLOR = "reader_text_color";
    private static final String KEY_LINE_SPACING = "line_spacing_extra";
    private static final String KEY_LEFT_PADDING = "left_padding_dp";
    private static final String KEY_RIGHT_PADDING = "right_padding_dp";
    private static final String KEY_TOP_PADDING = "top_padding_dp";
    private static final String KEY_BOTTOM_PADDING = "bottom_padding_dp";
    private static final String KEY_APP_THEME_MODE = "app_theme_mode";
    private static final String KEY_READER_UI_THEME_MODE = "reader_ui_theme_mode";
    private static final String KEY_APP_LIGHT_STYLE_VARIANT = "app_light_style_variant";
    private static final String KEY_APP_DARK_STYLE_VARIANT = "app_dark_style_variant";
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
    private static final String KEY_LETTER_SPACING = "letter_spacing";
    private static final String KEY_FIRST_LINE_INDENT = "first_line_indent";
    private static final String KEY_BACKGROUND_BLUR_PERCENT = "background_blur_percent";
    private static final String KEY_CUSTOM_TEXT_COLOR = "custom_text_color";
    private static final String KEY_CHAPTER_TITLE_ALIGNMENT = "chapter_title_alignment";
    private static final String KEY_BODY_TEXT_JUSTIFY = "body_text_justify";
    private static final String KEY_FLIP_SPEED = "flip_speed";
    private static final String KEY_HUD_VERTICAL_MARGIN = "hud_vertical_margin";
    private static final String KEY_HUD_TOP_MARGIN = "hud_top_margin";
    private static final String KEY_HUD_BOTTOM_MARGIN = "hud_bottom_margin";
    private static final String KEY_READER_MENU_AUTO_HIDE = "reader_menu_auto_hide";
    private static final String KEY_READING_TIME_TRACKING_ENABLED = "reading_time_tracking_enabled";
    private static final String KEY_READING_STATS_DEVICE_ID = "reading_stats_device_id";

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

    public String getWebDavSettingsSubdir() {
        return normalizeDirectory(preferences.getString(KEY_WEB_DAV_SETTINGS_SUBDIR, ""));
    }

    public void setWebDavSettingsSubdir(String value) {
        preferences.edit().putString(KEY_WEB_DAV_SETTINGS_SUBDIR, normalizeDirectory(value)).apply();
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

    public boolean isWebDavSyncReadingStatsEnabled() {
        return preferences.getBoolean(KEY_WEB_DAV_SYNC_READING_STATS, true);
    }

    public void setWebDavSyncReadingStatsEnabled(boolean enabled) {
        preferences.edit().putBoolean(KEY_WEB_DAV_SYNC_READING_STATS, enabled).apply();
    }

    public boolean isReadingTimeTrackingEnabled() {
        return preferences.getBoolean(KEY_READING_TIME_TRACKING_ENABLED, false);
    }

    public void setReadingTimeTrackingEnabled(boolean enabled) {
        preferences.edit().putBoolean(KEY_READING_TIME_TRACKING_ENABLED, enabled).apply();
    }

    public String getReadingStatsDeviceId() {
        String value = preferences.getString(KEY_READING_STATS_DEVICE_ID, "");
        if (value != null && !value.isBlank()) {
            return value;
        }
        String created = UUID.randomUUID().toString();
        preferences.edit().putString(KEY_READING_STATS_DEVICE_ID, created).apply();
        return created;
    }

    public float getFontSizeSp() {
        return preferences.getFloat(KEY_FONT_SIZE, 18f);
    }

    public void setFontSizeSp(float value) {
        preferences.edit().putFloat(KEY_FONT_SIZE, clamp(value, 12f, 64f)).apply();
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

    public int getLeftPaddingDp() {
        return preferences.getInt(KEY_LEFT_PADDING, preferences.getInt("side_padding_dp", 18));
    }

    public void setLeftPaddingDp(int value) {
        preferences.edit().putInt(KEY_LEFT_PADDING, clamp(value, 0, 48)).apply();
    }

    public int getRightPaddingDp() {
        return preferences.getInt(KEY_RIGHT_PADDING, preferences.getInt("side_padding_dp", 18));
    }

    public void setRightPaddingDp(int value) {
        preferences.edit().putInt(KEY_RIGHT_PADDING, clamp(value, 0, 48)).apply();
    }

    public int getTopPaddingDp() {
        return preferences.getInt(KEY_TOP_PADDING, 8);
    }

    public void setTopPaddingDp(int value) {
        preferences.edit().putInt(KEY_TOP_PADDING, clamp(value, 0, 128)).apply();
    }

    public int getBottomPaddingDp() {
        return preferences.getInt(KEY_BOTTOM_PADDING, 8);
    }

    public void setBottomPaddingDp(int value) {
        preferences.edit().putInt(KEY_BOTTOM_PADDING, clamp(value, 0, 128)).apply();
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

    public String getAppLightStyleVariant() {
        return normalizeAppLightStyleVariant(preferences.getString(KEY_APP_LIGHT_STYLE_VARIANT, "yunbai"));
    }

    public void setAppLightStyleVariant(String value) {
        preferences.edit().putString(KEY_APP_LIGHT_STYLE_VARIANT, normalizeAppLightStyleVariant(value)).apply();
    }

    public String getAppDarkStyleVariant() {
        return normalizeAppDarkStyleVariant(preferences.getString(KEY_APP_DARK_STYLE_VARIANT, "yemu"));
    }

    public void setAppDarkStyleVariant(String value) {
        preferences.edit().putString(KEY_APP_DARK_STYLE_VARIANT, normalizeAppDarkStyleVariant(value)).apply();
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
        String value = preferences.getString(KEY_TTS_ENGINE, null);
        if ("system".equals(value) || "mimo".equals(value)) {
            return value;
        }
        return getTtsMimoApiKey().isBlank() ? "system" : "mimo";
    }

    public void setTtsEngine(String value) {
        preferences.edit().putString(KEY_TTS_ENGINE, normalizeTtsEngine(value)).apply();
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

    public String getFlipSpeed() {
        return preferences.getString(KEY_FLIP_SPEED, "medium");
    }

    public void setFlipSpeed(String value) {
        preferences.edit().putString(KEY_FLIP_SPEED, value).apply();
    }

    public int getHudTopMarginDp() {
        return preferences.getInt(
                KEY_HUD_TOP_MARGIN,
                preferences.getInt(KEY_HUD_VERTICAL_MARGIN, 2)
        );
    }

    public void setHudTopMarginDp(int value) {
        preferences.edit().putInt(KEY_HUD_TOP_MARGIN, clamp(value, 0, 32)).apply();
    }

    public int getHudBottomMarginDp() {
        return preferences.getInt(
                KEY_HUD_BOTTOM_MARGIN,
                preferences.getInt(KEY_HUD_VERTICAL_MARGIN, 2)
        );
    }

    public void setHudBottomMarginDp(int value) {
        preferences.edit().putInt(KEY_HUD_BOTTOM_MARGIN, clamp(value, 0, 32)).apply();
    }

    public int getHudVerticalMarginDp() {
        return preferences.getInt(KEY_HUD_VERTICAL_MARGIN, 2);
    }

    public void setHudVerticalMarginDp(int value) {
        int clamped = clamp(value, 0, 32);
        preferences.edit()
                .putInt(KEY_HUD_VERTICAL_MARGIN, clamped)
                .putInt(KEY_HUD_TOP_MARGIN, clamped)
                .putInt(KEY_HUD_BOTTOM_MARGIN, clamped)
                .apply();
    }

    public boolean isReaderMenuAutoHideEnabled() {
        return preferences.getBoolean(KEY_READER_MENU_AUTO_HIDE, true);
    }

    public void setReaderMenuAutoHideEnabled(boolean enabled) {
        preferences.edit().putBoolean(KEY_READER_MENU_AUTO_HIDE, enabled).apply();
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

    public float getLetterSpacing() {
        return normalizeLetterSpacing(preferences.getFloat(KEY_LETTER_SPACING, 0f));
    }

    public void setLetterSpacing(float value) {
        preferences.edit().putFloat(KEY_LETTER_SPACING, normalizeLetterSpacing(value)).apply();
    }

    public int getFirstLineIndentDp() {
        return preferences.getInt(KEY_FIRST_LINE_INDENT, 2);
    }

    public void setFirstLineIndentDp(int value) {
        preferences.edit().putInt(KEY_FIRST_LINE_INDENT, clamp(value, 0, 8)).apply();
    }

    public int getBackgroundBlurPercent() {
        return clamp(preferences.getInt(KEY_BACKGROUND_BLUR_PERCENT, 0), 0, 100);
    }

    public void setBackgroundBlurPercent(int value) {
        preferences.edit().putInt(KEY_BACKGROUND_BLUR_PERCENT, clamp(value, 0, 100)).apply();
    }

    public String getCustomTextColor() {
        return preferences.getString(KEY_CUSTOM_TEXT_COLOR, "");
    }

    public void setCustomTextColor(String value) {
        preferences.edit().putString(KEY_CUSTOM_TEXT_COLOR, value == null ? "" : value).apply();
    }

    public String getChapterTitleAlignment() {
        String value = preferences.getString(KEY_CHAPTER_TITLE_ALIGNMENT, "left");
        return "center".equals(value) ? "center" : "left";
    }

    public void setChapterTitleAlignment(String value) {
        preferences.edit().putString(KEY_CHAPTER_TITLE_ALIGNMENT, "center".equals(value) ? "center" : "left").apply();
    }

    public boolean isBodyTextJustified() {
        return preferences.getBoolean(KEY_BODY_TEXT_JUSTIFY, true);
    }

    public void setBodyTextJustified(boolean justified) {
        preferences.edit().putBoolean(KEY_BODY_TEXT_JUSTIFY, justified).apply();
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

    public static String normalizeAppThemeMode(String value) {
        if ("light".equals(value) || "dark".equals(value)) {
            return value;
        }
        return "system";
    }

    public static String normalizeReaderUiThemeMode(String value) {
        if ("system".equals(value) || "light".equals(value) || "dark".equals(value)) {
            return value;
        }
        return "follow_app";
    }

    public static String normalizeAppLightStyleVariant(String value) {
        return "yaobai".equals(value) ? "yaobai" : "yunbai";
    }

    public static String normalizeAppDarkStyleVariant(String value) {
        return "jiye".equals(value) ? "jiye" : "yemu";
    }

    private static String normalizeTtsEngine(String value) {
        return "mimo".equals(value) ? "mimo" : "system";
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

    private static float normalizeLetterSpacing(float value) {
        float clamped = clamp(value, 0f, 1f);
        return Math.round(clamped * 20f) / 20f;
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
