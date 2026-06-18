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
import java.util.HashSet;
import java.util.Set;

public class BookListAdapter extends BaseAdapter {
    private final LayoutInflater inflater;
    private final DateFormat dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.SIMPLIFIED_CHINESE);
    private final List<BookRecord> books = new ArrayList<>();
    private final Set<Long> selectedBookIds = new HashSet<>();

    public BookListAdapter(Context context) {
        this.inflater = LayoutInflater.from(context);
    }

    public void setItems(List<BookRecord> items) {
        books.clear();
        books.addAll(items);
        notifyDataSetChanged();
    }

    public void setSelectedBookIds(Set<Long> selectedIds) {
        selectedBookIds.clear();
        if (selectedIds != null) selectedBookIds.addAll(selectedIds);
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
            holder.card = convertView.findViewById(R.id.container_book_card);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        BookRecord book = getItem(position);
        holder.card.setBackgroundResource(selectedBookIds.contains(book.id)
                ? R.drawable.bg_book_selected
                : R.drawable.bg_app_card);
        holder.title.setText(book.title == null || book.title.isBlank() ? "未命名书籍" : book.title);
        holder.author.setText("作者：" + (book.author == null || book.author.isBlank() ? "未知作者" : book.author));
        holder.currentChapter.setText(currentChapterText(book));
        holder.recentRead.setText(book.lastReadAt > 0
                ? "最近阅读：" + dateFormat.format(new Date(book.lastReadAt))
                : "最近阅读：尚未阅读");
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

    private static class ViewHolder {
        TextView title;
        TextView author;
        TextView currentChapter;
        TextView recentRead;
        TextView pin;
        TextView coverFallback;
        TextView type;
        ImageView cover;
        View card;
    }
}
