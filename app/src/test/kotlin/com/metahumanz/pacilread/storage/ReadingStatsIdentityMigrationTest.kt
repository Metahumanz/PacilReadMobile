package com.metahumanz.pacilread.storage

import com.metahumanz.pacilread.model.ReadingTimeEntryRecord
import com.metahumanz.pacilread.stats.ReadingStatsUtils
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingStatsIdentityMigrationTest {
    private fun row(legacy: Boolean, seconds: Int, device: String = "phone") = ReadingTimeEntryRecord().apply {
        bookTitle = "三体"
        bookAuthor = "刘慈欣"
        bookIdentity = if (legacy) ReadingStatsUtils.buildLegacyAndroidBookIdentity(bookTitle, bookAuthor)
            else ReadingStatsUtils.buildBookIdentity(bookTitle, bookAuthor)
        sourceDeviceId = device
        date = "2026-09-26"
        durationSeconds = seconds
        charCount = seconds * 10
        updatedAt = seconds.toLong()
    }

    // 仅运行不依赖 Android Context 的归组逻辑；不初始化或读写真实数据库。
    private fun database(): JsonDatabase {
        val allocatorClass = Class.forName("sun.misc.Unsafe")
        val field = allocatorClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        return allocatorClass.getMethod("allocateInstance", Class::class.java)
            .invoke(field.get(null), JsonDatabase::class.java) as JsonDatabase
    }

    @Suppress("UNCHECKED_CAST")
    private fun normalize(rows: List<ReadingTimeEntryRecord>, local: Boolean): List<ReadingTimeEntryRecord> {
        val db = database()
        if (!local) {
            val method = JsonDatabase::class.java.getDeclaredMethod("normalizeIncomingReadingStatsRows", List::class.java)
                .apply { isAccessible = true }
            return method.invoke(db, rows) as List<ReadingTimeEntryRecord>
        }
        val cache = JsonDatabase::class.java.getDeclaredField("readingStatsCache").apply { isAccessible = true }
        cache.set(db, rows.toMutableList())
        JsonDatabase::class.java.getDeclaredMethod("normalizeReadingStatsCacheLocked")
            .apply { isAccessible = true }.invoke(db)
        return cache.get(db) as List<ReadingTimeEntryRecord>
    }

    private fun <T> permutations(items: List<T>): List<List<T>> = if (items.isEmpty()) listOf(emptyList()) else
        items.indices.flatMap { index -> permutations(items.filterIndexed { i, _ -> i != index }).map { listOf(items[index]) + it } }

    @Test fun mixedIdentityRowsHaveOrderIndependentTotals() {
        val rows = listOf(row(true, 100), row(false, 130), row(true, 20), row(false, 10))
        for (local in listOf(false, true)) {
            for (order in permutations(rows)) {
                val result = normalize(order, local)
                assertEquals(1, result.size)
                assertEquals(140, result.single().durationSeconds)
                assertEquals(1400, result.single().charCount)
                assertEquals(130L, result.single().updatedAt)
                assertEquals(ReadingStatsUtils.buildBookIdentity("三体", "刘慈欣"), result.single().bookIdentity)
            }
        }
    }

    @Test fun repeatedKeyKeepsExistingSumAndDevicesStaySeparate() {
        for (local in listOf(false, true)) {
            val result = normalize(listOf(row(true, 100), row(true, 20), row(false, 130, "desktop")), local)
            assertEquals(2, result.size)
            assertEquals(120, result.single { it.sourceDeviceId == "phone" }.durationSeconds)
            assertEquals(130, result.single { it.sourceDeviceId == "desktop" }.durationSeconds)
        }
    }
}
