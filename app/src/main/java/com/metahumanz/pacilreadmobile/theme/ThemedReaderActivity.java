package com.metahumanz.pacilreadmobile.theme;

import android.app.Activity;
import android.content.Context;

public abstract class ThemedReaderActivity extends Activity {
    private String appliedThemeMode = ThemeModeHelper.MODE_SYSTEM;

    @Override
    protected void attachBaseContext(Context newBase) {
        appliedThemeMode = ThemeModeHelper.getResolvedReaderThemeMode(newBase);
        super.attachBaseContext(ThemeModeHelper.wrapForReader(newBase));
    }

    @Override
    protected void onResume() {
        super.onResume();
        String desiredMode = ThemeModeHelper.getResolvedReaderThemeMode(this);
        if (!desiredMode.equals(appliedThemeMode)) {
            recreate();
        }
    }

    protected boolean isDarkReaderUi() {
        return ThemeModeHelper.isDark(getResources());
    }
}
