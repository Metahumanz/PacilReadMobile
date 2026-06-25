package com.metahumanz.pacilread.importer

import java.util.Locale

object BookDuplicateDetector {
    enum class MatchType { EXACT_CONTENT, SAME_TITLE_AUTHOR }

    class Candidate(key: String?, title: String?, author: String?, sha256: String?) {
        @JvmField val key: String = key ?: ""
        @JvmField val title: String = title ?: ""
        @JvmField val author: String = author ?: ""
        @JvmField val sha256: String = sha256?.trim()?.lowercase(Locale.ROOT) ?: ""
    }

    @JvmStatic
    fun detect(existing: List<Candidate?>?, incoming: List<Candidate?>?): Map<String, MatchType> {
        val result = HashMap<String, MatchType>()
        val seen = ArrayList<Candidate?>()
        if (existing != null) seen.addAll(existing)
        if (incoming == null) return result
        for (candidate in incoming) {
            val match = findMatch(seen, candidate)
            if (match != null) result[candidate!!.key] = match
            seen.add(candidate)
        }
        return result
    }

    private fun findMatch(candidates: List<Candidate?>, target: Candidate?): MatchType? {
        if (target == null) return null
        if (target.sha256.isNotEmpty()) {
            for (candidate in candidates) {
                if (target.sha256 == candidate!!.sha256) return MatchType.EXACT_CONTENT
            }
        }
        val targetIdentity = identity(target.title, target.author)
        if (targetIdentity.isEmpty()) return null
        for (candidate in candidates) {
            if (targetIdentity == identity(candidate!!.title, candidate.author)) return MatchType.SAME_TITLE_AUTHOR
        }
        return null
    }

    @JvmStatic
    fun identity(title: String?, author: String?): String {
        val safeTitle = normalize(title)
        val safeAuthor = normalize(author)
        if (safeTitle.isEmpty() && safeAuthor.isEmpty()) return ""
        return "$safeTitle\u0000$safeAuthor"
    }

    private fun normalize(value: String?): String =
        value?.trim()?.replace(Regex("\\s+"), " ")?.lowercase(Locale.ROOT) ?: ""
}
