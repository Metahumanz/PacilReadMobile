package com.metahumanz.pacilread.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.util.Locale;

public final class BookCoverViewHelper {
    private BookCoverViewHelper() {
    }

    public static void bindCover(ImageView coverView, TextView fallbackView, String coverPath, String title) {
        Bitmap coverBitmap = decodeCover(coverPath);
        if (coverBitmap != null && coverView != null) {
            coverView.setImageBitmap(coverBitmap);
            if (fallbackView != null) {
                fallbackView.setVisibility(View.GONE);
            }
            return;
        }
        if (coverView != null) {
            coverView.setImageDrawable(null);
        }
        if (fallbackView != null) {
            fallbackView.setText(initialsFor(title));
            fallbackView.setVisibility(View.VISIBLE);
        }
    }

    private static Bitmap decodeCover(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        File file = new File(path);
        if (!file.exists()) {
            return null;
        }
        return BitmapFactory.decodeFile(file.getAbsolutePath());
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
