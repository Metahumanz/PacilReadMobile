package com.metahumanz.pacilread.reader;

import android.util.Log;
import com.metahumanz.pacilread.model.ReplacementRuleRecord;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class ReplacementEngine {
    private static final String TAG = "ReplacementEngine";
    private static final int MAX_COMPILED_REGEX_CACHE_SIZE = 128;
    private static final Map<String, Pattern> COMPILED_REGEX_CACHE =
            new LinkedHashMap<String, Pattern>(MAX_COMPILED_REGEX_CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Pattern> eldest) {
                    return size() > MAX_COMPILED_REGEX_CACHE_SIZE;
                }
            };

    private ReplacementEngine() {
    }

    public static String apply(String source, List<ReplacementRuleRecord> rules) {
        String result = source == null ? "" : source;
        if (rules == null || rules.isEmpty()) {
            return result;
        }
        for (ReplacementRuleRecord rule : rules) {
            if (rule == null || !rule.active || rule.pattern == null || rule.pattern.isBlank()) {
                continue;
            }
            String replacement = rule.replacement == null ? "" : rule.replacement;
            try {
                if (rule.regex) {
                    Pattern compiled = compiledPatternFor(rule);
                    if (compiled == null) {
                        continue;
                    }
                    result = compiled.matcher(result).replaceAll(replacement);
                } else {
                    result = result.replace(rule.pattern, replacement);
                }
            } catch (Exception e) {
                Log.w(TAG, "规则执行失败: pattern=" + rule.pattern + ", error=" + e.getMessage());
            }
        }
        return result;
    }

    private static Pattern compiledPatternFor(ReplacementRuleRecord rule) {
        String key = regexCacheKey(rule);
        synchronized (COMPILED_REGEX_CACHE) {
            Pattern cached = COMPILED_REGEX_CACHE.get(key);
            if (cached != null) {
                return cached;
            }
            try {
                Pattern compiled = Pattern.compile(rule.pattern);
                COMPILED_REGEX_CACHE.put(key, compiled);
                return compiled;
            } catch (Exception error) {
                Log.w(TAG, "正则规则编译失败: pattern=" + rule.pattern + ", error=" + error.getMessage());
                return null;
            }
        }
    }

    private static String regexCacheKey(ReplacementRuleRecord rule) {
        return rule.id + "|" + rule.updatedAt + "|" + rule.pattern;
    }
}
