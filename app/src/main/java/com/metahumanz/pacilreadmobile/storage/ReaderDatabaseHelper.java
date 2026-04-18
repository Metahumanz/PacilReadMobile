package com.metahumanz.pacilreadmobile.storage;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.metahumanz.pacilreadmobile.model.BookRecord;
import com.metahumanz.pacilreadmobile.model.ChapterRecord;
import com.metahumanz.pacilreadmobile.model.ImportedBook;
import com.metahumanz.pacilreadmobile.model.ReaderThemeRecord;
import com.metahumanz.pacilreadmobile.model.ReplacementRuleRecord;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

public class ReaderDatabaseHelper extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "reader.db";
    private static final int DATABASE_VERSION = 3;

    private static ReaderDatabaseHelper instance;

    public static synchronized ReaderDatabaseHelper getInstance(Context context) {
        if (instance == null) {
            instance = new ReaderDatabaseHelper(context.getApplicationContext());
        }
        return instance;
    }

    private final Context appContext;

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
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            try {
                db.execSQL("ALTER TABLE books ADD COLUMN cover_path TEXT");
            } catch (Exception ignore) {
            }
            try {
                db.execSQL("ALTER TABLE books ADD COLUMN book_type TEXT NOT NULL DEFAULT 'text'");
            } catch (Exception ignore) {
            }
            db.execSQL("CREATE TABLE IF NOT EXISTS custom_themes (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "name TEXT NOT NULL UNIQUE," +
                    "config_json TEXT NOT NULL," +
                    "updated_at INTEGER NOT NULL" +
                    ")");
        }
        if (oldVersion < 3) {
            db.execSQL("CREATE TABLE IF NOT EXISTS custom_themes (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "name TEXT NOT NULL UNIQUE," +
                    "config_json TEXT NOT NULL," +
                    "updated_at INTEGER NOT NULL" +
                    ")");
        }
    }

    private void createAllTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE books (" +
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

        db.execSQL("CREATE TABLE chapters (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "book_id INTEGER NOT NULL," +
                "title TEXT NOT NULL," +
                "body_html TEXT NOT NULL," +
                "body_text TEXT NOT NULL," +
                "order_index INTEGER NOT NULL," +
                "FOREIGN KEY(book_id) REFERENCES books(id) ON DELETE CASCADE" +
                ")");

        db.execSQL("CREATE TABLE replacement_rules (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "pattern TEXT NOT NULL," +
                "replacement TEXT NOT NULL," +
                "scope TEXT NOT NULL DEFAULT 'global'," +
                "book_id INTEGER," +
                "is_regex INTEGER NOT NULL DEFAULT 0," +
                "active INTEGER NOT NULL DEFAULT 1" +
                ")");

        db.execSQL("CREATE TABLE custom_themes (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name TEXT NOT NULL UNIQUE," +
                "config_json TEXT NOT NULL," +
                "updated_at INTEGER NOT NULL" +
                ")");
    }

    public synchronized long insertImportedBook(ImportedBook importedBook) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues bookValues = new ContentValues();
            bookValues.put("title", importedBook.title);
            bookValues.put("author", importedBook.author);
            bookValues.put("local_path", importedBook.storedPath);
            bookValues.put("book_type", importedBook.bookType == null ? "text" : importedBook.bookType);
            bookValues.put("progress_index", 0);
            bookValues.put("progress_offset", 0);
            bookValues.put("last_read_at", System.currentTimeMillis());
            bookValues.put("pinned", 0);
            long bookId = db.insertOrThrow("books", null, bookValues);

            for (ImportedBook.ChapterSeed seed : importedBook.chapters) {
                ContentValues chapterValues = new ContentValues();
                chapterValues.put("book_id", bookId);
                chapterValues.put("title", seed.title);
                chapterValues.put("body_html", seed.bodyHtml);
                chapterValues.put("body_text", seed.bodyText);
                chapterValues.put("order_index", seed.orderIndex);
                db.insertOrThrow("chapters", null, chapterValues);
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
                "books",
                null,
                null,
                null,
                null,
                null,
                "pinned DESC, last_read_at DESC, title COLLATE NOCASE ASC"
        )) {
            while (cursor.moveToNext()) {
                books.add(readBook(cursor));
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
        List<ChapterRecord> chapters = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(
                "chapters",
                null,
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
                chapter.bodyHtml = cursor.getString(cursor.getColumnIndexOrThrow("body_html"));
                chapter.bodyText = cursor.getString(cursor.getColumnIndexOrThrow("body_text"));
                chapter.orderIndex = cursor.getInt(cursor.getColumnIndexOrThrow("order_index"));
                chapters.add(chapter);
            }
        }
        return chapters;
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
            db.delete("books", "id=?", new String[]{String.valueOf(bookId)});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        if (book != null) {
            deleteFileIfExists(book.localPath);
            deleteFileIfExists(book.coverPath);
        }
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
        ContentValues values = new ContentValues();
        values.put("progress_index", chapterIndex);
        values.put("progress_offset", Math.max(charOffset, 0));
        values.put("last_read_at", System.currentTimeMillis());
        getWritableDatabase().update("books", values, "id=?", new String[]{String.valueOf(bookId)});
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

    public synchronized File exportDatabase(File destination) throws IOException {
        close();
        copyFile(getDatabaseFile(), destination);
        getWritableDatabase();
        return destination;
    }

    public synchronized File exportLiteDatabase(File destination) throws IOException {
        if (destination.exists()) {
            destination.delete();
        }
        SQLiteDatabase liteDb = SQLiteDatabase.openOrCreateDatabase(destination, null);
        try {
            liteDb.execSQL("CREATE TABLE books (" +
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

            copyRows(getReadableDatabase(), liteDb, "books");
            copyRows(getReadableDatabase(), liteDb, "replacement_rules");
            copyRows(getReadableDatabase(), liteDb, "custom_themes");
        } finally {
            liteDb.close();
        }
        return destination;
    }

    public synchronized void importDatabase(File source) throws IOException {
        close();
        deleteSidecarFiles();
        copyFile(source, getDatabaseFile());
        getWritableDatabase();
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
        try (Cursor cursor = db.query("books", new String[]{"id", "local_path", "cover_path"}, null, null, null, null, null)) {
            while (cursor.moveToNext()) {
                long id = cursor.getLong(0);
                String localPath = cursor.getString(1);
                String coverPath = cursor.getString(2);
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
                    BookRecord match = findBook(existingBooks, title, author);

                    ContentValues values = new ContentValues();
                    values.put("title", title);
                    values.put("author", author);
                    values.put("local_path", rebasedAssetPath("books", sourceBooks.getString(sourceBooks.getColumnIndexOrThrow("local_path"))));
                    int coverIndex = sourceBooks.getColumnIndex("cover_path");
                    if (coverIndex >= 0 && !sourceBooks.isNull(coverIndex)) {
                        values.put("cover_path", rebasedAssetPath("covers", sourceBooks.getString(coverIndex)));
                    } else {
                        values.putNull("cover_path");
                    }
                    int typeIndex = sourceBooks.getColumnIndex("book_type");
                    values.put("book_type", typeIndex >= 0 ? sourceBooks.getString(typeIndex) : "text");
                    values.put("progress_index", sourceBooks.getInt(sourceBooks.getColumnIndexOrThrow("progress_index")));
                    values.put("progress_offset", sourceBooks.getInt(sourceBooks.getColumnIndexOrThrow("progress_offset")));
                    values.put("last_read_at", sourceBooks.getLong(sourceBooks.getColumnIndexOrThrow("last_read_at")));
                    values.put("pinned", sourceBooks.getInt(sourceBooks.getColumnIndexOrThrow("pinned")));

                    if (match != null) {
                        target.update("books", values, "id=?", new String[]{String.valueOf(match.id)});
                    } else {
                        target.insert("books", null, values);
                    }
                }
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

    private void deleteSidecarFiles() {
        deleteFileIfExists(getDatabaseFile().getAbsolutePath() + "-wal");
        deleteFileIfExists(getDatabaseFile().getAbsolutePath() + "-shm");
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

    private BookRecord findBook(List<BookRecord> books, String title, String author) {
        String targetKey = (title == null ? "" : title.trim().toLowerCase()) + "::" + (author == null ? "" : author.trim().toLowerCase());
        for (BookRecord book : books) {
            String currentKey = (book.title == null ? "" : book.title.trim().toLowerCase()) + "::" + (book.author == null ? "" : book.author.trim().toLowerCase());
            if (targetKey.equals(currentKey)) {
                return book;
            }
        }
        return null;
    }

    private String rebasedAssetPath(String folderName, String originalPath) {
        if (originalPath == null || originalPath.isBlank()) {
            return originalPath;
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
        book.localPath = cursor.getString(cursor.getColumnIndexOrThrow("local_path"));
        int coverIndex = cursor.getColumnIndex("cover_path");
        book.coverPath = coverIndex >= 0 ? cursor.getString(coverIndex) : null;
        int typeIndex = cursor.getColumnIndex("book_type");
        book.bookType = typeIndex >= 0 ? cursor.getString(typeIndex) : "text";
        book.progressIndex = cursor.getInt(cursor.getColumnIndexOrThrow("progress_index"));
        book.progressOffset = cursor.getInt(cursor.getColumnIndexOrThrow("progress_offset"));
        book.lastReadAt = cursor.getLong(cursor.getColumnIndexOrThrow("last_read_at"));
        book.pinned = cursor.getInt(cursor.getColumnIndexOrThrow("pinned")) == 1;
        return book;
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
}
