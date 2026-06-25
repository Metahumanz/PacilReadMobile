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

open class BookListAdapter(context: Context) : BaseAdapter() {
    private val inflater = LayoutInflater.from(context)
    private val dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.SIMPLIFIED_CHINESE)
    private val books: MutableList<BookRecord> = ArrayList()
    private val selectedBookIds: MutableSet<Long> = HashSet()

    fun setItems(items: List<BookRecord>) {
        books.clear()
        books.addAll(items)
        notifyDataSetChanged()
    }

    fun setSelectedBookIds(selectedIds: Set<Long>?) {
        selectedBookIds.clear()
        if (selectedIds != null) selectedBookIds.addAll(selectedIds)
        notifyDataSetChanged()
    }

    override fun getCount(): Int = books.size
    override fun getItem(position: Int): BookRecord = books[position]
    override fun getItemId(position: Int): Long = books[position].id

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val itemView: View
        val holder: ViewHolder
        if (convertView == null) {
            itemView = inflater.inflate(R.layout.item_book, parent, false)
            holder = ViewHolder().apply {
                title = itemView.findViewById(R.id.text_title)
                author = itemView.findViewById(R.id.text_author)
                currentChapter = itemView.findViewById(R.id.text_current_chapter)
                recentRead = itemView.findViewById(R.id.text_recent_read)
                pin = itemView.findViewById(R.id.text_pin)
                cover = itemView.findViewById(R.id.image_cover)
                coverFallback = itemView.findViewById(R.id.text_cover_fallback)
                type = itemView.findViewById(R.id.text_type)
                card = itemView.findViewById(R.id.container_book_card)
            }
            itemView.tag = holder
        } else {
            itemView = convertView
            holder = itemView.tag as ViewHolder
        }

        val book = getItem(position)
        holder.card.setBackgroundResource(if (selectedBookIds.contains(book.id)) R.drawable.bg_book_selected else R.drawable.bg_app_card)
        holder.title.text = if (book.title.isNullOrBlank()) "未命名书籍" else book.title
        holder.author.text = "作者：${if (book.author.isNullOrBlank()) "未知作者" else book.author}"
        holder.currentChapter.text = currentChapterText(book)
        holder.recentRead.text = if (book.lastReadAt > 0) {
            "最近阅读：${dateFormat.format(Date(book.lastReadAt))}"
        } else {
            "最近阅读：尚未阅读"
        }
        holder.pin.visibility = if (book.pinned) View.VISIBLE else View.GONE
        holder.type.text = typeLabel(book.bookType)
        BookCoverViewHelper.bindCover(holder.cover, holder.coverFallback, book.coverPath, book.title)
        return itemView
    }

    private fun currentChapterText(book: BookRecord): String {
        if (book.chapterCount <= 0) return "未生成章节"
        return if (book.currentChapterTitle.isNullOrBlank()) "未命名章节" else book.currentChapterTitle!!.trim()
    }

    private fun typeLabel(bookType: String?): String = when {
        bookType.equals("epub", ignoreCase = true) -> "EPUB"
        bookType.equals("pdf", ignoreCase = true) -> "PDF"
        else -> "TXT"
    }

    private class ViewHolder {
        lateinit var title: TextView
        lateinit var author: TextView
        lateinit var currentChapter: TextView
        lateinit var recentRead: TextView
        lateinit var pin: TextView
        lateinit var coverFallback: TextView
        lateinit var type: TextView
        lateinit var cover: ImageView
        lateinit var card: View
    }
}
