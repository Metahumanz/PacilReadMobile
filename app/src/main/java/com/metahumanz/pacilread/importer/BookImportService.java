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
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class BookImportService {
    private final Context context;

    public BookImportService(Context context) {
        this.context = context.getApplicationContext();
        PDFBoxResourceLoader.init(this.context);
    }

    /**
     * 导入书籍，支持指定 PDF 是否按页拆分。
     */
    public ImportedBook importFromUri(Uri uri, boolean pdfSplitByPage) throws Exception {
        PreparedImport prepared = prepareFromUri(uri);
        try {
            return parsePrepared(prepared, pdfSplitByPage);
        } catch (Exception error) {
            prepared.deleteLocalCopy();
            throw error;
        }
    }

    public PreparedImport prepareFromUri(Uri uri) throws Exception {
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
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try {
            try (InputStream inputStream = context.getContentResolver().openInputStream(uri);
                 FileOutputStream outputStream = new FileOutputStream(localCopy)) {
                if (inputStream == null) {
                    throw new IOException("无法读取导入文件");
                }
                byte[] buffer = new byte[8192];
                int read;
                while ((read = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                }
            }
        } catch (Exception error) {
            localCopy.delete();
            throw error;
        }

        BookFileNameParser.ParsedName parsedName = BookFileNameParser.parse(displayName);
        return new PreparedImport(
                uri,
                displayName,
                extension,
                parsedName.title,
                parsedName.author,
                localCopy,
                toHex(digest.digest())
        );
    }

    public ImportedBook parsePrepared(PreparedImport prepared, boolean pdfSplitByPage) throws Exception {
        if (prepared == null || prepared.localCopy == null || !prepared.localCopy.isFile()) {
            throw new IOException("导入暂存文件不存在");
        }
        ImportedBook importedBook = new ImportedBook();
        importedBook.title = prepared.title;
        importedBook.author = prepared.author;
        importedBook.sourceDisplayName = prepared.displayName;
        importedBook.contentSha256 = prepared.contentSha256;
        importedBook.storedPath = prepared.localCopy.getAbsolutePath();

        List<ImportedBook.ChapterSeed> parsedChapters;
        if (".txt".equals(prepared.extension)) {
            try (InputStream inputStream = new FileInputStream(prepared.localCopy)) {
                parsedChapters = TxtChapterParser.parse(inputStream);
            }
            importedBook.bookType = "text";
        } else if (".pdf".equals(prepared.extension)) {
            parsedChapters = PdfChapterParser.parse(prepared.localCopy, pdfSplitByPage);
            importedBook.bookType = "pdf";
        } else {
            parsedChapters = EpubChapterParser.parse(prepared.localCopy);
            File coverFile = EpubChapterParser.extractCover(context, prepared.localCopy, "epub_cover");
            if (coverFile != null) {
                importedBook.coverPath = coverFile.getAbsolutePath();
            }
            importedBook.bookType = "epub";
        }
        importedBook.chapters.addAll(parsedChapters);
        return importedBook;
    }

    public String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return toHex(digest.digest());
    }

    private static String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }

    public static final class PreparedImport {
        public final Uri sourceUri;
        public final String displayName;
        public final String extension;
        public final String title;
        public final String author;
        public final File localCopy;
        public final String contentSha256;

        private PreparedImport(Uri sourceUri, String displayName, String extension,
                               String title, String author, File localCopy, String contentSha256) {
            this.sourceUri = sourceUri;
            this.displayName = displayName;
            this.extension = extension;
            this.title = title;
            this.author = author;
            this.localCopy = localCopy;
            this.contentSha256 = contentSha256;
        }

        public void deleteLocalCopy() {
            if (localCopy != null && localCopy.isFile()) {
                localCopy.delete();
            }
        }
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
