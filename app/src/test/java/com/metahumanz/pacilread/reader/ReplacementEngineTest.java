package com.metahumanz.pacilread.reader;

import com.metahumanz.pacilread.model.ReplacementRuleRecord;

import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;

public class ReplacementEngineTest {
    @Test
    public void regexRulesReuseCompiledPatternUntilRuleChanges() throws Exception {
        Map<String, Pattern> cache = compiledRegexCache();
        synchronized (cache) {
            cache.clear();
        }

        ReplacementRuleRecord rule = regexRule(1L, "(\\d+)", "[$1]", 100L);

        assertEquals("a[123]", ReplacementEngine.apply("a123", Collections.singletonList(rule)));
        synchronized (cache) {
            assertEquals(1, cache.size());
        }

        assertEquals("b[456]", ReplacementEngine.apply("b456", Collections.singletonList(rule)));
        synchronized (cache) {
            assertEquals(1, cache.size());
        }

        rule.updatedAt = 101L;
        assertEquals("c[789]", ReplacementEngine.apply("c789", Collections.singletonList(rule)));
        synchronized (cache) {
            assertEquals(2, cache.size());
        }
    }

    @Test
    public void literalRulesDoNotPopulateRegexCache() throws Exception {
        Map<String, Pattern> cache = compiledRegexCache();
        synchronized (cache) {
            cache.clear();
        }

        ReplacementRuleRecord rule = new ReplacementRuleRecord();
        rule.id = 2L;
        rule.pattern = "foo";
        rule.replacement = "bar";
        rule.active = true;
        rule.regex = false;

        assertEquals("bar baz", ReplacementEngine.apply("foo baz", Collections.singletonList(rule)));
        synchronized (cache) {
            assertEquals(0, cache.size());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Pattern> compiledRegexCache() throws Exception {
        Field field = ReplacementEngine.class.getDeclaredField("COMPILED_REGEX_CACHE");
        field.setAccessible(true);
        return (Map<String, Pattern>) field.get(null);
    }

    private static ReplacementRuleRecord regexRule(long id, String pattern, String replacement, long updatedAt) {
        ReplacementRuleRecord rule = new ReplacementRuleRecord();
        rule.id = id;
        rule.pattern = pattern;
        rule.replacement = replacement;
        rule.active = true;
        rule.regex = true;
        rule.updatedAt = updatedAt;
        return rule;
    }
}
