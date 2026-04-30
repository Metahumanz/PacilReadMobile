package com.metahumanz.pacilread.sync;

import android.content.Context;

import android.util.Log;

import com.metahumanz.pacilread.model.BookRecord;
import com.metahumanz.pacilread.model.ChapterRecord;
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
        ensureAnySyncScopeSelected();
        listener.onStatus("创建云端目录...");
        webDavClient.ensureBackupDirectories();
        webDavClient.ensureChapterTextDirectory();

        if (shouldSyncDatabaseSnapshot()) {
            File tempDir = new File(context.getCacheDir(), "backup");
            if (!tempDir.exists()) {
                tempDir.mkdirs();
            }
            File fullDb = new File(tempDir, "reader_full.db");

            listener.onStatus("导出数据库...");
            databaseHelper.exportDatabase(fullDb);
            listener.onStatus("上传数据库...");
            webDavClient.uploadFile(fullDb, webDavClient.backupBaseUrl() + "reader.db");
        }
        if (shouldSyncSettingsSnapshot()) {
            uploadSettingsSnapshot(listener);
        }

        uploadLocalAssets(listener, false, settingsStore.isWebDavSyncFilesEnabled(), settingsStore.isWebDavSyncBackgroundsEnabled());
        settingsStore.setWebDavLastFullBackupAt(System.currentTimeMillis());
    }

    public void incrementalBackup(StatusListener listener) throws Exception {
        ensureAnySyncScopeSelected();
        listener.onStatus("创建云端目录...");
        webDavClient.ensureBackupDirectories();

        if (shouldSyncDatabaseSnapshot()) {
            File tempDir = new File(context.getCacheDir(), "backup");
            if (!tempDir.exists()) {
                tempDir.mkdirs();
            }
            File liteDb = new File(tempDir, "reader_lite.db");

            listener.onStatus("处理增量数据库...");
            databaseHelper.exportLiteDatabase(liteDb);
            listener.onStatus("上传增量数据库...");
            webDavClient.uploadFile(liteDb, webDavClient.backupBaseUrl() + "reader_lite.db");
        }
        if (shouldSyncSettingsSnapshot()) {
            uploadSettingsSnapshot(listener);
        }

        uploadLocalAssets(listener, true, settingsStore.isWebDavSyncFilesEnabled(), settingsStore.isWebDavSyncBackgroundsEnabled());
        settingsStore.setWebDavLastLiteBackupAt(System.currentTimeMillis());
    }

    public void fullRestore(StatusListener listener) throws Exception {
        ensureRestoreScopeSelected();

        if (shouldSyncDatabaseSnapshot()) {
            File tempDir = new File(context.getCacheDir(), "backup_restore");
            if (!tempDir.exists()) {
                tempDir.mkdirs();
            }
            File fullDb = new File(tempDir, "reader_full_restore.db");

            listener.onStatus("下载完整数据库...");
            webDavClient.downloadBinaryFile(webDavClient.backupBaseUrl() + "reader.db", fullDb);

            listener.onStatus("应用数据库...");
            databaseHelper.stripPlatformSettingsTable(fullDb);
            databaseHelper.importDatabase(fullDb);
            databaseHelper.rebaseLocalAssetPaths();

            listener.onStatus("恢复章节正文...");
            restoreChapterTextFiles(listener);
        }

        restoreSettingsJsonIfPresent(listener);
    }

    public void incrementalRestore(StatusListener listener) throws Exception {
        ensureRestoreScopeSelected();

        if (shouldSyncDatabaseSnapshot()) {
            File tempDir = new File(context.getCacheDir(), "backup_restore");
            if (!tempDir.exists()) {
                tempDir.mkdirs();
            }
            File liteDb = new File(tempDir, "reader_lite_restore.db");

            listener.onStatus("下载精简数据库...");
            webDavClient.downloadBinaryFile(webDavClient.backupBaseUrl() + "reader_lite.db", liteDb);

            listener.onStatus("合并基础数据...");
            databaseHelper.stripPlatformSettingsTable(liteDb);
            databaseHelper.mergeLiteDatabase(liteDb);
        }

        restoreSettingsJsonIfPresent(listener);
    }

    public String lastFullBackupLabel() {
        long value = settingsStore.getWebDavLastFullBackupAt();
        return value <= 0 ? "尚未备份" : DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.SIMPLIFIED_CHINESE).format(value);
    }

    public String lastLiteBackupLabel() {
        long value = settingsStore.getWebDavLastLiteBackupAt();
        return value <= 0 ? "尚未备份" : DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.SIMPLIFIED_CHINESE).format(value);
    }

    private void uploadSettingsSnapshot(StatusListener listener) throws Exception {
        listener.onStatus("上传 Android 设置...");
        webDavClient.uploadText(
                webDavClient.androidSettingsSnapshotUrl(),
                settingsStore.exportAndroidPrivateSettingsJson().toString(2),
                "application/json; charset=utf-8"
        );
    }

    private void restoreSettingsJsonIfPresent(StatusListener listener) throws Exception {
        if (!shouldSyncSettingsSnapshot()) {
            return;
        }
        String remotePath = webDavClient.androidSettingsSnapshotUrl();
        if (webDavClient.head(remotePath).code != 200) {
            return;
        }
        listener.onStatus("恢复 Android 设置...");
        String json = webDavClient.downloadText(remotePath);
        JSONObject settingsJson = new JSONObject(json);
        String restoredBackgroundPath = restoreBackgroundIfPresent(listener, settingsJson);
        settingsStore.importAndroidPrivateSettingsJson(settingsJson, restoredBackgroundPath);
    }

    private String restoreBackgroundIfPresent(StatusListener listener, JSONObject settingsJson) {
        String backgroundFileName = settingsStore.androidSettingsBackgroundFileName(settingsJson);
        if (backgroundFileName.isBlank()) {
            return null;
        }
        try {
            String remotePath = webDavClient.androidSettingsBackgroundsBaseUrl() + backgroundFileName;
            if (webDavClient.head(remotePath).code != 200) {
                return null;
            }
            File folder = new File(context.getFilesDir(), "backgrounds");
            if (!folder.exists() && !folder.mkdirs()) {
                return null;
            }
            File destination = new File(folder, backgroundFileName);
            listener.onStatus("恢复背景图片...");
            webDavClient.downloadBinaryFile(remotePath, destination);
            return destination.getAbsolutePath();
        } catch (Exception ignored) {
            return null;
        }
    }

    private void uploadLocalAssets(StatusListener listener, boolean skipRemoteExisting, boolean includeFiles, boolean includeBackgrounds) throws Exception {
        if (includeFiles) {
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
                uploadChapterTextFiles(book.id, skipRemoteExisting, listener);
            }
        }

        if (!includeBackgrounds) {
            return;
        }
        String backgroundPath = settingsStore.getReaderBackgroundPath();
        if (!backgroundPath.isBlank()) {
            File backgroundFile = new File(backgroundPath);
            if (backgroundFile.exists()) {
                String remotePath = webDavClient.androidSettingsBackgroundsBaseUrl() + backgroundFile.getName();
                if (!skipRemoteExisting || webDavClient.head(remotePath).code != 200) {
                    listener.onStatus("上传背景图片...");
                    webDavClient.uploadFile(backgroundFile, remotePath);
                }
            }
        }
    }

    private void uploadChapterTextFiles(long bookId, boolean skipRemoteExisting, StatusListener listener) throws Exception {
        List<ChapterRecord> chapters = databaseHelper.getChaptersWithExternalStorage(bookId);
        for (ChapterRecord chapter : chapters) {
            if (chapter.bodyTextPath == null || chapter.bodyTextPath.isBlank()) {
                continue;
            }
            File localFile = databaseHelper.resolveChapterTextFile(chapter.bodyTextPath);
            if (localFile == null || !localFile.exists()) {
                continue;
            }
            String remotePath = webDavClient.backupBaseUrl() + "chapter_text/" + chapter.bodyTextPath;
            if (!skipRemoteExisting || webDavClient.head(remotePath).code != 200) {
                webDavClient.uploadFile(localFile, remotePath);
            }
        }
    }

    private void restoreChapterTextFiles(StatusListener listener) {
        List<BookRecord> books = databaseHelper.getBooks();
        int total = Math.max(books.size(), 1);
        for (int i = 0; i < books.size(); i++) {
            BookRecord book = books.get(i);
            List<ChapterRecord> chapters = databaseHelper.getChaptersWithExternalStorage(book.id);
            for (ChapterRecord chapter : chapters) {
                if (chapter.bodyTextPath == null || chapter.bodyTextPath.isBlank()) {
                    continue;
                }
                try {
                    String remotePath = webDavClient.backupBaseUrl() + "chapter_text/" + chapter.bodyTextPath;
                    if (webDavClient.head(remotePath).code != 200) {
                        Log.w("WebDavBackup", "章节正文缺失 chapter " + chapter.id + ": " + chapter.bodyTextPath);
                        continue;
                    }
                    listener.onStatus("恢复章节正文 " + (i + 1) + "/" + total + "...");
                    File localFile = databaseHelper.resolveChapterTextFile(chapter.bodyTextPath);
                    if (localFile != null) {
                        File parent = localFile.getParentFile();
                        if (parent != null && !parent.exists()) {
                            parent.mkdirs();
                        }
                        webDavClient.downloadBinaryFile(remotePath, localFile);
                    }
                } catch (Exception e) {
                    Log.w("WebDavBackup", "恢复章节正文失败 chapter " + chapter.id, e);
                }
            }
        }
    }

    private void ensureAnySyncScopeSelected() {
        if (shouldSyncDatabaseSnapshot()
                || shouldSyncSettingsSnapshot()
                || settingsStore.isWebDavSyncFilesEnabled()
                || settingsStore.isWebDavSyncBackgroundsEnabled()) {
            return;
        }
        throw new IllegalStateException("请先选择至少一种同步内容");
    }

    private void ensureRestoreScopeSelected() {
        if (shouldSyncDatabaseSnapshot() || shouldSyncSettingsSnapshot()) {
            return;
        }
        throw new IllegalStateException("当前未包含书架、界面、主题或背景快照，无法执行恢复");
    }

    private boolean shouldSyncDatabaseSnapshot() {
        return settingsStore.isWebDavSyncBookshelfEnabled()
                || settingsStore.isWebDavSyncThemesEnabled();
    }

    private boolean shouldSyncSettingsSnapshot() {
        return settingsStore.isWebDavSyncUiSettingsEnabled()
                || settingsStore.isWebDavSyncThemesEnabled()
                || settingsStore.isWebDavSyncBackgroundsEnabled();
    }

    public interface StatusListener {
        void onStatus(String status);
    }
}
