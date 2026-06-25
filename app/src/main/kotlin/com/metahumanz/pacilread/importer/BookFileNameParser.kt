package com.metahumanz.pacilread.importer

import java.util.regex.Pattern

object BookFileNameParser {
    private val TITLE_PATTERN = Pattern.compile("《([^》]+)》")
    private val AUTHOR_MARKER = Pattern.compile("(?:作者|Author)\\s*[:： ]\\s*([^\\s_()（）\\[\\]【】-]{1,24})", Pattern.CASE_INSENSITIVE)
    private val BRACKET_AUTHOR = Pattern.compile("^[\\[【]([^\\]】]{1,24})[\\]】]\\s*(.+)$")
    private val DASH_AUTHOR = Pattern.compile("^(.+?)\\s*[-_]\\s*([^-_]{1,24})$")

    @JvmStatic
    fun parse(rawName: String?): ParsedName {
        var stem = rawName?.trim() ?: ""
        val dotIndex = stem.lastIndexOf('.')
        if (dotIndex > 0) stem = stem.substring(0, dotIndex)

        var title = stem
        var author: String? = null

        val titleMatcher = TITLE_PATTERN.matcher(stem)
        if (titleMatcher.find()) title = titleMatcher.group(1)!!.trim()

        val authorMatcher = AUTHOR_MARKER.matcher(stem)
        if (authorMatcher.find()) {
            author = cleanAuthor(authorMatcher.group(1))
            title = stem.replace(authorMatcher.group(0)!!, " ")
        }

        if (author == null) {
            val bracketMatcher = BRACKET_AUTHOR.matcher(stem)
            if (bracketMatcher.matches()) {
                author = cleanAuthor(bracketMatcher.group(1))
                title = bracketMatcher.group(2)!!
            }
        }

        if (author == null) {
            val dashMatcher = DASH_AUTHOR.matcher(stem)
            if (dashMatcher.matches()) {
                title = dashMatcher.group(1)!!
                author = cleanAuthor(dashMatcher.group(2))
            }
        }

        title = title.replace(Regex("[（(][^）)]*(?:精校|校对|全本|番外|完结|修改)[^）)]*[）)]"), " ")
        title = title.replace(Regex("[_\\-]+"), " ").replace(Regex("\\s+"), " ").trim()
        title = title.replace("《", "").replace("》", "").trim()

        if (author == "未知" || author != null && author.isBlank()) author = null
        if (title.isBlank()) title = stem
        return ParsedName(title, author)
    }

    private fun cleanAuthor(value: String?): String? = value?.replace(Regex("[_\\-]+"), " ")?.trim()

    class ParsedName(
        @JvmField val title: String,
        @JvmField val author: String?,
    )
}
