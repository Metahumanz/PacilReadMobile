package com.metahumanz.pacilread.importer;

import com.metahumanz.pacilread.model.ImportedBook;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public final class PdfChapterParser {
    private PdfChapterParser() {
    }

    /**
     * 解析 PDF 文件，支持按页拆分或按全文本智能拆分。
     * @param pdfFile PDF 文件
     * @param splitByPage true: 每一页作为一个章节; false: 尝试按标题拆分全文
     */
    public static List<ImportedBook.ChapterSeed> parse(File pdfFile, boolean splitByPage) throws Exception {
        List<ImportedBook.ChapterSeed> chapters = new ArrayList<>();
        try (PDDocument document = PDDocument.load(pdfFile)) {
            if (splitByPage) {
                int numberOfPages = document.getNumberOfPages();
                for (int i = 0; i < numberOfPages; i++) {
                    PDFTextStripper stripper = new PDFTextStripper();
                    stripper.setStartPage(i + 1);
                    stripper.setEndPage(i + 1);
                    String text = stripper.getText(document);
                    if (text != null && !text.trim().isEmpty()) {
                        ImportedBook.ChapterSeed seed = new ImportedBook.ChapterSeed();
                        seed.title = "第 " + (i + 1) + " 页";
                        seed.bodyText = text;
                        seed.orderIndex = i;
                        chapters.add(seed);
                    }
                }
            } else {
                PDFTextStripper stripper = new PDFTextStripper();
                String text = stripper.getText(document);
                return TxtChapterParser.split(text == null ? "" : text);
            }
        }
        return chapters;
    }
}
