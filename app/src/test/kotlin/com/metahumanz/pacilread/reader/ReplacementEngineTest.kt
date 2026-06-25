package com.metahumanz.pacilread.reader

import com.metahumanz.pacilread.model.ReplacementRuleRecord
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.regex.Pattern

class ReplacementEngineTest {
    @Test
    fun regexRulesReuseCompiledPatternUntilRuleChanges() {
        val cache = compiledRegexCache()
        synchronized(cache) { cache.clear() }
        val rule = regexRule(1L, "(\\d+)", "[$1]", 100L)

        assertEquals("a[123]", ReplacementEngine.apply("a123", listOf(rule)))
        synchronized(cache) { assertEquals(1, cache.size) }
        assertEquals("b[456]", ReplacementEngine.apply("b456", listOf(rule)))
        synchronized(cache) { assertEquals(1, cache.size) }

        rule.updatedAt = 101L
        assertEquals("c[789]", ReplacementEngine.apply("c789", listOf(rule)))
        synchronized(cache) { assertEquals(2, cache.size) }
    }

    @Test
    fun literalRulesDoNotPopulateRegexCache() {
        val cache = compiledRegexCache()
        synchronized(cache) { cache.clear() }
        val rule = ReplacementRuleRecord().apply {
            id = 2L
            pattern = "foo"
            replacement = "bar"
            active = true
            regex = false
        }
        assertEquals("bar baz", ReplacementEngine.apply("foo baz", listOf(rule)))
        synchronized(cache) { assertEquals(0, cache.size) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun compiledRegexCache(): MutableMap<String, Pattern> {
        val field = ReplacementEngine::class.java.getDeclaredField("COMPILED_REGEX_CACHE")
        field.isAccessible = true
        return field.get(null) as MutableMap<String, Pattern>
    }

    private fun regexRule(id: Long, pattern: String, replacement: String, updatedAt: Long) =
        ReplacementRuleRecord().apply {
            this.id = id
            this.pattern = pattern
            this.replacement = replacement
            active = true
            regex = true
            this.updatedAt = updatedAt
        }
}
