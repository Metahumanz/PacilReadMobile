package com.metahumanz.pacilread.stats

import org.junit.Assert.assertEquals
import org.junit.Test

class BookIdentityProtocolTest {
    @Test fun sharedVectorsMatchDesktopUtf8Hashes() {
        val vectors = listOf(
            Triple("三体", "刘慈欣", "e7e6091acb0cc44ca4cd526302d4150c0950accc864b1bc7962249c731d93b29"),
            Triple("无作者", "", "4c3bf4812dd1a864980cd8232bc5238ac7c2f49198f9dddce0cb9ba909c4fa66"),
            Triple("  THE\t Book  ", " A\n B ", "55eb80539bc133d6e9b7476e8d8a8c9b48d891e44f68c8bae3774c7d16f8557b"),
            Triple("\u00a0三\u3000体\u202f", "\ufeff刘\u00a0慈欣\u3000", "e5b4c9e9299edbfcb3132be5df5087d2bb72c19b97f8a4143b19063030efe738"),
            Triple("İSTANBUL", "MÜLLER", "d87f5109670247323053d28d7affcaf33b4d364efe287d4d7fc9f86e73ada5e1"),
            Triple("", "", "01ba4719c80b6fe911b091a7c05124b64eeece964e09c058ef8f9805daca546b"),
        )
        for ((title, author, digest) in vectors) assertEquals(digest, ReadingStatsUtils.buildBookIdentity(title, author))
        assertEquals(ReadingStatsUtils.buildBookIdentity("无作者", ""), ReadingStatsUtils.buildBookIdentity("无作者", null))
        assertEquals(ReadingStatsUtils.buildBookIdentity("", ""), ReadingStatsUtils.buildBookIdentity(null, null))
    }

    @Test fun legacyAndroidKeyMapsOnlyWhenVerified() {
        val old = ReadingStatsUtils.buildLegacyAndroidBookIdentity("三体", "刘慈欣")
        assertEquals("f430d8053b26af0addcc37200dabb798f426276b5aed0566e319c262e0a05354", old)
        assertEquals(ReadingStatsUtils.buildBookIdentity("三体", "刘慈欣"), ReadingStatsUtils.canonicalBookIdentity(old, "三体", "刘慈欣"))
        assertEquals(
            "06d5ecf6a3c412249ced042ba440e5f8c6109eb5a452dd95d515a875c0ccba40",
            ReadingStatsUtils.buildLegacyAndroidBookIdentity("\u00a0三\u3000体\u202f", "\ufeff刘\u00a0慈欣\u3000"),
        )
        assertEquals("unrecognized-key", ReadingStatsUtils.canonicalBookIdentity("unrecognized-key", "三体", "刘慈欣"))
    }
}
