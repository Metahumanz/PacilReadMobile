package com.metahumanz.pacilread.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Build;

public final class ActivityTransitionCompat {
    private ActivityTransitionCompat() {
    }

    @SuppressLint({"NewApi", "InlinedApi"})
    public static void overrideOpen(Activity activity, int enterAnim, int exitAnim) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, enterAnim, exitAnim);
        } else {
            activity.overridePendingTransition(enterAnim, exitAnim);
        }
    }

    @SuppressLint({"NewApi", "InlinedApi"})
    public static void overrideClose(Activity activity, int enterAnim, int exitAnim) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, enterAnim, exitAnim);
        } else {
            activity.overridePendingTransition(enterAnim, exitAnim);
        }
    }
}
