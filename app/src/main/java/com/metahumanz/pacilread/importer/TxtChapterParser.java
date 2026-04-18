package com.metahumanz.pacilread.importer;

import com.metahumanz.pacilread.model.ImportedBook;
import com.metahumanz.pacilread.util.HtmlUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TxtChapterParser {
    private static final Pattern[] CHAPTER_PATTERNS = new Pattern[]{
            Pattern.compile("(?m)^[ \\t\\u3000]{0,6}(?:序章|前言|楔子|引子|尾声|后记|番外|终章|第[0-9一二三四五六七八九十百千万零两〇]+[章节卷回部篇集节])(?:[ \\t:：、,.，。—\\-]{0,6}.{0,30})?$"),
            Pattern.compile("(?m)^[ \\t\\u3000]{0,4}\\d{1,5}[、.:：]\\s*.{1,30}$"),
            Pattern.compile("(?m)^[ \\t\\u3000]{0,6}(?:Chapter|CHAPTER)\\s+[0-9IVXLC]+(?:\\s+.{0,24})?$")
    };

    private TxtChapterParser() {
    }

    public static List<ImportedBook.ChapterSeed> parse(InputStream inputStream) throws IOException {
        byte[] raw = readAll(inputStream);
        String content = HtmlUtils.normalizeText(decode(raw)).trim();
        return split(content);
    }

    public static List<ImportedBook.ChapterSeed> split(String content) {
        List<HeadingMatch> matches = findBestHeadings(content);
        List<ImportedBook.ChapterSeed> result = new ArrayList<>();
        if (matches.isEmpty()) {
            result.add(new ImportedBook.ChapterSeed("全文", HtmlUtils.toParagraphHtml(content), content, 0));
            return result;
        }

        int chapterIndex = 0;
        if (matches.get(0).start > 0) {
            String intro = content.substring(0, matches.get(0).start).trim();
            if (!intro.isEmpty()) {
                result.add(new ImportedBook.ChapterSeed("前言", HtmlUtils.toParagraphHtml(intro), intro, chapterIndex++));
            }
        }

        for (int i = 0; i < matches.size(); i++) {
            HeadingMatch current = matches.get(i);
            int nextStart = i + 1 < matches.size() ? matches.get(i + 1).start : content.length();
            String body = content.substring(current.end, nextStart).trim();
            result.add(new ImportedBook.ChapterSeed(
                    current.title.isEmpty() ? "第 " + (chapterIndex + 1) + " 章" : current.title,
                    HtmlUtils.toParagraphHtml(body),
                    body,
                    chapterIndex
            ));
            chapterIndex++;
        }

        if (result.isEmpty()) {
            result.add(new ImportedBook.ChapterSeed("全文", HtmlUtils.toParagraphHtml(content), content, 0));
        }
        return result;
    }

    private static List<HeadingMatch> findBestHeadings(String content) {
        List<HeadingMatch> best = new ArrayList<>();
        for (Pattern pattern : CHAPTER_PATTERNS) {
            Matcher matcher = pattern.matcher(content);
            List<HeadingMatch> current = new ArrayList<>();
            while (matcher.find()) {
                current.add(new HeadingMatch(matcher.start(), matcher.end(), matcher.group().trim()));
            }
            if (current.size() > best.size()) {
                best = current;
            }
        }
        return best;
    }

    private static byte[] readAll(InputStream inputStream) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, read);
        }
        return outputStream.toByteArray();
    }

    private static String decode(byte[] bytes) {
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        try {
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
            decoder.onMalformedInput(CodingErrorAction.REPORT);
            decoder.onUnmappableCharacter(CodingErrorAction.REPORT);
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException ignore) {
            return new String(bytes, Charset.forName("GB18030"));
        }
    }

    private static final class HeadingMatch {
        final int start;
        final int end;
        final String title;

        HeadingMatch(int start, int end, String title) {
            this.start = start;
            this.end = end;
            this.title = title;
        }
    }
}
