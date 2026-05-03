package com.metahumanz.pacilread.storage;

import android.content.Context;
import android.util.Log;

import com.metahumanz.pacilread.model.BookRecord;
import com.metahumanz.pacilread.model.BookmarkRecord;
import com.metahumanz.pacilread.model.ChapterRecord;
import com.metahumanz.pacilread.model.ReadingTimeEntryRecord;
import com.metahumanz.pacilread.model.ReaderThemeRecord;
import com.metahumanz.pacilread.model.ReplacementRuleRecord;

import org.json.JSONArray;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/**
 * SQLite (reader.db) → JSON 一次性迁移。
 * 迁移完成后将 reader.db 重命名为 reader.db.v7.backup，后续不再使用。
 */
public class JsonDatabaseMigrator {
    private static final String TAG = "JsonDatabaseMigrator";
    private static final String MIGRATION_MARKER = ".migrated_v8";

    private final Context appContext;

    public JsonDatabaseMigrator(Context context) {
        this.appContext = context.getApplicationContext();
    }

    /**
     * @return true 表示需要迁移（SQLite 存在且迁移标记不存在）
     * 注意：不能检查 books.json 是否存在，因为上次迁移可能在 books 后 chapters 前失败，
     * books.json 已存在但数据不完整，必须重试。
     */
    public boolean needsMigration() {
        File dbFile = appContext.getDatabasePath("reader.db");
        File marker = new File(new File(appContext.getFilesDir(), "database"), MIGRATION_MARKER);
        return dbFile.exists() && !marker.exists();
    }

    /**
     * 执行迁移。返回 true 表示成功。
     */
    public boolean migrate() {
        try {
            Log.i(TAG, "开始从 SQLite 迁移到 JSON...");
            ReaderDatabaseHelper dbHelper = ReaderDatabaseHelper.getInstance(appContext);

            // 确保 JSON 目录存在
            File jsonDir = new File(appContext.getFilesDir(), "database");
            if (!jsonDir.exists()) {
                jsonDir.mkdirs();
            }

            // 1. 迁移 books
            Log.i(TAG, "迁移 books...");
            List<BookRecord> books = dbHelper.getBooks();
            JSONArray booksJson = new JSONArray();
            for (BookRecord book : books) {
                if (book.createdAt == 0) book.createdAt = book.lastReadAt;
                if (book.updatedAt == 0) book.updatedAt = book.lastReadAt;
                booksJson.put(book.toJson());
            }
            writeJsonFile(new File(jsonDir, "books.json"), booksJson);

            // 2. 迁移 chapters（需要在迁移前处理 body_text_storage='db' 的章节）
            Log.i(TAG, "迁移 chapters...");
            JSONArray chaptersJson = new JSONArray();
            for (BookRecord book : books) {
                List<ChapterRecord> chapters = dbHelper.getChapters(book.id, true);
                for (ChapterRecord chapter : chapters) {
                    // 如果正文还在数据库中，导出到外置文件
                    if ("db".equals(chapter.bodyTextStorage) || chapter.bodyTextStorage == null) {
                        String bodyText = chapter.bodyText;
                        if (bodyText != null && !bodyText.isEmpty()) {
                            try {
                                writeChapterTextToFile(book.id, chapter.id, bodyText);
                                chapter.bodyTextPath = buildChapterTextRelativePath(book.id, chapter.id);
                                chapter.bodyTextStorage = "file_gzip";
                                chapter.bodyTextSize = bodyText.getBytes(StandardCharsets.UTF_8).length;
                            } catch (Exception e) {
                                Log.w(TAG, "章节 " + chapter.id + " 正文导出失败, 保留 DB 存储", e);
                                chapter.bodyTextStorage = "db";
                            }
                        }
                        chapter.bodyText = "";
                    }
                    // 清空 bodyHtml（JSON 不再存储）
                    chapter.bodyHtml = "";
                    chaptersJson.put(chapter.toJson());
                }
            }
            writeJsonFile(new File(jsonDir, "chapters.json"), chaptersJson);

            // 3. 迁移 replacement_rules
            Log.i(TAG, "迁移 rules...");
            JSONArray rulesJson = new JSONArray();
            for (BookRecord book : books) {
                List<ReplacementRuleRecord> rules = dbHelper.getReplacementRules(book.id);
                for (ReplacementRuleRecord rule : rules) {
                    rulesJson.put(rule.toJson());
                }
            }
            writeJsonFile(new File(jsonDir, "rules.json"), rulesJson);

            // 4. 迁移 custom_themes
            Log.i(TAG, "迁移 themes...");
            List<ReaderThemeRecord> themes = dbHelper.getCustomThemes();
            JSONArray themesJson = new JSONArray();
            for (ReaderThemeRecord theme : themes) {
                themesJson.put(theme.toJson());
            }
            writeJsonFile(new File(jsonDir, "themes.json"), themesJson);

            // 5. 迁移 bookmarks
            Log.i(TAG, "迁移 bookmarks...");
            List<BookmarkRecord> bookmarks = dbHelper.getBookmarks();
            JSONArray bookmarksJson = new JSONArray();
            for (BookmarkRecord bookmark : bookmarks) {
                bookmarksJson.put(bookmark.toJson());
            }
            writeJsonFile(new File(jsonDir, "bookmarks.json"), bookmarksJson);

            // 6. 迁移 reading_stats
            Log.i(TAG, "迁移 reading_stats...");
            List<ReadingTimeEntryRecord> stats = dbHelper.getReadingStatsRowsForSync("");
            // 如果空列表返回所有数据，尝试用 legacy device ID
            if (stats.isEmpty()) {
                stats = dbHelper.getReadingStatsRowsForSync(com.metahumanz.pacilread.stats.ReadingStatsUtils.LEGACY_DEVICE_ID);
            }
            JSONArray statsJson = new JSONArray();
            for (ReadingTimeEntryRecord entry : stats) {
                statsJson.put(entry.toJson());
            }
            writeJsonFile(new File(jsonDir, "reading_stats.json"), statsJson);

            // 7. 重命名旧数据库（保留作为回滚保险）
            File dbFile = appContext.getDatabasePath("reader.db");
            File backupFile = new File(dbFile.getParentFile(), "reader.db.v7.backup");
            // 删除旧备份（如果存在）
            if (backupFile.exists()) {
                backupFile.delete();
            }
            // 关闭数据库连接，然后重命名
            dbHelper.close();
            if (!dbFile.renameTo(backupFile)) {
                Log.w(TAG, "重命名 reader.db 失败，但 JSON 数据已写入，继续使用 JSON");
            }
            // 清理附属文件
            new File(dbFile.getParentFile(), "reader.db-wal").delete();
            new File(dbFile.getParentFile(), "reader.db-shm").delete();
            new File(dbFile.getParentFile(), "reader.db-journal").delete();

            // 8. 写入迁移成功标记
            writeMigrationMarker(jsonDir);

            Log.i(TAG, "SQLite → JSON 迁移完成！books=" + books.size() +
                    " chapters=" + chaptersJson.length() +
                    " rules=" + rulesJson.length() +
                    " themes=" + themes.size() +
                    " bookmarks=" + bookmarks.size() +
                    " stats=" + stats.size());
            return true;

        } catch (Exception e) {
            Log.e(TAG, "迁移失败", e);
            return false;
        }
    }

    private void writeJsonFile(File file, JSONArray data) throws Exception {
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (java.io.FileWriter writer = new java.io.FileWriter(tmp)) {
            writer.write(data.toString(2));
        }
        file.delete();
        if (!tmp.renameTo(file)) {
            throw new IOException("原子写入失败：" + file.getName());
        }
    }

    private void writeMigrationMarker(File jsonDir) {
        try {
            new File(jsonDir, MIGRATION_MARKER).createNewFile();
        } catch (Exception ignored) {}
    }

    private void writeChapterTextToFile(long bookId, long chapterId, String bodyText) throws Exception {
        File parentDir = new File(new File(appContext.getFilesDir(), "chapter_text"), "book_" + bookId);
        if (!parentDir.exists()) {
            parentDir.mkdirs();
        }
        File file = new File(parentDir, "chapter_" + chapterId + ".txt.gz");
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(file);
             GZIPOutputStream gzOut = new GZIPOutputStream(fos)) {
            gzOut.write(bodyText.getBytes(StandardCharsets.UTF_8));
        }
    }

    private String buildChapterTextRelativePath(long bookId, long chapterId) {
        return "chapter_text/book_" + bookId + "/chapter_" + chapterId + ".txt.gz";
    }
}
