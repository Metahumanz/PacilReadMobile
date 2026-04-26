package com.metahumanz.pacilread.theme;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

import androidx.appcompat.app.AppCompatActivity;

import com.metahumanz.pacilread.ui.EdgeToEdgeHelper;

public abstract class ThemedActivity extends AppCompatActivity {
    private String appliedThemeBucket = ThemeModeHelper.MODE_LIGHT;
    private String appliedStyleVariant = ThemeModeHelper.LIGHT_STYLE_YUNBAI;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(ThemeModeHelper.wrapForApp(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(ThemeModeHelper.resolveAppThemeResId(this));
        appliedThemeBucket = ThemeModeHelper.getResolvedAppBucket(this);
        appliedStyleVariant = ThemeModeHelper.getResolvedAppStyleVariant(this);
        super.onCreate(savedInstanceState);
        EdgeToEdgeHelper.configure(this);
    }

    @Override
    public void setContentView(int layoutResID) {
        super.setContentView(layoutResID);
        EdgeToEdgeHelper.applySystemBarPaddingToContentRoot(this);
    }

    @Override
    public void setContentView(View view) {
        super.setContentView(view);
        EdgeToEdgeHelper.applySystemBarPaddingToContentRoot(this);
    }

    @Override
    public void setContentView(View view, ViewGroup.LayoutParams params) {
        super.setContentView(view, params);
        EdgeToEdgeHelper.applySystemBarPaddingToContentRoot(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        String desiredBucket = ThemeModeHelper.getResolvedAppBucket(this);
        String desiredStyleVariant = ThemeModeHelper.getResolvedAppStyleVariant(this);
        if (!desiredBucket.equals(appliedThemeBucket) || !desiredStyleVariant.equals(appliedStyleVariant)) {
            recreate();
        }
    }

    protected boolean isDarkAppTheme() {
        return ThemeModeHelper.isDark(getResources());
    }
}
