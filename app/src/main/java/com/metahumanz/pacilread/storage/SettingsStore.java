package com.metahumanz.pacilread.storage;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONObject;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;

public class SettingsStore {
    private static final String PREFS_NAME = "pacil_read_settings";
    private static final String PLATFORM_ANDROID = "android";
    private static final int ANDROID_SETTINGS_SCHEMA_VERSION = 1;
    private static final String JSON_PLATFORM = "platform";
    private static final String JSON_SCHEMA_VERSION = "schemaVersion";
    private static final String JSON_BACKGROUND_FILE = "reader_background_file";
    private static final String DEFAULT_WEB_DAV_ROOT_DIR = "PacilRead";
    private static final String DEFAULT_ANDROID_SETTINGS_DIR = "android-settings";
    private static final int DEFAULT_WEB_DAV_BOOKSHELF_PROGRESS_PREFETCH_LIMIT = 6;
    private static final int MAX_WEB_DAV_BOOKSHELF_PROGRESS_PREFETCH_LIMIT = 100;

    private static final String KEY_AUTO_OPEN = "auto_open_last";
    private static final String KEY_WEB_DAV_ENABLED = "webdav_enabled";
    private static final String KEY_WEB_DAV_URL = "webdav_url";
    private static final String KEY_WEB_DAV_DIR = "webdav_dir";
    private static final String KEY_WEB_DAV_SETTINGS_SUBDIR = "webdav_settings_subdir";
    private static final String KEY_WEB_DAV_USER = "webdav_user";
    private static final String KEY_WEB_DAV_PASSWORD = "webdav_password";
    private static final String KEY_WEB_DAV_LAST_FULL = "webdav_last_full";
    private static final String KEY_WEB_DAV_LAST_LITE = "webdav_last_lite";
    private static final String KEY_WEB_DAV_BOOKSHELF_PROGRESS_PREFETCH_LIMIT =
            "webdav_bookshelf_progress_prefetch_limit";
    private static final String KEY_WEB_DAV_SYNC_BOOKSHELF = "webdav_sync_bookshelf";
    private static final String KEY_WEB_DAV_SYNC_FILES = "webdav_sync_files";
    private static final String KEY_WEB_DAV_SYNC_UI_SETTINGS = "webdav_sync_ui_settings";
    private static final String KEY_WEB_DAV_SYNC_THEMES = "webdav_sync_themes";
    private static final String KEY_WEB_DAV_SYNC_BACKGROUNDS = "webdav_sync_backgrounds";
    private static final String KEY_WEB_DAV_SYNC_READING_STATS = "webdav_sync_reading_stats";
    private static final String KEY_WEB_DAV_CLEAN_REMOTE_ORPHANS = "webdav_clean_remote_orphans";
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
    private static final String KEY_TTS_MIMO_VOICE = "tts_mimo_voice";
    private static final String KEY_TTS_SYSTEM_ENGINE = "tts_system_engine";
    private static final String KEY_TTS_TIMER_MODE = "tts_timer_mode";
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
    private static final String KEY_PARAGRAPH_SPACING = "paragraph_spacing_dp";
    private static final String KEY_BACKGROUND_BLUR_PERCENT = "background_blur_percent";
    private static final String KEY_CUSTOM_TEXT_COLOR = "custom_text_color";
    private static final String KEY_CHAPTER_TITLE_ALIGNMENT = "chapter_title_alignment";
    private static final String KEY_BODY_TEXT_JUSTIFY = "body_text_justify";
    private static final String KEY_FLIP_SPEED = "flip_speed";
    private static final String KEY_HUD_VERTICAL_MARGIN = "hud_vertical_margin";
    private static final String KEY_HUD_TOP_MARGIN = "hud_top_margin";
    private static final String KEY_HUD_BOTTOM_MARGIN = "hud_bottom_margin";
    private static final String KEY_READER_MENU_AUTO_HIDE = "reader_menu_auto_hide";
    private static final String KEY_READER_MENU_PERSISTENT_ACTIONS = "reader_menu_persistent_actions";
    private static final String KEY_READING_TIME_TRACKING_ENABLED = "reading_time_tracking_enabled";
    private static final String KEY_READING_STATS_DEVICE_ID = "reading_stats_device_id";
    private static final String KEY_ANNUAL_REPORT_GLOBAL_METRICS = "annual_report_global_metrics";
    private static final String KEY_ANNUAL_REPORT_BOOK_METRICS = "annual_report_book_metrics";
    private static final String KEY_READER_DOUBLE_PAGE_ENABLED = "reader_double_page_enabled";
    private static final String KEY_READER_DOUBLE_PAGE_MODE = "reader_double_page_mode";
    private static final String KEY_READER_DOUBLE_PAGE_TURN_STEP = "reader_double_page_turn_step";
    private static final String KEY_READER_SIMULATION_DOUBLE_PAGE_TURN_MODE = "reader_simulationDoublePageTurnMode";
    private static final String KEY_READER_AUTO_NIGHT_ENABLED = "reader_auto_night_enabled";
    private static final String KEY_READER_AUTO_NIGHT_CUSTOM_POLICY = "reader_auto_night_custom_policy";
    private static final String KEY_BOOKSHELF_SHOW_ADD_ENTRY = "bookshelf_show_add_entry";
    private static final String KEY_HOME_BOTTOM_NAV_STYLE = "home_bottom_nav_style";
    private static final String KEY_HOME_NAV_PORTRAIT_MODE = "home_nav_portrait_mode";
    private static final String KEY_HOME_NAV_LANDSCAPE_MODE = "home_nav_landscape_mode";
    private static final String KEY_HOME_SIDEBAR_PRESENTATION = "home_sidebar_presentation";
    private static final String KEY_HOME_FIXED_SIDEBAR_STYLE = "home_fixed_sidebar_style";
    private static final String KEY_READER_ORIENTATION_MODE = "reader_orientation_mode";
    private static final String KEY_TRANSITION_MOTION_MODE = "transition_motion_mode";

    private static final Set<String> ANDROID_PRIVATE_SYNC_KEYS = new HashSet<>(Arrays.asList(
            KEY_AUTO_OPEN,
            KEY_WEB_DAV_BOOKSHELF_PROGRESS_PREFETCH_LIMIT,
            KEY_FONT_SIZE,
            KEY_FONT_FAMILY,
            KEY_FONT_WEIGHT,
            KEY_TEXT_COLOR,
            KEY_LINE_SPACING,
            KEY_LEFT_PADDING,
            KEY_RIGHT_PADDING,
            KEY_TOP_PADDING,
            KEY_BOTTOM_PADDING,
            KEY_APP_THEME_MODE,
            KEY_READER_UI_THEME_MODE,
            KEY_APP_LIGHT_STYLE_VARIANT,
            KEY_APP_DARK_STYLE_VARIANT,
            KEY_THEME,
            KEY_KEEP_SCREEN_ON,
            KEY_AUTO_PAGE_SECONDS,
            KEY_TTS_ENGINE,
            KEY_TTS_RATE,
            KEY_TTS_MIMO_API_KEY,
            KEY_TTS_MIMO_VOICE,
            KEY_TTS_SYSTEM_ENGINE,
            KEY_TTS_TIMER_MODE,
            KEY_FLIP_MODE,
            KEY_READER_SLIDER_MODE,
            KEY_VOLUME_KEY_UP_ACTION,
            KEY_VOLUME_KEY_DOWN_ACTION,
            KEY_CHAPTER_TITLE_VISIBILITY,
            KEY_BOOKSHELF_VIEW_MODE,
            KEY_GLASS_OPACITY_PERCENT,
            KEY_HUD_TOP_LEFT,
            KEY_HUD_TOP_CENTER,
            KEY_HUD_TOP_RIGHT,
            KEY_HUD_BOTTOM_LEFT,
            KEY_HUD_BOTTOM_CENTER,
            KEY_HUD_BOTTOM_RIGHT,
            KEY_LETTER_SPACING,
            KEY_FIRST_LINE_INDENT,
            KEY_PARAGRAPH_SPACING,
            KEY_BACKGROUND_BLUR_PERCENT,
            KEY_CUSTOM_TEXT_COLOR,
            KEY_CHAPTER_TITLE_ALIGNMENT,
            KEY_BODY_TEXT_JUSTIFY,
            KEY_FLIP_SPEED,
            KEY_HUD_VERTICAL_MARGIN,
            KEY_HUD_TOP_MARGIN,
            KEY_HUD_BOTTOM_MARGIN,
            KEY_READER_MENU_AUTO_HIDE,
            KEY_READER_MENU_PERSISTENT_ACTIONS,
            KEY_READING_TIME_TRACKING_ENABLED,
            KEY_ANNUAL_REPORT_GLOBAL_METRICS,
            KEY_ANNUAL_REPORT_BOOK_METRICS,
            KEY_READER_DOUBLE_PAGE_ENABLED,
            KEY_READER_DOUBLE_PAGE_MODE,
            KEY_READER_DOUBLE_PAGE_TURN_STEP,
            KEY_READER_SIMULATION_DOUBLE_PAGE_TURN_MODE,
            KEY_READER_AUTO_NIGHT_ENABLED,
            KEY_READER_AUTO_NIGHT_CUSTOM_POLICY,
            KEY_BOOKSHELF_SHOW_ADD_ENTRY,
            KEY_HOME_BOTTOM_NAV_STYLE,
            KEY_HOME_NAV_PORTRAIT_MODE,
            KEY_HOME_NAV_LANDSCAPE_MODE,
            KEY_HOME_SIDEBAR_PRESENTATION,
            KEY_HOME_FIXED_SIDEBAR_STYLE,
            KEY_READER_ORIENTATION_MODE,
            KEY_TRANSITION_MOTION_MODE
    ));

    private static final Set<String> FLOAT_SYNC_KEYS = new HashSet<>(Arrays.asList(
            KEY_FONT_SIZE,
            KEY_LINE_SPACING,
            KEY_TTS_RATE,
            KEY_LETTER_SPACING
    ));

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
        return normalizeDirectoryOrDefault(preferences.getString(KEY_WEB_DAV_DIR, DEFAULT_WEB_DAV_ROOT_DIR), DEFAULT_WEB_DAV_ROOT_DIR);
    }

    public void setWebDavDir(String value) {
        preferences.edit().putString(KEY_WEB_DAV_DIR, normalizeDirectoryOrDefault(value, DEFAULT_WEB_DAV_ROOT_DIR)).apply();
    }

    public String getWebDavSettingsSubdir() {
        return normalizeDirectoryOrDefault(
                preferences.getString(KEY_WEB_DAV_SETTINGS_SUBDIR, DEFAULT_ANDROID_SETTINGS_DIR),
                DEFAULT_ANDROID_SETTINGS_DIR
        );
    }

    public void setWebDavSettingsSubdir(String value) {
        preferences.edit().putString(
                KEY_WEB_DAV_SETTINGS_SUBDIR,
                normalizeDirectoryOrDefault(value, DEFAULT_ANDROID_SETTINGS_DIR)
        ).apply();
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

    public int getWebDavBookshelfProgressPrefetchLimit() {
        return clamp(
                preferences.getInt(
                        KEY_WEB_DAV_BOOKSHELF_PROGRESS_PREFETCH_LIMIT,
                        DEFAULT_WEB_DAV_BOOKSHELF_PROGRESS_PREFETCH_LIMIT
                ),
                0,
                MAX_WEB_DAV_BOOKSHELF_PROGRESS_PREFETCH_LIMIT
        );
    }

    public void setWebDavBookshelfProgressPrefetchLimit(int value) {
        preferences.edit().putInt(
                KEY_WEB_DAV_BOOKSHELF_PROGRESS_PREFETCH_LIMIT,
                clamp(value, 0, MAX_WEB_DAV_BOOKSHELF_PROGRESS_PREFETCH_LIMIT)
        ).apply();
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

    public boolean isWebDavCleanRemoteOrphansEnabled() {
        return preferences.getBoolean(KEY_WEB_DAV_CLEAN_REMOTE_ORPHANS, false);
    }

    public void setWebDavCleanRemoteOrphansEnabled(boolean enabled) {
        preferences.edit().putBoolean(KEY_WEB_DAV_CLEAN_REMOTE_ORPHANS, enabled).apply();
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

    public String getAnnualReportMetricSelection(boolean bookScope) {
        return preferences.getString(
                bookScope ? KEY_ANNUAL_REPORT_BOOK_METRICS : KEY_ANNUAL_REPORT_GLOBAL_METRICS,
                ""
        );
    }

    public void setAnnualReportMetricSelection(boolean bookScope, String value) {
        preferences.edit().putString(
                bookScope ? KEY_ANNUAL_REPORT_BOOK_METRICS : KEY_ANNUAL_REPORT_GLOBAL_METRICS,
                value == null ? "" : value.trim()
        ).apply();
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

    public String getTtsMimoVoice() {
        return normalizeTtsMimoVoice(preferences.getString(KEY_TTS_MIMO_VOICE, "冰糖"));
    }

    public void setTtsMimoVoice(String value) {
        preferences.edit().putString(KEY_TTS_MIMO_VOICE, normalizeTtsMimoVoice(value)).apply();
    }

    public String getTtsSystemEnginePackage() {
        return preferences.getString(KEY_TTS_SYSTEM_ENGINE, "");
    }

    public void setTtsSystemEnginePackage(String packageName) {
        preferences.edit().putString(KEY_TTS_SYSTEM_ENGINE, packageName == null ? "" : packageName.trim()).apply();
    }

    public String getTtsTimerMode() {
        return "precise".equals(preferences.getString(KEY_TTS_TIMER_MODE, "slider"))
                ? "precise" : "slider";
    }

    public void setTtsTimerMode(String mode) {
        preferences.edit().putString(KEY_TTS_TIMER_MODE, "precise".equals(mode) ? "precise" : "slider").apply();
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
        return preferences.getBoolean(KEY_READER_MENU_AUTO_HIDE, false);
    }

    public void setReaderMenuAutoHideEnabled(boolean enabled) {
        preferences.edit().putBoolean(KEY_READER_MENU_AUTO_HIDE, enabled).apply();
    }

    public boolean isReaderMenuPersistentActionsEnabled() {
        return preferences.getBoolean(KEY_READER_MENU_PERSISTENT_ACTIONS, false);
    }

    public void setReaderMenuPersistentActionsEnabled(boolean enabled) {
        preferences.edit().putBoolean(KEY_READER_MENU_PERSISTENT_ACTIONS, enabled).apply();
    }

    public boolean isReaderDoublePageEnabled() {
        return preferences.getBoolean(KEY_READER_DOUBLE_PAGE_ENABLED, false);
    }

    public void setReaderDoublePageEnabled(boolean enabled) {
        preferences.edit().putBoolean(KEY_READER_DOUBLE_PAGE_ENABLED, enabled).apply();
    }

    public String getReaderDoublePageMode() {
        return normalizeReaderDoublePageMode(preferences.getString(KEY_READER_DOUBLE_PAGE_MODE, "landscape"));
    }

    public void setReaderDoublePageMode(String value) {
        preferences.edit().putString(KEY_READER_DOUBLE_PAGE_MODE, normalizeReaderDoublePageMode(value)).apply();
    }

    public String getReaderDoublePageTurnStep() {
        return normalizeReaderDoublePageTurnStep(preferences.getString(KEY_READER_DOUBLE_PAGE_TURN_STEP, "two"));
    }

    public void setReaderDoublePageTurnStep(String value) {
        preferences.edit().putString(KEY_READER_DOUBLE_PAGE_TURN_STEP, normalizeReaderDoublePageTurnStep(value)).apply();
    }

    public String getSimulationDoublePageTurnMode() {
        return normalizeSimulationDoublePageTurnMode(
                preferences.getString(KEY_READER_SIMULATION_DOUBLE_PAGE_TURN_MODE, "outerPage")
        );
    }

    public void setSimulationDoublePageTurnMode(String value) {
        preferences.edit().putString(
                KEY_READER_SIMULATION_DOUBLE_PAGE_TURN_MODE,
                normalizeSimulationDoublePageTurnMode(value)
        ).apply();
    }

    public boolean isReaderAutoNightEnabled() {
        return preferences.getBoolean(KEY_READER_AUTO_NIGHT_ENABLED, true);
    }

    public void setReaderAutoNightEnabled(boolean enabled) {
        preferences.edit().putBoolean(KEY_READER_AUTO_NIGHT_ENABLED, enabled).apply();
    }

    public String getReaderAutoNightCustomPolicy() {
        return normalizeReaderAutoNightCustomPolicy(
                preferences.getString(KEY_READER_AUTO_NIGHT_CUSTOM_POLICY, "ask")
        );
    }

    public void setReaderAutoNightCustomPolicy(String value) {
        preferences.edit().putString(
                KEY_READER_AUTO_NIGHT_CUSTOM_POLICY,
                normalizeReaderAutoNightCustomPolicy(value)
        ).apply();
    }

    public boolean isBookshelfAddEntryVisible() {
        return preferences.getBoolean(KEY_BOOKSHELF_SHOW_ADD_ENTRY, true);
    }

    public void setBookshelfAddEntryVisible(boolean visible) {
        preferences.edit().putBoolean(KEY_BOOKSHELF_SHOW_ADD_ENTRY, visible).apply();
    }

    public String getHomeBottomNavStyle() {
        return normalizeHomeBottomNavStyle(preferences.getString(KEY_HOME_BOTTOM_NAV_STYLE, "icons"));
    }

    public void setHomeBottomNavStyle(String value) {
        preferences.edit().putString(KEY_HOME_BOTTOM_NAV_STYLE, normalizeHomeBottomNavStyle(value)).apply();
    }

    public String getPortraitHomeNavigationMode() {
        return normalizeHomeNavigationMode(preferences.getString(KEY_HOME_NAV_PORTRAIT_MODE, "auto"));
    }

    public void setPortraitHomeNavigationMode(String value) {
        preferences.edit().putString(KEY_HOME_NAV_PORTRAIT_MODE, normalizeHomeNavigationMode(value)).apply();
    }

    public String getLandscapeHomeNavigationMode() {
        return normalizeHomeNavigationMode(preferences.getString(KEY_HOME_NAV_LANDSCAPE_MODE, "auto"));
    }

    public void setLandscapeHomeNavigationMode(String value) {
        preferences.edit().putString(KEY_HOME_NAV_LANDSCAPE_MODE, normalizeHomeNavigationMode(value)).apply();
    }

    public String getReaderOrientationMode() {
        return normalizeReaderOrientationMode(preferences.getString(KEY_READER_ORIENTATION_MODE, "system"));
    }

    public void setReaderOrientationMode(String value) {
        preferences.edit().putString(KEY_READER_ORIENTATION_MODE, normalizeReaderOrientationMode(value)).apply();
    }

    public String getTransitionMotionMode() {
        String defaultMode = Build.VERSION.SDK_INT >= 34 ? "fluid" : "simple";
        String value = preferences.getString(KEY_TRANSITION_MOTION_MODE, defaultMode);
        if (Build.VERSION.SDK_INT < 34) {
            return "simple";
        }
        return "simple".equals(value) ? "simple" : "fluid";
    }

    public void setTransitionMotionMode(String value) {
        String mode = Build.VERSION.SDK_INT < 34 || "simple".equals(value) ? "simple" : "fluid";
        preferences.edit().putString(KEY_TRANSITION_MOTION_MODE, mode).apply();
    }

    public String getHomeSidebarPresentation() {
        return normalizeHomeSidebarPresentation(preferences.getString(KEY_HOME_SIDEBAR_PRESENTATION, "slide"));
    }

    public void setHomeSidebarPresentation(String value) {
        preferences.edit().putString(KEY_HOME_SIDEBAR_PRESENTATION, normalizeHomeSidebarPresentation(value)).apply();
    }

    public String getHomeFixedSidebarStyle() {
        return normalizeHomeFixedSidebarStyle(preferences.getString(KEY_HOME_FIXED_SIDEBAR_STYLE, "full"));
    }

    public void setHomeFixedSidebarStyle(String value) {
        preferences.edit().putString(KEY_HOME_FIXED_SIDEBAR_STYLE, normalizeHomeFixedSidebarStyle(value)).apply();
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

    public int getParagraphSpacingDp() {
        return clamp(preferences.getInt(KEY_PARAGRAPH_SPACING, 4), 0, 32);
    }

    public void setParagraphSpacingDp(int value) {
        preferences.edit().putInt(KEY_PARAGRAPH_SPACING, clamp(value, 0, 32)).apply();
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
        return base + parentDirectory(getWebDavDir());
    }

    public String getWebDavProgressDir() {
        return parentDirectory(getWebDavDir());
    }

    public JSONObject exportAndroidPrivateSettingsJson() {
        JSONObject object = new JSONObject();
        try {
            object.put(JSON_PLATFORM, PLATFORM_ANDROID);
            object.put(JSON_SCHEMA_VERSION, ANDROID_SETTINGS_SCHEMA_VERSION);
            Map<String, ?> all = preferences.getAll();
            for (Map.Entry<String, ?> entry : all.entrySet()) {
                if (ANDROID_PRIVATE_SYNC_KEYS.contains(entry.getKey())) {
                    object.put(entry.getKey(), entry.getValue());
                }
            }
            object.put(JSON_BACKGROUND_FILE, readerBackgroundFileName());
        } catch (Exception ignore) {
        }
        return object;
    }

    public void importAndroidPrivateSettingsJson(JSONObject jsonObject, String restoredBackgroundPath) {
        if (jsonObject == null) {
            throw new IllegalArgumentException("设置快照为空");
        }
        if (!PLATFORM_ANDROID.equals(jsonObject.optString(JSON_PLATFORM, ""))) {
            throw new IllegalArgumentException("设置快照不是 Android 平台");
        }
        SharedPreferences.Editor editor = preferences.edit();
        Iterator<String> iterator = jsonObject.keys();
        while (iterator.hasNext()) {
            String key = iterator.next();
            if (!ANDROID_PRIVATE_SYNC_KEYS.contains(key)) {
                continue;
            }
            putJsonPreference(editor, key, jsonObject.opt(key));
        }
        if (restoredBackgroundPath != null && !restoredBackgroundPath.isBlank()) {
            editor.putString(KEY_BACKGROUND_PATH, restoredBackgroundPath);
        } else if (jsonObject.has(JSON_BACKGROUND_FILE) && jsonObject.optString(JSON_BACKGROUND_FILE, "").isBlank()) {
            editor.putString(KEY_BACKGROUND_PATH, "");
        }
        editor.apply();
    }

    public String readerBackgroundFileName() {
        String path = getReaderBackgroundPath();
        if (path == null || path.isBlank()) {
            return "";
        }
        return new File(path).getName();
    }

    public String androidSettingsBackgroundFileName(JSONObject jsonObject) {
        if (jsonObject == null) {
            return "";
        }
        String value = jsonObject.optString(JSON_BACKGROUND_FILE, "");
        return sanitizeRemoteFileName(value);
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

    private String normalizeDirectoryOrDefault(String value, String fallback) {
        String normalized = normalizeDirectory(value);
        return normalized.isBlank() ? normalizeDirectory(fallback) : normalized;
    }

    private String parentDirectory(String directory) {
        String normalized = normalizeDirectory(directory);
        if (normalized.isBlank()) {
            return "";
        }
        String withoutTrailing = normalized.substring(0, normalized.length() - 1);
        int slashIndex = withoutTrailing.lastIndexOf('/');
        if (slashIndex < 0) {
            return "";
        }
        return withoutTrailing.substring(0, slashIndex + 1);
    }

    private void putJsonPreference(SharedPreferences.Editor editor, String key, Object value) {
        if (value instanceof Boolean) {
            editor.putBoolean(key, (Boolean) value);
        } else if (value instanceof Number) {
            Number number = (Number) value;
            if (FLOAT_SYNC_KEYS.contains(key)) {
                editor.putFloat(key, number.floatValue());
            } else {
                editor.putInt(key, number.intValue());
            }
        } else if (value instanceof String) {
            editor.putString(key, (String) value);
        }
    }

    private String sanitizeRemoteFileName(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String name = new File(value).getName();
        if (name.contains("/") || name.contains("\\") || ".".equals(name) || "..".equals(name)) {
            return "";
        }
        return name;
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

    public static String normalizeReaderDoublePageMode(String value) {
        if ("always".equals(value) || "landscape_or_tablet".equals(value)) {
            return value;
        }
        return "landscape";
    }

    public static String normalizeReaderDoublePageTurnStep(String value) {
        return "one".equals(value) ? "one" : "two";
    }

    public static String normalizeSimulationDoublePageTurnMode(String value) {
        return "spread".equals(value) ? "spread" : "outerPage";
    }

    public static String normalizeReaderAutoNightCustomPolicy(String value) {
        if ("override".equals(value) || "preserve".equals(value)) {
            return value;
        }
        return "ask";
    }

    public static String normalizeHomeBottomNavStyle(String value) {
        return "text".equals(value) ? "text" : "icons";
    }

    public static String normalizeHomeNavigationMode(String value) {
        if ("bottom".equals(value) || "sidebar".equals(value)) {
            return value;
        }
        return "auto";
    }

    public static String normalizeReaderOrientationMode(String value) {
        if ("portrait".equals(value) || "landscape".equals(value)) {
            return value;
        }
        return "system";
    }

    public static String normalizeHomeSidebarPresentation(String value) {
        return "fixed_wide".equals(value) ? "fixed_wide" : "slide";
    }

    public static String normalizeHomeFixedSidebarStyle(String value) {
        return "icons".equals(value) ? "icons" : "full";
    }

    private static String normalizeTtsEngine(String value) {
        return "mimo".equals(value) ? "mimo" : "system";
    }

    public static String normalizeTtsMimoVoice(String value) {
        if ("冰糖".equals(value) || "茉莉".equals(value) || "苏打".equals(value) || "白桦".equals(value)) {
            return value;
        }
        return "冰糖";
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
                || "custom".equals(value)
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
