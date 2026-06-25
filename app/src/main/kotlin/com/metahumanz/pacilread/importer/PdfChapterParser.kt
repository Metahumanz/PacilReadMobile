package com.metahumanz.pacilread.importer

import com.metahumanz.pacilread.model.ImportedBook
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

object PdfChapterParser {
    /** 解析 PDF 文件，支持按页拆分或按全文本智能拆分。 */
    @JvmStatic
    @Throws(Exception::class)
    fun parse(pdfFile: File, splitByPage: Boolean): List<ImportedBook.ChapterSeed> {
        val chapters = ArrayList<ImportedBook.ChapterSeed>()
        PDDocument.load(pdfFile).use { document ->
            if (splitByPage) {
                val numberOfPages = document.numberOfPages
                for (index in 0 until numberOfPages) {
                    val stripper = PDFTextStripper().apply {
                        startPage = index + 1
                        endPage = index + 1
                    }
                    val text = stripper.getText(document)
                    if (text != null && text.trim().isNotEmpty()) {
                        chapters.add(ImportedBook.ChapterSeed("第 ${index + 1} 页", null, text, index))
                    }
                }
            } else {
                val text = PDFTextStripper().getText(document)
                return TxtChapterParser.split(text ?: "")
            }
        }
        return chapters
    }
}
