package com.metahumanz.pacilread.reader.modern;

import android.widget.Toast;

import androidx.annotation.ColorRes;

import com.metahumanz.pacilread.theme.ThemeModeHelper;

public final class ReaderUiUtils {
    private final ModernReaderActivity activity;

    public ReaderUiUtils(ModernReaderActivity activity) {
        this.activity = activity;
    }

    public int themeColor(@ColorRes int resId) {
        return ThemeModeHelper.resolveColor(activity, resId);
    }

    public int dp(int value) {
        return Math.round(activity.getResources().getDisplayMetrics().density * value);
    }

    public int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public void showToast(String text) {
        Toast.makeText(activity, text, Toast.LENGTH_SHORT).show();
    }
}
