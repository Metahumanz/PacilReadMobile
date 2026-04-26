package com.metahumanz.pacilread.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.metahumanz.pacilread.model.BookRecord;
import com.metahumanz.pacilread.model.ChapterRecord;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.lang.reflect.Field;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class ReaderDatabaseMigrationTest {
    private static final String DATABASE_NAME = "reader.db";

    private Context context;

    @Before
    public void setUp() throws Exception {
        context = ApplicationProvider.getApplicationContext();
        resetHelperSingleton();
        deleteDatabaseFiles();
    }

    @After
    public void tearDown() throws Exception {
        resetHelperSingleton();
        deleteDatabaseFiles();
    }

    @Test
    public void upgradeFromMainSchemaAddsReadingStatsKeyIndexAndKeepsBooksReadable() throws Exception {
        File dbFile = context.getDatabasePath(DATABASE_NAME);
        File parent = dbFile.getParentFile();
        if (parent != null && !parent.exists()) {
            assertTrue(parent.mkdirs() || parent.exists());
        }

        SQLiteDatabase legacyDb = SQLiteDatabase.openOrCreateDatabase(dbFile, null);
        try {
            legacyDb.execSQL("CREATE TABLE books (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "title TEXT NOT NULL," +
                    "author TEXT," +
                    "local_path TEXT NOT NULL," +
                    "cover_path TEXT," +
                    "book_type TEXT NOT NULL DEFAULT 'text'," +
                    "progress_index INTEGER NOT NULL DEFAULT 0," +
                    "progress_offset INTEGER NOT NULL DEFAULT 0," +
                    "last_read_at INTEGER NOT NULL," +
                    "pinned INTEGER NOT NULL DEFAULT 0" +
                    ")");
            legacyDb.execSQL("CREATE TABLE chapters (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "book_id INTEGER NOT NULL," +
                    "title TEXT NOT NULL," +
                    "body_html TEXT NOT NULL," +
                    "body_text TEXT NOT NULL," +
                    "order_index INTEGER NOT NULL" +
                    ")");
            legacyDb.execSQL("CREATE INDEX idx_chapters_book_order ON chapters(book_id, order_index)");
            legacyDb.execSQL("CREATE TABLE replacement_rules (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "pattern TEXT NOT NULL," +
                    "replacement TEXT NOT NULL," +
                    "scope TEXT NOT NULL DEFAULT 'global'," +
                    "book_id INTEGER," +
                    "is_regex INTEGER NOT NULL DEFAULT 0," +
                    "active INTEGER NOT NULL DEFAULT 1" +
                    ")");
            legacyDb.execSQL("CREATE TABLE custom_themes (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "name TEXT NOT NULL UNIQUE," +
                    "config_json TEXT NOT NULL," +
                    "updated_at INTEGER NOT NULL" +
                    ")");
            legacyDb.execSQL("CREATE TABLE reading_stats (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "date TEXT NOT NULL," +
                    "duration_seconds INTEGER NOT NULL DEFAULT 0," +
                    "char_count INTEGER NOT NULL DEFAULT 0" +
                    ")");
            legacyDb.execSQL("CREATE INDEX idx_reading_stats_date ON reading_stats(date)");
            legacyDb.execSQL("INSERT INTO books(title, author, local_path, cover_path, book_type, progress_index, progress_offset, last_read_at, pinned) " +
                    "VALUES ('Legacy Book', 'Legacy Author', '/tmp/legacy.txt', NULL, 'text', 2, 8, 123456789, 1)");
            legacyDb.setVersion(3);
        } finally {
            legacyDb.close();
        }

        ReaderDatabaseHelper helper = ReaderDatabaseHelper.getInstance(context);
        List<BookRecord> books = helper.getBooks();

        assertEquals(1, books.size());
        assertEquals("Legacy Book", books.get(0).title);
        assertTrue(books.get(0).readingStatsKey != null && !books.get(0).readingStatsKey.isBlank());

        SQLiteDatabase upgradedDb = SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
        try {
            assertTrue(hasColumn(upgradedDb, "books", "reading_stats_key"));
            assertTrue(hasIndex(upgradedDb, "idx_books_reading_stats_key"));
            assertTrue(hasTable(upgradedDb, "bookmarks"));
            assertTrue(hasIndex(upgradedDb, "idx_bookmarks_book_identity"));
            assertTrue(hasIndex(upgradedDb, "idx_bookmarks_book_id"));
        } finally {
            upgradedDb.close();
        }
    }

    @Test
    public void upgradeFromTypoLocalPathColumnRepairsAndRebasesBookPaths() throws Exception {
        File dbFile = context.getDatabasePath(DATABASE_NAME);
        File parent = dbFile.getParentFile();
        if (parent != null && !parent.exists()) {
            assertTrue(parent.mkdirs() || parent.exists());
        }

        SQLiteDatabase legacyDb = SQLiteDatabase.openOrCreateDatabase(dbFile, null);
        try {
            legacyDb.execSQL("CREATE TABLE books (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "title TEXT NOT NULL," +
                    "author TEXT," +
                    "loacl_path TEXT NOT NULL," +
                    "cover_path TEXT," +
                    "book_type TEXT NOT NULL DEFAULT 'text'," +
                    "progress_index INTEGER NOT NULL DEFAULT 0," +
                    "progress_offset INTEGER NOT NULL DEFAULT 0," +
                    "last_read_at INTEGER NOT NULL," +
                    "pinned INTEGER NOT NULL DEFAULT 0" +
                    ")");
            legacyDb.execSQL("INSERT INTO books(title, author, loacl_path, cover_path, book_type, progress_index, progress_offset, last_read_at, pinned) " +
                    "VALUES ('Legacy Book', 'Legacy Author', '/tmp/legacy.txt', NULL, 'text', 2, 8, 123456789, 1)");
            legacyDb.setVersion(4);
        } finally {
            legacyDb.close();
        }

        ReaderDatabaseHelper helper = ReaderDatabaseHelper.getInstance(context);
        helper.rebaseLocalAssetPaths();

        SQLiteDatabase upgradedDb = SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
        try {
            assertTrue(hasColumn(upgradedDb, "books", "local_path"));
            assertTrue(hasTable(upgradedDb, "bookmarks"));
            assertTrue(hasIndex(upgradedDb, "idx_bookmarks_book_identity"));
            assertTrue(hasIndex(upgradedDb, "idx_bookmarks_book_id"));
            try (Cursor cursor = upgradedDb.query("books", new String[]{"local_path"}, null, null, null, null, null)) {
                assertTrue(cursor.moveToFirst());
                String rebasedPath = cursor.getString(0);
                assertTrue(rebasedPath.contains(context.getFilesDir().getAbsolutePath()));
                assertTrue(rebasedPath.endsWith("legacy.txt"));
            }
        } finally {
            upgradedDb.close();
        }
    }

    @Test
    public void upgradeFromWin11SchemaCopiesChapterBodyIntoAndroidBodyText() throws Exception {
        File dbFile = context.getDatabasePath(DATABASE_NAME);
        File parent = dbFile.getParentFile();
        if (parent != null && !parent.exists()) {
            assertTrue(parent.mkdirs() || parent.exists());
        }

        SQLiteDatabase win11Db = SQLiteDatabase.openOrCreateDatabase(dbFile, null);
        try {
            win11Db.execSQL("CREATE TABLE books (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "title TEXT NOT NULL," +
                    "author TEXT," +
                    "cover_path TEXT," +
                    "path TEXT," +
                    "progress_index INTEGER DEFAULT 0," +
                    "progress_offset INTEGER DEFAULT 0," +
                    "last_read DATETIME DEFAULT CURRENT_TIMESTAMP," +
                    "source_id INTEGER," +
                    "pinned INTEGER DEFAULT 0" +
                    ")");
            win11Db.execSQL("CREATE TABLE chapters (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "book_id INTEGER NOT NULL," +
                    "title TEXT NOT NULL," +
                    "body TEXT NOT NULL," +
                    "order_index INTEGER NOT NULL," +
                    "link TEXT" +
                    ")");
            win11Db.execSQL("INSERT INTO books(title, author, path, progress_index, progress_offset, last_read, pinned) " +
                    "VALUES ('Win11 Book', 'Win11 Author', 'C:/books/win11.txt', 0, 0, '2026-04-23T12:34:56.000Z', 0)");
            win11Db.execSQL("INSERT INTO chapters(book_id, title, body, order_index) " +
                    "VALUES (1, '第一章', '<p>第一段正文</p><p>第二段正文</p>', 0)");
            win11Db.setVersion(4);
        } finally {
            win11Db.close();
        }

        ReaderDatabaseHelper helper = ReaderDatabaseHelper.getInstance(context);
        BookRecord book = helper.getBook(1);
        List<ChapterRecord> chapters = helper.getChapters(1, true);

        assertEquals("C:/books/win11.txt", book.localPath);
        assertEquals(1, chapters.size());
        assertEquals("<p>第一段正文</p><p>第二段正文</p>", chapters.get(0).bodyHtml);
        assertTrue(chapters.get(0).bodyText.contains("第一段正文"));
        assertTrue(chapters.get(0).bodyText.contains("第二段正文"));
    }

    @Test
    public void exportAndImportStripLegacySettingsTable() throws Exception {
        ReaderDatabaseHelper helper = ReaderDatabaseHelper.getInstance(context);
        helper.getWritableDatabase().execSQL("CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT)");
        helper.getWritableDatabase().execSQL("INSERT OR REPLACE INTO settings(key, value) VALUES ('reader_pageMode', 'double')");

        File exportedDb = new File(context.getCacheDir(), "reader_export_strip.db");
        if (exportedDb.exists()) {
            exportedDb.delete();
        }
        helper.exportDatabase(exportedDb);
        SQLiteDatabase exported = SQLiteDatabase.openDatabase(exportedDb.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
        try {
            assertFalse(hasTable(exported, "settings"));
        } finally {
            exported.close();
            exportedDb.delete();
        }

        File sourceDbFile = new File(context.getCacheDir(), "reader_import_with_settings.db");
        if (sourceDbFile.exists()) {
            sourceDbFile.delete();
        }
        SQLiteDatabase sourceDb = SQLiteDatabase.openOrCreateDatabase(sourceDbFile, null);
        try {
            sourceDb.execSQL("CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT)");
            sourceDb.execSQL("INSERT INTO settings(key, value) VALUES ('reader_pageMode', 'double')");
        } finally {
            sourceDb.close();
        }

        helper.importDatabase(sourceDbFile);
        SQLiteDatabase imported = SQLiteDatabase.openDatabase(context.getDatabasePath(DATABASE_NAME).getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
        try {
            assertFalse(hasTable(imported, "settings"));
        } finally {
            imported.close();
            sourceDbFile.delete();
        }
    }

    private boolean hasColumn(SQLiteDatabase db, String table, String column) {
        try (Cursor cursor = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            while (cursor.moveToNext()) {
                if (column.equalsIgnoreCase(cursor.getString(cursor.getColumnIndexOrThrow("name")))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasIndex(SQLiteDatabase db, String indexName) {
        try (Cursor cursor = db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='index' AND name=?",
                new String[]{indexName}
        )) {
            return cursor.moveToFirst();
        }
    }

    private boolean hasTable(SQLiteDatabase db, String tableName) {
        try (Cursor cursor = db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                new String[]{tableName}
        )) {
            return cursor.moveToFirst();
        }
    }

    private void resetHelperSingleton() throws Exception {
        Field instanceField = ReaderDatabaseHelper.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        ReaderDatabaseHelper helper = (ReaderDatabaseHelper) instanceField.get(null);
        if (helper != null) {
            helper.close();
        }
        instanceField.set(null, null);
    }

    private void deleteDatabaseFiles() {
        context.deleteDatabase(DATABASE_NAME);
        deleteSidecar("-wal");
        deleteSidecar("-shm");
    }

    private void deleteSidecar(String suffix) {
        File file = context.getDatabasePath(DATABASE_NAME + suffix);
        if (file.exists()) {
            file.delete();
        }
    }
}
