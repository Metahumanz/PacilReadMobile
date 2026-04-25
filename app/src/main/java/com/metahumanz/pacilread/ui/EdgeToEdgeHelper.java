package com.metahumanz.pacilread.ui;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;

import androidx.core.view.WindowCompat;

import com.metahumanz.pacilread.theme.ThemeModeHelper;

public final class EdgeToEdgeHelper {
    private EdgeToEdgeHelper() {
    }

    public static void configure(Activity activity) {
        if (activity == null) {
            return;
        }
        configure(activity.getWindow(), activity);
    }

    public static void configure(Window window, Activity activity) {
        if (window == null) {
            return;
        }
        WindowCompat.setDecorFitsSystemWindows(window, false);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(attributes);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
        }
        View decorView = window.getDecorView();
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (activity != null && !ThemeModeHelper.isDark(activity.getResources())) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        decorView.setSystemUiVisibility(flags);
    }

    public static void applySystemBarPadding(View view) {
        if (view == null) {
            return;
        }
        int initialLeft = view.getPaddingLeft();
        int initialTop = view.getPaddingTop();
        int initialRight = view.getPaddingRight();
        int initialBottom = view.getPaddingBottom();
        view.setOnApplyWindowInsetsListener((target, windowInsets) -> {
            int left;
            int top;
            int right;
            int bottom;
            boolean landscape = target.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Insets systemBars = windowInsets.getInsets(WindowInsets.Type.systemBars());
                Insets cutout = windowInsets.getInsets(WindowInsets.Type.displayCutout());
                left = landscape ? systemBars.left : Math.max(systemBars.left, cutout.left);
                top = Math.max(systemBars.top, cutout.top);
                right = landscape ? systemBars.right : Math.max(systemBars.right, cutout.right);
                bottom = Math.max(systemBars.bottom, cutout.bottom);
            } else {
                left = windowInsets.getSystemWindowInsetLeft();
                top = windowInsets.getSystemWindowInsetTop();
                right = windowInsets.getSystemWindowInsetRight();
                bottom = windowInsets.getSystemWindowInsetBottom();
            }
            target.setPadding(
                    initialLeft + left,
                    initialTop + top,
                    initialRight + right,
                    initialBottom + bottom
            );
            return windowInsets;
        });
        view.requestApplyInsets();
    }

    public static void applySystemBarPaddingToContentRoot(Activity activity) {
        if (activity == null) {
            return;
        }
        View content = activity.findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup)) {
            return;
        }
        ViewGroup contentGroup = (ViewGroup) content;
        if (contentGroup.getChildCount() > 0) {
            applySystemBarPadding(contentGroup.getChildAt(0));
        }
    }
}
