package com.metahumanz.pacilread.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class WebDavDirectorySemanticsTest {
    @Test fun nestedDirectoryIsExpandedInOrder() {
        assertEquals(
            listOf("snapshots", "generation-1", "database"),
            normalizedDirectorySegments("/snapshots/generation-1/database/"),
        )
    }

    @Test fun blankDirectoryHasNoSegments() {
        assertEquals(emptyList<String>(), normalizedDirectorySegments(" / "))
    }
}
