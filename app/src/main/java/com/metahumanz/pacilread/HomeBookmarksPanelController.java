package com.metahumanz.pacilread;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.metahumanz.pacilread.model.BookRecord;
import com.metahumanz.pacilread.model.BookmarkRecord;
import com.metahumanz.pacilread.stats.ReadingStatsUtils;
import com.metahumanz.pacilread.storage.ReaderDatabaseHelper;
import com.metahumanz.pacilread.theme.ThemeModeHelper;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;

public final class HomeBookmarksPanelController {
    private final Activity activity;
    private final ReaderDatabaseHelper databaseHelper;
    private final ExecutorService executor;
    private final LinearLayout listLayout;
    private final TextView emptyText;

    public HomeBookmarksPanelController(
            Activity activity,
            ReaderDatabaseHelper databaseHelper,
            ExecutorService executor
    ) {
        this.activity = activity;
        this.databaseHelper = databaseHelper;
        this.executor = executor;
        this.listLayout = activity.findViewById(R.id.layout_home_bookmarks_list);
        this.emptyText = activity.findViewById(R.id.text_home_bookmarks_empty);
    }

    public void refreshIfVisible(int currentPage) {
        if (currentPage != HomeNavigationController.PAGE_BOOKMARKS) {
            return;
        }
        executor.execute(() -> {
            List<BookmarkRecord> bookmarks = databaseHelper.getBookmarks();
            activity.runOnUiThread(() -> render(bookmarks));
        });
    }

    private void render(List<BookmarkRecord> bookmarks) {
        if (listLayout == null || emptyText == null) {
            return;
        }
        listLayout.removeAllViews();
        boolean empty = bookmarks == null || bookmarks.isEmpty();
        emptyText.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) {
            return;
        }
        for (BookmarkRecord bookmark : bookmarks) {
            listLayout.addView(createBookmarkRow(bookmark));
        }
    }

    private View createBookmarkRow(BookmarkRecord bookmark) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bg_app_input);
        row.setPadding(dp(14), dp(12), dp(10), dp(12));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        rowParams.setMargins(0, dp(8), 0, 0);
        row.setLayoutParams(rowParams);
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> openBookmark(bookmark));

        LinearLayout texts = new LinearLayout(activity);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(activity);
        title.setText(ReadingStatsUtils.safeBookTitle(bookmark.bookTitle));
        title.setTextColor(ThemeModeHelper.resolveColor(activity, R.color.app_text_primary));
        title.setTextSize(15f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);

        TextView meta = new TextView(activity);
        meta.setText(String.format(
                Locale.SIMPLIFIED_CHINESE,
                "%s · %.1f%%",
                bookmark.chapterTitle == null || bookmark.chapterTitle.isBlank() ? "未命名章节" : bookmark.chapterTitle,
                bookmark.progressPercent
        ));
        meta.setTextColor(ThemeModeHelper.resolveColor(activity, R.color.app_text_secondary));
        meta.setTextSize(13f);
        meta.setMaxLines(1);
        meta.setEllipsize(android.text.TextUtils.TruncateAt.END);

        TextView summary = new TextView(activity);
        summary.setText(bookmark.summary == null || bookmark.summary.isBlank() ? "无摘要" : bookmark.summary);
        summary.setTextColor(ThemeModeHelper.resolveColor(activity, R.color.app_text_secondary));
        summary.setTextSize(13f);
        summary.setMaxLines(2);
        summary.setEllipsize(android.text.TextUtils.TruncateAt.END);

        texts.addView(title);
        texts.addView(meta);
        texts.addView(summary);

        Button deleteButton = new Button(activity);
        deleteButton.setText("删除");
        deleteButton.setTextSize(12f);
        deleteButton.setAllCaps(false);
        deleteButton.setMinWidth(0);
        deleteButton.setMinHeight(0);
        deleteButton.setPadding(dp(12), dp(8), dp(12), dp(8));
        deleteButton.setBackgroundResource(R.drawable.bg_app_danger_button);
        deleteButton.setTextColor(ThemeModeHelper.resolveColor(activity, R.color.app_button_danger_text));
        deleteButton.setOnClickListener(v -> confirmDeleteBookmark(bookmark));

        row.addView(texts);
        row.addView(deleteButton);
        return row;
    }

    private void openBookmark(BookmarkRecord bookmark) {
        executor.execute(() -> {
            BookRecord book = bookmark.bookId > 0L ? databaseHelper.getBook(bookmark.bookId) : null;
            if (book == null) {
                book = databaseHelper.findBookByReadingStatsKey(bookmark.bookIdentity);
            }
            BookRecord finalBook = book;
            activity.runOnUiThread(() -> {
                if (finalBook == null) {
                    showToast("这本书已经不在当前设备的书架中");
                    return;
                }
                Intent intent = new Intent(activity, ReaderActivity.class);
                intent.putExtra("book_id", finalBook.id);
                intent.putExtra("bookmark_chapter_order_index", bookmark.chapterOrderIndex);
                intent.putExtra("bookmark_chapter_offset", bookmark.chapterOffset);
                activity.startActivity(intent);
            });
        });
    }

    private void confirmDeleteBookmark(BookmarkRecord bookmark) {
        new AlertDialog.Builder(activity)
                .setTitle("删除书签")
                .setMessage("确定删除这个书签吗？")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> executor.execute(() -> {
                    databaseHelper.deleteBookmark(bookmark.id);
                    activity.runOnUiThread(() -> refreshIfVisible(HomeNavigationController.PAGE_BOOKMARKS));
                }))
                .show();
    }

    private void showToast(String message) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(activity.getResources().getDisplayMetrics().density * value);
    }
}
