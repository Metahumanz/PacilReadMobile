package com.metahumanz.pacilread.importer

import android.content.Context
import android.util.Log
import com.metahumanz.pacilread.model.ImportedBook
import com.metahumanz.pacilread.util.CoverImageStore
import com.metahumanz.pacilread.util.HtmlUtils
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.ZipFile
import kotlin.math.max
import kotlin.math.min

class EpubChapterParser private constructor() {
    companion object {
        private const val TAG = "EpubChapterParser"
        private const val XML_PROBE_BYTES = 4096

        @JvmStatic
        @Throws(Exception::class)
        fun parse(epubFile: File): List<ImportedBook.ChapterSeed> {
            ZipFile(epubFile, Charset.forName("UTF-8")).use { zipFile ->
                val opfPath = resolvePackageDocumentPath(zipFile)
                val opf = readOpfPackage(zipFile, opfPath)
                val opfDir = parentPath(opfPath)
                val titles = LinkedHashMap<String, String>()
                try {
                    titles.putAll(parseNcxTitles(zipFile, opfDir, opf.manifest))
                } catch (error: Exception) {
                    Log.w(TAG, "解析 NCX 目录失败，继续导入 EPUB 正文", error)
                }
                try {
                    titles.putAll(parseNavTitles(zipFile, opfDir, opf.manifest))
                } catch (error: Exception) {
                    Log.w(TAG, "解析 NAV 目录失败，继续导入 EPUB 正文", error)
                }

                val chapters = ArrayList<ImportedBook.ChapterSeed>()
                var order = 0
                for (idRef in opf.spine) {
                    val item = opf.manifest[idRef]
                    if (!isReadableDocumentItem(item)) continue
                    val rawDocument = readManifestItem(zipFile, opfDir, item)
                    if (rawDocument == null || rawDocument.isEmpty()) continue
                    val documentText = decodeXmlText(rawDocument)
                    val bodyHtml = HtmlUtils.pruneUnsupportedMarkup(HtmlUtils.extractBodyFragment(documentText))
                    var plain = HtmlUtils.stripHtml(bodyHtml)
                    if (plain.isBlank()) plain = HtmlUtils.stripHtml(documentText)
                    if (plain.isBlank()) continue
                    val title = findChapterTitle(item!!.href, titles, documentText, order + 1)
                    chapters.add(ImportedBook.ChapterSeed(title, "", plain, order))
                    order++
                }
                if (chapters.isEmpty()) throw IOException("EPUB 中没有可读取的章节内容")
                return chapters
            }
        }

        @JvmStatic
        fun extractCover(context: Context, epubFile: File, prefix: String): File? {
            try {
                ZipFile(epubFile, Charset.forName("UTF-8")).use { zipFile ->
                    val opfPath = resolvePackageDocumentPath(zipFile)
                    val opf = readOpfPackage(zipFile, opfPath)
                    val opfDir = parentPath(opfPath)
                    for (item in coverCandidates(opf)) {
                        try {
                            val imageBytes = readManifestItem(zipFile, opfDir, item)
                            if (imageBytes == null || imageBytes.isEmpty()) continue
                            return CoverImageStore.saveCompressedCover(context, imageBytes, prefix)
                        } catch (error: IOException) {
                            Log.w(TAG, "EPUB 封面候选读取失败，继续尝试下一个", error)
                        } catch (error: RuntimeException) {
                            Log.w(TAG, "EPUB 封面候选读取失败，继续尝试下一个", error)
                        } catch (error: OutOfMemoryError) {
                            Log.w(TAG, "EPUB 封面候选读取失败，继续尝试下一个", error)
                        }
                    }
                }
            } catch (error: Exception) {
                Log.w(TAG, "EPUB 封面提取失败，跳过封面", error)
            }
            return null
        }

        @Throws(IOException::class)
        private fun resolvePackageDocumentPath(zipFile: ZipFile): String {
            val containerXml = readRequiredEntry(zipFile, "META-INF/container.xml", "EPUB 缺少 META-INF/container.xml")
            val containerText = decodeXmlText(containerXml)
            for (tag in startTagsByLocalName(containerText, "rootfile")) {
                val fullPath = attributeValue(tag, "full-path")
                if (fullPath.isNotBlank()) return normalizeEntryPath(fullPath.trim())
            }
            throw IOException("EPUB container.xml 未声明 package 文档路径")
        }

        @Throws(IOException::class)
        private fun readOpfPackage(zipFile: ZipFile, opfPath: String): OpfPackage {
            val bytes = readRequiredEntry(zipFile, opfPath, "EPUB package 文档读取失败: $opfPath")
            return parseOpfPackage(decodeXmlText(bytes), opfPath)
        }

        @Throws(IOException::class)
        private fun parseOpfPackage(opfText: String, opfPath: String): OpfPackage {
            val manifest = LinkedHashMap<String, ManifestItem>()
            for (tag in startTagsByLocalName(opfText, "item")) {
                val id = attributeValue(tag, "id")
                val href = attributeValue(tag, "href")
                if (id.isBlank() || href.isBlank()) continue
                manifest[id] = ManifestItem(id, href, attributeValue(tag, "media-type"), attributeValue(tag, "properties"))
            }
            if (manifest.isEmpty()) throw IOException("EPUB OPF manifest 为空: $opfPath")
            val spine = ArrayList<String>()
            for (tag in startTagsByLocalName(opfText, "itemref")) {
                val idRef = attributeValue(tag, "idref")
                if (idRef.isNotBlank()) spine.add(idRef)
            }
            if (spine.isEmpty()) {
                for (item in manifest.values) if (isReadableDocumentItem(item)) spine.add(item.id)
            }
            var coverId = ""
            for (tag in startTagsByLocalName(opfText, "meta")) {
                if (attributeValue(tag, "name").equals("cover", ignoreCase = true)) {
                    coverId = attributeValue(tag, "content")
                    break
                }
            }
            return OpfPackage(manifest, spine, coverId)
        }

        @Throws(IOException::class)
        private fun parseNcxTitles(zipFile: ZipFile, opfDir: String, manifest: Map<String, ManifestItem>): Map<String, String> {
            val toc = HashMap<String, String>()
            for (item in manifest.values) {
                if (!isNcxItem(item)) continue
                val bytes = readManifestItem(zipFile, opfDir, item)
                if (bytes == null || bytes.isEmpty()) continue
                val ncx = decodeXmlText(bytes)
                for (block in elementBlocksByLocalName(ncx, "navPoint")) {
                    val label = elementTextByLocalName(block, "text")
                    val src = attributeValue(firstStartTagByLocalName(block, "content"), "src")
                    if (label.isNotBlank() && src.isNotBlank()) toc[normalizeLookupKey(src)] = label
                }
                break
            }
            return toc
        }

        @Throws(IOException::class)
        private fun parseNavTitles(zipFile: ZipFile, opfDir: String, manifest: Map<String, ManifestItem>): Map<String, String> {
            val toc = HashMap<String, String>()
            for (item in manifest.values) {
                if (!item.properties.lowercase(Locale.ROOT).contains("nav")) continue
                val bytes = readManifestItem(zipFile, opfDir, item)
                if (bytes == null || bytes.isEmpty()) continue
                val html = decodeXmlText(bytes)
                var searchFrom = 0
                while (true) {
                    val anchor = nextStartTagByLocalName(html, "a", searchFrom) ?: return toc
                    val anchorEnd = findEndTagByLocalName(html, "a", anchor.end + 1)
                    if (anchorEnd < 0) return toc
                    val href = attributeValue(anchor.tag, "href")
                    val text = HtmlUtils.stripHtml(html.substring(anchor.end + 1, anchorEnd))
                    if (href.isNotBlank() && text.isNotBlank()) toc[normalizeLookupKey(href)] = text
                    val closeEnd = html.indexOf('>', anchorEnd)
                    searchFrom = if (closeEnd >= 0) closeEnd + 1 else anchorEnd + 1
                }
            }
            return toc
        }

        private fun coverCandidates(opf: OpfPackage): List<ManifestItem> {
            val candidates = ArrayList<ManifestItem>()
            if (opf.coverId.isNotBlank()) addCoverCandidate(candidates, opf.manifest[opf.coverId])
            for (item in opf.manifest.values) {
                if (item.properties.lowercase(Locale.ROOT).contains("cover-image")) addCoverCandidate(candidates, item)
            }
            for (item in opf.manifest.values) {
                if (!isImageItem(item)) continue
                val href = item.href.lowercase(Locale.ROOT)
                if (href.contains("cover") || href.contains("front") || href.contains("title")) addCoverCandidate(candidates, item)
            }
            for (item in opf.manifest.values) if (isImageItem(item)) addCoverCandidate(candidates, item)
            return candidates
        }

        private fun addCoverCandidate(candidates: MutableList<ManifestItem>, item: ManifestItem?) {
            if (!isImageItem(item)) return
            for (existing in candidates) {
                if ((existing.id.isNotBlank() && existing.id == item!!.id) ||
                    (existing.href.isNotBlank() && existing.href == item!!.href)) return
            }
            candidates.add(item!!)
        }

        private fun isNcxItem(item: ManifestItem): Boolean {
            val mediaType = item.mediaType.lowercase(Locale.ROOT)
            val href = item.href.lowercase(Locale.ROOT)
            return mediaType == "application/x-dtbncx+xml" || href.endsWith(".ncx")
        }

        private fun isReadableDocumentItem(item: ManifestItem?): Boolean {
            if (item == null || item.href.isBlank()) return false
            val mediaType = item.mediaType.lowercase(Locale.ROOT)
            val href = item.href.lowercase(Locale.ROOT)
            return mediaType.contains("xhtml") || mediaType.contains("html") || mediaType == "application/xml" ||
                href.endsWith(".xhtml") || href.endsWith(".html") || href.endsWith(".htm") || href.endsWith(".xml")
        }

        private fun isImageItem(item: ManifestItem?): Boolean {
            if (item == null || item.href.isBlank()) return false
            val mediaType = item.mediaType.lowercase(Locale.ROOT)
            val href = item.href.lowercase(Locale.ROOT)
            val imageMediaType = mediaType.startsWith("image/") && !mediaType.contains("svg")
            val imageExtension = href.endsWith(".jpg") || href.endsWith(".jpeg") || href.endsWith(".png") || href.endsWith(".webp")
            return (imageMediaType || imageExtension) && !href.endsWith(".svg")
        }

        private fun findChapterTitle(href: String, titles: Map<String, String>, html: String, index: Int): String {
            var title = titles[normalizeLookupKey(href)] ?: titles[normalizeLookupKey(urlDecodedPath(href))]
            if (title.isNullOrBlank()) title = HtmlUtils.firstMeaningfulHeading(html)
            if (title.isNullOrBlank()) title = "第 $index 章"
            return title.trim()
        }

        private fun startTagsByLocalName(text: String, localName: String): List<String> {
            val tags = ArrayList<String>()
            var searchFrom = 0
            while (true) {
                val match = nextStartTagByLocalName(text, localName, searchFrom) ?: return tags
                tags.add(match.tag)
                searchFrom = match.end + 1
            }
        }

        private fun firstStartTagByLocalName(text: String, localName: String): String =
            nextStartTagByLocalName(text, localName, 0)?.tag ?: ""

        private fun nextStartTagByLocalName(text: String?, localName: String?, fromIndex: Int): TagMatch? {
            if (text == null || localName == null) return null
            val wanted = localName.lowercase(Locale.ROOT)
            var searchFrom = max(0, fromIndex)
            while (searchFrom < text.length) {
                val start = text.indexOf('<', searchFrom)
                if (start < 0 || start + 1 >= text.length) return null
                val next = text[start + 1]
                if (next == '/' || next == '?' || next == '!') {
                    searchFrom = start + 2
                    continue
                }
                val nameStart = start + 1
                var nameEnd = nameStart
                while (nameEnd < text.length && isXmlNameChar(text[nameEnd])) nameEnd++
                if (nameEnd == nameStart) {
                    searchFrom = start + 1
                    continue
                }
                val name = localNameOf(text.substring(nameStart, nameEnd)).lowercase(Locale.ROOT)
                val tagEnd = findTagEnd(text, nameEnd)
                if (tagEnd < 0) return null
                if (wanted == name) return TagMatch(start, tagEnd, text.substring(start, tagEnd + 1))
                searchFrom = tagEnd + 1
            }
            return null
        }

        private fun elementBlocksByLocalName(text: String, localName: String): List<String> {
            val blocks = ArrayList<String>()
            var searchFrom = 0
            while (true) {
                val start = nextStartTagByLocalName(text, localName, searchFrom) ?: return blocks
                val endStart = findEndTagByLocalName(text, localName, start.end + 1)
                if (endStart < 0) return blocks
                val end = text.indexOf('>', endStart)
                if (end < 0) return blocks
                blocks.add(text.substring(start.start, end + 1))
                searchFrom = end + 1
            }
        }

        private fun elementTextByLocalName(text: String, localName: String): String {
            val start = nextStartTagByLocalName(text, localName, 0) ?: return ""
            val endStart = findEndTagByLocalName(text, localName, start.end + 1)
            if (endStart < 0) return ""
            return decodeXmlEntities(HtmlUtils.stripHtml(text.substring(start.end + 1, endStart))).trim()
        }

        private fun findEndTagByLocalName(text: String, localName: String, fromIndex: Int): Int {
            val wanted = localName.lowercase(Locale.ROOT)
            var searchFrom = max(0, fromIndex)
            while (searchFrom < text.length) {
                val start = text.indexOf("</", searchFrom)
                if (start < 0) return -1
                val nameStart = start + 2
                var nameEnd = nameStart
                while (nameEnd < text.length && isXmlNameChar(text[nameEnd])) nameEnd++
                if (nameEnd > nameStart) {
                    val name = localNameOf(text.substring(nameStart, nameEnd)).lowercase(Locale.ROOT)
                    if (wanted == name) return start
                }
                searchFrom = nameEnd + 1
            }
            return -1
        }

        private fun findTagEnd(text: String, fromIndex: Int): Int {
            var quote = '\u0000'
            for (i in fromIndex until text.length) {
                val char = text[i]
                if (quote != '\u0000') {
                    if (char == quote) quote = '\u0000'
                    continue
                }
                if (char == '"' || char == '\'') quote = char else if (char == '>') return i
            }
            return -1
        }

        private fun attributeValue(tag: String?, name: String?): String {
            if (tag.isNullOrEmpty() || name.isNullOrEmpty()) return ""
            val wanted = name.lowercase(Locale.ROOT)
            var pos = 0
            while (pos < tag.length && tag[pos] != '<') pos++
            pos++
            while (pos < tag.length && isXmlNameChar(tag[pos])) pos++
            while (pos < tag.length) {
                while (pos < tag.length && tag[pos].isWhitespace()) pos++
                if (pos >= tag.length || tag[pos] == '/' || tag[pos] == '>') return ""
                val nameStart = pos
                while (pos < tag.length && isXmlNameChar(tag[pos])) pos++
                if (pos == nameStart) {
                    pos++
                    continue
                }
                val attrName = localNameOf(tag.substring(nameStart, pos)).lowercase(Locale.ROOT)
                while (pos < tag.length && tag[pos].isWhitespace()) pos++
                if (pos >= tag.length || tag[pos] != '=') continue
                pos++
                while (pos < tag.length && tag[pos].isWhitespace()) pos++
                if (pos >= tag.length) return ""
                val quote = tag[pos]
                val value: String
                if (quote == '"' || quote == '\'') {
                    val valueStart = pos + 1
                    val valueEnd = tag.indexOf(quote, valueStart)
                    if (valueEnd < 0) return ""
                    value = tag.substring(valueStart, valueEnd)
                    pos = valueEnd + 1
                } else {
                    val valueStart = pos
                    while (pos < tag.length && !tag[pos].isWhitespace() && tag[pos] != '/' && tag[pos] != '>') pos++
                    value = tag.substring(valueStart, pos)
                }
                if (wanted == attrName) return decodeXmlEntities(value).trim()
            }
            return ""
        }

        private fun decodeXmlText(rawDocument: ByteArray?): String {
            if (rawDocument == null || rawDocument.isEmpty()) return ""
            val detected = detectCharset(rawDocument)
            return String(rawDocument, detected.offset, rawDocument.size - detected.offset, detected.charset)
        }

        private fun detectCharset(bytes: ByteArray): DecodedCharset {
            if (startsWith(bytes, 0xEF, 0xBB, 0xBF)) return DecodedCharset(StandardCharsets.UTF_8, 3)
            if (startsWith(bytes, 0xFF, 0xFE)) return DecodedCharset(StandardCharsets.UTF_16LE, 2)
            if (startsWith(bytes, 0xFE, 0xFF)) return DecodedCharset(StandardCharsets.UTF_16BE, 2)
            val utf16 = detectUtf16Encoding(bytes)
            if (utf16 != null) return DecodedCharset(utf16, 0)
            val probe = String(bytes, 0, min(bytes.size, XML_PROBE_BYTES), StandardCharsets.ISO_8859_1)
            val encoding = attributeValue(xmlDeclarationFromText(probe), "encoding")
            return DecodedCharset(charsetOrDefault(encoding, StandardCharsets.UTF_8), 0)
        }

        private fun detectUtf16Encoding(bytes: ByteArray): Charset? {
            val scanLimit = min(bytes.size - 3, 256)
            for (i in 0 until scanLimit) {
                if (bytes[i].toInt() == 0 && bytes[i + 1].toInt() == '<'.code && bytes[i + 2].toInt() == 0 && bytes[i + 3].toInt() == '?'.code) return StandardCharsets.UTF_16BE
                if (bytes[i].toInt() == '<'.code && bytes[i + 1].toInt() == 0 && bytes[i + 2].toInt() == '?'.code && bytes[i + 3].toInt() == 0) return StandardCharsets.UTF_16LE
            }
            val pairCount = min(bytes.size / 2, 96)
            var evenNulls = 0
            var oddNulls = 0
            var i = 0
            while (i < pairCount * 2) {
                if (bytes[i].toInt() == 0) evenNulls++
                if (bytes[i + 1].toInt() == 0) oddNulls++
                i += 2
            }
            if (oddNulls >= max(3, pairCount / 3) && oddNulls > evenNulls * 2) return StandardCharsets.UTF_16LE
            if (evenNulls >= max(3, pairCount / 3) && evenNulls > oddNulls * 2) return StandardCharsets.UTF_16BE
            return null
        }

        private fun charsetOrDefault(encoding: String?, fallback: Charset): Charset {
            if (encoding.isNullOrBlank()) return fallback
            return try { Charset.forName(encoding.trim()) } catch (_: Exception) { fallback }
        }

        private fun xmlDeclarationFromText(text: String?): String {
            if (text.isNullOrEmpty()) return ""
            var start = 0
            while (start < text.length && (text[start].isWhitespace() || text[start] == '\uFEFF')) start++
            if (!text.regionMatches(start, "<?xml", 0, 5, ignoreCase = true)) return ""
            val end = text.indexOf("?>", start)
            if (end < 0 || end - start > 512) return ""
            return text.substring(start, end + 2)
        }

        private fun startsWith(bytes: ByteArray?, vararg values: Int): Boolean {
            if (bytes == null || bytes.size < values.size) return false
            for (i in values.indices) if (bytes[i].toInt() and 0xFF != values[i]) return false
            return true
        }

        private fun decodeXmlEntities(value: String?): String {
            if (value.isNullOrEmpty()) return ""
            return value.replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&amp;", "&")
        }

        @Throws(IOException::class)
        private fun readEntry(zipFile: ZipFile, path: String): ByteArray? {
            val entry = zipFile.getEntry(path) ?: return null
            zipFile.getInputStream(entry).use { return readAllBytesCompat(it) }
        }

        @Throws(IOException::class)
        private fun readEntryWithFallback(zipFile: ZipFile, path: String): ByteArray? =
            readEntry(zipFile, path) ?: readEntry(zipFile, urlDecodedPath(path))

        @Throws(IOException::class)
        private fun readRequiredEntry(zipFile: ZipFile, path: String, errorMessage: String): ByteArray =
            readEntryWithFallback(zipFile, path) ?: throw IOException(errorMessage)

        @Throws(IOException::class)
        private fun readManifestItem(zipFile: ZipFile, opfDir: String, item: ManifestItem?): ByteArray? {
            if (item == null || item.href.isBlank()) return null
            return readEntryWithFallback(zipFile, normalizeEntryPath(resolveAgainst(opfDir, item.href)))
        }

        @Throws(IOException::class)
        private fun readAllBytesCompat(input: InputStream): ByteArray {
            val buffer = ByteArrayOutputStream()
            val data = ByteArray(16384)
            while (true) {
                val count = input.read(data, 0, data.size)
                if (count == -1) break
                buffer.write(data, 0, count)
            }
            return buffer.toByteArray()
        }

        private fun parentPath(path: String): String {
            val index = path.lastIndexOf('/')
            return if (index < 0) "" else path.substring(0, index)
        }

        private fun resolveAgainst(base: String?, child: String?): String {
            if (child == null) return ""
            if (child.startsWith('/')) return child.substring(1)
            if (base.isNullOrEmpty()) return child
            val normalized = ArrayList<String>()
            for (part in "$base/$child".split('/')) {
                if (part.isEmpty() || part == ".") continue
                if (part == "..") {
                    if (normalized.isNotEmpty()) normalized.removeAt(normalized.size - 1)
                } else normalized.add(part)
            }
            return normalized.joinToString("/")
        }

        private fun normalizeEntryPath(path: String?): String {
            var normalized = path?.replace('\\', '/') ?: ""
            while (normalized.startsWith('/')) normalized = normalized.substring(1)
            return normalized
        }

        private fun normalizeLookupKey(href: String?): String = normalizeEntryPath((href ?: "").substringBefore('#'))

        private fun urlDecodedPath(path: String): String = try { URLDecoder.decode(path, "UTF-8") } catch (_: Exception) { path }

        private fun localNameOf(qualifiedName: String?): String {
            if (qualifiedName == null) return ""
            val colon = qualifiedName.lastIndexOf(':')
            return if (colon >= 0) qualifiedName.substring(colon + 1) else qualifiedName
        }

        private fun isXmlNameChar(value: Char): Boolean = value.isLetterOrDigit() || value == '_' || value == '-' || value == '.' || value == ':'

        private class OpfPackage(val manifest: Map<String, ManifestItem>, val spine: List<String>, coverId: String?) {
            val coverId = coverId ?: ""
        }

        private class ManifestItem(id: String?, href: String?, mediaType: String?, properties: String?) {
            val id = id ?: ""
            val href = href ?: ""
            val mediaType = mediaType ?: ""
            val properties = properties ?: ""
        }

        private class DecodedCharset(val charset: Charset, val offset: Int)
        private class TagMatch(val start: Int, val end: Int, val tag: String)
    }
}
