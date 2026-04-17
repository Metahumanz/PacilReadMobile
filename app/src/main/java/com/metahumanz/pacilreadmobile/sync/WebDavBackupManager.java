package com.metahumanz.pacilread.sync;

import android.content.Context;

import com.metahumanz.pacilread.model.BookRecord;
import com.metahumanz.pacilread.storage.ReaderDatabaseHelper;
import com.metahumanz.pacilread.storage.SettingsStore;

import org.json.JSONObject;

import java.io.File;
import java.text.DateFormat;
import java.util.List;
import java.util.Locale;

public class WebDavBackupManager {
    private final Context context;
    private final ReaderDatabaseHelper databaseHelper;
    private final SettingsStore settingsStore;
    private final WebDavClient webDavClient;

    public WebDavBackupManager(Context context, ReaderDatabaseHelper databaseHelper, SettingsStore settingsStore, WebDavClient webDavClient) {
        this.context = context.getApplicationContext();
        this.databaseHelper = databaseHelper;
        this.settingsStore = settingsStore;
        this.webDavClient = webDavClient;
    }

    public void fullBackup(StatusListener listener) throws Exception {
        listener.onStatus("创建云端目录...");
        webDavClient.ensureBackupDirectories();

        File tempDir = new File(context.getCacheDir(), "backup");
        if (!tempDir.exists()) {
            tempDir.mkdirs();
        }
        File fullDb = new File(tempDir, "reader_full.db");

        listener.onStatus("导出完整数据库...");
        databaseHelper.exportDatabase(fullDb);
        listener.onStatus("上传数据库快照...");
        webDavClient.uploadFile(fullDb, webDavClient.backupBaseUrl() + "reader.db");

        listener.onStatus("上传设置...");
        webDavClient.uploadText(webDavClient.backupBaseUrl() + "settings.json", settingsStore.exportAsJson().toString(2), "application/json; charset=utf-8");

        uploadLocalAssets(listener, false);
        settingsStore.setWebDavLastFullBackupAt(System.currentTimeMillis());
    }

    public void incrementalBackup(StatusListener listener) throws Exception {
        listener.onStatus("创建云端目录...");
        webDavClient.ensureBackupDirectories();

        File tempDir = new File(context.getCacheDir(), "backup");
        if (!tempDir.exists()) {
            tempDir.mkdirs();
        }
        File liteDb = new File(tempDir, "reader_lite.db");

        listener.onStatus("导出精简数据库...");
        databaseHelper.exportLiteDatabase(liteDb);
        listener.onStatus("上传精简数据库...");
        webDavClient.uploadFile(liteDb, webDavClient.backupBaseUrl() + "reader_lite.db");

        listener.onStatus("上传设置...");
        webDavClient.uploadText(webDavClient.backupBaseUrl() + "settings.json", settingsStore.exportAsJson().toString(2), "application/json; charset=utf-8");

        uploadLocalAssets(listener, true);
        settingsStore.setWebDavLastLiteBackupAt(System.currentTimeMillis());
    }

    public void fullRestore(StatusListener listener) throws Exception {
        File tempDir = new File(context.getCacheDir(), "backup_restore");
        if (!tempDir.exists()) {
            tempDir.mkdirs();
        }
        File fullDb = new File(tempDir, "reader_full_restore.db");

        listener.onStatus("下载完整数据库...");
        webDavClient.downloadBinaryFile(webDavClient.backupBaseUrl() + "reader.db", fullDb);

        listener.onStatus("应用数据库...");
        databaseHelper.importDatabase(fullDb);
        databaseHelper.rebaseLocalAssetPaths();

        listener.onStatus("恢复设置...");
        restoreSettingsJson();

        listener.onStatus("下载书籍与封面资源...");
        downloadAssetsForCurrentBooks(listener);
        settingsStore.setWebDavLastFullBackupAt(System.currentTimeMillis());
    }

    public void incrementalRestore(StatusListener listener) throws Exception {
        File tempDir = new File(context.getCacheDir(), "backup_restore");
        if (!tempDir.exists()) {
            tempDir.mkdirs();
        }
        File liteDb = new File(tempDir, "reader_lite_restore.db");

        listener.onStatus("下载精简数据库...");
        webDavClient.downloadBinaryFile(webDavClient.backupBaseUrl() + "reader_lite.db", liteDb);

        listener.onStatus("合并基础数据...");
        databaseHelper.mergeLiteDatabase(liteDb);

        listener.onStatus("恢复设置...");
        restoreSettingsJson();

        listener.onStatus("下载缺失的书籍与封面...");
        downloadAssetsForCurrentBooks(listener);
        settingsStore.setWebDavLastLiteBackupAt(System.currentTimeMillis());
    }

    public String lastFullBackupLabel() {
        long value = settingsStore.getWebDavLastFullBackupAt();
        return value <= 0 ? "尚未备份" : DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.SIMPLIFIED_CHINESE).format(value);
    }

    public String lastLiteBackupLabel() {
        long value = settingsStore.getWebDavLastLiteBackupAt();
        return value <= 0 ? "尚未备份" : DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.SIMPLIFIED_CHINESE).format(value);
    }

    private void restoreSettingsJson() throws Exception {
        String json = webDavClient.downloadText(webDavClient.backupBaseUrl() + "settings.json");
        settingsStore.importFromJson(new JSONObject(json));
    }

    private void uploadLocalAssets(StatusListener listener, boolean skipRemoteExisting) throws Exception {
        List<BookRecord> books = databaseHelper.getBooks();
        int total = Math.max(books.size(), 1);
        for (int i = 0; i < books.size(); i++) {
            BookRecord book = books.get(i);
            if (book.localPath != null && !book.localPath.isBlank()) {
                File localFile = new File(book.localPath);
                if (localFile.exists()) {
                    String remotePath = webDavClient.backupBaseUrl() + "books/" + localFile.getName();
                    if (!skipRemoteExisting || webDavClient.head(remotePath).code != 200) {
                        listener.onStatus("上传书籍 " + (i + 1) + "/" + total + "...");
                        webDavClient.uploadFile(localFile, remotePath);
                    }
                }
            }
            if (book.coverPath != null && !book.coverPath.isBlank()) {
                File coverFile = new File(book.coverPath);
                if (coverFile.exists()) {
                    String remotePath = webDavClient.backupBaseUrl() + "covers/" + coverFile.getName();
                    if (!skipRemoteExisting || webDavClient.head(remotePath).code != 200) {
                        listener.onStatus("上传封面 " + (i + 1) + "/" + total + "...");
                        webDavClient.uploadFile(coverFile, remotePath);
                    }
                }
            }
        }

        String backgroundPath = settingsStore.getReaderBackgroundPath();
        if (!backgroundPath.isBlank()) {
            File backgroundFile = new File(backgroundPath);
            if (backgroundFile.exists()) {
                String remotePath = webDavClient.backupBaseUrl() + "backgrounds/" + backgroundFile.getName();
                if (!skipRemoteExisting || webDavClient.head(remotePath).code != 200) {
                    listener.onStatus("上传背景图片...");
                    webDavClient.uploadFile(backgroundFile, remotePath);
                }
            }
        }
    }

    private void downloadAssetsForCurrentBooks(StatusListener listener) throws Exception {
        List<BookRecord> books = databaseHelper.getBooks();
        int total = Math.max(books.size(), 1);
        for (int i = 0; i < books.size(); i++) {
            BookRecord book = books.get(i);
            if (book.localPath != null && !book.localPath.isBlank()) {
                File localFile = new File(book.localPath);
                if (!localFile.exists()) {
                    listener.onStatus("下载书籍 " + (i + 1) + "/" + total + "...");
                    webDavClient.downloadBinaryFile(webDavClient.backupBaseUrl() + "books/" + localFile.getName(), localFile);
                }
            }
            if (book.coverPath != null && !book.coverPath.isBlank()) {
                File coverFile = new File(book.coverPath);
                if (!coverFile.exists()) {
                    listener.onStatus("下载封面 " + (i + 1) + "/" + total + "...");
                    webDavClient.downloadBinaryFile(webDavClient.backupBaseUrl() + "covers/" + coverFile.getName(), coverFile);
                }
            }
        }

        String backgroundPath = settingsStore.getReaderBackgroundPath();
        if (!backgroundPath.isBlank()) {
            File backgroundFile = new File(backgroundPath);
            if (!backgroundFile.exists()) {
                listener.onStatus("下载背景图片...");
                webDavClient.downloadBinaryFile(webDavClient.backupBaseUrl() + "backgrounds/" + backgroundFile.getName(), backgroundFile);
            }
        }
    }

    public interface StatusListener {
        void onStatus(String status);
    }
}
