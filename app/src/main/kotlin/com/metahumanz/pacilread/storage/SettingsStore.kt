package com.metahumanz.pacilread.storage

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

open class SettingsStore(context: Context) {
    private val preferences: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var isAutoOpenLastBook: Boolean
        get() = preferences.getBoolean(KEY_AUTO_OPEN, false)
        set(value) = edit { putBoolean(KEY_AUTO_OPEN, value) }

    var isWebDavEnabled: Boolean
        get() = preferences.getBoolean(KEY_WEB_DAV_ENABLED, false)
        set(value) = edit { putBoolean(KEY_WEB_DAV_ENABLED, value) }

    var webDavUrl: String
        get() = preferences.getString(KEY_WEB_DAV_URL, "").orEmpty()
        set(value) = edit { putString(KEY_WEB_DAV_URL, normalizeBaseUrl(value)) }

    var webDavDir: String
        get() = normalizeDirectoryOrDefault(
            preferences.getString(KEY_WEB_DAV_DIR, DEFAULT_WEB_DAV_ROOT_DIR),
            DEFAULT_WEB_DAV_ROOT_DIR,
        )
        set(value) = edit { putString(KEY_WEB_DAV_DIR, normalizeDirectoryOrDefault(value, DEFAULT_WEB_DAV_ROOT_DIR)) }

    var webDavSettingsSubdir: String
        get() = normalizeDirectoryOrDefault(
            preferences.getString(KEY_WEB_DAV_SETTINGS_SUBDIR, DEFAULT_ANDROID_SETTINGS_DIR),
            DEFAULT_ANDROID_SETTINGS_DIR,
        )
        set(value) = edit {
            putString(KEY_WEB_DAV_SETTINGS_SUBDIR, normalizeDirectoryOrDefault(value, DEFAULT_ANDROID_SETTINGS_DIR))
        }

    var webDavUser: String
        get() = preferences.getString(KEY_WEB_DAV_USER, "").orEmpty()
        set(value) = edit { putString(KEY_WEB_DAV_USER, value.trim()) }

    var webDavPassword: String
        get() = preferences.getString(KEY_WEB_DAV_PASSWORD, "").orEmpty()
        set(value) = edit { putString(KEY_WEB_DAV_PASSWORD, value.trim()) }

    var webDavLastFullBackupAt: Long
        get() = preferences.getLong(KEY_WEB_DAV_LAST_FULL, 0L)
        set(value) = edit { putLong(KEY_WEB_DAV_LAST_FULL, value) }

    var webDavLastLiteBackupAt: Long
        get() = preferences.getLong(KEY_WEB_DAV_LAST_LITE, 0L)
        set(value) = edit { putLong(KEY_WEB_DAV_LAST_LITE, value) }

    var webDavBookshelfProgressPrefetchLimit: Int
        get() = clamp(
            preferences.getInt(
                KEY_WEB_DAV_BOOKSHELF_PROGRESS_PREFETCH_LIMIT,
                DEFAULT_WEB_DAV_BOOKSHELF_PROGRESS_PREFETCH_LIMIT,
            ),
            0,
            MAX_WEB_DAV_BOOKSHELF_PROGRESS_PREFETCH_LIMIT,
        )
        set(value) = edit {
            putInt(
                KEY_WEB_DAV_BOOKSHELF_PROGRESS_PREFETCH_LIMIT,
                clamp(value, 0, MAX_WEB_DAV_BOOKSHELF_PROGRESS_PREFETCH_LIMIT),
            )
        }

    var isWebDavSyncBookshelfEnabled: Boolean
        get() = preferences.getBoolean(KEY_WEB_DAV_SYNC_BOOKSHELF, true)
        set(value) = edit { putBoolean(KEY_WEB_DAV_SYNC_BOOKSHELF, value) }

    var isWebDavSyncFilesEnabled: Boolean
        get() = preferences.getBoolean(KEY_WEB_DAV_SYNC_FILES, true)
        set(value) = edit { putBoolean(KEY_WEB_DAV_SYNC_FILES, value) }

    var isWebDavSyncUiSettingsEnabled: Boolean
        get() = preferences.getBoolean(KEY_WEB_DAV_SYNC_UI_SETTINGS, true)
        set(value) = edit { putBoolean(KEY_WEB_DAV_SYNC_UI_SETTINGS, value) }

    var isWebDavSyncThemesEnabled: Boolean
        get() = preferences.getBoolean(KEY_WEB_DAV_SYNC_THEMES, true)
        set(value) = edit { putBoolean(KEY_WEB_DAV_SYNC_THEMES, value) }

    var isWebDavSyncBackgroundsEnabled: Boolean
        get() = preferences.getBoolean(KEY_WEB_DAV_SYNC_BACKGROUNDS, true)
        set(value) = edit { putBoolean(KEY_WEB_DAV_SYNC_BACKGROUNDS, value) }

    var isWebDavSyncReadingStatsEnabled: Boolean
        get() = preferences.getBoolean(KEY_WEB_DAV_SYNC_READING_STATS, true)
        set(value) = edit { putBoolean(KEY_WEB_DAV_SYNC_READING_STATS, value) }

    var isWebDavCleanRemoteOrphansEnabled: Boolean
        get() = preferences.getBoolean(KEY_WEB_DAV_CLEAN_REMOTE_ORPHANS, false)
        set(value) = edit { putBoolean(KEY_WEB_DAV_CLEAN_REMOTE_ORPHANS, value) }

    var isReadingTimeTrackingEnabled: Boolean
        get() = preferences.getBoolean(KEY_READING_TIME_TRACKING_ENABLED, false)
        set(value) = edit { putBoolean(KEY_READING_TIME_TRACKING_ENABLED, value) }

    val readingStatsDeviceId: String
        get() {
            val value = preferences.getString(KEY_READING_STATS_DEVICE_ID, "")
            if (!value.isNullOrBlank()) return value
            val created = UUID.randomUUID().toString()
            edit { putString(KEY_READING_STATS_DEVICE_ID, created) }
            return created
        }

    fun getAnnualReportMetricSelection(bookScope: Boolean): String = preferences.getString(
        if (bookScope) KEY_ANNUAL_REPORT_BOOK_METRICS else KEY_ANNUAL_REPORT_GLOBAL_METRICS,
        "",
    ).orEmpty()

    fun setAnnualReportMetricSelection(bookScope: Boolean, value: String?) {
        edit {
            putString(
                if (bookScope) KEY_ANNUAL_REPORT_BOOK_METRICS else KEY_ANNUAL_REPORT_GLOBAL_METRICS,
                value?.trim().orEmpty(),
            )
        }
    }

    var fontSizeSp: Float
        get() = preferences.getFloat(KEY_FONT_SIZE, 18f)
        set(value) = edit { putFloat(KEY_FONT_SIZE, clamp(value, 12f, 64f)) }

    var readerFontFamily: String
        get() = normalizeReaderFontFamily(preferences.getString(KEY_FONT_FAMILY, "system_default"))
        set(value) = edit { putString(KEY_FONT_FAMILY, normalizeReaderFontFamily(value)) }

    var readerFontWeight: Int
        get() = normalizeReaderFontWeight(preferences.getInt(KEY_FONT_WEIGHT, 400))
        set(value) = edit { putInt(KEY_FONT_WEIGHT, normalizeReaderFontWeight(value)) }

    var readerTextColor: String
        get() = normalizeReaderTextColor(preferences.getString(KEY_TEXT_COLOR, "theme_default"))
        set(value) = edit { putString(KEY_TEXT_COLOR, normalizeReaderTextColor(value)) }

    var lineSpacingExtraSp: Float
        get() = preferences.getFloat(KEY_LINE_SPACING, 8f)
        set(value) = edit { putFloat(KEY_LINE_SPACING, clamp(value, 0f, 28f)) }

    var leftPaddingDp: Int
        get() = preferences.getInt(KEY_LEFT_PADDING, preferences.getInt("side_padding_dp", 18))
        set(value) = edit { putInt(KEY_LEFT_PADDING, clamp(value, 0, 48)) }

    var rightPaddingDp: Int
        get() = preferences.getInt(KEY_RIGHT_PADDING, preferences.getInt("side_padding_dp", 18))
        set(value) = edit { putInt(KEY_RIGHT_PADDING, clamp(value, 0, 48)) }

    var topPaddingDp: Int
        get() = preferences.getInt(KEY_TOP_PADDING, 8)
        set(value) = edit { putInt(KEY_TOP_PADDING, clamp(value, 0, 128)) }

    var bottomPaddingDp: Int
        get() = preferences.getInt(KEY_BOTTOM_PADDING, 8)
        set(value) = edit { putInt(KEY_BOTTOM_PADDING, clamp(value, 0, 128)) }

    var appThemeMode: String
        get() = normalizeAppThemeMode(preferences.getString(KEY_APP_THEME_MODE, "system"))
        set(value) = edit { putString(KEY_APP_THEME_MODE, normalizeAppThemeMode(value)) }

    var readerUiThemeMode: String
        get() = normalizeReaderUiThemeMode(preferences.getString(KEY_READER_UI_THEME_MODE, "follow_app"))
        set(value) = edit { putString(KEY_READER_UI_THEME_MODE, normalizeReaderUiThemeMode(value)) }

    var appLightStyleVariant: String
        get() = normalizeAppLightStyleVariant(preferences.getString(KEY_APP_LIGHT_STYLE_VARIANT, "yunbai"))
        set(value) = edit { putString(KEY_APP_LIGHT_STYLE_VARIANT, normalizeAppLightStyleVariant(value)) }

    var appDarkStyleVariant: String
        get() = normalizeAppDarkStyleVariant(preferences.getString(KEY_APP_DARK_STYLE_VARIANT, "yemu"))
        set(value) = edit { putString(KEY_APP_DARK_STYLE_VARIANT, normalizeAppDarkStyleVariant(value)) }

    var readerTheme: String
        get() = preferences.getString(KEY_THEME, "paper") ?: "paper"
        set(value) = edit { putString(KEY_THEME, value) }

    var readerBackgroundPath: String
        get() = preferences.getString(KEY_BACKGROUND_PATH, "").orEmpty()
        set(value) = edit { putString(KEY_BACKGROUND_PATH, value) }

    var isKeepScreenOn: Boolean
        get() = preferences.getBoolean(KEY_KEEP_SCREEN_ON, true)
        set(value) = edit { putBoolean(KEY_KEEP_SCREEN_ON, value) }

    var autoPageSeconds: Int
        get() = clamp(preferences.getInt(KEY_AUTO_PAGE_SECONDS, 10), 1, 30)
        set(value) = edit { putInt(KEY_AUTO_PAGE_SECONDS, clamp(value, 1, 30)) }

    var ttsEngine: String
        get() {
            val value = preferences.getString(KEY_TTS_ENGINE, null)
            if (value == "system" || value == "mimo") return value
            return if (ttsMimoApiKey.isBlank()) "system" else "mimo"
        }
        set(value) = edit { putString(KEY_TTS_ENGINE, normalizeTtsEngine(value)) }

    var ttsRate: Float
        get() = preferences.getFloat(KEY_TTS_RATE, 1f)
        set(value) = edit { putFloat(KEY_TTS_RATE, clamp(value, 0.5f, 2f)) }

    var ttsMimoApiKey: String
        get() = preferences.getString(KEY_TTS_MIMO_API_KEY, "").orEmpty()
        set(value) = edit { putString(KEY_TTS_MIMO_API_KEY, value.trim()) }

    var ttsMimoVoice: String
        get() = normalizeTtsMimoVoice(preferences.getString(KEY_TTS_MIMO_VOICE, "冰糖"))
        set(value) = edit { putString(KEY_TTS_MIMO_VOICE, normalizeTtsMimoVoice(value)) }

    var ttsSystemEnginePackage: String
        get() = preferences.getString(KEY_TTS_SYSTEM_ENGINE, "").orEmpty()
        set(value) = edit { putString(KEY_TTS_SYSTEM_ENGINE, value.trim()) }

    var ttsTimerMode: String
        get() = if (preferences.getString(KEY_TTS_TIMER_MODE, "slider") == "precise") "precise" else "slider"
        set(value) = edit { putString(KEY_TTS_TIMER_MODE, if (value == "precise") "precise" else "slider") }

    var flipMode: String
        get() = normalizeFlipMode(preferences.getString(KEY_FLIP_MODE, "slide"))
        set(value) = edit { putString(KEY_FLIP_MODE, normalizeFlipMode(value)) }

    var flipSpeed: String
        get() = preferences.getString(KEY_FLIP_SPEED, "medium") ?: "medium"
        set(value) = edit { putString(KEY_FLIP_SPEED, value) }

    var hudTopMarginDp: Int
        get() = preferences.getInt(KEY_HUD_TOP_MARGIN, preferences.getInt(KEY_HUD_VERTICAL_MARGIN, 2))
        set(value) = edit { putInt(KEY_HUD_TOP_MARGIN, clamp(value, 0, 32)) }

    var hudBottomMarginDp: Int
        get() = preferences.getInt(KEY_HUD_BOTTOM_MARGIN, preferences.getInt(KEY_HUD_VERTICAL_MARGIN, 2))
        set(value) = edit { putInt(KEY_HUD_BOTTOM_MARGIN, clamp(value, 0, 32)) }

    var hudVerticalMarginDp: Int
        get() = preferences.getInt(KEY_HUD_VERTICAL_MARGIN, 2)
        set(value) {
            val clamped = clamp(value, 0, 32)
            edit {
                putInt(KEY_HUD_VERTICAL_MARGIN, clamped)
                putInt(KEY_HUD_TOP_MARGIN, clamped)
                putInt(KEY_HUD_BOTTOM_MARGIN, clamped)
            }
        }

    var isReaderMenuAutoHideEnabled: Boolean
        get() = preferences.getBoolean(KEY_READER_MENU_AUTO_HIDE, false)
        set(value) = edit { putBoolean(KEY_READER_MENU_AUTO_HIDE, value) }

    var isReaderMenuPersistentActionsEnabled: Boolean
        get() = preferences.getBoolean(KEY_READER_MENU_PERSISTENT_ACTIONS, false)
        set(value) = edit { putBoolean(KEY_READER_MENU_PERSISTENT_ACTIONS, value) }

    var isReaderDoublePageEnabled: Boolean
        get() = preferences.getBoolean(KEY_READER_DOUBLE_PAGE_ENABLED, false)
        set(value) = edit { putBoolean(KEY_READER_DOUBLE_PAGE_ENABLED, value) }

    var readerDoublePageMode: String
        get() = normalizeReaderDoublePageMode(preferences.getString(KEY_READER_DOUBLE_PAGE_MODE, "landscape"))
        set(value) = edit { putString(KEY_READER_DOUBLE_PAGE_MODE, normalizeReaderDoublePageMode(value)) }

    var readerDoublePageTurnStep: String
        get() = normalizeReaderDoublePageTurnStep(preferences.getString(KEY_READER_DOUBLE_PAGE_TURN_STEP, "two"))
        set(value) = edit { putString(KEY_READER_DOUBLE_PAGE_TURN_STEP, normalizeReaderDoublePageTurnStep(value)) }

    var simulationDoublePageTurnMode: String
        get() = normalizeSimulationDoublePageTurnMode(
            preferences.getString(KEY_READER_SIMULATION_DOUBLE_PAGE_TURN_MODE, "outerPage"),
        )
        set(value) = edit {
            putString(KEY_READER_SIMULATION_DOUBLE_PAGE_TURN_MODE, normalizeSimulationDoublePageTurnMode(value))
        }

    var isReaderAutoNightEnabled: Boolean
        get() = preferences.getBoolean(KEY_READER_AUTO_NIGHT_ENABLED, true)
        set(value) = edit { putBoolean(KEY_READER_AUTO_NIGHT_ENABLED, value) }

    var readerAutoNightCustomPolicy: String
        get() = normalizeReaderAutoNightCustomPolicy(
            preferences.getString(KEY_READER_AUTO_NIGHT_CUSTOM_POLICY, "ask"),
        )
        set(value) = edit {
            putString(KEY_READER_AUTO_NIGHT_CUSTOM_POLICY, normalizeReaderAutoNightCustomPolicy(value))
        }

    var isBookshelfAddEntryVisible: Boolean
        get() = preferences.getBoolean(KEY_BOOKSHELF_SHOW_ADD_ENTRY, true)
        set(value) = edit { putBoolean(KEY_BOOKSHELF_SHOW_ADD_ENTRY, value) }

    var homeBottomNavStyle: String
        get() = normalizeHomeBottomNavStyle(preferences.getString(KEY_HOME_BOTTOM_NAV_STYLE, "icons"))
        set(value) = edit { putString(KEY_HOME_BOTTOM_NAV_STYLE, normalizeHomeBottomNavStyle(value)) }

    var portraitHomeNavigationMode: String
        get() = normalizeHomeNavigationMode(preferences.getString(KEY_HOME_NAV_PORTRAIT_MODE, "auto"))
        set(value) = edit { putString(KEY_HOME_NAV_PORTRAIT_MODE, normalizeHomeNavigationMode(value)) }

    var landscapeHomeNavigationMode: String
        get() = normalizeHomeNavigationMode(preferences.getString(KEY_HOME_NAV_LANDSCAPE_MODE, "auto"))
        set(value) = edit { putString(KEY_HOME_NAV_LANDSCAPE_MODE, normalizeHomeNavigationMode(value)) }

    var readerOrientationMode: String
        get() = normalizeReaderOrientationMode(preferences.getString(KEY_READER_ORIENTATION_MODE, "system"))
        set(value) = edit { putString(KEY_READER_ORIENTATION_MODE, normalizeReaderOrientationMode(value)) }

    var transitionMotionMode: String
        get() {
            val defaultMode = if (Build.VERSION.SDK_INT >= 34) "fluid" else "simple"
            val value = preferences.getString(KEY_TRANSITION_MOTION_MODE, defaultMode)
            if (Build.VERSION.SDK_INT < 34) return "simple"
            return if (value == "simple") "simple" else "fluid"
        }
        set(value) {
            val mode = if (Build.VERSION.SDK_INT < 34 || value == "simple") "simple" else "fluid"
            edit { putString(KEY_TRANSITION_MOTION_MODE, mode) }
        }

    var homeSidebarPresentation: String
        get() = normalizeHomeSidebarPresentation(preferences.getString(KEY_HOME_SIDEBAR_PRESENTATION, "slide"))
        set(value) = edit { putString(KEY_HOME_SIDEBAR_PRESENTATION, normalizeHomeSidebarPresentation(value)) }

    var homeFixedSidebarStyle: String
        get() = normalizeHomeFixedSidebarStyle(preferences.getString(KEY_HOME_FIXED_SIDEBAR_STYLE, "full"))
        set(value) = edit { putString(KEY_HOME_FIXED_SIDEBAR_STYLE, normalizeHomeFixedSidebarStyle(value)) }

    var readerSliderMode: String
        get() = if (preferences.getString(KEY_READER_SLIDER_MODE, "book") == "chapter") "chapter" else "book"
        set(value) = edit { putString(KEY_READER_SLIDER_MODE, if (value == "chapter") "chapter" else "book") }

    var volumeKeyUpAction: String
        get() = normalizeVolumeKeyAction(preferences.getString(KEY_VOLUME_KEY_UP_ACTION, "page_up"), "page_up")
        set(value) = edit { putString(KEY_VOLUME_KEY_UP_ACTION, normalizeVolumeKeyAction(value, "page_up")) }

    var volumeKeyDownAction: String
        get() = normalizeVolumeKeyAction(preferences.getString(KEY_VOLUME_KEY_DOWN_ACTION, "page_down"), "page_down")
        set(value) = edit { putString(KEY_VOLUME_KEY_DOWN_ACTION, normalizeVolumeKeyAction(value, "page_down")) }

    var isChapterTitleVisible: Boolean
        get() = preferences.getBoolean(KEY_CHAPTER_TITLE_VISIBILITY, true)
        set(value) = edit { putBoolean(KEY_CHAPTER_TITLE_VISIBILITY, value) }

    var bookshelfViewMode: String
        get() = if (preferences.getString(KEY_BOOKSHELF_VIEW_MODE, "card") == "list") "list" else "card"
        set(value) = edit { putString(KEY_BOOKSHELF_VIEW_MODE, if (value == "list") "list" else "card") }

    var glassOpacityPercent: Int
        get() = clamp(preferences.getInt(KEY_GLASS_OPACITY_PERCENT, 80), 20, 100)
        set(value) = edit { putInt(KEY_GLASS_OPACITY_PERCENT, clamp(value, 20, 100)) }

    var hudTopLeft: String
        get() = normalizeHudSlot(preferences.getString(KEY_HUD_TOP_LEFT, "title"))
        set(value) = edit { putString(KEY_HUD_TOP_LEFT, normalizeHudSlot(value)) }

    var hudTopCenter: String
        get() = normalizeHudSlot(preferences.getString(KEY_HUD_TOP_CENTER, "none"))
        set(value) = edit { putString(KEY_HUD_TOP_CENTER, normalizeHudSlot(value)) }

    var hudTopRight: String
        get() = normalizeHudSlot(preferences.getString(KEY_HUD_TOP_RIGHT, "time"))
        set(value) = edit { putString(KEY_HUD_TOP_RIGHT, normalizeHudSlot(value)) }

    var hudBottomLeft: String
        get() = normalizeHudSlot(preferences.getString(KEY_HUD_BOTTOM_LEFT, "chapter"))
        set(value) = edit { putString(KEY_HUD_BOTTOM_LEFT, normalizeHudSlot(value)) }

    var hudBottomCenter: String
        get() = normalizeHudSlot(preferences.getString(KEY_HUD_BOTTOM_CENTER, "none"))
        set(value) = edit { putString(KEY_HUD_BOTTOM_CENTER, normalizeHudSlot(value)) }

    var hudBottomRight: String
        get() = normalizeHudSlot(preferences.getString(KEY_HUD_BOTTOM_RIGHT, "page_and_progress"))
        set(value) = edit { putString(KEY_HUD_BOTTOM_RIGHT, normalizeHudSlot(value)) }

    var letterSpacing: Float
        get() = normalizeLetterSpacing(preferences.getFloat(KEY_LETTER_SPACING, 0f))
        set(value) = edit { putFloat(KEY_LETTER_SPACING, normalizeLetterSpacing(value)) }

    var firstLineIndentDp: Int
        get() = preferences.getInt(KEY_FIRST_LINE_INDENT, 2)
        set(value) = edit { putInt(KEY_FIRST_LINE_INDENT, clamp(value, 0, 8)) }

    var paragraphSpacingDp: Int
        get() = clamp(preferences.getInt(KEY_PARAGRAPH_SPACING, 4), 0, 32)
        set(value) = edit { putInt(KEY_PARAGRAPH_SPACING, clamp(value, 0, 32)) }

    var backgroundBlurPercent: Int
        get() = clamp(preferences.getInt(KEY_BACKGROUND_BLUR_PERCENT, 0), 0, 100)
        set(value) = edit { putInt(KEY_BACKGROUND_BLUR_PERCENT, clamp(value, 0, 100)) }

    var customTextColor: String
        get() = preferences.getString(KEY_CUSTOM_TEXT_COLOR, "").orEmpty()
        set(value) = edit { putString(KEY_CUSTOM_TEXT_COLOR, value) }

    var chapterTitleAlignment: String
        get() = if (preferences.getString(KEY_CHAPTER_TITLE_ALIGNMENT, "left") == "center") "center" else "left"
        set(value) = edit { putString(KEY_CHAPTER_TITLE_ALIGNMENT, if (value == "center") "center" else "left") }

    var isBodyTextJustified: Boolean
        get() = preferences.getBoolean(KEY_BODY_TEXT_JUSTIFY, true)
        set(value) = edit { putBoolean(KEY_BODY_TEXT_JUSTIFY, value) }

    val webDavProgressBaseUrl: String
        get() = normalizeBaseUrl(webDavUrl) + parentDirectory(webDavDir)

    val webDavProgressDir: String
        get() = parentDirectory(webDavDir)

    fun exportAndroidPrivateSettingsJson(): JSONObject {
        val result = JSONObject()
        try {
            result.put(JSON_PLATFORM, PLATFORM_ANDROID)
            result.put(JSON_SCHEMA_VERSION, ANDROID_SETTINGS_SCHEMA_VERSION)
            for ((key, value) in preferences.all) {
                if (key in ANDROID_PRIVATE_SYNC_KEYS) result.put(key, value)
            }
            result.put(JSON_BACKGROUND_FILE, readerBackgroundFileName())
        } catch (_: Exception) {
        }
        return result
    }

    fun importAndroidPrivateSettingsJson(jsonObject: JSONObject?, restoredBackgroundPath: String?) {
        if (jsonObject == null) throw IllegalArgumentException("设置快照为空")
        if (jsonObject.optString(JSON_PLATFORM, "") != PLATFORM_ANDROID) {
            throw IllegalArgumentException("设置快照不是 Android 平台")
        }
        val editor = preferences.edit()
        val iterator = jsonObject.keys()
        while (iterator.hasNext()) {
            val key = iterator.next()
            if (key !in ANDROID_PRIVATE_SYNC_KEYS) continue
            putJsonPreference(editor, key, jsonObject.opt(key))
        }
        if (!restoredBackgroundPath.isNullOrBlank()) {
            editor.putString(KEY_BACKGROUND_PATH, restoredBackgroundPath)
        } else if (jsonObject.has(JSON_BACKGROUND_FILE) && jsonObject.optString(JSON_BACKGROUND_FILE, "").isBlank()) {
            editor.putString(KEY_BACKGROUND_PATH, "")
        }
        editor.apply()
    }

    fun readerBackgroundFileName(): String {
        val path = readerBackgroundPath
        return if (path.isBlank()) "" else File(path).name
    }

    fun androidSettingsBackgroundFileName(jsonObject: JSONObject?): String =
        if (jsonObject == null) "" else sanitizeRemoteFileName(jsonObject.optString(JSON_BACKGROUND_FILE, ""))

    fun exportAsJson(): JSONObject {
        val result = JSONObject()
        try {
            for ((key, value) in preferences.all) result.put(key, value)
        } catch (_: Exception) {
        }
        return result
    }

    fun importFromJson(jsonObject: JSONObject) {
        val editor = preferences.edit().clear()
        val iterator = jsonObject.keys()
        while (iterator.hasNext()) {
            val key = iterator.next()
            when (val value = jsonObject.opt(key)) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Double -> editor.putFloat(key, value.toFloat())
                is String -> editor.putString(key, value)
            }
        }
        editor.apply()
    }

    private inline fun edit(action: SharedPreferences.Editor.() -> Unit) {
        preferences.edit().also(action).apply()
    }

    private fun normalizeBaseUrl(value: String?): String {
        val trimmed = value?.trim().orEmpty()
        return if (trimmed.isNotEmpty() && !trimmed.endsWith('/')) "$trimmed/" else trimmed
    }

    private fun normalizeDirectory(value: String?): String {
        var trimmed = value?.trim().orEmpty()
        while (trimmed.startsWith('/')) trimmed = trimmed.substring(1)
        if (trimmed.isNotEmpty() && !trimmed.endsWith('/')) trimmed += "/"
        return trimmed
    }

    private fun normalizeDirectoryOrDefault(value: String?, fallback: String): String {
        val normalized = normalizeDirectory(value)
        return if (normalized.isBlank()) normalizeDirectory(fallback) else normalized
    }

    private fun parentDirectory(directory: String?): String {
        val normalized = normalizeDirectory(directory)
        if (normalized.isBlank()) return ""
        val withoutTrailing = normalized.substring(0, normalized.length - 1)
        val slashIndex = withoutTrailing.lastIndexOf('/')
        return if (slashIndex < 0) "" else withoutTrailing.substring(0, slashIndex + 1)
    }

    private fun putJsonPreference(editor: SharedPreferences.Editor, key: String, value: Any?) {
        when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is Number -> if (key in FLOAT_SYNC_KEYS) editor.putFloat(key, value.toFloat()) else editor.putInt(key, value.toInt())
            is String -> editor.putString(key, value)
        }
    }

    private fun sanitizeRemoteFileName(value: String?): String {
        if (value.isNullOrBlank()) return ""
        val name = File(value).name
        return if (name.contains('/') || name.contains('\\') || name == "." || name == "..") "" else name
    }

    companion object {
        private const val PREFS_NAME = "pacil_read_settings"
        private const val PLATFORM_ANDROID = "android"
        private const val ANDROID_SETTINGS_SCHEMA_VERSION = 1
        private const val JSON_PLATFORM = "platform"
        private const val JSON_SCHEMA_VERSION = "schemaVersion"
        private const val JSON_BACKGROUND_FILE = "reader_background_file"
        private const val DEFAULT_WEB_DAV_ROOT_DIR = "PacilRead"
        private const val DEFAULT_ANDROID_SETTINGS_DIR = "android-settings"
        private const val DEFAULT_WEB_DAV_BOOKSHELF_PROGRESS_PREFETCH_LIMIT = 6
        private const val MAX_WEB_DAV_BOOKSHELF_PROGRESS_PREFETCH_LIMIT = 100
        private const val KEY_AUTO_OPEN = "auto_open_last"
        private const val KEY_WEB_DAV_ENABLED = "webdav_enabled"
        private const val KEY_WEB_DAV_URL = "webdav_url"
        private const val KEY_WEB_DAV_DIR = "webdav_dir"
        private const val KEY_WEB_DAV_SETTINGS_SUBDIR = "webdav_settings_subdir"
        private const val KEY_WEB_DAV_USER = "webdav_user"
        private const val KEY_WEB_DAV_PASSWORD = "webdav_password"
        private const val KEY_WEB_DAV_LAST_FULL = "webdav_last_full"
        private const val KEY_WEB_DAV_LAST_LITE = "webdav_last_lite"
        private const val KEY_WEB_DAV_BOOKSHELF_PROGRESS_PREFETCH_LIMIT = "webdav_bookshelf_progress_prefetch_limit"
        private const val KEY_WEB_DAV_SYNC_BOOKSHELF = "webdav_sync_bookshelf"
        private const val KEY_WEB_DAV_SYNC_FILES = "webdav_sync_files"
        private const val KEY_WEB_DAV_SYNC_UI_SETTINGS = "webdav_sync_ui_settings"
        private const val KEY_WEB_DAV_SYNC_THEMES = "webdav_sync_themes"
        private const val KEY_WEB_DAV_SYNC_BACKGROUNDS = "webdav_sync_backgrounds"
        private const val KEY_WEB_DAV_SYNC_READING_STATS = "webdav_sync_reading_stats"
        private const val KEY_WEB_DAV_CLEAN_REMOTE_ORPHANS = "webdav_clean_remote_orphans"
        private const val KEY_FONT_SIZE = "font_size_sp"
        private const val KEY_FONT_FAMILY = "font_family"
        private const val KEY_FONT_WEIGHT = "font_weight"
        private const val KEY_TEXT_COLOR = "reader_text_color"
        private const val KEY_LINE_SPACING = "line_spacing_extra"
        private const val KEY_LEFT_PADDING = "left_padding_dp"
        private const val KEY_RIGHT_PADDING = "right_padding_dp"
        private const val KEY_TOP_PADDING = "top_padding_dp"
        private const val KEY_BOTTOM_PADDING = "bottom_padding_dp"
        private const val KEY_APP_THEME_MODE = "app_theme_mode"
        private const val KEY_READER_UI_THEME_MODE = "reader_ui_theme_mode"
        private const val KEY_APP_LIGHT_STYLE_VARIANT = "app_light_style_variant"
        private const val KEY_APP_DARK_STYLE_VARIANT = "app_dark_style_variant"
        private const val KEY_THEME = "reader_theme"
        private const val KEY_BACKGROUND_PATH = "reader_background_path"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_AUTO_PAGE_SECONDS = "auto_page_seconds"
        private const val KEY_TTS_ENGINE = "tts_engine"
        private const val KEY_TTS_RATE = "tts_rate"
        private const val KEY_TTS_MIMO_API_KEY = "tts_mimo_api_key"
        private const val KEY_TTS_MIMO_VOICE = "tts_mimo_voice"
        private const val KEY_TTS_SYSTEM_ENGINE = "tts_system_engine"
        private const val KEY_TTS_TIMER_MODE = "tts_timer_mode"
        private const val KEY_FLIP_MODE = "flip_mode"
        private const val KEY_READER_SLIDER_MODE = "reader_slider_mode"
        private const val KEY_VOLUME_KEY_UP_ACTION = "volume_key_up_action"
        private const val KEY_VOLUME_KEY_DOWN_ACTION = "volume_key_down_action"
        private const val KEY_CHAPTER_TITLE_VISIBILITY = "chapter_title_visibility"
        private const val KEY_BOOKSHELF_VIEW_MODE = "bookshelf_view_mode"
        private const val KEY_GLASS_OPACITY_PERCENT = "glass_opacity_percent"
        private const val KEY_HUD_TOP_LEFT = "hud_top_left"
        private const val KEY_HUD_TOP_CENTER = "hud_top_center"
        private const val KEY_HUD_TOP_RIGHT = "hud_top_right"
        private const val KEY_HUD_BOTTOM_LEFT = "hud_bottom_left"
        private const val KEY_HUD_BOTTOM_CENTER = "hud_bottom_center"
        private const val KEY_HUD_BOTTOM_RIGHT = "hud_bottom_right"
        private const val KEY_LETTER_SPACING = "letter_spacing"
        private const val KEY_FIRST_LINE_INDENT = "first_line_indent"
        private const val KEY_PARAGRAPH_SPACING = "paragraph_spacing_dp"
        private const val KEY_BACKGROUND_BLUR_PERCENT = "background_blur_percent"
        private const val KEY_CUSTOM_TEXT_COLOR = "custom_text_color"
        private const val KEY_CHAPTER_TITLE_ALIGNMENT = "chapter_title_alignment"
        private const val KEY_BODY_TEXT_JUSTIFY = "body_text_justify"
        private const val KEY_FLIP_SPEED = "flip_speed"
        private const val KEY_HUD_VERTICAL_MARGIN = "hud_vertical_margin"
        private const val KEY_HUD_TOP_MARGIN = "hud_top_margin"
        private const val KEY_HUD_BOTTOM_MARGIN = "hud_bottom_margin"
        private const val KEY_READER_MENU_AUTO_HIDE = "reader_menu_auto_hide"
        private const val KEY_READER_MENU_PERSISTENT_ACTIONS = "reader_menu_persistent_actions"
        private const val KEY_READING_TIME_TRACKING_ENABLED = "reading_time_tracking_enabled"
        private const val KEY_READING_STATS_DEVICE_ID = "reading_stats_device_id"
        private const val KEY_ANNUAL_REPORT_GLOBAL_METRICS = "annual_report_global_metrics"
        private const val KEY_ANNUAL_REPORT_BOOK_METRICS = "annual_report_book_metrics"
        private const val KEY_READER_DOUBLE_PAGE_ENABLED = "reader_double_page_enabled"
        private const val KEY_READER_DOUBLE_PAGE_MODE = "reader_double_page_mode"
        private const val KEY_READER_DOUBLE_PAGE_TURN_STEP = "reader_double_page_turn_step"
        private const val KEY_READER_SIMULATION_DOUBLE_PAGE_TURN_MODE = "reader_simulationDoublePageTurnMode"
        private const val KEY_READER_AUTO_NIGHT_ENABLED = "reader_auto_night_enabled"
        private const val KEY_READER_AUTO_NIGHT_CUSTOM_POLICY = "reader_auto_night_custom_policy"
        private const val KEY_BOOKSHELF_SHOW_ADD_ENTRY = "bookshelf_show_add_entry"
        private const val KEY_HOME_BOTTOM_NAV_STYLE = "home_bottom_nav_style"
        private const val KEY_HOME_NAV_PORTRAIT_MODE = "home_nav_portrait_mode"
        private const val KEY_HOME_NAV_LANDSCAPE_MODE = "home_nav_landscape_mode"
        private const val KEY_HOME_SIDEBAR_PRESENTATION = "home_sidebar_presentation"
        private const val KEY_HOME_FIXED_SIDEBAR_STYLE = "home_fixed_sidebar_style"
        private const val KEY_READER_ORIENTATION_MODE = "reader_orientation_mode"
        private const val KEY_TRANSITION_MOTION_MODE = "transition_motion_mode"

        private val ANDROID_PRIVATE_SYNC_KEYS = setOf(
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
            KEY_TRANSITION_MOTION_MODE,
        )
        private val FLOAT_SYNC_KEYS = setOf(KEY_FONT_SIZE, KEY_LINE_SPACING, KEY_TTS_RATE, KEY_LETTER_SPACING)

        private fun clamp(value: Float, low: Float, high: Float): Float = max(low, min(high, value))
        private fun clamp(value: Int, low: Int, high: Int): Int = max(low, min(high, value))

        @JvmStatic
        fun normalizeAppThemeMode(value: String?): String = if (value == "light" || value == "dark") value else "system"

        @JvmStatic
        fun normalizeReaderUiThemeMode(value: String?): String = when (value) {
            "system", "light", "dark" -> value
            else -> "follow_app"
        }

        @JvmStatic
        fun normalizeAppLightStyleVariant(value: String?): String = if (value == "yaobai") "yaobai" else "yunbai"

        @JvmStatic
        fun normalizeAppDarkStyleVariant(value: String?): String = if (value == "jiye") "jiye" else "yemu"

        @JvmStatic
        fun normalizeReaderDoublePageMode(value: String?): String = when (value) {
            "always", "landscape_or_tablet" -> value
            else -> "landscape"
        }

        @JvmStatic
        fun normalizeReaderDoublePageTurnStep(value: String?): String = if (value == "one") "one" else "two"

        @JvmStatic
        fun normalizeSimulationDoublePageTurnMode(value: String?): String = if (value == "spread") "spread" else "outerPage"

        @JvmStatic
        fun normalizeReaderAutoNightCustomPolicy(value: String?): String = when (value) {
            "override", "preserve" -> value
            else -> "ask"
        }

        @JvmStatic
        fun normalizeHomeBottomNavStyle(value: String?): String = if (value == "text") "text" else "icons"

        @JvmStatic
        fun normalizeHomeNavigationMode(value: String?): String = when (value) {
            "bottom", "sidebar" -> value
            else -> "auto"
        }

        @JvmStatic
        fun normalizeReaderOrientationMode(value: String?): String = when (value) {
            "portrait", "landscape" -> value
            else -> "system"
        }

        @JvmStatic
        fun normalizeHomeSidebarPresentation(value: String?): String = if (value == "fixed_wide") "fixed_wide" else "slide"

        @JvmStatic
        fun normalizeHomeFixedSidebarStyle(value: String?): String = if (value == "icons") "icons" else "full"

        private fun normalizeTtsEngine(value: String?): String = if (value == "mimo") "mimo" else "system"

        @JvmStatic
        fun normalizeTtsMimoVoice(value: String?): String = when (value) {
            "冰糖", "茉莉", "苏打", "白桦" -> value
            else -> "冰糖"
        }

        private fun normalizeReaderFontFamily(value: String?): String = when (value) {
            "sans-serif", "monospace", "system_default" -> value
            else -> "system_default"
        }

        private fun normalizeReaderFontWeight(value: Int): Int = when {
            value <= 325 -> 250
            value >= 550 -> 700
            else -> 400
        }

        private fun normalizeReaderTextColor(value: String?): String = when (value) {
            "ink_brown", "graphite", "warm_gray", "jade_ink", "forest_ink", "moon_white", "custom", "theme_default" -> value
            else -> "theme_default"
        }

        private fun normalizeFlipMode(value: String?): String = when (value) {
            "cover", "slide", "simulation", "scroll", "none" -> value
            "flip" -> "simulation"
            "fade" -> "scroll"
            else -> "slide"
        }

        private fun normalizeLetterSpacing(value: Float): Float {
            val clamped = clamp(value, 0f, 1f)
            return Math.round(clamped * 20f) / 20f
        }

        private fun normalizeVolumeKeyAction(value: String?, fallback: String): String = when (value) {
            "system", "page_up", "page_down" -> value
            else -> fallback
        }

        private fun normalizeHudSlot(value: String?): String = when (value) {
            "progress" -> "book_progress"
            "title", "chapter", "title_chapter", "time", "battery", "chapter_page", "book_progress",
            "page_and_progress", "time_and_battery" -> value
            else -> "none"
        }
    }
}
