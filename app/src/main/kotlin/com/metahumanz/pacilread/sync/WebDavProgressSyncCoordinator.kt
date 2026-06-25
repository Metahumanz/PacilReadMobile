package com.metahumanz.pacilread.sync

import com.metahumanz.pacilread.model.BookRecord
import com.metahumanz.pacilread.storage.JsonDatabase
import com.metahumanz.pacilread.storage.SettingsStore
import java.util.concurrent.TimeUnit

class WebDavProgressSyncCoordinator(
    private val databaseHelper: JsonDatabase,
    private val settingsStore: SettingsStore,
    private val webDavClient: WebDavClient,
) {
    @Throws(Exception::class)
    fun syncBookProgressIfNeeded(book: BookRecord?): SyncResult = syncBookProgressIfNeeded(book, null)

    @Throws(Exception::class)
    fun syncBookProgressIfNeeded(book: BookRecord?, baseline: ProgressBaseline?): SyncResult {
        if (book == null || book.id <= 0L || !settingsStore.isWebDavEnabled) return SyncResult.skipped(false)
        if (!beginSync(book.id)) return SyncResult.skipped(true)
        var completedSuccessfully = false
        try {
            val currentBook = databaseHelper.getBook(book.id) ?: book
            val payload = webDavClient.downloadProgress(currentBook)
            if (payload == null) {
                completedSuccessfully = true
                return SyncResult.checkedNoRemote()
            }
            val comparison = baseline ?: ProgressBaseline.fromBook(currentBook)
            val localEmpty = comparison.progressIndex == 0 && comparison.progressOffset == 0
            val remoteNewer = payload.chapterTime > comparison.lastReadAt + REMOTE_NEWER_GRACE_MS
            if (!remoteNewer && !localEmpty) {
                completedSuccessfully = true
                return SyncResult.checkedNotApplied(true)
            }
            val chapterOrderIndex = resolveChapterOrderIndex(currentBook, payload.chapterIndex)
            val chapterPosition = Math.max(payload.chapterPosition, 0)
            databaseHelper.updateProgressFromRemote(currentBook.id, chapterOrderIndex, chapterPosition, payload.chapterTime)
            completedSuccessfully = true
            return SyncResult.applied(chapterOrderIndex, chapterPosition, payload.chapterTime)
        } finally {
            finishSync(book.id, completedSuccessfully)
        }
    }

    @Throws(Exception::class)
    fun findRemoteProgressIfNeeded(book: BookRecord?, baseline: ProgressBaseline?): SyncResult {
        if (book == null || book.id <= 0L || !settingsStore.isWebDavEnabled) return SyncResult.skipped(false)
        if (!beginSync(book.id)) return SyncResult.skipped(true)
        var completedSuccessfully = false
        try {
            val currentBook = databaseHelper.getBook(book.id) ?: book
            val payload = webDavClient.downloadProgress(currentBook)
            if (payload == null) {
                completedSuccessfully = true
                return SyncResult.checkedNoRemote()
            }
            val comparison = baseline ?: ProgressBaseline.fromBook(currentBook)
            val localEmpty = comparison.progressIndex == 0 && comparison.progressOffset == 0
            val remoteNewer = payload.chapterTime > comparison.lastReadAt + REMOTE_NEWER_GRACE_MS
            if (!remoteNewer && !localEmpty) {
                completedSuccessfully = true
                return SyncResult.checkedNotApplied(true)
            }
            val chapterOrderIndex = resolveChapterOrderIndex(currentBook, payload.chapterIndex)
            completedSuccessfully = true
            return SyncResult.suggested(chapterOrderIndex, Math.max(payload.chapterPosition, 0), payload.chapterTime)
        } finally {
            finishSync(book.id, completedSuccessfully)
        }
    }

    private fun beginSync(bookId: Long): Boolean = synchronized(LOCK) {
        while (IN_FLIGHT_BOOK_IDS.contains(bookId)) {
            try {
                LOCK.wait(250L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return@synchronized false
            }
            if (isProgressFreshLocked(bookId, System.currentTimeMillis())) return@synchronized false
        }
        if (isProgressFreshLocked(bookId, System.currentTimeMillis())) return@synchronized false
        IN_FLIGHT_BOOK_IDS.add(bookId)
        true
    }

    private fun finishSync(bookId: Long, checkedRemote: Boolean) = synchronized(LOCK) {
        IN_FLIGHT_BOOK_IDS.remove(bookId)
        if (checkedRemote) LAST_SYNCED_AT_BY_BOOK_ID[bookId] = System.currentTimeMillis()
        LOCK.notifyAll()
    }

    private fun resolveChapterOrderIndex(book: BookRecord, remoteChapterIndex: Int): Int {
        val chapters = databaseHelper.getChapters(book.id, false)
        if (chapters.isNullOrEmpty()) return Math.max(remoteChapterIndex, 0)
        for (chapter in chapters) if (chapter.orderIndex == remoteChapterIndex) return chapter.orderIndex
        val safeIndex = Math.max(0, Math.min(remoteChapterIndex, chapters.size - 1))
        return chapters[safeIndex].orderIndex
    }

    class ProgressBaseline(
        @JvmField val lastReadAt: Long,
        @JvmField val progressIndex: Int,
        @JvmField val progressOffset: Int,
    ) {
        companion object {
            @JvmStatic fun fromBook(book: BookRecord): ProgressBaseline =
                ProgressBaseline(book.lastReadAt, book.progressIndex, book.progressOffset)
        }
    }

    class SyncResult private constructor(
        @JvmField val checkedRemote: Boolean,
        @JvmField val remoteAvailable: Boolean,
        @JvmField val remoteApplied: Boolean,
        @JvmField val remoteSuggested: Boolean,
        @JvmField val skippedFresh: Boolean,
        @JvmField val chapterOrderIndex: Int,
        @JvmField val chapterPosition: Int,
        @JvmField val chapterTime: Long,
    ) {
        companion object {
            @JvmStatic fun skipped(skippedFresh: Boolean) = SyncResult(false, false, false, false, skippedFresh, 0, 0, 0L)
            @JvmStatic fun checkedNoRemote() = SyncResult(true, false, false, false, false, 0, 0, 0L)
            @JvmStatic fun checkedNotApplied(remoteAvailable: Boolean) = SyncResult(true, remoteAvailable, false, false, false, 0, 0, 0L)
            @JvmStatic fun applied(chapterOrderIndex: Int, chapterPosition: Int, chapterTime: Long) =
                SyncResult(true, true, true, false, false, chapterOrderIndex, chapterPosition, chapterTime)
            @JvmStatic fun suggested(chapterOrderIndex: Int, chapterPosition: Int, chapterTime: Long) =
                SyncResult(true, true, false, true, false, chapterOrderIndex, chapterPosition, chapterTime)
        }
    }

    companion object {
        private val FRESHNESS_TTL_MS = TimeUnit.MINUTES.toMillis(5)
        private const val REMOTE_NEWER_GRACE_MS = 5_000L
        private val LOCK = java.lang.Object()
        private val LAST_SYNCED_AT_BY_BOOK_ID = HashMap<Long, Long>()
        private val IN_FLIGHT_BOOK_IDS = HashSet<Long>()

        @JvmStatic
        fun isProgressFresh(bookId: Long): Boolean = synchronized(LOCK) {
            isProgressFreshLocked(bookId, System.currentTimeMillis())
        }

        private fun isProgressFreshLocked(bookId: Long, now: Long): Boolean {
            val lastSyncedAt = LAST_SYNCED_AT_BY_BOOK_ID[bookId]
            return lastSyncedAt != null && now - lastSyncedAt < FRESHNESS_TTL_MS
        }
    }
}
