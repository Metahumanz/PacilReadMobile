package com.metahumanz.pacilread.reader

import android.util.Log
import com.metahumanz.pacilread.model.ReplacementRuleRecord
import java.util.LinkedHashMap
import java.util.regex.Pattern

object ReplacementEngine {
    private const val TAG = "ReplacementEngine"
    private const val MAX_COMPILED_REGEX_CACHE_SIZE = 128
    private val COMPILED_REGEX_CACHE = object : LinkedHashMap<String, Pattern>(MAX_COMPILED_REGEX_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pattern>?): Boolean =
            size > MAX_COMPILED_REGEX_CACHE_SIZE
    }

    @JvmStatic
    fun apply(source: String?, rules: List<ReplacementRuleRecord?>?): String {
        var result = source ?: ""
        if (rules.isNullOrEmpty()) return result
        for (rule in rules) {
            val pattern = rule?.pattern
            if (rule == null || !rule.active || pattern == null || pattern.isBlank()) continue
            val replacement = rule.replacement ?: ""
            try {
                result = if (rule.regex) {
                    val compiled = compiledPatternFor(rule) ?: continue
                    compiled.matcher(result).replaceAll(replacement)
                } else {
                    result.replace(pattern, replacement)
                }
            } catch (error: Exception) {
                Log.w(TAG, "规则执行失败: pattern=${rule.pattern}, error=${error.message}")
            }
        }
        return result
    }

    private fun compiledPatternFor(rule: ReplacementRuleRecord): Pattern? {
        val key = regexCacheKey(rule)
        synchronized(COMPILED_REGEX_CACHE) {
            COMPILED_REGEX_CACHE[key]?.let { return it }
            return try {
                Pattern.compile(rule.pattern!!).also { COMPILED_REGEX_CACHE[key] = it }
            } catch (error: Exception) {
                Log.w(TAG, "正则规则编译失败: pattern=${rule.pattern}, error=${error.message}")
                null
            }
        }
    }

    private fun regexCacheKey(rule: ReplacementRuleRecord): String = "${rule.id}|${rule.updatedAt}|${rule.pattern}"
}
