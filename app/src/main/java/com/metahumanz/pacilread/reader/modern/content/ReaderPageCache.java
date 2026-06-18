package com.metahumanz.pacilread.reader.modern.content;

import com.metahumanz.pacilread.reader.PageSlice;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ReaderPageCache {
    private final int maxLayoutSignatures;
    private final Map<Integer, List<PageSlice>> activePagesByChapter = new HashMap<>();
    private final Map<ReaderLayoutSignature, Map<Integer, List<PageSlice>>> pagesByLayout;
    private ReaderLayoutSignature activeLayoutSignature;
    private long activeBookId = -1L;

    ReaderPageCache(int maxLayoutSignatures) {
        this.maxLayoutSignatures = Math.max(maxLayoutSignatures, 1);
        this.pagesByLayout = new LinkedHashMap<ReaderLayoutSignature, Map<Integer, List<PageSlice>>>(
                this.maxLayoutSignatures,
                0.75f,
                true
        ) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<ReaderLayoutSignature, Map<Integer, List<PageSlice>>> eldest) {
                return size() > ReaderPageCache.this.maxLayoutSignatures;
            }
        };
    }

    synchronized void setBookId(long bookId) {
        if (activeBookId == bookId) {
            return;
        }
        activeBookId = bookId;
        clear();
    }

    synchronized List<PageSlice> get(int chapterIndex) {
        return activePagesByChapter.get(chapterIndex);
    }

    synchronized boolean contains(int chapterIndex) {
        return activePagesByChapter.containsKey(chapterIndex);
    }

    synchronized void put(int chapterIndex, List<PageSlice> pages) {
        activePagesByChapter.put(chapterIndex, pages);
        rememberActiveLayoutCacheLocked();
    }

    synchronized void clear() {
        activePagesByChapter.clear();
        pagesByLayout.clear();
        activeLayoutSignature = null;
    }

    synchronized boolean hasAny(int chapterIndex) {
        if (activePagesByChapter.containsKey(chapterIndex)) {
            return true;
        }
        for (Map<Integer, List<PageSlice>> pagesByChapter : pagesByLayout.values()) {
            if (pagesByChapter != null && pagesByChapter.containsKey(chapterIndex)) {
                return true;
            }
        }
        return false;
    }

    synchronized boolean activate(ReaderLayoutSignature signature, Runnable beforeIncompatibleSwitch) {
        if (signature == null) {
            return false;
        }
        if (signature.equals(activeLayoutSignature)) {
            return false;
        }
        if (activeLayoutSignature != null && activeLayoutSignature.isPaginationCompatibleWith(signature)) {
            Map<Integer, List<PageSlice>> restored = pagesByLayout.get(signature);
            if (activePagesByChapter.isEmpty() && restored != null) {
                activePagesByChapter.putAll(restored);
            }
            pagesByLayout.remove(activeLayoutSignature);
            activeLayoutSignature = signature;
            rememberActiveLayoutCacheLocked();
            return false;
        }
        if (beforeIncompatibleSwitch != null) {
            beforeIncompatibleSwitch.run();
        }
        rememberActiveLayoutCacheLocked();
        activePagesByChapter.clear();
        Map<Integer, List<PageSlice>> restored = pagesByLayout.get(signature);
        if (restored != null) {
            activePagesByChapter.putAll(restored);
        }
        activeLayoutSignature = signature;
        return true;
    }

    private void rememberActiveLayoutCacheLocked() {
        if (activeLayoutSignature == null) {
            return;
        }
        if (activePagesByChapter.isEmpty()) {
            pagesByLayout.remove(activeLayoutSignature);
        } else {
            pagesByLayout.put(activeLayoutSignature, new HashMap<>(activePagesByChapter));
        }
    }
}
