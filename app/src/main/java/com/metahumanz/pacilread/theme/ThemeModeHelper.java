package com.metahumanz.pacilread.theme;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.util.TypedValue;

import androidx.annotation.AttrRes;
import androidx.annotation.ColorInt;
import androidx.annotation.ColorRes;
import androidx.annotation.StyleRes;
import androidx.core.content.ContextCompat;

import com.metahumanz.pacilread.R;
import com.metahumanz.pacilread.storage.SettingsStore;

public final class ThemeModeHelper {
    public static final String MODE_SYSTEM = "system";
    public static final String MODE_LIGHT = "light";
    public static final String MODE_DARK = "dark";
    public static final String MODE_FOLLOW_APP = "follow_app";

    public static final String LIGHT_STYLE_YAOBAI = "yaobai";
    public static final String LIGHT_STYLE_YUNBAI = "yunbai";
    public static final String DARK_STYLE_YEMU = "yemu";
    public static final String DARK_STYLE_JIYE = "jiye";

    private static final String PREFS_NAME = "pacil_read_settings";
    private static final String KEY_APP_THEME_MODE = "app_theme_mode";
    private static final String KEY_READER_UI_THEME_MODE = "reader_ui_theme_mode";
    private static final String KEY_APP_LIGHT_STYLE_VARIANT = "app_light_style_variant";
    private static final String KEY_APP_DARK_STYLE_VARIANT = "app_dark_style_variant";

    private ThemeModeHelper() {
    }

    public static Context wrapForApp(Context base) {
        return wrap(base, getResolvedAppBucket(base));
    }

    public static Context wrapForReader(Context base) {
        return wrap(base, getResolvedReaderBucket(base));
    }

    public static String getAppThemeMode(Context context) {
        return SettingsStore.normalizeAppThemeMode(preferences(context).getString(KEY_APP_THEME_MODE, MODE_SYSTEM));
    }

    public static String getReaderUiThemeMode(Context context) {
        return SettingsStore.normalizeReaderUiThemeMode(preferences(context).getString(KEY_READER_UI_THEME_MODE, MODE_FOLLOW_APP));
    }

    public static String getAppLightStyleVariant(Context context) {
        return SettingsStore.normalizeAppLightStyleVariant(
                preferences(context).getString(KEY_APP_LIGHT_STYLE_VARIANT, LIGHT_STYLE_YUNBAI)
        );
    }

    public static String getAppDarkStyleVariant(Context context) {
        return SettingsStore.normalizeAppDarkStyleVariant(
                preferences(context).getString(KEY_APP_DARK_STYLE_VARIANT, DARK_STYLE_YEMU)
        );
    }

    public static String getResolvedAppBucket(Context context) {
        String mode = getAppThemeMode(context);
        if (MODE_LIGHT.equals(mode) || MODE_DARK.equals(mode)) {
            return mode;
        }
        return isSystemDark(context) ? MODE_DARK : MODE_LIGHT;
    }

    public static String getResolvedReaderBucket(Context context) {
        String mode = getReaderUiThemeMode(context);
        if (MODE_FOLLOW_APP.equals(mode)) {
            return getResolvedAppBucket(context);
        }
        if (MODE_SYSTEM.equals(mode)) {
            return isSystemDark(context) ? MODE_DARK : MODE_LIGHT;
        }
        return MODE_DARK.equals(mode) ? MODE_DARK : MODE_LIGHT;
    }

    public static String getResolvedReaderThemeMode(Context context) {
        return getResolvedReaderBucket(context);
    }

    public static String getResolvedAppStyleVariant(Context context) {
        return MODE_DARK.equals(getResolvedAppBucket(context))
                ? getAppDarkStyleVariant(context)
                : getAppLightStyleVariant(context);
    }

    public static String getResolvedReaderStyleVariant(Context context) {
        return MODE_DARK.equals(getResolvedReaderBucket(context))
                ? getAppDarkStyleVariant(context)
                : getAppLightStyleVariant(context);
    }

    @StyleRes
    public static int resolveAppThemeResId(Context context) {
        return resolveThemeResId(getResolvedAppBucket(context), getResolvedAppStyleVariant(context));
    }

    @StyleRes
    public static int resolveReaderThemeResId(Context context) {
        return resolveReaderThemeResId(getResolvedReaderBucket(context), getResolvedReaderStyleVariant(context));
    }

    public static String getResolvedAppAppearanceLabel(Context context) {
        return getAppearanceLabel(getResolvedAppBucket(context), getResolvedAppStyleVariant(context));
    }

    public static String getResolvedReaderAppearanceLabel(Context context) {
        return getAppearanceLabel(getResolvedReaderBucket(context), getResolvedReaderStyleVariant(context));
    }

    public static String getBucketLabel(String bucket) {
        return MODE_DARK.equals(bucket) ? "深色" : "浅色";
    }

    public static String getStyleLabel(String styleVariant) {
        switch (styleVariant) {
            case LIGHT_STYLE_YAOBAI:
                return "曜白";
            case DARK_STYLE_JIYE:
                return "极夜";
            case DARK_STYLE_YEMU:
                return "夜幕";
            default:
                return "云白";
        }
    }

    public static String getAppearanceLabel(String bucket, String styleVariant) {
        return getBucketLabel(bucket) + "·" + getStyleLabel(styleVariant);
    }

    public static boolean isDark(Resources resources) {
        int mask = resources.getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mask == Configuration.UI_MODE_NIGHT_YES;
    }

    public static boolean isSystemDark(Context context) {
        Resources resources = context == null
                ? Resources.getSystem()
                : context.getApplicationContext().getResources();
        return isDark(resources);
    }

    @ColorInt
    public static int resolveColor(Context context, @ColorRes int colorResId) {
        if (context == null) {
            return 0;
        }
        TypedValue value = new TypedValue();
        Resources resources = context.getResources();
        resources.getValue(colorResId, value, true);
        if (isInlineColor(value)) {
            return value.data;
        }
        if (value.type == TypedValue.TYPE_ATTRIBUTE) {
            return resolveThemeAttrColor(context, value.data);
        }
        if (value.resourceId != 0) {
            return ContextCompat.getColor(context, value.resourceId);
        }
        return ContextCompat.getColor(context, colorResId);
    }

    @ColorInt
    public static int resolveThemeAttrColor(Context context, @AttrRes int attrResId) {
        TypedValue value = new TypedValue();
        if (!context.getTheme().resolveAttribute(attrResId, value, true)) {
            return 0;
        }
        if (isInlineColor(value)) {
            return value.data;
        }
        if (value.resourceId != 0) {
            return ContextCompat.getColor(context, value.resourceId);
        }
        return 0;
    }

    private static Context wrap(Context base, String bucket) {
        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        configuration.uiMode = (configuration.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (MODE_DARK.equals(bucket) ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        return base.createConfigurationContext(configuration);
    }

    @StyleRes
    private static int resolveThemeResId(String bucket, String styleVariant) {
        if (MODE_DARK.equals(bucket)) {
            return DARK_STYLE_JIYE.equals(styleVariant)
                    ? R.style.PacilReadTheme_Jiye
                    : R.style.PacilReadTheme_Yemu;
        }
        return LIGHT_STYLE_YAOBAI.equals(styleVariant)
                ? R.style.PacilReadTheme_Yaobai
                : R.style.PacilReadTheme_Yunbai;
    }

    @StyleRes
    private static int resolveReaderThemeResId(String bucket, String styleVariant) {
        if (MODE_DARK.equals(bucket)) {
            return DARK_STYLE_JIYE.equals(styleVariant)
                    ? R.style.PacilReadTheme_ReaderJiye
                    : R.style.PacilReadTheme_ReaderYemu;
        }
        return LIGHT_STYLE_YAOBAI.equals(styleVariant)
                ? R.style.PacilReadTheme_ReaderYaobai
                : R.style.PacilReadTheme_ReaderYunbai;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static boolean isInlineColor(TypedValue value) {
        return value.type >= TypedValue.TYPE_FIRST_COLOR_INT
                && value.type <= TypedValue.TYPE_LAST_COLOR_INT;
    }
}
