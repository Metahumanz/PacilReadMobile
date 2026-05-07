package com.metahumanz.pacilread.storage;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.util.Log;
import android.util.LruCache;

import com.metahumanz.pacilread.model.BookRecord;
import com.metahumanz.pacilread.model.BookmarkRecord;
import com.metahumanz.pacilread.model.ChapterRecord;
import com.metahumanz.pacilread.model.ImportedBook;
import com.metahumanz.pacilread.model.ReadingBookStatRecord;
import com.metahumanz.pacilread.model.ReadingTimeEntryRecord;
import com.metahumanz.pacilread.model.ReaderThemeRecord;
import com.metahumanz.pacilread.model.ReplacementRuleRecord;
import com.metahumanz.pacilread.stats.ReadingStatsUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.zip.GZIPOutputStream;

public class JsonDatabase {
    private static final String TAG = "JsonDatabase";
    private static final String DATABASE_DIR = "database";
    private static final String FILE_BOOKS = "books.json";
    private static final String FILE_CHAPTERS = "chapters.json";
    private static final String FILE_RULES = "rules.json";
    private static final String FILE_THEMES = "themes.json";
    private static final String FILE_BOOKMARKS = "bookmarks.json";
    private static final String FILE_READING_STATS = "reading_stats.json";

    private static volatile JsonDatabase instance;
    private final Context appContext;
    private final File dataDir;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    // 内存缓存
    private List<BookRecord> bookCache = new ArrayList<>();
    private List<ChapterRecord> chapterCache = new ArrayList<>();
    private List<ReplacementRuleRecord> ruleCache = new ArrayList<>();
    // 解压后的章节正文缓存（chapterId → 正文），避免重复解压 gzip 文件
    private final LruCache<Long, String> decompressedTextCache = new LruCache<>(20);
    private List<ReaderThemeRecord> themeCache = new ArrayList<>();
    private List<BookmarkRecord> bookmarkCache = new ArrayList<>();
    private List<ReadingTimeEntryRecord> readingStatsCache = new ArrayList<>();

    private boolean loaded = false;

    private JsonDatabase(Context context) {
        this.appContext = context.getApplicationContext();
        this.dataDir = new File(appContext.getFilesDir(), DATABASE_DIR);
    }

    public static synchronized JsonDatabase getInstance(Context context) {
        if (instance == null) {
            instance = new JsonDatabase(context);
            // 检查是否需要从 SQLite 迁移
            JsonDatabaseMigrator migrator = new JsonDatabaseMigrator(context);
            if (migrator.needsMigration()) {
                Log.i(TAG, "检测到旧版 SQLite 数据库，开始迁移...");
                // 删除可能残留的不完整 JSON 文件（上次迁移中断）
                instance.purgeDataDir();
                if (migrator.migrate()) {
                    // 验证所有关键文件均已写入
                    String[] requiredFiles = {FILE_BOOKS, FILE_CHAPTERS, FILE_RULES,
                            FILE_THEMES, FILE_BOOKMARKS, FILE_READING_STATS};
                    boolean allExist = true;
                    for (String f : requiredFiles) {
                        if (!new File(instance.dataDir, f).exists()) {
                            Log.e(TAG, "迁移后文件缺失：" + f);
                            allExist = false;
                        }
                    }
                    if (!allExist) {
                        Log.e(TAG, "迁移不完整，清理 JSON 并将在下次启动重试");
                        instance.purgeDataDir();
                    } else {
                        instance.loadAll();
                        instance.rebaseLocalAssetPaths();
                        Log.i(TAG, "迁移完成，路径已重定位");
                    }
                }
            }
        }
        return instance;
    }

    private void purgeDataDir() {
        if (dataDir.exists()) {
            File[] files = dataDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    f.delete();
                }
            }
        }
        dataDir.mkdirs();
    }

    // ========== 初始化 / 加载 ==========

    public synchronized void ensureLoaded() {
        if (loaded) return;
        if (!dataDir.exists()) {
            dataDir.mkdirs();
        }
        loadAll(); // loadAll 内部会调用 rebasePaths 重定位封面和源文件路径
        loaded = true;
    }

    private void loadAll() {
        lock.writeLock().lock();
        try {
            bookCache = loadList(FILE_BOOKS, BookRecord::fromJson);
            chapterCache = loadList(FILE_CHAPTERS, ChapterRecord::fromJson);
            ruleCache = loadList(FILE_RULES, ReplacementRuleRecord::fromJson);
            themeCache = loadList(FILE_THEMES, ReaderThemeRecord::fromJson);
            bookmarkCache = loadList(FILE_BOOKMARKS, BookmarkRecord::fromJson);
            readingStatsCache = loadList(FILE_READING_STATS, ReadingTimeEntryRecord::fromJson);
            // 加载后重定位路径：JSON 中存的是文件名，需拼接本地目录
            rebasePathsLocked();
            boolean booksChanged = backfillBookReadingStatsKeysLocked();
            boolean statsChanged = normalizeReadingStatsCacheLocked();
            if (booksChanged) {
                saveList(FILE_BOOKS, bookCache, BookRecord::toJson);
            }
            if (statsChanged) {
                saveList(FILE_READING_STATS, readingStatsCache, ReadingTimeEntryRecord::toJson);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 内部路径重定位（调用方必须已持有 writeLock） */
    private void rebasePathsLocked() {
        File booksDir = new File(appContext.getFilesDir(), "books");
        File coversDir = new File(appContext.getFilesDir(), "covers");
        if (!booksDir.exists()) booksDir.mkdirs();
        if (!coversDir.exists()) coversDir.mkdirs();
        for (BookRecord book : bookCache) {
            if (book.localPath != null && !book.localPath.isEmpty()) {
                book.localPath = new File(booksDir, new File(book.localPath).getName()).getAbsolutePath();
            }
            if (book.coverPath != null && !book.coverPath.isEmpty()) {
                book.coverPath = new File(coversDir, new File(book.coverPath).getName()).getAbsolutePath();
            }
        }
    }

    public synchronized void reloadFromDisk() {
        loadAll();
    }

    public synchronized void flush() {
        lock.writeLock().lock();
        try {
            saveList(FILE_BOOKS, bookCache, BookRecord::toJson);
            saveList(FILE_CHAPTERS, chapterCache, ChapterRecord::toJson);
            saveList(FILE_RULES, ruleCache, ReplacementRuleRecord::toJson);
            saveList(FILE_THEMES, themeCache, ReaderThemeRecord::toJson);
            saveList(FILE_BOOKMARKS, bookmarkCache, BookmarkRecord::toJson);
            saveList(FILE_READING_STATS, readingStatsCache, ReadingTimeEntryRecord::toJson);
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ========== 通用 JSON I/O ==========

    private interface JsonParser<T> {
        T parse(JSONObject json);
    }

    private interface JsonSerializer<T> {
        JSONObject serialize(T item);
    }

    private <T> List<T> loadList(String fileName, JsonParser<T> parser) {
        List<T> list = new ArrayList<>();
        File file = new File(dataDir, fileName);
        if (!file.exists()) return list;
        try {
            String content = readFileString(file);
            if (content == null || content.trim().isEmpty()) return list;
            JSONArray array = new JSONArray(content);
            for (int i = 0; i < array.length(); i++) {
                try {
                    T item = parser.parse(array.getJSONObject(i));
                    if (item != null) {
                        list.add(item);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "解析 " + fileName + " 第 " + i + " 条失败", e);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "加载 " + fileName + " 失败", e);
        }
        return list;
    }

    private <T> void saveList(String fileName, List<T> list, JsonSerializer<T> serializer) {
        File file = new File(dataDir, fileName);
        File tmp = new File(dataDir, fileName + ".tmp");
        try {
            JSONArray array = new JSONArray();
            for (T item : list) {
                JSONObject json = serializer.serialize(item);
                if (json != null) {
                    array.put(json);
                }
            }
            try (FileWriter writer = new FileWriter(tmp)) {
                writer.write(array.toString(2));
            }
            file.delete();
            if (!tmp.renameTo(file)) {
                Log.w(TAG, "原子写入 " + fileName + " 失败，尝试直接写入");
                try (FileWriter writer = new FileWriter(file)) {
                    writer.write(array.toString(2));
                }
                tmp.delete();
            }
        } catch (Exception e) {
            Log.e(TAG, "保存 " + fileName + " 失败", e);
        }
    }

    private String readFileString(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int offset = 0;
            while (offset < bytes.length) {
                int read = fis.read(bytes, offset, bytes.length - offset);
                if (read < 0) break;
                offset += read;
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    // ========== Books ==========

    public List<BookRecord> getBooks() {
        ensureLoaded();
        lock.readLock().lock();
        try {
            List<BookRecord> books = new ArrayList<>(bookCache);
            Collections.sort(books, (a, b) -> {
                if (a.pinned != b.pinned) return a.pinned ? -1 : 1;
                if (a.lastReadAt != b.lastReadAt) return Long.compare(b.lastReadAt, a.lastReadAt);
                String ta = a.title != null ? a.title.toLowerCase(Locale.ROOT) : "";
                String tb = b.title != null ? b.title.toLowerCase(Locale.ROOT) : "";
                return ta.compareTo(tb);
            });
            for (BookRecord book : books) {
                if (book.chapterCount > 0) {
                    book.progressIndex = Math.max(0, Math.min(book.progressIndex, book.chapterCount - 1));
                } else {
                    book.progressIndex = 0;
                    book.currentChapterTitle = "";
                }
            }
            return books;
        } finally {
            lock.readLock().unlock();
        }
    }

    public BookRecord getBook(long id) {
        ensureLoaded();
        lock.readLock().lock();
        try {
            for (BookRecord book : bookCache) {
                if (book.id == id) return cloneBook(book);
            }
            return null;
        } finally {
            lock.readLock().unlock();
        }
    }

    public long insertImportedBook(ImportedBook importedBook) {
        ensureLoaded();
        lock.writeLock().lock();
        try {
            long bookId = nextId(bookCache);
            long now = System.currentTimeMillis();

            BookRecord book = new BookRecord();
            book.id = bookId;
            book.title = importedBook.title;
            book.author = importedBook.author;
            book.localPath = importedBook.storedPath;
            book.coverPath = importedBook.coverPath;
            book.bookType = importedBook.bookType != null ? importedBook.bookType : "text";
            book.readingStatsKey = ReadingStatsUtils.buildBookIdentity(importedBook.title, importedBook.author);
            book.progressIndex = 0;
            book.progressOffset = 0;
            book.lastReadAt = now;
            book.pinned = false;
            book.chapterCount = importedBook.chapters.size();
            book.currentChapterTitle = importedBook.chapters.isEmpty() ? "" :
                    (importedBook.chapters.get(0).title != null ? importedBook.chapters.get(0).title : "");
            book.createdAt = now;
            book.updatedAt = now;
            bookCache.add(book);

            for (ImportedBook.ChapterSeed seed : importedBook.chapters) {
                long chapterId = nextId(chapterCache);
                ChapterRecord chapter = new ChapterRecord();
                chapter.id = chapterId;
                chapter.bookId = bookId;
                chapter.title = seed.title;
                chapter.orderIndex = seed.orderIndex;
                chapter.bodyHtml = "";
                chapter.bodyText = "";
                chapter.bodyTextStorage = "file_gzip";
                String bodyText = seed.bodyText == null ? "" : seed.bodyText;
                if (!bodyText.isEmpty()) {
                    try {
                        writeChapterTextToFile(bookId, chapterId, bodyText);
                        chapter.bodyTextPath = buildChapterTextRelativePath(bookId, chapterId);
                        chapter.bodyTextSize = bodyText.getBytes(StandardCharsets.UTF_8).length;
                    } catch (IOException e) {
                        Log.w(TAG, "写入章节外置正文失败, 回退数据库存储 chapter " + chapterId, e);
                        chapter.bodyText = bodyText;
                        chapter.bodyTextStorage = "db";
                        chapter.bodyTextPath = null;
                        chapter.bodyTextSize = 0;
                    }
                }
                chapterCache.add(chapter);
            }

            saveBooksAndChapters();
            return bookId;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void updateBookInfo(long bookId, String title, String author) {
        ensureLoaded();
        lock.writeLock().lock();
        try {
            for (BookRecord book : bookCache) {
                if (book.id == bookId) {
                    String previousKey = book.readingStatsKey;
                    String previousTitle = book.title;
                    String previousAuthor = book.author;
                    String nextKey = ReadingStatsUtils.buildBookIdentity(title, author);
                    book.title = title;
                    book.author = author;
                    book.readingStatsKey = nextKey;
                    book.updatedAt = System.currentTimeMillis();
                    boolean statsChanged = migrateReadingStatsForBookRenameLocked(
                            previousKey,
                            previousTitle,
                            previousAuthor,
                            nextKey,
                            title,
                            author
                    );
                    saveList(FILE_BOOKS, bookCache, BookRecord::toJson);
                    if (statsChanged) {
                        saveList(FILE_READING_STATS, readingStatsCache, ReadingTimeEntryRecord::toJson);
                    }
                    return;
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void deleteBook(long bookId) {
        lock.writeLock().lock();
        try {
            BookRecord target = null;
            for (BookRecord book : bookCache) {
                if (book.id == bookId) { target = book; break; }
            }
            if (target != null) {
                bookCache.remove(target);
            }
            // 驱逐该书籍所有章节的解压缓存（必须在 removeIf 之前，否则章节已移除）
            for (ChapterRecord ch : chapterCache) {
                if (ch.bookId == bookId) {
                    decompressedTextCache.remove(ch.id);
                }
            }
            chapterCache.removeIf(c -> c.bookId == bookId);
            ruleCache.removeIf(r -> r.bookId != null && r.bookId == bookId);
            bookmarkCache.removeIf(b -> b.bookId == bookId);
            saveBooksAndChapters();
            saveList(FILE_RULES, ruleCache, ReplacementRuleRecord::toJson);
            saveList(FILE_BOOKMARKS, bookmarkCache, BookmarkRecord::toJson);
            if (target != null) {
                deleteFileIfExists(target.localPath);
                deleteFileIfExists(target.coverPath);
            }
            deleteChapterTextDir(bookId);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void setPinned(long bookId, boolean pinned) {
        lock.writeLock().lock();
        try {
            for (BookRecord book : bookCache) {
                if (book.id == bookId) {
                    book.pinned = pinned;
                    book.updatedAt = System.currentTimeMillis();
                    saveList(FILE_BOOKS, bookCache, BookRecord::toJson);
                    return;
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void setCoverPath(long bookId, String coverPath) {
        lock.writeLock().lock();
        try {
            for (BookRecord book : bookCache) {
                if (book.id == bookId) {
                    book.coverPath = coverPath;
                    book.updatedAt = System.currentTimeMillis();
                    saveList(FILE_BOOKS, bookCache, BookRecord::toJson);
                    return;
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void updateProgress(long bookId, int chapterIndex, int charOffset) {
        lock.writeLock().lock();
        try {
            for (BookRecord book : bookCache) {
                if (book.id == bookId) {
                    book.progressIndex = chapterIndex;
                    book.progressOffset = charOffset;
                    book.lastReadAt = System.currentTimeMillis();
                    book.updatedAt = book.lastReadAt;
                    book.currentChapterTitle = chapterTitleForProgressLocked(bookId, chapterIndex);
                    saveList(FILE_BOOKS, bookCache, BookRecord::toJson);
                    return;
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void updateProgressFromRemote(long bookId, int chapterIndex, int charOffset, long remoteLastReadAt) {
        lock.writeLock().lock();
        try {
            for (BookRecord book : bookCache) {
                if (book.id == bookId) {
                    long safeRemoteLastReadAt = Math.max(remoteLastReadAt, 0L);
                    book.progressIndex = chapterIndex;
                    book.progressOffset = Math.max(charOffset, 0);
                    book.lastReadAt = safeRemoteLastReadAt;
                    book.updatedAt = safeRemoteLastReadAt;
                    book.currentChapterTitle = chapterTitleForProgressLocked(bookId, chapterIndex);
                    saveList(FILE_BOOKS, bookCache, BookRecord::toJson);
                    return;
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    private String chapterTitleForProgressLocked(long bookId, int chapterIndex) {
        for (ChapterRecord ch : chapterCache) {
            if (ch.bookId == bookId && ch.orderIndex == chapterIndex) {
                return ch.title != null ? ch.title : "";
            }
        }
        return "";
    }

    public long getMostRecentBookId() {
        ensureLoaded();
        lock.readLock().lock();
        try {
            long maxId = -1;
            long maxTime = 0;
            for (BookRecord book : bookCache) {
                if (book.lastReadAt > maxTime) {
                    maxTime = book.lastReadAt;
                    maxId = book.id;
                }
            }
            return maxId;
        } finally {
            lock.readLock().unlock();
        }
    }

    public BookRecord findBookByReadingStatsKey(String readingStatsKey) {
        return findBookForReadingStats(readingStatsKey, null, null);
    }

    public BookRecord findBookForReadingStats(String readingStatsKey, String title, String author) {
        ensureLoaded();
        lock.readLock().lock();
        try {
            BookRecord matched = findBookForReadingStatsLocked(readingStatsKey, title, author);
            return matched == null ? null : cloneBook(matched);
        } finally {
            lock.readLock().unlock();
        }
    }

    private BookRecord findBookForReadingStatsLocked(String readingStatsKey, String title, String author) {
        if (readingStatsKey != null && !readingStatsKey.isBlank()) {
            for (BookRecord book : bookCache) {
                if (readingStatsKey.equals(book.readingStatsKey)) {
                    return book;
                }
            }
        }
        String targetTitleAuthorKey = titleAuthorKeyOrEmpty(title, author);
        if (targetTitleAuthorKey.isEmpty()) {
            return null;
        }
        for (BookRecord book : bookCache) {
            if (targetTitleAuthorKey.equals(titleAuthorKeyOrEmpty(book.title, book.author))) {
                return book;
            }
        }
        return null;
    }

    private void saveBooksAndChapters() {
        saveList(FILE_BOOKS, bookCache, BookRecord::toJson);
        saveList(FILE_CHAPTERS, chapterCache, ChapterRecord::toJson);
    }

    private BookRecord cloneBook(BookRecord src) {
        BookRecord book = new BookRecord();
        book.id = src.id;
        book.title = src.title;
        book.author = src.author;
        book.localPath = src.localPath;
        book.coverPath = src.coverPath;
        book.bookType = src.bookType;
        book.readingStatsKey = src.readingStatsKey;
        book.progressIndex = src.progressIndex;
        book.progressOffset = src.progressOffset;
        book.lastReadAt = src.lastReadAt;
        book.pinned = src.pinned;
        book.currentChapterTitle = src.currentChapterTitle;
        book.chapterCount = src.chapterCount;
        book.createdAt = src.createdAt;
        book.updatedAt = src.updatedAt;
        return book;
    }

    // ========== Chapters ==========

    public List<ChapterRecord> getChapters(long bookId) {
        return getChapters(bookId, false);
    }

    public List<ChapterRecord> getChapters(long bookId, boolean includeContent) {
        ensureLoaded();
        lock.readLock().lock();
        try {
            List<ChapterRecord> chapters = new ArrayList<>();
            for (ChapterRecord ch : chapterCache) {
                if (ch.bookId == bookId) {
                    ChapterRecord copy = new ChapterRecord();
                    copy.id = ch.id;
                    copy.bookId = ch.bookId;
                    copy.title = ch.title;
                    copy.bodyHtml = "";
                    copy.orderIndex = ch.orderIndex;
                    copy.bodyTextPath = ch.bodyTextPath;
                    copy.bodyTextStorage = ch.bodyTextStorage;
                    copy.bodyTextSize = ch.bodyTextSize;
                    if (includeContent) {
                        copy.bodyText = resolveChapterText(ch.bookId, ch.id, null, ch.bodyTextPath, ch.bodyTextStorage);
                    }
                    chapters.add(copy);
                }
            }
            Collections.sort(chapters, Comparator.comparingInt(a -> a.orderIndex));
            return chapters;
        } finally {
            lock.readLock().unlock();
        }
    }

    public int getChapterCount(long bookId) {
        int count = 0;
        lock.readLock().lock();
        try {
            for (ChapterRecord ch : chapterCache) {
                if (ch.bookId == bookId) count++;
            }
        } finally {
            lock.readLock().unlock();
        }
        return count;
    }

    public ChapterRecord getChapterContent(long chapterId) {
        ensureLoaded();
        lock.readLock().lock();
        try {
            for (ChapterRecord ch : chapterCache) {
                if (ch.id == chapterId) {
                    ChapterRecord copy = new ChapterRecord();
                    copy.id = ch.id;
                    copy.bookId = ch.bookId;
                    copy.bodyHtml = "";
                    copy.bodyText = resolveChapterText(ch.bookId, ch.id, null, ch.bodyTextPath, ch.bodyTextStorage);
                    return copy;
                }
            }
            return null;
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<ChapterRecord> getChaptersWithExternalStorage(long bookId) {
        ensureLoaded();
        lock.readLock().lock();
        try {
            List<ChapterRecord> chapters = new ArrayList<>();
            for (ChapterRecord ch : chapterCache) {
                if (ch.bookId == bookId && "file_gzip".equals(ch.bodyTextStorage) &&
                        ch.bodyTextPath != null && !ch.bodyTextPath.isEmpty()) {
                    chapters.add(ch);
                }
            }
            Collections.sort(chapters, Comparator.comparingInt(a -> a.orderIndex));
            return chapters;
        } finally {
            lock.readLock().unlock();
        }
    }

    // ========== Replacement Rules ==========

    public List<ReplacementRuleRecord> getReplacementRules(long bookId) {
        ensureLoaded();
        lock.readLock().lock();
        try {
            List<ReplacementRuleRecord> rules = new ArrayList<>();
            for (ReplacementRuleRecord rule : ruleCache) {
                if ("global".equals(rule.scope) ||
                        ("book".equals(rule.scope) && rule.bookId != null && rule.bookId == bookId)) {
                    ReplacementRuleRecord copy = cloneRule(rule);
                    rules.add(copy);
                }
            }
            Collections.sort(rules, (a, b) -> {
                if (!a.scope.equals(b.scope)) return "global".equals(a.scope) ? -1 : 1;
                return Long.compare(b.id, a.id);
            });
            return rules;
        } finally {
            lock.readLock().unlock();
        }
    }

    public void addReplacementRule(String pattern, String replacement, boolean global, long bookId, boolean regex) {
        lock.writeLock().lock();
        try {
            ReplacementRuleRecord rule = new ReplacementRuleRecord();
            rule.id = nextId(ruleCache);
            rule.pattern = pattern;
            rule.replacement = replacement;
            rule.scope = global ? "global" : "book";
            rule.bookId = global ? null : bookId;
            rule.regex = regex;
            rule.active = true;
            rule.updatedAt = System.currentTimeMillis();
            ruleCache.add(rule);
            saveList(FILE_RULES, ruleCache, ReplacementRuleRecord::toJson);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void toggleReplacementRule(long ruleId, boolean active) {
        lock.writeLock().lock();
        try {
            for (ReplacementRuleRecord rule : ruleCache) {
                if (rule.id == ruleId) {
                    rule.active = active;
                    rule.updatedAt = System.currentTimeMillis();
                    saveList(FILE_RULES, ruleCache, ReplacementRuleRecord::toJson);
                    return;
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void deleteReplacementRule(long ruleId) {
        lock.writeLock().lock();
        try {
            ruleCache.removeIf(r -> r.id == ruleId);
            saveList(FILE_RULES, ruleCache, ReplacementRuleRecord::toJson);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private ReplacementRuleRecord cloneRule(ReplacementRuleRecord src) {
        ReplacementRuleRecord rule = new ReplacementRuleRecord();
        rule.id = src.id;
        rule.pattern = src.pattern;
        rule.replacement = src.replacement;
        rule.scope = src.scope;
        rule.bookId = src.bookId;
        rule.regex = src.regex;
        rule.active = src.active;
        rule.updatedAt = src.updatedAt;
        return rule;
    }

    // ========== Custom Themes ==========

    public List<ReaderThemeRecord> getCustomThemes() {
        ensureLoaded();
        lock.readLock().lock();
        try {
            List<ReaderThemeRecord> themes = new ArrayList<>();
            for (ReaderThemeRecord theme : themeCache) {
                ReaderThemeRecord copy = new ReaderThemeRecord();
                copy.id = theme.id;
                copy.name = theme.name;
                copy.configJson = theme.configJson;
                copy.updatedAt = theme.updatedAt;
                themes.add(copy);
            }
            return themes;
        } finally {
            lock.readLock().unlock();
        }
    }

    public void saveCustomTheme(String name, String configJson) {
        lock.writeLock().lock();
        try {
            long now = System.currentTimeMillis();
            for (ReaderThemeRecord theme : themeCache) {
                if (name.equals(theme.name)) {
                    theme.configJson = configJson;
                    theme.updatedAt = now;
                    saveList(FILE_THEMES, themeCache, ReaderThemeRecord::toJson);
                    return;
                }
            }
            ReaderThemeRecord theme = new ReaderThemeRecord();
            theme.id = nextId(themeCache);
            theme.name = name;
            theme.configJson = configJson;
            theme.updatedAt = now;
            themeCache.add(theme);
            saveList(FILE_THEMES, themeCache, ReaderThemeRecord::toJson);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void deleteCustomTheme(long themeId) {
        lock.writeLock().lock();
        try {
            themeCache.removeIf(t -> t.id == themeId);
            saveList(FILE_THEMES, themeCache, ReaderThemeRecord::toJson);
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ========== Bookmarks ==========

    public long upsertBookmark(BookmarkRecord bookmark) {
        lock.writeLock().lock();
        try {
            if (bookmark.uuid == null || bookmark.uuid.isEmpty()) {
                bookmark.uuid = UUID.randomUUID().toString();
            }
            long now = System.currentTimeMillis();
            if (bookmark.createdAt == 0) bookmark.createdAt = now;
            bookmark.updatedAt = now;

            for (int i = 0; i < bookmarkCache.size(); i++) {
                BookmarkRecord existing = bookmarkCache.get(i);
                if (bookmark.uuid.equals(existing.uuid)) {
                    bookmark.id = existing.id;
                    bookmarkCache.set(i, bookmark);
                    saveList(FILE_BOOKMARKS, bookmarkCache, BookmarkRecord::toJson);
                    return existing.id;
                }
            }
            bookmark.id = nextId(bookmarkCache);
            bookmarkCache.add(bookmark);
            saveList(FILE_BOOKMARKS, bookmarkCache, BookmarkRecord::toJson);
            return bookmark.id;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<BookmarkRecord> getBookmarks() {
        ensureLoaded();
        lock.readLock().lock();
        try {
            return new ArrayList<>(bookmarkCache);
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<BookmarkRecord> getBookmarksForBook(long bookId, String bookIdentity) {
        ensureLoaded();
        lock.readLock().lock();
        try {
            List<BookmarkRecord> list = new ArrayList<>();
            for (BookmarkRecord bm : bookmarkCache) {
                if (bm.bookId == bookId ||
                        (bookIdentity != null && !bookIdentity.isEmpty() && bookIdentity.equals(bm.bookIdentity))) {
                    list.add(bm);
                }
            }
            return list;
        } finally {
            lock.readLock().unlock();
        }
    }

    public void deleteBookmark(long bookmarkId) {
        lock.writeLock().lock();
        try {
            bookmarkCache.removeIf(b -> b.id == bookmarkId);
            saveList(FILE_BOOKMARKS, bookmarkCache, BookmarkRecord::toJson);
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ========== Reading Stats ==========

    public void recordReadingStats(String date, int durationSeconds, int charCount) {
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

    public void recordReadingDuration(
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
        ensureLoaded();
        lock.writeLock().lock();
        try {
            String safeDeviceId = sourceDeviceId != null ? sourceDeviceId : "";
            String safeDate = date != null ? date : "";
            String safeTitle = ReadingStatsUtils.safeBookTitle(bookTitle);
            String safeAuthor = bookAuthor != null ? bookAuthor.trim() : "";
            String safeIdentity = canonicalReadingStatsIdentity(bookIdentity, safeTitle, safeAuthor);
            for (ReadingTimeEntryRecord entry : readingStatsCache) {
                if (safeDate.equals(entry.date) && safeDeviceId.equals(entry.sourceDeviceId) &&
                        sameReadingStatsBook(entry.bookIdentity, entry.bookTitle, entry.bookAuthor, safeIdentity, safeTitle, safeAuthor)) {
                    entry.durationSeconds += Math.max(durationSeconds, 0);
                    if (charCount > 0) entry.charCount += Math.max(charCount, 0);
                    entry.bookIdentity = safeIdentity;
                    entry.bookTitle = safeTitle;
                    entry.bookAuthor = safeAuthor;
                    entry.updatedAt = Math.max(updatedAt, 0L);
                    normalizeReadingStatsCacheLocked();
                    saveList(FILE_READING_STATS, readingStatsCache, ReadingTimeEntryRecord::toJson);
                    return;
                }
            }
            ReadingTimeEntryRecord entry = new ReadingTimeEntryRecord();
            entry.id = nextId(readingStatsCache);
            entry.date = safeDate;
            entry.sourceDeviceId = safeDeviceId;
            entry.bookIdentity = safeIdentity;
            entry.bookTitle = safeTitle;
            entry.bookAuthor = safeAuthor;
            entry.durationSeconds = Math.max(durationSeconds, 0);
            entry.charCount = Math.max(charCount, 0);
            entry.updatedAt = Math.max(updatedAt, 0L);
            readingStatsCache.add(entry);
            normalizeReadingStatsCacheLocked();
            saveList(FILE_READING_STATS, readingStatsCache, ReadingTimeEntryRecord::toJson);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public int getReadingDurationSeconds(String startDate, String endDate, String bookIdentity) {
        ensureLoaded();
        lock.readLock().lock();
        try {
            int total = 0;
            for (ReadingTimeEntryRecord entry : readingStatsCache) {
                if ((bookIdentity == null || bookIdentity.isEmpty() || bookIdentity.equals(entry.bookIdentity)) &&
                        entry.date.compareTo(startDate) >= 0 && entry.date.compareTo(endDate) <= 0) {
                    total += entry.durationSeconds;
                }
            }
            return total;
        } finally {
            lock.readLock().unlock();
        }
    }

    public int getReadingDurationSecondsForBook(String startDate, String endDate, String bookIdentity, String bookTitle, String bookAuthor) {
        ensureLoaded();
        lock.readLock().lock();
        try {
            int total = 0;
            for (ReadingTimeEntryRecord entry : readingStatsCache) {
                if (entry.date.compareTo(startDate) >= 0 && entry.date.compareTo(endDate) <= 0 &&
                        sameReadingStatsBook(entry.bookIdentity, entry.bookTitle, entry.bookAuthor, bookIdentity, bookTitle, bookAuthor)) {
                    total += entry.durationSeconds;
                }
            }
            return total;
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<ReadingBookStatRecord> getReadingBookStats(String startDate, String endDate) {
        ensureLoaded();
        lock.readLock().lock();
        try {
            Map<String, ReadingBookStatRecord> map = new HashMap<>();
            for (ReadingTimeEntryRecord entry : readingStatsCache) {
                if (entry.date.compareTo(startDate) >= 0 && entry.date.compareTo(endDate) <= 0) {
                    String key = readingStatsBookGroupKey(entry);
                    ReadingBookStatRecord stat = map.get(key);
                    if (stat == null) {
                        stat = new ReadingBookStatRecord();
                        stat.bookIdentity = canonicalReadingStatsIdentity(entry.bookIdentity, entry.bookTitle, entry.bookAuthor);
                        stat.bookTitle = entry.bookTitle;
                        stat.bookAuthor = entry.bookAuthor;
                        stat.totalDurationSeconds = 0;
                        stat.updatedAt = 0;
                        map.put(key, stat);
                    }
                    stat.totalDurationSeconds += entry.durationSeconds;
                    if (entry.updatedAt > stat.updatedAt) {
                        stat.updatedAt = entry.updatedAt;
                        stat.bookIdentity = canonicalReadingStatsIdentity(entry.bookIdentity, entry.bookTitle, entry.bookAuthor);
                        stat.bookTitle = entry.bookTitle;
                        stat.bookAuthor = entry.bookAuthor;
                    }
                }
            }
            // 补上本地书的封面路径
            for (ReadingBookStatRecord stat : map.values()) {
                BookRecord book = findBookForReadingStatsLocked(stat.bookIdentity, stat.bookTitle, stat.bookAuthor);
                if (book != null) {
                    stat.localBookId = book.id;
                    stat.localCoverPath = book.coverPath;
                }
            }
            List<ReadingBookStatRecord> list = new ArrayList<>(map.values());
            Collections.sort(list, (a, b) -> Long.compare(b.updatedAt, a.updatedAt));
            return list;
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<ReadingTimeEntryRecord> getReadingStatsRowsForSync(String sourceDeviceId) {
        ensureLoaded();
        List<ReadingTimeEntryRecord> rows = new ArrayList<>();
        String safeSourceDeviceId = sourceDeviceId == null ? "" : sourceDeviceId;
        lock.writeLock().lock();
        try {
            boolean changed = normalizeReadingStatsCacheLocked();
            if (changed) {
                saveList(FILE_READING_STATS, readingStatsCache, ReadingTimeEntryRecord::toJson);
            }
            for (ReadingTimeEntryRecord entry : readingStatsCache) {
                if (safeSourceDeviceId.equals(entry.sourceDeviceId)) {
                    rows.add(cloneReadingTimeEntry(entry));
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
        return rows;
    }

    public void mergeReadingStatsRows(List<ReadingTimeEntryRecord> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        ensureLoaded();
        List<ReadingTimeEntryRecord> normalizedRows = normalizeIncomingReadingStatsRows(rows);
        lock.writeLock().lock();
        try {
            for (ReadingTimeEntryRecord row : normalizedRows) {
                boolean found = false;
                for (int i = 0; i < readingStatsCache.size(); i++) {
                    ReadingTimeEntryRecord existing = readingStatsCache.get(i);
                    if (existing.date.equals(row.date) &&
                            existing.sourceDeviceId.equals(row.sourceDeviceId) &&
                            sameReadingStatsBook(existing.bookIdentity, existing.bookTitle, existing.bookAuthor,
                                    row.bookIdentity, row.bookTitle, row.bookAuthor)) {
                        if (row.updatedAt >= existing.updatedAt) {
                            row.id = existing.id;
                            readingStatsCache.set(i, row);
                        }
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    if (row.id == 0) row.id = nextId(readingStatsCache);
                    readingStatsCache.add(row);
                }
            }
            normalizeReadingStatsCacheLocked();
            saveList(FILE_READING_STATS, readingStatsCache, ReadingTimeEntryRecord::toJson);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean hasAnyReadingStats() {
        ensureLoaded();
        lock.readLock().lock();
        try {
            return !readingStatsCache.isEmpty();
        } finally {
            lock.readLock().unlock();
        }
    }

    public void clearReadingStats() {
        ensureLoaded();
        lock.writeLock().lock();
        try {
            readingStatsCache.clear();
            saveList(FILE_READING_STATS, readingStatsCache, ReadingTimeEntryRecord::toJson);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private boolean backfillBookReadingStatsKeysLocked() {
        boolean changed = false;
        for (BookRecord book : bookCache) {
            if (book.readingStatsKey == null || book.readingStatsKey.isBlank()) {
                book.readingStatsKey = ReadingStatsUtils.buildBookIdentity(book.title, book.author);
                changed = true;
            }
        }
        return changed;
    }

    private boolean migrateReadingStatsForBookRenameLocked(
            String previousKey,
            String previousTitle,
            String previousAuthor,
            String nextKey,
            String nextTitle,
            String nextAuthor
    ) {
        if ((previousKey == null || previousKey.isBlank())
                && titleAuthorKeyOrEmpty(previousTitle, previousAuthor).isEmpty()) {
            return false;
        }
        String safeNextTitle = ReadingStatsUtils.safeBookTitle(nextTitle);
        String safeNextAuthor = nextAuthor == null ? "" : nextAuthor.trim();
        boolean changed = false;
        for (ReadingTimeEntryRecord entry : readingStatsCache) {
            if (sameReadingStatsBook(entry.bookIdentity, entry.bookTitle, entry.bookAuthor,
                    previousKey, previousTitle, previousAuthor)) {
                if (!safeEquals(entry.bookIdentity, nextKey)
                        || !safeEquals(entry.bookTitle, safeNextTitle)
                        || !safeEquals(entry.bookAuthor, safeNextAuthor)) {
                    entry.bookIdentity = nextKey;
                    entry.bookTitle = safeNextTitle;
                    entry.bookAuthor = safeNextAuthor;
                    changed = true;
                }
            }
        }
        return normalizeReadingStatsCacheLocked() || changed;
    }

    private boolean normalizeReadingStatsCacheLocked() {
        if (readingStatsCache.isEmpty()) {
            return false;
        }
        Map<String, ReadingTimeEntryRecord> merged = new HashMap<>();
        boolean changed = false;
        for (ReadingTimeEntryRecord entry : readingStatsCache) {
            ReadingTimeEntryRecord normalized = cloneReadingTimeEntry(entry);
            if (normalizeReadingStatsEntry(normalized)) {
                changed = true;
            }
            String groupKey = readingStatsBucketGroupKey(normalized);
            ReadingTimeEntryRecord existing = merged.get(groupKey);
            if (existing == null) {
                merged.put(groupKey, normalized);
            } else {
                mergeReadingStatsTotals(existing, normalized);
                changed = true;
            }
        }
        if (changed || merged.size() != readingStatsCache.size()) {
            readingStatsCache = new ArrayList<>(merged.values());
            return true;
        }
        return false;
    }

    private List<ReadingTimeEntryRecord> normalizeIncomingReadingStatsRows(List<ReadingTimeEntryRecord> rows) {
        Map<String, ReadingTimeEntryRecord> merged = new HashMap<>();
        for (ReadingTimeEntryRecord row : rows) {
            if (row == null || row.date == null || row.date.isBlank()) {
                continue;
            }
            ReadingTimeEntryRecord normalized = cloneReadingTimeEntry(row);
            normalizeReadingStatsEntry(normalized);
            String groupKey = readingStatsBucketGroupKey(normalized);
            ReadingTimeEntryRecord existing = merged.get(groupKey);
            if (existing == null) {
                merged.put(groupKey, normalized);
            } else {
                mergeReadingStatsTotals(existing, normalized);
            }
        }
        return new ArrayList<>(merged.values());
    }

    private void mergeReadingStatsTotals(ReadingTimeEntryRecord target, ReadingTimeEntryRecord source) {
        target.durationSeconds += Math.max(source.durationSeconds, 0);
        target.charCount += Math.max(source.charCount, 0);
        if (target.id <= 0L && source.id > 0L) {
            target.id = source.id;
        }
        if (source.updatedAt >= target.updatedAt) {
            target.bookIdentity = source.bookIdentity;
            target.bookTitle = source.bookTitle;
            target.bookAuthor = source.bookAuthor;
        }
        target.updatedAt = Math.max(target.updatedAt, source.updatedAt);
    }

    private boolean normalizeReadingStatsEntry(ReadingTimeEntryRecord entry) {
        String safeDeviceId = entry.sourceDeviceId == null ? "" : entry.sourceDeviceId;
        String safeDate = entry.date == null ? "" : entry.date;
        String safeTitle = safeStatsTitle(entry.bookIdentity, entry.bookTitle);
        String safeAuthor = entry.bookAuthor == null ? "" : entry.bookAuthor.trim();
        String safeIdentity = canonicalReadingStatsIdentity(entry.bookIdentity, safeTitle, safeAuthor);
        int safeDuration = Math.max(entry.durationSeconds, 0);
        int safeCharCount = Math.max(entry.charCount, 0);
        long safeUpdatedAt = Math.max(entry.updatedAt, 0L);
        boolean changed = !safeEquals(entry.sourceDeviceId, safeDeviceId)
                || !safeEquals(entry.date, safeDate)
                || !safeEquals(entry.bookIdentity, safeIdentity)
                || !safeEquals(entry.bookTitle, safeTitle)
                || !safeEquals(entry.bookAuthor, safeAuthor)
                || entry.durationSeconds != safeDuration
                || entry.charCount != safeCharCount
                || entry.updatedAt != safeUpdatedAt;
        entry.sourceDeviceId = safeDeviceId;
        entry.date = safeDate;
        entry.bookIdentity = safeIdentity;
        entry.bookTitle = safeTitle;
        entry.bookAuthor = safeAuthor;
        entry.durationSeconds = safeDuration;
        entry.charCount = safeCharCount;
        entry.updatedAt = safeUpdatedAt;
        return changed;
    }

    private String safeStatsTitle(String bookIdentity, String bookTitle) {
        if (ReadingStatsUtils.LEGACY_BOOK_IDENTITY.equals(bookIdentity)
                && (bookTitle == null || bookTitle.isBlank())) {
            return ReadingStatsUtils.LEGACY_BOOK_TITLE;
        }
        return ReadingStatsUtils.safeBookTitle(bookTitle);
    }

    private String canonicalReadingStatsIdentity(String bookIdentity, String bookTitle, String bookAuthor) {
        String safeIdentity = bookIdentity == null ? "" : bookIdentity.trim();
        if (ReadingStatsUtils.LEGACY_BOOK_IDENTITY.equals(safeIdentity)) {
            return ReadingStatsUtils.LEGACY_BOOK_IDENTITY;
        }
        String titleAuthorKey = titleAuthorKeyOrEmpty(bookTitle, bookAuthor);
        if (!titleAuthorKey.isEmpty()) {
            return ReadingStatsUtils.buildBookIdentity(bookTitle, bookAuthor);
        }
        return safeIdentity;
    }

    private String readingStatsBookGroupKey(ReadingTimeEntryRecord entry) {
        String safeIdentity = canonicalReadingStatsIdentity(entry.bookIdentity, entry.bookTitle, entry.bookAuthor);
        if (ReadingStatsUtils.LEGACY_BOOK_IDENTITY.equals(safeIdentity)) {
            return "legacy:" + safeIdentity;
        }
        String titleAuthorKey = titleAuthorKeyOrEmpty(entry.bookTitle, entry.bookAuthor);
        return titleAuthorKey.isEmpty() ? "identity:" + safeIdentity : "title-author:" + titleAuthorKey;
    }

    private String readingStatsBucketGroupKey(ReadingTimeEntryRecord entry) {
        return (entry.sourceDeviceId == null ? "" : entry.sourceDeviceId)
                + "\n" + (entry.date == null ? "" : entry.date)
                + "\n" + readingStatsBookGroupKey(entry);
    }

    private boolean sameReadingStatsBook(
            String leftIdentity,
            String leftTitle,
            String leftAuthor,
            String rightIdentity,
            String rightTitle,
            String rightAuthor
    ) {
        String safeLeftIdentity = leftIdentity == null ? "" : leftIdentity.trim();
        String safeRightIdentity = rightIdentity == null ? "" : rightIdentity.trim();
        if (!safeLeftIdentity.isEmpty() && safeLeftIdentity.equals(safeRightIdentity)) {
            return true;
        }
        if (ReadingStatsUtils.LEGACY_BOOK_IDENTITY.equals(safeLeftIdentity)
                || ReadingStatsUtils.LEGACY_BOOK_IDENTITY.equals(safeRightIdentity)) {
            return false;
        }
        String leftTitleAuthorKey = titleAuthorKeyOrEmpty(leftTitle, leftAuthor);
        String rightTitleAuthorKey = titleAuthorKeyOrEmpty(rightTitle, rightAuthor);
        return !leftTitleAuthorKey.isEmpty() && leftTitleAuthorKey.equals(rightTitleAuthorKey);
    }

    private String titleAuthorKeyOrEmpty(String title, String author) {
        String normalizedTitle = ReadingStatsUtils.normalizeIdentityText(title);
        String normalizedAuthor = ReadingStatsUtils.normalizeIdentityText(author);
        if (normalizedTitle.isEmpty() && normalizedAuthor.isEmpty()) {
            return "";
        }
        return normalizedTitle + "::" + normalizedAuthor;
    }

    private ReadingTimeEntryRecord cloneReadingTimeEntry(ReadingTimeEntryRecord source) {
        ReadingTimeEntryRecord clone = new ReadingTimeEntryRecord();
        clone.id = source.id;
        clone.date = source.date;
        clone.sourceDeviceId = source.sourceDeviceId;
        clone.bookIdentity = source.bookIdentity;
        clone.bookTitle = source.bookTitle;
        clone.bookAuthor = source.bookAuthor;
        clone.durationSeconds = source.durationSeconds;
        clone.charCount = source.charCount;
        clone.updatedAt = source.updatedAt;
        return clone;
    }

    private boolean safeEquals(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    // ========== 数据库健康 / 维护 ==========

    public boolean isDatabaseHealthyForStartup() {
        // JSON 文件不需要健康检查（不会像 SQLite 那样损坏）
        return true;
    }

    public void triggerStorageMaintenance() {
        cleanupTemporaryCacheFiles();
    }

    public interface MaintenanceProgressListener {
        void onPhaseStart(String phaseName);
        void onPhaseDone(String phaseName);
        void onAllDone();
        void onError(String errorMessage);
    }

    public String getPendingMaintenanceSummary() {
        long temporaryCacheSize = temporaryCacheSize();
        if (temporaryCacheSize > 0L) {
            return "可清理临时缓存 " + formatFileSize(temporaryCacheSize);
        }
        return "无需维护（JSON 存储）";
    }

    public boolean hasPendingMaintenanceWork() {
        return temporaryCacheSize() > 0L;
    }

    public void runStorageMaintenanceWithProgress(MaintenanceProgressListener listener) {
        try {
            if (listener != null) listener.onPhaseStart("清理临时缓存");
            cleanupTemporaryCacheFiles();
            if (listener != null) listener.onPhaseDone("清理临时缓存");
            if (listener != null) listener.onAllDone();
        } catch (Exception error) {
            if (listener != null) listener.onError(error.getMessage());
        }
    }

    // ========== 数据库文件信息 ==========

    public File getDatabaseFile() {
        return dataDir;
    }

    public File getDatabaseDir() {
        return dataDir;
    }

    public String getDatabaseSizeInfo() {
        ensureLoaded();
        File filesDir = appContext.getFilesDir();
        File chapterTextDir = getChapterTextDir();
        File coversDir = new File(filesDir, "covers");
        File booksDir = new File(filesDir, "books");
        File backgroundsDir = new File(filesDir, "backgrounds");
        File databasesDir = new File(appContext.getApplicationInfo().dataDir, "databases");
        File sharedPrefsDir = new File(appContext.getApplicationInfo().dataDir, "shared_prefs");

        long jsonSize = dirSize(dataDir);
        long chapterTextSize = dirSize(chapterTextDir);
        long coversSize = dirSize(coversDir);
        long booksSize = dirSize(booksDir);
        long backgroundsSize = dirSize(backgroundsDir);
        long otherFilesSize = dirChildrenSizeExcluding(
                filesDir,
                dataDir,
                chapterTextDir,
                coversDir,
                booksDir,
                backgroundsDir
        );
        long databasesSize = dirSize(databasesDir);
        long cacheSize = dirSize(appContext.getCacheDir());
        long codeCacheSize = dirSize(getCodeCacheDirCompat());
        long sharedPrefsSize = dirSize(sharedPrefsDir);
        long noBackupSize = dirSize(getNoBackupFilesDirCompat());
        long readingDataTotal = jsonSize + chapterTextSize + coversSize + booksSize
                + backgroundsSize + otherFilesSize + databasesSize;
        long privateDataTotal = readingDataTotal + cacheSize + codeCacheSize
                + sharedPrefsSize + noBackupSize;
        long installedPackageSize = installedPackageSizeEstimate();

        StringBuilder sb = new StringBuilder();
        sb.append("JSON 数据文件 ").append(formatFileSize(jsonSize))
                .append("\n章节正文文件 ").append(formatFileSize(chapterTextSize))
                .append("\n封面缓存 ").append(formatFileSize(coversSize))
                .append("\n源文件缓存 ").append(formatFileSize(booksSize))
                .append("\n自定义背景 ").append(formatFileSize(backgroundsSize))
                .append("\n其它 files ").append(formatFileSize(otherFilesSize));
        if (databasesSize > 0L) {
            sb.append("\n旧数据库/迁移残留 ").append(formatFileSize(databasesSize));
        }
        sb.append("\n阅读数据小计 ").append(formatFileSize(readingDataTotal));
        sb.append("\n──────────────────");
        sb.append("\n缓存目录 ").append(formatFileSize(cacheSize));
        appendDirectoryChildrenBreakdown(sb, appContext.getCacheDir(), "  ");
        sb.append("\n代码缓存 ").append(formatFileSize(codeCacheSize))
                .append("\n偏好设置 ").append(formatFileSize(sharedPrefsSize))
                .append("\nNo backup ").append(formatFileSize(noBackupSize));
        sb.append("\n应用私有数据合计 ").append(formatFileSize(privateDataTotal));
        sb.append("\n安装包/库文件估算 ").append(formatFileSize(installedPackageSize));
        sb.append("\n系统口径估算合计 ").append(formatFileSize(privateDataTotal + installedPackageSize));
        sb.append("\n──────────────────");
        sb.append("\nbooks: ").append(bookCache.size()).append(" 条");
        sb.append("\nchapters: ").append(chapterCache.size()).append(" 条");
        sb.append("\nrules: ").append(ruleCache.size()).append(" 条");
        sb.append("\nthemes: ").append(themeCache.size()).append(" 条");
        sb.append("\nbookmarks: ").append(bookmarkCache.size()).append(" 条");
        sb.append("\nreading_stats: ").append(readingStatsCache.size()).append(" 条");
        return sb.toString();
    }

    // ========== 路径重定位 ==========

    public void rebaseLocalAssetPaths() {
        lock.writeLock().lock();
        try {
            rebasePathsLocked();
            saveList(FILE_BOOKS, bookCache, BookRecord::toJson);
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ========== 兼容旧接口（无操作） ==========

    public void checkpoint() {
        // JSON 不需要 WAL checkpoint
    }

    public void vacuumIfNeeded() {
        // JSON 不需要 VACUUM
    }

    public void schedulePendingStorageMaintenance() {
        // JSON 不需要
    }

    public void backfillBookStatsKeys() {
        // 迁移时已处理
    }

    // ========== 文件导出/导入（兼容全量备份旧格式） ==========

    public File exportDatabase(File destination) throws IOException {
        flush();
        // 将所有 JSON 文件打包为 zip 式的行为不太合适，这里只确保最新数据已刷盘
        // 实际备份由 WebDavBackupManager 直接上传 JSON 文件
        return dataDir;
    }

    public File exportLiteDatabase(File destination) throws IOException {
        flush();
        return dataDir;
    }

    public void importDatabase(File source) throws IOException {
        // 由 WebDavBackupManager 下载 JSON 文件后调用 reloadFromDisk()
    }

    public void stripPlatformSettingsTable(File databaseFile) {
        // JSON 没有 settings 表，无需操作
    }

    public void mergeLiteDatabase(File source) {
        // 由 WebDavBackupManager 的逐实体合并逻辑替代
    }

    // ========== 章节正文文件管理 ==========

    public File getChapterTextDir() {
        return new File(appContext.getFilesDir(), "chapter_text");
    }

    public File resolveChapterTextFile(String bodyTextPath) {
        if (bodyTextPath == null || bodyTextPath.isEmpty()) return null;
        // 如果路径已经包含 chapter_text/ 前缀，直接拼 filesDir
        // 否则拼 chapter_text 目录（兼容旧数据库中的短格式路径 book_X/chapter_Y.txt.gz）
        if (bodyTextPath.startsWith("chapter_text/") || bodyTextPath.startsWith("chapter_text\\")) {
            return new File(appContext.getFilesDir(), bodyTextPath);
        }
        return new File(getChapterTextDir(), bodyTextPath);
    }

    public String resolveChapterText(long bookId, long chapterId, String bodyText,
                                     String bodyTextPath, String bodyTextStorage) {
        if ("file_gzip".equals(bodyTextStorage) && bodyTextPath != null && !bodyTextPath.isEmpty()) {
            // 先查解压缓存，避免重复 IO + 解压
            String cached = decompressedTextCache.get(chapterId);
            if (cached != null) {
                return cached;
            }
            File file = resolveChapterTextFile(bodyTextPath);
            if (file != null && file.exists()) {
                String text = readGzipFile(file);
                if (text.isEmpty()) {
                    Log.w(TAG, "章节正文文件为空: book=" + bookId + " chapter=" + chapterId + " path=" + bodyTextPath);
                } else {
                    decompressedTextCache.put(chapterId, text);
                }
                return text;
            } else {
                Log.w(TAG, "章节外置正文缺失: book=" + bookId + " chapter=" + chapterId +
                        " storage=" + bodyTextStorage + " path=" + bodyTextPath);
            }
        }
        // 回退：body_text 可能在 JSON/数据库中（"db" 模式），或文件缺失时的 fallback
        if (bodyText != null && !bodyText.isEmpty()) {
            return bodyText;
        }
        if (!"file_gzip".equals(bodyTextStorage) && (bodyTextPath == null || bodyTextPath.isEmpty())) {
            Log.w(TAG, "章节正文不可用: book=" + bookId + " chapter=" + chapterId +
                    " storage=" + bodyTextStorage + " path=" + bodyTextPath);
        }
        return "";
    }

    private String buildChapterTextRelativePath(long bookId, long chapterId) {
        return "chapter_text/book_" + bookId + "/chapter_" + chapterId + ".txt.gz";
    }

    private void writeChapterTextToFile(long bookId, long chapterId, String bodyText) throws IOException {
        File dir = new File(getChapterTextDir(), "book_" + bookId);
        if (!dir.exists()) dir.mkdirs();
        File file = new File(dir, "chapter_" + chapterId + ".txt.gz");
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(file);
             GZIPOutputStream gzOut = new GZIPOutputStream(fos)) {
            gzOut.write(bodyText.getBytes(StandardCharsets.UTF_8));
        }
    }

    private String readGzipFile(File file) {
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            try (java.io.FileInputStream fis = new java.io.FileInputStream(file);
                 java.util.zip.GZIPInputStream gzIn = new java.util.zip.GZIPInputStream(fis)) {
                byte[] buffer = new byte[32768];
                int read;
                while ((read = gzIn.read(buffer)) != -1) {
                    baos.write(buffer, 0, read);
                }
            }
            return baos.toString("UTF-8");
        } catch (Exception e) {
            Log.w(TAG, "读取 gzip 章节文件失败: " + file, e);
            return "";
        }
    }

    private void deleteChapterTextDir(long bookId) {
        File dir = new File(getChapterTextDir(), "book_" + bookId);
        deleteDir(dir);
    }

    // ========== 辅助方法 ==========

    private long nextId(List<?> list) {
        // 用当前最大 id + 1，如果没有则用时间戳
        long maxId = 0;
        for (Object obj : list) {
            long id = 0;
            if (obj instanceof BookRecord) id = ((BookRecord) obj).id;
            else if (obj instanceof ChapterRecord) id = ((ChapterRecord) obj).id;
            else if (obj instanceof ReplacementRuleRecord) id = ((ReplacementRuleRecord) obj).id;
            else if (obj instanceof ReaderThemeRecord) id = ((ReaderThemeRecord) obj).id;
            else if (obj instanceof BookmarkRecord) id = ((BookmarkRecord) obj).id;
            else if (obj instanceof ReadingTimeEntryRecord) id = ((ReadingTimeEntryRecord) obj).id;
            if (id > maxId) maxId = id;
        }
        if (maxId > 0) return maxId + 1;
        return System.currentTimeMillis() * 1000 + ThreadLocalRandom.current().nextInt(1000);
    }

    private long dirSize(File dir) {
        return pathSize(dir);
    }

    private long pathSize(File file) {
        if (file == null || !file.exists()) return 0L;
        if (file.isFile()) return file.length();
        if (!file.isDirectory()) return 0L;
        long size = 0L;
        File[] files = file.listFiles();
        if (files == null) return 0L;
        for (File child : files) {
            size += pathSize(child);
        }
        return size;
    }

    private long dirChildrenSizeExcluding(File parent, File... excluded) {
        if (parent == null || !parent.exists() || !parent.isDirectory()) return 0L;
        long total = 0L;
        File[] children = parent.listFiles();
        if (children == null) return 0L;
        for (File child : children) {
            if (matchesAnyPath(child, excluded)) {
                continue;
            }
            total += pathSize(child);
        }
        return total;
    }

    private boolean matchesAnyPath(File file, File... candidates) {
        if (file == null || candidates == null) return false;
        for (File candidate : candidates) {
            if (samePath(file, candidate)) {
                return true;
            }
        }
        return false;
    }

    private boolean samePath(File first, File second) {
        if (first == null || second == null) return false;
        try {
            return first.getCanonicalFile().equals(second.getCanonicalFile());
        } catch (IOException ignored) {
            return first.getAbsolutePath().equals(second.getAbsolutePath());
        }
    }

    private File getCodeCacheDirCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            return appContext.getCodeCacheDir();
        }
        return new File(appContext.getApplicationInfo().dataDir, "code_cache");
    }

    private File getNoBackupFilesDirCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            return appContext.getNoBackupFilesDir();
        }
        return new File(appContext.getApplicationInfo().dataDir, "no_backup");
    }

    private long installedPackageSizeEstimate() {
        ApplicationInfo info = appContext.getApplicationInfo();
        Set<String> seenPaths = new HashSet<>();
        long total = addUniquePathSize(seenPaths, info.sourceDir == null ? null : new File(info.sourceDir));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && info.splitSourceDirs != null) {
            for (String splitPath : info.splitSourceDirs) {
                total += addUniquePathSize(seenPaths, splitPath == null ? null : new File(splitPath));
            }
        }
        total += addUniquePathSize(seenPaths, info.nativeLibraryDir == null ? null : new File(info.nativeLibraryDir));
        return total;
    }

    private long addUniquePathSize(Set<String> seenPaths, File file) {
        if (file == null || !file.exists()) return 0L;
        String path;
        try {
            path = file.getCanonicalPath();
        } catch (IOException ignored) {
            path = file.getAbsolutePath();
        }
        if (!seenPaths.add(path)) return 0L;
        return pathSize(file);
    }

    private long temporaryCacheSize() {
        File cacheDir = appContext.getCacheDir();
        if (cacheDir == null) return 0L;
        long total = 0L;
        total += pathSize(new File(cacheDir, "backup"));
        total += pathSize(new File(cacheDir, "backup_restore"));
        total += pathSize(new File(cacheDir, "launch_sources"));
        File[] files = cacheDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile() && file.getName().startsWith("restore_")) {
                    total += file.length();
                }
            }
        }
        return total;
    }

    private void cleanupTemporaryCacheFiles() {
        File cacheDir = appContext.getCacheDir();
        if (cacheDir == null) return;
        deletePathRecursively(new File(cacheDir, "backup"));
        deletePathRecursively(new File(cacheDir, "backup_restore"));
        deletePathRecursively(new File(cacheDir, "launch_sources"));
        File[] files = cacheDir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isFile() && file.getName().startsWith("restore_")) {
                file.delete();
            }
        }
    }

    private void appendDirectoryChildrenBreakdown(StringBuilder sb, File dir, String indent) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return;
        File[] children = dir.listFiles();
        if (children == null || children.length == 0) return;
        List<FileSizeEntry> entries = new ArrayList<>();
        for (File child : children) {
            long size = pathSize(child);
            if (size > 0L) {
                entries.add(new FileSizeEntry(child, size));
            }
        }
        if (entries.isEmpty()) return;
        entries.sort((left, right) -> Long.compare(right.size, left.size));
        int limit = Math.min(entries.size(), 8);
        long hiddenSize = 0L;
        for (int i = 0; i < entries.size(); i++) {
            FileSizeEntry entry = entries.get(i);
            if (i < limit) {
                sb.append("\n").append(indent)
                        .append(cacheChildLabel(entry.file))
                        .append(" ")
                        .append(formatFileSize(entry.size));
            } else {
                hiddenSize += entry.size;
            }
        }
        if (hiddenSize > 0L) {
            sb.append("\n").append(indent)
                    .append("其它缓存项 ")
                    .append(formatFileSize(hiddenSize));
        }
    }

    private String cacheChildLabel(File file) {
        String name = file.getName();
        if ("backup".equals(name)) return "WebDAV备份临时 backup";
        if ("backup_restore".equals(name)) return "WebDAV恢复临时 backup_restore";
        if ("launch_sources".equals(name)) return "阅读页转场截图 launch_sources";
        if (name.startsWith("restore_")) return "WebDAV恢复临时 " + name;
        return name;
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

    private static final class FileSizeEntry {
        final File file;
        final long size;

        FileSizeEntry(File file, long size) {
            this.file = file;
            this.size = size;
        }
    }

    static String formatFileSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1048576) return String.format(Locale.ROOT, "%.1f KB", size / 1024.0);
        if (size < 1073741824) return String.format(Locale.ROOT, "%.2f MB", size / 1048576.0);
        return String.format(Locale.ROOT, "%.2f GB", size / 1073741824.0);
    }

    private void deleteFileIfExists(String path) {
        if (path == null || path.isEmpty()) return;
        File file = new File(path);
        if (file.exists()) file.delete();
    }

    private void deleteDir(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) f.delete();
        }
        dir.delete();
    }

    // ========== 供 WebDavBackupManager 使用的访问器 ==========

    public File getDataDir() {
        return dataDir;
    }

    public String getJsonFileName(String type) {
        switch (type) {
            case "books": return FILE_BOOKS;
            case "chapters": return FILE_CHAPTERS;
            case "rules": return FILE_RULES;
            case "themes": return FILE_THEMES;
            case "bookmarks": return FILE_BOOKMARKS;
            case "reading_stats": return FILE_READING_STATS;
            default: return null;
        }
    }

    /** 供 WebDavBackupManager 合并时使用 */
    public List<BookRecord> getBooksMutable() {
        ensureLoaded();
        return bookCache;
    }

    public List<ChapterRecord> getChaptersMutable() {
        ensureLoaded();
        return chapterCache;
    }

    public List<ReplacementRuleRecord> getRulesMutable() {
        ensureLoaded();
        return ruleCache;
    }

    public List<ReaderThemeRecord> getThemesMutable() {
        ensureLoaded();
        return themeCache;
    }

    public List<BookmarkRecord> getBookmarksMutable() {
        ensureLoaded();
        return bookmarkCache;
    }

    public List<ReadingTimeEntryRecord> getReadingStatsMutable() {
        ensureLoaded();
        return readingStatsCache;
    }

    public Context getAppContext() {
        return appContext;
    }
}
