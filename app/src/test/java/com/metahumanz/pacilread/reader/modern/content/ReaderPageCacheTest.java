package com.metahumanz.pacilread.reader.modern.content;

import com.metahumanz.pacilread.reader.PageSlice;

import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ReaderPageCacheTest {
    @Test
    public void changingBookClearsCachedPages() {
        ReaderPageCache cache = new ReaderPageCache(4);
        List<PageSlice> pages = Collections.singletonList(new PageSlice(0, 3, 0, 3, "abc"));

        cache.setBookId(1L);
        cache.activate(signature(360, 640, 0), null);
        cache.put(0, pages);

        assertTrue(cache.contains(0));

        cache.setBookId(2L);

        assertFalse(cache.contains(0));
        assertFalse(cache.hasAny(0));
    }

    @Test
    public void paginationCompatibleLayoutRetainsActivePages() {
        ReaderPageCache cache = new ReaderPageCache(4);
        List<PageSlice> pages = Collections.singletonList(new PageSlice(0, 3, 0, 3, "abc"));
        AtomicBoolean invalidated = new AtomicBoolean(false);

        cache.setBookId(1L);
        cache.activate(signature(360, 640, 0), null);
        cache.put(0, pages);

        boolean cleared = cache.activate(signature(360, 640, 24), () -> invalidated.set(true));

        assertFalse(cleared);
        assertFalse(invalidated.get());
        assertSame(pages, cache.get(0));
    }

    @Test
    public void incompatibleLayoutClearsActivePages() {
        ReaderPageCache cache = new ReaderPageCache(4);
        List<PageSlice> pages = Collections.singletonList(new PageSlice(0, 3, 0, 3, "abc"));
        AtomicBoolean invalidated = new AtomicBoolean(false);

        cache.setBookId(1L);
        cache.activate(signature(360, 640, 0), null);
        cache.put(0, pages);

        boolean cleared = cache.activate(signature(400, 640, 0), () -> invalidated.set(true));

        assertTrue(cleared);
        assertTrue(invalidated.get());
        assertFalse(cache.contains(0));
    }

    private static ReaderLayoutSignature signature(int width, int height, int systemInsetTop) {
        return new ReaderLayoutSignature(
                width,
                height,
                true,
                "center",
                22f,
                18f,
                400,
                "sans",
                6f,
                0f,
                2,
                8,
                20,
                20,
                24,
                24,
                systemInsetTop,
                0,
                false
        );
    }
}
