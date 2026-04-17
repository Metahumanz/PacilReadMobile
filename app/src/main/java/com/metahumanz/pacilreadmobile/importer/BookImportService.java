package com.metahumanz.pacilread.importer;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.metahumanz.pacilread.model.ImportedBook;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class BookImportService {
    private final Context context;

    public BookImportService(Context context) {
        this.context = context.getApplicationContext();
        PDFBoxResourceLoader.init(this.context);
    }

    public ImportedBook importFromUri(Uri uri) throws Exception {
        String displayName = resolveDisplayName(uri);
        String extension = extensionOf(displayName);
        if (!".txt".equals(extension) && !".epub".equals(extension) && !".pdf".equals(extension)) {
            throw new IOException("当前仅支持导入 TXT、EPUB 与 PDF 文件");
        }

        File booksDir = new File(context.getFilesDir(), "books");
        if (!booksDir.exists() && !booksDir.mkdirs()) {
            throw new IOException("无法创建书籍缓存目录");
        }

        File localCopy = new File(booksDir, UUID.randomUUID() + extension);
        try (InputStream inputStream = context.getContentResolver().openInputStream(uri);
             FileOutputStream outputStream = new FileOutputStream(localCopy)) {
            if (inputStream == null) {
                throw new IOException("无法读取导入文件");
            }
            byte[] buffer = new byte[8192];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
        }

        BookFileNameParser.ParsedName parsedName = BookFileNameParser.parse(displayName);
        ImportedBook importedBook = new ImportedBook();
        importedBook.title = parsedName.title;
        importedBook.author = parsedName.author;
        importedBook.sourceDisplayName = displayName;
        importedBook.storedPath = localCopy.getAbsolutePath();

        List<ImportedBook.ChapterSeed> parsedChapters;
        if (".txt".equals(extension)) {
            try (InputStream inputStream = context.getContentResolver().openInputStream(uri)) {
                if (inputStream == null) {
                    throw new IOException("TXT 文件读取失败");
                }
                parsedChapters = TxtChapterParser.parse(inputStream);
            }
            importedBook.bookType = "text";
        } else if (".pdf".equals(extension)) {
            parsedChapters = PdfChapterParser.parse(localCopy);
            importedBook.bookType = "pdf";
        } else {
            parsedChapters = EpubChapterParser.parse(localCopy);
            importedBook.bookType = "epub";
        }
        importedBook.chapters.addAll(parsedChapters);
        return importedBook;
    }

    private String resolveDisplayName(Uri uri) {
        ContentResolver resolver = context.getContentResolver();
        try (Cursor cursor = resolver.query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    return cursor.getString(index);
                }
            }
        }
        String fallback = uri.getLastPathSegment();
        return fallback == null ? "Unknown.txt" : fallback;
    }

    private String extensionOf(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0) {
            return "";
        }
        return fileName.substring(dotIndex).toLowerCase(Locale.ROOT);
    }
}
