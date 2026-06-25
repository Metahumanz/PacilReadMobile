package com.metahumanz.pacilread.reader.modern.content

import com.metahumanz.pacilread.reader.PageSlice
import java.util.LinkedHashMap

class ReaderPageCache(maxLayoutSignatures: Int) {
    private val maxLayoutSignatures = maxLayoutSignatures.coerceAtLeast(1)
    private val activePagesByChapter: MutableMap<Int, List<PageSlice>> = HashMap()
    private val pagesByLayout: MutableMap<ReaderLayoutSignature, MutableMap<Int, List<PageSlice>>> =
        object : LinkedHashMap<ReaderLayoutSignature, MutableMap<Int, List<PageSlice>>>(this.maxLayoutSignatures, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ReaderLayoutSignature, MutableMap<Int, List<PageSlice>>>?): Boolean =
                size > this@ReaderPageCache.maxLayoutSignatures
        }
    private var activeLayoutSignature: ReaderLayoutSignature? = null
    private var activeBookId = -1L

    @Synchronized
    fun setBookId(bookId: Long) {
        if (activeBookId == bookId) return
        activeBookId = bookId
        clear()
    }

    @Synchronized
    fun get(chapterIndex: Int): List<PageSlice>? = activePagesByChapter[chapterIndex]

    @Synchronized
    fun contains(chapterIndex: Int): Boolean = activePagesByChapter.containsKey(chapterIndex)

    @Synchronized
    fun put(chapterIndex: Int, pages: List<PageSlice>) {
        activePagesByChapter[chapterIndex] = pages
        rememberActiveLayoutCacheLocked()
    }

    @Synchronized
    fun clear() {
        activePagesByChapter.clear()
        pagesByLayout.clear()
        activeLayoutSignature = null
    }

    @Synchronized
    fun hasAny(chapterIndex: Int): Boolean {
        if (activePagesByChapter.containsKey(chapterIndex)) return true
        for (pagesByChapter in pagesByLayout.values) {
            if (pagesByChapter.containsKey(chapterIndex)) return true
        }
        return false
    }

    @Synchronized
    fun activate(signature: ReaderLayoutSignature?, beforeIncompatibleSwitch: Runnable?): Boolean {
        if (signature == null) return false
        if (signature == activeLayoutSignature) return false
        val activeSignature = activeLayoutSignature
        if (activeSignature != null && activeSignature.isPaginationCompatibleWith(signature)) {
            val restored = pagesByLayout[signature]
            if (activePagesByChapter.isEmpty() && restored != null) activePagesByChapter.putAll(restored)
            pagesByLayout.remove(activeSignature)
            activeLayoutSignature = signature
            rememberActiveLayoutCacheLocked()
            return false
        }
        beforeIncompatibleSwitch?.run()
        rememberActiveLayoutCacheLocked()
        activePagesByChapter.clear()
        pagesByLayout[signature]?.let { activePagesByChapter.putAll(it) }
        activeLayoutSignature = signature
        return true
    }

    private fun rememberActiveLayoutCacheLocked() {
        val signature = activeLayoutSignature ?: return
        if (activePagesByChapter.isEmpty()) {
            pagesByLayout.remove(signature)
        } else {
            pagesByLayout[signature] = HashMap(activePagesByChapter)
        }
    }
}
