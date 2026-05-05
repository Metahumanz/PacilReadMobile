package com.metahumanz.pacilread.theme;

import android.content.Context;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.metahumanz.pacilread.ui.EdgeToEdgeHelper;

public abstract class ThemedReaderActivity extends AppCompatActivity {
    private String appliedThemeBucket = ThemeModeHelper.MODE_LIGHT;
    private String appliedStyleVariant = ThemeModeHelper.LIGHT_STYLE_YUNBAI;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(ThemeModeHelper.wrapForReader(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(ThemeModeHelper.resolveReaderThemeResId(this));
        appliedThemeBucket = ThemeModeHelper.getResolvedReaderBucket(this);
        appliedStyleVariant = ThemeModeHelper.getResolvedReaderStyleVariant(this);
        super.onCreate(savedInstanceState);
        EdgeToEdgeHelper.configure(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        String desiredBucket = ThemeModeHelper.getResolvedReaderBucket(this);
        String desiredStyleVariant = ThemeModeHelper.getResolvedReaderStyleVariant(this);
        if (!desiredBucket.equals(appliedThemeBucket) || !desiredStyleVariant.equals(appliedStyleVariant)) {
            recreate();
        }
    }

    protected void applyResolvedReaderThemeWithoutRecreate() {
        setTheme(ThemeModeHelper.resolveReaderThemeResId(this));
        appliedThemeBucket = ThemeModeHelper.getResolvedReaderBucket(this);
        appliedStyleVariant = ThemeModeHelper.getResolvedReaderStyleVariant(this);
        EdgeToEdgeHelper.configure(this);
    }

    protected boolean isDarkReaderUi() {
        return ThemeModeHelper.MODE_DARK.equals(ThemeModeHelper.getResolvedReaderBucket(this));
    }
}
