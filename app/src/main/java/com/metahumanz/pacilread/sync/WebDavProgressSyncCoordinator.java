package com.metahumanz.pacilread.sync;

import com.metahumanz.pacilread.model.BookRecord;
import com.metahumanz.pacilread.model.ChapterRecord;
import com.metahumanz.pacilread.storage.JsonDatabase;
import com.metahumanz.pacilread.storage.SettingsStore;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public final class WebDavProgressSyncCoordinator {
    public static final int BOOKSHELF_PREFETCH_LIMIT = 6;

    private static final long FRESHNESS_TTL_MS = TimeUnit.MINUTES.toMillis(5);
    private static final long REMOTE_NEWER_GRACE_MS = 5000L;
    private static final Object LOCK = new Object();
    private static final Map<Long, Long> LAST_SYNCED_AT_BY_BOOK_ID = new HashMap<>();
    private static final Set<Long> IN_FLIGHT_BOOK_IDS = new HashSet<>();

    private final JsonDatabase databaseHelper;
    private final SettingsStore settingsStore;
    private final WebDavClient webDavClient;

    public WebDavProgressSyncCoordinator(
            JsonDatabase databaseHelper,
            SettingsStore settingsStore,
            WebDavClient webDavClient
    ) {
        this.databaseHelper = databaseHelper;
        this.settingsStore = settingsStore;
        this.webDavClient = webDavClient;
    }

    public static boolean isProgressFresh(long bookId) {
        synchronized (LOCK) {
            return isProgressFreshLocked(bookId, System.currentTimeMillis());
        }
    }

    public SyncResult syncBookProgressIfNeeded(BookRecord book) throws Exception {
        return syncBookProgressIfNeeded(book, null);
    }

    public SyncResult syncBookProgressIfNeeded(BookRecord book, ProgressBaseline baseline) throws Exception {
        if (book == null || book.id <= 0) {
            return SyncResult.skipped(false);
        }
        if (!settingsStore.isWebDavEnabled()) {
            return SyncResult.skipped(false);
        }
        if (!beginSync(book.id)) {
            return SyncResult.skipped(true);
        }
        boolean completedSuccessfully = false;
        try {
            BookRecord currentBook = databaseHelper.getBook(book.id);
            if (currentBook == null) {
                currentBook = book;
            }
            WebDavClient.ProgressPayload payload = webDavClient.downloadProgress(currentBook);
            if (payload == null) {
                completedSuccessfully = true;
                return SyncResult.checkedNoRemote();
            }

            ProgressBaseline comparison = baseline != null ? baseline : ProgressBaseline.fromBook(currentBook);
            boolean localEmpty = comparison.progressIndex == 0 && comparison.progressOffset == 0;
            boolean remoteNewer = payload.chapterTime > comparison.lastReadAt + REMOTE_NEWER_GRACE_MS;
            if (!remoteNewer && !localEmpty) {
                completedSuccessfully = true;
                return SyncResult.checkedNotApplied(true);
            }

            int chapterOrderIndex = resolveChapterOrderIndex(currentBook, payload.chapterIndex);
            int chapterPosition = Math.max(payload.chapterPosition, 0);
            databaseHelper.updateProgressFromRemote(
                    currentBook.id,
                    chapterOrderIndex,
                    chapterPosition,
                    payload.chapterTime
            );
            completedSuccessfully = true;
            return SyncResult.applied(chapterOrderIndex, chapterPosition, payload.chapterTime);
        } finally {
            finishSync(book.id, completedSuccessfully);
        }
    }

    private boolean beginSync(long bookId) throws InterruptedException {
        synchronized (LOCK) {
            while (IN_FLIGHT_BOOK_IDS.contains(bookId)) {
                LOCK.wait(250L);
                if (isProgressFreshLocked(bookId, System.currentTimeMillis())) {
                    return false;
                }
            }
            if (isProgressFreshLocked(bookId, System.currentTimeMillis())) {
                return false;
            }
            IN_FLIGHT_BOOK_IDS.add(bookId);
            return true;
        }
    }

    private void finishSync(long bookId, boolean checkedRemote) {
        synchronized (LOCK) {
            IN_FLIGHT_BOOK_IDS.remove(bookId);
            if (checkedRemote) {
                LAST_SYNCED_AT_BY_BOOK_ID.put(bookId, System.currentTimeMillis());
            }
            LOCK.notifyAll();
        }
    }

    private static boolean isProgressFreshLocked(long bookId, long now) {
        Long lastSyncedAt = LAST_SYNCED_AT_BY_BOOK_ID.get(bookId);
        return lastSyncedAt != null && now - lastSyncedAt < FRESHNESS_TTL_MS;
    }

    private int resolveChapterOrderIndex(BookRecord book, int remoteChapterIndex) {
        List<ChapterRecord> chapters = databaseHelper.getChapters(book.id, false);
        if (chapters == null || chapters.isEmpty()) {
            return Math.max(remoteChapterIndex, 0);
        }
        for (ChapterRecord chapter : chapters) {
            if (chapter.orderIndex == remoteChapterIndex) {
                return chapter.orderIndex;
            }
        }
        int safeIndex = Math.max(0, Math.min(remoteChapterIndex, chapters.size() - 1));
        return chapters.get(safeIndex).orderIndex;
    }

    public static final class ProgressBaseline {
        public final long lastReadAt;
        public final int progressIndex;
        public final int progressOffset;

        public ProgressBaseline(long lastReadAt, int progressIndex, int progressOffset) {
            this.lastReadAt = lastReadAt;
            this.progressIndex = progressIndex;
            this.progressOffset = progressOffset;
        }

        static ProgressBaseline fromBook(BookRecord book) {
            return new ProgressBaseline(book.lastReadAt, book.progressIndex, book.progressOffset);
        }
    }

    public static final class SyncResult {
        public final boolean checkedRemote;
        public final boolean remoteAvailable;
        public final boolean remoteApplied;
        public final boolean skippedFresh;
        public final int chapterOrderIndex;
        public final int chapterPosition;
        public final long chapterTime;

        private SyncResult(
                boolean checkedRemote,
                boolean remoteAvailable,
                boolean remoteApplied,
                boolean skippedFresh,
                int chapterOrderIndex,
                int chapterPosition,
                long chapterTime
        ) {
            this.checkedRemote = checkedRemote;
            this.remoteAvailable = remoteAvailable;
            this.remoteApplied = remoteApplied;
            this.skippedFresh = skippedFresh;
            this.chapterOrderIndex = chapterOrderIndex;
            this.chapterPosition = chapterPosition;
            this.chapterTime = chapterTime;
        }

        static SyncResult skipped(boolean skippedFresh) {
            return new SyncResult(false, false, false, skippedFresh, 0, 0, 0L);
        }

        static SyncResult checkedNoRemote() {
            return new SyncResult(true, false, false, false, 0, 0, 0L);
        }

        static SyncResult checkedNotApplied(boolean remoteAvailable) {
            return new SyncResult(true, remoteAvailable, false, false, 0, 0, 0L);
        }

        static SyncResult applied(int chapterOrderIndex, int chapterPosition, long chapterTime) {
            return new SyncResult(true, true, true, false, chapterOrderIndex, chapterPosition, chapterTime);
        }
    }
}
