package com.metahumanz.pacilread.ui;

import android.animation.ValueAnimator;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public final class LaunchSourceTransition {
    private static final String EXTRA_LEFT = "com.metahumanz.pacilread.EXTRA_LAUNCH_SOURCE_LEFT";
    private static final String EXTRA_TOP = "com.metahumanz.pacilread.EXTRA_LAUNCH_SOURCE_TOP";
    private static final String EXTRA_RIGHT = "com.metahumanz.pacilread.EXTRA_LAUNCH_SOURCE_RIGHT";
    private static final String EXTRA_BOTTOM = "com.metahumanz.pacilread.EXTRA_LAUNCH_SOURCE_BOTTOM";
    private static final String EXTRA_SNAPSHOT_PATH = "com.metahumanz.pacilread.EXTRA_LAUNCH_SOURCE_SNAPSHOT_PATH";
    private static final String LEGACY_EXTRA_LEFT = "com.metahumanz.pacilread.EXTRA_READER_SOURCE_LEFT";
    private static final String LEGACY_EXTRA_TOP = "com.metahumanz.pacilread.EXTRA_READER_SOURCE_TOP";
    private static final String LEGACY_EXTRA_RIGHT = "com.metahumanz.pacilread.EXTRA_READER_SOURCE_RIGHT";
    private static final String LEGACY_EXTRA_BOTTOM = "com.metahumanz.pacilread.EXTRA_READER_SOURCE_BOTTOM";

    private LaunchSourceTransition() {
    }

    public static final class Source {
        private final Rect bounds;
        private final Bitmap snapshot;

        private Source(Rect bounds, Bitmap snapshot) {
            this.bounds = bounds == null ? null : new Rect(bounds);
            this.snapshot = snapshot;
        }

        public Rect bounds() {
            return bounds == null ? null : new Rect(bounds);
        }

        public Bitmap snapshot() {
            return snapshot;
        }
    }

    public static final class Options {
        final long durationMs;
        final float snapshotFadeStartFraction;
        final Interpolator interpolator;
        final boolean enterUsesSnapshotOverlay;
        final boolean enterFadesContent;

        private Options(
                long durationMs,
                float snapshotFadeStartFraction,
                Interpolator interpolator,
                boolean enterUsesSnapshotOverlay,
                boolean enterFadesContent
        ) {
            this.durationMs = durationMs;
            this.snapshotFadeStartFraction = clampOption(snapshotFadeStartFraction, 0f, 1f);
            this.interpolator = interpolator;
            this.enterUsesSnapshotOverlay = enterUsesSnapshotOverlay;
            this.enterFadesContent = enterFadesContent;
        }

        public static Options defaults() {
            return new Options(260L, 0.5f, new DecelerateInterpolator(), true, true);
        }

        public Options withDuration(long durationMs) {
            return new Options(durationMs, snapshotFadeStartFraction, interpolator,
                    enterUsesSnapshotOverlay, enterFadesContent);
        }

        public Options withSnapshotFadeStartFraction(float fraction) {
            return new Options(durationMs, fraction, interpolator,
                    enterUsesSnapshotOverlay, enterFadesContent);
        }

        public Options withEnterSnapshotOverlay(boolean useSnapshotOverlay) {
            return new Options(durationMs, snapshotFadeStartFraction, interpolator,
                    useSnapshotOverlay, enterFadesContent);
        }

        public Options withEnterContentFade(boolean fadeContent) {
            return new Options(durationMs, snapshotFadeStartFraction, interpolator,
                    enterUsesSnapshotOverlay, fadeContent);
        }

        private static float clampOption(float value, float min, float max) {
            return Math.max(min, Math.min(max, value));
        }
    }

    // ==================== public API ====================

    public static void attach(Intent intent, View sourceView) {
        Source source = captureSource(sourceView);
        if (intent == null || source == null || source.bounds == null) {
            return;
        }
        putBounds(intent, source.bounds);
        String snapshotPath = persistSnapshot(sourceView, source.snapshot);
        if (snapshotPath != null) {
            intent.putExtra(EXTRA_SNAPSHOT_PATH, snapshotPath);
        }
    }

    public static void attachBoundsOnly(Intent intent, View sourceView) {
        Rect bounds = captureBounds(sourceView);
        if (intent == null || bounds == null) {
            return;
        }
        putBounds(intent, bounds);
    }

    public static Source captureSource(View sourceView) {
        Rect bounds = captureBounds(sourceView);
        if (bounds == null) {
            return null;
        }
        return new Source(bounds, captureSnapshot(sourceView));
    }

    public static Source sourceFromBounds(Rect bounds) {
        return bounds == null ? null : new Source(bounds, null);
    }

    public static Rect captureBounds(View sourceView) {
        if (sourceView == null || sourceView.getWidth() <= 0 || sourceView.getHeight() <= 0) {
            return null;
        }
        int[] location = new int[2];
        sourceView.getLocationOnScreen(location);
        return new Rect(
                location[0],
                location[1],
                location[0] + sourceView.getWidth(),
                location[1] + sourceView.getHeight()
        );
    }

    public static Source fromIntentSource(Intent intent) {
        Rect bounds = fromIntent(intent);
        if (bounds == null) {
            return null;
        }
        Bitmap snapshot = null;
        String snapshotPath = intent == null ? null : intent.getStringExtra(EXTRA_SNAPSHOT_PATH);
        if (snapshotPath != null && !snapshotPath.isBlank()) {
            snapshot = BitmapFactory.decodeFile(snapshotPath);
            if (snapshot != null) {
                new File(snapshotPath).delete();
            }
        }
        return new Source(bounds, snapshot);
    }

    public static Rect fromIntent(Intent intent) {
        if (intent == null) {
            return null;
        }
        String leftKey = intent.hasExtra(EXTRA_LEFT) ? EXTRA_LEFT : LEGACY_EXTRA_LEFT;
        String topKey = intent.hasExtra(EXTRA_LEFT) ? EXTRA_TOP : LEGACY_EXTRA_TOP;
        String rightKey = intent.hasExtra(EXTRA_LEFT) ? EXTRA_RIGHT : LEGACY_EXTRA_RIGHT;
        String bottomKey = intent.hasExtra(EXTRA_LEFT) ? EXTRA_BOTTOM : LEGACY_EXTRA_BOTTOM;
        if (!intent.hasExtra(leftKey)) {
            return null;
        }
        Rect bounds = new Rect(
                intent.getIntExtra(leftKey, 0),
                intent.getIntExtra(topKey, 0),
                intent.getIntExtra(rightKey, 0),
                intent.getIntExtra(bottomKey, 0)
        );
        return bounds.width() > 0 && bounds.height() > 0 ? bounds : null;
    }

    private static void putBounds(Intent intent, Rect bounds) {
        intent.putExtra(EXTRA_LEFT, bounds.left);
        intent.putExtra(EXTRA_TOP, bounds.top);
        intent.putExtra(EXTRA_RIGHT, bounds.right);
        intent.putExtra(EXTRA_BOTTOM, bounds.bottom);
    }

    // ==================== 退出动画（带延迟快照淡入） ====================

    public static boolean animateExitToSource(
            View targetView,
            Rect targetBounds,
            long durationMs,
            Runnable onComplete
    ) {
        return animateExitToSource(targetView, new Source(targetBounds, null),
                Options.defaults().withDuration(durationMs), onComplete);
    }

    public static boolean animateExitToSource(
            View targetView,
            Source source,
            long durationMs,
            Runnable onComplete
    ) {
        return animateExitToSource(targetView, source,
                Options.defaults().withDuration(durationMs), onComplete);
    }

    public static boolean animateExitToSource(
            View targetView,
            Source source,
            Options options,
            Runnable onComplete
    ) {
        Rect targetBounds = source == null ? null : source.bounds;
        if (targetView == null || targetBounds == null || targetBounds.width() <= 0 || targetBounds.height() <= 0) {
            return false;
        }
        if (targetView.getWidth() <= 0 || targetView.getHeight() <= 0) {
            return false;
        }

        ScreenCornerClipper.apply(targetView);
        targetView.animate().cancel();

        float pivotX = targetView.getWidth() / 2f;
        float pivotY = targetView.getHeight() / 2f;
        targetView.setPivotX(pivotX);
        targetView.setPivotY(pivotY);

        int[] targetLocation = untransformedLocationOnScreen(targetView);
        float destinationCenterX = targetBounds.centerX() - targetLocation[0];
        float destinationCenterY = targetBounds.centerY() - targetLocation[1];
        float destScaleX = clampScale(targetBounds.width() / (float) targetView.getWidth());
        float destScaleY = clampScale(targetBounds.height() / (float) targetView.getHeight());
        float destTransX = destinationCenterX - pivotX;
        float destTransY = destinationCenterY - pivotY;

        // 从当前视觉状态起始，避免手势松手后跳变
        float startScaleX = targetView.getScaleX();
        float startScaleY = targetView.getScaleY();
        float startTransX = targetView.getTranslationX();
        float startTransY = targetView.getTranslationY();
        float startAlpha = targetView.getAlpha();

        ImageView snapshotView = createSnapshotView(targetView, source.snapshot, targetLocation);
        if (snapshotView != null) {
            snapshotView.setPivotX(pivotX);
            snapshotView.setPivotY(pivotY);
        }

        float fadeStart = options.snapshotFadeStartFraction;

        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(options.durationMs);
        animator.setInterpolator(options.interpolator);
        animator.addUpdateListener(animation -> {
            float fraction = animation.getAnimatedFraction();
            targetView.setScaleX(lerp(startScaleX, destScaleX, fraction));
            targetView.setScaleY(lerp(startScaleY, destScaleY, fraction));
            targetView.setTranslationX(lerp(startTransX, destTransX, fraction));
            targetView.setTranslationY(lerp(startTransY, destTransY, fraction));

            // 原界面：前 fadeStart 保持当前透明度，之后淡出至 0
            float originalAlpha = fraction < fadeStart
                    ? startAlpha
                    : lerp(startAlpha, 0f, (fraction - fadeStart) / Math.max(1f - fadeStart, 0.001f));
            targetView.setAlpha(clampAlpha(originalAlpha));

            if (snapshotView != null) {
                // 目标快照：前 fadeStart 保持透明，之后淡入至 1
                float snapshotAlpha = fraction < fadeStart
                        ? 0f
                        : (fraction - fadeStart) / Math.max(1f - fadeStart, 0.001f);
                snapshotView.setScaleX(lerp(startScaleX, destScaleX, fraction));
                snapshotView.setScaleY(lerp(startScaleY, destScaleY, fraction));
                snapshotView.setTranslationX(targetView.getTranslationX());
                snapshotView.setTranslationY(targetView.getTranslationY());
                snapshotView.setAlpha(clampAlpha(snapshotAlpha));
            }
        });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (snapshotView != null && snapshotView.getParent() instanceof ViewGroup) {
                    ((ViewGroup) snapshotView.getParent()).getOverlay().remove(snapshotView);
                }
                if (onComplete != null) {
                    onComplete.run();
                }
            }
        });
        animator.start();
        return true;
    }

    public static boolean animateExitToSourceWithClip(
            View targetView,
            Source source,
            Options options,
            Runnable onComplete
    ) {
        Rect targetBounds = source == null ? null : source.bounds;
        if (targetView == null || targetBounds == null || targetBounds.width() <= 0 || targetBounds.height() <= 0) {
            return false;
        }
        if (targetView.getWidth() <= 0 || targetView.getHeight() <= 0) {
            return false;
        }

        ScreenCornerClipper.apply(targetView);
        targetView.animate().cancel();

        float pivotX = targetView.getWidth() / 2f;
        float pivotY = targetView.getHeight() / 2f;
        targetView.setPivotX(pivotX);
        targetView.setPivotY(pivotY);

        int[] targetLocation = untransformedLocationOnScreen(targetView);
        float startScaleX = clampScale(targetView.getScaleX());
        float startScaleY = clampScale(targetView.getScaleY());
        float startTransX = targetView.getTranslationX();
        float startTransY = targetView.getTranslationY();
        float startAlpha = targetView.getAlpha();

        float destinationCenterX = targetBounds.centerX() - targetLocation[0];
        float destinationCenterY = targetBounds.centerY() - targetLocation[1];
        float destTransX = destinationCenterX - pivotX;
        float destTransY = destinationCenterY - pivotY;
        float finalClipWidth = Math.min(targetView.getWidth(), Math.max(1f, targetBounds.width() / startScaleX));
        float finalClipHeight = Math.min(targetView.getHeight(), Math.max(1f, targetBounds.height() / startScaleY));

        Rect startClip = targetView.getClipBounds();
        if (startClip == null) {
            startClip = new Rect(0, 0, targetView.getWidth(), targetView.getHeight());
        }
        Rect endClip = new Rect(
                Math.round(pivotX - finalClipWidth / 2f),
                Math.round(pivotY - finalClipHeight / 2f),
                Math.round(pivotX + finalClipWidth / 2f),
                Math.round(pivotY + finalClipHeight / 2f)
        );
        clampRectToView(endClip, targetView.getWidth(), targetView.getHeight());

        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(options.durationMs);
        animator.setInterpolator(options.interpolator);
        Rect animatedClip = new Rect(startClip);
        Rect finalStartClip = new Rect(startClip);
        animator.addUpdateListener(animation -> {
            float fraction = animation.getAnimatedFraction();
            targetView.setScaleX(startScaleX);
            targetView.setScaleY(startScaleY);
            targetView.setTranslationX(lerp(startTransX, destTransX, fraction));
            targetView.setTranslationY(lerp(startTransY, destTransY, fraction));

            animatedClip.set(
                    Math.round(lerp(finalStartClip.left, endClip.left, fraction)),
                    Math.round(lerp(finalStartClip.top, endClip.top, fraction)),
                    Math.round(lerp(finalStartClip.right, endClip.right, fraction)),
                    Math.round(lerp(finalStartClip.bottom, endClip.bottom, fraction))
            );
            targetView.setClipBounds(animatedClip);

            float fadeStart = Math.max(0f, Math.min(0.9f, options.snapshotFadeStartFraction));
            float alpha = fraction < fadeStart
                    ? startAlpha
                    : lerp(startAlpha, 0f, (fraction - fadeStart) / Math.max(1f - fadeStart, 0.001f));
            targetView.setAlpha(clampAlpha(alpha));
        });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (onComplete != null) {
                    onComplete.run();
                }
            }
        });
        animator.start();
        return true;
    }

    // ==================== 进入动画（从来源放大到全屏） ====================

    public static boolean animateEnterFromSource(
            View targetView,
            Source source,
            Options options,
            Runnable onComplete
    ) {
        Rect sourceBounds = source == null ? null : source.bounds;
        if (targetView == null || sourceBounds == null || sourceBounds.width() <= 0 || sourceBounds.height() <= 0) {
            return false;
        }
        if (targetView.getWidth() <= 0 || targetView.getHeight() <= 0) {
            return false;
        }

        targetView.animate().cancel();

        float pivotX = targetView.getWidth() / 2f;
        float pivotY = targetView.getHeight() / 2f;
        targetView.setPivotX(pivotX);
        targetView.setPivotY(pivotY);

        int[] targetLocation = untransformedLocationOnScreen(targetView);
        float sourceCenterX = sourceBounds.centerX() - targetLocation[0];
        float sourceCenterY = sourceBounds.centerY() - targetLocation[1];
        float sourceScaleX = clampScale(sourceBounds.width() / (float) targetView.getWidth());
        float sourceScaleY = clampScale(sourceBounds.height() / (float) targetView.getHeight());
        float startTranslationX = sourceCenterX - pivotX;
        float startTranslationY = sourceCenterY - pivotY;

        // 设置起始状态：从来源位置/尺寸开始
        targetView.setScaleX(sourceScaleX);
        targetView.setScaleY(sourceScaleY);
        targetView.setTranslationX(startTranslationX);
        targetView.setTranslationY(startTranslationY);
        targetView.setAlpha(options.enterFadesContent ? 0f : 1f);

        ImageView snapshotView = options.enterUsesSnapshotOverlay
                ? createEnterSnapshotOverlay(
                        targetView, source, targetLocation, sourceScaleX, sourceScaleY,
                        startTranslationX, startTranslationY, pivotX, pivotY)
                : null;

        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(options.durationMs);
        animator.setInterpolator(options.interpolator);
        animator.addUpdateListener(animation -> {
            float fraction = animation.getAnimatedFraction();
            targetView.setScaleX(lerp(sourceScaleX, 1f, fraction));
            targetView.setScaleY(lerp(sourceScaleY, 1f, fraction));
            targetView.setTranslationX(lerp(startTranslationX, 0f, fraction));
            targetView.setTranslationY(lerp(startTranslationY, 0f, fraction));
            targetView.setAlpha(options.enterFadesContent ? clampAlpha(fraction) : 1f);

            if (snapshotView != null) {
                snapshotView.setScaleX(targetView.getScaleX());
                snapshotView.setScaleY(targetView.getScaleY());
                snapshotView.setTranslationX(targetView.getTranslationX());
                snapshotView.setTranslationY(targetView.getTranslationY());
                snapshotView.setAlpha(clampAlpha(1f - fraction));
            }
        });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (snapshotView != null && snapshotView.getParent() instanceof ViewGroup) {
                    ((ViewGroup) snapshotView.getParent()).getOverlay().remove(snapshotView);
                }
                if (onComplete != null) {
                    onComplete.run();
                }
            }
        });
        animator.start();
        return true;
    }

    public static boolean animateEnterFromSource(
            View targetView,
            Source source,
            long durationMs,
            Runnable onComplete
    ) {
        return animateEnterFromSource(targetView, source,
                Options.defaults().withDuration(durationMs), onComplete);
    }

    // ==================== 内部工具方法 ====================

    private static ImageView createEnterSnapshotOverlay(
            View targetView, Source source, int[] targetLocation,
            float sourceScaleX, float sourceScaleY,
            float startTranslationX, float startTranslationY,
            float pivotX, float pivotY
    ) {
        if (source.snapshot == null || !(targetView.getRootView() instanceof ViewGroup)) {
            return null;
        }
        ViewGroup root = (ViewGroup) targetView.getRootView();
        int[] rootLocation = new int[2];
        root.getLocationOnScreen(rootLocation);
        int left = targetLocation[0] - rootLocation[0];
        int top = targetLocation[1] - rootLocation[1];
        ImageView view = new ImageView(targetView.getContext());
        view.setImageBitmap(source.snapshot);
        view.setScaleType(ImageView.ScaleType.FIT_XY);
        view.setAlpha(1f);
        view.setPivotX(pivotX);
        view.setPivotY(pivotY);
        view.setScaleX(sourceScaleX);
        view.setScaleY(sourceScaleY);
        view.setTranslationX(startTranslationX);
        view.setTranslationY(startTranslationY);
        view.layout(left, top, left + targetView.getWidth(), top + targetView.getHeight());
        view.measure(
                View.MeasureSpec.makeMeasureSpec(targetView.getWidth(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(targetView.getHeight(), View.MeasureSpec.EXACTLY)
        );
        root.getOverlay().add(view);
        return view;
    }

    private static ImageView createSnapshotView(View targetView, Bitmap snapshot, int[] targetLocation) {
        if (snapshot == null || !(targetView.getRootView() instanceof ViewGroup)) {
            return null;
        }
        ViewGroup root = (ViewGroup) targetView.getRootView();
        int[] rootLocation = new int[2];
        root.getLocationOnScreen(rootLocation);
        int left = targetLocation[0] - rootLocation[0];
        int top = targetLocation[1] - rootLocation[1];
        ImageView snapshotView = new ImageView(targetView.getContext());
        snapshotView.setImageBitmap(snapshot);
        snapshotView.setScaleType(ImageView.ScaleType.FIT_XY);
        snapshotView.setAlpha(0f);
        snapshotView.setPivotX(targetView.getWidth() / 2f);
        snapshotView.setPivotY(targetView.getHeight() / 2f);
        snapshotView.layout(left, top, left + targetView.getWidth(), top + targetView.getHeight());
        snapshotView.setScaleX(targetView.getScaleX());
        snapshotView.setScaleY(targetView.getScaleY());
        snapshotView.setTranslationX(targetView.getTranslationX());
        snapshotView.setTranslationY(targetView.getTranslationY());
        snapshotView.measure(
                View.MeasureSpec.makeMeasureSpec(targetView.getWidth(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(targetView.getHeight(), View.MeasureSpec.EXACTLY)
        );
        root.getOverlay().add(snapshotView);
        return snapshotView;
    }

    private static Bitmap captureSnapshot(View sourceView) {
        if (sourceView == null || sourceView.getWidth() <= 0 || sourceView.getHeight() <= 0) {
            return null;
        }
        try {
            Bitmap bitmap = Bitmap.createBitmap(sourceView.getWidth(), sourceView.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            sourceView.draw(canvas);
            return bitmap;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String persistSnapshot(View sourceView, Bitmap snapshot) {
        if (sourceView == null || snapshot == null) {
            return null;
        }
        File dir = new File(sourceView.getContext().getCacheDir(), "launch_sources");
        if (!dir.exists() && !dir.mkdirs()) {
            return null;
        }
        File file;
        try {
            file = File.createTempFile("source_", ".png", dir);
        } catch (IOException ignored) {
            return null;
        }
        try (FileOutputStream output = new FileOutputStream(file)) {
            return snapshot.compress(Bitmap.CompressFormat.PNG, 100, output) ? file.getAbsolutePath() : null;
        } catch (IOException ignored) {
            return null;
        }
    }

    private static int[] untransformedLocationOnScreen(View targetView) {
        float scaleX = targetView.getScaleX();
        float scaleY = targetView.getScaleY();
        float translationX = targetView.getTranslationX();
        float translationY = targetView.getTranslationY();
        float alpha = targetView.getAlpha();
        targetView.setScaleX(1f);
        targetView.setScaleY(1f);
        targetView.setTranslationX(0f);
        targetView.setTranslationY(0f);
        int[] location = new int[2];
        targetView.getLocationOnScreen(location);
        targetView.setScaleX(scaleX);
        targetView.setScaleY(scaleY);
        targetView.setTranslationX(translationX);
        targetView.setTranslationY(translationY);
        targetView.setAlpha(alpha);
        return location;
    }

    private static float clampScale(float scale) {
        return Math.max(0.01f, Math.min(1f, scale));
    }

    private static float clampAlpha(float alpha) {
        return Math.max(0f, Math.min(1f, alpha));
    }

    private static void clampRectToView(Rect rect, int width, int height) {
        int rectWidth = Math.max(1, rect.width());
        int rectHeight = Math.max(1, rect.height());
        if (rect.left < 0) {
            rect.left = 0;
            rect.right = Math.min(width, rectWidth);
        }
        if (rect.top < 0) {
            rect.top = 0;
            rect.bottom = Math.min(height, rectHeight);
        }
        if (rect.right > width) {
            rect.right = width;
            rect.left = Math.max(0, width - rectWidth);
        }
        if (rect.bottom > height) {
            rect.bottom = height;
            rect.top = Math.max(0, height - rectHeight);
        }
    }

    private static float lerp(float start, float end, float fraction) {
        return start + (end - start) * fraction;
    }
}
