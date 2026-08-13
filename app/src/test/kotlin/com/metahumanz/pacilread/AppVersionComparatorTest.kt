package com.metahumanz.pacilread

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppVersionComparatorTest {
    @Test
    fun comparesNumericVersionsByEachPart() {
        assertEquals(1, AppVersionComparator.compare("v1.10.0", "1.9.9"))
        assertEquals(-1, AppVersionComparator.compare("v1.4.5", "1.4.6"))
        assertEquals(0, AppVersionComparator.compare("v1.4.6.0", "1.4.6"))
    }

    @Test
    fun rejectsNonNumericReleaseTags() {
        assertNull(AppVersionComparator.compare("v1.4.7-beta", "1.4.6"))
        assertNull(AppVersionComparator.compare("v1.4.7", "dev"))
    }
}
