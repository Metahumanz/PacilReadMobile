package com.metahumanz.pacilread.ui

import android.annotation.TargetApi
import android.app.Activity
import android.graphics.Outline
import android.graphics.Rect
import android.os.Build
import android.view.RoundedCorner
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowInsets

object ScreenCornerClipper {
    private const val FALLBACK_RADIUS_DP = 32f
    private const val MAX_RADIUS_WINDOW_RATIO = 0.12f
    private const val MAX_MULTI_WINDOW_RADIUS_RATIO = 0.08f

    @JvmStatic
    fun apply(target: View?) {
        apply(null, target, null)
    }

    @JvmStatic
    fun apply(target: View?, outlineBounds: Rect?) {
        apply(null, target, outlineBounds)
    }

    @JvmStatic
    fun apply(activity: Activity?, target: View?) {
        apply(activity, target, null)
    }

    @JvmStatic
    fun apply(activity: Activity?, target: View?, outlineBounds: Rect?) {
        if (target == null) return
        target.clipToOutline = true
        target.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                if (view.width <= 0 || view.height <= 0) {
                    outline.setEmpty()
                    return
                }
                val bounds = outlineBounds?.let(::Rect) ?: Rect(0, 0, view.width, view.height)
                if (!bounds.intersect(0, 0, view.width, view.height) || bounds.isEmpty) {
                    outline.setEmpty()
                    return
                }
                outline.setRoundRect(
                    bounds.left,
                    bounds.top,
                    bounds.right,
                    bounds.bottom,
                    screenCornerRadiusPx(activity, view),
                )
            }
        }
        target.addOnLayoutChangeListener { view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                view.invalidateOutline()
            }
        }
        target.post { target.invalidateOutline() }
    }

    @JvmStatic
    fun setClipEnabled(target: View?, enabled: Boolean) {
        if (target == null) return
        target.clipToOutline = enabled
        if (!enabled) target.clipBounds = null
        target.invalidateOutline()
    }

    private fun screenCornerRadiusPx(activity: Activity?, view: View): Float {
        if (isInMultiWindowMode(activity)) return adaptiveWindowCornerRadiusPx(view)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val insets = view.rootWindowInsets
            if (insets != null) {
                var detectedRadius = 0
                detectedRadius = Math.max(detectedRadius, roundedCornerRadius(insets, RoundedCorner.POSITION_TOP_LEFT))
                detectedRadius = Math.max(detectedRadius, roundedCornerRadius(insets, RoundedCorner.POSITION_TOP_RIGHT))
                detectedRadius = Math.max(detectedRadius, roundedCornerRadius(insets, RoundedCorner.POSITION_BOTTOM_RIGHT))
                detectedRadius = Math.max(detectedRadius, roundedCornerRadius(insets, RoundedCorner.POSITION_BOTTOM_LEFT))
                if (detectedRadius > 0) return clampRadiusToWindow(view, detectedRadius.toFloat())
            }
        }
        return clampRadiusToWindow(view, FALLBACK_RADIUS_DP * view.resources.displayMetrics.density)
    }

    private fun adaptiveWindowCornerRadiusPx(view: View): Float =
        clampRadiusToWindow(view, FALLBACK_RADIUS_DP * view.resources.displayMetrics.density, MAX_MULTI_WINDOW_RADIUS_RATIO)

    private fun clampRadiusToWindow(view: View, radius: Float): Float =
        clampRadiusToWindow(view, radius, MAX_RADIUS_WINDOW_RATIO)

    private fun clampRadiusToWindow(view: View, radius: Float, maxRatio: Float): Float {
        val shortestSide = Math.min(view.width, view.height)
        if (shortestSide <= 0) return radius
        return Math.max(0f, Math.min(radius, shortestSide * maxRatio))
    }

    private fun isInMultiWindowMode(activity: Activity?): Boolean =
        activity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity.isInMultiWindowMode

    @TargetApi(Build.VERSION_CODES.S)
    private fun roundedCornerRadius(insets: WindowInsets, position: Int): Int =
        insets.getRoundedCorner(position)?.radius ?: 0
}
