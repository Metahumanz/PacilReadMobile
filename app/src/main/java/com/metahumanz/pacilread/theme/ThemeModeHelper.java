package com.metahumanz.pacilread.theme;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;

public final class ThemeModeHelper {
    public static final String MODE_SYSTEM = "system";
    public static final String MODE_LIGHT = "light";
    public static final String MODE_DARK = "dark";
    public static final String MODE_FOLLOW_APP = "follow_app";

    private static final String PREFS_NAME = "pacil_read_settings";
    private static final String KEY_APP_THEME_MODE = "app_theme_mode";
    private static final String KEY_READER_UI_THEME_MODE = "reader_ui_theme_mode";

    private ThemeModeHelper() {
    }

    public static void apply(Context context) {
        String mode = getAppThemeMode(context);
        int nightMode;
        switch (mode) {
            case MODE_LIGHT:
                nightMode = androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO;
                break;
            case MODE_DARK:
                nightMode = androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES;
                break;
            default:
                nightMode = androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
                break;
        }
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(nightMode);
    }

    public static Context wrapForApp(Context base) {
        return wrap(base, getAppThemeMode(base));
    }

    public static Context wrapForReader(Context base) {
        return wrap(base, getResolvedReaderThemeMode(base));
    }

    public static String getAppThemeMode(Context context) {
        return normalizeAppThemeMode(preferences(context).getString(KEY_APP_THEME_MODE, MODE_SYSTEM));
    }

    public static String getReaderUiThemeMode(Context context) {
        return normalizeReaderUiThemeMode(preferences(context).getString(KEY_READER_UI_THEME_MODE, MODE_FOLLOW_APP));
    }

    public static String getResolvedReaderThemeMode(Context context) {
        String readerMode = getReaderUiThemeMode(context);
        return MODE_FOLLOW_APP.equals(readerMode) ? getAppThemeMode(context) : readerMode;
    }

    public static boolean isDark(Resources resources) {
        int mask = resources.getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mask == Configuration.UI_MODE_NIGHT_YES;
    }

    private static Context wrap(Context base, String mode) {
        String normalized = normalizeAppThemeMode(mode);
        if (MODE_SYSTEM.equals(normalized)) {
            return base;
        }
        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        configuration.uiMode = (configuration.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (MODE_DARK.equals(normalized) ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        return base.createConfigurationContext(configuration);
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static String normalizeAppThemeMode(String value) {
        if (MODE_LIGHT.equals(value) || MODE_DARK.equals(value)) {
            return value;
        }
        return MODE_SYSTEM;
    }

    private static String normalizeReaderUiThemeMode(String value) {
        if (MODE_SYSTEM.equals(value) || MODE_LIGHT.equals(value) || MODE_DARK.equals(value)) {
            return value;
        }
        return MODE_FOLLOW_APP;
    }
}
