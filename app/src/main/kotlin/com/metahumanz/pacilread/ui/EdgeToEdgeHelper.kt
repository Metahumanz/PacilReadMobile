package com.metahumanz.pacilread.ui

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import androidx.core.view.WindowCompat
import com.metahumanz.pacilread.theme.ThemeModeHelper

object EdgeToEdgeHelper {
    @JvmStatic
    fun configure(activity: Activity?) {
        if (activity == null) return
        configure(activity.window, activity)
    }

    @JvmStatic
    @Suppress("DEPRECATION")
    fun configure(window: Window?, activity: Activity?) {
        if (window == null) return
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val attributes = window.attributes
            attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = attributes
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        if (activity != null && !ThemeModeHelper.isDark(activity.resources)) {
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        window.decorView.systemUiVisibility = flags
    }

    @JvmStatic
    @Suppress("DEPRECATION")
    fun applySystemBarPadding(view: View?) {
        if (view == null) return
        val initialLeft = view.paddingLeft
        val initialTop = view.paddingTop
        val initialRight = view.paddingRight
        val initialBottom = view.paddingBottom
        view.setOnApplyWindowInsetsListener { target, windowInsets ->
            val landscape = target.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val left: Int
            val top: Int
            val right: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val systemBars = windowInsets.getInsets(WindowInsets.Type.systemBars())
                val cutout = windowInsets.getInsets(WindowInsets.Type.displayCutout())
                left = if (landscape) systemBars.left else maxOf(systemBars.left, cutout.left)
                top = maxOf(systemBars.top, cutout.top)
                right = if (landscape) systemBars.right else maxOf(systemBars.right, cutout.right)
                bottom = maxOf(systemBars.bottom, cutout.bottom)
            } else {
                left = windowInsets.systemWindowInsetLeft
                top = windowInsets.systemWindowInsetTop
                right = windowInsets.systemWindowInsetRight
                bottom = windowInsets.systemWindowInsetBottom
            }
            target.setPadding(initialLeft + left, initialTop + top, initialRight + right, initialBottom + bottom)
            windowInsets
        }
        view.requestApplyInsets()
    }

    @JvmStatic
    fun applySystemBarPaddingToContentRoot(activity: Activity?) {
        if (activity == null) return
        val content = activity.findViewById<View>(android.R.id.content)
        if (content !is ViewGroup) return
        if (content.childCount > 0) applySystemBarPadding(content.getChildAt(0))
    }
}
