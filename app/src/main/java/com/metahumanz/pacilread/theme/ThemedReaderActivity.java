package com.metahumanz.pacilread.theme;

import android.content.Context;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

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

    protected boolean isDarkReaderUi() {
        return ThemeModeHelper.isDark(getResources());
    }
}
