package com.metahumanz.pacilread;

import android.content.Context;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import com.metahumanz.pacilread.theme.ThemeModeHelper;

public final class AppUiUtils {
    private AppUiUtils() {}

    public static int dp(Context context, int value) {
        return Math.round(context.getResources().getDisplayMetrics().density * value);
    }

    public static void showToast(Context context, String text) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
    }

    public static boolean isMotionEventInsideView(View view, MotionEvent event) {
        if (view == null || event == null || view.getVisibility() != View.VISIBLE) {
            return false;
        }
        Rect bounds = new Rect();
        return view.getGlobalVisibleRect(bounds)
                && bounds.contains(Math.round(event.getRawX()), Math.round(event.getRawY()));
    }

    public static void styleSelectionButton(Context context, Button button, boolean selected) {
        if (button == null) {
            return;
        }
        button.setBackgroundResource(selected ? R.drawable.bg_app_primary_button : R.drawable.bg_app_outline_button);
        button.setTextColor(ThemeModeHelper.resolveColor(
                context,
                selected ? R.color.app_button_primary_text : R.color.app_button_outline_text
        ));
    }

    public static void styleToggleButton(Context context, Button button, boolean selected) {
        if (button == null) {
            return;
        }
        button.setSelected(selected);
        styleSelectionButton(context, button, selected);
    }
}
