package com.metahumanz.pacilread.reader.modern.dialog

import android.app.AlertDialog
import android.graphics.Typeface
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.TextView
import com.metahumanz.pacilread.R
import com.metahumanz.pacilread.model.ReplacementRuleRecord
import com.metahumanz.pacilread.reader.modern.ModernReaderActivity
import com.metahumanz.pacilread.reader.modern.ReaderRuntime
import com.metahumanz.pacilread.reader.modern.ReaderSessionState
import com.metahumanz.pacilread.reader.modern.ReaderUiUtils
import com.metahumanz.pacilread.reader.modern.content.ReaderContentController
import com.metahumanz.pacilread.reader.modern.paging.ReaderNavigationController
import com.metahumanz.pacilread.reader.search.BookSearchIndex
import java.util.Locale
import java.util.regex.Pattern
import kotlin.math.max
import kotlin.math.min

class ReaderLibraryDialogs(
    private val activity: ModernReaderActivity,
    private val runtime: ReaderRuntime,
    private val state: ReaderSessionState,
    private val ui: ReaderUiUtils,
    private val dialogSupport: ReaderDialogSupport,
    private val content: ReaderContentController,
    private val navigation: ReaderNavigationController,
) {
    private val searchIndex = BookSearchIndex(activity, runtime.databaseHelper)
    private var searchGeneration = 0

    fun showTocDialog() {
        if (state.chapters.isEmpty()) return
        val contentView = LayoutInflater.from(activity).inflate(R.layout.dialog_toc, null, false)
        val contentContainer = contentView.findViewById<View>(R.id.toc_content)
        val tocBody = contentView.findViewById<FrameLayout>(R.id.toc_body)
        val listView = contentView.findViewById<ListView>(R.id.toc_list)
        val scrubberHost = contentView.findViewById<View>(R.id.toc_scrubber_host)
        val scrubberTrack = contentView.findViewById<View>(R.id.toc_scrubber_track)
        val scrubberThumb = contentView.findViewById<View>(R.id.toc_scrubber_thumb)
        val scrubberPreview = contentView.findViewById<TextView>(R.id.toc_scrubber_preview)
        val items = ArrayList<String>(state.chapters.size)
        for (i in state.chapters.indices) {
            items.add(String.format(Locale.SIMPLIFIED_CHINESE, "%03d  %s", i + 1, state.chapters[i].title))
        }
        val adapter: ArrayAdapter<String> = TocListAdapter(items, state.currentChapterIndex)
        listView.adapter = adapter
        val dialog = AlertDialog.Builder(activity).setView(contentView).create()
        dialogSupport.applyTocStyleFullscreenInsets(contentView, contentContainer)
        dialogSupport.addAlignedCloseButton(contentView, R.id.toc_title, contentContainer, dialog)
        attachListScrubber(
            listView,
            tocBody,
            scrubberHost,
            scrubberTrack,
            scrubberThumb,
            scrubberPreview,
            object : ScrubberItems {
                override fun size(): Int = items.size
                override fun previewText(index: Int): CharSequence = items[index]
            },
        )
        listView.setOnItemClickListener { _, _, position, _ ->
            dialog.dismiss()
            navigation.openChapterFromStart(position, true, if (position >= state.currentChapterIndex) 1 else -1)
        }
        dialogSupport.showImmersiveFullscreenDialog(dialog, state.controlsVisible)
        contentView.requestApplyInsets()
        listView.post {
            listView.setSelectionFromTop(state.currentChapterIndex, 0)
            positionScrubberThumb(
                scrubberTrack,
                scrubberThumb,
                fractionForIndex(state.currentChapterIndex, items.size),
            )
        }
    }

    fun showSearchDialog() {
        showSearchDialog("", false)
    }

    fun showSearchDialog(initialQuery: String?, autoRun: Boolean) {
        val contentView = LayoutInflater.from(activity).inflate(R.layout.dialog_search, null, false)
        val queryInput = contentView.findViewById<EditText>(R.id.search_query_input)
        val searchButton = contentView.findViewById<Button>(R.id.search_button_go)
        val resultCount = contentView.findViewById<TextView>(R.id.search_result_count)
        val searchBody = contentView.findViewById<FrameLayout>(R.id.search_result_body)
        val listView = contentView.findViewById<ListView>(R.id.search_result_list)
        val scrubberHost = contentView.findViewById<View>(R.id.search_scrubber_host)
        val scrubberTrack = contentView.findViewById<View>(R.id.search_scrubber_track)
        val scrubberThumb = contentView.findViewById<View>(R.id.search_scrubber_thumb)
        val scrubberPreview = contentView.findViewById<TextView>(R.id.search_scrubber_preview)
        val results = ArrayList<SearchResult>()
        val adapter = dialogSupport.buildDialogListAdapter(ArrayList())
        listView.adapter = adapter
        val dialog = AlertDialog.Builder(activity).setView(contentView).create()
        dialog.setOnDismissListener { searchGeneration++ }
        attachListScrubber(
            listView,
            searchBody,
            scrubberHost,
            scrubberTrack,
            scrubberThumb,
            scrubberPreview,
            object : ScrubberItems {
                override fun size(): Int = results.size
                override fun previewText(index: Int): CharSequence = searchScrubberPreviewText(results[index])
            },
        )
        listView.setOnItemClickListener { _, _, position, _ ->
            val result = results[position]
            dialog.dismiss()
            navigation.openChapter(
                result.chapterIndex,
                result.charOffset,
                true,
                if (result.chapterIndex >= state.currentChapterIndex) 1 else -1,
            )
        }
        val runSearch = Runnable {
            val query = queryInput.text.toString().trim()
            val generation = ++searchGeneration
            if (query.isEmpty()) {
                results.clear()
                adapter.clear()
                resultCount.text = "请输入关键词"
                refreshListScrubber(
                    listView,
                    scrubberHost,
                    scrubberTrack,
                    scrubberThumb,
                    scrubberPreview,
                    results.size,
                )
                return@Runnable
            }
            results.clear()
            adapter.clear()
            resultCount.text = if (searchIndex.isReady(state.bookId)) "正在搜索..." else "正在建立索引..."
            refreshListScrubber(
                listView,
                scrubberHost,
                scrubberTrack,
                scrubberThumb,
                scrubberPreview,
                results.size,
            )
            runtime.safeExecute(Runnable {
                val tempResults = ArrayList<SearchResult>()
                try {
                    val indexedResults = searchIndex.search(
                        state.bookId,
                        query,
                        BookSearchIndex.CancellationToken {
                            generation != searchGeneration || !activity.isReaderActive
                        },
                    )
                    for (result in indexedResults) {
                        tempResults.add(
                            SearchResult(
                                result.chapterIndex,
                                result.chapterTitle,
                                result.snippet,
                                result.charOffset,
                            ),
                        )
                    }
                } catch (error: Exception) {
                    activity.runOnReaderUiThread {
                        if (generation == searchGeneration) resultCount.text = "搜索失败: ${error.message}"
                    }
                    return@Runnable
                }
                activity.runOnReaderUiThread {
                    if (generation != searchGeneration) return@runOnReaderUiThread
                    results.clear()
                    results.addAll(tempResults)
                    adapter.clear()
                    for (result in results) adapter.add("${result.chapterTitle}\n${result.snippet}")
                    resultCount.text = if (results.isEmpty()) "没有找到匹配内容" else "找到 ${results.size} 条结果"
                    listView.setSelectionFromTop(0, 0)
                    listView.post {
                        refreshListScrubber(
                            listView,
                            scrubberHost,
                            scrubberTrack,
                            scrubberThumb,
                            scrubberPreview,
                            results.size,
                        )
                    }
                }
            }, "search reader text")
        }
        searchButton.setOnClickListener { runSearch.run() }
        val safeInitialQuery = initialQuery?.trim().orEmpty()
        if (safeInitialQuery.isNotEmpty()) {
            queryInput.setText(safeInitialQuery)
            queryInput.setSelection(queryInput.text.length)
        }
        dialogSupport.showStyledDialog(dialog)
        if (autoRun && safeInitialQuery.isNotEmpty()) resultCount.post(runSearch)
    }

    fun showRulesDialog() {
        showRulesDialog("")
    }

    fun showRulesDialog(initialPattern: String?) {
        val contentView = LayoutInflater.from(activity).inflate(R.layout.dialog_rules, null, false)
        val patternInput = contentView.findViewById<EditText>(R.id.rules_input_pattern)
        val replacementInput = contentView.findViewById<EditText>(R.id.rules_input_replacement)
        val globalCheck = contentView.findViewById<CheckBox>(R.id.rules_check_global)
        val regexCheck = contentView.findViewById<CheckBox>(R.id.rules_check_regex)
        val addButton = contentView.findViewById<Button>(R.id.rules_button_add)
        val hintText = contentView.findViewById<TextView>(R.id.rules_text_hint)
        val rulesBody = contentView.findViewById<FrameLayout>(R.id.rules_body)
        val listView = contentView.findViewById<ListView>(R.id.rules_list)
        val scrubberHost = contentView.findViewById<View>(R.id.rules_scrubber_host)
        val scrubberTrack = contentView.findViewById<View>(R.id.rules_scrubber_track)
        val scrubberThumb = contentView.findViewById<View>(R.id.rules_scrubber_thumb)
        val scrubberPreview = contentView.findViewById<TextView>(R.id.rules_scrubber_preview)
        val adapter = dialogSupport.buildDialogListAdapter(ArrayList())
        listView.adapter = adapter
        listView.isVerticalScrollBarEnabled = false
        listView.isFastScrollEnabled = false
        hintText.text = "点击列表切换启用状态，长按删除。"
        val safeInitialPattern = initialPattern.orEmpty()
        if (safeInitialPattern.isNotEmpty()) {
            patternInput.setText(safeInitialPattern)
            patternInput.setSelection(patternInput.text.length)
            replacementInput.requestFocus()
        }
        refreshRuleLabels(adapter)
        val dialog = AlertDialog.Builder(activity).setView(contentView).create()
        val refreshRulesScrubber = Runnable {
            refreshListScrubber(
                listView,
                scrubberHost,
                scrubberTrack,
                scrubberThumb,
                scrubberPreview,
                adapter.count,
            )
        }
        attachListScrubber(
            listView,
            rulesBody,
            scrubberHost,
            scrubberTrack,
            scrubberThumb,
            scrubberPreview,
            object : ScrubberItems {
                override fun size(): Int = adapter.count
                override fun previewText(index: Int): CharSequence {
                    if (index < 0 || index >= adapter.count) return ""
                    return adapter.getItem(index).orEmpty()
                }
            },
        )
        addButton.setOnClickListener {
            val pattern = patternInput.text.toString()
            if (pattern.trim().isEmpty()) {
                ui.showToast("请先输入查找内容")
                return@setOnClickListener
            }
            if (regexCheck.isChecked) {
                try {
                    Pattern.compile(pattern)
                } catch (error: Exception) {
                    ui.showToast("正则表达式语法错误: ${error.message}")
                    return@setOnClickListener
                }
            }
            val offset = content.currentCharOffset()
            runtime.safeExecute(Runnable {
                runtime.databaseHelper.addReplacementRule(
                    pattern,
                    replacementInput.text.toString(),
                    globalCheck.isChecked,
                    state.bookId,
                    regexCheck.isChecked,
                )
                val rules = runtime.databaseHelper.getReplacementRules(state.bookId)
                activity.runOnReaderUiThread {
                    state.replacementRules.clear()
                    state.replacementRules.addAll(rules)
                    content.clearAllReaderCaches()
                    refreshRuleLabels(adapter)
                    listView.post(refreshRulesScrubber)
                    patternInput.setText("")
                    replacementInput.setText("")
                    regexCheck.isChecked = false
                    navigation.openChapter(state.currentChapterIndex, offset, false, 0)
                }
            }, "add replacement rule")
        }
        listView.setOnItemClickListener { _, _, position, _ ->
            val rule = state.replacementRules[position]
            val offset = content.currentCharOffset()
            runtime.safeExecute(Runnable {
                runtime.databaseHelper.toggleReplacementRule(rule.id, !rule.active)
                val rules = runtime.databaseHelper.getReplacementRules(state.bookId)
                activity.runOnReaderUiThread {
                    state.replacementRules.clear()
                    state.replacementRules.addAll(rules)
                    content.clearAllReaderCaches()
                    refreshRuleLabels(adapter)
                    listView.post(refreshRulesScrubber)
                    navigation.openChapter(state.currentChapterIndex, offset, false, 0)
                }
            }, "toggle replacement rule")
        }
        listView.setOnItemLongClickListener { _, _, position, _ ->
            val rule = state.replacementRules[position]
            val offset = content.currentCharOffset()
            runtime.safeExecute(Runnable {
                runtime.databaseHelper.deleteReplacementRule(rule.id)
                val rules = runtime.databaseHelper.getReplacementRules(state.bookId)
                activity.runOnReaderUiThread {
                    state.replacementRules.clear()
                    state.replacementRules.addAll(rules)
                    content.clearAllReaderCaches()
                    refreshRuleLabels(adapter)
                    listView.post(refreshRulesScrubber)
                    navigation.openChapter(state.currentChapterIndex, offset, false, 0)
                }
            }, "delete replacement rule")
            true
        }
        dialogSupport.showStyledDialog(dialog)
    }

    private fun refreshRuleLabels(adapter: ArrayAdapter<String>) {
        adapter.clear()
        for (rule in state.replacementRules) {
            val replacement = if (rule.replacement.isNullOrEmpty()) "(删除)" else rule.replacement
            adapter.add("${if (rule.active) "[启用] " else "[停用] "}${rule.pattern} -> $replacement")
        }
    }

    private fun attachListScrubber(
        listView: ListView?,
        body: View?,
        scrubberHost: View?,
        scrubberTrack: View?,
        scrubberThumb: View?,
        scrubberPreview: TextView?,
        items: ScrubberItems?,
    ) {
        if (listView == null || body == null || scrubberHost == null || scrubberTrack == null ||
            scrubberThumb == null || scrubberPreview == null || items == null
        ) return
        var scrubberDragging = false
        var lastDraggedIndex = -1
        listView.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) = Unit

            override fun onScroll(view: AbsListView?, firstVisibleItem: Int, visibleItemCount: Int, totalItemCount: Int) {
                if (!scrubberDragging) {
                    refreshListScrubber(
                        listView,
                        scrubberHost,
                        scrubberTrack,
                        scrubberThumb,
                        scrubberPreview,
                        items.size(),
                    )
                }
            }
        })
        scrubberHost.setOnTouchListener { view, event ->
            val action = event.actionMasked
            if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_UP) {
                scrubberDragging = false
                lastDraggedIndex = -1
                scrubberPreview.visibility = View.INVISIBLE
                view.parent.requestDisallowInterceptTouchEvent(false)
                view.post {
                    refreshListScrubber(
                        listView,
                        scrubberHost,
                        scrubberTrack,
                        scrubberThumb,
                        scrubberPreview,
                        items.size(),
                    )
                }
                return@setOnTouchListener true
            }
            if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_MOVE) return@setOnTouchListener false
            val itemCount = items.size()
            if (itemCount <= 1) {
                scrubberPreview.visibility = View.INVISIBLE
                return@setOnTouchListener false
            }
            scrubberDragging = true
            view.parent.requestDisallowInterceptTouchEvent(true)
            val fraction = touchFractionForScrubber(event, scrubberTrack)
            val index = fractionToItemIndex(fraction, itemCount)
            positionScrubberThumb(scrubberTrack, scrubberThumb, fraction)
            if (index != lastDraggedIndex) {
                lastDraggedIndex = index
                listView.setSelectionFromTop(index, 0)
            }
            scrubberPreview.text = items.previewText(index)
            scrubberPreview.visibility = View.VISIBLE
            positionScrubberPreview(scrubberPreview, body, scrubberTrack, fraction)
            true
        }
        refreshListScrubber(
            listView,
            scrubberHost,
            scrubberTrack,
            scrubberThumb,
            scrubberPreview,
            items.size(),
        )
    }

    private fun refreshListScrubber(
        listView: ListView,
        scrubberHost: View?,
        scrubberTrack: View?,
        scrubberThumb: View?,
        scrubberPreview: TextView?,
        itemCount: Int,
    ) {
        if (scrubberHost == null || scrubberTrack == null || scrubberThumb == null || scrubberPreview == null) return
        if (itemCount <= 1) {
            scrubberHost.visibility = View.INVISIBLE
            scrubberPreview.visibility = View.INVISIBLE
            positionScrubberThumb(scrubberTrack, scrubberThumb, 0f)
            return
        }
        scrubberHost.visibility = View.VISIBLE
        positionScrubberThumb(scrubberTrack, scrubberThumb, firstVisibleFraction(listView, itemCount))
    }

    private fun positionScrubberPreview(preview: TextView, body: View, scrubberTrack: View, fraction: Float) {
        if (body.height <= 0) return
        preview.measure(
            View.MeasureSpec.makeMeasureSpec(body.width, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val anchorY = scrubberTrack.y + clampFraction(fraction) * max(scrubberTrack.height, 1)
        val targetY = anchorY - preview.measuredHeight / 2f
        val maxY = max(body.height - preview.measuredHeight, 0)
        preview.y = max(0f, min(targetY, maxY.toFloat()))
    }

    private fun positionScrubberThumb(scrubberTrack: View, scrubberThumb: View, fraction: Float) {
        scrubberTrack.post {
            val trackTop = scrubberTrack.y
            val travel = max(scrubberTrack.height - scrubberThumb.height, 0)
            scrubberThumb.y = trackTop + travel * clampFraction(fraction)
        }
    }

    private fun touchFractionForScrubber(event: MotionEvent, scrubberTrack: View): Float =
        clampFraction((event.y - scrubberTrack.y) / max(scrubberTrack.height, 1))

    private fun firstVisibleFraction(listView: ListView, itemCount: Int): Float {
        if (itemCount <= 1) return 0f
        val firstChild = listView.getChildAt(0)
        var firstRowOffset = 0f
        if (firstChild != null && firstChild.height > 0) firstRowOffset = -firstChild.top / firstChild.height.toFloat()
        return clampFraction((listView.firstVisiblePosition + firstRowOffset) / (itemCount - 1f))
    }

    private fun fractionForIndex(index: Int, itemCount: Int): Float =
        if (itemCount <= 1) 0f else clampFraction(index / (itemCount - 1f))

    private fun fractionToItemIndex(fraction: Float, itemCount: Int): Int =
        if (itemCount <= 1) 0 else ui.clamp(Math.round(clampFraction(fraction) * (itemCount - 1)), 0, itemCount - 1)

    private fun clampFraction(fraction: Float): Float = max(0f, min(1f, fraction))

    private fun searchScrubberPreviewText(result: SearchResult?): String {
        if (result == null) return ""
        return String.format(
            Locale.SIMPLIFIED_CHINESE,
            "%03d  %s\n%s",
            result.chapterIndex + 1,
            result.chapterTitle,
            result.snippet,
        )
    }

    private interface ScrubberItems {
        fun size(): Int
        fun previewText(index: Int): CharSequence
    }

    private class SearchResult(
        val chapterIndex: Int,
        val chapterTitle: String?,
        val snippet: String,
        val charOffset: Int,
    )

    private inner class TocListAdapter(items: List<String>, private val currentChapterIndex: Int) :
        ArrayAdapter<String>(activity, R.layout.item_toc_list_row, R.id.toc_row_text, items) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_toc_list_row, parent, false)
            val rowContent = view.findViewById<View>(R.id.toc_row_content)
            val indicator = view.findViewById<View>(R.id.toc_row_indicator)
            val textView = view.findViewById<TextView>(R.id.toc_row_text)
            textView.text = getItem(position)
            val isCurrent = position == currentChapterIndex
            rowContent.setBackgroundResource(if (isCurrent) R.drawable.bg_toc_row_current else 0)
            indicator.visibility = if (isCurrent) View.VISIBLE else View.INVISIBLE
            textView.setTextColor(ui.themeColor(if (isCurrent) R.color.primary else R.color.on_surface))
            textView.setTypeface(Typeface.DEFAULT, if (isCurrent) Typeface.BOLD else Typeface.NORMAL)
            textView.gravity = Gravity.CENTER_VERTICAL
            return view
        }
    }
}
