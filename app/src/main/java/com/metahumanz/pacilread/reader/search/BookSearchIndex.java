package com.metahumanz.pacilread.reader.search;

import android.content.Context;

import com.metahumanz.pacilread.model.ChapterRecord;
import com.metahumanz.pacilread.model.ReplacementRuleRecord;
import com.metahumanz.pacilread.reader.ReplacementEngine;
import com.metahumanz.pacilread.storage.JsonDatabase;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;

public final class BookSearchIndex {
    private static final int MAGIC = 0x50525349;
    private static final int VERSION = 1;
    private static final int HASH_PROBES = 4;

    public interface CancellationToken {
        boolean isCancelled();
    }

    public static final class Result {
        public final int chapterIndex;
        public final String chapterTitle;
        public final String snippet;
        public final int charOffset;

        public Result(int chapterIndex, String chapterTitle, String snippet, int charOffset) {
            this.chapterIndex = chapterIndex;
            this.chapterTitle = chapterTitle;
            this.snippet = snippet;
            this.charOffset = charOffset;
        }
    }

    private final JsonDatabase database;
    private final File indexDirectory;

    public BookSearchIndex(Context context, JsonDatabase database) {
        this.database = database;
        this.indexDirectory = new File(context.getApplicationContext().getFilesDir(), "search_index");
    }

    public boolean isReady(long bookId) {
        try {
            List<ChapterRecord> chapters = database.getChapters(bookId, false);
            List<ReplacementRuleRecord> rules = database.getReplacementRules(bookId);
            File file = indexFile(bookId);
            if (!file.isFile()) return false;
            try (DataInputStream input = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
                return input.readInt() == MAGIC
                        && input.readInt() == VERSION
                        && fingerprint(chapters, rules).equals(input.readUTF());
            }
        } catch (Exception ignored) {
            return false;
        }
    }

    public void build(long bookId, CancellationToken cancellationToken) throws Exception {
        List<ChapterRecord> chapters = database.getChapters(bookId, false);
        List<ReplacementRuleRecord> rules = database.getReplacementRules(bookId);
        buildInternal(bookId, chapters, rules, cancellationToken);
    }

    public List<Result> search(long bookId, String query, CancellationToken cancellationToken) throws Exception {
        String normalizedQuery = SearchTextMatcher.normalize(query == null ? "" : query.trim());
        if (normalizedQuery.isEmpty()) return new ArrayList<>();
        List<ChapterRecord> chapters = database.getChapters(bookId, false);
        List<ReplacementRuleRecord> rules = database.getReplacementRules(bookId);
        String expectedFingerprint = fingerprint(chapters, rules);
        LoadedIndex index = readIndex(bookId, expectedFingerprint);
        if (index == null) {
            buildInternal(bookId, chapters, rules, cancellationToken);
            if (isCancelled(cancellationToken)) return new ArrayList<>();
            index = readIndex(bookId, expectedFingerprint);
        }
        List<Result> results = new ArrayList<>();
        if (index == null) return results;
        for (int chapterIndex = 0; chapterIndex < chapters.size(); chapterIndex++) {
            if (isCancelled(cancellationToken)) return new ArrayList<>();
            ChapterBloom bloom = index.chapterBlooms.get(chapterIndex);
            if (!bloom.mightContain(normalizedQuery)) continue;
            ChapterRecord chapter = chapters.get(chapterIndex);
            String processed = ReplacementEngine.apply(resolveText(chapter), rules);
            for (int match : findAll(processed, normalizedQuery, cancellationToken)) {
                if (isCancelled(cancellationToken)) return new ArrayList<>();
                int start = Math.max(0, match - 24);
                int end = Math.min(processed.length(), match + normalizedQuery.length() + 32);
                String snippet = processed.substring(start, end).replace('\n', ' ').trim();
                results.add(new Result(chapterIndex, chapter.title, snippet, match));
            }
        }
        return results;
    }

    public void invalidate(long bookId) {
        indexFile(bookId).delete();
    }

    public static void delete(Context context, long bookId) {
        new File(new File(context.getApplicationContext().getFilesDir(), "search_index"),
                "book_" + bookId + ".idx").delete();
    }

    private void buildInternal(long bookId, List<ChapterRecord> chapters,
                               List<ReplacementRuleRecord> rules,
                               CancellationToken cancellationToken) throws Exception {
        if (!indexDirectory.exists() && !indexDirectory.mkdirs()) {
            throw new IllegalStateException("无法创建搜索索引目录");
        }
        File target = indexFile(bookId);
        File temp = new File(indexDirectory, target.getName() + ".tmp");
        try (FileOutputStream fileOutput = new FileOutputStream(temp);
             DataOutputStream output = new DataOutputStream(new BufferedOutputStream(fileOutput))) {
            output.writeInt(MAGIC);
            output.writeInt(VERSION);
            output.writeUTF(fingerprint(chapters, rules));
            output.writeInt(chapters.size());
            for (ChapterRecord chapter : chapters) {
                if (isCancelled(cancellationToken)) {
                    temp.delete();
                    return;
                }
                String processed = SearchTextMatcher.normalize(ReplacementEngine.apply(resolveText(chapter), rules));
                ChapterBloom bloom = ChapterBloom.build(processed, cancellationToken);
                if (bloom == null) {
                    temp.delete();
                    return;
                }
                output.writeInt(bloom.bitSize);
                byte[] bytes = bloom.bits.toByteArray();
                output.writeInt(bytes.length);
                output.write(bytes);
            }
            output.flush();
            fileOutput.getFD().sync();
        }
        if (target.exists() && !target.delete()) {
            temp.delete();
            throw new IllegalStateException("无法替换搜索索引");
        }
        if (!temp.renameTo(target)) {
            temp.delete();
            throw new IllegalStateException("无法保存搜索索引");
        }
    }

    private LoadedIndex readIndex(long bookId, String expectedFingerprint) {
        File file = indexFile(bookId);
        if (!file.isFile()) return null;
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            if (input.readInt() != MAGIC || input.readInt() != VERSION) return null;
            if (!expectedFingerprint.equals(input.readUTF())) return null;
            int count = input.readInt();
            if (count != database.getChapters(bookId, false).size()) return null;
            List<ChapterBloom> blooms = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int bitSize = input.readInt();
                int byteCount = input.readInt();
                if (bitSize <= 0 || byteCount < 0 || byteCount > 8 * 1024 * 1024) return null;
                byte[] bytes = new byte[byteCount];
                input.readFully(bytes);
                blooms.add(new ChapterBloom(bitSize, BitSet.valueOf(bytes)));
            }
            return new LoadedIndex(blooms);
        } catch (Exception ignored) {
            file.delete();
            return null;
        }
    }

    private String resolveText(ChapterRecord chapter) {
        return database.resolveChapterText(
                chapter.bookId, chapter.id, chapter.bodyText, chapter.bodyTextPath, chapter.bodyTextStorage);
    }

    private String fingerprint(List<ChapterRecord> chapters, List<ReplacementRuleRecord> rules) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (ChapterRecord chapter : chapters) {
            updateDigest(digest, chapter.id + "|" + chapter.orderIndex + "|" + chapter.bodyTextSize + "|"
                    + safe(chapter.bodyTextPath) + "|" + safe(chapter.bodyTextStorage));
            File bodyFile = database.resolveChapterTextFile(chapter.bodyTextPath);
            if (bodyFile != null) updateDigest(digest, "|" + bodyFile.length() + "|" + bodyFile.lastModified());
        }
        for (ReplacementRuleRecord rule : rules) {
            updateDigest(digest, rule.id + "|" + rule.updatedAt + "|" + rule.active + "|" + rule.regex
                    + "|" + safe(rule.pattern) + "|" + safe(rule.replacement));
        }
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }

    private void updateDigest(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private File indexFile(long bookId) {
        return new File(indexDirectory, "book_" + bookId + ".idx");
    }

    private static boolean isCancelled(CancellationToken token) {
        return token != null && token.isCancelled();
    }

    private static List<Integer> findAll(
            String text,
            String normalizedQuery,
            CancellationToken cancellationToken
    ) {
        List<Integer> offsets = new ArrayList<>();
        String normalizedText = SearchTextMatcher.normalize(text);
        if (normalizedQuery.isEmpty()) return offsets;
        int from = 0;
        int checks = 0;
        while (from <= normalizedText.length() - normalizedQuery.length()) {
            if ((checks++ & 0xff) == 0 && isCancelled(cancellationToken)) {
                return new ArrayList<>();
            }
            int match = normalizedText.indexOf(normalizedQuery, from);
            if (match < 0) break;
            offsets.add(match);
            from = match + 1;
        }
        return offsets;
    }

    private static final class LoadedIndex {
        final List<ChapterBloom> chapterBlooms;

        LoadedIndex(List<ChapterBloom> chapterBlooms) {
            this.chapterBlooms = chapterBlooms;
        }
    }

    private static final class ChapterBloom {
        final int bitSize;
        final BitSet bits;

        ChapterBloom(int bitSize, BitSet bits) {
            this.bitSize = bitSize;
            this.bits = bits;
        }

        static ChapterBloom build(String text, CancellationToken cancellationToken) {
            int desiredBits = Math.max(1024, Math.min(8 * 1024 * 1024, text.length() * 12));
            int bitSize = ((desiredBits + 63) / 64) * 64;
            BitSet bits = new BitSet(bitSize);
            for (int length = 1; length <= 3; length++) {
                for (int start = 0; start + length <= text.length(); start++) {
                    if ((start & 0x3ff) == 0 && isCancelled(cancellationToken)) return null;
                    add(bits, bitSize, text, start, length);
                }
            }
            return new ChapterBloom(bitSize, bits);
        }

        boolean mightContain(String query) {
            int length = Math.min(3, query.length());
            for (int start = 0; start + length <= query.length(); start++) {
                if (!contains(bits, bitSize, query, start, length)) return false;
            }
            return true;
        }

        private static void add(BitSet bits, int bitSize, String value, int start, int length) {
            long[] hashes = hashes(value, start, length);
            for (int i = 0; i < HASH_PROBES; i++) {
                bits.set(index(hashes[0] + i * hashes[1], bitSize));
            }
        }

        private static boolean contains(BitSet bits, int bitSize, String value, int start, int length) {
            long[] hashes = hashes(value, start, length);
            for (int i = 0; i < HASH_PROBES; i++) {
                if (!bits.get(index(hashes[0] + i * hashes[1], bitSize))) return false;
            }
            return true;
        }

        private static long[] hashes(String value, int start, int length) {
            long first = 0xcbf29ce484222325L;
            long second = 0x9e3779b97f4a7c15L;
            for (int i = start; i < start + length; i++) {
                char c = value.charAt(i);
                first = (first ^ c) * 0x100000001b3L;
                second ^= c + 0x9e3779b9L + (second << 6) + (second >>> 2);
            }
            if ((second & 1L) == 0L) second++;
            return new long[]{first, second};
        }

        private static int index(long hash, int bitSize) {
            return (int) ((hash & Long.MAX_VALUE) % bitSize);
        }
    }
}
