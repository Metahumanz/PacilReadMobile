package com.metahumanz.pacilread.reader;

import android.util.Log;
import com.metahumanz.pacilread.model.ReplacementRuleRecord;

import java.util.List;
import java.util.regex.Pattern;

public final class ReplacementEngine {
    private static final String TAG = "ReplacementEngine";
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
                    result = Pattern.compile(rule.pattern).matcher(result).replaceAll(replacement);
                } else {
                    result = result.replace(rule.pattern, replacement);
                }
            } catch (Exception e) {
                Log.w(TAG, "规则执行失败: pattern=" + rule.pattern + ", error=" + e.getMessage());
            }
        }
        return result;
    }
}
