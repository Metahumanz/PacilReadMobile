package com.metahumanz.pacilread.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.metahumanz.pacilread.R;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

public final class BookCoverViewHelper {
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final ExecutorService COVER_EXECUTOR = Executors.newFixedThreadPool(2);
    private static final LruCache<String, Bitmap> COVER_CACHE = new LruCache<String, Bitmap>(maxCacheSizeBytes()) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value == null ? 0 : value.getByteCount();
        }
    };

    private BookCoverViewHelper() {
    }

    public static void bindCover(ImageView coverView, TextView fallbackView, String coverPath, String title) {
        if (coverView == null) {
            if (fallbackView != null) {
                fallbackView.setText(initialsFor(title));
                fallbackView.setVisibility(View.VISIBLE);
            }
            return;
        }
        String fallbackText = initialsFor(title);
        String cacheKey = cacheKeyFor(coverView, coverPath);
        coverView.setTag(R.id.tag_book_cover_request, cacheKey);
        if (cacheKey == null) {
            coverView.setImageDrawable(null);
            if (fallbackView != null) {
                fallbackView.setText(fallbackText);
                fallbackView.setVisibility(View.VISIBLE);
            }
            return;
        }

        Bitmap cached = COVER_CACHE.get(cacheKey);
        if (cached != null && !cached.isRecycled()) {
            coverView.setImageBitmap(cached);
            if (fallbackView != null) {
                fallbackView.setVisibility(View.GONE);
            }
            return;
        }

        coverView.setImageDrawable(null);
        if (fallbackView != null) {
            fallbackView.setText(fallbackText);
            fallbackView.setVisibility(View.VISIBLE);
        }

        int targetWidth = CoverDecodeSizing.targetSize(coverView.getWidth());
        int targetHeight = CoverDecodeSizing.targetSize(coverView.getHeight());
        try {
            COVER_EXECUTOR.execute(() -> {
                Bitmap decoded = decodeCover(coverPath, targetWidth, targetHeight);
                if (decoded != null) {
                    COVER_CACHE.put(cacheKey, decoded);
                }
                MAIN_HANDLER.post(() -> {
                    Object latestKey = coverView.getTag(R.id.tag_book_cover_request);
                    if (!cacheKey.equals(latestKey)) {
                        return;
                    }
                    Bitmap latest = COVER_CACHE.get(cacheKey);
                    if (latest != null && !latest.isRecycled()) {
                        coverView.setImageBitmap(latest);
                        if (fallbackView != null) {
                            fallbackView.setVisibility(View.GONE);
                        }
                    } else {
                        coverView.setImageDrawable(null);
                        if (fallbackView != null) {
                            fallbackView.setText(fallbackText);
                            fallbackView.setVisibility(View.VISIBLE);
                        }
                    }
                });
            });
        } catch (RejectedExecutionException ignored) {
            // Activity shutdown can race with adapter binding; keep the fallback visible.
        }
    }

    private static Bitmap decodeCover(String path, int targetWidth, int targetHeight) {
        if (path == null || path.isBlank()) {
            return null;
        }
        File file = new File(path);
        if (!file.exists()) {
            return null;
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = CoverDecodeSizing.sampleSizeFor(
                bounds.outWidth,
                bounds.outHeight,
                targetWidth,
                targetHeight
        );
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    }

    private static String cacheKeyFor(ImageView coverView, String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        File file = new File(path);
        if (!file.exists()) {
            return null;
        }
        return file.getAbsolutePath()
                + "#" + file.lastModified()
                + ":" + file.length()
                + ":" + CoverDecodeSizing.targetSize(coverView.getWidth())
                + "x" + CoverDecodeSizing.targetSize(coverView.getHeight());
    }

    private static int maxCacheSizeBytes() {
        long runtimeCache = Runtime.getRuntime().maxMemory() / 16L;
        long bounded = Math.max(4L * 1024L * 1024L, Math.min(runtimeCache, 24L * 1024L * 1024L));
        return (int) bounded;
    }

    private static String initialsFor(String title) {
        if (title == null || title.isBlank()) {
            return "PR";
        }
        String trimmed = title.trim();
        if (trimmed.length() == 1) {
            return trimmed.toUpperCase(Locale.ROOT);
        }
        return trimmed.substring(0, Math.min(2, trimmed.length())).toUpperCase(Locale.ROOT);
    }
}
