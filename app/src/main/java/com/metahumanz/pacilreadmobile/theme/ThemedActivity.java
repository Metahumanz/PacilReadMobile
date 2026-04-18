package com.metahumanz.pacilreadmobile.theme;

import android.app.Activity;
import android.content.Context;

public abstract class ThemedActivity extends Activity {
    private String appliedThemeMode = ThemeModeHelper.MODE_SYSTEM;

    @Override
    protected void attachBaseContext(Context newBase) {
        appliedThemeMode = ThemeModeHelper.getAppThemeMode(newBase);
        super.attachBaseContext(ThemeModeHelper.wrapForApp(newBase));
    }

    @Override
    protected void onResume() {
        super.onResume();
        String desiredMode = ThemeModeHelper.getAppThemeMode(this);
        if (!desiredMode.equals(appliedThemeMode)) {
            recreate();
        }
    }

    protected boolean isDarkAppTheme() {
        return ThemeModeHelper.isDark(getResources());
    }
}
