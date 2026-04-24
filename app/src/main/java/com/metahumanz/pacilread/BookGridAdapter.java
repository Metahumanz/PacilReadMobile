package com.metahumanz.pacilread;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import com.metahumanz.pacilread.model.BookRecord;
import com.metahumanz.pacilread.ui.BookCoverViewHelper;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class BookGridAdapter extends BaseAdapter {
    private static final int TYPE_BOOK = 0;
    private static final int TYPE_ADD = 1;

    private final LayoutInflater inflater;
    private final DateFormat dateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.SIMPLIFIED_CHINESE);
    private final List<BookRecord> books = new ArrayList<>();
    private boolean showAddEntry = true;

    public BookGridAdapter(Context context) {
        this.inflater = LayoutInflater.from(context);
    }

    public void setItems(List<BookRecord> items) {
        books.clear();
        books.addAll(items);
        notifyDataSetChanged();
    }

    public void setShowAddEntry(boolean showAddEntry) {
        if (this.showAddEntry == showAddEntry) {
            return;
        }
        this.showAddEntry = showAddEntry;
        notifyDataSetChanged();
    }

    public boolean isAddPosition(int position) {
        return showAddEntry && position == books.size();
    }

    @Override
    public int getViewTypeCount() {
        return 2;
    }

    @Override
    public int getItemViewType(int position) {
        return isAddPosition(position) ? TYPE_ADD : TYPE_BOOK;
    }

    @Override
    public int getCount() {
        return books.size() + (showAddEntry ? 1 : 0);
    }

    @Override
    public BookRecord getItem(int position) {
        return isAddPosition(position) ? null : books.get(position);
    }

    @Override
    public long getItemId(int position) {
        BookRecord record = getItem(position);
        return record == null ? -1L : record.id;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (getItemViewType(position) == TYPE_ADD) {
            if (convertView == null) {
                convertView = inflater.inflate(R.layout.item_book_add, parent, false);
            }
            return convertView;
        }

        BookViewHolder holder;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_book_card, parent, false);
            holder = new BookViewHolder();
            holder.title = convertView.findViewById(R.id.text_title);
            holder.author = convertView.findViewById(R.id.text_author);
            holder.currentChapter = convertView.findViewById(R.id.text_current_chapter);
            holder.meta = convertView.findViewById(R.id.text_meta);
            holder.pin = convertView.findViewById(R.id.text_pin);
            holder.cover = convertView.findViewById(R.id.image_cover);
            holder.coverFallback = convertView.findViewById(R.id.text_cover_fallback);
            holder.type = convertView.findViewById(R.id.text_type);
            convertView.setTag(holder);
        } else {
            holder = (BookViewHolder) convertView.getTag();
        }

        BookRecord book = getItem(position);
        holder.title.setText(book.title == null || book.title.isBlank() ? "未命名书籍" : book.title);
        holder.author.setText(book.author == null || book.author.isBlank() ? "未知作者" : book.author);
        holder.currentChapter.setText(currentChapterText(book));
        holder.meta.setText(book.lastReadAt > 0
                ? "最近阅读 " + dateFormat.format(new Date(book.lastReadAt))
                : "尚未阅读");
        holder.pin.setVisibility(book.pinned ? View.VISIBLE : View.GONE);
        holder.type.setText(typeLabel(book.bookType));
        BookCoverViewHelper.bindCover(holder.cover, holder.coverFallback, book.coverPath, book.title);
        return convertView;
    }

    private String currentChapterText(BookRecord book) {
        if (book.chapterCount <= 0) {
            return "未生成章节";
        }
        return book.currentChapterTitle == null || book.currentChapterTitle.isBlank()
                ? "未命名章节"
                : book.currentChapterTitle.trim();
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

    private static class BookViewHolder {
        TextView title;
        TextView author;
        TextView currentChapter;
        TextView meta;
        TextView pin;
        TextView coverFallback;
        TextView type;
        ImageView cover;
    }
}
