package com.metahumanz.pacilread.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.UUID;

public final class CoverImageStore {
    private static final int MAX_COVER_DIMENSION_PX = 900;
    private static final int JPEG_QUALITY = 85;

    private CoverImageStore() {
    }

    public static File saveCompressedCover(Context context, Uri uri, String prefix) throws IOException {
        try (InputStream inputStream = context.getApplicationContext().getContentResolver().openInputStream(uri)) {
            if (inputStream == null) {
                throw new IOException("无法读取封面图片");
            }
            return saveCompressedCover(context, readAllBytes(inputStream), prefix);
        }
    }

    public static File saveCompressedCover(Context context, File sourceFile, String prefix) throws IOException {
        if (sourceFile == null || !sourceFile.exists()) {
            throw new IOException("封面文件不存在");
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(sourceFile.getAbsolutePath(), bounds);
        BitmapFactory.Options options = decodeOptionsFor(bounds);
        Bitmap bitmap = BitmapFactory.decodeFile(sourceFile.getAbsolutePath(), options);
        return saveBitmap(context, bitmap, prefix);
    }

    public static File saveCompressedCover(Context context, byte[] imageBytes, String prefix) throws IOException {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new IOException("封面图片为空");
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length, bounds);
        BitmapFactory.Options options = decodeOptionsFor(bounds);
        Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length, options);
        return saveBitmap(context, bitmap, prefix);
    }

    private static BitmapFactory.Options decodeOptionsFor(BitmapFactory.Options bounds) throws IOException {
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("封面图片格式不受支持");
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight);
        return options;
    }

    private static int sampleSizeFor(int width, int height) {
        int sampleSize = 1;
        int longest = Math.max(width, height);
        while (longest / sampleSize > MAX_COVER_DIMENSION_PX * 2) {
            sampleSize *= 2;
        }
        return sampleSize;
    }

    private static File saveBitmap(Context context, Bitmap bitmap, String prefix) throws IOException {
        if (bitmap == null) {
            throw new IOException("封面图片解码失败");
        }
        Bitmap scaled = scaleDown(bitmap);
        Bitmap flattened = flattenOnWhite(scaled);
        File destination = destinationFile(context, prefix);
        try (FileOutputStream outputStream = new FileOutputStream(destination)) {
            if (!flattened.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)) {
                throw new IOException("封面图片压缩失败");
            }
        } finally {
            if (flattened != scaled) {
                flattened.recycle();
            }
            if (scaled != bitmap) {
                scaled.recycle();
            }
            bitmap.recycle();
        }
        return destination;
    }

    private static Bitmap scaleDown(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int longest = Math.max(width, height);
        if (longest <= MAX_COVER_DIMENSION_PX) {
            return bitmap;
        }
        float scale = MAX_COVER_DIMENSION_PX / (float) longest;
        int targetWidth = Math.max(1, Math.round(width * scale));
        int targetHeight = Math.max(1, Math.round(height * scale));
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true);
    }

    private static Bitmap flattenOnWhite(Bitmap bitmap) {
        if (!bitmap.hasAlpha()) {
            return bitmap;
        }
        Bitmap flattened = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(flattened);
        canvas.drawColor(Color.WHITE);
        canvas.drawBitmap(bitmap, 0f, 0f, new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
        return flattened;
    }

    private static File destinationFile(Context context, String prefix) throws IOException {
        File folder = new File(context.getApplicationContext().getFilesDir(), "covers");
        if (!folder.exists() && !folder.mkdirs()) {
            throw new IOException("无法创建封面目录: " + folder.getAbsolutePath());
        }
        return new File(folder, safePrefix(prefix) + "_" + UUID.randomUUID() + ".jpg");
    }

    private static String safePrefix(String prefix) {
        String value = prefix == null ? "cover" : prefix.trim().toLowerCase(Locale.ROOT);
        value = value.replaceAll("[^a-z0-9_-]+", "_");
        return value.isEmpty() ? "cover" : value;
    }

    private static byte[] readAllBytes(InputStream inputStream) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, read);
        }
        return outputStream.toByteArray();
    }
}
