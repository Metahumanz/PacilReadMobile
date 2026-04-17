package com.metahumanz.pacilread.importer;

import com.metahumanz.pacilread.model.ImportedBook;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import java.io.File;
import java.util.List;

public final class PdfChapterParser {
    private PdfChapterParser() {
    }

    public static List<ImportedBook.ChapterSeed> parse(File pdfFile) throws Exception {
        try (PDDocument document = PDDocument.load(pdfFile)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
            return TxtChapterParser.split(text == null ? "" : text);
        }
    }
}
