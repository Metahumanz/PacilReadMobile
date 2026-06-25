package com.metahumanz.pacilread.reader.search

object SearchTextMatcher {
    @JvmStatic
    fun findAll(text: String?, query: String?): List<Int> {
        val offsets = ArrayList<Int>()
        val normalizedText = normalize(text)
        val normalizedQuery = normalize(query?.trim() ?: "")
        if (normalizedQuery.isEmpty()) return offsets
        var from = 0
        while (from <= normalizedText.length - normalizedQuery.length) {
            val match = normalizedText.indexOf(normalizedQuery, from)
            if (match < 0) break
            offsets.add(match)
            from = match + 1
        }
        return offsets
    }

    @JvmStatic
    fun normalize(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        val result = StringBuilder(value.length)
        for (character in value) result.append(character.lowercaseChar())
        return result.toString()
    }
}
