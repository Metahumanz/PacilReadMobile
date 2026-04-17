package com.metahumanz.pacilread.util;

import android.os.Build;
import android.text.Html;
import android.text.Spanned;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

public final class HtmlUtils {
    private HtmlUtils() {
    }

    public static String toParagraphHtml(String text) {
        String normalized = normalizeText(text);
        String[] rawLines = normalized.split("\n");
        List<String> paragraphs = new ArrayList<>();
        StringBuilder block = new StringBuilder();
        for (String rawLine : rawLines) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                if (block.length() > 0) {
                    paragraphs.add(block.toString().trim());
                    block.setLength(0);
                }
                continue;
            }
            if (block.length() > 0) {
                block.append('\n');
            }
            block.append(line);
        }
        if (block.length() > 0) {
            paragraphs.add(block.toString().trim());
        }
        if (paragraphs.isEmpty() && !normalized.trim().isEmpty()) {
            paragraphs.add(normalized.trim());
        }

        StringBuilder html = new StringBuilder();
        for (String paragraph : paragraphs) {
            if (paragraph.isEmpty()) {
                continue;
            }
            html.append("<p>")
                    .append(TextUtils.htmlEncode(paragraph).replace("\n", "<br/>"))
                    .append("</p>");
        }
        return html.toString();
    }

    public static String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    public static String stripHtml(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }
        Spanned spanned;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            spanned = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY);
        } else {
            spanned = Html.fromHtml(html);
        }
        return spanned.toString().replace('\u00A0', ' ').trim();
    }

    public static String extractBodyFragment(String html) {
        if (html == null) {
            return "";
        }
        String lower = html.toLowerCase();
        int bodyStart = lower.indexOf("<body");
        if (bodyStart >= 0) {
            int bodyTagEnd = lower.indexOf('>', bodyStart);
            int bodyEnd = lower.lastIndexOf("</body>");
            if (bodyTagEnd >= 0 && bodyEnd > bodyTagEnd) {
                return html.substring(bodyTagEnd + 1, bodyEnd).trim();
            }
        }
        return html.trim();
    }

    public static String firstMeaningfulHeading(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }
        String[] tags = new String[]{"h1", "h2", "h3", "title"};
        String lower = html.toLowerCase();
        for (String tag : tags) {
            String open = "<" + tag;
            String close = "</" + tag + ">";
            int start = lower.indexOf(open);
            while (start >= 0) {
                int openEnd = lower.indexOf('>', start);
                int end = lower.indexOf(close, openEnd + 1);
                if (openEnd >= 0 && end > openEnd) {
                    String text = stripHtml(html.substring(openEnd + 1, end)).trim();
                    if (!text.isEmpty()) {
                        return text;
                    }
                }
                start = lower.indexOf(open, start + 1);
            }
        }
        return "";
    }

    public static String pruneUnsupportedMarkup(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }
        String cleaned = html.replaceAll("(?is)<script[^>]*>.*?</script>", "");
        cleaned = cleaned.replaceAll("(?is)<style[^>]*>.*?</style>", "");
        cleaned = cleaned.replaceAll("(?is)<img[^>]*>", "");
        cleaned = cleaned.replaceAll("(?is)<svg[^>]*>.*?</svg>", "");
        cleaned = cleaned.replaceAll("(?is)<audio[^>]*>.*?</audio>", "");
        cleaned = cleaned.replaceAll("(?is)<video[^>]*>.*?</video>", "");
        return cleaned.trim();
    }
}
