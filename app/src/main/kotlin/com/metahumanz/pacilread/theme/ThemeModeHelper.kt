package com.metahumanz.pacilread.theme

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.annotation.StyleRes
import androidx.core.content.ContextCompat
import com.metahumanz.pacilread.R
import com.metahumanz.pacilread.storage.SettingsStore

object ThemeModeHelper {
    const val MODE_SYSTEM = "system"
    const val MODE_LIGHT = "light"
    const val MODE_DARK = "dark"
    const val MODE_FOLLOW_APP = "follow_app"
    const val LIGHT_STYLE_YAOBAI = "yaobai"
    const val LIGHT_STYLE_YUNBAI = "yunbai"
    const val DARK_STYLE_YEMU = "yemu"
    const val DARK_STYLE_JIYE = "jiye"
    private const val PREFS_NAME = "pacil_read_settings"
    private const val KEY_APP_THEME_MODE = "app_theme_mode"
    private const val KEY_READER_UI_THEME_MODE = "reader_ui_theme_mode"
    private const val KEY_APP_LIGHT_STYLE_VARIANT = "app_light_style_variant"
    private const val KEY_APP_DARK_STYLE_VARIANT = "app_dark_style_variant"

    @JvmStatic fun wrapForApp(base: Context): Context = wrap(base, getResolvedAppBucket(base))
    @JvmStatic fun wrapForReader(base: Context): Context = wrap(base, getResolvedReaderBucket(base))
    @JvmStatic fun getAppThemeMode(context: Context?): String =
        SettingsStore.normalizeAppThemeMode(preferences(context).getString(KEY_APP_THEME_MODE, MODE_SYSTEM))
    @JvmStatic fun getReaderUiThemeMode(context: Context?): String =
        SettingsStore.normalizeReaderUiThemeMode(preferences(context).getString(KEY_READER_UI_THEME_MODE, MODE_FOLLOW_APP))
    @JvmStatic fun getAppLightStyleVariant(context: Context?): String =
        SettingsStore.normalizeAppLightStyleVariant(preferences(context).getString(KEY_APP_LIGHT_STYLE_VARIANT, LIGHT_STYLE_YUNBAI))
    @JvmStatic fun getAppDarkStyleVariant(context: Context?): String =
        SettingsStore.normalizeAppDarkStyleVariant(preferences(context).getString(KEY_APP_DARK_STYLE_VARIANT, DARK_STYLE_YEMU))

    @JvmStatic
    fun getResolvedAppBucket(context: Context?): String {
        val mode = getAppThemeMode(context)
        return if (mode == MODE_LIGHT || mode == MODE_DARK) mode else if (isSystemDark(context)) MODE_DARK else MODE_LIGHT
    }

    @JvmStatic
    fun getResolvedReaderBucket(context: Context?): String = when (val mode = getReaderUiThemeMode(context)) {
        MODE_FOLLOW_APP -> getResolvedAppBucket(context)
        MODE_SYSTEM -> if (isSystemDark(context)) MODE_DARK else MODE_LIGHT
        else -> if (mode == MODE_DARK) MODE_DARK else MODE_LIGHT
    }

    @JvmStatic fun getResolvedReaderThemeMode(context: Context?): String = getResolvedReaderBucket(context)
    @JvmStatic fun getResolvedAppStyleVariant(context: Context?): String =
        if (getResolvedAppBucket(context) == MODE_DARK) getAppDarkStyleVariant(context) else getAppLightStyleVariant(context)
    @JvmStatic fun getResolvedReaderStyleVariant(context: Context?): String =
        if (getResolvedReaderBucket(context) == MODE_DARK) getAppDarkStyleVariant(context) else getAppLightStyleVariant(context)

    @StyleRes @JvmStatic fun resolveAppThemeResId(context: Context?): Int =
        resolveThemeResId(getResolvedAppBucket(context), getResolvedAppStyleVariant(context))
    @StyleRes @JvmStatic fun resolveReaderThemeResId(context: Context?): Int =
        resolveReaderThemeResId(getResolvedReaderBucket(context), getResolvedReaderStyleVariant(context))
    @JvmStatic fun getResolvedAppAppearanceLabel(context: Context?): String =
        getAppearanceLabel(getResolvedAppBucket(context), getResolvedAppStyleVariant(context))
    @JvmStatic fun getResolvedReaderAppearanceLabel(context: Context?): String =
        getAppearanceLabel(getResolvedReaderBucket(context), getResolvedReaderStyleVariant(context))
    @JvmStatic fun getBucketLabel(bucket: String?): String = if (bucket == MODE_DARK) "深色" else "浅色"
    @JvmStatic fun getStyleLabel(styleVariant: String?): String = when (styleVariant) {
        LIGHT_STYLE_YAOBAI -> "曜白"
        DARK_STYLE_JIYE -> "极夜"
        DARK_STYLE_YEMU -> "夜幕"
        else -> "云白"
    }
    @JvmStatic fun getAppearanceLabel(bucket: String?, styleVariant: String?): String =
        "${getBucketLabel(bucket)}·${getStyleLabel(styleVariant)}"

    @JvmStatic
    fun isDark(resources: Resources): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    @JvmStatic
    fun isSystemDark(context: Context?): Boolean = isDark(context?.applicationContext?.resources ?: Resources.getSystem())

    @ColorInt
    @JvmStatic
    fun resolveColor(context: Context?, @ColorRes colorResId: Int): Int {
        if (context == null) return 0
        val value = TypedValue()
        context.resources.getValue(colorResId, value, true)
        if (isInlineColor(value)) return value.data
        if (value.type == TypedValue.TYPE_ATTRIBUTE) return resolveThemeAttrColor(context, value.data)
        return ContextCompat.getColor(context, if (value.resourceId != 0) value.resourceId else colorResId)
    }

    @ColorInt
    @JvmStatic
    fun resolveThemeAttrColor(context: Context, @AttrRes attrResId: Int): Int {
        val value = TypedValue()
        if (!context.theme.resolveAttribute(attrResId, value, true)) return 0
        if (isInlineColor(value)) return value.data
        return if (value.resourceId != 0) ContextCompat.getColor(context, value.resourceId) else 0
    }

    private fun wrap(base: Context, bucket: String): Context {
        val configuration = Configuration(base.resources.configuration)
        configuration.uiMode = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv() or
            if (bucket == MODE_DARK) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        return base.createConfigurationContext(configuration)
    }

    @StyleRes
    private fun resolveThemeResId(bucket: String, styleVariant: String): Int = if (bucket == MODE_DARK) {
        if (styleVariant == DARK_STYLE_JIYE) R.style.PacilReadTheme_Jiye else R.style.PacilReadTheme_Yemu
    } else {
        if (styleVariant == LIGHT_STYLE_YAOBAI) R.style.PacilReadTheme_Yaobai else R.style.PacilReadTheme_Yunbai
    }

    @StyleRes
    private fun resolveReaderThemeResId(bucket: String, styleVariant: String): Int = if (bucket == MODE_DARK) {
        if (styleVariant == DARK_STYLE_JIYE) R.style.PacilReadTheme_ReaderJiye else R.style.PacilReadTheme_ReaderYemu
    } else {
        if (styleVariant == LIGHT_STYLE_YAOBAI) R.style.PacilReadTheme_ReaderYaobai else R.style.PacilReadTheme_ReaderYunbai
    }

    private fun preferences(context: Context?): SharedPreferences =
        requireNotNull(context).applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private fun isInlineColor(value: TypedValue): Boolean =
        value.type >= TypedValue.TYPE_FIRST_COLOR_INT && value.type <= TypedValue.TYPE_LAST_COLOR_INT
}
