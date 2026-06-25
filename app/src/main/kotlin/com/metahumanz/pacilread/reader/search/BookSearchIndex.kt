package com.metahumanz.pacilread.reader.search

import android.content.Context
import com.metahumanz.pacilread.model.ChapterRecord
import com.metahumanz.pacilread.model.ReplacementRuleRecord
import com.metahumanz.pacilread.reader.ReplacementEngine
import com.metahumanz.pacilread.storage.JsonDatabase
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.BitSet
import java.util.Locale

class BookSearchIndex(context: Context, private val database: JsonDatabase) {
    fun interface CancellationToken {
        fun isCancelled(): Boolean
    }

    class Result(
        @JvmField val chapterIndex: Int,
        @JvmField val chapterTitle: String?,
        @JvmField val snippet: String,
        @JvmField val charOffset: Int,
    )

    private val indexDirectory = File(context.applicationContext.filesDir, "search_index")

    fun isReady(bookId: Long): Boolean = try {
        val chapters = database.getChapters(bookId, false)
        val rules = database.getReplacementRules(bookId)
        val file = indexFile(bookId)
        if (!file.isFile) {
            false
        } else {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
                input.readInt() == MAGIC && input.readInt() == VERSION && fingerprint(chapters, rules) == input.readUTF()
            }
        }
    } catch (_: Exception) {
        false
    }

    @Throws(Exception::class)
    fun build(bookId: Long, cancellationToken: CancellationToken?) {
        buildInternal(bookId, database.getChapters(bookId, false), database.getReplacementRules(bookId), cancellationToken)
    }

    @Throws(Exception::class)
    fun search(bookId: Long, query: String?, cancellationToken: CancellationToken?): List<Result> {
        val normalizedQuery = SearchTextMatcher.normalize(query?.trim().orEmpty())
        if (normalizedQuery.isEmpty()) return ArrayList()
        val chapters = database.getChapters(bookId, false)
        val rules = database.getReplacementRules(bookId)
        val expectedFingerprint = fingerprint(chapters, rules)
        var index = readIndex(bookId, expectedFingerprint)
        if (index == null) {
            buildInternal(bookId, chapters, rules, cancellationToken)
            if (isCancelled(cancellationToken)) return ArrayList()
            index = readIndex(bookId, expectedFingerprint)
        }
        val results = ArrayList<Result>()
        if (index == null) return results
        for (chapterIndex in chapters.indices) {
            if (isCancelled(cancellationToken)) return ArrayList()
            if (!index.chapterBlooms[chapterIndex].mightContain(normalizedQuery)) continue
            val chapter = chapters[chapterIndex]
            val processed = ReplacementEngine.apply(resolveText(chapter), rules)
            for (match in findAll(processed, normalizedQuery, cancellationToken)) {
                if (isCancelled(cancellationToken)) return ArrayList()
                val start = Math.max(0, match - 24)
                val end = Math.min(processed.length, match + normalizedQuery.length + 32)
                results.add(Result(chapterIndex, chapter.title, processed.substring(start, end).replace('\n', ' ').trim(), match))
            }
        }
        return results
    }

    fun invalidate(bookId: Long) {
        indexFile(bookId).delete()
    }

    @Throws(Exception::class)
    private fun buildInternal(
        bookId: Long,
        chapters: List<ChapterRecord>,
        rules: List<ReplacementRuleRecord>,
        cancellationToken: CancellationToken?,
    ) {
        if (!indexDirectory.exists() && !indexDirectory.mkdirs()) throw IllegalStateException("无法创建搜索索引目录")
        val target = indexFile(bookId)
        val temp = File(indexDirectory, "${target.name}.tmp")
        FileOutputStream(temp).use { fileOutput ->
            DataOutputStream(BufferedOutputStream(fileOutput)).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(VERSION)
                output.writeUTF(fingerprint(chapters, rules))
                output.writeInt(chapters.size)
                for (chapter in chapters) {
                    if (isCancelled(cancellationToken)) {
                        temp.delete()
                        return
                    }
                    val processed = SearchTextMatcher.normalize(ReplacementEngine.apply(resolveText(chapter), rules))
                    val bloom = ChapterBloom.build(processed, cancellationToken)
                    if (bloom == null) {
                        temp.delete()
                        return
                    }
                    output.writeInt(bloom.bitSize)
                    val bytes = bloom.bits.toByteArray()
                    output.writeInt(bytes.size)
                    output.write(bytes)
                }
                output.flush()
                fileOutput.fd.sync()
            }
        }
        if (target.exists() && !target.delete()) {
            temp.delete()
            throw IllegalStateException("无法替换搜索索引")
        }
        if (!temp.renameTo(target)) {
            temp.delete()
            throw IllegalStateException("无法保存搜索索引")
        }
    }

    private fun readIndex(bookId: Long, expectedFingerprint: String): LoadedIndex? {
        val file = indexFile(bookId)
        if (!file.isFile) return null
        return try {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != VERSION) return null
                if (expectedFingerprint != input.readUTF()) return null
                val count = input.readInt()
                if (count != database.getChapters(bookId, false).size) return null
                val blooms = ArrayList<ChapterBloom>(count)
                for (i in 0 until count) {
                    val bitSize = input.readInt()
                    val byteCount = input.readInt()
                    if (bitSize <= 0 || byteCount < 0 || byteCount > 8 * 1024 * 1024) return null
                    val bytes = ByteArray(byteCount)
                    input.readFully(bytes)
                    blooms.add(ChapterBloom(bitSize, BitSet.valueOf(bytes)))
                }
                LoadedIndex(blooms)
            }
        } catch (_: Exception) {
            file.delete()
            null
        }
    }

    private fun resolveText(chapter: ChapterRecord): String = database.resolveChapterText(
        chapter.bookId, chapter.id, chapter.bodyText, chapter.bodyTextPath, chapter.bodyTextStorage,
    )

    @Throws(Exception::class)
    private fun fingerprint(chapters: List<ChapterRecord>, rules: List<ReplacementRuleRecord>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (chapter in chapters) {
            updateDigest(digest, "${chapter.id}|${chapter.orderIndex}|${chapter.bodyTextSize}|${safe(chapter.bodyTextPath)}|${safe(chapter.bodyTextStorage)}")
            database.resolveChapterTextFile(chapter.bodyTextPath)?.let {
                updateDigest(digest, "|${it.length()}|${it.lastModified()}")
            }
        }
        for (rule in rules) {
            updateDigest(digest, "${rule.id}|${rule.updatedAt}|${rule.active}|${rule.regex}|${safe(rule.pattern)}|${safe(rule.replacement)}")
        }
        val result = StringBuilder(64)
        for (value in digest.digest()) result.append(String.format(Locale.ROOT, "%02x", value.toInt() and 0xff))
        return result.toString()
    }

    private fun updateDigest(digest: MessageDigest, value: String) {
        digest.update(value.toByteArray(StandardCharsets.UTF_8))
        digest.update(0.toByte())
    }

    private fun indexFile(bookId: Long): File = File(indexDirectory, "book_$bookId.idx")
    private class LoadedIndex(val chapterBlooms: List<ChapterBloom>)

    private class ChapterBloom(val bitSize: Int, val bits: BitSet) {
        fun mightContain(query: String): Boolean {
            val length = Math.min(3, query.length)
            for (start in 0..query.length - length) {
                if (!contains(bits, bitSize, query, start, length)) return false
            }
            return true
        }

        companion object {
            fun build(text: String, cancellationToken: CancellationToken?): ChapterBloom? {
                val desiredBits = Math.max(1024, Math.min(8 * 1024 * 1024, text.length * 12))
                val bitSize = (desiredBits + 63) / 64 * 64
                val bits = BitSet(bitSize)
                for (length in 1..3) {
                    for (start in 0..text.length - length) {
                        if (start and 0x3ff == 0 && isCancelled(cancellationToken)) return null
                        add(bits, bitSize, text, start, length)
                    }
                }
                return ChapterBloom(bitSize, bits)
            }

            private fun add(bits: BitSet, bitSize: Int, value: String, start: Int, length: Int) {
                val hashes = hashes(value, start, length)
                for (i in 0 until HASH_PROBES) bits.set(index(hashes[0] + i * hashes[1], bitSize))
            }

            private fun contains(bits: BitSet, bitSize: Int, value: String, start: Int, length: Int): Boolean {
                val hashes = hashes(value, start, length)
                for (i in 0 until HASH_PROBES) if (!bits[index(hashes[0] + i * hashes[1], bitSize)]) return false
                return true
            }

            private fun hashes(value: String, start: Int, length: Int): LongArray {
                var first = -3750763034362895579L
                var second = -7046029254386353131L
                for (i in start until start + length) {
                    val charValue = value[i].code.toLong()
                    first = (first xor charValue) * 0x100000001b3L
                    second = second xor (charValue + 0x9e3779b9L + (second shl 6) + (second ushr 2))
                }
                if (second and 1L == 0L) second++
                return longArrayOf(first, second)
            }

            private fun index(hash: Long, bitSize: Int): Int = ((hash and Long.MAX_VALUE) % bitSize).toInt()
        }
    }

    companion object {
        private const val MAGIC = 0x50525349
        private const val VERSION = 1
        private const val HASH_PROBES = 4
        private fun safe(value: String?): String = value ?: ""
        private fun isCancelled(token: CancellationToken?): Boolean = token?.isCancelled() == true

        private fun findAll(text: String, normalizedQuery: String, cancellationToken: CancellationToken?): List<Int> {
            val offsets = ArrayList<Int>()
            val normalizedText = SearchTextMatcher.normalize(text)
            if (normalizedQuery.isEmpty()) return offsets
            var from = 0
            var checks = 0
            while (from <= normalizedText.length - normalizedQuery.length) {
                if (checks++ and 0xff == 0 && isCancelled(cancellationToken)) return ArrayList()
                val match = normalizedText.indexOf(normalizedQuery, from)
                if (match < 0) break
                offsets.add(match)
                from = match + 1
            }
            return offsets
        }

        @JvmStatic
        fun delete(context: Context, bookId: Long) {
            File(File(context.applicationContext.filesDir, "search_index"), "book_$bookId.idx").delete()
        }
    }
}
