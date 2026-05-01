package com.metahumanz.pacilread.importer;

import android.content.Context;
import android.util.Log;

import com.metahumanz.pacilread.model.ImportedBook;
import com.metahumanz.pacilread.util.CoverImageStore;
import com.metahumanz.pacilread.util.HtmlUtils;

import java.io.File;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class EpubChapterParser {
    private static final String TAG = "EpubChapterParser";
    private static final int XML_PROBE_BYTES = 4096;

    private EpubChapterParser() {
    }

    public static List<ImportedBook.ChapterSeed> parse(File epubFile) throws Exception {
        try (ZipFile zipFile = new ZipFile(epubFile, Charset.forName("UTF-8"))) {
            String opfPath = resolvePackageDocumentPath(zipFile);
            OpfPackage opf = readOpfPackage(zipFile, opfPath);
            String opfDir = parentPath(opfPath);

            Map<String, String> titles = new LinkedHashMap<>();
            try {
                titles.putAll(parseNcxTitles(zipFile, opfDir, opf.manifest));
            } catch (Exception error) {
                Log.w(TAG, "解析 NCX 目录失败，继续导入 EPUB 正文", error);
            }
            try {
                titles.putAll(parseNavTitles(zipFile, opfDir, opf.manifest));
            } catch (Exception error) {
                Log.w(TAG, "解析 NAV 目录失败，继续导入 EPUB 正文", error);
            }

            List<ImportedBook.ChapterSeed> chapters = new ArrayList<>();
            int order = 0;
            for (String idRef : opf.spine) {
                ManifestItem item = opf.manifest.get(idRef);
                if (item == null || !isReadableDocumentItem(item)) {
                    continue;
                }
                byte[] rawDocument = readManifestItem(zipFile, opfDir, item);
                if (rawDocument == null || rawDocument.length == 0) {
                    continue;
                }
                String documentText = decodeXmlText(rawDocument);
                String bodyHtml = HtmlUtils.pruneUnsupportedMarkup(HtmlUtils.extractBodyFragment(documentText));
                String plain = HtmlUtils.stripHtml(bodyHtml);
                if (plain.isBlank()) {
                    plain = HtmlUtils.stripHtml(documentText);
                }
                if (plain.isBlank()) {
                    continue;
                }
                String title = findChapterTitle(item.href, titles, documentText, order + 1);
                chapters.add(new ImportedBook.ChapterSeed(title, "", plain, order));
                order++;
            }

            if (chapters.isEmpty()) {
                throw new IOException("EPUB 中没有可读取的章节内容");
            }
            return chapters;
        }
    }

    public static File extractCover(Context context, File epubFile, String prefix) {
        try (ZipFile zipFile = new ZipFile(epubFile, Charset.forName("UTF-8"))) {
            String opfPath = resolvePackageDocumentPath(zipFile);
            OpfPackage opf = readOpfPackage(zipFile, opfPath);
            String opfDir = parentPath(opfPath);
            for (ManifestItem item : coverCandidates(opf)) {
                try {
                    byte[] imageBytes = readManifestItem(zipFile, opfDir, item);
                    if (imageBytes == null || imageBytes.length == 0) {
                        continue;
                    }
                    return CoverImageStore.saveCompressedCover(context, imageBytes, prefix);
                } catch (IOException | RuntimeException | OutOfMemoryError error) {
                    Log.w(TAG, "EPUB 封面候选读取失败，继续尝试下一个", error);
                }
            }
        } catch (Exception error) {
            Log.w(TAG, "EPUB 封面提取失败，跳过封面", error);
        }
        return null;
    }

    private static String resolvePackageDocumentPath(ZipFile zipFile) throws IOException {
        byte[] containerXml = readRequiredEntry(
                zipFile,
                "META-INF/container.xml",
                "EPUB 缺少 META-INF/container.xml"
        );
        String containerText = decodeXmlText(containerXml);
        for (String tag : startTagsByLocalName(containerText, "rootfile")) {
            String fullPath = attributeValue(tag, "full-path");
            if (!fullPath.isBlank()) {
                return normalizeEntryPath(fullPath.trim());
            }
        }
        throw new IOException("EPUB container.xml 未声明 package 文档路径");
    }

    private static OpfPackage readOpfPackage(ZipFile zipFile, String opfPath) throws IOException {
        byte[] opfBytes = readRequiredEntry(
                zipFile,
                opfPath,
                "EPUB package 文档读取失败: " + opfPath
        );
        return parseOpfPackage(decodeXmlText(opfBytes), opfPath);
    }

    private static OpfPackage parseOpfPackage(String opfText, String opfPath) throws IOException {
        Map<String, ManifestItem> manifest = new LinkedHashMap<>();
        for (String tag : startTagsByLocalName(opfText, "item")) {
            String id = attributeValue(tag, "id");
            String href = attributeValue(tag, "href");
            if (id.isBlank() || href.isBlank()) {
                continue;
            }
            manifest.put(id, new ManifestItem(
                    id,
                    href,
                    attributeValue(tag, "media-type"),
                    attributeValue(tag, "properties")
            ));
        }
        if (manifest.isEmpty()) {
            throw new IOException("EPUB OPF manifest 为空: " + opfPath);
        }

        List<String> spine = new ArrayList<>();
        for (String tag : startTagsByLocalName(opfText, "itemref")) {
            String idRef = attributeValue(tag, "idref");
            if (!idRef.isBlank()) {
                spine.add(idRef);
            }
        }
        if (spine.isEmpty()) {
            for (ManifestItem item : manifest.values()) {
                if (isReadableDocumentItem(item)) {
                    spine.add(item.id);
                }
            }
        }

        String coverId = "";
        for (String tag : startTagsByLocalName(opfText, "meta")) {
            if ("cover".equalsIgnoreCase(attributeValue(tag, "name"))) {
                coverId = attributeValue(tag, "content");
                break;
            }
        }
        return new OpfPackage(manifest, spine, coverId);
    }

    private static Map<String, String> parseNcxTitles(ZipFile zipFile, String opfDir,
                                                      Map<String, ManifestItem> manifest) throws IOException {
        Map<String, String> toc = new HashMap<>();
        for (ManifestItem item : manifest.values()) {
            if (!isNcxItem(item)) {
                continue;
            }
            byte[] bytes = readManifestItem(zipFile, opfDir, item);
            if (bytes == null || bytes.length == 0) {
                continue;
            }
            String ncx = decodeXmlText(bytes);
            for (String block : elementBlocksByLocalName(ncx, "navPoint")) {
                String label = elementTextByLocalName(block, "text");
                String contentTag = firstStartTagByLocalName(block, "content");
                String src = attributeValue(contentTag, "src");
                if (!label.isBlank() && !src.isBlank()) {
                    toc.put(normalizeLookupKey(src), label);
                }
            }
            break;
        }
        return toc;
    }

    private static Map<String, String> parseNavTitles(ZipFile zipFile, String opfDir,
                                                      Map<String, ManifestItem> manifest) throws IOException {
        Map<String, String> toc = new HashMap<>();
        for (ManifestItem item : manifest.values()) {
            if (item.properties == null || !item.properties.toLowerCase(Locale.ROOT).contains("nav")) {
                continue;
            }
            byte[] bytes = readManifestItem(zipFile, opfDir, item);
            if (bytes == null || bytes.length == 0) {
                continue;
            }
            String html = decodeXmlText(bytes);
            int searchFrom = 0;
            while (true) {
                TagMatch anchor = nextStartTagByLocalName(html, "a", searchFrom);
                if (anchor == null) {
                    return toc;
                }
                int anchorEnd = findEndTagByLocalName(html, "a", anchor.end + 1);
                if (anchorEnd < 0) {
                    return toc;
                }
                String href = attributeValue(anchor.tag, "href");
                String text = HtmlUtils.stripHtml(html.substring(anchor.end + 1, anchorEnd));
                if (!href.isBlank() && !text.isBlank()) {
                    toc.put(normalizeLookupKey(href), text);
                }
                int closeEnd = html.indexOf('>', anchorEnd);
                searchFrom = closeEnd >= 0 ? closeEnd + 1 : anchorEnd + 1;
            }
        }
        return toc;
    }

    private static List<ManifestItem> coverCandidates(OpfPackage opf) {
        List<ManifestItem> candidates = new ArrayList<>();
        if (!opf.coverId.isBlank()) {
            addCoverCandidate(candidates, opf.manifest.get(opf.coverId));
        }
        for (ManifestItem item : opf.manifest.values()) {
            if (item.properties != null && item.properties.toLowerCase(Locale.ROOT).contains("cover-image")) {
                addCoverCandidate(candidates, item);
            }
        }
        for (ManifestItem item : opf.manifest.values()) {
            if (!isImageItem(item)) {
                continue;
            }
            String href = item.href == null ? "" : item.href.toLowerCase(Locale.ROOT);
            if (href.contains("cover") || href.contains("front") || href.contains("title")) {
                addCoverCandidate(candidates, item);
            }
        }
        for (ManifestItem item : opf.manifest.values()) {
            if (isImageItem(item)) {
                addCoverCandidate(candidates, item);
            }
        }
        return candidates;
    }

    private static void addCoverCandidate(List<ManifestItem> candidates, ManifestItem item) {
        if (!isImageItem(item)) {
            return;
        }
        for (ManifestItem existing : candidates) {
            if ((!existing.id.isBlank() && existing.id.equals(item.id))
                    || (!existing.href.isBlank() && existing.href.equals(item.href))) {
                return;
            }
        }
        candidates.add(item);
    }

    private static boolean isNcxItem(ManifestItem item) {
        String mediaType = item.mediaType == null ? "" : item.mediaType.toLowerCase(Locale.ROOT);
        String href = item.href == null ? "" : item.href.toLowerCase(Locale.ROOT);
        return "application/x-dtbncx+xml".equals(mediaType) || href.endsWith(".ncx");
    }

    private static boolean isReadableDocumentItem(ManifestItem item) {
        if (item == null || item.href == null || item.href.isBlank()) {
            return false;
        }
        String mediaType = item.mediaType == null ? "" : item.mediaType.toLowerCase(Locale.ROOT);
        String href = item.href.toLowerCase(Locale.ROOT);
        return mediaType.contains("xhtml")
                || mediaType.contains("html")
                || "application/xml".equals(mediaType)
                || href.endsWith(".xhtml")
                || href.endsWith(".html")
                || href.endsWith(".htm")
                || href.endsWith(".xml");
    }

    private static boolean isImageItem(ManifestItem item) {
        if (item == null || item.href == null || item.href.isBlank()) {
            return false;
        }
        String mediaType = item.mediaType == null ? "" : item.mediaType.toLowerCase(Locale.ROOT);
        String href = item.href.toLowerCase(Locale.ROOT);
        boolean imageMediaType = mediaType.startsWith("image/") && !mediaType.contains("svg");
        boolean imageExtension = href.endsWith(".jpg")
                || href.endsWith(".jpeg")
                || href.endsWith(".png")
                || href.endsWith(".webp");
        return (imageMediaType || imageExtension) && !href.endsWith(".svg");
    }

    private static String findChapterTitle(String href, Map<String, String> titles, String html, int index) {
        String title = titles.get(normalizeLookupKey(href));
        if (title == null) {
            title = titles.get(normalizeLookupKey(urlDecodedPath(href)));
        }
        if (title == null || title.isBlank()) {
            title = HtmlUtils.firstMeaningfulHeading(html);
        }
        if (title == null || title.isBlank()) {
            title = "第 " + index + " 章";
        }
        return title.trim();
    }

    private static List<String> startTagsByLocalName(String text, String localName) {
        List<String> tags = new ArrayList<>();
        int searchFrom = 0;
        while (true) {
            TagMatch match = nextStartTagByLocalName(text, localName, searchFrom);
            if (match == null) {
                return tags;
            }
            tags.add(match.tag);
            searchFrom = match.end + 1;
        }
    }

    private static String firstStartTagByLocalName(String text, String localName) {
        TagMatch match = nextStartTagByLocalName(text, localName, 0);
        return match == null ? "" : match.tag;
    }

    private static TagMatch nextStartTagByLocalName(String text, String localName, int fromIndex) {
        if (text == null || localName == null) {
            return null;
        }
        String wanted = localName.toLowerCase(Locale.ROOT);
        int searchFrom = Math.max(0, fromIndex);
        while (searchFrom < text.length()) {
            int start = text.indexOf('<', searchFrom);
            if (start < 0 || start + 1 >= text.length()) {
                return null;
            }
            char next = text.charAt(start + 1);
            if (next == '/' || next == '?' || next == '!') {
                searchFrom = start + 2;
                continue;
            }
            int nameStart = start + 1;
            int nameEnd = nameStart;
            while (nameEnd < text.length() && isXmlNameChar(text.charAt(nameEnd))) {
                nameEnd++;
            }
            if (nameEnd == nameStart) {
                searchFrom = start + 1;
                continue;
            }
            String name = localNameOf(text.substring(nameStart, nameEnd)).toLowerCase(Locale.ROOT);
            int tagEnd = findTagEnd(text, nameEnd);
            if (tagEnd < 0) {
                return null;
            }
            if (wanted.equals(name)) {
                return new TagMatch(start, tagEnd, text.substring(start, tagEnd + 1));
            }
            searchFrom = tagEnd + 1;
        }
        return null;
    }

    private static List<String> elementBlocksByLocalName(String text, String localName) {
        List<String> blocks = new ArrayList<>();
        int searchFrom = 0;
        while (true) {
            TagMatch start = nextStartTagByLocalName(text, localName, searchFrom);
            if (start == null) {
                return blocks;
            }
            int endStart = findEndTagByLocalName(text, localName, start.end + 1);
            if (endStart < 0) {
                return blocks;
            }
            int end = text.indexOf('>', endStart);
            if (end < 0) {
                return blocks;
            }
            blocks.add(text.substring(start.start, end + 1));
            searchFrom = end + 1;
        }
    }

    private static String elementTextByLocalName(String text, String localName) {
        TagMatch start = nextStartTagByLocalName(text, localName, 0);
        if (start == null) {
            return "";
        }
        int endStart = findEndTagByLocalName(text, localName, start.end + 1);
        if (endStart < 0) {
            return "";
        }
        return decodeXmlEntities(HtmlUtils.stripHtml(text.substring(start.end + 1, endStart))).trim();
    }

    private static int findEndTagByLocalName(String text, String localName, int fromIndex) {
        String wanted = localName.toLowerCase(Locale.ROOT);
        int searchFrom = Math.max(0, fromIndex);
        while (searchFrom < text.length()) {
            int start = text.indexOf("</", searchFrom);
            if (start < 0) {
                return -1;
            }
            int nameStart = start + 2;
            int nameEnd = nameStart;
            while (nameEnd < text.length() && isXmlNameChar(text.charAt(nameEnd))) {
                nameEnd++;
            }
            if (nameEnd > nameStart) {
                String name = localNameOf(text.substring(nameStart, nameEnd)).toLowerCase(Locale.ROOT);
                if (wanted.equals(name)) {
                    return start;
                }
            }
            searchFrom = nameEnd + 1;
        }
        return -1;
    }

    private static int findTagEnd(String text, int fromIndex) {
        char quote = 0;
        for (int i = fromIndex; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '>') {
                return i;
            }
        }
        return -1;
    }

    private static String attributeValue(String tag, String name) {
        if (tag == null || tag.isEmpty() || name == null || name.isEmpty()) {
            return "";
        }
        String wanted = name.toLowerCase(Locale.ROOT);
        int pos = 0;
        while (pos < tag.length() && tag.charAt(pos) != '<') {
            pos++;
        }
        pos++;
        while (pos < tag.length() && isXmlNameChar(tag.charAt(pos))) {
            pos++;
        }
        while (pos < tag.length()) {
            while (pos < tag.length() && Character.isWhitespace(tag.charAt(pos))) {
                pos++;
            }
            if (pos >= tag.length() || tag.charAt(pos) == '/' || tag.charAt(pos) == '>') {
                return "";
            }
            int nameStart = pos;
            while (pos < tag.length() && isXmlNameChar(tag.charAt(pos))) {
                pos++;
            }
            if (pos == nameStart) {
                pos++;
                continue;
            }
            String attrName = localNameOf(tag.substring(nameStart, pos)).toLowerCase(Locale.ROOT);
            while (pos < tag.length() && Character.isWhitespace(tag.charAt(pos))) {
                pos++;
            }
            if (pos >= tag.length() || tag.charAt(pos) != '=') {
                continue;
            }
            pos++;
            while (pos < tag.length() && Character.isWhitespace(tag.charAt(pos))) {
                pos++;
            }
            if (pos >= tag.length()) {
                return "";
            }
            char quote = tag.charAt(pos);
            String value;
            if (quote == '"' || quote == '\'') {
                int valueStart = pos + 1;
                int valueEnd = tag.indexOf(quote, valueStart);
                if (valueEnd < 0) {
                    return "";
                }
                value = tag.substring(valueStart, valueEnd);
                pos = valueEnd + 1;
            } else {
                int valueStart = pos;
                while (pos < tag.length()
                        && !Character.isWhitespace(tag.charAt(pos))
                        && tag.charAt(pos) != '/'
                        && tag.charAt(pos) != '>') {
                    pos++;
                }
                value = tag.substring(valueStart, pos);
            }
            if (wanted.equals(attrName)) {
                return decodeXmlEntities(value).trim();
            }
        }
        return "";
    }

    private static String decodeXmlText(byte[] rawDocument) {
        if (rawDocument == null || rawDocument.length == 0) {
            return "";
        }
        DecodedCharset detected = detectCharset(rawDocument);
        return new String(rawDocument, detected.offset, rawDocument.length - detected.offset, detected.charset);
    }

    private static DecodedCharset detectCharset(byte[] bytes) {
        if (startsWith(bytes, 0xEF, 0xBB, 0xBF)) {
            return new DecodedCharset(StandardCharsets.UTF_8, 3);
        }
        if (startsWith(bytes, 0xFF, 0xFE)) {
            return new DecodedCharset(StandardCharsets.UTF_16LE, 2);
        }
        if (startsWith(bytes, 0xFE, 0xFF)) {
            return new DecodedCharset(StandardCharsets.UTF_16BE, 2);
        }
        Charset utf16 = detectUtf16Encoding(bytes);
        if (utf16 != null) {
            return new DecodedCharset(utf16, 0);
        }
        String probe = new String(bytes, 0, Math.min(bytes.length, XML_PROBE_BYTES), StandardCharsets.ISO_8859_1);
        String declaration = xmlDeclarationFromText(probe);
        String encoding = attributeValue(declaration, "encoding");
        return new DecodedCharset(charsetOrDefault(encoding, StandardCharsets.UTF_8), 0);
    }

    private static Charset detectUtf16Encoding(byte[] bytes) {
        int scanLimit = Math.min(bytes.length - 3, 256);
        for (int i = 0; i < scanLimit; i++) {
            if (bytes[i] == 0x00 && bytes[i + 1] == '<' && bytes[i + 2] == 0x00 && bytes[i + 3] == '?') {
                return StandardCharsets.UTF_16BE;
            }
            if (bytes[i] == '<' && bytes[i + 1] == 0x00 && bytes[i + 2] == '?' && bytes[i + 3] == 0x00) {
                return StandardCharsets.UTF_16LE;
            }
        }
        int pairCount = Math.min(bytes.length / 2, 96);
        int evenNulls = 0;
        int oddNulls = 0;
        for (int i = 0; i < pairCount * 2; i += 2) {
            if (bytes[i] == 0x00) {
                evenNulls++;
            }
            if (bytes[i + 1] == 0x00) {
                oddNulls++;
            }
        }
        if (oddNulls >= Math.max(3, pairCount / 3) && oddNulls > evenNulls * 2) {
            return StandardCharsets.UTF_16LE;
        }
        if (evenNulls >= Math.max(3, pairCount / 3) && evenNulls > oddNulls * 2) {
            return StandardCharsets.UTF_16BE;
        }
        return null;
    }

    private static Charset charsetOrDefault(String encoding, Charset fallback) {
        if (encoding == null || encoding.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Charset.forName(encoding.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String xmlDeclarationFromText(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        int start = 0;
        while (start < text.length()
                && (Character.isWhitespace(text.charAt(start)) || text.charAt(start) == '\uFEFF')) {
            start++;
        }
        if (!text.regionMatches(true, start, "<?xml", 0, 5)) {
            return "";
        }
        int end = text.indexOf("?>", start);
        if (end < 0 || end - start > 512) {
            return "";
        }
        return text.substring(start, end + 2);
    }

    private static boolean startsWith(byte[] bytes, int... values) {
        if (bytes == null || bytes.length < values.length) {
            return false;
        }
        for (int i = 0; i < values.length; i++) {
            if ((bytes[i] & 0xFF) != values[i]) {
                return false;
            }
        }
        return true;
    }

    private static String decodeXmlEntities(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value.replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&");
    }

    private static byte[] readEntry(ZipFile zipFile, String path) throws IOException {
        ZipEntry entry = zipFile.getEntry(path);
        if (entry == null) {
            return null;
        }
        try (java.io.InputStream is = zipFile.getInputStream(entry)) {
            return readAllBytesCompat(is);
        }
    }

    private static byte[] readEntryWithFallback(ZipFile zipFile, String path) throws IOException {
        byte[] bytes = readEntry(zipFile, path);
        if (bytes != null) {
            return bytes;
        }
        return readEntry(zipFile, urlDecodedPath(path));
    }

    private static byte[] readRequiredEntry(ZipFile zipFile, String path, String errorMessage) throws IOException {
        byte[] bytes = readEntryWithFallback(zipFile, path);
        if (bytes == null) {
            throw new IOException(errorMessage);
        }
        return bytes;
    }

    private static byte[] readManifestItem(ZipFile zipFile, String opfDir, ManifestItem item) throws IOException {
        if (item == null || item.href == null || item.href.isBlank()) {
            return null;
        }
        String resolvedPath = normalizeEntryPath(resolveAgainst(opfDir, item.href));
        return readEntryWithFallback(zipFile, resolvedPath);
    }

    private static byte[] readAllBytesCompat(java.io.InputStream is) throws IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        int nRead;
        byte[] data = new byte[16384];
        while ((nRead = is.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }
        return buffer.toByteArray();
    }

    private static String parentPath(String path) {
        int index = path.lastIndexOf('/');
        if (index < 0) {
            return "";
        }
        return path.substring(0, index);
    }

    private static String resolveAgainst(String base, String child) {
        if (child == null) {
            return "";
        }
        if (child.startsWith("/")) {
            return child.substring(1);
        }
        if (base == null || base.isEmpty()) {
            return child;
        }
        String combined = base + "/" + child;
        String[] parts = combined.split("/");
        List<String> normalized = new ArrayList<>();
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                if (!normalized.isEmpty()) {
                    normalized.remove(normalized.size() - 1);
                }
            } else {
                normalized.add(part);
            }
        }
        return String.join("/", normalized);
    }

    private static String normalizeEntryPath(String path) {
        String normalized = path == null ? "" : path.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private static String normalizeLookupKey(String href) {
        return normalizeEntryPath(href == null ? "" : href.split("#")[0]);
    }

    private static String urlDecodedPath(String path) {
        try {
            return URLDecoder.decode(path, "UTF-8");
        } catch (Exception ignore) {
            return path;
        }
    }

    private static String localNameOf(String qualifiedName) {
        int colon = qualifiedName == null ? -1 : qualifiedName.lastIndexOf(':');
        return colon >= 0 ? qualifiedName.substring(colon + 1) : qualifiedName;
    }

    private static boolean isXmlNameChar(char value) {
        return Character.isLetterOrDigit(value)
                || value == '_'
                || value == '-'
                || value == '.'
                || value == ':';
    }

    private static final class OpfPackage {
        final Map<String, ManifestItem> manifest;
        final List<String> spine;
        final String coverId;

        OpfPackage(Map<String, ManifestItem> manifest, List<String> spine, String coverId) {
            this.manifest = manifest;
            this.spine = spine;
            this.coverId = coverId == null ? "" : coverId;
        }
    }

    private static final class ManifestItem {
        final String id;
        final String href;
        final String mediaType;
        final String properties;

        ManifestItem(String id, String href, String mediaType, String properties) {
            this.id = id == null ? "" : id;
            this.href = href == null ? "" : href;
            this.mediaType = mediaType == null ? "" : mediaType;
            this.properties = properties == null ? "" : properties;
        }
    }

    private static final class DecodedCharset {
        final Charset charset;
        final int offset;

        DecodedCharset(Charset charset, int offset) {
            this.charset = charset;
            this.offset = offset;
        }
    }

    private static final class TagMatch {
        final int start;
        final int end;
        final String tag;

        TagMatch(int start, int end, String tag) {
            this.start = start;
            this.end = end;
            this.tag = tag;
        }
    }
}
