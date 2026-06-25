package com.metahumanz.pacilread.storage

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Log
import android.util.LruCache
import com.metahumanz.pacilread.model.BookRecord
import com.metahumanz.pacilread.model.BookmarkRecord
import com.metahumanz.pacilread.model.ChapterRecord
import com.metahumanz.pacilread.model.ImportedBook
import com.metahumanz.pacilread.model.ReaderThemeRecord
import com.metahumanz.pacilread.model.ReadingBookStatRecord
import com.metahumanz.pacilread.model.ReadingTimeEntryRecord
import com.metahumanz.pacilread.model.ReplacementRuleRecord
import com.metahumanz.pacilread.stats.ReadingStatsUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class JsonDatabase private constructor(context: Context) {
    private val appContext: Context = context.applicationContext
    @get:JvmName("getDataDirProperty")
    val dataDir: File = File(appContext.filesDir, DATABASE_DIR)
    private val lock = ReentrantReadWriteLock()
    private val dirtyScheduleLock = Object()
    private val writeExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val dirtyFiles: MutableSet<String> = HashSet()
    private var pendingDirtyFlush: ScheduledFuture<*>? = null

    private var bookCache: MutableList<BookRecord> = ArrayList()
    private var chapterCache: MutableList<ChapterRecord> = ArrayList()
    private var ruleCache: MutableList<ReplacementRuleRecord> = ArrayList()
    private val decompressedTextCache = object : LruCache<Long, String>(MAX_DECOMPRESSED_TEXT_CACHE_BYTES) {
        override fun sizeOf(key: Long, value: String): Int = value.length * 2
    }
    private var themeCache: MutableList<ReaderThemeRecord> = ArrayList()
    private var bookmarkCache: MutableList<BookmarkRecord> = ArrayList()
    private var readingStatsCache: MutableList<ReadingTimeEntryRecord> = ArrayList()
    private val bookById: MutableMap<Long, BookRecord> = HashMap()
    private val chapterById: MutableMap<Long, ChapterRecord> = HashMap()
    private val chaptersByBookId: MutableMap<Long, MutableList<ChapterRecord>> = HashMap()
    private val globalRules: MutableList<ReplacementRuleRecord> = ArrayList()
    private val bookRulesByBookId: MutableMap<Long, MutableList<ReplacementRuleRecord>> = HashMap()
    private val bookmarksByBookId: MutableMap<Long, MutableList<BookmarkRecord>> = HashMap()
    private val bookmarksByIdentity: MutableMap<String, MutableList<BookmarkRecord>> = HashMap()

    private var loaded = false

    @get:JvmName("getBooksProperty")
    val books: MutableList<BookRecord>
        get() = getBooks()

    @get:JvmName("getBookmarksProperty")
    val bookmarks: MutableList<BookmarkRecord>
        get() = getBookmarks()

    @get:JvmName("getCustomThemesProperty")
    val customThemes: MutableList<ReaderThemeRecord>
        get() = getCustomThemes()

    @get:JvmName("getDatabaseSizeInfoProperty")
    val databaseSizeInfo: String
        get() = getDatabaseSizeInfo()

    @get:JvmName("getPendingMaintenanceSummaryProperty")
    val pendingMaintenanceSummary: String
        get() = getPendingMaintenanceSummary()

    @get:JvmName("getMostRecentBookIdProperty")
    val mostRecentBookId: Long
        get() = getMostRecentBookId()

    @get:JvmName("getRulesMutableProperty")
    val rulesMutable: MutableList<ReplacementRuleRecord>
        get() = getRulesMutable()

    @get:JvmName("isDatabaseHealthyForStartupProperty")
    val isDatabaseHealthyForStartup: Boolean
        get() = isDatabaseHealthyForStartup()

    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        if (!dataDir.exists()) dataDir.mkdirs()
        loadAll()
        loaded = true
    }

    private fun loadAll() {
        lock.writeLock().lock()
        try {
            bookCache = loadList(FILE_BOOKS, BookRecord::fromJson)
            chapterCache = loadList(FILE_CHAPTERS, ChapterRecord::fromJson)
            ruleCache = loadList(FILE_RULES, ReplacementRuleRecord::fromJson)
            themeCache = loadList(FILE_THEMES, ReaderThemeRecord::fromJson)
            bookmarkCache = loadList(FILE_BOOKMARKS, BookmarkRecord::fromJson)
            readingStatsCache = loadList(FILE_READING_STATS, ReadingTimeEntryRecord::fromJson)
            rebasePathsLocked()
            val booksChanged = backfillBookReadingStatsKeysLocked()
            val statsChanged = normalizeReadingStatsCacheLocked()
            rebuildIndexesLocked()
            if (booksChanged) saveList(FILE_BOOKS, bookCache, BookRecord::toJson)
            if (statsChanged) saveList(FILE_READING_STATS, readingStatsCache, ReadingTimeEntryRecord::toJson)
        } finally {
            lock.writeLock().unlock()
        }
    }

    private fun rebasePathsLocked() {
        val booksDir = File(appContext.filesDir, "books")
        val coversDir = File(appContext.filesDir, "covers")
        if (!booksDir.exists()) booksDir.mkdirs()
        if (!coversDir.exists()) coversDir.mkdirs()
        for (book in bookCache) {
            val localPath = book.localPath
            if (!localPath.isNullOrEmpty()) {
                book.localPath = File(booksDir, File(localPath).name).absolutePath
            }
            val coverPath = book.coverPath
            if (!coverPath.isNullOrEmpty()) {
                book.coverPath = File(coversDir, File(coverPath).name).absolutePath
            }
        }
    }

    private fun rebuildIndexesLocked() {
        bookById.clear()
        chapterById.clear()
        chaptersByBookId.clear()
        globalRules.clear()
        bookRulesByBookId.clear()
        bookmarksByBookId.clear()
        bookmarksByIdentity.clear()
        for (book in bookCache) {
            bookById[book.id] = book
        }
        for (chapter in chapterCache) {
            chapterById[chapter.id] = chapter
            val chapters = chaptersByBookId.getOrPut(chapter.bookId) { ArrayList() }
            chapters.add(chapter)
        }
        for (chapters in chaptersByBookId.values) {
            chapters.sortWith(Comparator.comparingInt { chapter -> chapter.orderIndex })
        }
        for (rule in ruleCache) {
            if (rule.scope == "global") {
                globalRules.add(rule)
            } else if (rule.scope == "book") {
                val bookId = rule.bookId
                if (bookId != null) bookRulesByBookId.getOrPut(bookId) { ArrayList() }.add(rule)
            }
        }
        sortRulesLocked(globalRules)
        for (rules in bookRulesByBookId.values) sortRulesLocked(rules)
        for (bookmark in bookmarkCache) {
            bookmarksByBookId.getOrPut(bookmark.bookId) { ArrayList() }.add(bookmark)
            val identity = bookmark.bookIdentity
            if (!identity.isNullOrEmpty()) {
                bookmarksByIdentity.getOrPut(identity) { ArrayList() }.add(bookmark)
            }
        }
    }

    private fun sortRulesLocked(rules: MutableList<ReplacementRuleRecord>) {
        rules.sortWith { a, b ->
            if (a.scope != b.scope) {
                if (a.scope == "global") -1 else 1
            } else {
                java.lang.Long.compare(b.id, a.id)
            }
        }
    }

    @Synchronized
    fun reloadFromDisk() {
        cancelPendingDirtyFlush()
        lock.writeLock().lock()
        try {
            dirtyFiles.clear()
        } finally {
            lock.writeLock().unlock()
        }
        loadAll()
    }

    @Synchronized
    fun flush() {
        cancelPendingDirtyFlush()
        lock.writeLock().lock()
        try {
            rebuildIndexesLocked()
            saveList(FILE_BOOKS, bookCache, BookRecord::toJson)
            saveList(FILE_CHAPTERS, chapterCache, ChapterRecord::toJson)
            saveList(FILE_RULES, ruleCache, ReplacementRuleRecord::toJson)
            saveList(FILE_THEMES, themeCache, ReaderThemeRecord::toJson)
            saveList(FILE_BOOKMARKS, bookmarkCache, BookmarkRecord::toJson)
            saveList(FILE_READING_STATS, readingStatsCache, ReadingTimeEntryRecord::toJson)
            dirtyFiles.clear()
        } finally {
            lock.writeLock().unlock()
        }
    }

    fun flushDirtyNow() {
        cancelPendingDirtyFlush()
        lock.writeLock().lock()
        try {
            if (dirtyFiles.isEmpty()) return
            val files = ArrayList(dirtyFiles)
            dirtyFiles.clear()
            for (fileName in files) saveDirtyFileLocked(fileName)
        } finally {
            lock.writeLock().unlock()
        }
    }

    fun shutdown() {
        flush()
        writeExecutor.shutdown()
    }

    private fun <T> loadList(fileName: String, parser: (JSONObject) -> T?): MutableList<T> {
        val file = File(dataDir, fileName)
        val backup = File(dataDir, fileName + ".bak")
        var list = loadListFromFile(file, parser)
        if (list != null) return list
        list = loadListFromFile(backup, parser)
        if (list != null) {
            Log.w(TAG, "使用备份 JSON 恢复 " + fileName)
            return list
        }
        return ArrayList()
    }

    private fun <T> loadListFromFile(file: File, parser: (JSONObject) -> T?): MutableList<T>? {
        val list: MutableList<T> = ArrayList()
        if (!file.exists()) return null
        try {
            val content = readFileString(file)
            if (content == null || content.trim().isEmpty()) return null
            val array = JSONArray(content)
            for (i in 0 until array.length()) {
                try {
                    val item = parser(array.getJSONObject(i))
                    if (item != null) list.add(item)
                } catch (e: Exception) {
                    Log.w(TAG, "解析 " + file.name + " 第 " + i + " 条失败", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "加载 " + file.name + " 失败", e)
            return null
        }
        return list
    }

    private fun <T> saveList(fileName: String, list: List<T>, serializer: (T) -> JSONObject?) {
        try {
            val array = JSONArray()
            for (item in list) {
                val json = serializer(item)
                if (json != null) array.put(json)
            }
            writeJsonFileAtomic(fileName, array)
            dirtyFiles.remove(fileName)
        } catch (e: Exception) {
            Log.e(TAG, "保存 " + fileName + " 失败", e)
        }
    }

    private fun markDirty(fileName: String) {
        dirtyFiles.add(fileName)
        synchronized(dirtyScheduleLock) {
            pendingDirtyFlush?.cancel(false)
            pendingDirtyFlush = writeExecutor.schedule(
                { flushDirtyNow() },
                WRITE_DEBOUNCE_MS,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    private fun cancelPendingDirtyFlush() {
        synchronized(dirtyScheduleLock) {
            pendingDirtyFlush?.cancel(false)
            pendingDirtyFlush = null
        }
    }

    private fun saveDirtyFileLocked(fileName: String) {
        when (fileName) {
            FILE_BOOKS -> saveList(FILE_BOOKS, bookCache, BookRecord::toJson)
            FILE_CHAPTERS -> saveList(FILE_CHAPTERS, chapterCache, ChapterRecord::toJson)
            FILE_RULES -> saveList(FILE_RULES, ruleCache, ReplacementRuleRecord::toJson)
            FILE_THEMES -> saveList(FILE_THEMES, themeCache, ReaderThemeRecord::toJson)
            FILE_BOOKMARKS -> saveList(FILE_BOOKMARKS, bookmarkCache, BookmarkRecord::toJson)
            FILE_READING_STATS -> saveList(FILE_READING_STATS, readingStatsCache, ReadingTimeEntryRecord::toJson)
        }
    }

    @Throws(Exception::class)
    private fun writeJsonFileAtomic(fileName: String, array: JSONArray) {
        if (!dataDir.exists() && !dataDir.mkdirs()) {
            throw IOException("无法创建 JSON 数据目录: " + dataDir.absolutePath)
        }
        val file = File(dataDir, fileName)
        val tmp = File(dataDir, fileName + ".tmp")
        val backup = File(dataDir, fileName + ".bak")
        FileOutputStream(tmp).use { outputStream ->
            OutputStreamWriter(outputStream, StandardCharsets.UTF_8).use { writer ->
                writer.write(array.toString(2))
                writer.flush()
                outputStream.fd.sync()
            }
        }
        if (backup.exists() && !backup.delete()) Log.w(TAG, "删除旧备份失败: " + backup.name)
        if (file.exists() && !file.renameTo(backup)) Log.w(TAG, "创建 JSON 备份失败: " + fileName)
        if (!tmp.renameTo(file)) {
            if (backup.exists() && !file.exists()) backup.renameTo(file)
            throw IOException("原子写入失败: " + fileName)
        }
    }

    private fun readFileString(file: File): String? {
        return try {
            FileInputStream(file).use { fis ->
                val bytes = ByteArray(file.length().toInt())
                var offset = 0
                while (offset < bytes.size) {
                    val read = fis.read(bytes, offset, bytes.size - offset)
                    if (read < 0) break
                    offset += read
                }
                String(bytes, StandardCharsets.UTF_8)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun getBooks(): MutableList<BookRecord> {
        ensureLoaded()
        lock.readLock().lock()
        try {
            val books = ArrayList(bookCache)
            books.sortWith { a, b ->
                when {
                    a.pinned != b.pinned -> if (a.pinned) -1 else 1
                    a.lastReadAt != b.lastReadAt -> java.lang.Long.compare(b.lastReadAt, a.lastReadAt)
                    else -> (a.title ?: "").lowercase(Locale.ROOT).compareTo((b.title ?: "").lowercase(Locale.ROOT))
                }
            }
            for (book in books) {
                if (book.chapterCount > 0) {
                    book.progressIndex = book.progressIndex.coerceIn(0, book.chapterCount - 1)
                } else {
                    book.progressIndex = 0
                    book.currentChapterTitle = ""
                }
            }
            return books
        } finally {
            lock.readLock().unlock()
        }
    }

    fun getBook(id: Long): BookRecord? {
        ensureLoaded()
        lock.readLock().lock()
        try {
            val book = bookById[id]
            return if (book == null) null else cloneBook(book)
        } finally {
            lock.readLock().unlock()
        }
    }

    fun insertImportedBook(importedBook: ImportedBook): Long {
        ensureLoaded()
        lock.writeLock().lock()
        try {
            val bookId = nextId(bookCache)
            val now = System.currentTimeMillis()

            val book = BookRecord()
            book.id = bookId
            book.title = importedBook.title
            book.author = importedBook.author
            book.localPath = importedBook.storedPath
            book.coverPath = importedBook.coverPath
            book.bookType = importedBook.bookType ?: "text"
            book.sourceDisplayName = importedBook.sourceDisplayName ?: ""
            book.contentSha256 = importedBook.contentSha256 ?: ""
            book.readingStatsKey = ReadingStatsUtils.buildBookIdentity(importedBook.title, importedBook.author)
            book.progressIndex = 0
            book.progressOffset = 0
            book.lastReadAt = now
            book.tags = ArrayList()
            book.series = ""
            book.seriesIndex = null
            book.readingStatus = BookRecord.STATUS_UNREAD
            book.pinned = false
            book.chapterCount = importedBook.chapters.size
            book.currentChapterTitle = if (importedBook.chapters.isEmpty()) "" else importedBook.chapters[0].title ?: ""
            book.createdAt = now
            book.updatedAt = now
            bookCache.add(book)

            for (seed in importedBook.chapters) {
                val chapterId = nextId(chapterCache)
                val chapter = ChapterRecord()
                chapter.id = chapterId
                chapter.bookId = bookId
                chapter.title = seed.title
                chapter.orderIndex = seed.orderIndex
                chapter.bodyHtml = ""
                chapter.bodyText = ""
                chapter.bodyTextStorage = "file_gzip"
                val bodyText = seed.bodyText ?: ""
                if (bodyText.isNotEmpty()) {
                    try {
                        writeChapterTextToFile(bookId, chapterId, bodyText)
                        chapter.bodyTextPath = buildChapterTextRelativePath(bookId, chapterId)
                        chapter.bodyTextSize = bodyText.toByteArray(StandardCharsets.UTF_8).size.toLong()
                    } catch (e: IOException) {
                        Log.w(TAG, "写入章节外置正文失败, 回退数据库存储 chapter " + chapterId, e)
                        chapter.bodyText = bodyText
                        chapter.bodyTextStorage = "db"
                        chapter.bodyTextPath = null
                        chapter.bodyTextSize = 0
                    }
                }
                chapterCache.add(chapter)
            }

            rebuildIndexesLocked()
            saveBooksAndChapters()
            return bookId
        } finally {
            lock.writeLock().unlock()
        }
    }

    fun updateBookInfo(bookId: Long, title: String?, author: String?) {
        ensureLoaded()
        lock.writeLock().lock()
        try {
            for (book in bookCache) {
                if (book.id == bookId) {
                    val previousKey = book.readingStatsKey
                    val previousTitle = book.title
                    val previousAuthor = book.author
                    val nextKey = ReadingStatsUtils.buildBookIdentity(title, author)
                    book.title = title
                    book.author = author
                    book.readingStatsKey = nextKey
                    book.updatedAt = System.currentTimeMillis()
                    val statsChanged = migrateReadingStatsForBookRenameLocked(
                        previousKey,
                        previousTitle,
                        previousAuthor,
                        nextKey,
                        title,
                        author,
                    )
                    saveList(FILE_BOOKS, bookCache, BookRecord::toJson)
                    if (statsChanged) saveList(FILE_READING_STATS, readingStatsCache, ReadingTimeEntryRecord::toJson)
                    return
                }
            }
        } finally {
            lock.writeLock().unlock()
        }
    }

    fun deleteBook(bookId: Long) {
        val ids: MutableSet<Long> = HashSet()
        ids.add(bookId)
        deleteBooks(ids)
    }

    fun deleteBooks(bookIds: Set<Long>?) {
        ensureLoaded()
        if (bookIds.isNullOrEmpty()) return
        lock.writeLock().lock()
        try {
            val targets: MutableList<BookRecord> = ArrayList()
            for (book in bookCache) if (bookIds.contains(book.id)) targets.add(book)
            for (chapter in chapterCache) if (bookIds.contains(chapter.bookId)) decompressedTextCache.remove(chapter.id)
            bookCache.removeAll { bookIds.contains(it.id) }
            chapterCache.removeAll { bookIds.contains(it.bookId) }
            ruleCache.removeAll { it.bookId != null && bookIds.contains(it.bookId) }
            bookmarkCache.removeAll { bookIds.contains(it.bookId) }
            rebuildIndexesLocked()
            saveBooksAndChapters()
            saveList(FILE_RULES, ruleCache, ReplacementRuleRecord::toJson)
            saveList(FILE_BOOKMARKS, bookmarkCache, BookmarkRecord::toJson)
            for (target in targets) {
                deleteFileIfExists(target.localPath)
                deleteFileIfExists(target.coverPath)
                deleteChapterTextDir(target.id)
            }
        } finally {
            lock.writeLock().unlock()
        }
    }

    fun updateBookClassification(bookId: Long, tags: List<String?>?, series: String?, status: String?) {
        ensureLoaded()
        lock.writeLock().lock()
        try {
            val book = bookById[bookId] ?: return
            book.tags = normalizeTags(tags)
            book.series = series?.trim() ?: ""
            book.readingStatus = BookRecord.normalizeReadingStatus(status, false)
            book.updatedAt = System.currentTimeMillis()
            saveList(FILE_BOOKS, bookCache, BookRecord::toJson)
        } finally {
            lock.writeLock().unlock()
        }
    }

    fun addTagsToBooks(bookIds: Set<Long>?, tags: List<String?>?) {
        mutateBookTags(bookIds, tags, true)
    }

    fun removeTagsFromBooks(bookIds: Set<Long>?, tags: List<String?>?) {
        mutateBookTags(bookIds, tags, false)
    }

    private fun mutateBookTags(bookIds: Set<Long>?, tags: List<String?>?, add: Boolean) {
        ensureLoaded()
        val normalized = normalizeTags(tags)
        if (bookIds.isNullOrEmpty() || normalized.isEmpty()) return
        lock.writeLock().lock()
        try {
            var changed = false
            val now = System.currentTimeMillis()
            for (book in bookCache) {
                if (!bookIds.contains(book.id)) continue
                val current = normalizeTags(book.tags)
                if (add) {
                    for (tag in normalized) if (!current.contains(tag)) current.add(tag)
                } else {
                    current.removeAll(normalized.toSet())
                }
                if (current != book.tags) {
                    book.tags = current
                    book.updatedAt = now
                    changed = true
                }
            }
            if (changed) saveList(FILE_BOOKS, bookCache, BookRecord::toJson)
        } finally {
            lock.writeLock().unlock()
        }
    }

    fun setSeriesForBooks(bookIds: Set<Long>?, series: String?) {
        ensureLoaded()
        if (bookIds.isNullOrEmpty()) return
        val safeSeries = series?.trim() ?: ""
        lock.writeLock().lock()
        try {
            var changed = false
            val now = System.currentTimeMillis()
            for (book in bookCache) {
                if (bookIds.contains(book.id) && safeSeries != (book.series ?: "")) {
                    book.series = safeSeries
                    book.updatedAt = now
                    changed = true
                }
            }
            if (changed) saveList(FILE_BOOKS, bookCache, BookRecord::toJson)
        } finally {
            lock.writeLock().unlock()
        }
    }

    fun setReadingStatusForBooks(bookIds: Set<Long>?, status: String?) {
        ensureLoaded()
        if (bookIds.isNullOrEmpty()) return
        val safeStatus = BookRecord.normalizeReadingStatus(status, false)
        lock.writeLock().lock()
        try {
            var changed = false
            val now = System.currentTimeMillis()
            for (book in bookCache) {
                if (bookIds.contains(book.id) && safeStatus != book.readingStatus) {
                    book.readingStatus = safeStatus
                    book.updatedAt = now
                    changed = true
                }
            }
            if (changed) saveList(FILE_BOOKS, bookCache, BookRecord::toJson)
        } finally {
            lock.writeLock().unlock()
        }
    }

    fun backfillMissingContentHashes(): MutableList<BookRecord> {
        ensureLoaded()
        lock.writeLock().lock()
        try {
            var changed = false
            for (book in bookCache) {
                if (!book.contentSha256.isNullOrBlank()) continue
                val source = book.localPath?.let { File(it) }
                if (source == null || !source.isFile) continue
                try {
                    book.contentSha256 = sha256(source)
                    changed = true
                } catch (error: Exception) {
                    Log.w(TAG, "计算书籍摘要失败: " + source, error)
                }
            }
            if (changed) saveList(FILE_BOOKS, bookCache, BookRecord::toJson)
            val result: MutableList<BookRecord> = ArrayList()
            for (book in bookCache) result.add(cloneBook(book))
            return result
        } finally {
            lock.writeLock().unlock()
        }
    }

    private fun normalizeTags(tags: List<String?>?): MutableList<String> {
        val result: MutableList<String> = ArrayList()
        if (tags == null) return result
        for (tag in tags) {
            val safe = tag?.trim() ?: ""
            if (safe.isNotEmpty() && !result.contains(safe)) result.add(safe)
        }
        return result
    }

    @Throws(Exception::class)
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        val value = StringBuilder(64)
        for (item in digest.digest()) value.append(String.format(Locale.ROOT, "%02x", item.toInt() and 0xff))
        return value.toString()
    }

    fun setPinned(bookId: Long, pinned: Boolean) {
        lock.writeLock().lock()
        try {
            for (book in bookCache) {
                if (book.id == bookId) {
                    book.pinned = pinned
                    book.updatedAt = System.currentTimeMillis()
                    saveList(FILE_BOOKS, bookCache, BookRecord::toJson)
                    return
                }
            }
        } finally {
            lock.writeLock().unlock()
        }
    }

    fun setCoverPath(bookId: Long, coverPath: String?) {
        lock.writeLock().lock()
        try {
            for (book in bookCache) {
                if (book.id == bookId) {
                    book.coverPath = coverPath
                    book.updatedAt = System.currentTimeMillis()
                    saveList(FILE_BOOKS, bookCache, BookRecord::toJson)
                    return
                }
            }
        } finally {
            lock.writeLock().unlock()
        }
    }

    fun updateProgress(bookId: Long, chapterIndex: Int, charOffset: Int) {
        lock.writeLock().lock()
        try {
            for (book in bookCache) {
                if (book.id == bookId) {
                    book.progressIndex = chapterIndex
                    book.progressOffset = charOffset
                    book.lastReadAt = System.currentTimeMillis()
                    book.updatedAt = book.lastReadAt
                    if (book.readingStatus != BookRecord.STATUS_FINISHED) book.readingStatus = BookRecord.STATUS_READING
                    book.currentChapterTitle = chapterTitleForProgressLocked(bookId, chapterIndex)
                    markDirty(FILE_BOOKS)
                    return
                }
            }
        } finally {
            lock.writeLock().unlock()
        }
    }

    fun updateProgressFromRemote(bookId: Long, chapterIndex: Int, charOffset: Int, remoteLastReadAt: Long) {
        lock.writeLock().lock()
        try {
            for (book in bookCache) {
                if (book.id == bookId) {
                    val safeRemoteLastReadAt = remoteLastReadAt.coerceAtLeast(0L)
                    book.progressIndex = chapterIndex
                    book.progressOffset = charOffset.coerceAtLeast(0)
                    book.lastReadAt = safeRemoteLastReadAt
                    book.updatedAt = safeRemoteLastReadAt
                    book.currentChapterTitle = chapterTitleForProgressLocked(bookId, chapterIndex)
                    markDirty(FILE_BOOKS)
                    return
                }
            }
        } finally {
            lock.writeLock().unlock()
        }
    }

    fun updateBookReadingStatus(bookId: Long, status: String?) {
        ensureLoaded()
        val safeStatus = BookRecord.normalizeReadingStatus(status, false)
        lock.writeLock().lock()
        try {
            for (book in bookCache) {
                if (book.id == bookId) {
                    if (safeStatus == book.readingStatus) return
                    book.readingStatus = safeStatus
                    book.updatedAt = System.currentTimeMillis()
                    saveList(FILE_BOOKS, bookCache, BookRecord::toJson)
                    return
                }
            }
        } finally {
            lock.writeLock().unlock()
        }
    }

    fun markBookReadingIfUnread(bookId: Long) {
        ensureLoaded()
        lock.writeLock().lock()
        try {
            for (book in bookCache) {
                if (book.id == bookId) {
                    if (book.readingStatus.isNullOrEmpty() || book.readingStatus == BookRecord.STATUS_UNREAD) {
                        book.readingStatus = BookRecord.STATUS_READING
                        book.updatedAt = System.currentTimeMillis()
                        saveList(FILE_BOOKS, bookCache, BookRecord::toJson)
                    }
                    return
                }
            }
        } finally {
            lock.writeLock().unlock()
        }
    }

    private fun chapterTitleForProgressLocked(bookId: Long, chapterIndex: Int): String {
        val chapters = chaptersByBookId[bookId] ?: return ""
        for (chapter in chapters) {
            if (chapter.bookId == bookId && chapter.orderIndex == chapterIndex) return chapter.title ?: ""
        }
        return ""
    }

    fun getMostRecentBookId(): Long {
        ensureLoaded()
        lock.readLock().lock()
        try {
            var maxId = -1L
            var maxTime = 0L
            for (book in bookCache) {
                if (book.lastReadAt > maxTime) {
                    maxTime = book.lastReadAt
                    maxId = book.id
                }
            }
            return maxId
        } finally {
            lock.readLock().unlock()
        }
    }

    fun findBookByReadingStatsKey(readingStatsKey: String?): BookRecord? = findBookForReadingStats(readingStatsKey, null, null)

    fun findBookForReadingStats(readingStatsKey: String?, title: String?, author: String?): BookRecord? {
        ensureLoaded()
        lock.readLock().lock()
        try {
            val matched = findBookForReadingStatsLocked(readingStatsKey, title, author)
            return if (matched == null) null else cloneBook(matched)
        } finally {
            lock.readLock().unlock()
        }
    }

    private fun findBookForReadingStatsLocked(readingStatsKey: String?, title: String?, author: String?): BookRecord? {
        if (!readingStatsKey.isNullOrBlank()) {
            for (book in bookCache) if (readingStatsKey == book.readingStatsKey) return book
        }
        val targetTitleAuthorKey = titleAuthorKeyOrEmpty(title, author)
        if (targetTitleAuthorKey.isEmpty()) return null
        for (book in bookCache) if (targetTitleAuthorKey == titleAuthorKeyOrEmpty(book.title, book.author)) return book
        return null
    }

    private fun saveBooksAndChapters() {
        saveList(FILE_BOOKS, bookCache, BookRecord::toJson)
        saveList(FILE_CHAPTERS, chapterCache, ChapterRecord::toJson)
    }

    private fun cloneBook(src: BookRecord): BookRecord {
        val book = BookRecord()
        book.id = src.id
        book.title = src.title
        book.author = src.author
        book.localPath = src.localPath
        book.coverPath = src.coverPath
        book.bookType = src.bookType
        book.readingStatsKey = src.readingStatsKey
        book.progressIndex = src.progressIndex
        book.progressOffset = src.progressOffset
        book.lastReadAt = src.lastReadAt
        book.pinned = src.pinned
        book.currentChapterTitle = src.currentChapterTitle
        book.chapterCount = src.chapterCount
        book.createdAt = src.createdAt
        book.updatedAt = src.updatedAt
        book.copyExtendedFieldsFrom(src)
        return book
    }

    fun getChapters(bookId: Long): MutableList<ChapterRecord> = getChapters(bookId, false)

    fun getChapters(bookId: Long, includeContent: Boolean): MutableList<ChapterRecord> {
        ensureLoaded()
        lock.readLock().lock()
        try {
            val chapters: MutableList<ChapterRecord> = ArrayList()
            val source = chaptersByBookId[bookId]
            if (source != null) {
                for (ch in source) {
                    val copy = ChapterRecord()
                    copy.id = ch.id
                    copy.bookId = ch.bookId
                    copy.title = ch.title
                    copy.bodyHtml = ""
                    copy.orderIndex = ch.orderIndex
                    copy.bodyTextPath = ch.bodyTextPath
                    copy.bodyTextStorage = ch.bodyTextStorage
                    copy.bodyTextSize = ch.bodyTextSize
                    if (includeContent) {
                        copy.bodyText = resolveChapterText(ch.bookId, ch.id, null, ch.bodyTextPath, ch.bodyTextStorage)
                    }
                    chapters.add(copy)
                }
            }
            return chapters
        } finally {
            lock.readLock().unlock()
        }
    }

    fun getChapterCount(bookId: Long): Int {
        lock.readLock().lock()
        try {
            return chaptersByBookId[bookId]?.size ?: 0
        } finally {
            lock.readLock().unlock()
        }
    }

    fun getChapterContent(chapterId: Long): ChapterRecord? {
        ensureLoaded()
        lock.readLock().lock()
        try {
            val ch = chapterById[chapterId]
            if (ch != null) {
                val copy = ChapterRecord()
                copy.id = ch.id
                copy.bookId = ch.bookId
                copy.bodyHtml = ""
                copy.bodyText = resolveChapterText(ch.bookId, ch.id, null, ch.bodyTextPath, ch.bodyTextStorage)
                return copy
            }
            return null
        } finally {
            lock.readLock().unlock()
        }
    }

    fun getChapterTextExcerpt(chapterId: Long, charOffset: Int, maxChars: Int): String {
        ensureLoaded()
        val chapter: ChapterRecord
        val cached: String?
        lock.readLock().lock()
        try {
            val source = chapterById[chapterId] ?: return ""
            cached = decompressedTextCache.get(chapterId)
            chapter = ChapterRecord()
            chapter.bodyText = source.bodyText
            chapter.bodyTextPath = source.bodyTextPath
            chapter.bodyTextStorage = source.bodyTextStorage
        } finally {
            lock.readLock().unlock()
        }

        val safeMaxChars = maxChars.coerceIn(16, 160)
        val safeOffset = charOffset.coerceAtLeast(0)
        val excerptStart = (safeOffset - safeMaxChars / 3).coerceAtLeast(0)
        if (!cached.isNullOrEmpty()) return textExcerpt(cached, excerptStart, safeMaxChars)
        if (chapter.bodyTextStorage != "file_gzip" || chapter.bodyTextPath.isNullOrEmpty()) {
            return textExcerpt(chapter.bodyText, excerptStart, safeMaxChars)
        }

        val file = resolveChapterTextFile(chapter.bodyTextPath)
        if (file == null || !file.exists()) return ""
        return try {
            FileInputStream(file).use { input ->
                GZIPInputStream(input).use { gzip ->
                    InputStreamReader(gzip, StandardCharsets.UTF_8).use { reader ->
                        var remaining = excerptStart.toLong()
                        while (remaining > 0) {
                            val skipped = reader.skip(remaining)
                            if (skipped > 0) {
                                remaining -= skipped
                                continue
                            }
                            if (reader.read() == -1) return ""
                            remaining--
                        }
                        val buffer = CharArray(safeMaxChars + 1)
                        var total = 0
                        while (total < buffer.size) {
                            val read = reader.read(buffer, total, buffer.size - total)
                            if (read == -1) break
                            total += read
                        }
                        val visibleLength = kotlin.math.min(total, safeMaxChars)
                        val excerpt = String(buffer, 0, visibleLength).replace("\\s+".toRegex(), " ").trim()
                        if (excerpt.isEmpty()) return ""
                        (if (excerptStart > 0) "…" else "") + excerpt + if (total > safeMaxChars) "…" else ""
                    }
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "流式读取章节摘要失败: chapter=" + chapterId, error)
            ""
        }
    }

    private fun textExcerpt(text: String?, excerptStart: Int, maxChars: Int): String {
        if (text.isNullOrEmpty()) return ""
        val safeStart = excerptStart.coerceIn(0, text.length)
        val end = kotlin.math.min(text.length, safeStart + maxChars)
        val excerpt = text.substring(safeStart, end).replace("\\s+".toRegex(), " ").trim()
        if (excerpt.isEmpty()) return ""
        return (if (safeStart > 0) "…" else "") + excerpt + if (end < text.length) "…" else ""
    }

    fun getChaptersWithExternalStorage(bookId: Long): MutableList<ChapterRecord> {
        ensureLoaded()
        lock.readLock().lock()
        try {
            val chapters: MutableList<ChapterRecord> = ArrayList()
            val source = chaptersByBookId[bookId]
            if (source != null) {
                for (ch in source) {
                    if (ch.bodyTextStorage == "file_gzip" && !ch.bodyTextPath.isNullOrEmpty()) chapters.add(ch)
                }
            }
            return chapters
        } finally {
            lock.readLock().unlock()
        }
    }

    fun getReplacementRules(bookId: Long): MutableList<ReplacementRuleRecord> {
        ensureLoaded()
        lock.readLock().lock()
        try {
            val rules: MutableList<ReplacementRuleRecord> = ArrayList()
            for (rule in globalRules) rules.add(cloneRule(rule))
            val bookRules = bookRulesByBookId[bookId]
            if (bookRules != null) for (rule in bookRules) rules.add(cloneRule(rule))
            return rules
        } finally {
            lock.readLock().unlock()
        }
    }

    fun addReplacementRule(pattern: String?, replacement: String?, global: Boolean, bookId: Long, regex: Boolean) {
        lock.writeLock().lock()
        try {
            val rule = ReplacementRuleRecord()
            rule.id = nextId(ruleCache)
            rule.pattern = pattern
            rule.replacement = replacement
            rule.scope = if (global) "global" else "book"
            rule.bookId = if (global) null else bookId
            rule.regex = regex
            rule.active = true
            rule.updatedAt = System.currentTimeMillis()
            ruleCache.add(rule)
            rebuildIndexesLocked()
            saveList(FILE_RULES, ruleCache, ReplacementRuleRecord::toJson)
        } finally {
            lock.writeLock().unlock()
        }
    }

    fun toggleReplacementRule(ruleId: Long, active: Boolean) {
        lock.writeLock().lock()
        try {
            for (rule in ruleCache) {
                if (rule.id == ruleId) {
                    rule.active = active
                    rule.updatedAt = System.currentTimeMillis()
                    saveList(FILE_RULES, ruleCache, ReplacementRuleRecord::toJson)
                    return
                }
            }
        } finally {
            lock.writeLock().unlock()
        }
    }

    fun deleteReplacementRule(ruleId: Long) {
        lock.writeLock().lock()
        try {
            ruleCache.removeAll { it.id == ruleId }
            rebuildIndexesLocked()
            saveList(FILE_RULES, ruleCache, ReplacementRuleRecord::toJson)
        } finally {
            lock.writeLock().unlock()
        }
    }

    private fun cloneRule(src: ReplacementRuleRecord): ReplacementRuleRecord {
        val rule = ReplacementRuleRecord()
        rule.id = src.id
        rule.pattern = src.pattern
        rule.replacement = src.replacement
        rule.scope = src.scope
        rule.bookId = src.bookId
        rule.regex = src.regex
        rule.active = src.active
        rule.updatedAt = src.updatedAt
        return rule
    }

    fun getCustomThemes(): MutableList<ReaderThemeRecord> {
        ensureLoaded()
        lock.readLock().lock()
        try {
            val themes: MutableList<ReaderThemeRecord> = ArrayList()
            for (theme in themeCache) {
                val copy = ReaderThemeRecord()
                copy.id = theme.id
                copy.name = theme.name
                copy.configJson = theme.configJson
                copy.updatedAt = theme.updatedAt
                themes.add(copy)
            }
            return themes
        } finally {
            lock.readLock().unlock()
        }
    }

    fun saveCustomTheme(name: String?, configJson: String?) {
        lock.writeLock().lock()
        try {
            val now = System.currentTimeMillis()
            for (theme in themeCache) {
                if (name == theme.name) {
                    theme.configJson = configJson
                    theme.updatedAt = now
                    saveList(FILE_THEMES, themeCache, ReaderThemeRecord::toJson)
                    return
                }
            }
            val theme = ReaderThemeRecord()
            theme.id = nextId(themeCache)
            theme.name = name
            theme.configJson = configJson
            theme.updatedAt = now
            themeCache.add(theme)
            saveList(FILE_THEMES, themeCache, ReaderThemeRecord::toJson)
        } finally {
            lock.writeLock().unlock()
        }
    }

    fun deleteCustomTheme(themeId: Long) {
        lock.writeLock().lock()
        try {
            themeCache.removeAll { it.id == themeId }
            saveList(FILE_THEMES, themeCache, ReaderThemeRecord::toJson)
        } finally {
            lock.writeLock().unlock()
        }
    }

    fun upsertBookmark(bookmark: BookmarkRecord): Long {
        lock.writeLock().lock()
        try {
            if (bookmark.uuid.isNullOrEmpty()) bookmark.uuid = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            if (bookmark.createdAt == 0L) bookmark.createdAt = now
            bookmark.updatedAt = now

            for (i in bookmarkCache.indices) {
                val existing = bookmarkCache[i]
                if (bookmark.uuid == existing.uuid) {
                    bookmark.id = existing.id
                    bookmarkCache[i] = bookmark
                    rebuildIndexesLocked()
                    saveList(FILE_BOOKMARKS, bookmarkCache, BookmarkRecord::toJson)
                    return existing.id
                }
            }
            bookmark.id = nextId(bookmarkCache)
            bookmarkCache.add(bookmark)
            rebuildIndexesLocked()
            saveList(FILE_BOOKMARKS, bookmarkCache, BookmarkRecord::toJson)
            return bookmark.id
        } finally {
            lock.writeLock().unlock()
        }
    }

    fun getBookmarks(): MutableList<BookmarkRecord> {
        ensureLoaded()
        lock.readLock().lock()
        try {
            return ArrayList(bookmarkCache)
        } finally {
            lock.readLock().unlock()
        }
    }

    fun getBookmarksForBook(bookId: Long, bookIdentity: String?): MutableList<BookmarkRecord> {
        ensureLoaded()
        lock.readLock().lock()
        try {
            val unique: MutableMap<String?, BookmarkRecord> = HashMap()
            val byBook = bookmarksByBookId[bookId]
            if (byBook != null) for (bookmark in byBook) unique[bookmark.uuid] = bookmark
            if (!bookIdentity.isNullOrEmpty()) {
                val byIdentity = bookmarksByIdentity[bookIdentity]
                if (byIdentity != null) for (bookmark in byIdentity) unique[bookmark.uuid] = bookmark
            }
            return ArrayList(unique.values)
        } finally {
            lock.readLock().unlock()
        }
    }

    fun deleteBookmark(bookmarkId: Long) {
        lock.writeLock().lock()
        try {
            bookmarkCache.removeAll { it.id == bookmarkId }
            rebuildIndexesLocked()
            saveList(FILE_BOOKMARKS, bookmarkCache, BookmarkRecord::toJson)
        } finally {
            lock.writeLock().unlock()
        }
    }

    fun recordReadingStats(date: String?, durationSeconds: Int, charCount: Int) {
        recordReadingDuration(
            ReadingStatsUtils.LEGACY_DEVICE_ID,
            date,
            ReadingStatsUtils.LEGACY_BOOK_IDENTITY,
            ReadingStatsUtils.LEGACY_BOOK_TITLE,
            "",
            durationSeconds,
            charCount,
            System.currentTimeMillis(),
        )
    }

    fun recordReadingDuration(
        sourceDeviceId: String?,
        date: String?,
        bookIdentity: String?,
        bookTitle: String?,
        bookAuthor: String?,
        durationSeconds: Int,
        charCount: Int,
        updatedAt: Long,
    ) {
        if (durationSeconds <= 0 && charCount <= 0) return
        ensureLoaded()
        lock.writeLock().lock()
        try {
            val safeDeviceId = sourceDeviceId ?: ""
            val safeDate = date ?: ""
            val safeTitle = ReadingStatsUtils.safeBookTitle(bookTitle)
            val safeAuthor = bookAuthor?.trim() ?: ""
            val safeIdentity = canonicalReadingStatsIdentity(bookIdentity, safeTitle, safeAuthor)
            for (entry in readingStatsCache) {
                if (safeDate == entry.date && safeDeviceId == entry.sourceDeviceId &&
                    sameReadingStatsBook(entry.bookIdentity, entry.bookTitle, entry.bookAuthor, safeIdentity, safeTitle, safeAuthor)
                ) {
                    entry.durationSeconds += durationSeconds.coerceAtLeast(0)
                    if (charCount > 0) entry.charCount += charCount.coerceAtLeast(0)
                    entry.bookIdentity = safeIdentity
                    entry.bookTitle = safeTitle
                    entry.bookAuthor = safeAuthor
                    entry.updatedAt = updatedAt.coerceAtLeast(0L)
                    normalizeReadingStatsCacheLocked()
                    markDirty(FILE_READING_STATS)
                    return
                }
            }
            val entry = ReadingTimeEntryRecord()
            entry.id = nextId(readingStatsCache)
            entry.date = safeDate
            entry.sourceDeviceId = safeDeviceId
            entry.bookIdentity = safeIdentity
            entry.bookTitle = safeTitle
            entry.bookAuthor = safeAuthor
            entry.durationSeconds = durationSeconds.coerceAtLeast(0)
            entry.charCount = charCount.coerceAtLeast(0)
            entry.updatedAt = updatedAt.coerceAtLeast(0L)
            readingStatsCache.add(entry)
            normalizeReadingStatsCacheLocked()
            markDirty(FILE_READING_STATS)
        } finally {
            lock.writeLock().unlock()
        }
    }

    fun getReadingDurationSeconds(startDate: String, endDate: String, bookIdentity: String?): Int {
        ensureLoaded()
        lock.readLock().lock()
        try {
            var total = 0
            for (entry in readingStatsCache) {
                val entryDate = entry.date ?: ""
                if ((bookIdentity.isNullOrEmpty() || bookIdentity == entry.bookIdentity) && entryDate >= startDate && entryDate <= endDate) {
                    total += entry.durationSeconds
                }
            }
            return total
        } finally {
            lock.readLock().unlock()
        }
    }

    fun getReadingCharCount(startDate: String, endDate: String, bookIdentity: String?): Int {
        ensureLoaded()
        lock.readLock().lock()
        try {
            var total = 0
            for (entry in readingStatsCache) {
                val entryDate = entry.date ?: ""
                if ((bookIdentity.isNullOrEmpty() || bookIdentity == entry.bookIdentity) && entryDate >= startDate && entryDate <= endDate) {
                    total += entry.charCount.coerceAtLeast(0)
                }
            }
            return total
        } finally {
            lock.readLock().unlock()
        }
    }

    fun getReadingDurationSecondsForBook(
        startDate: String,
        endDate: String,
        bookIdentity: String?,
        bookTitle: String?,
        bookAuthor: String?,
    ): Int {
        ensureLoaded()
        lock.readLock().lock()
        try {
            var total = 0
            for (entry in readingStatsCache) {
                val entryDate = entry.date ?: ""
                if (entryDate >= startDate && entryDate <= endDate &&
                    sameReadingStatsBook(entry.bookIdentity, entry.bookTitle, entry.bookAuthor, bookIdentity, bookTitle, bookAuthor)
                ) {
                    total += entry.durationSeconds
                }
            }
            return total
        } finally {
            lock.readLock().unlock()
        }
    }

    fun getReadingCharCountForBook(
        startDate: String,
        endDate: String,
        bookIdentity: String?,
        bookTitle: String?,
        bookAuthor: String?,
    ): Int {
        ensureLoaded()
        lock.readLock().lock()
        try {
            var total = 0
            for (entry in readingStatsCache) {
                val entryDate = entry.date ?: ""
                if (entryDate >= startDate && entryDate <= endDate &&
                    sameReadingStatsBook(entry.bookIdentity, entry.bookTitle, entry.bookAuthor, bookIdentity, bookTitle, bookAuthor)
                ) {
                    total += entry.charCount.coerceAtLeast(0)
                }
            }
            return total
        } finally {
            lock.readLock().unlock()
        }
    }

    fun getReadingBookStats(startDate: String, endDate: String): MutableList<ReadingBookStatRecord> {
        ensureLoaded()
        lock.readLock().lock()
        try {
            val map: MutableMap<String, ReadingBookStatRecord> = HashMap()
            for (entry in readingStatsCache) {
                val entryDate = entry.date ?: ""
                if (entryDate >= startDate && entryDate <= endDate) {
                    val key = readingStatsBookGroupKey(entry)
                    var stat = map[key]
                    if (stat == null) {
                        stat = ReadingBookStatRecord()
                        stat.bookIdentity = canonicalReadingStatsIdentity(entry.bookIdentity, entry.bookTitle, entry.bookAuthor)
                        stat.bookTitle = entry.bookTitle
                        stat.bookAuthor = entry.bookAuthor
                        stat.totalDurationSeconds = 0
                        stat.totalCharCount = 0
                        stat.updatedAt = 0
                        map[key] = stat
                    }
                    stat.totalDurationSeconds += entry.durationSeconds
                    stat.totalCharCount += entry.charCount.coerceAtLeast(0)
                    if (entry.updatedAt > stat.updatedAt) {
                        stat.updatedAt = entry.updatedAt
                        stat.bookIdentity = canonicalReadingStatsIdentity(entry.bookIdentity, entry.bookTitle, entry.bookAuthor)
                        stat.bookTitle = entry.bookTitle
                        stat.bookAuthor = entry.bookAuthor
                    }
                }
            }
            for (stat in map.values) {
                val book = findBookForReadingStatsLocked(stat.bookIdentity, stat.bookTitle, stat.bookAuthor)
                if (book != null) {
                    stat.localBookId = book.id
                    stat.localCoverPath = book.coverPath
                }
            }
            val list = ArrayList(map.values)
            list.sortWith { a, b -> java.lang.Long.compare(b.updatedAt, a.updatedAt) }
            return list
        } finally {
            lock.readLock().unlock()
        }
    }

    fun getReadingStatsRows(startDate: String, endDate: String): MutableList<ReadingTimeEntryRecord> {
        ensureLoaded()
        val rows: MutableList<ReadingTimeEntryRecord> = ArrayList()
        lock.readLock().lock()
        try {
            for (entry in readingStatsCache) {
                val entryDate = entry.date ?: ""
                if (entryDate >= startDate && entryDate <= endDate) rows.add(cloneReadingTimeEntry(entry))
            }
        } finally {
            lock.readLock().unlock()
        }
        return rows
    }

    fun getReadingStatsRowsForSync(sourceDeviceId: String?): MutableList<ReadingTimeEntryRecord> {
        ensureLoaded()
        val rows: MutableList<ReadingTimeEntryRecord> = ArrayList()
        val safeSourceDeviceId = sourceDeviceId ?: ""
        lock.writeLock().lock()
        try {
            val changed = normalizeReadingStatsCacheLocked()
            if (changed) saveList(FILE_READING_STATS, readingStatsCache, ReadingTimeEntryRecord::toJson)
            for (entry in readingStatsCache) if (safeSourceDeviceId == entry.sourceDeviceId) rows.add(cloneReadingTimeEntry(entry))
        } finally {
            lock.writeLock().unlock()
        }
        return rows
    }

    fun mergeReadingStatsRows(rows: List<ReadingTimeEntryRecord?>?) {
        if (rows.isNullOrEmpty()) return
        ensureLoaded()
        val normalizedRows = normalizeIncomingReadingStatsRows(rows)
        lock.writeLock().lock()
        try {
            for (row in normalizedRows) {
                var found = false
                for (i in readingStatsCache.indices) {
                    val existing = readingStatsCache[i]
                    if (existing.date == row.date && existing.sourceDeviceId == row.sourceDeviceId &&
                        sameReadingStatsBook(existing.bookIdentity, existing.bookTitle, existing.bookAuthor, row.bookIdentity, row.bookTitle, row.bookAuthor)
                    ) {
                        if (row.updatedAt >= existing.updatedAt) {
                            row.id = existing.id
                            readingStatsCache[i] = row
                        }
                        found = true
                        break
                    }
                }
                if (!found) {
                    if (row.id == 0L) row.id = nextId(readingStatsCache)
                    readingStatsCache.add(row)
                }
            }
            normalizeReadingStatsCacheLocked()
            saveList(FILE_READING_STATS, readingStatsCache, ReadingTimeEntryRecord::toJson)
        } finally {
            lock.writeLock().unlock()
        }
    }

    fun hasAnyReadingStats(): Boolean {
        ensureLoaded()
        lock.readLock().lock()
        try {
            return readingStatsCache.isNotEmpty()
        } finally {
            lock.readLock().unlock()
        }
    }

    fun clearReadingStats() {
        ensureLoaded()
        lock.writeLock().lock()
        try {
            readingStatsCache.clear()
            saveList(FILE_READING_STATS, readingStatsCache, ReadingTimeEntryRecord::toJson)
        } finally {
            lock.writeLock().unlock()
        }
    }

    private fun backfillBookReadingStatsKeysLocked(): Boolean {
        var changed = false
        for (book in bookCache) {
            if (book.readingStatsKey.isNullOrBlank()) {
                book.readingStatsKey = ReadingStatsUtils.buildBookIdentity(book.title, book.author)
                changed = true
            }
            if (book.tags == null) {
                book.tags = ArrayList()
                changed = true
            }
            if (book.series == null) {
                book.series = ""
                changed = true
            }
            val normalizedStatus = BookRecord.normalizeReadingStatus(
                book.readingStatus,
                BookRecord.hasReadingProgress(book.progressIndex, book.progressOffset, book.lastReadAt),
            )
            if (normalizedStatus != book.readingStatus) {
                book.readingStatus = normalizedStatus
                changed = true
            }
        }
        return changed
    }

    private fun migrateReadingStatsForBookRenameLocked(
        previousKey: String?,
        previousTitle: String?,
        previousAuthor: String?,
        nextKey: String?,
        nextTitle: String?,
        nextAuthor: String?,
    ): Boolean {
        if (previousKey.isNullOrBlank() && titleAuthorKeyOrEmpty(previousTitle, previousAuthor).isEmpty()) return false
        val safeNextTitle = ReadingStatsUtils.safeBookTitle(nextTitle)
        val safeNextAuthor = nextAuthor?.trim() ?: ""
        var changed = false
        for (entry in readingStatsCache) {
            if (sameReadingStatsBook(entry.bookIdentity, entry.bookTitle, entry.bookAuthor, previousKey, previousTitle, previousAuthor)) {
                if (!safeEquals(entry.bookIdentity, nextKey) || !safeEquals(entry.bookTitle, safeNextTitle) || !safeEquals(entry.bookAuthor, safeNextAuthor)) {
                    entry.bookIdentity = nextKey
                    entry.bookTitle = safeNextTitle
                    entry.bookAuthor = safeNextAuthor
                    changed = true
                }
            }
        }
        return normalizeReadingStatsCacheLocked() || changed
    }

    private fun normalizeReadingStatsCacheLocked(): Boolean {
        if (readingStatsCache.isEmpty()) return false
        val merged: MutableMap<String, ReadingTimeEntryRecord> = HashMap()
        var changed = false
        for (entry in readingStatsCache) {
            val normalized = cloneReadingTimeEntry(entry)
            if (normalizeReadingStatsEntry(normalized)) changed = true
            val groupKey = readingStatsBucketGroupKey(normalized)
            val existing = merged[groupKey]
            if (existing == null) {
                merged[groupKey] = normalized
            } else {
                mergeReadingStatsTotals(existing, normalized)
                changed = true
            }
        }
        if (changed || merged.size != readingStatsCache.size) {
            readingStatsCache = ArrayList(merged.values)
            return true
        }
        return false
    }

    private fun normalizeIncomingReadingStatsRows(rows: List<ReadingTimeEntryRecord?>): MutableList<ReadingTimeEntryRecord> {
        val merged: MutableMap<String, ReadingTimeEntryRecord> = HashMap()
        for (row in rows) {
            if (row == null || row.date.isNullOrBlank()) continue
            val normalized = cloneReadingTimeEntry(row)
            normalizeReadingStatsEntry(normalized)
            val groupKey = readingStatsBucketGroupKey(normalized)
            val existing = merged[groupKey]
            if (existing == null) merged[groupKey] = normalized else mergeReadingStatsTotals(existing, normalized)
        }
        return ArrayList(merged.values)
    }

    private fun mergeReadingStatsTotals(target: ReadingTimeEntryRecord, source: ReadingTimeEntryRecord) {
        target.durationSeconds += source.durationSeconds.coerceAtLeast(0)
        target.charCount += source.charCount.coerceAtLeast(0)
        if (target.id <= 0L && source.id > 0L) target.id = source.id
        if (source.updatedAt >= target.updatedAt) {
            target.bookIdentity = source.bookIdentity
            target.bookTitle = source.bookTitle
            target.bookAuthor = source.bookAuthor
        }
        target.updatedAt = kotlin.math.max(target.updatedAt, source.updatedAt)
    }

    private fun normalizeReadingStatsEntry(entry: ReadingTimeEntryRecord): Boolean {
        val safeDeviceId = entry.sourceDeviceId ?: ""
        val safeDate = entry.date ?: ""
        val safeTitle = safeStatsTitle(entry.bookIdentity, entry.bookTitle)
        val safeAuthor = entry.bookAuthor?.trim() ?: ""
        val safeIdentity = canonicalReadingStatsIdentity(entry.bookIdentity, safeTitle, safeAuthor)
        val safeDuration = entry.durationSeconds.coerceAtLeast(0)
        val safeCharCount = entry.charCount.coerceAtLeast(0)
        val safeUpdatedAt = entry.updatedAt.coerceAtLeast(0L)
        val changed = !safeEquals(entry.sourceDeviceId, safeDeviceId) ||
            !safeEquals(entry.date, safeDate) ||
            !safeEquals(entry.bookIdentity, safeIdentity) ||
            !safeEquals(entry.bookTitle, safeTitle) ||
            !safeEquals(entry.bookAuthor, safeAuthor) ||
            entry.durationSeconds != safeDuration ||
            entry.charCount != safeCharCount ||
            entry.updatedAt != safeUpdatedAt
        entry.sourceDeviceId = safeDeviceId
        entry.date = safeDate
        entry.bookIdentity = safeIdentity
        entry.bookTitle = safeTitle
        entry.bookAuthor = safeAuthor
        entry.durationSeconds = safeDuration
        entry.charCount = safeCharCount
        entry.updatedAt = safeUpdatedAt
        return changed
    }

    private fun safeStatsTitle(bookIdentity: String?, bookTitle: String?): String {
        if (ReadingStatsUtils.LEGACY_BOOK_IDENTITY == bookIdentity && bookTitle.isNullOrBlank()) return ReadingStatsUtils.LEGACY_BOOK_TITLE
        return ReadingStatsUtils.safeBookTitle(bookTitle)
    }

    private fun canonicalReadingStatsIdentity(bookIdentity: String?, bookTitle: String?, bookAuthor: String?): String {
        val safeIdentity = bookIdentity?.trim() ?: ""
        if (ReadingStatsUtils.LEGACY_BOOK_IDENTITY == safeIdentity) return ReadingStatsUtils.LEGACY_BOOK_IDENTITY
        val titleAuthorKey = titleAuthorKeyOrEmpty(bookTitle, bookAuthor)
        if (titleAuthorKey.isNotEmpty()) return ReadingStatsUtils.buildBookIdentity(bookTitle, bookAuthor)
        return safeIdentity
    }

    private fun readingStatsBookGroupKey(entry: ReadingTimeEntryRecord): String {
        val safeIdentity = canonicalReadingStatsIdentity(entry.bookIdentity, entry.bookTitle, entry.bookAuthor)
        if (ReadingStatsUtils.LEGACY_BOOK_IDENTITY == safeIdentity) return "legacy:" + safeIdentity
        val titleAuthorKey = titleAuthorKeyOrEmpty(entry.bookTitle, entry.bookAuthor)
        return if (titleAuthorKey.isEmpty()) "identity:" + safeIdentity else "title-author:" + titleAuthorKey
    }

    private fun readingStatsBucketGroupKey(entry: ReadingTimeEntryRecord): String {
        return (entry.sourceDeviceId ?: "") + "\n" + (entry.date ?: "") + "\n" + readingStatsBookGroupKey(entry)
    }

    private fun sameReadingStatsBook(
        leftIdentity: String?,
        leftTitle: String?,
        leftAuthor: String?,
        rightIdentity: String?,
        rightTitle: String?,
        rightAuthor: String?,
    ): Boolean {
        val safeLeftIdentity = leftIdentity?.trim() ?: ""
        val safeRightIdentity = rightIdentity?.trim() ?: ""
        if (safeLeftIdentity.isNotEmpty() && safeLeftIdentity == safeRightIdentity) return true
        if (ReadingStatsUtils.LEGACY_BOOK_IDENTITY == safeLeftIdentity || ReadingStatsUtils.LEGACY_BOOK_IDENTITY == safeRightIdentity) return false
        val leftTitleAuthorKey = titleAuthorKeyOrEmpty(leftTitle, leftAuthor)
        val rightTitleAuthorKey = titleAuthorKeyOrEmpty(rightTitle, rightAuthor)
        return leftTitleAuthorKey.isNotEmpty() && leftTitleAuthorKey == rightTitleAuthorKey
    }

    private fun titleAuthorKeyOrEmpty(title: String?, author: String?): String {
        val normalizedTitle = ReadingStatsUtils.normalizeIdentityText(title)
        val normalizedAuthor = ReadingStatsUtils.normalizeIdentityText(author)
        if (normalizedTitle.isEmpty() && normalizedAuthor.isEmpty()) return ""
        return normalizedTitle + "::" + normalizedAuthor
    }

    private fun cloneReadingTimeEntry(source: ReadingTimeEntryRecord): ReadingTimeEntryRecord {
        val clone = ReadingTimeEntryRecord()
        clone.id = source.id
        clone.date = source.date
        clone.sourceDeviceId = source.sourceDeviceId
        clone.bookIdentity = source.bookIdentity
        clone.bookTitle = source.bookTitle
        clone.bookAuthor = source.bookAuthor
        clone.durationSeconds = source.durationSeconds
        clone.charCount = source.charCount
        clone.updatedAt = source.updatedAt
        return clone
    }

    private fun safeEquals(left: String?, right: String?): Boolean = left == right

    fun isDatabaseHealthyForStartup(): Boolean = true

    fun triggerStorageMaintenance() {
        cleanupTemporaryCacheFiles()
    }

    interface MaintenanceProgressListener {
        fun onPhaseStart(phaseName: String)
        fun onPhaseDone(phaseName: String)
        fun onAllDone()
        fun onError(errorMessage: String)
    }

    fun getPendingMaintenanceSummary(): String {
        val temporaryCacheSize = temporaryCacheSize()
        if (temporaryCacheSize > 0L) return "可清理临时缓存 " + formatFileSize(temporaryCacheSize)
        return "无需维护（JSON 存储）"
    }

    fun hasPendingMaintenanceWork(): Boolean = temporaryCacheSize() > 0L

    fun runStorageMaintenanceWithProgress(listener: MaintenanceProgressListener?) {
        try {
            listener?.onPhaseStart("清理临时缓存")
            cleanupTemporaryCacheFiles()
            listener?.onPhaseDone("清理临时缓存")
            listener?.onAllDone()
        } catch (error: Exception) {
            listener?.onError(error.message ?: "")
        }
    }

    fun getDatabaseFile(): File = dataDir

    fun getDatabaseDir(): File = dataDir

    fun getDatabaseSizeInfo(): String {
        ensureLoaded()
        val filesDir = appContext.filesDir
        val chapterTextDir = getChapterTextDir()
        val coversDir = File(filesDir, "covers")
        val booksDir = File(filesDir, "books")
        val backgroundsDir = File(filesDir, "backgrounds")
        val databasesDir = File(appContext.applicationInfo.dataDir, "databases")
        val sharedPrefsDir = File(appContext.applicationInfo.dataDir, "shared_prefs")

        val jsonSize = dirSize(dataDir)
        val chapterTextSize = dirSize(chapterTextDir)
        val coversSize = dirSize(coversDir)
        val booksSize = dirSize(booksDir)
        val backgroundsSize = dirSize(backgroundsDir)
        val otherFilesSize = dirChildrenSizeExcluding(filesDir, dataDir, chapterTextDir, coversDir, booksDir, backgroundsDir)
        val databasesSize = dirSize(databasesDir)
        val cacheSize = dirSize(appContext.cacheDir)
        val codeCacheSize = dirSize(getCodeCacheDirCompat())
        val sharedPrefsSize = dirSize(sharedPrefsDir)
        val noBackupSize = dirSize(getNoBackupFilesDirCompat())
        val readingDataTotal = jsonSize + chapterTextSize + coversSize + booksSize + backgroundsSize + otherFilesSize + databasesSize
        val privateDataTotal = readingDataTotal + cacheSize + codeCacheSize + sharedPrefsSize + noBackupSize
        val installedPackageSize = installedPackageSizeEstimate()

        val sb = StringBuilder()
        sb.append("JSON 数据文件 ").append(formatFileSize(jsonSize))
            .append("\n章节正文文件 ").append(formatFileSize(chapterTextSize))
            .append("\n封面缓存 ").append(formatFileSize(coversSize))
            .append("\n源文件缓存 ").append(formatFileSize(booksSize))
            .append("\n自定义背景 ").append(formatFileSize(backgroundsSize))
            .append("\n其它 files ").append(formatFileSize(otherFilesSize))
        if (databasesSize > 0L) sb.append("\n旧数据库/迁移残留 ").append(formatFileSize(databasesSize))
        sb.append("\n阅读数据小计 ").append(formatFileSize(readingDataTotal))
        sb.append("\n──────────────────")
        sb.append("\n缓存目录 ").append(formatFileSize(cacheSize))
        appendDirectoryChildrenBreakdown(sb, appContext.cacheDir, "  ")
        sb.append("\n代码缓存 ").append(formatFileSize(codeCacheSize))
            .append("\n偏好设置 ").append(formatFileSize(sharedPrefsSize))
            .append("\nNo backup ").append(formatFileSize(noBackupSize))
        sb.append("\n应用私有数据合计 ").append(formatFileSize(privateDataTotal))
        sb.append("\n安装包/库文件估算 ").append(formatFileSize(installedPackageSize))
        sb.append("\n系统口径估算合计 ").append(formatFileSize(privateDataTotal + installedPackageSize))
        sb.append("\n──────────────────")
        sb.append("\nbooks: ").append(bookCache.size).append(" 条")
        sb.append("\nchapters: ").append(chapterCache.size).append(" 条")
        sb.append("\nrules: ").append(ruleCache.size).append(" 条")
        sb.append("\nthemes: ").append(themeCache.size).append(" 条")
        sb.append("\nbookmarks: ").append(bookmarkCache.size).append(" 条")
        sb.append("\nreading_stats: ").append(readingStatsCache.size).append(" 条")
        return sb.toString()
    }

    fun rebaseLocalAssetPaths() {
        lock.writeLock().lock()
        try {
            rebasePathsLocked()
            saveList(FILE_BOOKS, bookCache, BookRecord::toJson)
        } finally {
            lock.writeLock().unlock()
        }
    }

    fun checkpoint() {}

    fun vacuumIfNeeded() {}

    fun schedulePendingStorageMaintenance() {}

    fun backfillBookStatsKeys() {}

    @Throws(IOException::class)
    fun exportDatabase(destination: File?): File {
        flush()
        return dataDir
    }

    @Throws(IOException::class)
    fun exportLiteDatabase(destination: File?): File {
        flush()
        return dataDir
    }

    fun getChapterTextDir(): File = File(appContext.filesDir, "chapter_text")

    fun resolveChapterTextFile(bodyTextPath: String?): File? {
        if (bodyTextPath.isNullOrEmpty()) return null
        if (bodyTextPath.startsWith("chapter_text/") || bodyTextPath.startsWith("chapter_text\\")) {
            return File(appContext.filesDir, bodyTextPath)
        }
        return File(getChapterTextDir(), bodyTextPath)
    }

    fun resolveChapterText(
        bookId: Long,
        chapterId: Long,
        bodyText: String?,
        bodyTextPath: String?,
        bodyTextStorage: String?,
    ): String {
        if (bodyTextStorage == "file_gzip" && !bodyTextPath.isNullOrEmpty()) {
            val cached = decompressedTextCache.get(chapterId)
            if (cached != null) return cached
            val file = resolveChapterTextFile(bodyTextPath)
            if (file != null && file.exists()) {
                val text = readGzipFile(file)
                if (text.isEmpty()) {
                    Log.w(TAG, "章节正文文件为空: book=" + bookId + " chapter=" + chapterId + " path=" + bodyTextPath)
                } else {
                    decompressedTextCache.put(chapterId, text)
                }
                return text
            } else {
                Log.w(TAG, "章节外置正文缺失: book=" + bookId + " chapter=" + chapterId + " storage=" + bodyTextStorage + " path=" + bodyTextPath)
            }
        }
        if (!bodyText.isNullOrEmpty()) return bodyText
        if (bodyTextStorage != "file_gzip" && bodyTextPath.isNullOrEmpty()) {
            Log.w(TAG, "章节正文不可用: book=" + bookId + " chapter=" + chapterId + " storage=" + bodyTextStorage + " path=" + bodyTextPath)
        }
        return ""
    }

    private fun buildChapterTextRelativePath(bookId: Long, chapterId: Long): String {
        return "chapter_text/book_" + bookId + "/chapter_" + chapterId + ".txt.gz"
    }

    @Throws(IOException::class)
    private fun writeChapterTextToFile(bookId: Long, chapterId: Long, bodyText: String) {
        val dir = File(getChapterTextDir(), "book_" + bookId)
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "chapter_" + chapterId + ".txt.gz")
        FileOutputStream(file).use { fos ->
            GZIPOutputStream(fos).use { gzOut -> gzOut.write(bodyText.toByteArray(StandardCharsets.UTF_8)) }
        }
    }

    private fun readGzipFile(file: File): String {
        return try {
            val baos = ByteArrayOutputStream()
            FileInputStream(file).use { fis ->
                GZIPInputStream(fis).use { gzIn ->
                    val buffer = ByteArray(32768)
                    while (true) {
                        val read = gzIn.read(buffer)
                        if (read == -1) break
                        baos.write(buffer, 0, read)
                    }
                }
            }
            baos.toString("UTF-8")
        } catch (e: Exception) {
            Log.w(TAG, "读取 gzip 章节文件失败: " + file, e)
            ""
        }
    }

    private fun deleteChapterTextDir(bookId: Long) {
        deleteDir(File(getChapterTextDir(), "book_" + bookId))
    }

    private fun nextId(list: List<*>): Long {
        var maxId = 0L
        for (obj in list) {
            val id = when (obj) {
                is BookRecord -> obj.id
                is ChapterRecord -> obj.id
                is ReplacementRuleRecord -> obj.id
                is ReaderThemeRecord -> obj.id
                is BookmarkRecord -> obj.id
                is ReadingTimeEntryRecord -> obj.id
                else -> 0L
            }
            if (id > maxId) maxId = id
        }
        if (maxId > 0) return maxId + 1
        return System.currentTimeMillis() * 1000 + ThreadLocalRandom.current().nextInt(1000).toLong()
    }

    private fun dirSize(dir: File?): Long = pathSize(dir)

    private fun pathSize(file: File?): Long {
        if (file == null || !file.exists()) return 0L
        if (file.isFile) return file.length()
        if (!file.isDirectory) return 0L
        var size = 0L
        val files = file.listFiles() ?: return 0L
        for (child in files) size += pathSize(child)
        return size
    }

    private fun dirChildrenSizeExcluding(parent: File?, vararg excluded: File?): Long {
        if (parent == null || !parent.exists() || !parent.isDirectory) return 0L
        var total = 0L
        val children = parent.listFiles() ?: return 0L
        for (child in children) {
            if (matchesAnyPath(child, *excluded)) continue
            total += pathSize(child)
        }
        return total
    }

    private fun matchesAnyPath(file: File?, vararg candidates: File?): Boolean {
        if (file == null) return false
        for (candidate in candidates) if (samePath(file, candidate)) return true
        return false
    }

    private fun samePath(first: File?, second: File?): Boolean {
        if (first == null || second == null) return false
        return try {
            first.canonicalFile == second.canonicalFile
        } catch (_: IOException) {
            first.absolutePath == second.absolutePath
        }
    }

    private fun getCodeCacheDirCompat(): File {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) return appContext.codeCacheDir
        return File(appContext.applicationInfo.dataDir, "code_cache")
    }

    private fun getNoBackupFilesDirCompat(): File {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) return appContext.noBackupFilesDir
        return File(appContext.applicationInfo.dataDir, "no_backup")
    }

    private fun installedPackageSizeEstimate(): Long {
        val info: ApplicationInfo = appContext.applicationInfo
        val seenPaths: MutableSet<String> = HashSet()
        var total = addUniquePathSize(seenPaths, info.sourceDir?.let { File(it) })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && info.splitSourceDirs != null) {
            for (splitPath in info.splitSourceDirs) total += addUniquePathSize(seenPaths, splitPath?.let { File(it) })
        }
        total += addUniquePathSize(seenPaths, info.nativeLibraryDir?.let { File(it) })
        return total
    }

    private fun addUniquePathSize(seenPaths: MutableSet<String>, file: File?): Long {
        if (file == null || !file.exists()) return 0L
        val path = try {
            file.canonicalPath
        } catch (_: IOException) {
            file.absolutePath
        }
        if (!seenPaths.add(path)) return 0L
        return pathSize(file)
    }

    private fun temporaryCacheSize(): Long {
        val cacheDir = appContext.cacheDir ?: return 0L
        var total = 0L
        total += pathSize(File(cacheDir, "backup"))
        total += pathSize(File(cacheDir, "backup_restore"))
        total += pathSize(File(cacheDir, "launch_sources"))
        val files = cacheDir.listFiles()
        if (files != null) {
            for (file in files) if (file.isFile && file.name.startsWith("restore_")) total += file.length()
        }
        return total
    }

    private fun cleanupTemporaryCacheFiles() {
        val cacheDir = appContext.cacheDir ?: return
        deletePathRecursively(File(cacheDir, "backup"))
        deletePathRecursively(File(cacheDir, "backup_restore"))
        deletePathRecursively(File(cacheDir, "launch_sources"))
        val files = cacheDir.listFiles() ?: return
        for (file in files) if (file.isFile && file.name.startsWith("restore_")) file.delete()
    }

    private fun appendDirectoryChildrenBreakdown(sb: StringBuilder, dir: File?, indent: String) {
        if (dir == null || !dir.exists() || !dir.isDirectory) return
        val children = dir.listFiles()
        if (children == null || children.isEmpty()) return
        val entries: MutableList<FileSizeEntry> = ArrayList()
        for (child in children) {
            val size = pathSize(child)
            if (size > 0L) entries.add(FileSizeEntry(child, size))
        }
        if (entries.isEmpty()) return
        entries.sortWith { left, right -> java.lang.Long.compare(right.size, left.size) }
        val limit = kotlin.math.min(entries.size, 8)
        var hiddenSize = 0L
        for (i in entries.indices) {
            val entry = entries[i]
            if (i < limit) {
                sb.append("\n").append(indent).append(cacheChildLabel(entry.file)).append(" ").append(formatFileSize(entry.size))
            } else {
                hiddenSize += entry.size
            }
        }
        if (hiddenSize > 0L) sb.append("\n").append(indent).append("其它缓存项 ").append(formatFileSize(hiddenSize))
    }

    private fun cacheChildLabel(file: File): String {
        val name = file.name
        if (name == "backup") return "WebDAV备份临时 backup"
        if (name == "backup_restore") return "WebDAV恢复临时 backup_restore"
        if (name == "launch_sources") return "阅读页转场截图 launch_sources"
        if (name.startsWith("restore_")) return "WebDAV恢复临时 " + name
        return name
    }

    private fun deletePathRecursively(file: File?) {
        if (file == null || !file.exists()) return
        if (file.isDirectory) {
            val children = file.listFiles()
            if (children != null) for (child in children) deletePathRecursively(child)
        }
        file.delete()
    }

    private class FileSizeEntry(val file: File, val size: Long)

    private fun deleteFileIfExists(path: String?) {
        if (path.isNullOrEmpty()) return
        val file = File(path)
        if (file.exists()) file.delete()
    }

    private fun deleteDir(dir: File?) {
        if (dir == null || !dir.exists()) return
        val files = dir.listFiles()
        if (files != null) for (file in files) file.delete()
        dir.delete()
    }

    fun getDataDir(): File = dataDir

    fun getJsonFileName(type: String?): String? {
        return when (type) {
            "books" -> FILE_BOOKS
            "chapters" -> FILE_CHAPTERS
            "rules" -> FILE_RULES
            "themes" -> FILE_THEMES
            "bookmarks" -> FILE_BOOKMARKS
            "reading_stats" -> FILE_READING_STATS
            "readingStats" -> FILE_READING_STATS
            else -> null
        }
    }

    fun getBooksMutable(): MutableList<BookRecord> {
        ensureLoaded()
        return bookCache
    }

    fun getChaptersMutable(): MutableList<ChapterRecord> {
        ensureLoaded()
        return chapterCache
    }

    fun getRulesMutable(): MutableList<ReplacementRuleRecord> {
        ensureLoaded()
        return ruleCache
    }

    fun getThemesMutable(): MutableList<ReaderThemeRecord> {
        ensureLoaded()
        return themeCache
    }

    fun getBookmarksMutable(): MutableList<BookmarkRecord> {
        ensureLoaded()
        return bookmarkCache
    }

    fun getReadingStatsMutable(): MutableList<ReadingTimeEntryRecord> {
        ensureLoaded()
        return readingStatsCache
    }

    fun getAppContext(): Context = appContext

    companion object {
        private const val TAG = "JsonDatabase"
        private const val DATABASE_DIR = "database"
        private const val FILE_BOOKS = "books.json"
        private const val FILE_CHAPTERS = "chapters.json"
        private const val FILE_RULES = "rules.json"
        private const val FILE_THEMES = "themes.json"
        private const val FILE_BOOKMARKS = "bookmarks.json"
        private const val FILE_READING_STATS = "reading_stats.json"
        private const val WRITE_DEBOUNCE_MS = 800L
        private const val MAX_DECOMPRESSED_TEXT_CACHE_BYTES = 16 * 1024 * 1024

        @Volatile
        private var instance: JsonDatabase? = null

        @JvmStatic
        @Synchronized
        fun getInstance(context: Context): JsonDatabase {
            var current = instance
            if (current == null) {
                current = JsonDatabase(context)
                instance = current
            }
            return current
        }

        @JvmStatic
        fun formatFileSize(size: Long): String {
            if (size < 1024) return size.toString() + " B"
            if (size < 1048576) return String.format(Locale.ROOT, "%.1f KB", size / 1024.0)
            if (size < 1073741824) return String.format(Locale.ROOT, "%.2f MB", size / 1048576.0)
            return String.format(Locale.ROOT, "%.2f GB", size / 1073741824.0)
        }
    }
}






