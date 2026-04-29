package com.metahumanz.pacilread.ui;

import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.activity.BackEventCompat;
import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;

public final class PredictiveBackScaleController {
    public static final float STANDARD_MIN_SCALE = 0.68f;
    public static final float READER_MIN_SCALE = 0.68f;

    private PredictiveBackScaleController() {
    }

    public interface Delegate {
        boolean shouldAnimateBack();
        boolean consumeBack();
        void commitBack();

        default boolean commitBackFromGesture() {
            return false;
        }
    }

    public static final class Profile {
        final float minScale;
        final float minAlpha;
        final float translationDp;
        final long cancelDurationMs;
        final long commitDurationMs;

        private Profile(float minScale, float minAlpha, float translationDp,
                        long cancelDurationMs, long commitDurationMs) {
            this.minScale = minScale;
            this.minAlpha = minAlpha;
            this.translationDp = translationDp;
            this.cancelDurationMs = cancelDurationMs;
            this.commitDurationMs = commitDurationMs;
        }

        public static Profile standard() {
            return new Profile(STANDARD_MIN_SCALE, 0.94f, 28f, 190L, 110L);
        }

        public static Profile reader() {
            return new Profile(READER_MIN_SCALE, 0.96f, 14f, 180L, 100L);
        }
    }

    public static OnBackPressedCallback install(
            ComponentActivity activity,
            View targetView,
            Profile profile,
            Delegate delegate
    ) {
        ScreenCornerClipper.apply(targetView);
        targetView.post(() -> {
            targetView.setPivotX(targetView.getWidth() / 2f);
            targetView.setPivotY(targetView.getHeight() / 2f);
        });
        OnBackPressedCallback callback = new OnBackPressedCallback(true) {
            private final DecelerateInterpolator interpolator = new DecelerateInterpolator();
            private boolean gestureActive;
            private boolean animatedDuringGesture;
            private boolean committing;
            private int lastSwipeEdge = BackEventCompat.EDGE_LEFT;
            private float lastProgress;

            @Override
            public void handleOnBackStarted(BackEventCompat backEvent) {
                if (committing) {
                    return;
                }
                gestureActive = true;
                animatedDuringGesture = delegate.shouldAnimateBack();
                lastSwipeEdge = backEvent.getSwipeEdge();
                lastProgress = backEvent.getProgress();
                targetView.animate().cancel();
                if (animatedDuringGesture) {
                    applyProgress(backEvent);
                }
            }

            @Override
            public void handleOnBackProgressed(BackEventCompat backEvent) {
                if (committing) {
                    return;
                }
                if (!gestureActive) {
                    gestureActive = true;
                    animatedDuringGesture = delegate.shouldAnimateBack();
                }
                lastSwipeEdge = backEvent.getSwipeEdge();
                lastProgress = backEvent.getProgress();
                if (animatedDuringGesture) {
                    applyProgress(backEvent);
                }
            }

            @Override
            public void handleOnBackPressed() {
                if (committing) {
                    return;
                }
                if (delegate.consumeBack()) {
                    finishGesture();
                    animateToRest();
                    return;
                }
                if (gestureActive && animatedDuringGesture) {
                    committing = true;
                    if (delegate.commitBackFromGesture()) {
                        finishGesture();
                        delegate.commitBack();
                        return;
                    }
                    animateToCommit(delegate::commitBack);
                    return;
                }
                finishGesture();
                delegate.commitBack();
            }

            @Override
            public void handleOnBackCancelled() {
                if (committing) {
                    return;
                }
                finishGesture();
                animateToRest();
            }

            private void applyProgress(BackEventCompat backEvent) {
                float eased = eased(clamp(backEvent.getProgress()));
                float scale = lerp(1f, profile.minScale, eased);
                targetView.setScaleX(scale);
                targetView.setScaleY(scale);
                targetView.setAlpha(lerp(1f, profile.minAlpha, eased));
                targetView.setTranslationX(translationPx() * swipeDirection(backEvent.getSwipeEdge()) * eased);
            }

            private void animateToRest() {
                targetView.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .alpha(1f)
                        .translationX(0f)
                        .setDuration(profile.cancelDurationMs)
                        .setInterpolator(interpolator)
                        .start();
            }

            private void animateToCommit(Runnable onComplete) {
                float eased = eased(Math.max(lastProgress, 0.72f));
                targetView.animate()
                        .scaleX(profile.minScale)
                        .scaleY(profile.minScale)
                        .alpha(profile.minAlpha)
                        .translationX(translationPx() * swipeDirection(lastSwipeEdge) * eased)
                        .setDuration(profile.commitDurationMs)
                        .setInterpolator(interpolator)
                        .withEndAction(onComplete)
                        .start();
            }

            private void finishGesture() {
                gestureActive = false;
                animatedDuringGesture = false;
                lastProgress = 0f;
            }

            private float translationPx() {
                return profile.translationDp * targetView.getResources().getDisplayMetrics().density;
            }
        };
        activity.getOnBackPressedDispatcher().addCallback(activity, callback);
        return callback;
    }

    private static float swipeDirection(int swipeEdge) {
        return swipeEdge == BackEventCompat.EDGE_RIGHT ? -1f : 1f;
    }

    private static float eased(float value) {
        float inverse = 1f - value;
        return 1f - inverse * inverse;
    }

    private static float lerp(float start, float end, float amount) {
        return start + (end - start) * amount;
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
