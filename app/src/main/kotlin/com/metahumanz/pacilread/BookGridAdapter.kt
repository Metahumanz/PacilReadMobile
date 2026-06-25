package com.metahumanz.pacilread

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import com.metahumanz.pacilread.model.BookRecord
import com.metahumanz.pacilread.ui.BookCoverViewHelper
import java.text.DateFormat
import java.util.Date
import java.util.Locale

open class BookGridAdapter(context: Context) : BaseAdapter() {
    private val inflater = LayoutInflater.from(context)
    private val dateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.SIMPLIFIED_CHINESE)
    private val books = ArrayList<BookRecord>()
    private val selectedBookIds = HashSet<Long>()
    private var showAddEntry = true

    fun setItems(items: List<BookRecord>) {
        books.clear()
        books.addAll(items)
        notifyDataSetChanged()
    }

    fun setShowAddEntry(showAddEntry: Boolean) {
        if (this.showAddEntry == showAddEntry) return
        this.showAddEntry = showAddEntry
        notifyDataSetChanged()
    }

    fun setSelectedBookIds(selectedIds: Set<Long>?) {
        selectedBookIds.clear()
        if (selectedIds != null) selectedBookIds.addAll(selectedIds)
        notifyDataSetChanged()
    }

    fun isAddPosition(position: Int): Boolean = showAddEntry && position == books.size

    override fun getViewTypeCount(): Int = 2
    override fun getItemViewType(position: Int): Int = if (isAddPosition(position)) TYPE_ADD else TYPE_BOOK
    override fun getCount(): Int = books.size + if (showAddEntry) 1 else 0
    override fun getItem(position: Int): BookRecord? = if (isAddPosition(position)) null else books[position]
    override fun getItemId(position: Int): Long = getItem(position)?.id ?: -1L

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        var itemView = convertView
        if (getItemViewType(position) == TYPE_ADD) {
            if (itemView == null) itemView = inflater.inflate(R.layout.item_book_add, parent, false)
            return itemView
        }

        val holder: BookViewHolder
        if (itemView == null) {
            itemView = inflater.inflate(R.layout.item_book_card, parent, false)
            holder = BookViewHolder(
                itemView.findViewById(R.id.text_title),
                itemView.findViewById(R.id.text_author),
                itemView.findViewById(R.id.text_current_chapter),
                itemView.findViewById(R.id.text_meta),
                itemView.findViewById(R.id.text_pin),
                itemView.findViewById(R.id.text_cover_fallback),
                itemView.findViewById(R.id.text_type),
                itemView.findViewById(R.id.image_cover),
                itemView.findViewById(R.id.container_book_card),
            )
            itemView.tag = holder
        } else {
            holder = itemView.tag as BookViewHolder
        }

        val book = requireNotNull(getItem(position))
        holder.card.setBackgroundResource(
            if (selectedBookIds.contains(book.id)) R.drawable.bg_book_selected else R.drawable.bg_app_card,
        )
        holder.title.text = if (book.title.isNullOrBlank()) "未命名书籍" else book.title
        holder.author.text = if (book.author.isNullOrBlank()) "未知作者" else book.author
        holder.currentChapter.text = currentChapterText(book)
        holder.meta.text = if (book.lastReadAt > 0) {
            "最近阅读 ${dateFormat.format(Date(book.lastReadAt))}"
        } else {
            "尚未阅读"
        }
        holder.pin.visibility = if (book.pinned) View.VISIBLE else View.GONE
        holder.type.text = typeLabel(book.bookType)
        BookCoverViewHelper.bindCover(holder.cover, holder.coverFallback, book.coverPath, book.title)
        return itemView
    }

    private fun currentChapterText(book: BookRecord): String {
        if (book.chapterCount <= 0) return "未生成章节"
        val title = book.currentChapterTitle
        return if (title.isNullOrBlank()) "未命名章节" else title.trim()
    }

    private fun typeLabel(bookType: String?): String = when {
        "epub".equals(bookType, ignoreCase = true) -> "EPUB"
        "pdf".equals(bookType, ignoreCase = true) -> "PDF"
        else -> "TXT"
    }

    private class BookViewHolder(
        val title: TextView,
        val author: TextView,
        val currentChapter: TextView,
        val meta: TextView,
        val pin: TextView,
        val coverFallback: TextView,
        val type: TextView,
        val cover: ImageView,
        val card: View,
    )

    private companion object {
        const val TYPE_BOOK = 0
        const val TYPE_ADD = 1
    }
}
