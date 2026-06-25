package com.metahumanz.pacilread.ui

import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback

object PredictiveBackScaleController {
    const val STANDARD_MIN_SCALE = 0.68f
    const val READER_MIN_SCALE = 0.68f

    interface Delegate {
        fun shouldAnimateBack(): Boolean
        fun consumeBack(): Boolean
        fun commitBack()
        fun commitBackFromGesture(): Boolean = false
    }

    class Profile private constructor(
        @JvmField val minScale: Float,
        @JvmField val minAlpha: Float,
        @JvmField val translationDp: Float,
        @JvmField val cancelDurationMs: Long,
        @JvmField val commitDurationMs: Long,
        @JvmField val cornerClipDuringGestureOnly: Boolean,
    ) {
        companion object {
            @JvmStatic fun standard() = Profile(STANDARD_MIN_SCALE, 0.94f, 28f, 190L, 110L, false)
            @JvmStatic fun reader() = Profile(READER_MIN_SCALE, 0.96f, 14f, 180L, 100L, true)
        }
    }

    @JvmStatic
    fun install(activity: ComponentActivity, targetView: View, profile: Profile, delegate: Delegate): OnBackPressedCallback {
        ScreenCornerClipper.apply(targetView)
        if (profile.cornerClipDuringGestureOnly) ScreenCornerClipper.setClipEnabled(targetView, false)
        targetView.post {
            targetView.pivotX = targetView.width / 2f
            targetView.pivotY = targetView.height / 2f
        }
        val callback = object : OnBackPressedCallback(true) {
            private val interpolator = DecelerateInterpolator()
            private var gestureActive = false
            private var animatedDuringGesture = false
            private var committing = false
            private var cornerClipEnabled = false
            private var lastSwipeEdge = BackEventCompat.EDGE_LEFT
            private var lastProgress = 0f

            override fun handleOnBackStarted(backEvent: BackEventCompat) {
                if (committing) return
                gestureActive = true
                animatedDuringGesture = delegate.shouldAnimateBack()
                lastSwipeEdge = backEvent.swipeEdge
                lastProgress = backEvent.progress
                targetView.animate().cancel()
                if (animatedDuringGesture) applyProgress(backEvent)
            }

            override fun handleOnBackProgressed(backEvent: BackEventCompat) {
                if (committing) return
                if (!gestureActive) {
                    gestureActive = true
                    animatedDuringGesture = delegate.shouldAnimateBack()
                }
                lastSwipeEdge = backEvent.swipeEdge
                lastProgress = backEvent.progress
                if (animatedDuringGesture) applyProgress(backEvent)
            }

            override fun handleOnBackPressed() {
                if (committing) return
                if (delegate.consumeBack()) {
                    finishGesture()
                    animateToRest()
                    return
                }
                if (gestureActive && animatedDuringGesture) {
                    committing = true
                    enableCornerClipIfNeeded()
                    if (delegate.commitBackFromGesture()) {
                        finishGesture()
                        delegate.commitBack()
                        return
                    }
                    animateToCommit(Runnable { delegate.commitBack() })
                    return
                }
                finishGesture()
                disableCornerClipIfNeeded()
                delegate.commitBack()
            }

            override fun handleOnBackCancelled() {
                if (committing) return
                finishGesture()
                animateToRest()
            }

            private fun applyProgress(backEvent: BackEventCompat) {
                enableCornerClipIfNeeded()
                val eased = eased(clamp(backEvent.progress))
                val scale = lerp(1f, profile.minScale, eased)
                targetView.scaleX = scale
                targetView.scaleY = scale
                targetView.alpha = lerp(1f, profile.minAlpha, eased)
                targetView.translationX = translationPx() * swipeDirection(backEvent.swipeEdge) * eased
            }

            private fun animateToRest() {
                targetView.animate().scaleX(1f).scaleY(1f).alpha(1f).translationX(0f)
                    .setDuration(profile.cancelDurationMs).setInterpolator(interpolator)
                    .withEndAction { disableCornerClipIfNeeded() }.start()
            }

            private fun animateToCommit(onComplete: Runnable) {
                enableCornerClipIfNeeded()
                val eased = eased(Math.max(lastProgress, 0.72f))
                targetView.animate().scaleX(profile.minScale).scaleY(profile.minScale).alpha(profile.minAlpha)
                    .translationX(translationPx() * swipeDirection(lastSwipeEdge) * eased)
                    .setDuration(profile.commitDurationMs).setInterpolator(interpolator)
                    .withEndAction(onComplete).start()
            }

            private fun finishGesture() {
                gestureActive = false
                animatedDuringGesture = false
                lastProgress = 0f
            }

            private fun enableCornerClipIfNeeded() {
                if (!profile.cornerClipDuringGestureOnly || cornerClipEnabled) return
                cornerClipEnabled = true
                ScreenCornerClipper.setClipEnabled(targetView, true)
            }

            private fun disableCornerClipIfNeeded() {
                if (!profile.cornerClipDuringGestureOnly || !cornerClipEnabled) return
                cornerClipEnabled = false
                ScreenCornerClipper.setClipEnabled(targetView, false)
            }

            private fun translationPx(): Float = profile.translationDp * targetView.resources.displayMetrics.density
        }
        activity.onBackPressedDispatcher.addCallback(activity, callback)
        return callback
    }

    private fun swipeDirection(swipeEdge: Int): Float = if (swipeEdge == BackEventCompat.EDGE_RIGHT) -1f else 1f
    private fun eased(value: Float): Float = 1f - (1f - value) * (1f - value)
    private fun lerp(start: Float, end: Float, amount: Float): Float = start + (end - start) * amount
    private fun clamp(value: Float): Float = Math.max(0f, Math.min(1f, value))
}
