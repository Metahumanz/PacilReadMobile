package com.metahumanz.pacilread;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import com.metahumanz.pacilread.model.BookRecord;

import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class BookListAdapter extends BaseAdapter {
    private final LayoutInflater inflater;
    private final DateFormat dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.SIMPLIFIED_CHINESE);
    private final List<BookRecord> books = new ArrayList<>();

    public BookListAdapter(Context context) {
        this.inflater = LayoutInflater.from(context);
    }

    public void setItems(List<BookRecord> items) {
        books.clear();
        books.addAll(items);
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return books.size();
    }

    @Override
    public BookRecord getItem(int position) {
        return books.get(position);
    }

    @Override
    public long getItemId(int position) {
        return books.get(position).id;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_book, parent, false);
            holder = new ViewHolder();
            holder.title = convertView.findViewById(R.id.text_title);
            holder.author = convertView.findViewById(R.id.text_author);
            holder.currentChapter = convertView.findViewById(R.id.text_current_chapter);
            holder.recentRead = convertView.findViewById(R.id.text_recent_read);
            holder.pin = convertView.findViewById(R.id.text_pin);
            holder.cover = convertView.findViewById(R.id.image_cover);
            holder.coverFallback = convertView.findViewById(R.id.text_cover_fallback);
            holder.type = convertView.findViewById(R.id.text_type);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        BookRecord book = getItem(position);
        holder.title.setText(book.title == null || book.title.isBlank() ? "未命名书籍" : book.title);
        holder.author.setText("作者：" + (book.author == null || book.author.isBlank() ? "未知作者" : book.author));
        holder.currentChapter.setText(currentChapterText(book));
        holder.recentRead.setText(book.lastReadAt > 0
                ? "最近阅读：" + dateFormat.format(new Date(book.lastReadAt))
                : "最近阅读：尚未阅读");
        holder.pin.setVisibility(book.pinned ? View.VISIBLE : View.GONE);
        holder.type.setText(typeLabel(book.bookType));

        Bitmap coverBitmap = decodeCover(book.coverPath);
        if (coverBitmap != null) {
            holder.cover.setImageBitmap(coverBitmap);
            holder.coverFallback.setVisibility(View.GONE);
        } else {
            holder.cover.setImageDrawable(null);
            holder.coverFallback.setText(initialsFor(book.title));
            holder.coverFallback.setVisibility(View.VISIBLE);
        }
        return convertView;
    }

    private Bitmap decodeCover(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        File file = new File(path);
        if (!file.exists()) {
            return null;
        }
        return BitmapFactory.decodeFile(file.getAbsolutePath());
    }

    private String initialsFor(String title) {
        if (title == null || title.isBlank()) {
            return "PR";
        }
        String trimmed = title.trim();
        if (trimmed.length() == 1) {
            return trimmed.toUpperCase(Locale.ROOT);
        }
        return trimmed.substring(0, Math.min(2, trimmed.length())).toUpperCase(Locale.ROOT);
    }

    private String currentChapterText(BookRecord book) {
        if (book.chapterCount <= 0) {
            return "当前阅读：未生成章节";
        }
        String chapterTitle = book.currentChapterTitle == null || book.currentChapterTitle.isBlank()
                ? "未命名章节"
                : book.currentChapterTitle.trim();
        int chapterPosition = Math.max(1, Math.min(book.progressIndex + 1, book.chapterCount));
        return String.format(
                Locale.SIMPLIFIED_CHINESE,
                "当前阅读：第 %d/%d 章 · %s",
                chapterPosition,
                book.chapterCount,
                chapterTitle
        );
    }

    private String typeLabel(String bookType) {
        if ("epub".equalsIgnoreCase(bookType)) {
            return "EPUB";
        }
        if ("pdf".equalsIgnoreCase(bookType)) {
            return "PDF";
        }
        return "TXT";
    }

    private static class ViewHolder {
        TextView title;
        TextView author;
        TextView currentChapter;
        TextView recentRead;
        TextView pin;
        TextView coverFallback;
        TextView type;
        ImageView cover;
    }
}
