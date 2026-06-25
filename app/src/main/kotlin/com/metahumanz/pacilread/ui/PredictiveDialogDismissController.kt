package com.metahumanz.pacilread.ui

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.os.Build
import android.view.View
import android.view.Window
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.window.BackEvent
import android.window.OnBackAnimationCallback
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher

object PredictiveDialogDismissController {
    private const val MIN_SCALE = PredictiveBackScaleController.READER_MIN_SCALE
    private const val MIN_ALPHA = 0.96f

    class Registration internal constructor(
        private val dialog: AlertDialog?,
        private val callback: OnBackInvokedCallback?,
    ) {
        private var unregistered = false

        fun unregister() {
            if (unregistered || dialog == null || callback == null) return
            unregistered = true
            unregisterPredictiveDismiss(dialog, callback)
        }
    }

    @SuppressLint("NewApi")
    @JvmStatic
    fun install(
        dialog: AlertDialog?,
        window: Window?,
        enabled: Boolean,
        dismissSource: LaunchSourceTransition.Source?,
    ): Registration {
        if (!enabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || dialog == null || window == null) {
            return Registration(null, null)
        }
        val target = window.decorView
        ScreenCornerClipper.apply(target)
        target.post {
            target.pivotX = target.width / 2f
            target.pivotY = target.height / 2f
        }
        val callback = object : OnBackAnimationCallback {
            private var dismissing = false

            override fun onBackStarted(backEvent: BackEvent) {
                if (dismissing) return
                target.animate().cancel()
                applyBackProgress(target, backEvent.progress)
            }

            override fun onBackProgressed(backEvent: BackEvent) {
                if (dismissing) return
                applyBackProgress(target, backEvent.progress)
            }

            override fun onBackCancelled() {
                if (dismissing) return
                target.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(180L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }

            override fun onBackInvoked() {
                if (dismissing) return
                dismissing = true
                target.animate().cancel()
                if (LaunchSourceTransition.animateExitToSource(
                        target,
                        dismissSource,
                        230L,
                        Runnable { dismissIfShowing(dialog) },
                    )
                ) return
                target.animate()
                    .scaleX(MIN_SCALE)
                    .scaleY(MIN_SCALE)
                    .alpha(0f)
                    .setDuration(130L)
                    .setInterpolator(AccelerateInterpolator())
                    .withEndAction { dismissIfShowing(dialog) }
                    .start()
            }
        }
        dialog.onBackInvokedDispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            callback,
        )
        return Registration(dialog, callback)
    }

    private fun dismissIfShowing(dialog: AlertDialog) {
        if (dialog.isShowing) dialog.dismiss()
    }

    @SuppressLint("NewApi")
    private fun unregisterPredictiveDismiss(dialog: AlertDialog?, callback: OnBackInvokedCallback?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || dialog == null || callback == null) return
        try {
            dialog.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback)
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun applyBackProgress(target: View, progress: Float) {
        val safeProgress = Math.max(0f, Math.min(1f, progress))
        val eased = 1f - (1f - safeProgress) * (1f - safeProgress)
        val scale = 1f + (MIN_SCALE - 1f) * eased
        target.scaleX = scale
        target.scaleY = scale
        target.alpha = 1f + (MIN_ALPHA - 1f) * eased
    }
}
