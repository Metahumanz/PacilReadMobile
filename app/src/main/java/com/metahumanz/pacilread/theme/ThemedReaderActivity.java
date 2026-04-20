package com.metahumanz.pacilread.theme;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;

public abstract class ThemedReaderActivity extends AppCompatActivity {
    private String appliedThemeMode = ThemeModeHelper.MODE_SYSTEM;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeModeHelper.apply(this);
        appliedThemeMode = ThemeModeHelper.getResolvedReaderThemeMode(this);
        super.onCreate(savedInstanceState);
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
