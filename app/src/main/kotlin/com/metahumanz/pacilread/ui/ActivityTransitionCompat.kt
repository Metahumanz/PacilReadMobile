package com.metahumanz.pacilread.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Build

object ActivityTransitionCompat {
    @JvmStatic
    @SuppressLint("NewApi", "InlinedApi")
    fun overrideOpen(activity: Activity, enterAnim: Int, exitAnim: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, enterAnim, exitAnim)
        } else {
            @Suppress("DEPRECATION")
            activity.overridePendingTransition(enterAnim, exitAnim)
        }
    }

    @JvmStatic
    @SuppressLint("NewApi", "InlinedApi")
    fun overrideClose(activity: Activity, enterAnim: Int, exitAnim: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, enterAnim, exitAnim)
        } else {
            @Suppress("DEPRECATION")
            activity.overridePendingTransition(enterAnim, exitAnim)
        }
    }
}
