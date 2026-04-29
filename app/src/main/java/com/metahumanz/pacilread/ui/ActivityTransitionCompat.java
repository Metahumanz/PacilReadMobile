package com.metahumanz.pacilread.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Build;

public final class ActivityTransitionCompat {
    private ActivityTransitionCompat() {
    }

    public static void overrideOpen(Activity activity, int enterAnim, int exitAnim) {
        override(activity, Activity.OVERRIDE_TRANSITION_OPEN, enterAnim, exitAnim);
    }

    public static void overrideClose(Activity activity, int enterAnim, int exitAnim) {
        override(activity, Activity.OVERRIDE_TRANSITION_CLOSE, enterAnim, exitAnim);
    }

    @SuppressLint("NewApi")
    private static void override(Activity activity, int transitionType, int enterAnim, int exitAnim) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            activity.overrideActivityTransition(transitionType, enterAnim, exitAnim);
        } else {
            activity.overridePendingTransition(enterAnim, exitAnim);
        }
    }
}
