package com.metahumanz.pacilread.sync;

import android.content.Context;
import android.util.Log;

import com.metahumanz.pacilread.model.BookRecord;
import com.metahumanz.pacilread.model.BookmarkRecord;
import com.metahumanz.pacilread.model.ChapterRecord;
import com.metahumanz.pacilread.model.ReadingTimeEntryRecord;
import com.metahumanz.pacilread.model.ReaderThemeRecord;
import com.metahumanz.pacilread.model.ReplacementRuleRecord;
import com.metahumanz.pacilread.storage.JsonDatabase;
import com.metahumanz.pacilread.storage.SettingsStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class WebDavBackupManager {
    private static final String TAG = "WebDavBackup";
    private static final String[] SYNC_JSON_FILES = {
            "books.json", "chapters.json", "rules.json", "themes.json", "bookmarks.json"
    };
    private static final String MANIFEST_FILE = "manifest.json";

    private final Context context;
    private final JsonDatabase databaseHelper;
    private final SettingsStore settingsStore;
    private final WebDavClient webDavClient;

    public WebDavBackupManager(Context context, JsonDatabase databaseHelper, SettingsStore settingsStore, WebDavClient webDavClient) {
        this.context = context.getApplicationContext();
        this.databaseHelper = databaseHelper;
        this.settingsStore = settingsStore;
        this.webDavClient = webDavClient;
    }

    // ==================== 全量备份 ====================

    public void fullBackup(StatusListener listener) throws Exception {
        cleanupLocalTempCache();
        ensureAnySyncScopeSelected();
        normalizeReplacementRules(databaseHelper.getRulesMutable());
        databaseHelper.flush();
        boolean includeChapterText = settingsStore.isWebDavSyncBookshelfEnabled();
        boolean includeFiles = settingsStore.isWebDavSyncFilesEnabled();
        boolean includeBackgrounds = settingsStore.isWebDavSyncBackgroundsEnabled();

        listener.onStatus("创建云端目录...");
        webDavClient.ensureBackupRootDirectory();
        webDavClient.ensureDirectory(webDavClient.backupBaseUrl() + "database/");
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
            listener.onStatus("上传 JSON 数据文件...");
            File dataDir = databaseHelper.getDataDir();
            JSONObject manifest = new JSONObject();
            manifest.put("schemaVersion", 1);
            manifest.put("generatedAt", System.currentTimeMillis());
            JSONObject filesEntry = new JSONObject();

            for (String fileName : SYNC_JSON_FILES) {
                File jsonFile = new File(dataDir, fileName);
                if (!jsonFile.exists()) continue;
                String sha256 = computeFileSha256(jsonFile);
                manifestAppendFile(filesEntry, fileName, sha256, jsonFile.length());
                listener.onStatus("上传 " + fileName + "...");
                webDavClient.uploadFile(jsonFile, webDavClient.backupBaseUrl() + "database/" + fileName);
            }

            // 也上传 reading_stats.json（如果在数据库目录中）
            File statsFile = new File(dataDir, "reading_stats.json");
            if (statsFile.exists()) {
                String sha256 = computeFileSha256(statsFile);
                manifestAppendFile(filesEntry, "reading_stats.json", sha256, statsFile.length());
                listener.onStatus("上传 reading_stats.json...");
                webDavClient.uploadFile(statsFile, webDavClient.backupBaseUrl() + "database/reading_stats.json");
            }

            manifest.put("files", filesEntry);
            manifest.put("assets", new JSONObject());
            // 上传 manifest
            uploadManifest(webDavClient.backupBaseUrl() + "database/" + MANIFEST_FILE, manifest);
        }

        if (shouldSyncSettingsSnapshot()) {
            uploadSettingsSnapshot(listener);
        }

        uploadLocalAssets(listener, includeChapterText, includeFiles, includeBackgrounds);
        cleanupRemoteUnreferencedAssetsIfEnabled(listener, includeChapterText, includeFiles, includeBackgrounds);
        settingsStore.setWebDavLastFullBackupAt(System.currentTimeMillis());
        cleanupLocalTempCache();
        listener.onStatus("全量备份完成");
    }

    // ==================== 增量备份 ====================

    public void incrementalBackup(StatusListener listener) throws Exception {
        cleanupLocalTempCache();
        ensureAnySyncScopeSelected();
        normalizeReplacementRules(databaseHelper.getRulesMutable());
        databaseHelper.flush();
        boolean includeChapterText = settingsStore.isWebDavSyncBookshelfEnabled();
        boolean includeFiles = settingsStore.isWebDavSyncFilesEnabled();
        boolean includeBackgrounds = settingsStore.isWebDavSyncBackgroundsEnabled();

        listener.onStatus("创建云端目录...");
        webDavClient.ensureBackupRootDirectory();
        webDavClient.ensureDirectory(webDavClient.syncBaseUrl());
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
            File dataDir = databaseHelper.getDataDir();
            String manifestUrl = webDavClient.syncBaseUrl() + MANIFEST_FILE;

            // 构建本地 manifest
            JSONObject localManifest = new JSONObject();
            localManifest.put("schemaVersion", 1);
            localManifest.put("generatedAt", System.currentTimeMillis());
            JSONObject localFiles = new JSONObject();
            for (String fileName : SYNC_JSON_FILES) {
                File jsonFile = new File(dataDir, fileName);
                if (!jsonFile.exists()) continue;
                String sha256 = computeFileSha256(jsonFile);
                manifestAppendFile(localFiles, fileName, sha256, jsonFile.length());
            }
            localManifest.put("files", localFiles);

            // 下载远程 manifest
            JSONObject remoteManifest = downloadManifestIfExists(manifestUrl);
            int uploadedFiles = 0;

            // 只上传变化的 JSON 文件
            for (String fileName : SYNC_JSON_FILES) {
                File jsonFile = new File(dataDir, fileName);
                if (!jsonFile.exists()) continue;
                String localHash = getManifestFileHash(localFiles, fileName);
                String remoteHash = getManifestFileHash(
                        remoteManifest != null ? remoteManifest.optJSONObject("files") : null, fileName);
                if (remoteHash != null && remoteHash.equals(localHash)) {
                    continue; // 未变化，跳过
                }
                listener.onStatus("上传 " + fileName + "...");
                webDavClient.uploadFile(jsonFile, webDavClient.syncBaseUrl() + fileName);
                uploadedFiles++;
            }

            // 构建并上传资源清单（用于资源增量判断）
            JSONObject localAssets = new JSONObject();
            collectAssetManifest(localAssets, includeChapterText, includeFiles, includeBackgrounds);
            localManifest.put("assets", localAssets);

            if (uploadedFiles == 0 && assetsUnchanged(localAssets, remoteManifest)) {
                listener.onStatus("无变化，跳过上传");
            } else {
                uploadManifest(manifestUrl, localManifest);
            }

            // 上传变化的资源文件
            uploadChangedAssets(listener, includeChapterText, includeFiles, includeBackgrounds,
                    remoteManifest != null ? remoteManifest.optJSONObject("assets") : null, localAssets);

            // 保存远程 manifest 作为本地副本
            saveLocalManifest(localManifest);
        }

        if (shouldSyncSettingsSnapshot()) {
            uploadSettingsSnapshot(listener);
        }

        cleanupRemoteUnreferencedAssetsIfEnabled(listener, includeChapterText, includeFiles, includeBackgrounds);
        settingsStore.setWebDavLastLiteBackupAt(System.currentTimeMillis());
        cleanupLocalTempCache();
        listener.onStatus("增量备份完成");
    }

    // ==================== 全量恢复 ====================

    public void fullRestore(StatusListener listener) throws Exception {
        cleanupLocalTempCache();
        ensureRestoreScopeSelected();

        if (shouldSyncDatabaseSnapshot()) {
            String databaseUrl = webDavClient.backupBaseUrl() + "database/";
            String manifestUrl = databaseUrl + MANIFEST_FILE;

            // 检查远程是否有全量备份
            if (webDavClient.head(databaseUrl + "books.json").code == 404 &&
                    webDavClient.head(webDavClient.backupBaseUrl() + "reader.db").code == 404) {
                throw new IllegalStateException("云端没有全量备份，请先执行全量备份");
            }

            // 如果存在新格式 (JSON)，用新格式
            if (webDavClient.head(databaseUrl + "books.json").code == 200) {
                listener.onStatus("下载 JSON 数据文件...");
                File dataDir = databaseHelper.getDataDir();
                if (!dataDir.exists()) dataDir.mkdirs();

                JSONObject manifest = downloadManifestIfExists(manifestUrl);
                for (String fileName : SYNC_JSON_FILES) {
                    File target = new File(dataDir, fileName);
                    listener.onStatus("下载 " + fileName + "...");
                    try {
                        webDavClient.downloadBinaryFile(databaseUrl + fileName, target);
                        // 校验
                        if (manifest != null) {
                            String expectedHash = getManifestFileHash(manifest.optJSONObject("files"), fileName);
                            if (expectedHash != null) {
                                String actualHash = computeFileSha256(target);
                                if (!expectedHash.equals(actualHash)) {
                                    Log.w(TAG, fileName + " SHA-256 校验不匹配，继续使用");
                                }
                            }
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "下载 " + fileName + " 失败: " + e.getMessage());
                    }
                }
                databaseHelper.reloadFromDisk();
                normalizeReplacementRules(databaseHelper.getRulesMutable());
                databaseHelper.flush();
                databaseHelper.rebaseLocalAssetPaths();

                listener.onStatus("恢复书籍资源文件...");
                restoreBookAssetFiles(listener, settingsStore.isWebDavSyncFilesEnabled());
                listener.onStatus("恢复章节正文...");
                restoreChapterTextFiles(listener);
            } else {
                // 回退：使用旧格式 reader.db（如果 JSON 格式不存在）
                restoreFromLegacyFullDatabase(listener);
            }
        }

        restoreSettingsJsonIfPresent(listener);
        cleanupLocalTempCache();
        listener.onStatus("全量恢复完成");
    }

    // ==================== 增量恢复 ====================

    public void incrementalRestore(StatusListener listener) throws Exception {
        cleanupLocalTempCache();
        ensureRestoreScopeSelected();

        if (shouldSyncDatabaseSnapshot()) {
            String manifestUrl = webDavClient.syncBaseUrl() + MANIFEST_FILE;

            // 检查远程是否有增量数据
            if (webDavClient.head(webDavClient.syncBaseUrl() + "books.json").code == 404 &&
                    webDavClient.head(webDavClient.backupBaseUrl() + "reader_lite.db").code == 404) {
                throw new IllegalStateException("云端没有增量备份，请先执行增量备份或改用全量恢复");
            }

            // 如果存在新格式，用新格式
            if (webDavClient.head(webDavClient.syncBaseUrl() + "books.json").code == 200) {
                // 下载远程 manifest
                JSONObject remoteManifest = downloadManifestIfExists(manifestUrl);
                if (remoteManifest == null) {
                    throw new IllegalStateException("云端 manifest 不存在，请先执行增量备份");
                }

                // 加载本地 manifest
                JSONObject localManifest = loadLocalManifest();

                // 对比，找出变化的文件
                Set<String> changedFiles = compareManifests(remoteManifest.optJSONObject("files"),
                        localManifest != null ? localManifest.optJSONObject("files") : null);
                if (changedFiles.isEmpty()) {
                    listener.onStatus("已是最新，无需恢复");
                    cleanupLocalTempCache();
                    return;
                }

                databaseHelper.flush();
                listener.onStatus("下载并合并变化的数据...");

                boolean mergedBooks = false;
                boolean mergedChapters = false;
                Map<Long, Long> restoredBookIdMap = new HashMap<>();

                for (String fileName : SYNC_JSON_FILES) {
                    if (!changedFiles.contains(fileName)) continue;
                    File tempFile = new File(context.getCacheDir(), "restore_" + fileName);
                    listener.onStatus("下载 " + fileName + "...");
                    webDavClient.downloadBinaryFile(webDavClient.syncBaseUrl() + fileName, tempFile);

                    // 校验
                    String expectedHash = getManifestFileHash(remoteManifest.optJSONObject("files"), fileName);
                    if (expectedHash != null) {
                        String actualHash = computeFileSha256(tempFile);
                        if (!expectedHash.equals(actualHash)) {
                            throw new IllegalStateException(fileName + " 校验失败，传输可能不完整");
                        }
                    }

                    // 逐实体合并
                    String content = readFileString(tempFile);
                    if (content == null || content.trim().isEmpty()) continue;
                    JSONArray array = new JSONArray(content);

                    switch (fileName) {
                        case "books.json":
                            restoredBookIdMap.putAll(mergeBooks(array));
                            mergedBooks = true;
                            break;
                        case "chapters.json":
                            mergeChapters(array, restoredBookIdMap);
                            mergedChapters = true;
                            break;
                        case "rules.json":
                            mergeRules(array, restoredBookIdMap);
                            break;
                        case "themes.json":
                            mergeThemes(array);
                            break;
                        case "bookmarks.json":
                            mergeBookmarks(array);
                            break;
                    }
                    tempFile.delete();
                }

                // 合并后钳制进度
                if (mergedBooks || mergedChapters) {
                    clampProgressIndex();
                }

                // 保存合并结果到磁盘并重定位路径（新增书籍的 coverFile/sourceFile → 绝对路径）
                databaseHelper.flush();
                databaseHelper.rebaseLocalAssetPaths();

                // 恢复变化的资源文件
                JSONObject remoteAssets = remoteManifest.optJSONObject("assets");
                JSONObject localAssets = localManifest != null ? localManifest.optJSONObject("assets") : null;
                restoreChangedAssets(listener, remoteAssets, localAssets);

                // 保存远程 manifest 为本地副本
                saveLocalManifest(remoteManifest);
            } else {
                // 回退：使用旧格式 reader_lite.db
                restoreFromLegacyLiteDatabase(listener);
            }
        }

        restoreSettingsJsonIfPresent(listener);
        cleanupLocalTempCache();
        listener.onStatus("增量恢复完成");
    }

    // ==================== 时间标签 ====================

    public String lastFullBackupLabel() {
        long value = settingsStore.getWebDavLastFullBackupAt();
        return value <= 0 ? "尚未备份" : DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.SIMPLIFIED_CHINESE).format(new Date(value));
    }

    public String lastLiteBackupLabel() {
        long value = settingsStore.getWebDavLastLiteBackupAt();
        return value <= 0 ? "尚未备份" : DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.SIMPLIFIED_CHINESE).format(new Date(value));
    }

    // ==================== 设置快照 ====================

    private void uploadSettingsSnapshot(StatusListener listener) throws Exception {
        listener.onStatus("上传 Android 设置...");
        webDavClient.uploadText(
                webDavClient.androidSettingsSnapshotUrl(),
                settingsStore.exportAndroidPrivateSettingsJson().toString(2),
                "application/json; charset=utf-8"
        );
    }

    private void restoreSettingsJsonIfPresent(StatusListener listener) throws Exception {
        if (!shouldSyncSettingsSnapshot()) return;
        String remotePath = webDavClient.androidSettingsSnapshotUrl();
        if (webDavClient.head(remotePath).code != 200) return;
        listener.onStatus("恢复 Android 设置...");
        String json = webDavClient.downloadText(remotePath);
        JSONObject settingsJson = new JSONObject(json);
        String restoredBackgroundPath = restoreBackgroundIfPresent(listener, settingsJson);
        settingsStore.importAndroidPrivateSettingsJson(settingsJson, restoredBackgroundPath);
    }

    private String restoreBackgroundIfPresent(StatusListener listener, JSONObject settingsJson) {
        String backgroundFileName = settingsStore.androidSettingsBackgroundFileName(settingsJson);
        if (backgroundFileName.isBlank()) return null;
        try {
            String remotePath = webDavClient.androidSettingsBackgroundsBaseUrl() + backgroundFileName;
            if (webDavClient.head(remotePath).code != 200) return null;
            File folder = new File(context.getFilesDir(), "backgrounds");
            if (!folder.exists() && !folder.mkdirs()) return null;
            File destination = new File(folder, backgroundFileName);
            listener.onStatus("恢复背景图片...");
            webDavClient.downloadBinaryFile(remotePath, destination);
            return destination.getAbsolutePath();
        } catch (Exception ignored) {
            return null;
        }
    }

    // ==================== 资源文件上传/恢复（保持与旧版兼容） ====================

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
            if (head.code == 404 || head.code < 200 || head.code >= 300) return;
            long remoteLength = -1L;
            try { remoteLength = webDavClient.remoteContentLength(remotePath); } catch (Exception ignored) {}
            if (destination.exists() && remoteLength >= 0 && destination.length() == remoteLength) return;
            File parent = destination.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            listener.onStatus(status);
            webDavClient.downloadBinaryFile(remotePath, destination);
        } catch (Exception error) {
            Log.w(TAG, "恢复资源文件失败: " + remotePath, error);
        }
    }

    private void uploadLocalAssets(StatusListener listener, boolean includeChapterText, boolean includeFiles, boolean includeBackgrounds) throws Exception {
        List<BookRecord> books = includeChapterText || includeFiles ? databaseHelper.getBooks() : new ArrayList<>();
        if (includeChapterText) uploadChapterTextArchives(books, listener);
        if (includeFiles) {
            uploadCoverFiles(books, listener);
            uploadSourceFiles(books, listener);
        }
        if (!includeBackgrounds) return;
        String backgroundPath = settingsStore.getReaderBackgroundPath();
        if (!backgroundPath.isBlank()) {
            File backgroundFile = new File(backgroundPath);
            if (backgroundFile.exists()) {
                String remotePath = webDavClient.androidSettingsBackgroundsBaseUrl() + backgroundFile.getName();
                if (shouldUploadFile(remotePath, backgroundFile)) {
                    listener.onStatus("上传背景图片 " + formatFileSize(backgroundFile.length()) + "...");
                    webDavClient.uploadFile(backgroundFile, remotePath);
                }
            }
        }
    }

    private void uploadChangedAssets(StatusListener listener, boolean includeChapterText, boolean includeFiles,
                                     boolean includeBackgrounds, JSONObject remoteAssets, JSONObject localAssets) throws Exception {
        List<BookRecord> books = includeChapterText || includeFiles ? databaseHelper.getBooks() : new ArrayList<>();
        if (includeChapterText) uploadChangedChapterTextArchives(books, listener, remoteAssets, localAssets);
        if (includeFiles) {
            uploadChangedCoverFiles(books, listener, remoteAssets, localAssets);
            uploadChangedSourceFiles(books, listener, remoteAssets, localAssets);
        }
        if (includeBackgrounds) {
            String backgroundPath = settingsStore.getReaderBackgroundPath();
            if (!backgroundPath.isBlank()) {
                File backgroundFile = new File(backgroundPath);
                if (backgroundFile.exists()) {
                    String remotePath = webDavClient.androidSettingsBackgroundsBaseUrl() + backgroundFile.getName();
                    String assetKey = "backgrounds/" + backgroundFile.getName();
                    long localSize = backgroundFile.length();
                    if (isAssetChanged(remoteAssets, assetKey, localSize)) {
                        listener.onStatus("上传背景图片...");
                        webDavClient.uploadFile(backgroundFile, remotePath);
                    }
                }
            }
        }
    }

    private void restoreChangedAssets(StatusListener listener, JSONObject remoteAssets, JSONObject localAssets) {
        if (remoteAssets == null) return;
        // 只恢复变化的封面和源文件
        List<BookRecord> books = databaseHelper.getBooks();
        for (BookRecord book : books) {
            if (book.coverPath != null && !book.coverPath.isBlank()) {
                File coverFile = new File(book.coverPath);
                String remotePath = webDavClient.backupBaseUrl() + "covers/" + coverFile.getName();
                String assetKey = "covers/" + coverFile.getName();
                if (isAssetChanged(remoteAssets, assetKey, coverFile.exists() ? coverFile.length() : -1)) {
                    restoreRemoteFileIfPresent(remotePath, coverFile, "恢复封面...", listener);
                }
            }
            if (settingsStore.isWebDavSyncFilesEnabled() && book.localPath != null && !book.localPath.isBlank()) {
                File sourceFile = new File(book.localPath);
                String remotePath = webDavClient.backupBaseUrl() + "books/" + sourceFile.getName();
                String assetKey = "books/" + sourceFile.getName();
                if (isAssetChanged(remoteAssets, assetKey, sourceFile.exists() ? sourceFile.length() : -1)) {
                    restoreRemoteFileIfPresent(remotePath, sourceFile, "恢复源文件...", listener);
                }
            }
        }
    }

    private boolean assetsUnchanged(JSONObject localAssets, JSONObject remoteManifest) {
        if (remoteManifest == null) return false;
        JSONObject remoteAssets = remoteManifest.optJSONObject("assets");
        if (remoteAssets == null) return localAssets.length() == 0;
        if (localAssets.length() != remoteAssets.length()) return false;
        Iterator<String> keys = localAssets.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            JSONObject localAsset = localAssets.optJSONObject(key);
            JSONObject remoteAsset = remoteAssets.optJSONObject(key);
            if (localAsset == null || remoteAsset == null) return false;
            if (localAsset.optLong("size") != remoteAsset.optLong("size")) return false;
        }
        return true;
    }

    private boolean isAssetChanged(JSONObject manifestAssets, String assetKey, long localSize) {
        if (manifestAssets == null) return true; // 无远程清单，需要下载
        JSONObject asset = manifestAssets.optJSONObject(assetKey);
        if (asset == null) return true; // 远程没有，需要下载（可能是新文件）
        return asset.optLong("size", -1) != localSize;
    }

    private void collectAssetManifest(JSONObject assets, boolean includeChapterText, boolean includeFiles, boolean includeBackgrounds) {
        try {
            if (includeChapterText || includeFiles) {
                List<BookRecord> books = databaseHelper.getBooks();
                for (BookRecord book : books) {
                    if (includeFiles) {
                        if (book.coverPath != null && !book.coverPath.isBlank()) {
                            File coverFile = new File(book.coverPath);
                            if (coverFile.exists()) {
                                JSONObject entry = new JSONObject();
                                entry.put("size", coverFile.length());
                                assets.put("covers/" + coverFile.getName(), entry);
                            }
                        }
                        if (book.localPath != null && !book.localPath.isBlank()) {
                            File sourceFile = new File(book.localPath);
                            if (sourceFile.exists()) {
                                JSONObject entry = new JSONObject();
                                entry.put("size", sourceFile.length());
                                assets.put("books/" + sourceFile.getName(), entry);
                            }
                        }
                    }
                    if (includeChapterText) {
                        List<ChapterTextFile> files = collectChapterTextFiles(book);
                        if (!files.isEmpty()) {
                            String archiveName = "book_" + book.id + ".zip";
                            // 用本地临时打包估算大小
                            long estimatedSize = 0;
                            for (ChapterTextFile f : files) estimatedSize += f.file.length();
                            JSONObject entry = new JSONObject();
                            entry.put("size", estimatedSize);
                            assets.put("chapter_text/" + archiveName, entry);
                        }
                    }
                }
            }
            if (includeBackgrounds) {
                String bgPath = settingsStore.getReaderBackgroundPath();
                if (!bgPath.isBlank()) {
                    File bgFile = new File(bgPath);
                    if (bgFile.exists()) {
                        JSONObject entry = new JSONObject();
                        entry.put("size", bgFile.length());
                        assets.put("backgrounds/" + bgFile.getName(), entry);
                    }
                }
            }
        } catch (Exception ignore) {}
    }

    // ==================== 章节正文打包/解包（保持兼容） ====================

    private void uploadChapterTextArchives(List<BookRecord> books, StatusListener listener) throws Exception {
        File tempDir = new File(context.getCacheDir(), "backup");
        if (!tempDir.exists() && !tempDir.mkdirs()) throw new IllegalStateException("无法创建备份缓存目录");
        int total = Math.max(books.size(), 1);
        int uploaded = 0;
        int skipped = 0;
        for (int i = 0; i < books.size(); i++) {
            BookRecord book = books.get(i);
            List<ChapterTextFile> files = collectChapterTextFiles(book);
            if (files.isEmpty()) continue;
            File archive = new File(tempDir, chapterTextArchiveFileName(book.id));
            writeChapterTextArchive(archive, files, i + 1, total, listener);
            String remotePath = chapterTextArchiveRemotePath(book.id);
            if (shouldUploadFile(remotePath, archive)) {
                uploaded++;
                listener.onStatus("上传章节正文包 " + (i + 1) + "/" + total + " · " + formatFileSize(archive.length()) + "...");
                webDavClient.uploadFile(archive, remotePath);
            } else {
                skipped++;
            }
        }
        if (uploaded == 0 && skipped > 0) listener.onStatus("章节正文包未变化，跳过上传");
    }

    private void uploadChangedChapterTextArchives(List<BookRecord> books, StatusListener listener,
                                                   JSONObject remoteAssets, JSONObject localAssets) throws Exception {
        File tempDir = new File(context.getCacheDir(), "backup");
        if (!tempDir.exists() && !tempDir.mkdirs()) throw new IllegalStateException("无法创建备份缓存目录");
        for (BookRecord book : books) {
            List<ChapterTextFile> files = collectChapterTextFiles(book);
            if (files.isEmpty()) continue;
            String archiveName = "book_" + book.id + ".zip";
            String assetKey = "chapter_text/" + archiveName;
            long estimatedSize = 0;
            for (ChapterTextFile f : files) estimatedSize += f.file.length();
            if (!isAssetChanged(remoteAssets, assetKey, estimatedSize)) continue;
            File archive = new File(tempDir, archiveName);
            writeChapterTextArchive(archive, files, 0, books.size(), listener);
            listener.onStatus("上传章节正文包...");
            webDavClient.uploadFile(archive, chapterTextArchiveRemotePath(book.id));
        }
    }

    private void uploadCoverFiles(List<BookRecord> books, StatusListener listener) throws Exception {
        int total = Math.max(books.size(), 1);
        for (int i = 0; i < books.size(); i++) {
            BookRecord book = books.get(i);
            if (book.coverPath == null || book.coverPath.isBlank()) continue;
            File coverFile = new File(book.coverPath);
            if (!coverFile.exists() || !coverFile.isFile()) continue;
            String remotePath = webDavClient.backupBaseUrl() + "covers/" + coverFile.getName();
            if (shouldUploadFile(remotePath, coverFile)) {
                listener.onStatus("上传封面 " + (i + 1) + "/" + total + " · " + formatFileSize(coverFile.length()) + "...");
                webDavClient.uploadFile(coverFile, remotePath);
            }
        }
    }

    private void uploadChangedCoverFiles(List<BookRecord> books, StatusListener listener,
                                          JSONObject remoteAssets, JSONObject localAssets) throws Exception {
        for (BookRecord book : books) {
            if (book.coverPath == null || book.coverPath.isBlank()) continue;
            File coverFile = new File(book.coverPath);
            if (!coverFile.exists()) continue;
            String assetKey = "covers/" + coverFile.getName();
            if (isAssetChanged(remoteAssets, assetKey, coverFile.length())) {
                listener.onStatus("上传封面...");
                webDavClient.uploadFile(coverFile, webDavClient.backupBaseUrl() + "covers/" + coverFile.getName());
            }
        }
    }

    private void uploadSourceFiles(List<BookRecord> books, StatusListener listener) throws Exception {
        int total = Math.max(books.size(), 1);
        for (int i = 0; i < books.size(); i++) {
            BookRecord book = books.get(i);
            if (book.localPath == null || book.localPath.isBlank()) continue;
            File localFile = new File(book.localPath);
            if (!localFile.exists() || !localFile.isFile()) continue;
            String remotePath = webDavClient.backupBaseUrl() + "books/" + localFile.getName();
            if (shouldUploadFile(remotePath, localFile)) {
                listener.onStatus("上传书籍源文件 " + (i + 1) + "/" + total + " · " + formatFileSize(localFile.length()) + "...");
                webDavClient.uploadFile(localFile, remotePath);
            }
        }
    }

    private void uploadChangedSourceFiles(List<BookRecord> books, StatusListener listener,
                                           JSONObject remoteAssets, JSONObject localAssets) throws Exception {
        for (BookRecord book : books) {
            if (book.localPath == null || book.localPath.isBlank()) continue;
            File localFile = new File(book.localPath);
            if (!localFile.exists()) continue;
            String assetKey = "books/" + localFile.getName();
            if (isAssetChanged(remoteAssets, assetKey, localFile.length())) {
                listener.onStatus("上传源文件...");
                webDavClient.uploadFile(localFile, webDavClient.backupBaseUrl() + "books/" + localFile.getName());
            }
        }
    }

    private void restoreChapterTextFiles(StatusListener listener) {
        List<BookRecord> books = databaseHelper.getBooks();
        int total = Math.max(books.size(), 1);
        for (int i = 0; i < books.size(); i++) {
            BookRecord book = books.get(i);
            if (restoreBookChapterTextArchiveIfPresent(book, i + 1, total, listener)) continue;
            restoreChapterTextFilesForBook(book, i + 1, total, listener);
        }
    }

    private boolean restoreBookChapterTextArchiveIfPresent(BookRecord book, int bookIndex, int totalBooks, StatusListener listener) {
        String remotePath = chapterTextArchiveRemotePath(book.id);
        try {
            if (webDavClient.head(remotePath).code != 200) return false;
            File tempDir = new File(context.getCacheDir(), "backup_restore");
            if (!tempDir.exists() && !tempDir.mkdirs()) return false;
            File archive = new File(tempDir, chapterTextArchiveFileName(book.id));
            listener.onStatus("下载章节正文包 " + bookIndex + "/" + totalBooks + "...");
            webDavClient.downloadBinaryFile(remotePath, archive);
            listener.onStatus("解包章节正文 " + bookIndex + "/" + totalBooks + "...");
            int restored = extractChapterTextArchive(archive);
            listener.onStatus("章节正文包已恢复 " + bookIndex + "/" + totalBooks + " · " + restored + " 个文件");
            return restored > 0;
        } catch (Exception error) {
            Log.w(TAG, "恢复章节正文包失败 book " + book.id + "，回退逐文件恢复", error);
            return false;
        }
    }

    private void restoreChapterTextFilesForBook(BookRecord book, int bookIndex, int totalBooks, StatusListener listener) {
        List<ChapterRecord> chapters = databaseHelper.getChaptersWithExternalStorage(book.id);
        for (ChapterRecord chapter : chapters) {
            if (chapter.bodyTextPath == null || chapter.bodyTextPath.isBlank()) continue;
            try {
                String remotePath = webDavClient.backupBaseUrl() + "chapter_text/" + chapter.bodyTextPath;
                if (webDavClient.head(remotePath).code != 200) {
                    Log.w(TAG, "章节正文缺失 chapter " + chapter.id + ": " + chapter.bodyTextPath);
                    continue;
                }
                listener.onStatus("恢复章节正文 " + bookIndex + "/" + totalBooks + "...");
                File localFile = databaseHelper.resolveChapterTextFile(chapter.bodyTextPath);
                if (localFile != null) {
                    File parent = localFile.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();
                    webDavClient.downloadBinaryFile(remotePath, localFile);
                }
            } catch (Exception e) {
                Log.w(TAG, "恢复章节正文失败 chapter " + chapter.id, e);
            }
        }
    }

    // ==================== 旧格式回退 ====================

    private void restoreFromLegacyFullDatabase(StatusListener listener) throws Exception {
        File tempDir = new File(context.getCacheDir(), "backup_restore");
        if (!tempDir.exists()) tempDir.mkdirs();
        File fullDb = new File(tempDir, "reader_full_restore.db");
        listener.onStatus("下载旧格式数据库...");
        if (webDavClient.head(webDavClient.backupBaseUrl() + "reader.db").code == 404) {
            throw new IllegalStateException("云端没有全量备份，请先执行全量备份");
        }
        webDavClient.downloadBinaryFile(webDavClient.backupBaseUrl() + "reader.db", fullDb);
        listener.onStatus("应用旧格式数据库...");
        databaseHelper.stripPlatformSettingsTable(fullDb);
        databaseHelper.importDatabase(fullDb);
        normalizeReplacementRules(databaseHelper.getRulesMutable());
        databaseHelper.flush();
        databaseHelper.rebaseLocalAssetPaths();
        restoreBookAssetFiles(listener, settingsStore.isWebDavSyncFilesEnabled());
        listener.onStatus("恢复章节正文...");
        restoreChapterTextFiles(listener);
    }

    private void restoreFromLegacyLiteDatabase(StatusListener listener) throws Exception {
        File tempDir = new File(context.getCacheDir(), "backup_restore");
        if (!tempDir.exists()) tempDir.mkdirs();
        File liteDb = new File(tempDir, "reader_lite_restore.db");
        listener.onStatus("下载旧格式增量数据库...");
        if (webDavClient.head(webDavClient.backupBaseUrl() + "reader_lite.db").code == 404) {
            throw new IllegalStateException("云端没有增量备份，请先执行增量备份或改用全量恢复");
        }
        webDavClient.downloadBinaryFile(webDavClient.backupBaseUrl() + "reader_lite.db", liteDb);
        listener.onStatus("合并旧格式数据...");
        databaseHelper.stripPlatformSettingsTable(liteDb);
        databaseHelper.mergeLiteDatabase(liteDb);
        normalizeReplacementRules(databaseHelper.getRulesMutable());
        databaseHelper.flush();
        restoreBookAssetFiles(listener, settingsStore.isWebDavSyncFilesEnabled());
    }

    // ==================== 实体合并逻辑 ====================

    private Map<Long, Long> mergeBooks(JSONArray remoteBooks) {
        List<BookRecord> localBooks = databaseHelper.getBooksMutable();
        Map<Long, Long> remoteToLocalBookIds = new HashMap<>();
        Map<String, BookRecord> localByKey = new HashMap<>();
        Map<String, BookRecord> localByTitleAuthor = new HashMap<>();
        for (BookRecord book : localBooks) {
            if (book.readingStatsKey != null && !book.readingStatsKey.isEmpty()) {
                localByKey.put(book.readingStatsKey, book);
            }
            String taKey = normalizeTitleAuthor(book.title, book.author);
            if (!taKey.isEmpty()) {
                localByTitleAuthor.put(taKey, book);
            }
        }

        Set<Long> matchedIds = new HashSet<>();
        for (int i = 0; i < remoteBooks.length(); i++) {
            JSONObject remoteJson = remoteBooks.optJSONObject(i);
            if (remoteJson == null) continue;
            long remoteId = remoteJson.optLong("id", 0);
            String remoteKey = remoteJson.optString("readingStatsKey", "");
            String remoteTitle = remoteJson.optString("title", "");
            String remoteAuthor = remoteJson.optString("author", "");
            long remoteUpdatedAt = remoteJson.optLong("updatedAt", 0);

            // 先按 readingStatsKey 匹配
            BookRecord localMatch = null;
            if (!remoteKey.isEmpty()) {
                localMatch = localByKey.get(remoteKey);
            }
            // 再按 title+author 匹配
            if (localMatch == null) {
                String taKey = normalizeTitleAuthor(remoteTitle, remoteAuthor);
                localMatch = localByTitleAuthor.get(taKey);
            }

            if (localMatch == null) {
                // 新书：插入
                BookRecord newBook = BookRecord.fromJson(remoteJson);
                newBook.id = nextRecordId(localBooks, remoteId);
                localBooks.add(newBook);
                localMatch = newBook;
                if (!remoteKey.isEmpty()) {
                    localByKey.put(remoteKey, newBook);
                }
                String taKey = normalizeTitleAuthor(remoteTitle, remoteAuthor);
                if (!taKey.isEmpty()) {
                    localByTitleAuthor.put(taKey, newBook);
                }
            } else if (remoteUpdatedAt > localMatch.updatedAt) {
                // 远程较新：更新本地
                matchedIds.add(localMatch.id);
                localMatch.title = remoteTitle;
                localMatch.author = remoteAuthor;
                localMatch.bookType = remoteJson.optString("bookType", "text");
                localMatch.readingStatsKey = remoteKey;
                localMatch.progressIndex = remoteJson.optInt("progressIndex", 0);
                localMatch.progressOffset = remoteJson.optInt("progressOffset", 0);
                localMatch.lastReadAt = remoteJson.optLong("lastReadAt", 0);
                localMatch.pinned = remoteJson.optBoolean("pinned", false);
                localMatch.chapterCount = remoteJson.optInt("chapterCount", 0);
                localMatch.currentChapterTitle = remoteJson.optString("currentChapterTitle", "");
                localMatch.updatedAt = remoteUpdatedAt;
                // 保留本地路径（不覆盖 coverPath/localPath）
            }
            if (remoteId > 0 && localMatch != null) {
                remoteToLocalBookIds.put(remoteId, localMatch.id);
            }
        }
        return remoteToLocalBookIds;
    }

    private void mergeChapters(JSONArray remoteChapters, Map<Long, Long> restoredBookIdMap) {
        List<ChapterRecord> localChapters = databaseHelper.getChaptersMutable();
        // 按 (bookId, orderIndex) 构建索引
        Map<String, ChapterRecord> localByKey = new HashMap<>();
        for (ChapterRecord ch : localChapters) {
            String key = ch.bookId + ":" + ch.orderIndex;
            localByKey.put(key, ch);
        }

        for (int i = 0; i < remoteChapters.length(); i++) {
            JSONObject remoteJson = remoteChapters.optJSONObject(i);
            if (remoteJson == null) continue;
            long remoteBookId = remoteJson.optLong("bookId", 0);
            long bookId = restoredBookIdMap.getOrDefault(remoteBookId, remoteBookId);
            int orderIndex = remoteJson.optInt("orderIndex", 0);
            String key = bookId + ":" + orderIndex;
            ChapterRecord localMatch = localByKey.get(key);

            if (localMatch == null) {
                ChapterRecord newChapter = ChapterRecord.fromJson(remoteJson);
                newChapter.bookId = bookId;
                newChapter.id = nextRecordId(localChapters, newChapter.id);
                localChapters.add(newChapter);
                localByKey.put(key, newChapter);
            } else {
                // 更新章节元数据
                localMatch.title = remoteJson.optString("title", "");
                String remoteStorage = remoteJson.optString("bodyTextStorage", "db");
                String remotePath = remoteJson.optString("bodyTextPath", "");
                long remoteSize = remoteJson.optLong("bodyTextSize", 0);
                // 只有当远程有外置文件时才更新存储信息
                if ("file_gzip".equals(remoteStorage) && !remotePath.isEmpty()) {
                    localMatch.bodyTextStorage = remoteStorage;
                    localMatch.bodyTextPath = remotePath;
                    localMatch.bodyTextSize = remoteSize;
                }
            }
        }
    }

    private void mergeRules(JSONArray remoteRules, Map<Long, Long> restoredBookIdMap) {
        List<ReplacementRuleRecord> localRules = databaseHelper.getRulesMutable();
        normalizeReplacementRules(localRules);
        Map<String, ReplacementRuleRecord> localByKey = new HashMap<>();
        for (ReplacementRuleRecord rule : localRules) {
            String key = buildRuleKey(rule);
            localByKey.put(key, rule);
        }

        for (int i = 0; i < remoteRules.length(); i++) {
            JSONObject remoteJson = remoteRules.optJSONObject(i);
            if (remoteJson == null) continue;
            ReplacementRuleRecord remoteRule = ReplacementRuleRecord.fromJson(remoteJson);
            normalizeRuleScope(remoteRule);
            if ("book".equals(remoteRule.scope) && remoteRule.bookId != null) {
                remoteRule.bookId = restoredBookIdMap.getOrDefault(remoteRule.bookId, remoteRule.bookId);
            }
            String key = buildRuleKey(remoteRule);
            ReplacementRuleRecord localMatch = localByKey.get(key);

            if (localMatch == null) {
                remoteRule.id = nextRecordId(localRules, remoteRule.id);
                localRules.add(remoteRule);
                localByKey.put(key, remoteRule);
            } else if (remoteRule.updatedAt > localMatch.updatedAt) {
                copyReplacementRuleFields(localMatch, remoteRule);
            }
        }
        normalizeReplacementRules(localRules);
    }

    private void mergeThemes(JSONArray remoteThemes) {
        List<ReaderThemeRecord> localThemes = databaseHelper.getThemesMutable();
        Map<String, ReaderThemeRecord> localByName = new HashMap<>();
        for (ReaderThemeRecord theme : localThemes) {
            if (theme.name != null) localByName.put(theme.name, theme);
        }

        for (int i = 0; i < remoteThemes.length(); i++) {
            JSONObject remoteJson = remoteThemes.optJSONObject(i);
            if (remoteJson == null) continue;
            ReaderThemeRecord remoteTheme = ReaderThemeRecord.fromJson(remoteJson);
            ReaderThemeRecord localMatch = localByName.get(remoteTheme.name);

            if (localMatch == null) {
                remoteTheme.id = 0;
                localThemes.add(remoteTheme);
            } else if (remoteTheme.updatedAt > localMatch.updatedAt) {
                localMatch.configJson = remoteTheme.configJson;
                localMatch.updatedAt = remoteTheme.updatedAt;
            }
        }
    }

    private void mergeBookmarks(JSONArray remoteBookmarks) {
        List<BookmarkRecord> localBookmarks = databaseHelper.getBookmarksMutable();
        Map<String, BookmarkRecord> localByUuid = new HashMap<>();
        for (BookmarkRecord bm : localBookmarks) {
            if (bm.uuid != null) localByUuid.put(bm.uuid, bm);
        }

        for (int i = 0; i < remoteBookmarks.length(); i++) {
            JSONObject remoteJson = remoteBookmarks.optJSONObject(i);
            if (remoteJson == null) continue;
            BookmarkRecord remoteBm = BookmarkRecord.fromJson(remoteJson);
            BookmarkRecord localMatch = localByUuid.get(remoteBm.uuid);

            if (localMatch == null) {
                remoteBm.id = 0;
                localBookmarks.add(remoteBm);
            } else if (remoteBm.updatedAt > localMatch.updatedAt) {
                localMatch.bookId = remoteBm.bookId;
                localMatch.bookIdentity = remoteBm.bookIdentity;
                localMatch.bookTitle = remoteBm.bookTitle;
                localMatch.bookAuthor = remoteBm.bookAuthor;
                localMatch.chapterOrderIndex = remoteBm.chapterOrderIndex;
                localMatch.chapterTitle = remoteBm.chapterTitle;
                localMatch.chapterOffset = remoteBm.chapterOffset;
                localMatch.progressPercent = remoteBm.progressPercent;
                localMatch.summary = remoteBm.summary;
                localMatch.updatedAt = remoteBm.updatedAt;
            }
        }
    }

    private void clampProgressIndex() {
        List<BookRecord> books = databaseHelper.getBooksMutable();
        List<ChapterRecord> chapters = databaseHelper.getChaptersMutable();
        Map<Long, Integer> chapterCounts = new HashMap<>();
        for (ChapterRecord ch : chapters) {
            chapterCounts.put(ch.bookId, chapterCounts.getOrDefault(ch.bookId, 0) + 1);
        }
        for (BookRecord book : books) {
            int count = chapterCounts.getOrDefault(book.id, 0);
            book.chapterCount = count;
            if (count > 0) {
                book.progressIndex = Math.max(0, Math.min(book.progressIndex, count - 1));
            } else {
                book.progressIndex = 0;
                book.currentChapterTitle = "";
            }
        }
    }

    // ==================== Manifest 工具方法 ====================

    private void uploadManifest(String url, JSONObject manifest) throws Exception {
        File tempDir = new File(context.getCacheDir(), "backup");
        if (!tempDir.exists()) tempDir.mkdirs();
        File manifestFile = new File(tempDir, MANIFEST_FILE);
        try (FileWriter writer = new FileWriter(manifestFile)) {
            writer.write(manifest.toString(2));
        }
        webDavClient.uploadFile(manifestFile, url);
        manifestFile.delete();
    }

    private JSONObject downloadManifestIfExists(String url) {
        try {
            if (webDavClient.head(url).code != 200) return null;
            String content = webDavClient.downloadText(url);
            if (content == null || content.trim().isEmpty()) return null;
            return new JSONObject(content);
        } catch (Exception e) {
            return null;
        }
    }

    private void manifestAppendFile(JSONObject container, String fileName, String sha256, long size) {
        try {
            JSONObject entry = new JSONObject();
            entry.put("sha256", sha256);
            entry.put("size", size);
            container.put(fileName, entry);
        } catch (Exception ignore) {}
    }

    private String getManifestFileHash(JSONObject filesEntry, String fileName) {
        if (filesEntry == null) return null;
        JSONObject entry = filesEntry.optJSONObject(fileName);
        if (entry == null) return null;
        return entry.optString("sha256", null);
    }

    private Set<String> compareManifests(JSONObject remoteFiles, JSONObject localFiles) {
        Set<String> changed = new HashSet<>();
        if (remoteFiles == null) return changed;
        Iterator<String> keys = remoteFiles.keys();
        while (keys.hasNext()) {
            String fileName = keys.next();
            String remoteHash = remoteFiles.optJSONObject(fileName).optString("sha256", "");
            String localHash = localFiles != null && localFiles.has(fileName)
                    ? localFiles.optJSONObject(fileName).optString("sha256", "")
                    : "";
            if (!remoteHash.equals(localHash)) {
                changed.add(fileName);
            }
        }
        return changed;
    }

    private void saveLocalManifest(JSONObject manifest) {
        try {
            File file = new File(databaseHelper.getDataDir(), "last_manifest.json");
            try (FileWriter writer = new FileWriter(file)) {
                writer.write(manifest.toString(2));
            }
        } catch (Exception ignore) {}
    }

    private JSONObject loadLocalManifest() {
        try {
            File file = new File(databaseHelper.getDataDir(), "last_manifest.json");
            if (!file.exists()) return null;
            String content = readFileString(file);
            if (content == null || content.trim().isEmpty()) return null;
            return new JSONObject(content);
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== SHA-256 ====================

    static String computeFileSha256(File file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            try (FileInputStream fis = new FileInputStream(file)) {
                int read;
                while ((read = fis.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            StringBuilder builder = new StringBuilder();
            for (byte b : digest.digest()) {
                builder.append(String.format(Locale.ROOT, "%02x", b));
            }
            return builder.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // ==================== 辅助方法 ====================

    private boolean shouldUploadFile(String remotePath, File localFile) {
        try {
            return webDavClient.remoteContentLength(remotePath) != localFile.length();
        } catch (Exception ignored) {
            return true;
        }
    }

    private void normalizeReplacementRules(List<ReplacementRuleRecord> rules) {
        if (rules == null || rules.isEmpty()) {
            return;
        }
        Map<String, ReplacementRuleRecord> uniqueByKey = new LinkedHashMap<>();
        for (ReplacementRuleRecord rule : new ArrayList<>(rules)) {
            if (rule == null) {
                continue;
            }
            normalizeRuleScope(rule);
            String key = buildRuleKey(rule);
            ReplacementRuleRecord existing = uniqueByKey.get(key);
            if (existing == null) {
                uniqueByKey.put(key, rule);
            } else {
                mergeDuplicateReplacementRule(existing, rule);
            }
        }
        rules.clear();
        rules.addAll(uniqueByKey.values());
        ensureUniqueRuleIds(rules);
    }

    private void normalizeRuleScope(ReplacementRuleRecord rule) {
        if (rule == null) {
            return;
        }
        if ("book".equals(rule.scope)) {
            return;
        }
        rule.scope = "global";
        rule.bookId = null;
    }

    private void mergeDuplicateReplacementRule(ReplacementRuleRecord target, ReplacementRuleRecord incoming) {
        if (target == null || incoming == null) {
            return;
        }
        long stableId = target.id > 0 ? target.id : incoming.id;
        if (incoming.updatedAt > target.updatedAt) {
            copyReplacementRuleFields(target, incoming);
            target.id = stableId;
        } else if (target.id <= 0 && incoming.id > 0) {
            target.id = incoming.id;
        }
    }

    private void copyReplacementRuleFields(ReplacementRuleRecord target, ReplacementRuleRecord source) {
        long id = target.id;
        target.pattern = source.pattern;
        target.replacement = source.replacement;
        target.scope = source.scope;
        target.bookId = source.bookId;
        target.regex = source.regex;
        target.active = source.active;
        target.updatedAt = source.updatedAt;
        target.id = id;
        normalizeRuleScope(target);
    }

    private void ensureUniqueRuleIds(List<ReplacementRuleRecord> rules) {
        Set<Long> usedIds = new HashSet<>();
        for (ReplacementRuleRecord rule : rules) {
            if (rule == null) {
                continue;
            }
            if (rule.id <= 0 || usedIds.contains(rule.id)) {
                rule.id = nextUnusedId(usedIds);
            }
            usedIds.add(rule.id);
        }
    }

    private long nextRecordId(List<?> records, long preferredId) {
        Set<Long> usedIds = new HashSet<>();
        for (Object record : records) {
            long id = recordId(record);
            if (id > 0) {
                usedIds.add(id);
            }
        }
        if (preferredId > 0 && !usedIds.contains(preferredId)) {
            return preferredId;
        }
        return nextUnusedId(usedIds);
    }

    private long nextUnusedId(Set<Long> usedIds) {
        long maxId = 0;
        for (long id : usedIds) {
            if (id > maxId) {
                maxId = id;
            }
        }
        long candidate = maxId > 0 ? maxId + 1 : System.currentTimeMillis() * 1000L;
        while (usedIds.contains(candidate)) {
            candidate++;
        }
        return candidate;
    }

    private long recordId(Object record) {
        if (record instanceof BookRecord) return ((BookRecord) record).id;
        if (record instanceof ChapterRecord) return ((ChapterRecord) record).id;
        if (record instanceof ReplacementRuleRecord) return ((ReplacementRuleRecord) record).id;
        if (record instanceof ReaderThemeRecord) return ((ReaderThemeRecord) record).id;
        if (record instanceof BookmarkRecord) return ((BookmarkRecord) record).id;
        if (record instanceof ReadingTimeEntryRecord) return ((ReadingTimeEntryRecord) record).id;
        return 0;
    }

    private String buildRuleKey(ReplacementRuleRecord rule) {
        String scope = "book".equals(rule.scope) ? "book" : "global";
        long bookId = "book".equals(scope) && rule.bookId != null ? rule.bookId : 0L;
        return (rule.pattern != null ? rule.pattern : "") + "|" + scope + "|" + bookId;
    }

    private String normalizeTitleAuthor(String title, String author) {
        return (title != null ? title.trim().toLowerCase(Locale.ROOT) : "") + "::" +
                (author != null ? author.trim().toLowerCase(Locale.ROOT) : "");
    }

    private String readFileString(File file) {
        try {
            byte[] bytes = new byte[(int) file.length()];
            try (FileInputStream fis = new FileInputStream(file)) {
                int offset = 0;
                while (offset < bytes.length) {
                    int read = fis.read(bytes, offset, bytes.length - offset);
                    if (read < 0) break;
                    offset += read;
                }
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 章节正文工具方法（从旧版保留） ====================

    private void writeChapterTextArchive(File archive, List<ChapterTextFile> files, int bookIndex, int totalBooks, StatusListener listener) throws Exception {
        try (ZipOutputStream zipOutputStream = new ZipOutputStream(new FileOutputStream(archive))) {
            byte[] buffer = new byte[8192];
            Set<String> addedPaths = new HashSet<>();
            for (int i = 0; i < files.size(); i++) {
                ChapterTextFile textFile = files.get(i);
                if (!addedPaths.add(textFile.relativePath)) continue;
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
            }
        }
    }

    private int extractChapterTextArchive(File archive) throws Exception {
        File baseDir = databaseHelper.resolveChapterTextFile("chapter_text/__base__");
        if (baseDir == null) throw new IllegalStateException("无法定位章节正文目录");
        File realBase = baseDir.getParentFile();
        if (realBase == null) throw new IllegalStateException("无法定位章节正文目录");
        if (!realBase.exists() && !realBase.mkdirs()) throw new IllegalStateException("无法创建章节正文目录");
        String basePath = realBase.getCanonicalPath() + File.separator;
        byte[] buffer = new byte[8192];
        int restored = 0;
        try (ZipInputStream zipInputStream = new ZipInputStream(new FileInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                if (entry.isDirectory()) { zipInputStream.closeEntry(); continue; }
                String entryName = sanitizeChapterTextArchiveEntryName(entry.getName());
                if (entryName == null) { zipInputStream.closeEntry(); continue; }
                File destination = databaseHelper.resolveChapterTextFile(entryName);
                if (destination == null) { zipInputStream.closeEntry(); continue; }
                String destinationPath = destination.getCanonicalPath();
                if (!destinationPath.startsWith(basePath)) { zipInputStream.closeEntry(); continue; }
                File parent = destination.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) { zipInputStream.closeEntry(); continue; }
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

    private List<ChapterTextFile> collectChapterTextFiles(BookRecord book) {
        List<ChapterTextFile> files = new ArrayList<>();
        List<ChapterRecord> chapters = databaseHelper.getChaptersWithExternalStorage(book.id);
        for (ChapterRecord chapter : chapters) {
            if (chapter.bodyTextPath == null || chapter.bodyTextPath.isBlank()) continue;
            String entryName = sanitizeChapterTextArchiveEntryName(chapter.bodyTextPath);
            if (entryName == null) continue;
            File localFile = databaseHelper.resolveChapterTextFile(entryName);
            if (localFile == null || !localFile.exists() || !localFile.isFile()) continue;
            files.add(new ChapterTextFile(entryName, localFile));
        }
        return files;
    }

    // ==================== 远端孤立文件清理 ====================

    private void cleanupRemoteUnreferencedAssetsIfEnabled(StatusListener listener, boolean includeChapterText, boolean includeFiles, boolean includeBackgrounds) {
        if (!settingsStore.isWebDavCleanRemoteOrphansEnabled()) return;
        List<BookRecord> books = databaseHelper.getBooks();
        RemoteCleanupResult result = new RemoteCleanupResult();
        listener.onStatus("清理远端未引用文件...");
        if (includeChapterText) cleanupRemoteChapterText(books, result);
        if (includeFiles) {
            cleanupRemotePlainFileDirectory(webDavClient.backupBaseUrl() + "books/", collectSourceFileNames(books), result);
            cleanupRemotePlainFileDirectory(webDavClient.backupBaseUrl() + "covers/", collectCoverFileNames(books), result);
        }
        if (includeBackgrounds) {
            Set<String> keepBackgrounds = new HashSet<>();
            String bgName = settingsStore.readerBackgroundFileName();
            if (!bgName.isBlank()) keepBackgrounds.add(bgName);
            cleanupRemotePlainFileDirectory(webDavClient.androidSettingsBackgroundsBaseUrl(), keepBackgrounds, result);
        }
        String summary = "远端清理完成，删除 " + result.deleted + " 个";
        if (result.failed > 0) summary += "，失败 " + result.failed + " 个";
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
        cleanupRemoteDirectory(webDavClient.backupBaseUrl() + "chapter_text/", result,
                (remoteUrl, name, directory) -> {
                    if (!directory && "chapter_text.zip".equals(name)) return true;
                    if (!directory && name.matches("book_\\d+\\.zip")) return !keepArchiveNames.contains(name);
                    if (directory && name.matches("book_\\d+")) return !keepLegacyDirectoryNames.contains(name);
                    return false;
                });
    }

    private void cleanupRemotePlainFileDirectory(String remoteDirectoryUrl, Set<String> keepNames, RemoteCleanupResult result) {
        cleanupRemoteDirectory(remoteDirectoryUrl, result,
                (remoteUrl, name, directory) -> !directory && !keepNames.contains(name));
    }

    private void cleanupRemoteDirectory(String remoteDirectoryUrl, RemoteCleanupResult result, RemoteCleanupPredicate predicate) {
        List<String> remoteFiles;
        try { remoteFiles = webDavClient.listFiles(remoteDirectoryUrl); }
        catch (Exception error) { result.failed++; Log.w(TAG, "列出远端目录失败: " + remoteDirectoryUrl, error); return; }
        for (String remoteUrl : remoteFiles) {
            String name = remoteFileName(remoteUrl);
            if (name == null || name.isBlank()) continue;
            boolean directory = remoteUrl.endsWith("/");
            if (!predicate.shouldDelete(remoteUrl, name, directory)) continue;
            deleteRemoteFile(remoteUrl, result);
        }
    }

    private void cleanupKnownRemoteFile(String remoteUrl, RemoteCleanupResult result) {
        try { if (webDavClient.head(remoteUrl).code == 200) deleteRemoteFile(remoteUrl, result); }
        catch (Exception ignored) {}
    }

    private void deleteRemoteFile(String remoteUrl, RemoteCleanupResult result) {
        try { webDavClient.delete(remoteUrl); result.deleted++; }
        catch (Exception error) { result.failed++; Log.w(TAG, "删除远端残留失败: " + remoteUrl, error); }
    }

    private Set<String> collectSourceFileNames(List<BookRecord> books) {
        Set<String> names = new HashSet<>();
        for (BookRecord book : books) {
            if (book.localPath != null && !book.localPath.isBlank()) names.add(new File(book.localPath).getName());
        }
        return names;
    }

    private Set<String> collectCoverFileNames(List<BookRecord> books) {
        Set<String> names = new HashSet<>();
        for (BookRecord book : books) {
            if (book.coverPath != null && !book.coverPath.isBlank()) names.add(new File(book.coverPath).getName());
        }
        return names;
    }

    private String chapterTextArchiveRemotePath(long bookId) {
        return webDavClient.backupBaseUrl() + "chapter_text/" + chapterTextArchiveFileName(bookId);
    }

    private String chapterTextArchiveFileName(long bookId) {
        return "book_" + bookId + ".zip";
    }

    private String remoteFileName(String remoteUrl) {
        if (remoteUrl == null || remoteUrl.isBlank()) return null;
        String value = remoteUrl;
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        int index = value.lastIndexOf('/');
        String name = index >= 0 ? value.substring(index + 1) : value;
        try { return URLDecoder.decode(name, StandardCharsets.UTF_8.name()); }
        catch (Exception ignored) { return name; }
    }

    private String sanitizeChapterTextArchiveEntryName(String value) {
        if (value == null || value.isBlank() || value.startsWith("/") || value.contains("\\") || value.contains(":")) return null;
        for (String segment : value.split("/")) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) return null;
        }
        return value;
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.US, "%.2f MB", mb);
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }

    // ==================== 范围检查 ====================

    private void ensureAnySyncScopeSelected() {
        if (shouldSyncDatabaseSnapshot() || shouldSyncSettingsSnapshot()
                || settingsStore.isWebDavSyncFilesEnabled() || settingsStore.isWebDavSyncBackgroundsEnabled()) return;
        throw new IllegalStateException("请先选择至少一种同步内容");
    }

    private void ensureRestoreScopeSelected() {
        if (shouldSyncDatabaseSnapshot() || shouldSyncSettingsSnapshot()) return;
        throw new IllegalStateException("当前未包含书架、界面、主题或背景快照，无法执行恢复");
    }

    private boolean shouldSyncDatabaseSnapshot() {
        return settingsStore.isWebDavSyncBookshelfEnabled() || settingsStore.isWebDavSyncThemesEnabled();
    }

    private boolean shouldSyncSettingsSnapshot() {
        return settingsStore.isWebDavSyncUiSettingsEnabled() || settingsStore.isWebDavSyncThemesEnabled()
                || settingsStore.isWebDavSyncBackgroundsEnabled();
    }

    private void cleanupLocalTempCache() {
        File cacheDir = context.getCacheDir();
        if (cacheDir == null) return;
        deletePathRecursively(new File(cacheDir, "backup"));
        deletePathRecursively(new File(cacheDir, "backup_restore"));
        File[] files = cacheDir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isFile() && file.getName().startsWith("restore_")) {
                file.delete();
            }
        }
    }

    private void deletePathRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deletePathRecursively(child);
                }
            }
        }
        file.delete();
    }

    // ==================== 接口与内部类 ====================

    public interface StatusListener {
        void onStatus(String status);
        default void onProgress(int current, int total) {}
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
