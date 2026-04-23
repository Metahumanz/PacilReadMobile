package com.metahumanz.pacilread.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.metahumanz.pacilread.model.BookRecord;

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
        } finally {
            upgradedDb.close();
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
