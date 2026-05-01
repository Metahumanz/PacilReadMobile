package com.metahumanz.pacilread.storage;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import com.metahumanz.pacilread.importer.EpubChapterParser;
import com.metahumanz.pacilread.importer.TxtChapterParser;
import com.metahumanz.pacilread.model.BookmarkRecord;
import com.metahumanz.pacilread.model.BookRecord;
import com.metahumanz.pacilread.model.ChapterRecord;
import com.metahumanz.pacilread.model.ImportedBook;
import com.metahumanz.pacilread.model.ReadingBookStatRecord;
import com.metahumanz.pacilread.model.ReadingTimeEntryRecord;
import com.metahumanz.pacilread.model.ReaderThemeRecord;
import com.metahumanz.pacilread.model.ReplacementRuleRecord;
import com.metahumanz.pacilread.stats.ReadingStatsUtils;
import com.metahumanz.pacilread.util.CoverImageStore;
import com.metahumanz.pacilread.util.HtmlUtils;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class ReaderDatabaseHelper extends SQLiteOpenHelper {
    private static final String TAG = "ReaderDatabaseHelper";
    private static final String DATABASE_NAME = "reader.db";
    private static final int DATABASE_VERSION = 7;
    private static final String MAINTENANCE_PREFS_NAME = "reader_database_maintenance";
    private static final String KEY_MAINTENANCE_PHASE_CLEANUP_BODY_HTML = "phase_cleanup_body_html";
    private static final String KEY_MAINTENANCE_PHASE_EXPORT_BODY_TEXT = "phase_export_body_text";
    private static final String KEY_MAINTENANCE_PHASE_WAL_CHECKPOINT = "phase_wal_checkpoint";
    private static final String KEY_MAINTENANCE_PHASE_VACUUM = "phase_vacuum";
    private static final String KEY_MAINTENANCE_PHASE_RECOMPRESS_COVERS = "phase_recompress_covers";
    private static final String STORAGE_DB = "db";
    private static final String STORAGE_FILE_GZIP = "file_gzip";
    private static final String CHAPTER_TEXT_DIR = "chapter_text";
    private static final int VACUUM_FREE_PAGE_THRESHOLD = 256;
    private static final String EMPTY_CHAPTER_TEXT_PLACEHOLDER = "章节正文为空或外置正文文件缺失。";
    // 旧版本维护标记（用于升级迁移）
    private static final String KEY_VACUUM_AFTER_BODY_HTML_CLEANUP_LEGACY = "vacuum_after_body_html_cleanup";
    private static final String KEY_RECOMPRESS_COVERS_AFTER_BODY_HTML_CLEANUP_LEGACY = "recompress_covers_after_body_html_cleanup";

    private static ReaderDatabaseHelper instance;

    public static synchronized ReaderDatabaseHelper getInstance(Context context) {
        if (instance == null) {
            instance = new ReaderDatabaseHelper(context.getApplicationContext());
        }
        return instance;
    }

    private final Context appContext;
    private volatile boolean storageMaintenanceRunning = false;

    private ReaderDatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
        this.appContext = context.getApplicationContext();
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        createAllTables(db);
        ensureSchema(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        ensureSchema(db);
        if (oldVersion < 6) {
            // 不再在升级事务中同步清空 body_html，改为标记给后台维护阶段执行，
            // 避免首次打开应用时阻塞数据库升级。
            markStorageSlimmingMaintenancePending();
        }
        if (oldVersion < 7) {
            // v7 新增：标记 body_text 外置导出维护阶段
            maintenancePrefs().edit()
                    .putBoolean(KEY_MAINTENANCE_PHASE_EXPORT_BODY_TEXT, true)
                    .apply();
        }
    }

    @Override
    public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        ensureSchema(db);
        migrateMaintenancePrefsIfNeeded();
        // 维护任务由外部 triggerStorageMaintenance() 延迟触发，
        // 避免在数据库首次打开时抢占 I/O 导致首页加载变慢。
    }

    private void createAllTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS books (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "title TEXT NOT NULL," +
                "author TEXT," +
                "local_path TEXT NOT NULL," +
                "cover_path TEXT," +
                "book_type TEXT NOT NULL DEFAULT 'text'," +
                "reading_stats_key TEXT NOT NULL DEFAULT ''," +
                "progress_index INTEGER NOT NULL DEFAULT 0," +
                "progress_offset INTEGER NOT NULL DEFAULT 0," +
                "last_read_at INTEGER NOT NULL," +
                "pinned INTEGER NOT NULL DEFAULT 0" +
                ")");

        db.execSQL("CREATE TABLE IF NOT EXISTS chapters (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "book_id INTEGER NOT NULL," +
                "title TEXT NOT NULL," +
                "body_html TEXT NOT NULL DEFAULT ''," +
                "body_text TEXT NOT NULL DEFAULT ''," +
                "order_index INTEGER NOT NULL," +
                "body_text_path TEXT," +
                "body_text_storage TEXT NOT NULL DEFAULT 'db'," +
                "body_text_size INTEGER NOT NULL DEFAULT 0," +
                "FOREIGN KEY(book_id) REFERENCES books(id) ON DELETE CASCADE" +
                ")");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_chapters_book_order ON chapters(book_id, order_index)");

        db.execSQL("CREATE TABLE IF NOT EXISTS replacement_rules (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "pattern TEXT NOT NULL," +
                "replacement TEXT NOT NULL," +
                "scope TEXT NOT NULL DEFAULT 'global'," +
                "book_id INTEGER," +
                "is_regex INTEGER NOT NULL DEFAULT 0," +
                "active INTEGER NOT NULL DEFAULT 1" +
                ")");

        db.execSQL("CREATE TABLE IF NOT EXISTS custom_themes (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name TEXT NOT NULL UNIQUE," +
                "config_json TEXT NOT NULL," +
                "updated_at INTEGER NOT NULL" +
                ")");

        db.execSQL("CREATE TABLE IF NOT EXISTS reading_stats (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "date TEXT NOT NULL," +
                "source_device_id TEXT NOT NULL DEFAULT '" + ReadingStatsUtils.LEGACY_DEVICE_ID + "'," +
                "book_identity TEXT NOT NULL DEFAULT '" + ReadingStatsUtils.LEGACY_BOOK_IDENTITY + "'," +
                "book_title TEXT NOT NULL DEFAULT '" + ReadingStatsUtils.LEGACY_BOOK_TITLE + "'," +
                "book_author TEXT NOT NULL DEFAULT ''," +
                "duration_seconds INTEGER NOT NULL DEFAULT 0," +
                "char_count INTEGER NOT NULL DEFAULT 0," +
                "updated_at INTEGER NOT NULL DEFAULT 0" +
                ")");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_reading_stats_date ON reading_stats(date)");

        createBookmarkTable(db);
    }

    private void ensureSchema(SQLiteDatabase db) {
        createAllTables(db);
        createBookmarkTable(db);
        repairLegacyBookLocalPathColumn(db);
        ensureColumn(db, "books", "cover_path", "cover_path TEXT");
        ensureColumn(db, "books", "book_type", "book_type TEXT NOT NULL DEFAULT 'text'");
        ensureColumn(db, "books", "reading_stats_key", "reading_stats_key TEXT NOT NULL DEFAULT ''");
        ensureColumn(db, "books", "progress_index", "progress_index INTEGER NOT NULL DEFAULT 0");
        ensureColumn(db, "books", "progress_offset", "progress_offset INTEGER NOT NULL DEFAULT 0");
        ensureColumn(db, "books", "last_read_at", "last_read_at INTEGER NOT NULL DEFAULT 0");
        ensureColumn(db, "books", "pinned", "pinned INTEGER NOT NULL DEFAULT 0");
        repairLegacyBookLastReadColumn(db);

        ensureColumn(db, "books", "chapter_count", "chapter_count INTEGER NOT NULL DEFAULT 0");
        ensureColumn(db, "books", "current_chapter_title", "current_chapter_title TEXT NOT NULL DEFAULT ''");
        backfillBookshelfSummaryColumns(db);

        ensureColumn(db, "chapters", "body_html", "body_html TEXT NOT NULL DEFAULT ''");
        ensureColumn(db, "chapters", "body_text", "body_text TEXT NOT NULL DEFAULT ''");
        ensureColumn(db, "chapters", "order_index", "order_index INTEGER NOT NULL DEFAULT 0");
        ensureColumn(db, "chapters", "body_text_path", "body_text_path TEXT");
        ensureColumn(db, "chapters", "body_text_storage", "body_text_storage TEXT NOT NULL DEFAULT 'db'");
        ensureColumn(db, "chapters", "body_text_size", "body_text_size INTEGER NOT NULL DEFAULT 0");
        repairLegacyChapterBodyColumns(db);

        ensureColumn(db, "replacement_rules", "scope", "scope TEXT NOT NULL DEFAULT 'global'");
        ensureColumn(db, "replacement_rules", "book_id", "book_id INTEGER");
        ensureColumn(db, "replacement_rules", "is_regex", "is_regex INTEGER NOT NULL DEFAULT 0");
        ensureColumn(db, "replacement_rules", "active", "active INTEGER NOT NULL DEFAULT 1");

        ensureColumn(db, "custom_themes", "config_json", "config_json TEXT NOT NULL DEFAULT '{}'");
        ensureColumn(db, "custom_themes", "updated_at", "updated_at INTEGER NOT NULL DEFAULT 0");

        ensureColumn(db, "reading_stats", "source_device_id",
                "source_device_id TEXT NOT NULL DEFAULT '" + ReadingStatsUtils.LEGACY_DEVICE_ID + "'");
        ensureColumn(db, "reading_stats", "book_identity",
                "book_identity TEXT NOT NULL DEFAULT '" + ReadingStatsUtils.LEGACY_BOOK_IDENTITY + "'");
        ensureColumn(db, "reading_stats", "book_title",
                "book_title TEXT NOT NULL DEFAULT '" + ReadingStatsUtils.LEGACY_BOOK_TITLE + "'");
        ensureColumn(db, "reading_stats", "book_author", "book_author TEXT NOT NULL DEFAULT ''");
        ensureColumn(db, "reading_stats", "updated_at", "updated_at INTEGER NOT NULL DEFAULT 0");

        backfillBookStatsKeys(db);
        migrateLegacyReadingStats(db);
        deduplicateReadingStats(db);
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_books_reading_stats_key ON books(reading_stats_key)");
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_reading_stats_identity ON reading_stats(source_device_id, date, book_identity)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_bookmarks_book_identity ON bookmarks(book_identity)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_bookmarks_book_id ON bookmarks(book_id)");
    }

    private void createBookmarkTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS bookmarks (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "uuid TEXT NOT NULL UNIQUE," +
                "book_id INTEGER NOT NULL DEFAULT -1," +
                "book_identity TEXT NOT NULL DEFAULT ''," +
                "book_title TEXT NOT NULL DEFAULT ''," +
                "book_author TEXT NOT NULL DEFAULT ''," +
                "chapter_order_index INTEGER NOT NULL DEFAULT 0," +
                "chapter_title TEXT NOT NULL DEFAULT ''," +
                "chapter_offset INTEGER NOT NULL DEFAULT 0," +
                "progress_percent REAL NOT NULL DEFAULT 0," +
                "summary TEXT NOT NULL DEFAULT ''," +
                "created_at INTEGER NOT NULL DEFAULT 0," +
                "updated_at INTEGER NOT NULL DEFAULT 0" +
                ")");
    }

    private void ensureColumn(SQLiteDatabase db, String tableName, String columnName, String definition) {
        if (hasColumn(db, tableName, columnName)) {
            return;
        }
        db.execSQL("ALTER TABLE " + tableName + " ADD COLUMN " + definition);
    }

    private boolean hasColumn(SQLiteDatabase db, String tableName, String columnName) {
        try (Cursor cursor = db.rawQuery("PRAGMA table_info(" + tableName + ")", null)) {
            while (cursor.moveToNext()) {
                if (columnName.equalsIgnoreCase(cursor.getString(cursor.getColumnIndexOrThrow("name")))) {
                    return true;
                }
            }
        }
        return false;
    }

    private void repairLegacyBookLocalPathColumn(SQLiteDatabase db) {
        boolean hasLocalPath = hasColumn(db, "books", "local_path");
        boolean hasTypoLocalPath = hasColumn(db, "books", "loacl_path");
        boolean hasWin11Path = hasColumn(db, "books", "path");
        if (!hasLocalPath) {
            db.execSQL("ALTER TABLE books ADD COLUMN local_path TEXT NOT NULL DEFAULT ''");
        }
        if (hasWin11Path) {
            db.execSQL("UPDATE books SET local_path = path WHERE local_path IS NULL OR TRIM(local_path) = ''");
        }
        if (hasTypoLocalPath) {
            db.execSQL("UPDATE books SET local_path = loacl_path WHERE local_path IS NULL OR TRIM(local_path) = ''");
        }
    }

    // region 章节外置正文文件 I/O

    private File getChapterTextDir() {
        return new File(appContext.getFilesDir(), CHAPTER_TEXT_DIR);
    }

    private File getChapterTextDir(long bookId) {
        return new File(getChapterTextDir(), "book_" + bookId);
    }

    private File getChapterTextFile(long bookId, long chapterId) {
        return new File(getChapterTextDir(bookId), "chapter_" + chapterId + ".txt.gz");
    }

    private String buildChapterTextRelativePath(long bookId, long chapterId) {
        return "book_" + bookId + "/chapter_" + chapterId + ".txt.gz";
    }

    private void writeChapterTextToFile(long bookId, long chapterId, String text) throws IOException {
        File dir = getChapterTextDir(bookId);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建章节正文目录: " + dir.getAbsolutePath());
        }
        File file = getChapterTextFile(bookId, chapterId);
        try (FileOutputStream fos = new FileOutputStream(file);
             GZIPOutputStream gzos = new GZIPOutputStream(fos);
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(gzos, StandardCharsets.UTF_8))) {
            writer.write(text);
        }
    }

    private String readChapterTextFromFile(long bookId, long chapterId) throws IOException {
        return readChapterTextFromFile(getChapterTextFile(bookId, chapterId));
    }

    private String readChapterTextFromFile(File file) throws IOException {
        if (!file.exists()) {
            return null;
        }
        StringBuilder sb = new StringBuilder((int) Math.min(file.length() * 4, Integer.MAX_VALUE));
        try (FileInputStream fis = new FileInputStream(file);
             GZIPInputStream gzis = new GZIPInputStream(fis);
             BufferedReader reader = new BufferedReader(new InputStreamReader(gzis, StandardCharsets.UTF_8))) {
            char[] buf = new char[8192];
            int n;
            while ((n = reader.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
        }
        return sb.toString();
    }

    /**
     * 解析章节正文：优先从外置 .txt.gz 文件读取，缺失则回退到数据库 body_text 列。
     * bodyTextFromDb 可以为 null（调用方未从 cursor 预读大正文），此时自动分块读取。
     */
    private String resolveChapterText(long bookId, long chapterId, String bodyTextFromDb,
                                       String bodyTextPath, String bodyTextStorage) {
        if (STORAGE_FILE_GZIP.equals(bodyTextStorage) && bodyTextPath != null && !bodyTextPath.isBlank()) {
            try {
                File file = resolveChapterTextFile(bodyTextPath);
                if (file == null) {
                    file = getChapterTextFile(bookId, chapterId);
                }
                String text = readChapterTextFromFile(file);
                if (text != null) {
                    return text;
                }
                Log.w(TAG, "章节外置正文文件缺失 chapter " + chapterId + " path=" + bodyTextPath + ", 回退数据库正文");
            } catch (IOException e) {
                Log.w(TAG, "读取章节外置正文失败 chapter " + chapterId + ", 回退数据库正文", e);
            }
        }
        String fallback = bodyTextFromDb;
        if (fallback == null) {
            // 未从 cursor 预读（避免 CursorWindow 溢出），分块读取数据库正文
            fallback = readBodyTextChunked(getReadableDatabase(), chapterId);
        }
        if (fallback != null && !fallback.isEmpty()) {
            return fallback;
        }
        return EMPTY_CHAPTER_TEXT_PLACEHOLDER;
    }

    private void deleteChapterTextDir(long bookId) {
        File dir = getChapterTextDir(bookId);
        if (dir.exists() && dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    f.delete();
                }
            }
            dir.delete();
        }
    }

    /**
     * 返回指定书籍的所有外置存储章节（body_text_storage='file_gzip'）。
     * 供 WebDAV 备份/恢复使用。
     */
    public synchronized List<ChapterRecord> getChaptersWithExternalStorage(long bookId) {
        List<ChapterRecord> chapters = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(
                "chapters",
                new String[]{"id", "book_id", "body_text_path"},
                "book_id=? AND body_text_storage=? AND body_text_path IS NOT NULL AND TRIM(body_text_path) <> ''",
                new String[]{String.valueOf(bookId), STORAGE_FILE_GZIP},
                null,
                null,
                "order_index ASC"
        )) {
            while (cursor.moveToNext()) {
                ChapterRecord chapter = new ChapterRecord();
                chapter.id = cursor.getLong(cursor.getColumnIndexOrThrow("id"));
                chapter.bookId = cursor.getLong(cursor.getColumnIndexOrThrow("book_id"));
                chapter.bodyTextPath = cursor.getString(cursor.getColumnIndexOrThrow("body_text_path"));
                chapters.add(chapter);
            }
        }
        return chapters;
    }

    /**
     * 根据 body_text_path 相对路径解析为完整的本地文件。
     */
    public File resolveChapterTextFile(String bodyTextPath) {
        if (bodyTextPath == null || bodyTextPath.isBlank()) {
            return null;
        }
        return new File(getChapterTextDir(), bodyTextPath);
    }

    // endregion

    private void repairLegacyChapterBodyColumns(SQLiteDatabase db) {
        if (!hasColumn(db, "chapters", "body")) {
            return;
        }
        // 只在真正的旧行上做一次性 body -> body_text 迁移；不要在每次打开时把已外置的正文重新灌回数据库。
        try (Cursor cursor = db.query(
                "chapters",
                new String[]{"id"},
                "body IS NOT NULL AND TRIM(body) <> '' " +
                        "AND (body_text IS NULL OR TRIM(body_text) = '') " +
                        "AND (body_text_storage IS NULL OR TRIM(body_text_storage) = '' OR body_text_storage = ?)",
                new String[]{STORAGE_DB},
                null,
                null,
                null
        )) {
            while (cursor.moveToNext()) {
                long id = cursor.getLong(0);
                String legacyBody = readColumnChunked(db, "chapters", "body", id);
                if (legacyBody == null || legacyBody.isEmpty()) continue;
                ContentValues values = new ContentValues();
                values.put("body_text", HtmlUtils.stripHtml(legacyBody));
                db.update("chapters", values, "id=?", new String[]{String.valueOf(id)});
            }
        }
    }

    private void clearDeprecatedChapterHtml(SQLiteDatabase db) {
        if (!hasColumn(db, "chapters", "body_html")) {
            return;
        }
        db.execSQL("UPDATE chapters SET body_html = '' WHERE body_html IS NOT NULL AND body_html <> ''");
    }

    private void repairLegacyBookLastReadColumn(SQLiteDatabase db) {
        if (!hasColumn(db, "books", "last_read")) {
            return;
        }
        db.execSQL("UPDATE books SET last_read_at = COALESCE(CAST(strftime('%s', last_read) AS INTEGER) * 1000, last_read_at) " +
                "WHERE (last_read_at IS NULL OR last_read_at <= 0) AND last_read IS NOT NULL AND TRIM(last_read) <> ''");
    }

    public synchronized long insertImportedBook(ImportedBook importedBook) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues bookValues = new ContentValues();
            bookValues.put("title", importedBook.title);
            bookValues.put("author", importedBook.author);
            bookValues.put("local_path", importedBook.storedPath);
            if (importedBook.coverPath != null && !importedBook.coverPath.isBlank()) {
                bookValues.put("cover_path", importedBook.coverPath);
            }
            bookValues.put("book_type", importedBook.bookType == null ? "text" : importedBook.bookType);
            bookValues.put("reading_stats_key", buildReadingStatsKey(importedBook.title, importedBook.author));
            bookValues.put("progress_index", 0);
            bookValues.put("progress_offset", 0);
            bookValues.put("last_read_at", System.currentTimeMillis());
            bookValues.put("pinned", 0);
            bookValues.put("chapter_count", importedBook.chapters.size());
            String firstChapterTitle = importedBook.chapters.isEmpty() ? "" :
                    (importedBook.chapters.get(0).title == null ? "" : importedBook.chapters.get(0).title);
            bookValues.put("current_chapter_title", firstChapterTitle);
            long bookId = db.insertOrThrow("books", null, bookValues);

            boolean hasLegacyBodyColumn = hasColumn(db, "chapters", "body");
            for (ImportedBook.ChapterSeed seed : importedBook.chapters) {
                ContentValues chapterValues = new ContentValues();
                chapterValues.put("book_id", bookId);
                chapterValues.put("title", seed.title);
                if (hasLegacyBodyColumn) {
                    chapterValues.put("body", "");
                }
                chapterValues.put("body_html", "");
                String bodyText = seed.bodyText == null ? "" : seed.bodyText;
                chapterValues.put("body_text", "");
                chapterValues.put("order_index", seed.orderIndex);
                chapterValues.put("body_text_storage", STORAGE_FILE_GZIP);
                long chapterId = db.insertOrThrow("chapters", null, chapterValues);
                if (!bodyText.isEmpty()) {
                    try {
                        writeChapterTextToFile(bookId, chapterId, bodyText);
                        ContentValues updateValues = new ContentValues();
                        updateValues.put("body_text_path", buildChapterTextRelativePath(bookId, chapterId));
                        updateValues.put("body_text_size", bodyText.getBytes(StandardCharsets.UTF_8).length);
                        db.update("chapters", updateValues, "id=?", new String[]{String.valueOf(chapterId)});
                    } catch (IOException e) {
                        Log.w(TAG, "写入章节外置正文失败, 回退数据库存储 chapter " + chapterId, e);
                        ContentValues fallbackValues = new ContentValues();
                        fallbackValues.put("body_text", bodyText);
                        fallbackValues.put("body_text_storage", STORAGE_DB);
                        fallbackValues.put("body_text_path", (String) null);
                        fallbackValues.put("body_text_size", 0);
                        db.update("chapters", fallbackValues, "id=?", new String[]{String.valueOf(chapterId)});
                    }
                }
            }

            db.setTransactionSuccessful();
            return bookId;
        } finally {
            db.endTransaction();
        }
    }

    public synchronized List<BookRecord> getBooks() {
        List<BookRecord> books = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(
                "books", null, null, null, null, null,
                "pinned DESC, last_read_at DESC, title COLLATE NOCASE ASC"
        )) {
            while (cursor.moveToNext()) {
                BookRecord book = readBook(cursor);
                if (book.chapterCount > 0) {
                    book.progressIndex = Math.max(0, Math.min(book.progressIndex, book.chapterCount - 1));
                } else {
                    book.progressIndex = 0;
                    book.currentChapterTitle = "";
                }
                books.add(book);
            }
        }
        return books;
    }

    public synchronized BookRecord getBook(long bookId) {
        try (Cursor cursor = getReadableDatabase().query(
                "books",
                null,
                "id=?",
                new String[]{String.valueOf(bookId)},
                null,
                null,
                null,
                "1"
        )) {
            if (cursor.moveToFirst()) {
                return readBook(cursor);
            }
        }
        return null;
    }

    public synchronized List<ChapterRecord> getChapters(long bookId) {
        return getChapters(bookId, false);
    }

    public synchronized List<ChapterRecord> getChapters(long bookId, boolean includeContent) {
        List<ChapterRecord> chapters = new ArrayList<>();
        // includeContent=true 时也不读 body_text 列，避免超长正文超出 CursorWindow
        String[] columns = includeContent
                ? new String[]{"id", "book_id", "title", "order_index",
                        "body_text_path", "body_text_storage", "body_text_size"}
                : new String[]{"id", "book_id", "title", "order_index"};
        try (Cursor cursor = getReadableDatabase().query(
                "chapters",
                columns,
                "book_id=?",
                new String[]{String.valueOf(bookId)},
                null,
                null,
                "order_index ASC"
        )) {
            while (cursor.moveToNext()) {
                ChapterRecord chapter = new ChapterRecord();
                chapter.id = cursor.getLong(cursor.getColumnIndexOrThrow("id"));
                chapter.bookId = cursor.getLong(cursor.getColumnIndexOrThrow("book_id"));
                chapter.title = cursor.getString(cursor.getColumnIndexOrThrow("title"));
                if (includeContent) {
                    chapter.bodyHtml = "";
                    readChapterStorageFields(cursor, chapter);
                    chapter.bodyText = resolveChapterText(chapter.bookId, chapter.id,
                            null, chapter.bodyTextPath, chapter.bodyTextStorage);
                }
                chapter.orderIndex = cursor.getInt(cursor.getColumnIndexOrThrow("order_index"));
                chapters.add(chapter);
            }
        }
        return chapters;
    }

    public synchronized ChapterRecord getChapterContent(long chapterId) {
        // 不直接从 cursor 读 body_text——超长正文会超出 CursorWindow 限制
        try (Cursor cursor = getReadableDatabase().query(
                "chapters",
                new String[]{"book_id", "body_text_path", "body_text_storage", "body_text_size"},
                "id=?",
                new String[]{String.valueOf(chapterId)},
                null,
                null,
                null,
                "1"
        )) {
            if (cursor.moveToFirst()) {
                ChapterRecord chapter = new ChapterRecord();
                chapter.id = chapterId;
                chapter.bookId = cursor.getLong(cursor.getColumnIndexOrThrow("book_id"));
                chapter.bodyHtml = "";
                readChapterStorageFields(cursor, chapter);
                chapter.bodyText = resolveChapterText(chapter.bookId, chapter.id,
                        null, chapter.bodyTextPath, chapter.bodyTextStorage);
                return chapter;
            }
        }
        return null;
    }

    private void readChapterStorageFields(Cursor cursor, ChapterRecord chapter) {
        int pathIndex = cursor.getColumnIndex("body_text_path");
        chapter.bodyTextPath = pathIndex >= 0 ? cursor.getString(pathIndex) : null;
        int storageIndex = cursor.getColumnIndex("body_text_storage");
        chapter.bodyTextStorage = storageIndex >= 0 ? cursor.getString(storageIndex) : STORAGE_DB;
        int sizeIndex = cursor.getColumnIndex("body_text_size");
        chapter.bodyTextSize = sizeIndex >= 0 ? cursor.getInt(sizeIndex) : 0;
    }

    public synchronized void updateBookInfo(long bookId, String title, String author) {
        ContentValues values = new ContentValues();
        values.put("title", title);
        values.put("author", author);
        getWritableDatabase().update("books", values, "id=?", new String[]{String.valueOf(bookId)});
    }

    public synchronized void deleteBook(long bookId) {
        BookRecord book = getBook(bookId);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("chapters", "book_id=?", new String[]{String.valueOf(bookId)});
            db.delete("replacement_rules", "book_id=?", new String[]{String.valueOf(bookId)});
            if (book != null && book.readingStatsKey != null && !book.readingStatsKey.isBlank()) {
                db.delete("bookmarks", "book_id=? OR book_identity=?", new String[]{String.valueOf(bookId), book.readingStatsKey});
            } else {
                db.delete("bookmarks", "book_id=?", new String[]{String.valueOf(bookId)});
            }
            db.delete("books", "id=?", new String[]{String.valueOf(bookId)});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        if (book != null) {
            deleteFileIfExists(book.localPath);
            deleteFileIfExists(book.coverPath);
        }
        deleteChapterTextDir(bookId);
    }

    public synchronized void setPinned(long bookId, boolean pinned) {
        ContentValues values = new ContentValues();
        values.put("pinned", pinned ? 1 : 0);
        getWritableDatabase().update("books", values, "id=?", new String[]{String.valueOf(bookId)});
    }

    public synchronized void setCoverPath(long bookId, String coverPath) {
        ContentValues values = new ContentValues();
        values.put("cover_path", coverPath);
        getWritableDatabase().update("books", values, "id=?", new String[]{String.valueOf(bookId)});
    }

    public synchronized void updateProgress(long bookId, int chapterIndex, int charOffset) {
        getWritableDatabase().execSQL(
                "UPDATE books SET progress_index = ?, progress_offset = ?, last_read_at = ?, " +
                        "current_chapter_title = COALESCE((" +
                        "SELECT title FROM chapters WHERE chapters.book_id = books.id " +
                        "AND chapters.order_index = ?), '') " +
                        "WHERE id = ?",
                new Object[]{chapterIndex, Math.max(charOffset, 0), System.currentTimeMillis(),
                        chapterIndex, bookId}
        );
    }

    public synchronized boolean isDatabaseHealthyForStartup() {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name IN ('books','chapters')",
                null
        )) {
            if (cursor.moveToFirst()) {
                return cursor.getInt(0) >= 2;
            }
        } catch (Exception error) {
            Log.w(TAG, "Database lightweight startup check failed before auto-open", error);
        }
        return false;
    }

    public synchronized long getMostRecentBookId() {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT id FROM books ORDER BY last_read_at DESC LIMIT 1",
                null
        )) {
            if (cursor.moveToFirst()) {
                return cursor.getLong(0);
            }
        }
        return -1L;
    }

    public synchronized List<ReplacementRuleRecord> getReplacementRules(long bookId) {
        List<ReplacementRuleRecord> rules = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(
                "replacement_rules",
                null,
                "(scope='global' OR (scope='book' AND book_id=?))",
                new String[]{String.valueOf(bookId)},
                null,
                null,
                "scope DESC, id DESC"
        )) {
            while (cursor.moveToNext()) {
                ReplacementRuleRecord record = new ReplacementRuleRecord();
                record.id = cursor.getLong(cursor.getColumnIndexOrThrow("id"));
                record.pattern = cursor.getString(cursor.getColumnIndexOrThrow("pattern"));
                record.replacement = cursor.getString(cursor.getColumnIndexOrThrow("replacement"));
                record.scope = cursor.getString(cursor.getColumnIndexOrThrow("scope"));
                if (!cursor.isNull(cursor.getColumnIndexOrThrow("book_id"))) {
                    record.bookId = cursor.getLong(cursor.getColumnIndexOrThrow("book_id"));
                }
                record.regex = cursor.getInt(cursor.getColumnIndexOrThrow("is_regex")) == 1;
                record.active = cursor.getInt(cursor.getColumnIndexOrThrow("active")) == 1;
                rules.add(record);
            }
        }
        return rules;
    }

    public synchronized void addReplacementRule(String pattern, String replacement, boolean global, long bookId, boolean regex) {
        ContentValues values = new ContentValues();
        values.put("pattern", pattern);
        values.put("replacement", replacement);
        values.put("scope", global ? "global" : "book");
        values.put("book_id", global ? null : bookId);
        values.put("is_regex", regex ? 1 : 0);
        values.put("active", 1);
        getWritableDatabase().insertOrThrow("replacement_rules", null, values);
    }

    public synchronized void toggleReplacementRule(long ruleId, boolean active) {
        ContentValues values = new ContentValues();
        values.put("active", active ? 1 : 0);
        getWritableDatabase().update("replacement_rules", values, "id=?", new String[]{String.valueOf(ruleId)});
    }

    public synchronized void deleteReplacementRule(long ruleId) {
        getWritableDatabase().delete("replacement_rules", "id=?", new String[]{String.valueOf(ruleId)});
    }

    public synchronized List<ReaderThemeRecord> getCustomThemes() {
        List<ReaderThemeRecord> themes = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(
                "custom_themes",
                null,
                null,
                null,
                null,
                null,
                "updated_at DESC, name COLLATE NOCASE ASC"
        )) {
            while (cursor.moveToNext()) {
                ReaderThemeRecord record = new ReaderThemeRecord();
                record.id = cursor.getLong(cursor.getColumnIndexOrThrow("id"));
                record.name = cursor.getString(cursor.getColumnIndexOrThrow("name"));
                record.configJson = cursor.getString(cursor.getColumnIndexOrThrow("config_json"));
                record.updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at"));
                themes.add(record);
            }
        }
        return themes;
    }

    public synchronized void saveCustomTheme(String name, String configJson) {
        ContentValues values = new ContentValues();
        values.put("name", name);
        values.put("config_json", configJson);
        values.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("custom_themes", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized void deleteCustomTheme(long themeId) {
        getWritableDatabase().delete("custom_themes", "id=?", new String[]{String.valueOf(themeId)});
    }

    public synchronized void recordReadingStats(String date, int durationSeconds, int charCount) {
        recordReadingDuration(
                ReadingStatsUtils.LEGACY_DEVICE_ID,
                date,
                ReadingStatsUtils.LEGACY_BOOK_IDENTITY,
                ReadingStatsUtils.LEGACY_BOOK_TITLE,
                "",
                durationSeconds,
                charCount,
                System.currentTimeMillis()
        );
    }

    public synchronized void recordReadingDuration(
            String sourceDeviceId,
            String date,
            String bookIdentity,
            String bookTitle,
            String bookAuthor,
            int durationSeconds,
            int charCount,
            long updatedAt
    ) {
        if (durationSeconds <= 0 && charCount <= 0) {
            return;
        }
        SQLiteDatabase db = getWritableDatabase();
        try (Cursor cursor = db.query(
                "reading_stats",
                new String[]{"id", "duration_seconds", "char_count"},
                "source_device_id=? AND date=? AND book_identity=?",
                new String[]{safeDeviceId(sourceDeviceId), date, safeBookIdentity(bookIdentity)},
                null,
                null,
                null,
                "1"
        )) {
            if (cursor.moveToFirst()) {
                ContentValues values = new ContentValues();
                values.put("book_title", ReadingStatsUtils.safeBookTitle(bookTitle));
                values.put("book_author", normalizeAuthor(bookAuthor));
                values.put("duration_seconds", cursor.getInt(1) + Math.max(durationSeconds, 0));
                values.put("char_count", cursor.getInt(2) + Math.max(charCount, 0));
                values.put("updated_at", Math.max(updatedAt, 0L));
                db.update("reading_stats", values, "id=?", new String[]{String.valueOf(cursor.getLong(0))});
                return;
            }
        }

        ContentValues values = new ContentValues();
        values.put("date", date);
        values.put("source_device_id", safeDeviceId(sourceDeviceId));
        values.put("book_identity", safeBookIdentity(bookIdentity));
        values.put("book_title", ReadingStatsUtils.safeBookTitle(bookTitle));
        values.put("book_author", normalizeAuthor(bookAuthor));
        values.put("duration_seconds", Math.max(durationSeconds, 0));
        values.put("char_count", Math.max(charCount, 0));
        values.put("updated_at", Math.max(updatedAt, 0L));
        db.insert("reading_stats", null, values);
    }

    public synchronized int getReadingDurationSeconds(String startDate, String endDate, String bookIdentity) {
        String selection = "date>=? AND date<=?";
        List<String> args = new ArrayList<>();
        args.add(startDate);
        args.add(endDate);
        if (bookIdentity != null && !bookIdentity.isBlank()) {
            selection += " AND book_identity=?";
            args.add(bookIdentity);
        }
        try (Cursor cursor = getReadableDatabase().query(
                "reading_stats",
                new String[]{"COALESCE(SUM(duration_seconds), 0)"},
                selection,
                args.toArray(new String[0]),
                null,
                null,
                null
        )) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    public synchronized List<ReadingBookStatRecord> getReadingBookStats(String startDate, String endDate) {
        List<ReadingBookStatRecord> results = new ArrayList<>();
        String query = "SELECT rs.book_identity, " +
                "COALESCE((SELECT s.book_title FROM reading_stats s " +
                " WHERE s.book_identity = rs.book_identity ORDER BY s.updated_at DESC, s.id DESC LIMIT 1), '') AS latest_title, " +
                "COALESCE((SELECT s.book_author FROM reading_stats s " +
                " WHERE s.book_identity = rs.book_identity ORDER BY s.updated_at DESC, s.id DESC LIMIT 1), '') AS latest_author, " +
                "COALESCE((SELECT b.id FROM books b WHERE b.reading_stats_key = rs.book_identity LIMIT 1), -1) AS local_book_id, " +
                "COALESCE((SELECT b.cover_path FROM books b WHERE b.reading_stats_key = rs.book_identity LIMIT 1), '') AS local_cover_path, " +
                "SUM(rs.duration_seconds) AS total_duration_seconds, " +
                "MAX(rs.updated_at) AS latest_updated_at " +
                "FROM reading_stats rs " +
                "WHERE rs.date>=? AND rs.date<=? AND rs.book_identity<>? " +
                "GROUP BY rs.book_identity " +
                "ORDER BY total_duration_seconds DESC, latest_updated_at DESC, latest_title COLLATE NOCASE ASC";
        try (Cursor cursor = getReadableDatabase().rawQuery(query, new String[]{
                startDate,
                endDate,
                ReadingStatsUtils.LEGACY_BOOK_IDENTITY
        })) {
            while (cursor.moveToNext()) {
                ReadingBookStatRecord record = new ReadingBookStatRecord();
                record.bookIdentity = cursor.getString(cursor.getColumnIndexOrThrow("book_identity"));
                record.bookTitle = cursor.getString(cursor.getColumnIndexOrThrow("latest_title"));
                record.bookAuthor = cursor.getString(cursor.getColumnIndexOrThrow("latest_author"));
                record.localBookId = cursor.getLong(cursor.getColumnIndexOrThrow("local_book_id"));
                String coverPath = cursor.getString(cursor.getColumnIndexOrThrow("local_cover_path"));
                record.localCoverPath = coverPath == null || coverPath.isBlank() ? null : coverPath;
                record.totalDurationSeconds = cursor.getInt(cursor.getColumnIndexOrThrow("total_duration_seconds"));
                record.updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("latest_updated_at"));
                results.add(record);
            }
        }
        return results;
    }

    public synchronized List<ReadingTimeEntryRecord> getReadingStatsRowsForSync(String sourceDeviceId) {
        List<ReadingTimeEntryRecord> rows = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(
                "reading_stats",
                null,
                "source_device_id=?",
                new String[]{safeDeviceId(sourceDeviceId)},
                null,
                null,
                "date ASC, book_identity ASC"
        )) {
            while (cursor.moveToNext()) {
                rows.add(readReadingTimeEntry(cursor));
            }
        }
        return rows;
    }

    public synchronized void mergeReadingStatsRows(List<ReadingTimeEntryRecord> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (ReadingTimeEntryRecord row : rows) {
                mergeReadingStatsRow(db, row);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public synchronized boolean hasAnyReadingStats() {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM reading_stats", null)) {
            return cursor.moveToFirst() && cursor.getLong(0) > 0L;
        }
    }

    public synchronized void clearReadingStats() {
        getWritableDatabase().delete("reading_stats", null, null);
    }

    public synchronized BookRecord findBookByReadingStatsKey(String readingStatsKey) {
        if (readingStatsKey == null || readingStatsKey.isBlank()) {
            return null;
        }
        try (Cursor cursor = getReadableDatabase().query(
                "books",
                null,
                "reading_stats_key=?",
                new String[]{readingStatsKey},
                null,
                null,
                null,
                "1"
        )) {
            return cursor.moveToFirst() ? readBook(cursor) : null;
        }
    }

    public synchronized int getChapterCount(long bookId) {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM chapters WHERE book_id=?",
                new String[]{String.valueOf(bookId)}
        )) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    public synchronized long upsertBookmark(BookmarkRecord bookmark) {
        if (bookmark == null || bookmark.uuid == null || bookmark.uuid.isBlank()) {
            throw new IllegalArgumentException("书签缺少唯一标识");
        }
        SQLiteDatabase db = getWritableDatabase();
        return upsertBookmarkRow(db, bookmark);
    }

    public synchronized List<BookmarkRecord> getBookmarks() {
        List<BookmarkRecord> bookmarks = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(
                "bookmarks",
                null,
                null,
                null,
                null,
                null,
                "updated_at DESC, created_at DESC, id DESC"
        )) {
            while (cursor.moveToNext()) {
                bookmarks.add(readBookmark(cursor));
            }
        }
        return bookmarks;
    }

    public synchronized List<BookmarkRecord> getBookmarksForBook(long bookId, String bookIdentity) {
        List<BookmarkRecord> bookmarks = new ArrayList<>();
        String selection = "book_id=?";
        List<String> args = new ArrayList<>();
        args.add(String.valueOf(bookId));
        if (bookIdentity != null && !bookIdentity.isBlank()) {
            selection += " OR book_identity=?";
            args.add(bookIdentity);
        }
        try (Cursor cursor = getReadableDatabase().query(
                "bookmarks",
                null,
                selection,
                args.toArray(new String[0]),
                null,
                null,
                "chapter_order_index ASC, chapter_offset ASC, created_at ASC"
        )) {
            while (cursor.moveToNext()) {
                bookmarks.add(readBookmark(cursor));
            }
        }
        return bookmarks;
    }

    public synchronized void deleteBookmark(long bookmarkId) {
        getWritableDatabase().delete("bookmarks", "id=?", new String[]{String.valueOf(bookmarkId)});
    }

    private SharedPreferences maintenancePrefs() {
        return appContext.getSharedPreferences(MAINTENANCE_PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * 由外部（如 BookshelfActivity）在首屏加载完成后调用，延迟触发维护任务。
     * 可安全重复调用——如果没有待处理任务或维护已在运行则为空操作。
     */
    public void triggerStorageMaintenance() {
        migrateMaintenancePrefsIfNeeded();
        schedulePendingStorageMaintenance();
    }

    public interface MaintenanceProgressListener {
        void onPhaseStart(String phaseName);
        void onPhaseDone(String phaseName);
        void onAllDone();
        void onError(String errorMessage);
    }

    public String getPendingMaintenanceSummary() {
        migrateMaintenancePrefsIfNeeded();
        SharedPreferences prefs = maintenancePrefs();
        int pending = 0;
        if (prefs.getBoolean(KEY_MAINTENANCE_PHASE_CLEANUP_BODY_HTML, false)) pending++;
        if (prefs.getBoolean(KEY_MAINTENANCE_PHASE_RECOMPRESS_COVERS, false)) pending++;
        // 正文导出：不依赖标记位，直接查数据库
        if (hasBodyTextExportWork(prefs)) {
            pending++;
        }
        // 数据库瘦身：小量 freelist 属于 SQLite 正常波动，超过阈值才提示优化。
        if (hasVacuumWork(prefs)) {
            pending++;
        }
        if (countMissingChapterTextFiles(getReadableDatabase()) > 0) {
            pending++;
        }
        if (pending == 0) return "当前无需优化";
        return "待处理 " + pending + " 项维护任务";
    }

    public boolean hasPendingMaintenanceWork() {
        migrateMaintenancePrefsIfNeeded();
        SharedPreferences prefs = maintenancePrefs();
        return prefs.getBoolean(KEY_MAINTENANCE_PHASE_CLEANUP_BODY_HTML, false)
                || prefs.getBoolean(KEY_MAINTENANCE_PHASE_RECOMPRESS_COVERS, false)
                || hasBodyTextExportWork(prefs)
                || hasVacuumWork(prefs)
                || countMissingChapterTextFiles(getReadableDatabase()) > 0;
    }

    private boolean hasBodyTextExportWork(SharedPreferences prefs) {
        return prefs.getBoolean(KEY_MAINTENANCE_PHASE_EXPORT_BODY_TEXT, false)
                || countChaptersNeedingExport() > 0;
    }

    private boolean hasVacuumWork(SharedPreferences prefs) {
        return prefs.getBoolean(KEY_MAINTENANCE_PHASE_WAL_CHECKPOINT, false)
                || prefs.getBoolean(KEY_MAINTENANCE_PHASE_VACUUM, false)
                || countFreePages() >= VACUUM_FREE_PAGE_THRESHOLD;
    }

    private int countChaptersNeedingExport() {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM chapters WHERE body_text IS NOT NULL AND TRIM(body_text) <> ''" +
                        " AND (body_text_storage IS NULL OR body_text_storage = ?" +
                        " OR (body_text_storage = ? AND body_text_path IS NOT NULL))",
                new String[]{STORAGE_DB, STORAGE_FILE_GZIP})) {
            if (cursor.moveToFirst()) return cursor.getInt(0);
        } catch (Exception ignored) {
        }
        return 0;
    }

    private int countFreePages() {
        try (Cursor cursor = getReadableDatabase().rawQuery("PRAGMA freelist_count", null)) {
            if (cursor.moveToFirst()) return cursor.getInt(0);
        } catch (Exception ignored) {
        }
        return 0;
    }

    public void runStorageMaintenanceWithProgress(MaintenanceProgressListener listener) {
        migrateMaintenancePrefsIfNeeded();
        SharedPreferences prefs = maintenancePrefs();

        if (prefs.getBoolean(KEY_MAINTENANCE_PHASE_CLEANUP_BODY_HTML, false)) {
            try {
                if (listener != null) listener.onPhaseStart("清理废弃数据");
                clearDeprecatedChapterHtml(getWritableDatabase());
                prefs.edit().putBoolean(KEY_MAINTENANCE_PHASE_CLEANUP_BODY_HTML, false).apply();
                if (listener != null) listener.onPhaseDone("清理废弃数据");
            } catch (Exception error) {
                Log.w(TAG, "Body HTML cleanup phase failed", error);
                if (listener != null) listener.onError("清理废弃数据失败: " + error.getMessage());
                return;
            }
        }

        if (countMissingChapterTextFiles(getReadableDatabase()) > 0) {
            try {
                if (listener != null) listener.onPhaseStart("修复章节正文");
                repairMissingExternalChapterTextFiles();
                if (listener != null) listener.onPhaseDone("修复章节正文");
            } catch (Exception error) {
                Log.w(TAG, "Missing chapter text repair phase failed", error);
                if (listener != null) listener.onError("修复章节正文失败: " + error.getMessage());
                return;
            }
        }

        if (hasBodyTextExportWork(prefs)) {
            try {
                if (listener != null) listener.onPhaseStart("导出章节正文");
                exportBodyTextToFiles();
                // 导出后 DB 有大片空闲页，必须重新跑 checkpoint + VACUUM 才能回收
                prefs.edit()
                        .putBoolean(KEY_MAINTENANCE_PHASE_EXPORT_BODY_TEXT, false)
                        .putBoolean(KEY_MAINTENANCE_PHASE_WAL_CHECKPOINT, true)
                        .putBoolean(KEY_MAINTENANCE_PHASE_VACUUM, true)
                        .apply();
                if (listener != null) listener.onPhaseDone("导出章节正文");
            } catch (Exception error) {
                Log.w(TAG, "Body text export phase failed", error);
                if (listener != null) listener.onError("导出章节正文失败: " + error.getMessage());
                return;
            }
        }

        if (hasVacuumWork(prefs)) {
            // checkpoint：WAL 已空时可能报错，非致命，失败后直接继续 VACUUM
            try {
                if (listener != null) listener.onPhaseStart("整理数据库日志");
                synchronized (this) {
                    getWritableDatabase().execSQL("PRAGMA wal_checkpoint(TRUNCATE)");
                }
                prefs.edit().putBoolean(KEY_MAINTENANCE_PHASE_WAL_CHECKPOINT, false).apply();
                if (listener != null) listener.onPhaseDone("整理数据库日志");
            } catch (Exception error) {
                Log.w(TAG, "WAL checkpoint failed, continuing to VACUUM", error);
            }

            try {
                if (listener != null) listener.onPhaseStart("压缩数据库");
                synchronized (this) {
                    SQLiteDatabase db = getWritableDatabase();
                    try {
                        db.execSQL("PRAGMA wal_checkpoint(TRUNCATE)");
                    } catch (Exception ignored) {
                    }
                    db.execSQL("VACUUM");
                }
                prefs.edit()
                        .putBoolean(KEY_MAINTENANCE_PHASE_WAL_CHECKPOINT, false)
                        .putBoolean(KEY_MAINTENANCE_PHASE_VACUUM, false)
                        .apply();
                if (listener != null) listener.onPhaseDone("压缩数据库");
            } catch (Exception error) {
                Log.w(TAG, "Vacuum phase failed", error);
                if (listener != null) listener.onError("压缩数据库失败: " + error.getMessage());
                return;
            }
        }

        if (prefs.getBoolean(KEY_MAINTENANCE_PHASE_RECOMPRESS_COVERS, false)) {
            try {
                if (listener != null) listener.onPhaseStart("优化封面图片");
                recompressExistingCovers();
                prefs.edit().putBoolean(KEY_MAINTENANCE_PHASE_RECOMPRESS_COVERS, false).apply();
                if (listener != null) listener.onPhaseDone("优化封面图片");
            } catch (Exception error) {
                Log.w(TAG, "Cover recompression phase failed", error);
                if (listener != null) listener.onError("优化封面图片失败: " + error.getMessage());
                return;
            }
        }

        if (listener != null) listener.onAllDone();
    }

    /**
     * 将旧版本的双标记迁移到新的四阶段标记。
     * 仅在旧标记存在且新标记尚未初始化时执行一次。
     */
    private void migrateMaintenancePrefsIfNeeded() {
        SharedPreferences prefs = maintenancePrefs();
        if (prefs.contains(KEY_MAINTENANCE_PHASE_CLEANUP_BODY_HTML)) {
            return;
        }
        boolean legacyVacuum = prefs.getBoolean(KEY_VACUUM_AFTER_BODY_HTML_CLEANUP_LEGACY, false);
        boolean legacyRecompress = prefs.getBoolean(KEY_RECOMPRESS_COVERS_AFTER_BODY_HTML_CLEANUP_LEGACY, false);
        if (legacyVacuum || legacyRecompress) {
            prefs.edit()
                    .putBoolean(KEY_MAINTENANCE_PHASE_CLEANUP_BODY_HTML, legacyVacuum)
                    .putBoolean(KEY_MAINTENANCE_PHASE_WAL_CHECKPOINT, legacyVacuum)
                    .putBoolean(KEY_MAINTENANCE_PHASE_VACUUM, legacyVacuum)
                    .putBoolean(KEY_MAINTENANCE_PHASE_RECOMPRESS_COVERS, legacyRecompress)
                    .remove(KEY_VACUUM_AFTER_BODY_HTML_CLEANUP_LEGACY)
                    .remove(KEY_RECOMPRESS_COVERS_AFTER_BODY_HTML_CLEANUP_LEGACY)
                    .apply();
        }
    }

    private void markStorageSlimmingMaintenancePending() {
        maintenancePrefs().edit()
                .putBoolean(KEY_MAINTENANCE_PHASE_CLEANUP_BODY_HTML, true)
                .putBoolean(KEY_MAINTENANCE_PHASE_EXPORT_BODY_TEXT, true)
                .putBoolean(KEY_MAINTENANCE_PHASE_WAL_CHECKPOINT, true)
                .putBoolean(KEY_MAINTENANCE_PHASE_VACUUM, true)
                .putBoolean(KEY_MAINTENANCE_PHASE_RECOMPRESS_COVERS, true)
                .apply();
    }

    private void schedulePendingStorageMaintenance() {
        if (!hasPendingMaintenanceWork() || storageMaintenanceRunning) {
            return;
        }
        synchronized (this) {
            if (storageMaintenanceRunning) {
                return;
            }
            storageMaintenanceRunning = true;
        }
        Thread thread = new Thread(() -> {
            try {
                runPendingStorageMaintenance();
            } finally {
                storageMaintenanceRunning = false;
            }
        }, "PacilRead-storage-slimming");
        thread.setDaemon(true);
        thread.start();
    }

    private void runPendingStorageMaintenance() {
        SharedPreferences prefs = maintenancePrefs();

        // 阶段1：清空废弃的 body_html 列（idempotent，可安全重试）
        if (prefs.getBoolean(KEY_MAINTENANCE_PHASE_CLEANUP_BODY_HTML, false)) {
            try {
                clearDeprecatedChapterHtml(getWritableDatabase());
                prefs.edit().putBoolean(KEY_MAINTENANCE_PHASE_CLEANUP_BODY_HTML, false).apply();
            } catch (Exception error) {
                Log.w(TAG, "Body HTML cleanup phase failed", error);
                return;
            }
        }

        // 阶段2：导出 body_text 到外置 .txt.gz 文件，清空数据库正文（可安全重试）
        if (hasBodyTextExportWork(prefs)) {
            try {
                exportBodyTextToFiles();
                // 导出后 DB 有大量空闲页，必须重新跑 checkpoint + VACUUM
                prefs.edit()
                        .putBoolean(KEY_MAINTENANCE_PHASE_EXPORT_BODY_TEXT, false)
                        .putBoolean(KEY_MAINTENANCE_PHASE_WAL_CHECKPOINT, true)
                        .putBoolean(KEY_MAINTENANCE_PHASE_VACUUM, true)
                        .apply();
            } catch (Exception error) {
                Log.w(TAG, "Body text export phase failed", error);
                return;
            }
        }

        // 阶段4+5：checkpoint + VACUUM（合并为一个条件，任一需要即执行全部）
        if (hasVacuumWork(prefs)) {
            try {
                synchronized (this) {
                    getWritableDatabase().execSQL("PRAGMA wal_checkpoint(TRUNCATE)");
                }
                prefs.edit().putBoolean(KEY_MAINTENANCE_PHASE_WAL_CHECKPOINT, false).apply();
            } catch (Exception error) {
                Log.w(TAG, "WAL checkpoint failed, continuing to VACUUM", error);
            }

            try {
                synchronized (this) {
                    SQLiteDatabase db = getWritableDatabase();
                    try {
                        db.execSQL("PRAGMA wal_checkpoint(TRUNCATE)");
                    } catch (Exception ignored) {
                    }
                    db.execSQL("VACUUM");
                }
                prefs.edit()
                        .putBoolean(KEY_MAINTENANCE_PHASE_WAL_CHECKPOINT, false)
                        .putBoolean(KEY_MAINTENANCE_PHASE_VACUUM, false)
                        .apply();
            } catch (Exception error) {
                Log.w(TAG, "Vacuum phase failed", error);
                return;
            }
        }

        // 阶段6：封面重压缩（与数据库瘦身解耦，仅对已有封面执行一次）
        if (prefs.getBoolean(KEY_MAINTENANCE_PHASE_RECOMPRESS_COVERS, false)) {
            try {
                recompressExistingCovers();
                prefs.edit().putBoolean(KEY_MAINTENANCE_PHASE_RECOMPRESS_COVERS, false).apply();
            } catch (Exception error) {
                Log.w(TAG, "Cover recompression phase failed", error);
                return;
            }
        }
    }

    private void recompressExistingCovers() {
        List<BookRecord> books = getBooks();
        for (BookRecord book : books) {
            if (book.coverPath == null || book.coverPath.isBlank()) {
                continue;
            }
            File sourceFile = new File(book.coverPath);
            if (!sourceFile.exists() || !sourceFile.isFile()) {
                continue;
            }
            File compressedFile = null;
            try {
                compressedFile = CoverImageStore.saveCompressedCover(appContext, sourceFile, "cover_" + book.id);
                if (shouldReplaceCover(sourceFile, compressedFile)) {
                    setCoverPath(book.id, compressedFile.getAbsolutePath());
                    deleteFileIfExists(sourceFile.getAbsolutePath());
                } else {
                    deleteFileIfExists(compressedFile.getAbsolutePath());
                }
            } catch (Exception error) {
                Log.w(TAG, "Skipping cover recompression for book " + book.id, error);
                if (compressedFile != null) {
                    deleteFileIfExists(compressedFile.getAbsolutePath());
                }
            }
        }
    }

    private boolean shouldReplaceCover(File sourceFile, File compressedFile) {
        if (compressedFile == null || !compressedFile.exists() || compressedFile.length() <= 0L) {
            return false;
        }
        long originalSize = sourceFile.length();
        return originalSize <= 0L || compressedFile.length() < originalSize;
    }

    /**
     * 深度瘦身阶段：遍历所有 storage='db' 的章节，将 body_text 导出到 .txt.gz 文件并清空数据库正文。
     * 已完成的章节（storage='file_gzip'）自动跳过，可安全中断后重试。
     * 正文过长时使用 SUBSTR 分块读取，避免 CursorWindow 行大小限制。
     */
    private void exportBodyTextToFiles() {
        SQLiteDatabase db = getWritableDatabase();
        // 同时清空 body_html
        clearDeprecatedChapterHtml(db);

        // 包含两类：① storage='db' 待迁移  ② storage='file_gzip' 但 body_text 未清（上次中断残留）
        String selection = "body_text IS NOT NULL AND TRIM(body_text) <> '' AND (" +
                "body_text_storage IS NULL OR body_text_storage = ?" +
                " OR (body_text_storage = ? AND body_text_path IS NOT NULL)" +
                ")";
        try (Cursor cursor = db.query(
                "chapters",
                new String[]{"id", "book_id", "body_text_path", "body_text_storage"},
                selection,
                new String[]{STORAGE_DB, STORAGE_FILE_GZIP},
                null,
                null,
                "book_id ASC, order_index ASC"
        )) {
            while (cursor.moveToNext()) {
                long chapterId = cursor.getLong(cursor.getColumnIndexOrThrow("id"));
                long bookId = cursor.getLong(cursor.getColumnIndexOrThrow("book_id"));
                String existingPath = cursor.getString(cursor.getColumnIndexOrThrow("body_text_path"));
                String storage = cursor.getString(cursor.getColumnIndexOrThrow("body_text_storage"));

                // 已是 file_gzip 且外置文件存在 → 只需清 body_text，不用重新导出
                if (STORAGE_FILE_GZIP.equals(storage) && existingPath != null && !existingPath.isBlank()) {
                    File gzFile = resolveChapterTextFile(existingPath);
                    if (gzFile != null && gzFile.exists()) {
                        ContentValues clearOnly = new ContentValues();
                        clearOnly.put("body_text", "");
                        if (hasColumn(db, "chapters", "body")) {
                            clearOnly.put("body", "");
                        }
                        db.update("chapters", clearOnly, "id=?", new String[]{String.valueOf(chapterId)});
                        continue;
                    }
                }

                String bodyText = readBodyTextChunked(db, chapterId);
                if (bodyText == null || bodyText.isEmpty()) {
                    bodyText = readLegacyChapterBodyText(db, chapterId);
                }
                if (bodyText == null || bodyText.isEmpty()) {
                    Log.w(TAG, "章节 " + chapterId + " 外置正文缺失且数据库无正文，保留原状态等待源文件修复");
                    continue;
                }
                writeExternalChapterTextIfValid(db, bookId, chapterId, bodyText, true);
            }
        }
    }

    private void repairMissingExternalChapterTextFiles() {
        SQLiteDatabase db = getWritableDatabase();
        Map<Long, List<MissingChapterText>> unresolvedByBook = new LinkedHashMap<>();
        try (Cursor cursor = db.query(
                "chapters",
                new String[]{"id", "book_id", "order_index", "body_text_path"},
                "body_text_storage=? AND body_text_path IS NOT NULL AND TRIM(body_text_path) <> ''",
                new String[]{STORAGE_FILE_GZIP},
                null,
                null,
                "book_id ASC, order_index ASC"
        )) {
            while (cursor.moveToNext()) {
                long chapterId = cursor.getLong(cursor.getColumnIndexOrThrow("id"));
                long bookId = cursor.getLong(cursor.getColumnIndexOrThrow("book_id"));
                int orderIndex = cursor.getInt(cursor.getColumnIndexOrThrow("order_index"));
                String path = cursor.getString(cursor.getColumnIndexOrThrow("body_text_path"));
                File file = resolveChapterTextFile(path);
                if (file != null && file.exists()) {
                    continue;
                }
                String bodyText = readBodyTextChunked(db, chapterId);
                if (bodyText == null || bodyText.isEmpty()) {
                    bodyText = readLegacyChapterBodyText(db, chapterId);
                }
                if (bodyText != null && !bodyText.isEmpty()) {
                    writeExternalChapterTextIfValid(db, bookId, chapterId, bodyText, true);
                    continue;
                }
                List<MissingChapterText> missing = unresolvedByBook.get(bookId);
                if (missing == null) {
                    missing = new ArrayList<>();
                    unresolvedByBook.put(bookId, missing);
                }
                missing.add(new MissingChapterText(chapterId, orderIndex));
            }
        }

        for (Map.Entry<Long, List<MissingChapterText>> entry : unresolvedByBook.entrySet()) {
            repairMissingChapterTextFromSource(db, entry.getKey(), entry.getValue());
        }
    }

    private void repairMissingChapterTextFromSource(SQLiteDatabase db, long bookId, List<MissingChapterText> missingChapters) {
        BookRecord book = getBook(bookId);
        if (book == null || book.localPath == null || book.localPath.isBlank()) {
            return;
        }
        File sourceFile = new File(book.localPath);
        if (!sourceFile.exists() || !sourceFile.isFile()) {
            Log.w(TAG, "无法修复缺失正文，源文件不存在 book " + bookId + ": " + book.localPath);
            return;
        }
        List<ImportedBook.ChapterSeed> seeds;
        try {
            seeds = parseSourceForRepair(book, sourceFile);
        } catch (Exception error) {
            Log.w(TAG, "从源文件重建章节正文失败 book " + bookId, error);
            return;
        }
        if (seeds == null || seeds.isEmpty()) {
            return;
        }
        for (MissingChapterText missing : missingChapters) {
            if (missing.orderIndex < 0 || missing.orderIndex >= seeds.size()) {
                continue;
            }
            String bodyText = seeds.get(missing.orderIndex).bodyText;
            if (bodyText == null || bodyText.isEmpty()) {
                continue;
            }
            writeExternalChapterTextIfValid(db, bookId, missing.chapterId, bodyText, true);
        }
    }

    private List<ImportedBook.ChapterSeed> parseSourceForRepair(BookRecord book, File sourceFile) throws Exception {
        String bookType = book.bookType == null ? "" : book.bookType.toLowerCase(Locale.ROOT);
        String name = sourceFile.getName().toLowerCase(Locale.ROOT);
        if ("epub".equals(bookType) || name.endsWith(".epub")) {
            return EpubChapterParser.parse(sourceFile);
        }
        if ("text".equals(bookType) || "txt".equals(bookType) || name.endsWith(".txt")) {
            try (FileInputStream inputStream = new FileInputStream(sourceFile)) {
                return TxtChapterParser.parse(inputStream);
            }
        }
        Log.w(TAG, "暂不支持从该类型源文件修复章节正文 book " + book.id + " type=" + book.bookType);
        return new ArrayList<>();
    }

    private void writeExternalChapterTextIfValid(SQLiteDatabase db, long bookId, long chapterId,
                                                 String bodyText, boolean clearDatabaseText) {
        try {
            writeChapterTextToFile(bookId, chapterId, bodyText);
            String roundTripped = readChapterTextFromFile(bookId, chapterId);
            if (!bodyText.equals(roundTripped)) {
                Log.w(TAG, "章节正文校验失败 chapter " + chapterId + ", 保留数据库正文");
                return;
            }
            ContentValues updateValues = new ContentValues();
            if (clearDatabaseText) {
                updateValues.put("body_text", "");
                if (hasColumn(db, "chapters", "body")) {
                    updateValues.put("body", "");
                }
            }
            updateValues.put("body_text_storage", STORAGE_FILE_GZIP);
            updateValues.put("body_text_path", buildChapterTextRelativePath(bookId, chapterId));
            updateValues.put("body_text_size", bodyText.getBytes(StandardCharsets.UTF_8).length);
            db.update("chapters", updateValues, "id=?", new String[]{String.valueOf(chapterId)});
        } catch (IOException error) {
            Log.w(TAG, "写入章节外置正文失败 chapter " + chapterId + ", 保留数据库正文", error);
        }
    }

    private String readLegacyChapterBodyText(SQLiteDatabase db, long chapterId) {
        if (!hasColumn(db, "chapters", "body")) {
            return "";
        }
        String legacyBody = readColumnChunked(db, "chapters", "body", chapterId);
        if (legacyBody == null || legacyBody.isEmpty()) {
            return "";
        }
        return HtmlUtils.stripHtml(legacyBody);
    }

    /**
     * 分块读取单行某列的值，避免超长内容超出 CursorWindow 的 2MB 上限。
     * 每次读 512KB，拼接后返回完整内容。
     */
    private String readColumnChunked(SQLiteDatabase db, String table, String column, long rowId) {
        try (Cursor lenCursor = db.rawQuery(
                "SELECT LENGTH(" + column + ") FROM " + table + " WHERE id=?",
                new String[]{String.valueOf(rowId)})) {
            if (!lenCursor.moveToFirst()) return "";
            int totalLen = lenCursor.getInt(0);
            if (totalLen <= 0) return "";

            StringBuilder sb = new StringBuilder(totalLen);
            int offset = 1; // SQLite SUBSTR 从 1 开始
            final int chunkSize = 512 * 1024; // 512 KB

            while (offset <= totalLen) {
                try (Cursor chunkCursor = db.rawQuery(
                        "SELECT SUBSTR(" + column + ", ?, ?) FROM " + table + " WHERE id=?",
                        new String[]{String.valueOf(offset), String.valueOf(chunkSize), String.valueOf(rowId)})) {
                    if (chunkCursor.moveToFirst()) {
                        String chunk = chunkCursor.getString(0);
                        if (chunk != null) {
                            sb.append(chunk);
                        }
                    }
                }
                offset += chunkSize;
            }
            return sb.toString();
        }
    }

    /**
     * 分块读取 body_text，避免超长章节超出 CursorWindow 的 2MB 上限。
     */
    private String readBodyTextChunked(SQLiteDatabase db, long chapterId) {
        return readColumnChunked(db, "chapters", "body_text", chapterId);
    }

    public synchronized File exportDatabase(File destination) throws IOException {
        close();
        copyFile(getDatabaseFile(), destination);
        stripPlatformSettingsTable(destination);
        getWritableDatabase();
        return destination;
    }

    public synchronized File exportLiteDatabase(File destination) throws IOException {
        deleteDatabaseFileWithSidecars(destination);
        SQLiteDatabase liteDb = SQLiteDatabase.openOrCreateDatabase(destination, null);
        try {
            liteDb.execSQL("CREATE TABLE books (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "title TEXT NOT NULL," +
                    "author TEXT," +
                    "local_path TEXT NOT NULL," +
                    "cover_path TEXT," +
                    "book_type TEXT NOT NULL DEFAULT 'text'," +
                    "reading_stats_key TEXT NOT NULL DEFAULT ''," +
                    "progress_index INTEGER NOT NULL DEFAULT 0," +
                    "progress_offset INTEGER NOT NULL DEFAULT 0," +
                    "last_read_at INTEGER NOT NULL," +
                    "pinned INTEGER NOT NULL DEFAULT 0," +
                    "chapter_count INTEGER NOT NULL DEFAULT 0," +
                    "current_chapter_title TEXT NOT NULL DEFAULT ''" +
                    ")");
            liteDb.execSQL("CREATE TABLE replacement_rules (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "pattern TEXT NOT NULL," +
                    "replacement TEXT NOT NULL," +
                    "scope TEXT NOT NULL DEFAULT 'global'," +
                    "book_id INTEGER," +
                    "is_regex INTEGER NOT NULL DEFAULT 0," +
                    "active INTEGER NOT NULL DEFAULT 1" +
                    ")");
            liteDb.execSQL("CREATE TABLE custom_themes (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "name TEXT NOT NULL UNIQUE," +
                    "config_json TEXT NOT NULL," +
                    "updated_at INTEGER NOT NULL" +
                    ")");
            liteDb.execSQL("CREATE TABLE reading_stats (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "date TEXT NOT NULL," +
                    "source_device_id TEXT NOT NULL," +
                    "book_identity TEXT NOT NULL," +
                    "book_title TEXT NOT NULL," +
                    "book_author TEXT NOT NULL DEFAULT ''," +
                    "duration_seconds INTEGER NOT NULL DEFAULT 0," +
                    "char_count INTEGER NOT NULL DEFAULT 0," +
                    "updated_at INTEGER NOT NULL DEFAULT 0" +
                    ")");
            liteDb.execSQL("CREATE TABLE bookmarks (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "uuid TEXT NOT NULL UNIQUE," +
                    "book_id INTEGER NOT NULL DEFAULT -1," +
                    "book_identity TEXT NOT NULL DEFAULT ''," +
                    "book_title TEXT NOT NULL DEFAULT ''," +
                    "book_author TEXT NOT NULL DEFAULT ''," +
                    "chapter_order_index INTEGER NOT NULL DEFAULT 0," +
                    "chapter_title TEXT NOT NULL DEFAULT ''," +
                    "chapter_offset INTEGER NOT NULL DEFAULT 0," +
                    "progress_percent REAL NOT NULL DEFAULT 0," +
                    "summary TEXT NOT NULL DEFAULT ''," +
                    "created_at INTEGER NOT NULL DEFAULT 0," +
                    "updated_at INTEGER NOT NULL DEFAULT 0" +
                    ")");

            copyRows(getReadableDatabase(), liteDb, "books");
            copyRows(getReadableDatabase(), liteDb, "replacement_rules");
            copyRows(getReadableDatabase(), liteDb, "custom_themes");
            copyRows(getReadableDatabase(), liteDb, "reading_stats");
            copyRows(getReadableDatabase(), liteDb, "bookmarks");
        } finally {
            liteDb.close();
        }
        return destination;
    }

    public synchronized void importDatabase(File source) throws IOException {
        close();
        deleteSidecarFiles();
        copyFile(source, getDatabaseFile());
        SQLiteDatabase db = getWritableDatabase();
        dropPlatformSettingsTable(db);
        clearDeprecatedChapterHtml(db);
        markStorageSlimmingMaintenancePending();
        schedulePendingStorageMaintenance();
    }

    public synchronized void stripPlatformSettingsTable(File databaseFile) {
        if (databaseFile == null || !databaseFile.exists()) {
            return;
        }
        SQLiteDatabase db = SQLiteDatabase.openDatabase(databaseFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
        try {
            dropPlatformSettingsTable(db);
        } finally {
            db.close();
        }
    }

    public synchronized void rebaseLocalAssetPaths() {
        File booksDir = new File(appContext.getFilesDir(), "books");
        File coversDir = new File(appContext.getFilesDir(), "covers");
        if (!booksDir.exists()) {
            booksDir.mkdirs();
        }
        if (!coversDir.exists()) {
            coversDir.mkdirs();
        }
        SQLiteDatabase db = getWritableDatabase();
        try (Cursor cursor = db.query("books", null, null, null, null, null, null)) {
            int idIndex = cursor.getColumnIndexOrThrow("id");
            int localPathIndex = cursor.getColumnIndex("local_path");
            int coverPathIndex = cursor.getColumnIndex("cover_path");
            while (cursor.moveToNext()) {
                long id = cursor.getLong(idIndex);
                String localPath = localPathIndex >= 0 ? cursor.getString(localPathIndex) : "";
                String coverPath = coverPathIndex >= 0 ? cursor.getString(coverPathIndex) : "";
                ContentValues values = new ContentValues();
                if (localPath != null && !localPath.isBlank()) {
                    values.put("local_path", new File(booksDir, new File(localPath).getName()).getAbsolutePath());
                }
                if (coverPath != null && !coverPath.isBlank()) {
                    values.put("cover_path", new File(coversDir, new File(coverPath).getName()).getAbsolutePath());
                }
                if (values.size() > 0) {
                    db.update("books", values, "id=?", new String[]{String.valueOf(id)});
                }
            }
        }
    }

    public synchronized void mergeLiteDatabase(File source) {
        SQLiteDatabase sourceDb = SQLiteDatabase.openDatabase(source.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
        SQLiteDatabase target = getWritableDatabase();
        target.beginTransaction();
        try {
            List<BookRecord> existingBooks = getBooks();

            target.delete("replacement_rules", null, null);
            copyRows(sourceDb, target, "replacement_rules");

            target.delete("custom_themes", null, null);
            copyRows(sourceDb, target, "custom_themes");

            try (Cursor sourceBooks = sourceDb.query("books", null, null, null, null, null, null)) {
                while (sourceBooks.moveToNext()) {
                    String title = sourceBooks.getString(sourceBooks.getColumnIndexOrThrow("title"));
                    String author = sourceBooks.getString(sourceBooks.getColumnIndexOrThrow("author"));
                    int statsKeyIndex = sourceBooks.getColumnIndex("reading_stats_key");
                    String readingStatsKey = statsKeyIndex >= 0
                            ? sourceBooks.getString(statsKeyIndex)
                            : buildReadingStatsKey(title, author);
                    BookRecord match = findBook(existingBooks, title, author, readingStatsKey);

                    ContentValues values = new ContentValues();
                    values.put("title", title);
                    values.put("author", author);
                    values.put("local_path", rebasedAssetPath("books", getOptionalString(sourceBooks, "local_path", "loacl_path")));
                    int coverIndex = sourceBooks.getColumnIndex("cover_path");
                    if (coverIndex >= 0 && !sourceBooks.isNull(coverIndex)) {
                        values.put("cover_path", rebasedAssetPath("covers", sourceBooks.getString(coverIndex)));
                    } else {
                        values.putNull("cover_path");
                    }
                    int typeIndex = sourceBooks.getColumnIndex("book_type");
                    values.put("book_type", typeIndex >= 0 ? sourceBooks.getString(typeIndex) : "text");
                    values.put("reading_stats_key", readingStatsKey);
                    
                    // 进度一致性校验：确保章节存在
                    int progressIndex = sourceBooks.getInt(sourceBooks.getColumnIndexOrThrow("progress_index"));
                    // 这里假设 order_index 与 chapter count 对应，实际恢复时可能需要更复杂的逻辑
                    // 暂时先存入，由阅读器在打开时处理越界回退
                    values.put("progress_index", progressIndex);
                    
                    values.put("progress_offset", sourceBooks.getInt(sourceBooks.getColumnIndexOrThrow("progress_offset")));
                    values.put("last_read_at", sourceBooks.getLong(sourceBooks.getColumnIndexOrThrow("last_read_at")));
                    values.put("pinned", sourceBooks.getInt(sourceBooks.getColumnIndexOrThrow("pinned")));
                    int ccIndex = sourceBooks.getColumnIndex("chapter_count");
                    if (ccIndex >= 0) values.put("chapter_count", sourceBooks.getInt(ccIndex));
                    int cctIndex = sourceBooks.getColumnIndex("current_chapter_title");
                    if (cctIndex >= 0) values.put("current_chapter_title", sourceBooks.getString(cctIndex));

                    if (match != null) {
                        target.update("books", values, "id=?", new String[]{String.valueOf(match.id)});
                    } else {
                        target.insert("books", null, values);
                    }
                }
            }

            if (tableExists(sourceDb, "reading_stats")) {
                try (Cursor sourceReadingStats = sourceDb.query("reading_stats", null, null, null, null, null, null)) {
                    while (sourceReadingStats.moveToNext()) {
                        mergeReadingStatsRow(target, readReadingTimeEntry(sourceReadingStats));
                    }
                }
            }

            if (tableExists(sourceDb, "bookmarks")) {
                mergeBookmarkRows(sourceDb, target);
            }

            target.setTransactionSuccessful();
        } finally {
            target.endTransaction();
            sourceDb.close();
        }
    }

    public File getDatabaseFile() {
        return appContext.getDatabasePath(DATABASE_NAME);
    }

    public String getDatabaseSizeInfo() {
        File dbFile = getDatabaseFile();
        long dbSize = dbFile.exists() ? dbFile.length() : 0L;
        File walFile = new File(dbFile.getAbsolutePath() + "-wal");
        long walSize = walFile.exists() ? walFile.length() : 0L;
        File shmFile = new File(dbFile.getAbsolutePath() + "-shm");
        long shmSize = shmFile.exists() ? shmFile.length() : 0L;
        long dbTotal = dbSize + walSize + shmSize;

        long chapterTextSize = dirSize(getChapterTextDir());
        long coversSize = dirSize(new File(appContext.getFilesDir(), "covers"));
        long booksSize = dirSize(new File(appContext.getFilesDir(), "books"));
        long total = dbTotal + chapterTextSize + coversSize + booksSize;

        StringBuilder sb = new StringBuilder();
        sb.append("数据库文件 ").append(formatFileSize(dbTotal))
                .append("（主库 ").append(formatFileSize(dbSize))
                .append(" · WAL ").append(formatFileSize(walSize))
                .append(" · SHM ").append(formatFileSize(shmSize)).append("）")
                .append("\n章节正文文件 ").append(formatFileSize(chapterTextSize))
                .append("\n封面缓存 ").append(formatFileSize(coversSize))
                .append("\n源文件缓存 ").append(formatFileSize(booksSize));
        sb.append("\n本地存储合计 ").append(formatFileSize(total));
        sb.append("\n──────────────────");
        sb.append(getDatabaseContentBreakdown());
        return sb.toString();
    }

    /**
     * 诊断：按表统计行数与正文占用，定位大体积来源。
     */
    private String getDatabaseContentBreakdown() {
        StringBuilder sb = new StringBuilder();
        try {
            SQLiteDatabase db = getReadableDatabase();
            // 表行数（快速估计）
            sb.append("\ntables: ");
            int[] counts = new int[5];
            String[] tables = {"books", "chapters", "reading_stats", "bookmarks", "replacement_rules"};
            for (int i = 0; i < tables.length; i++) {
                try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + tables[i], null)) {
                    counts[i] = c.moveToFirst() ? c.getInt(0) : 0;
                }
            }
            sb.append("books×").append(counts[0])
                    .append(" ch×").append(counts[1])
                    .append(" stats×").append(counts[2])
                    .append(" bm×").append(counts[3])
                    .append(" rules×").append(counts[4]);

            // chapters 正文存储分布
            try (Cursor c = db.rawQuery(
                    "SELECT body_text_storage, COUNT(*), SUM(LENGTH(body_text)) " +
                            "FROM chapters GROUP BY body_text_storage", null)) {
                while (c.moveToNext()) {
                    String mode = c.getString(0);
                    int cnt = c.getInt(1);
                    long sumBytes = c.getLong(2);
                    sb.append("\n  storage=").append(mode == null ? "NULL" : mode)
                            .append(" ×").append(cnt)
                            .append(" body_text合计 ").append(formatFileSize(sumBytes));
                }
            }

            int missingTextFiles = countMissingChapterTextFiles(db);
            if (missingTextFiles > 0) {
                sb.append("\n  外置正文文件缺失 ×").append(missingTextFiles);
            }

            // chapters 中 body_text 非空但 storage 非 file_gzip 的（漏网之鱼）
            try (Cursor c = db.rawQuery(
                    "SELECT COUNT(*), SUM(LENGTH(body_text)) FROM chapters " +
                            "WHERE body_text IS NOT NULL AND TRIM(body_text) <> '' " +
                            "AND (body_text_storage IS NULL OR body_text_storage <> 'file_gzip')", null)) {
                if (c.moveToFirst() && c.getInt(0) > 0) {
                    sb.append("\n  ⚠ 未导出: ×").append(c.getInt(0))
                            .append(" 合计 ").append(formatFileSize(c.getLong(1)));
                }
            }

            // 最大的几个 body_text
            try (Cursor c = db.rawQuery(
                    "SELECT id, book_id, LENGTH(body_text) FROM chapters " +
                            "WHERE body_text IS NOT NULL AND LENGTH(body_text) > 0 " +
                            "ORDER BY LENGTH(body_text) DESC LIMIT 3", null)) {
                boolean first = true;
                while (c.moveToNext()) {
                    if (first) {
                        sb.append("\n  TOP3 大正文:");
                        first = false;
                    }
                    sb.append("\n    ch#").append(c.getLong(0))
                            .append(" book#").append(c.getLong(1))
                            .append(" ").append(formatFileSize(c.getLong(2)));
                }
            }
        } catch (Exception e) {
            sb.append("\n诊断失败: ").append(e.getMessage());
        }
        return sb.toString();
    }

    private int countMissingChapterTextFiles(SQLiteDatabase db) {
        int missing = 0;
        try (Cursor c = db.query(
                "chapters",
                new String[]{"body_text_path"},
                "body_text_storage=? AND body_text_path IS NOT NULL AND TRIM(body_text_path) <> ''",
                new String[]{STORAGE_FILE_GZIP},
                null,
                null,
                null
        )) {
            while (c.moveToNext()) {
                File file = resolveChapterTextFile(c.getString(0));
                if (file == null || !file.exists()) {
                    missing++;
                }
            }
        } catch (Exception ignored) {
        }
        return missing;
    }

    private static final class MissingChapterText {
        final long chapterId;
        final int orderIndex;

        MissingChapterText(long chapterId, int orderIndex) {
            this.chapterId = chapterId;
            this.orderIndex = orderIndex;
        }
    }

    private long dirSize(File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) {
            return 0L;
        }
        long total = 0L;
        File[] files = dir.listFiles();
        if (files == null) {
            return 0L;
        }
        for (File f : files) {
            if (f.isFile()) {
                total += f.length();
            } else if (f.isDirectory()) {
                total += dirSize(f);
            }
        }
        return total;
    }

    private static String formatFileSize(long bytes) {
        if (bytes <= 0L) return "0 B";
        if (bytes < 1024L) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb);
        double mb = kb / 1024.0;
        return String.format(Locale.US, "%.2f MB", mb);
    }

    private void deleteSidecarFiles() throws IOException {
        deleteDatabaseSidecarFilesOrThrow(getDatabaseFile());
    }

    private void deleteDatabaseFileWithSidecars(File databaseFile) throws IOException {
        if (databaseFile == null) {
            return;
        }
        deleteFileIfExistsOrThrow(databaseFile);
        deleteDatabaseSidecarFilesOrThrow(databaseFile);
    }

    private void deleteDatabaseSidecarFilesOrThrow(File databaseFile) throws IOException {
        if (databaseFile == null) {
            return;
        }
        String path = databaseFile.getAbsolutePath();
        deleteFileIfExistsOrThrow(new File(path + "-wal"));
        deleteFileIfExistsOrThrow(new File(path + "-shm"));
        deleteFileIfExistsOrThrow(new File(path + "-journal"));
    }

    private void deleteFileIfExistsOrThrow(File file) throws IOException {
        if (file != null && file.exists() && !file.delete()) {
            throw new IOException("无法删除旧临时文件: " + file.getAbsolutePath());
        }
    }

    private void dropPlatformSettingsTable(SQLiteDatabase db) {
        db.execSQL("DROP TABLE IF EXISTS settings");
    }

    private void copyRows(SQLiteDatabase from, SQLiteDatabase to, String table) {
        try (Cursor cursor = from.rawQuery("SELECT * FROM " + table, null)) {
            String[] columns = cursor.getColumnNames();
            while (cursor.moveToNext()) {
                ContentValues values = new ContentValues();
                for (String column : columns) {
                    int index = cursor.getColumnIndexOrThrow(column);
                    switch (cursor.getType(index)) {
                        case Cursor.FIELD_TYPE_NULL:
                            values.putNull(column);
                            break;
                        case Cursor.FIELD_TYPE_INTEGER:
                            values.put(column, cursor.getLong(index));
                            break;
                        case Cursor.FIELD_TYPE_FLOAT:
                            values.put(column, cursor.getDouble(index));
                            break;
                        case Cursor.FIELD_TYPE_STRING:
                            values.put(column, cursor.getString(index));
                            break;
                        case Cursor.FIELD_TYPE_BLOB:
                            values.put(column, cursor.getBlob(index));
                            break;
                    }
                }
                to.insert(table, null, values);
            }
        }
    }

    private BookRecord findBook(List<BookRecord> books, String title, String author, String readingStatsKey) {
        for (BookRecord book : books) {
            if (readingStatsKey != null && !readingStatsKey.isBlank() && readingStatsKey.equals(book.readingStatsKey)) {
                return book;
            }
        }
        String targetKey = normalizedTitleAuthorKey(title, author);
        for (BookRecord book : books) {
            if (targetKey.equals(normalizedTitleAuthorKey(book.title, book.author))) {
                return book;
            }
        }
        return null;
    }

    private long upsertBookmarkRow(SQLiteDatabase db, BookmarkRecord bookmark) {
        long now = System.currentTimeMillis();
        long createdAt = bookmark.createdAt > 0 ? bookmark.createdAt : now;
        long updatedAt = bookmark.updatedAt > 0 ? bookmark.updatedAt : now;
        ContentValues values = bookmarkValues(bookmark, createdAt, updatedAt);
        try (Cursor cursor = db.query(
                "bookmarks",
                new String[]{"id", "updated_at"},
                "uuid=?",
                new String[]{bookmark.uuid},
                null,
                null,
                null,
                "1"
        )) {
            if (cursor.moveToFirst()) {
                long existingId = cursor.getLong(0);
                long existingUpdatedAt = cursor.getLong(1);
                if (updatedAt >= existingUpdatedAt) {
                    db.update("bookmarks", values, "id=?", new String[]{String.valueOf(existingId)});
                }
                return existingId;
            }
        }
        return db.insertOrThrow("bookmarks", null, values);
    }

    private ContentValues bookmarkValues(BookmarkRecord bookmark, long createdAt, long updatedAt) {
        ContentValues values = new ContentValues();
        values.put("uuid", bookmark.uuid);
        values.put("book_id", bookmark.bookId);
        values.put("book_identity", safeBookIdentityForBookmark(bookmark.bookIdentity));
        values.put("book_title", ReadingStatsUtils.safeBookTitle(bookmark.bookTitle));
        values.put("book_author", normalizeAuthor(bookmark.bookAuthor));
        values.put("chapter_order_index", Math.max(bookmark.chapterOrderIndex, 0));
        values.put("chapter_title", bookmark.chapterTitle == null ? "" : bookmark.chapterTitle.trim());
        values.put("chapter_offset", Math.max(bookmark.chapterOffset, 0));
        values.put("progress_percent", clamp(bookmark.progressPercent, 0f, 100f));
        values.put("summary", bookmark.summary == null ? "" : bookmark.summary.trim());
        values.put("created_at", createdAt);
        values.put("updated_at", updatedAt);
        return values;
    }

    private BookmarkRecord readBookmark(Cursor cursor) {
        BookmarkRecord bookmark = new BookmarkRecord();
        bookmark.id = cursor.getLong(cursor.getColumnIndexOrThrow("id"));
        bookmark.uuid = cursor.getString(cursor.getColumnIndexOrThrow("uuid"));
        bookmark.bookId = cursor.getLong(cursor.getColumnIndexOrThrow("book_id"));
        bookmark.bookIdentity = cursor.getString(cursor.getColumnIndexOrThrow("book_identity"));
        bookmark.bookTitle = cursor.getString(cursor.getColumnIndexOrThrow("book_title"));
        bookmark.bookAuthor = cursor.getString(cursor.getColumnIndexOrThrow("book_author"));
        bookmark.chapterOrderIndex = cursor.getInt(cursor.getColumnIndexOrThrow("chapter_order_index"));
        bookmark.chapterTitle = cursor.getString(cursor.getColumnIndexOrThrow("chapter_title"));
        bookmark.chapterOffset = cursor.getInt(cursor.getColumnIndexOrThrow("chapter_offset"));
        bookmark.progressPercent = cursor.getFloat(cursor.getColumnIndexOrThrow("progress_percent"));
        bookmark.summary = cursor.getString(cursor.getColumnIndexOrThrow("summary"));
        bookmark.createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at"));
        bookmark.updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at"));
        return bookmark;
    }

    private String rebasedAssetPath(String folderName, String originalPath) {
        if (originalPath == null || originalPath.isBlank()) {
            return "";
        }
        File folder = new File(appContext.getFilesDir(), folderName);
        if (!folder.exists()) {
            folder.mkdirs();
        }
        return new File(folder, new File(originalPath).getName()).getAbsolutePath();
    }

    private BookRecord readBook(Cursor cursor) {
        BookRecord book = new BookRecord();
        book.id = cursor.getLong(cursor.getColumnIndexOrThrow("id"));
        book.title = cursor.getString(cursor.getColumnIndexOrThrow("title"));
        book.author = cursor.getString(cursor.getColumnIndexOrThrow("author"));
        book.localPath = getOptionalString(cursor, "local_path", "loacl_path");
        int coverIndex = cursor.getColumnIndex("cover_path");
        book.coverPath = coverIndex >= 0 ? cursor.getString(coverIndex) : null;
        int typeIndex = cursor.getColumnIndex("book_type");
        book.bookType = typeIndex >= 0 ? cursor.getString(typeIndex) : "text";
        int readingStatsKeyIndex = cursor.getColumnIndex("reading_stats_key");
        book.readingStatsKey = readingStatsKeyIndex >= 0
                ? cursor.getString(readingStatsKeyIndex)
                : buildReadingStatsKey(book.title, book.author);
        book.progressIndex = cursor.getInt(cursor.getColumnIndexOrThrow("progress_index"));
        book.progressOffset = cursor.getInt(cursor.getColumnIndexOrThrow("progress_offset"));
        book.lastReadAt = cursor.getLong(cursor.getColumnIndexOrThrow("last_read_at"));
        book.pinned = cursor.getInt(cursor.getColumnIndexOrThrow("pinned")) == 1;
        int ccIndex = cursor.getColumnIndex("chapter_count");
        book.chapterCount = ccIndex >= 0 ? cursor.getInt(ccIndex) : 0;
        int cctIndex = cursor.getColumnIndex("current_chapter_title");
        book.currentChapterTitle = cctIndex >= 0 ? cursor.getString(cctIndex) : "";
        return book;
    }

    private void populateBookshelfSummary(SQLiteDatabase db, BookRecord book) {
        if (book == null) {
            return;
        }
        try (Cursor cursor = db.query(
                "chapters",
                new String[]{"title"},
                "book_id=?",
                new String[]{String.valueOf(book.id)},
                null,
                null,
                "order_index ASC"
        )) {
            book.chapterCount = cursor.getCount();
            if (book.chapterCount <= 0) {
                book.progressIndex = 0;
                book.currentChapterTitle = "";
                return;
            }
            int safeProgressIndex = Math.max(0, Math.min(book.progressIndex, book.chapterCount - 1));
            book.progressIndex = safeProgressIndex;
            if (cursor.moveToPosition(safeProgressIndex)) {
                book.currentChapterTitle = cursor.getString(cursor.getColumnIndexOrThrow("title"));
            } else {
                book.currentChapterTitle = "";
            }
        }
    }

    private void populateBookshelfSummary(Cursor cursor, BookRecord book) {
        if (cursor == null || book == null) {
            return;
        }
        int chapterCountIndex = cursor.getColumnIndex("bookshelf_chapter_count");
        book.chapterCount = chapterCountIndex >= 0 ? Math.max(0, cursor.getInt(chapterCountIndex)) : 0;
        if (book.chapterCount <= 0) {
            book.progressIndex = 0;
            book.currentChapterTitle = "";
            return;
        }
        book.progressIndex = Math.max(0, Math.min(book.progressIndex, book.chapterCount - 1));
        int currentTitleIndex = cursor.getColumnIndex("bookshelf_current_chapter_title");
        book.currentChapterTitle = currentTitleIndex >= 0 && !cursor.isNull(currentTitleIndex)
                ? cursor.getString(currentTitleIndex)
                : "";
    }

    private void backfillBookshelfSummaryColumns(SQLiteDatabase db) {
        db.execSQL("UPDATE books SET chapter_count = COALESCE((" +
                "SELECT COUNT(*) FROM chapters WHERE chapters.book_id = books.id" +
                "), 0) WHERE chapter_count IS NULL OR chapter_count = 0");
        db.execSQL("UPDATE books SET current_chapter_title = COALESCE((" +
                "SELECT title FROM chapters WHERE chapters.book_id = books.id" +
                " AND chapters.order_index = MAX(0, MIN(books.progress_index, " +
                "(SELECT COUNT(*) FROM chapters WHERE chapters.book_id = books.id) - 1))" +
                "), '') WHERE current_chapter_title IS NULL OR TRIM(current_chapter_title) = ''");
    }

    private String getOptionalString(Cursor cursor, String columnName, String fallbackColumnName) {
        int index = cursor.getColumnIndex(columnName);
        if (index < 0 && fallbackColumnName != null) {
            index = cursor.getColumnIndex(fallbackColumnName);
        }
        if (index < 0 || cursor.isNull(index)) {
            return "";
        }
        return cursor.getString(index);
    }

    private ReadingTimeEntryRecord readReadingTimeEntry(Cursor cursor) {
        ReadingTimeEntryRecord record = new ReadingTimeEntryRecord();
        int idIndex = cursor.getColumnIndex("id");
        record.id = idIndex >= 0 ? cursor.getLong(idIndex) : -1L;
        record.date = cursor.getString(cursor.getColumnIndexOrThrow("date"));
        int sourceDeviceIndex = cursor.getColumnIndex("source_device_id");
        record.sourceDeviceId = sourceDeviceIndex >= 0
                ? cursor.getString(sourceDeviceIndex)
                : ReadingStatsUtils.LEGACY_DEVICE_ID;
        int bookIdentityIndex = cursor.getColumnIndex("book_identity");
        record.bookIdentity = bookIdentityIndex >= 0
                ? cursor.getString(bookIdentityIndex)
                : ReadingStatsUtils.LEGACY_BOOK_IDENTITY;
        int bookTitleIndex = cursor.getColumnIndex("book_title");
        record.bookTitle = bookTitleIndex >= 0
                ? cursor.getString(bookTitleIndex)
                : ReadingStatsUtils.LEGACY_BOOK_TITLE;
        int bookAuthorIndex = cursor.getColumnIndex("book_author");
        record.bookAuthor = bookAuthorIndex >= 0 ? cursor.getString(bookAuthorIndex) : "";
        record.durationSeconds = cursor.getInt(cursor.getColumnIndexOrThrow("duration_seconds"));
        int charCountIndex = cursor.getColumnIndex("char_count");
        record.charCount = charCountIndex >= 0 ? cursor.getInt(charCountIndex) : 0;
        int updatedAtIndex = cursor.getColumnIndex("updated_at");
        record.updatedAt = updatedAtIndex >= 0 ? cursor.getLong(updatedAtIndex) : 0L;
        return record;
    }

    private void copyFile(File source, File destination) throws IOException {
        if (destination.getParentFile() != null && !destination.getParentFile().exists() && !destination.getParentFile().mkdirs()) {
            throw new IOException("无法创建目录: " + destination.getParent());
        }
        try (FileChannel inputChannel = new FileInputStream(source).getChannel();
             FileChannel outputChannel = new FileOutputStream(destination).getChannel()) {
            outputChannel.transferFrom(inputChannel, 0, inputChannel.size());
        }
    }

    private void deleteFileIfExists(String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        File file = new File(path);
        if (file.exists()) {
            file.delete();
        }
    }

    private void backfillBookStatsKeys(SQLiteDatabase db) {
        try (Cursor cursor = db.query("books", new String[]{"id", "title", "author", "reading_stats_key"}, null, null, null, null, null)) {
            while (cursor.moveToNext()) {
                String currentKey = cursor.getString(cursor.getColumnIndexOrThrow("reading_stats_key"));
                if (currentKey != null && !currentKey.isBlank()) {
                    continue;
                }
                ContentValues values = new ContentValues();
                values.put("reading_stats_key", buildReadingStatsKey(cursor.getString(1), cursor.getString(2)));
                db.update("books", values, "id=?", new String[]{String.valueOf(cursor.getLong(0))});
            }
        }
    }

    private void migrateLegacyReadingStats(SQLiteDatabase db) {
        ContentValues defaults = new ContentValues();
        defaults.put("source_device_id", ReadingStatsUtils.LEGACY_DEVICE_ID);
        defaults.put("book_identity", ReadingStatsUtils.LEGACY_BOOK_IDENTITY);
        defaults.put("book_title", ReadingStatsUtils.LEGACY_BOOK_TITLE);
        defaults.put("book_author", "");
        defaults.put("updated_at", System.currentTimeMillis());
        db.update(
                "reading_stats",
                defaults,
                "(source_device_id IS NULL OR TRIM(source_device_id)='') OR " +
                        "(book_identity IS NULL OR TRIM(book_identity)='') OR " +
                        "(book_title IS NULL OR TRIM(book_title)='') OR updated_at<=0",
                null
        );
    }

    private void deduplicateReadingStats(SQLiteDatabase db) {
        List<ReadingTimeEntryRecord> mergedRows = new ArrayList<>();
        try (Cursor cursor = db.rawQuery(
                "SELECT source_device_id, date, book_identity, " +
                        "SUM(duration_seconds) AS total_duration, SUM(char_count) AS total_char_count, " +
                        "MAX(updated_at) AS latest_updated_at, COUNT(*) AS bucket_count " +
                        "FROM reading_stats GROUP BY source_device_id, date, book_identity HAVING bucket_count > 1",
                null
        )) {
            while (cursor.moveToNext()) {
                String sourceDeviceId = cursor.getString(cursor.getColumnIndexOrThrow("source_device_id"));
                String date = cursor.getString(cursor.getColumnIndexOrThrow("date"));
                String bookIdentity = cursor.getString(cursor.getColumnIndexOrThrow("book_identity"));
                try (Cursor latestCursor = db.query(
                        "reading_stats",
                        new String[]{"book_title", "book_author"},
                        "source_device_id=? AND date=? AND book_identity=?",
                        new String[]{sourceDeviceId, date, bookIdentity},
                        null,
                        null,
                        "updated_at DESC, id DESC",
                        "1"
                )) {
                    ReadingTimeEntryRecord record = new ReadingTimeEntryRecord();
                    record.sourceDeviceId = sourceDeviceId;
                    record.date = date;
                    record.bookIdentity = bookIdentity;
                    record.durationSeconds = cursor.getInt(cursor.getColumnIndexOrThrow("total_duration"));
                    record.charCount = cursor.getInt(cursor.getColumnIndexOrThrow("total_char_count"));
                    record.updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("latest_updated_at"));
                    if (latestCursor.moveToFirst()) {
                        record.bookTitle = latestCursor.getString(0);
                        record.bookAuthor = latestCursor.getString(1);
                    } else {
                        record.bookTitle = ReadingStatsUtils.LEGACY_BOOK_TITLE;
                        record.bookAuthor = "";
                    }
                    mergedRows.add(record);
                }
            }
        }
        if (mergedRows.isEmpty()) {
            return;
        }
        for (ReadingTimeEntryRecord row : mergedRows) {
            db.delete(
                    "reading_stats",
                    "source_device_id=? AND date=? AND book_identity=?",
                    new String[]{safeDeviceId(row.sourceDeviceId), row.date, safeBookIdentity(row.bookIdentity)}
            );
            ContentValues values = new ContentValues();
            values.put("date", row.date);
            values.put("source_device_id", safeDeviceId(row.sourceDeviceId));
            values.put("book_identity", safeBookIdentity(row.bookIdentity));
            values.put("book_title", ReadingStatsUtils.safeBookTitle(row.bookTitle));
            values.put("book_author", normalizeAuthor(row.bookAuthor));
            values.put("duration_seconds", Math.max(row.durationSeconds, 0));
            values.put("char_count", Math.max(row.charCount, 0));
            values.put("updated_at", Math.max(row.updatedAt, 0L));
            db.insert("reading_stats", null, values);
        }
    }

    private void mergeReadingStatsRow(SQLiteDatabase db, ReadingTimeEntryRecord row) {
        if (row == null || row.date == null || row.date.isBlank()) {
            return;
        }
        String sourceDeviceId = safeDeviceId(row.sourceDeviceId);
        String bookIdentity = safeBookIdentity(row.bookIdentity);
        try (Cursor cursor = db.query(
                "reading_stats",
                new String[]{"id", "updated_at"},
                "source_device_id=? AND date=? AND book_identity=?",
                new String[]{sourceDeviceId, row.date, bookIdentity},
                null,
                null,
                null,
                "1"
        )) {
            ContentValues values = new ContentValues();
            values.put("date", row.date);
            values.put("source_device_id", sourceDeviceId);
            values.put("book_identity", bookIdentity);
            values.put("book_title", ReadingStatsUtils.safeBookTitle(row.bookTitle));
            values.put("book_author", normalizeAuthor(row.bookAuthor));
            values.put("duration_seconds", Math.max(row.durationSeconds, 0));
            values.put("char_count", Math.max(row.charCount, 0));
            values.put("updated_at", Math.max(row.updatedAt, 0L));
            if (cursor.moveToFirst()) {
                long existingUpdatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at"));
                if (row.updatedAt >= existingUpdatedAt) {
                    db.update("reading_stats", values, "id=?", new String[]{String.valueOf(cursor.getLong(0))});
                }
                return;
            }
            db.insert("reading_stats", null, values);
        }
    }

    private void mergeBookmarkRows(SQLiteDatabase sourceDb, SQLiteDatabase targetDb) {
        try (Cursor cursor = sourceDb.query("bookmarks", null, null, null, null, null, null)) {
            while (cursor.moveToNext()) {
                BookmarkRecord bookmark = readBookmark(cursor);
                bookmark.bookId = resolveBookmarkBookId(targetDb, bookmark);
                upsertBookmarkRow(targetDb, bookmark);
            }
        }
    }

    private long resolveBookmarkBookId(SQLiteDatabase db, BookmarkRecord bookmark) {
        if (bookmark == null) {
            return -1L;
        }
        if (bookmark.bookIdentity != null && !bookmark.bookIdentity.isBlank()) {
            try (Cursor cursor = db.query(
                    "books",
                    new String[]{"id"},
                    "reading_stats_key=?",
                    new String[]{bookmark.bookIdentity},
                    null,
                    null,
                    null,
                    "1"
            )) {
                if (cursor.moveToFirst()) {
                    return cursor.getLong(0);
                }
            }
        }
        String title = bookmark.bookTitle == null ? "" : bookmark.bookTitle.trim();
        String author = bookmark.bookAuthor == null ? "" : bookmark.bookAuthor.trim();
        try (Cursor cursor = db.query(
                "books",
                new String[]{"id"},
                "LOWER(TRIM(title))=? AND LOWER(TRIM(COALESCE(author, '')))=?",
                new String[]{title.toLowerCase(Locale.ROOT), author.toLowerCase(Locale.ROOT)},
                null,
                null,
                null,
                "1"
        )) {
            if (cursor.moveToFirst()) {
                return cursor.getLong(0);
            }
        }
        return bookmark.bookId;
    }

    private boolean tableExists(SQLiteDatabase db, String tableName) {
        try (Cursor cursor = db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                new String[]{tableName}
        )) {
            return cursor.moveToFirst();
        }
    }

    private String buildReadingStatsKey(String title, String author) {
        return ReadingStatsUtils.buildBookIdentity(title, author);
    }

    private String safeDeviceId(String value) {
        return value == null || value.isBlank() ? ReadingStatsUtils.LEGACY_DEVICE_ID : value;
    }

    private String safeBookIdentity(String value) {
        return value == null || value.isBlank() ? ReadingStatsUtils.LEGACY_BOOK_IDENTITY : value;
    }

    private String safeBookIdentityForBookmark(String value) {
        return value == null ? "" : value.trim();
    }

    private String normalizeAuthor(String value) {
        return value == null ? "" : value.trim();
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private String normalizedTitleAuthorKey(String title, String author) {
        return (title == null ? "" : title.trim().toLowerCase(Locale.ROOT))
                + "::"
                + (author == null ? "" : author.trim().toLowerCase(Locale.ROOT));
    }
}
