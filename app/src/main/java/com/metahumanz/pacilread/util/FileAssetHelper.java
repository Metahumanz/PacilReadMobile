package com.metahumanz.pacilread.util;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.UUID;

public final class FileAssetHelper {
    private FileAssetHelper() {
    }

    public static File copyUriToFolder(Context context, Uri uri, String folderName, String prefix) throws IOException {
        Context appContext = context.getApplicationContext();
        File folder = new File(appContext.getFilesDir(), folderName);
        if (!folder.exists() && !folder.mkdirs()) {
            throw new IOException("无法创建目录: " + folder.getAbsolutePath());
        }

        String extension = extensionOf(resolveDisplayName(appContext, uri));
        File destination = new File(folder, safePrefix(prefix) + "_" + UUID.randomUUID() + extension);
        try (InputStream inputStream = appContext.getContentResolver().openInputStream(uri);
             FileOutputStream outputStream = new FileOutputStream(destination)) {
            if (inputStream == null) {
                throw new IOException("无法读取文件内容");
            }
            byte[] buffer = new byte[8192];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
        }
        return destination;
    }

    public static String resolveDisplayName(Context context, Uri uri) {
        ContentResolver resolver = context.getApplicationContext().getContentResolver();
        try (Cursor cursor = resolver.query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String value = cursor.getString(index);
                    if (value != null && !value.isBlank()) {
                        return value;
                    }
                }
            }
        } catch (Exception ignore) {
        }
        String fallback = uri.getLastPathSegment();
        return fallback == null || fallback.isBlank() ? "asset.bin" : fallback;
    }

    public static void deleteIfExists(String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        File file = new File(path);
        if (file.exists()) {
            file.delete();
        }
    }

    private static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0) {
            return "";
        }
        return fileName.substring(dotIndex).toLowerCase(Locale.ROOT);
    }

    private static String safePrefix(String prefix) {
        String value = prefix == null ? "asset" : prefix.trim().toLowerCase(Locale.ROOT);
        value = value.replaceAll("[^a-z0-9_-]+", "_");
        return value.isEmpty() ? "asset" : value;
    }
}
