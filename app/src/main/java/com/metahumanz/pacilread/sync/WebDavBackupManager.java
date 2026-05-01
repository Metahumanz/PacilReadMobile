package com.metahumanz.pacilread.sync;

import android.content.Context;

import android.util.Log;

import com.metahumanz.pacilread.model.BookRecord;
import com.metahumanz.pacilread.model.ChapterRecord;
import com.metahumanz.pacilread.storage.ReaderDatabaseHelper;
import com.metahumanz.pacilread.storage.SettingsStore;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

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
        boolean includeChapterText = settingsStore.isWebDavSyncBookshelfEnabled();
        boolean includeFiles = settingsStore.isWebDavSyncFilesEnabled();
        boolean includeBackgrounds = settingsStore.isWebDavSyncBackgroundsEnabled();
        listener.onStatus("创建云端目录...");
        webDavClient.ensureBackupRootDirectory();
        if (includeFiles) {
            webDavClient.ensureBookAssetDirectories();
        }
        if (shouldSyncSettingsSnapshot()) {
            webDavClient.ensureAndroidSettingsDirectory();
        }
        if (includeChapterText) {
            webDavClient.ensureChapterTextDirectory();
        }

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

        uploadLocalAssets(listener, false, includeChapterText, includeFiles, includeBackgrounds);
        cleanupRemoteUnreferencedAssetsIfEnabled(listener, includeChapterText, includeFiles, includeBackgrounds);
        settingsStore.setWebDavLastFullBackupAt(System.currentTimeMillis());
    }

    public void incrementalBackup(StatusListener listener) throws Exception {
        ensureAnySyncScopeSelected();
        boolean includeChapterText = settingsStore.isWebDavSyncBookshelfEnabled();
        boolean includeFiles = settingsStore.isWebDavSyncFilesEnabled();
        boolean includeBackgrounds = settingsStore.isWebDavSyncBackgroundsEnabled();
        listener.onStatus("创建云端目录...");
        webDavClient.ensureBackupRootDirectory();
        if (includeFiles) {
            webDavClient.ensureBookAssetDirectories();
        }
        if (shouldSyncSettingsSnapshot()) {
            webDavClient.ensureAndroidSettingsDirectory();
        }
        if (includeChapterText) {
            webDavClient.ensureChapterTextDirectory();
        }

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

        uploadLocalAssets(listener, true, includeChapterText, includeFiles, includeBackgrounds);
        cleanupRemoteUnreferencedAssetsIfEnabled(listener, includeChapterText, includeFiles, includeBackgrounds);
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
            if (webDavClient.head(webDavClient.backupBaseUrl() + "reader.db").code == 404) {
                throw new IllegalStateException("云端没有全量备份 reader.db，请先执行全量备份");
            }
            webDavClient.downloadBinaryFile(webDavClient.backupBaseUrl() + "reader.db", fullDb);

            listener.onStatus("应用数据库...");
            databaseHelper.stripPlatformSettingsTable(fullDb);
            databaseHelper.importDatabase(fullDb);
            databaseHelper.rebaseLocalAssetPaths();

            restoreBookAssetFiles(listener, settingsStore.isWebDavSyncFilesEnabled());
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
            if (webDavClient.head(webDavClient.backupBaseUrl() + "reader_lite.db").code == 404) {
                throw new IllegalStateException("云端没有增量备份 reader_lite.db，请先执行增量备份或改用全量恢复");
            }
            webDavClient.downloadBinaryFile(webDavClient.backupBaseUrl() + "reader_lite.db", liteDb);

            listener.onStatus("合并基础数据...");
            databaseHelper.stripPlatformSettingsTable(liteDb);
            databaseHelper.mergeLiteDatabase(liteDb);
            restoreBookAssetFiles(listener, settingsStore.isWebDavSyncFilesEnabled());
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
        listener.onStatus("Android 设置已上传");
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

    private void restoreBookAssetFiles(StatusListener listener, boolean includeSourceFiles) {
        List<BookRecord> books = databaseHelper.getBooks();
        int total = Math.max(books.size(), 1);
        for (int i = 0; i < books.size(); i++) {
            BookRecord book = books.get(i);
            if (book.coverPath != null && !book.coverPath.isBlank()) {
                File coverFile = new File(book.coverPath);
                String remotePath = webDavClient.backupBaseUrl() + "covers/" + coverFile.getName();
                restoreRemoteFileIfPresent(remotePath, coverFile, "恢复封面 " + (i + 1) + "/" + total + "...", listener);
            }
            if (includeSourceFiles && book.localPath != null && !book.localPath.isBlank()) {
                File sourceFile = new File(book.localPath);
                String remotePath = webDavClient.backupBaseUrl() + "books/" + sourceFile.getName();
                restoreRemoteFileIfPresent(remotePath, sourceFile, "恢复书籍源文件 " + (i + 1) + "/" + total + "...", listener);
            }
        }
    }

    private void restoreRemoteFileIfPresent(String remotePath, File destination, String status, StatusListener listener) {
        try {
            WebDavClient.Response head = webDavClient.head(remotePath);
            if (head.code == 404) {
                return;
            }
            if (head.code < 200 || head.code >= 300) {
                return;
            }
            long remoteLength = -1L;
            try {
                remoteLength = webDavClient.remoteContentLength(remotePath);
            } catch (Exception ignored) {
            }
            if (destination.exists() && remoteLength >= 0 && destination.length() == remoteLength) {
                return;
            }
            File parent = destination.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            listener.onStatus(status);
            webDavClient.downloadBinaryFile(remotePath, destination);
        } catch (Exception error) {
            Log.w("WebDavBackup", "恢复资源文件失败: " + remotePath, error);
        }
    }

    private void uploadLocalAssets(StatusListener listener, boolean skipRemoteExisting, boolean includeChapterText, boolean includeFiles, boolean includeBackgrounds) throws Exception {
        List<BookRecord> books = includeChapterText || includeFiles ? databaseHelper.getBooks() : new ArrayList<>();
        if (includeChapterText) {
            uploadChapterTextArchives(books, skipRemoteExisting, listener);
        }

        if (includeFiles) {
            uploadCoverFiles(books, skipRemoteExisting, listener);
            uploadSourceFiles(books, skipRemoteExisting, listener);
        }

        if (!includeBackgrounds) {
            return;
        }
        String backgroundPath = settingsStore.getReaderBackgroundPath();
        if (!backgroundPath.isBlank()) {
            File backgroundFile = new File(backgroundPath);
            if (backgroundFile.exists()) {
                String remotePath = webDavClient.androidSettingsBackgroundsBaseUrl() + backgroundFile.getName();
                if (shouldUploadAsset(remotePath, backgroundFile, skipRemoteExisting)) {
                    listener.onStatus("上传背景图片 " + formatFileSize(backgroundFile.length()) + "...");
                    webDavClient.uploadFile(backgroundFile, remotePath);
                }
            }
        }
    }

    private void restoreChapterTextFiles(StatusListener listener) {
        List<BookRecord> books = databaseHelper.getBooks();
        int total = Math.max(books.size(), 1);
        for (int i = 0; i < books.size(); i++) {
            BookRecord book = books.get(i);
            if (restoreBookChapterTextArchiveIfPresent(book, i + 1, total, listener)) {
                continue;
            }
            restoreChapterTextFilesForBook(book, i + 1, total, listener);
        }
    }

    private void uploadChapterTextArchives(List<BookRecord> books, boolean skipRemoteExisting, StatusListener listener) throws Exception {
        int total = Math.max(books.size(), 1);
        int uploaded = 0;
        int skipped = 0;
        int booksWithText = 0;
        File tempDir = new File(context.getCacheDir(), "backup");
        if (!tempDir.exists() && !tempDir.mkdirs()) {
            throw new IllegalStateException("无法创建备份缓存目录");
        }
        for (int i = 0; i < books.size(); i++) {
            BookRecord book = books.get(i);
            List<ChapterTextFile> files = collectChapterTextFiles(book);
            if (files.isEmpty()) {
                continue;
            }
            booksWithText++;
            File archive = new File(tempDir, chapterTextArchiveFileName(book.id));
            listener.onStatus("打包章节正文 " + (i + 1) + "/" + total + " · 0/" + files.size() + "...");
            writeChapterTextArchive(archive, files, i + 1, total, listener);
            String remotePath = chapterTextArchiveRemotePath(book.id);
            if (shouldUploadChapterTextArchive(remotePath, archive, skipRemoteExisting)) {
                uploaded++;
                listener.onStatus("上传章节正文包 " + (i + 1) + "/" + total + " · " + formatFileSize(archive.length()) + "...");
                webDavClient.uploadFile(archive, remotePath);
            } else {
                skipped++;
            }
        }
        if (booksWithText == 0) {
            listener.onStatus("没有可上传的章节正文文件");
            return;
        }
        if (uploaded == 0 && skipped > 0) {
            listener.onStatus("章节正文包未变化，跳过上传");
        }
    }

    private void writeChapterTextArchive(File archive, List<ChapterTextFile> files, int bookIndex, int totalBooks, StatusListener listener) throws Exception {
        try (ZipOutputStream zipOutputStream = new ZipOutputStream(new FileOutputStream(archive))) {
            byte[] buffer = new byte[8192];
            Set<String> addedPaths = new HashSet<>();
            for (int i = 0; i < files.size(); i++) {
                ChapterTextFile textFile = files.get(i);
                if (!addedPaths.add(textFile.relativePath)) {
                    continue;
                }
                ZipEntry entry = new ZipEntry(textFile.relativePath);
                entry.setTime(0L);
                zipOutputStream.putNextEntry(entry);
                try (FileInputStream inputStream = new FileInputStream(textFile.file)) {
                    int read;
                    while ((read = inputStream.read(buffer)) != -1) {
                        zipOutputStream.write(buffer, 0, read);
                    }
                }
                zipOutputStream.closeEntry();
                int done = i + 1;
                if (done == files.size() || done % 200 == 0) {
                    listener.onStatus("打包章节正文 " + bookIndex + "/" + totalBooks + " · " + done + "/" + files.size() + "...");
                }
            }
        }
    }

    private void uploadCoverFiles(List<BookRecord> books, boolean skipRemoteExisting, StatusListener listener) throws Exception {
        int total = Math.max(books.size(), 1);
        for (int i = 0; i < books.size(); i++) {
            BookRecord book = books.get(i);
            if (book.coverPath == null || book.coverPath.isBlank()) {
                continue;
            }
            File coverFile = new File(book.coverPath);
            if (!coverFile.exists() || !coverFile.isFile()) {
                continue;
            }
            String remotePath = webDavClient.backupBaseUrl() + "covers/" + coverFile.getName();
            if (shouldUploadAsset(remotePath, coverFile, skipRemoteExisting)) {
                listener.onStatus("上传封面 " + (i + 1) + "/" + total + " · " + formatFileSize(coverFile.length()) + "...");
                webDavClient.uploadFile(coverFile, remotePath);
            }
        }
    }

    private void uploadSourceFiles(List<BookRecord> books, boolean skipRemoteExisting, StatusListener listener) throws Exception {
        int total = Math.max(books.size(), 1);
        for (int i = 0; i < books.size(); i++) {
            BookRecord book = books.get(i);
            if (book.localPath == null || book.localPath.isBlank()) {
                continue;
            }
            File localFile = new File(book.localPath);
            if (!localFile.exists() || !localFile.isFile()) {
                continue;
            }
            String remotePath = webDavClient.backupBaseUrl() + "books/" + localFile.getName();
            if (shouldUploadAsset(remotePath, localFile, skipRemoteExisting)) {
                listener.onStatus("上传书籍源文件 " + (i + 1) + "/" + total + " · " + formatFileSize(localFile.length()) + "...");
                webDavClient.uploadFile(localFile, remotePath);
            }
        }
    }

    private List<ChapterTextFile> collectChapterTextFiles(BookRecord book) {
        List<ChapterTextFile> files = new ArrayList<>();
        List<ChapterRecord> chapters = databaseHelper.getChaptersWithExternalStorage(book.id);
        for (ChapterRecord chapter : chapters) {
            if (chapter.bodyTextPath == null || chapter.bodyTextPath.isBlank()) {
                continue;
            }
            String entryName = sanitizeChapterTextArchiveEntryName(chapter.bodyTextPath);
            if (entryName == null) {
                continue;
            }
            File localFile = databaseHelper.resolveChapterTextFile(entryName);
            if (localFile == null || !localFile.exists() || !localFile.isFile()) {
                continue;
            }
            files.add(new ChapterTextFile(entryName, localFile));
        }
        return files;
    }

    private boolean restoreBookChapterTextArchiveIfPresent(BookRecord book, int bookIndex, int totalBooks, StatusListener listener) {
        String remotePath = chapterTextArchiveRemotePath(book.id);
        try {
            if (webDavClient.head(remotePath).code != 200) {
                return false;
            }
            File tempDir = new File(context.getCacheDir(), "backup_restore");
            if (!tempDir.exists() && !tempDir.mkdirs()) {
                return false;
            }
            File archive = new File(tempDir, chapterTextArchiveFileName(book.id));
            listener.onStatus("下载章节正文包 " + bookIndex + "/" + totalBooks + "...");
            webDavClient.downloadBinaryFile(remotePath, archive);
            listener.onStatus("解包章节正文 " + bookIndex + "/" + totalBooks + "...");
            int restored = extractChapterTextArchive(archive);
            listener.onStatus("章节正文包已恢复 " + bookIndex + "/" + totalBooks + " · " + restored + " 个文件");
            return restored > 0;
        } catch (Exception error) {
            Log.w("WebDavBackup", "恢复章节正文包失败 book " + book.id + "，回退逐文件恢复", error);
            return false;
        }
    }

    private void restoreChapterTextFilesForBook(BookRecord book, int bookIndex, int totalBooks, StatusListener listener) {
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
                listener.onStatus("恢复章节正文 " + bookIndex + "/" + totalBooks + "...");
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

    private int extractChapterTextArchive(File archive) throws Exception {
        File baseDir = databaseHelper.resolveChapterTextFile("__base__").getParentFile();
        if (baseDir == null) {
            throw new IllegalStateException("无法定位章节正文目录");
        }
        if (!baseDir.exists() && !baseDir.mkdirs()) {
            throw new IllegalStateException("无法创建章节正文目录: " + baseDir.getAbsolutePath());
        }
        String basePath = baseDir.getCanonicalPath() + File.separator;
        byte[] buffer = new byte[8192];
        int restored = 0;
        try (ZipInputStream zipInputStream = new ZipInputStream(new FileInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    zipInputStream.closeEntry();
                    continue;
                }
                String entryName = sanitizeChapterTextArchiveEntryName(entry.getName());
                if (entryName == null) {
                    zipInputStream.closeEntry();
                    continue;
                }
                File destination = databaseHelper.resolveChapterTextFile(entryName);
                if (destination == null) {
                    zipInputStream.closeEntry();
                    continue;
                }
                String destinationPath = destination.getCanonicalPath();
                if (!destinationPath.startsWith(basePath)) {
                    zipInputStream.closeEntry();
                    continue;
                }
                File parent = destination.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    zipInputStream.closeEntry();
                    continue;
                }
                try (FileOutputStream outputStream = new FileOutputStream(destination)) {
                    int read;
                    while ((read = zipInputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, read);
                    }
                }
                restored++;
                zipInputStream.closeEntry();
            }
        }
        return restored;
    }

    private boolean shouldUploadAsset(String remotePath, File localFile, boolean skipRemoteExisting) throws Exception {
        if (skipRemoteExisting) {
            try {
                return webDavClient.head(remotePath).code != 200;
            } catch (Exception ignored) {
                return true;
            }
        }
        try {
            return webDavClient.remoteContentLength(remotePath) != localFile.length();
        } catch (Exception ignored) {
            return true;
        }
    }

    private boolean shouldUploadChapterTextArchive(String remotePath, File archive, boolean skipRemoteExisting) {
        if (!skipRemoteExisting) {
            return true;
        }
        try {
            return webDavClient.remoteContentLength(remotePath) != archive.length();
        } catch (Exception ignored) {
            return true;
        }
    }

    private String chapterTextArchiveRemotePath(long bookId) {
        return webDavClient.backupBaseUrl() + "chapter_text/" + chapterTextArchiveFileName(bookId);
    }

    private String chapterTextArchiveFileName(long bookId) {
        return "book_" + bookId + ".zip";
    }

    private void cleanupRemoteUnreferencedAssetsIfEnabled(StatusListener listener, boolean includeChapterText, boolean includeFiles, boolean includeBackgrounds) {
        if (!settingsStore.isWebDavCleanRemoteOrphansEnabled()) {
            return;
        }
        List<BookRecord> books = databaseHelper.getBooks();
        RemoteCleanupResult result = new RemoteCleanupResult();
        listener.onStatus("清理远端未引用文件...");

        if (includeChapterText) {
            cleanupRemoteChapterText(books, result);
        }
        if (includeFiles) {
            cleanupRemotePlainFileDirectory(webDavClient.backupBaseUrl() + "books/", collectSourceFileNames(books), result);
            cleanupRemotePlainFileDirectory(webDavClient.backupBaseUrl() + "covers/", collectCoverFileNames(books), result);
        }
        if (includeBackgrounds) {
            Set<String> keepBackgrounds = new HashSet<>();
            String backgroundFileName = settingsStore.readerBackgroundFileName();
            if (!backgroundFileName.isBlank()) {
                keepBackgrounds.add(backgroundFileName);
            }
            cleanupRemotePlainFileDirectory(webDavClient.androidSettingsBackgroundsBaseUrl(), keepBackgrounds, result);
        }

        String summary = "远端清理完成，删除 " + result.deleted + " 个";
        if (result.failed > 0) {
            summary += "，失败 " + result.failed + " 个";
        }
        listener.onStatus(summary);
    }

    private void cleanupRemoteChapterText(List<BookRecord> books, RemoteCleanupResult result) {
        Set<String> keepArchiveNames = new HashSet<>();
        Set<String> keepLegacyDirectoryNames = new HashSet<>();
        for (BookRecord book : books) {
            keepLegacyDirectoryNames.add("book_" + book.id);
            if (!collectChapterTextFiles(book).isEmpty()) {
                keepArchiveNames.add(chapterTextArchiveFileName(book.id));
            }
        }

        cleanupKnownRemoteFile(webDavClient.backupBaseUrl() + "chapter_text.zip", result);
        cleanupRemoteDirectory(
                webDavClient.backupBaseUrl() + "chapter_text/",
                result,
                (remoteUrl, name, directory) -> {
                    if (!directory && "chapter_text.zip".equals(name)) {
                        return true;
                    }
                    if (!directory && name.matches("book_\\d+\\.zip")) {
                        return !keepArchiveNames.contains(name);
                    }
                    if (directory && name.matches("book_\\d+")) {
                        return !keepLegacyDirectoryNames.contains(name);
                    }
                    return false;
                }
        );
    }

    private void cleanupRemotePlainFileDirectory(String remoteDirectoryUrl, Set<String> keepNames, RemoteCleanupResult result) {
        cleanupRemoteDirectory(
                remoteDirectoryUrl,
                result,
                (remoteUrl, name, directory) -> !directory && !keepNames.contains(name)
        );
    }

    private void cleanupRemoteDirectory(String remoteDirectoryUrl, RemoteCleanupResult result, RemoteCleanupPredicate predicate) {
        List<String> remoteFiles;
        try {
            remoteFiles = webDavClient.listFiles(remoteDirectoryUrl);
        } catch (Exception error) {
            result.failed++;
            Log.w("WebDavBackup", "列出远端目录失败: " + remoteDirectoryUrl, error);
            return;
        }
        for (String remoteUrl : remoteFiles) {
            String name = remoteFileName(remoteUrl);
            if (name == null || name.isBlank()) {
                continue;
            }
            boolean directory = remoteUrl.endsWith("/");
            if (!predicate.shouldDelete(remoteUrl, name, directory)) {
                continue;
            }
            deleteRemoteFile(remoteUrl, result);
        }
    }

    private void cleanupKnownRemoteFile(String remoteUrl, RemoteCleanupResult result) {
        try {
            if (webDavClient.head(remoteUrl).code == 200) {
                deleteRemoteFile(remoteUrl, result);
            }
        } catch (Exception ignored) {
        }
    }

    private void deleteRemoteFile(String remoteUrl, RemoteCleanupResult result) {
        try {
            webDavClient.delete(remoteUrl);
            result.deleted++;
        } catch (Exception error) {
            result.failed++;
            Log.w("WebDavBackup", "删除远端残留失败: " + remoteUrl, error);
        }
    }

    private Set<String> collectSourceFileNames(List<BookRecord> books) {
        Set<String> names = new HashSet<>();
        for (BookRecord book : books) {
            if (book.localPath != null && !book.localPath.isBlank()) {
                names.add(new File(book.localPath).getName());
            }
        }
        return names;
    }

    private Set<String> collectCoverFileNames(List<BookRecord> books) {
        Set<String> names = new HashSet<>();
        for (BookRecord book : books) {
            if (book.coverPath != null && !book.coverPath.isBlank()) {
                names.add(new File(book.coverPath).getName());
            }
        }
        return names;
    }

    private String remoteFileName(String remoteUrl) {
        if (remoteUrl == null || remoteUrl.isBlank()) {
            return null;
        }
        String value = remoteUrl;
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        int index = value.lastIndexOf('/');
        String name = index >= 0 ? value.substring(index + 1) : value;
        try {
            return URLDecoder.decode(name, StandardCharsets.UTF_8.name());
        } catch (Exception ignored) {
            return name;
        }
    }

    private String sanitizeChapterTextArchiveEntryName(String value) {
        if (value == null || value.isBlank() || value.startsWith("/") || value.contains("\\") || value.contains(":")) {
            return null;
        }
        for (String segment : value.split("/")) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
                return null;
            }
        }
        return value;
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.US, "%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format(Locale.US, "%.2f MB", mb);
        }
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
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

    private interface RemoteCleanupPredicate {
        boolean shouldDelete(String remoteUrl, String name, boolean directory);
    }

    private static class RemoteCleanupResult {
        int deleted;
        int failed;
    }

    private static class ChapterTextFile {
        final String relativePath;
        final File file;

        ChapterTextFile(String relativePath, File file) {
            this.relativePath = relativePath;
            this.file = file;
        }
    }
}
