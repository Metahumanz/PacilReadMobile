package com.metahumanz.pacilread.theme;

import android.content.Context;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;

public abstract class ThemedActivity extends AppCompatActivity {
    private String appliedThemeMode = ThemeModeHelper.MODE_SYSTEM;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeModeHelper.apply(this);
        appliedThemeMode = ThemeModeHelper.getAppThemeMode(this);
        super.onCreate(savedInstanceState);
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
