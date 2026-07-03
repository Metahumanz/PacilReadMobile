package com.metahumanz.pacilread.reader.modern.content

import com.metahumanz.pacilread.reader.PageSlice
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class ReaderPageCacheTest {
    @Test
    fun changingBookClearsCachedPages() {
        val cache = ReaderPageCache(4)
        val pages = listOf(PageSlice(0, 3, 0, 3, "abc"))
        cache.setBookId(1L)
        cache.activate(signature(360, 640, 0), null)
        cache.put(0, pages)
        assertTrue(cache.contains(0))
        cache.setBookId(2L)
        assertFalse(cache.contains(0))
        assertFalse(cache.hasAny(0))
    }

    @Test
    fun paginationCompatibleLayoutRetainsActivePages() {
        val cache = ReaderPageCache(4)
        val pages = listOf(PageSlice(0, 3, 0, 3, "abc"))
        val invalidated = AtomicBoolean(false)
        cache.setBookId(1L)
        cache.activate(signature(360, 640, 0), null)
        cache.put(0, pages)
        val cleared = cache.activate(signature(360, 640, 24)) { invalidated.set(true) }
        assertFalse(cleared)
        assertFalse(invalidated.get())
        assertSame(pages, cache.get(0))
    }

    @Test
    fun incompatibleLayoutClearsActivePages() {
        val cache = ReaderPageCache(4)
        val pages = listOf(PageSlice(0, 3, 0, 3, "abc"))
        val invalidated = AtomicBoolean(false)
        cache.setBookId(1L)
        cache.activate(signature(360, 640, 0), null)
        cache.put(0, pages)
        val cleared = cache.activate(signature(400, 640, 0)) { invalidated.set(true) }
        assertTrue(cleared)
        assertTrue(invalidated.get())
        assertFalse(cache.contains(0))
    }

    @Test
    fun inactiveLayoutPagesAreStillDiscoverable() {
        val cache = ReaderPageCache(4)
        val pages = listOf(PageSlice(0, 3, 0, 3, "abc"))
        cache.setBookId(1L)
        cache.activate(signature(360, 640, 0), null)
        cache.put(0, pages)
        cache.activate(signature(400, 640, 0), null)
        assertFalse(cache.contains(0))
        assertTrue(cache.hasAny(0))
    }

    @Test
    fun switchingBackToPreviousLayoutRestoresCachedPages() {
        val cache = ReaderPageCache(4)
        val narrowPages = listOf(PageSlice(0, 3, 0, 3, "abc"))
        val widePages = listOf(PageSlice(0, 4, 0, 4, "abcd"))
        cache.setBookId(1L)
        val narrow = signature(360, 640, 0)
        val wide = signature(400, 640, 0)
        cache.activate(narrow, null)
        cache.put(0, narrowPages)
        cache.activate(wide, null)
        cache.put(0, widePages)
        cache.activate(narrow, null)
        assertSame(narrowPages, cache.get(0))
    }

    private fun signature(width: Int, height: Int, systemInsetTop: Int) = ReaderLayoutSignature(
        width, height, true, "center", 22f, 18f, 400, "sans", 6f, 0f,
        2, 8, 20, 20, 24, 24, systemInsetTop, 0, false,
    )
}
