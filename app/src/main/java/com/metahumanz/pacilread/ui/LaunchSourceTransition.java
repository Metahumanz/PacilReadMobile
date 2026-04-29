package com.metahumanz.pacilread.ui;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.ViewGroup;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
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
    }

    public static void attach(Intent intent, View sourceView) {
        Source source = captureSource(sourceView);
        if (intent == null || source == null || source.bounds == null) {
            return;
        }
        intent.putExtra(EXTRA_LEFT, source.bounds.left);
        intent.putExtra(EXTRA_TOP, source.bounds.top);
        intent.putExtra(EXTRA_RIGHT, source.bounds.right);
        intent.putExtra(EXTRA_BOTTOM, source.bounds.bottom);
        String snapshotPath = persistSnapshot(sourceView, source.snapshot);
        if (snapshotPath != null) {
            intent.putExtra(EXTRA_SNAPSHOT_PATH, snapshotPath);
        }
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
                // The bitmap is now held in memory for the return animation; the cache file is no longer needed.
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

    public static boolean animateExitToSource(
            View targetView,
            Rect targetBounds,
            long durationMs,
            Runnable onComplete
    ) {
        return animateExitToSource(targetView, new Source(targetBounds, null), durationMs, onComplete);
    }

    public static boolean animateExitToSource(
            View targetView,
            Source source,
            long durationMs,
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
        targetView.setPivotX(targetView.getWidth() / 2f);
        targetView.setPivotY(targetView.getHeight() / 2f);
        int[] targetLocation = untransformedLocationOnScreen(targetView);
        float destinationCenterX = targetBounds.centerX() - targetLocation[0];
        float destinationCenterY = targetBounds.centerY() - targetLocation[1];
        float targetCenterX = targetView.getWidth() / 2f;
        float targetCenterY = targetView.getHeight() / 2f;
        float destinationScaleX = clampScale(targetBounds.width() / (float) targetView.getWidth());
        float destinationScaleY = clampScale(targetBounds.height() / (float) targetView.getHeight());
        ImageView snapshotView = createSnapshotView(targetView, source.snapshot, targetLocation);

        targetView.animate()
                .scaleX(destinationScaleX)
                .scaleY(destinationScaleY)
                .translationX(destinationCenterX - targetCenterX)
                .translationY(destinationCenterY - targetCenterY)
                .alpha(0f)
                .setDuration(durationMs)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(onComplete)
                .start();
        if (snapshotView != null) {
            snapshotView.animate()
                    .scaleX(destinationScaleX)
                    .scaleY(destinationScaleY)
                    .translationX(destinationCenterX - targetCenterX)
                    .translationY(destinationCenterY - targetCenterY)
                    .alpha(1f)
                    .setDuration(durationMs)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }
        return true;
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
}
