package com.metahumanz.pacilread

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.metahumanz.pacilread.model.BookRecord
import com.metahumanz.pacilread.model.BookmarkRecord
import com.metahumanz.pacilread.stats.ReadingStatsUtils
import com.metahumanz.pacilread.storage.JsonDatabase
import com.metahumanz.pacilread.storage.SettingsStore
import com.metahumanz.pacilread.theme.ThemeModeHelper
import com.metahumanz.pacilread.ui.LaunchSourceTransition
import com.metahumanz.pacilread.ui.TransitionMotionModeHelper
import java.util.Locale
import java.util.concurrent.ExecutorService

class HomeBookmarksPanelController(
    private val activity: Activity,
    private val databaseHelper: JsonDatabase,
    private val executor: ExecutorService,
) {
    private val listLayout: LinearLayout? = activity.findViewById(R.id.layout_home_bookmarks_list)
    private val emptyText: TextView? = activity.findViewById(R.id.text_home_bookmarks_empty)

    fun refreshIfVisible(currentPage: Int) {
        if (currentPage != HomeNavigationController.PAGE_BOOKMARKS) return
        executor.execute {
            val bookmarks = databaseHelper.bookmarks
            activity.runOnUiThread { render(bookmarks) }
        }
    }

    private fun render(bookmarks: List<BookmarkRecord>?) {
        val layout = listLayout ?: return
        val emptyView = emptyText ?: return
        layout.removeAllViews()
        val empty = bookmarks.isNullOrEmpty()
        emptyView.visibility = if (empty) View.VISIBLE else View.GONE
        if (empty) return
        for (bookmark in bookmarks) layout.addView(createBookmarkRow(bookmark))
    }

    private fun createBookmarkRow(bookmark: BookmarkRecord): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_app_input)
            setPadding(AppUiUtils.dp(activity, 14), AppUiUtils.dp(activity, 12), AppUiUtils.dp(activity, 10), AppUiUtils.dp(activity, 12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, AppUiUtils.dp(activity, 8), 0, 0) }
            isClickable = true
            isFocusable = true
            setOnClickListener { openBookmark(bookmark, it) }
        }
        val texts = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(activity).apply {
            text = ReadingStatsUtils.safeBookTitle(bookmark.bookTitle)
            setTextColor(ThemeModeHelper.resolveColor(activity, R.color.app_text_primary))
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        texts.addView(TextView(activity).apply {
            text = String.format(
                Locale.SIMPLIFIED_CHINESE,
                "%s · %.1f%%",
                if (bookmark.chapterTitle.isNullOrBlank()) "未命名章节" else bookmark.chapterTitle,
                bookmark.progressPercent,
            )
            setTextColor(ThemeModeHelper.resolveColor(activity, R.color.app_text_secondary))
            textSize = 13f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        texts.addView(TextView(activity).apply {
            text = if (bookmark.summary.isNullOrBlank()) "无摘要" else bookmark.summary
            setTextColor(ThemeModeHelper.resolveColor(activity, R.color.app_text_secondary))
            textSize = 13f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })
        val deleteButton = Button(activity).apply {
            text = "删除"
            textSize = 12f
            isAllCaps = false
            minWidth = 0
            minHeight = 0
            setPadding(AppUiUtils.dp(activity, 12), AppUiUtils.dp(activity, 8), AppUiUtils.dp(activity, 12), AppUiUtils.dp(activity, 8))
            setBackgroundResource(R.drawable.bg_app_danger_button)
            setTextColor(ThemeModeHelper.resolveColor(activity, R.color.app_button_danger_text))
            setOnClickListener { confirmDeleteBookmark(bookmark) }
        }
        row.addView(texts)
        row.addView(deleteButton)
        return row
    }

    private fun openBookmark(bookmark: BookmarkRecord, sourceView: View) {
        executor.execute {
            var book: BookRecord? = if (bookmark.bookId > 0L) databaseHelper.getBook(bookmark.bookId) else null
            if (book == null) book = databaseHelper.findBookByReadingStatsKey(bookmark.bookIdentity)
            val finalBook = book
            activity.runOnUiThread {
                if (finalBook == null) {
                    showToast("这本书已经不在当前设备的书架中")
                    return@runOnUiThread
                }
                val intent = Intent(activity, ReaderActivity::class.java)
                    .putExtra("book_id", finalBook.id)
                    .putExtra("bookmark_chapter_order_index", bookmark.chapterOrderIndex)
                    .putExtra("bookmark_chapter_offset", bookmark.chapterOffset)
                if (TransitionMotionModeHelper.isFluidMode(SettingsStore(activity))) {
                    LaunchSourceTransition.attachBoundsOnly(intent, sourceView)
                }
                activity.startActivity(intent)
            }
        }
    }

    private fun confirmDeleteBookmark(bookmark: BookmarkRecord) {
        AlertDialog.Builder(activity)
            .setTitle("删除书签")
            .setMessage("确定删除这个书签吗？")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                executor.execute {
                    databaseHelper.deleteBookmark(bookmark.id)
                    activity.runOnUiThread { refreshIfVisible(HomeNavigationController.PAGE_BOOKMARKS) }
                }
            }
            .show()
    }

    private fun showToast(message: String) = AppUiUtils.showToast(activity, message)
}
