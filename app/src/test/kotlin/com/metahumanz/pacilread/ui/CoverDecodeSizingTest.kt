package com.metahumanz.pacilread.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CoverDecodeSizingTest {
    @Test
    fun sampleSizeUsesPowerOfTwoWhileBothDimensionsFit() {
        assertEquals(4, CoverDecodeSizing.sampleSizeFor(2000, 1000, 500, 250))
        assertEquals(1, CoverDecodeSizing.sampleSizeFor(600, 300, 500, 250))
    }

    @Test
    fun targetSizeFallsBackWhenViewIsNotMeasured() {
        assertEquals(360, CoverDecodeSizing.targetSize(0))
        assertEquals(240, CoverDecodeSizing.targetSize(240))
    }
}
