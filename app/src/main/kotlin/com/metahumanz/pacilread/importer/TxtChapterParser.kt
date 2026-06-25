package com.metahumanz.pacilread.importer

import com.metahumanz.pacilread.model.ImportedBook
import com.metahumanz.pacilread.util.HtmlUtils
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

object TxtChapterParser {
    private val CHAPTER_PATTERNS = arrayOf(
        Pattern.compile("(?m)^[ \\t\\u3000]{0,6}(?:序章|前言|楔子|引子|尾声|后记|番外|终章|第[0-9一二三四五六七八九十百千万零两〇]+[章节卷回部篇集节])(?:[ \\t:：、,.，。—\\-]{0,6}.{0,30})?$"),
        Pattern.compile("(?m)^[ \\t\\u3000]{0,4}\\d{1,5}[、.:：]\\s*.{1,30}$"),
        Pattern.compile("(?m)^[ \\t\\u3000]{0,6}(?:Chapter|CHAPTER)\\s+[0-9IVXLC]+(?:\\s+.{0,24})?$"),
    )

    @JvmStatic
    @Throws(IOException::class)
    fun parse(inputStream: InputStream): List<ImportedBook.ChapterSeed> {
        val raw = readAll(inputStream)
        val content = HtmlUtils.normalizeText(decode(raw)).trim()
        return split(content)
    }

    @JvmStatic
    fun split(content: String): List<ImportedBook.ChapterSeed> {
        val matches = findBestHeadings(content)
        val result = ArrayList<ImportedBook.ChapterSeed>()
        if (matches.isEmpty()) {
            result.add(ImportedBook.ChapterSeed("全文", "", content, 0))
            return result
        }

        var chapterIndex = 0
        if (matches[0].start > 0) {
            val intro = content.substring(0, matches[0].start).trim()
            if (intro.isNotEmpty()) result.add(ImportedBook.ChapterSeed("前言", "", intro, chapterIndex++))
        }

        for (index in matches.indices) {
            val current = matches[index]
            val nextStart = if (index + 1 < matches.size) matches[index + 1].start else content.length
            val body = content.substring(current.end, nextStart).trim()
            result.add(
                ImportedBook.ChapterSeed(
                    if (current.title.isEmpty()) "第 ${chapterIndex + 1} 章" else current.title,
                    "",
                    body,
                    chapterIndex,
                ),
            )
            chapterIndex++
        }
        if (result.isEmpty()) result.add(ImportedBook.ChapterSeed("全文", "", content, 0))
        return result
    }

    private fun findBestHeadings(content: String): List<HeadingMatch> {
        var best: List<HeadingMatch> = ArrayList()
        for (pattern in CHAPTER_PATTERNS) {
            val matcher = pattern.matcher(content)
            val current = ArrayList<HeadingMatch>()
            while (matcher.find()) current.add(HeadingMatch(matcher.start(), matcher.end(), matcher.group().trim()))
            if (current.size > best.size) best = current
        }
        return best
    }

    @Throws(IOException::class)
    private fun readAll(inputStream: InputStream): ByteArray {
        val outputStream = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = inputStream.read(buffer)
            if (read == -1) break
            outputStream.write(buffer, 0, read)
        }
        return outputStream.toByteArray()
    }

    private fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0].toInt() and 0xFF == 0xEF && bytes[1].toInt() and 0xFF == 0xBB && bytes[2].toInt() and 0xFF == 0xBF) {
            return String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0].toInt() and 0xFF == 0xFF && bytes[1].toInt() and 0xFF == 0xFE) {
            return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0].toInt() and 0xFF == 0xFE && bytes[1].toInt() and 0xFF == 0xFF) {
            return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
        }
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            String(bytes, Charset.forName("GB18030"))
        }
    }

    private class HeadingMatch(val start: Int, val end: Int, val title: String)
}
