package com.metahumanz.pacilread.importer;

import android.content.Context;

import com.metahumanz.pacilread.model.ImportedBook;
import com.metahumanz.pacilread.util.CoverImageStore;
import com.metahumanz.pacilread.util.HtmlUtils;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
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

import javax.xml.parsers.DocumentBuilderFactory;

public final class EpubChapterParser {
    private EpubChapterParser() {
    }

    public static List<ImportedBook.ChapterSeed> parse(File epubFile) throws Exception {
        try (ZipFile zipFile = new ZipFile(epubFile, Charset.forName("UTF-8"))) {
            String opfPath = resolvePackageDocumentPath(zipFile);
            Document opfDocument = parseXml(readEntry(zipFile, opfPath));
            String opfDir = parentPath(opfPath);

            Map<String, ManifestItem> manifest = parseManifest(opfDocument);
            List<String> spine = parseSpine(opfDocument);
            Map<String, String> titles = new LinkedHashMap<>();
            titles.putAll(parseNcxTitles(zipFile, opfDir, manifest));
            titles.putAll(parseNavTitles(zipFile, opfDir, manifest));

            List<ImportedBook.ChapterSeed> chapters = new ArrayList<>();
            int order = 0;
            for (String idRef : spine) {
                ManifestItem item = manifest.get(idRef);
                if (item == null) {
                    continue;
                }
                String resolvedPath = normalizeEntryPath(resolveAgainst(opfDir, item.href));
                byte[] rawDocument = readEntry(zipFile, resolvedPath);
                if (rawDocument == null) {
                    rawDocument = readEntry(zipFile, urlDecodedPath(resolvedPath));
                }
                if (rawDocument == null) {
                    continue;
                }
                String documentText = decodeXmlText(rawDocument);
                String bodyHtml = HtmlUtils.pruneUnsupportedMarkup(HtmlUtils.extractBodyFragment(documentText));
                String plain = HtmlUtils.stripHtml(bodyHtml);
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
            Document opfDocument = parseXml(readEntry(zipFile, opfPath));
            String opfDir = parentPath(opfPath);
            Map<String, ManifestItem> manifest = parseManifest(opfDocument);
            List<ManifestItem> candidates = coverCandidates(opfDocument, manifest);
            for (ManifestItem item : candidates) {
                byte[] imageBytes = readManifestItem(zipFile, opfDir, item);
                if (imageBytes == null || imageBytes.length == 0) {
                    continue;
                }
                try {
                    return CoverImageStore.saveCompressedCover(context, imageBytes, prefix);
                } catch (IOException ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String resolvePackageDocumentPath(ZipFile zipFile) throws Exception {
        byte[] containerXml = readEntry(zipFile, "META-INF/container.xml");
        if (containerXml == null) {
            throw new IOException("EPUB 缺少 META-INF/container.xml");
        }
        Document document = parseXml(containerXml);
        NodeList rootfiles = document.getElementsByTagName("rootfile");
        if (rootfiles.getLength() == 0) {
            throw new IOException("EPUB package 文档路径缺失");
        }
        Element rootFile = (Element) rootfiles.item(0);
        return rootFile.getAttribute("full-path");
    }

    private static Map<String, ManifestItem> parseManifest(Document opfDocument) {
        Map<String, ManifestItem> manifest = new LinkedHashMap<>();
        NodeList items = opfDocument.getElementsByTagName("item");
        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            String id = item.getAttribute("id");
            manifest.put(item.getAttribute("id"), new ManifestItem(
                    id,
                    item.getAttribute("href"),
                    item.getAttribute("media-type"),
                    item.getAttribute("properties")
            ));
        }
        return manifest;
    }

    private static List<ManifestItem> coverCandidates(Document opfDocument, Map<String, ManifestItem> manifest) {
        List<ManifestItem> candidates = new ArrayList<>();
        String coverId = opfCoverId(opfDocument);
        if (!coverId.isBlank()) {
            addCoverCandidate(candidates, manifest.get(coverId));
        }
        for (ManifestItem item : manifest.values()) {
            if (item.properties != null && item.properties.toLowerCase(Locale.ROOT).contains("cover-image")) {
                addCoverCandidate(candidates, item);
            }
        }
        for (ManifestItem item : manifest.values()) {
            if (!isImageItem(item)) {
                continue;
            }
            String href = item.href == null ? "" : item.href.toLowerCase(Locale.ROOT);
            if (href.contains("cover") || href.contains("front") || href.contains("title")) {
                addCoverCandidate(candidates, item);
            }
        }
        return candidates;
    }

    private static String opfCoverId(Document opfDocument) {
        NodeList metas = opfDocument.getElementsByTagName("meta");
        for (int i = 0; i < metas.getLength(); i++) {
            Element meta = (Element) metas.item(i);
            if ("cover".equalsIgnoreCase(meta.getAttribute("name"))) {
                String content = meta.getAttribute("content");
                return content == null ? "" : content.trim();
            }
        }
        return "";
    }

    private static void addCoverCandidate(List<ManifestItem> candidates, ManifestItem item) {
        if (!isImageItem(item)) {
            return;
        }
        for (ManifestItem existing : candidates) {
            if ((!existing.id.isBlank() && existing.id.equals(item.id)) || existing.href.equals(item.href)) {
                return;
            }
        }
        candidates.add(item);
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
        if (!imageMediaType && !imageExtension) {
            return false;
        }
        return !href.endsWith(".svg");
    }

    private static List<String> parseSpine(Document opfDocument) {
        List<String> spine = new ArrayList<>();
        NodeList itemRefs = opfDocument.getElementsByTagName("itemref");
        for (int i = 0; i < itemRefs.getLength(); i++) {
            Element itemRef = (Element) itemRefs.item(i);
            spine.add(itemRef.getAttribute("idref"));
        }
        return spine;
    }

    private static Map<String, String> parseNcxTitles(ZipFile zipFile, String opfDir, Map<String, ManifestItem> manifest) throws Exception {
        Map<String, String> toc = new HashMap<>();
        for (ManifestItem item : manifest.values()) {
            if (!"application/x-dtbncx+xml".equals(item.mediaType)) {
                continue;
            }
            String path = normalizeEntryPath(resolveAgainst(opfDir, item.href));
            byte[] bytes = readEntry(zipFile, path);
            if (bytes == null) {
                continue;
            }
            Document document = parseXml(bytes);
            NodeList navPoints = document.getElementsByTagName("navPoint");
            for (int i = 0; i < navPoints.getLength(); i++) {
                Element navPoint = (Element) navPoints.item(i);
                NodeList labelNodes = navPoint.getElementsByTagName("text");
                String label = labelNodes.getLength() > 0 ? labelNodes.item(0).getTextContent().trim() : "";
                NodeList contentNodes = navPoint.getElementsByTagName("content");
                if (contentNodes.getLength() == 0) {
                    continue;
                }
                Element content = (Element) contentNodes.item(0);
                String src = content.getAttribute("src");
                if (src != null && !src.isBlank() && !label.isBlank()) {
                    toc.put(normalizeLookupKey(src), label);
                }
            }
            break;
        }
        return toc;
    }

    private static Map<String, String> parseNavTitles(ZipFile zipFile, String opfDir, Map<String, ManifestItem> manifest) throws Exception {
        Map<String, String> toc = new HashMap<>();
        for (ManifestItem item : manifest.values()) {
            if (item.properties == null || !item.properties.contains("nav")) {
                continue;
            }
            String path = normalizeEntryPath(resolveAgainst(opfDir, item.href));
            byte[] bytes = readEntry(zipFile, path);
            if (bytes == null) {
                continue;
            }
            String html = decodeXmlText(bytes);
            String lower = html.toLowerCase(Locale.ROOT);
            int searchFrom = 0;
            while (true) {
                int anchorStart = lower.indexOf("<a", searchFrom);
                if (anchorStart < 0) {
                    return toc;
                }
                int tagEnd = lower.indexOf('>', anchorStart);
                int anchorEnd = lower.indexOf("</a>", tagEnd);
                if (tagEnd < 0 || anchorEnd < 0) {
                    return toc;
                }
                String tag = html.substring(anchorStart, tagEnd + 1);
                String href = attributeValue(tag, "href");
                String text = HtmlUtils.stripHtml(html.substring(tagEnd + 1, anchorEnd));
                if (!href.isBlank() && !text.isBlank()) {
                    toc.put(normalizeLookupKey(href), text);
                }
                searchFrom = anchorEnd + 4;
            }
        }
        return toc;
    }

    private static String attributeValue(String tag, String name) {
        String marker = name + "=\"";
        int start = tag.indexOf(marker);
        if (start < 0) {
            return "";
        }
        int valueStart = start + marker.length();
        int valueEnd = tag.indexOf('"', valueStart);
        if (valueEnd < 0) {
            return "";
        }
        return tag.substring(valueStart, valueEnd);
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

    private static Document parseXml(byte[] bytes) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }

    private static String decodeXmlText(byte[] rawDocument) {
        String asciiProbe = new String(rawDocument, StandardCharsets.ISO_8859_1);
        String charsetName = "UTF-8";
        int encodingIndex = asciiProbe.indexOf("encoding=\"");
        if (encodingIndex >= 0) {
            int start = encodingIndex + "encoding=\"".length();
            int end = asciiProbe.indexOf('"', start);
            if (end > start) {
                charsetName = asciiProbe.substring(start, end);
            }
        }
        return new String(rawDocument, Charset.forName(charsetName));
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

    private static byte[] readManifestItem(ZipFile zipFile, String opfDir, ManifestItem item) throws IOException {
        if (item == null || item.href == null || item.href.isBlank()) {
            return null;
        }
        String resolvedPath = normalizeEntryPath(resolveAgainst(opfDir, item.href));
        byte[] bytes = readEntry(zipFile, resolvedPath);
        return bytes == null ? readEntry(zipFile, urlDecodedPath(resolvedPath)) : bytes;
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
        return path.replace('\\', '/');
    }

    private static String normalizeLookupKey(String href) {
        return normalizeEntryPath(href.split("#")[0]);
    }

    private static String urlDecodedPath(String path) {
        try {
            return URLDecoder.decode(path, "UTF-8");
        } catch (Exception ignore) {
            return path;
        }
    }

    private static final class ManifestItem {
        final String id;
        final String href;
        final String mediaType;
        final String properties;

        ManifestItem(String id, String href, String mediaType, String properties) {
            this.id = id == null ? "" : id;
            this.href = href;
            this.mediaType = mediaType;
            this.properties = properties;
        }
    }
}
