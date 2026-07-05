package com.metahumanz.pacilread.reader.modern.selection

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.metahumanz.pacilread.R
import com.metahumanz.pacilread.reader.JustifiedPageTextView
import com.metahumanz.pacilread.reader.PageSlice
import com.metahumanz.pacilread.reader.modern.ModernReaderActivity
import com.metahumanz.pacilread.reader.modern.ReaderRuntime
import com.metahumanz.pacilread.reader.modern.ReaderSessionState
import com.metahumanz.pacilread.reader.modern.ReaderUiUtils
import com.metahumanz.pacilread.reader.modern.ReaderViewRefs
import com.metahumanz.pacilread.reader.modern.content.ReaderContentController
import com.metahumanz.pacilread.reader.modern.dialog.ReaderLibraryDialogs
import com.metahumanz.pacilread.reader.modern.tts.ReaderTtsController
import com.metahumanz.pacilread.reader.share.QuoteShareCard
import com.metahumanz.pacilread.theme.ThemeModeHelper
import com.metahumanz.pacilread.ui.PredictiveBackScaleController
import com.metahumanz.pacilread.ui.PredictiveDialogDismissController
import com.metahumanz.pacilread.ui.TransitionMotionModeHelper
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class ReaderTextSelectionController(
    private val activity: ModernReaderActivity,
    private val runtime: ReaderRuntime,
    private val views: ReaderViewRefs,
    private val state: ReaderSessionState,
    private val ui: ReaderUiUtils,
    private val content: ReaderContentController,
) {
    private val touchSlop = ViewConfiguration.get(activity).scaledTouchSlop
    private val longPressRunnable = Runnable(::beginSelectionFromPending)
    private var libraryDialogs: ReaderLibraryDialogs? = null
    private var tts: ReaderTtsController? = null
    private var popupWindow: PopupWindow? = null
    private var popupAnimatingDismiss = false
    private var pendingTarget: Target? = null
    private var activeTarget: Target? = null
    private var longPressPending = false
    private var selectionActive = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var anchorStart = 0
    private var anchorEnd = 0
    private var selectionStart = 0
    private var selectionEnd = 0
    private var draggingHandle = HANDLE_NONE
    private var pendingSaveCard: QuoteShareCard.GeneratedCard? = null

    fun attachControllers(libraryDialogs: ReaderLibraryDialogs, tts: ReaderTtsController) {
        this.libraryDialogs = libraryDialogs
        this.tts = tts
    }

    fun handleTouchEvent(event: MotionEvent?): Boolean {
        event ?: return false
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN && selectionActive && !isInsideActiveText(event)) {
            clearSelection()
            return true
        }
        if (state.controlsVisible || state.isAnimating || state.interactivePaging || state.chapters.isEmpty()) {
            cancelPendingLongPress()
            return false
        }
        return when (action) {
            MotionEvent.ACTION_DOWN -> handleDown(event)
            MotionEvent.ACTION_MOVE -> handleMove(event)
            MotionEvent.ACTION_UP -> handleUp()
            MotionEvent.ACTION_CANCEL -> { cancelPendingLongPress(); selectionActive }
            else -> selectionActive
        }
    }

    fun hasSelection() = selectionActive

    fun clearSelection() {
        cancelPendingLongPress()
        draggingHandle = HANDLE_NONE
        activeTarget?.textView?.clearSelectionHighlight()
        activeTarget = null
        selectionActive = false
        state.pagingGestureCandidate = false
        selectionStart = -1; selectionEnd = -1; anchorStart = -1; anchorEnd = -1
        dismissPopup()
    }

    private fun handleDown(event: MotionEvent): Boolean {
        if (selectionActive) {
            lastRawX = event.rawX; lastRawY = event.rawY; state.pagingGestureCandidate = false
            val handle = hitTestHandle(event)
            if (handle != HANDLE_NONE) { draggingHandle = handle; dismissPopup(); return true }
            if (isInsideActiveText(event)) {
                val tapOffset = bodyOffsetForTouch(activeTarget, event.rawX, event.rawY, false)
                if (tapOffset in selectionStart until selectionEnd) dismissPopup() else clearSelection()
                return true
            }
            clearSelection()
            return true
        }
        val target = findTextTarget(event) ?: return false
        pendingTarget = target
        longPressPending = true
        downRawX = event.rawX; downRawY = event.rawY; lastRawX = downRawX; lastRawY = downRawY
        target.textView.postDelayed(longPressRunnable, LONG_PRESS_TIMEOUT_MS)
        return false
    }

    private fun handleMove(event: MotionEvent): Boolean {
        lastRawX = event.rawX; lastRawY = event.rawY
        if (longPressPending) {
            val dx = event.rawX - downRawX; val dy = event.rawY - downRawY
            if (dx * dx + dy * dy > touchSlop * touchSlop) cancelPendingLongPress()
            return false
        }
        if (!selectionActive) return false
        state.pagingGestureCandidate = false
        if (draggingHandle != HANDLE_NONE) updateHandleDrag(event.rawX, event.rawY) else updateSelectionFromTouch(event.rawX, event.rawY)
        return true
    }

    private fun handleUp(): Boolean {
        if (longPressPending) { cancelPendingLongPress(); return false }
        if (draggingHandle != HANDLE_NONE) { draggingHandle = HANDLE_NONE; showOrUpdatePopup(); return true }
        if (!selectionActive) return false
        state.pagingGestureCandidate = false
        showOrUpdatePopup()
        return true
    }

    private fun beginSelectionFromPending() {
        if (!longPressPending) return
        val target = pendingTarget ?: return
        longPressPending = false; pendingTarget = null
        val localOffset = bodyOffsetForTouch(target, downRawX, downRawY, false)
        if (localOffset < 0) return
        val range = wordRangeFor(target, localOffset) ?: return
        activity.ensureLivePageLayerForTextSelection()
        activeTarget = target
        selectionActive = true
        state.pagingGestureCandidate = false
        anchorStart = range.start; anchorEnd = range.end
        applySelectionRange(range.start, range.end)
        target.textView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        activity.markReadingActivity()
        showOrUpdatePopup()
    }

    private fun updateSelectionFromTouch(rawX: Float, rawY: Float) {
        val target = activeTarget ?: return
        val focus = bodyOffsetForTouch(target, rawX, rawY, true)
        if (focus < 0) return
        var start: Int
        var end: Int
        when {
            focus < anchorStart -> { start = focus; end = anchorEnd }
            focus > anchorEnd -> { start = anchorStart; end = focus }
            else -> { start = anchorStart; end = anchorEnd }
        }
        val bodyStart = max(target.slice.bodyStartInSlice, 0)
        val bodyEnd = max(bodyStart, target.slice.bodyEndInSlice)
        if (end <= start) end = min(bodyEnd, start + 1)
        applySelectionRange(start, end)
        showOrUpdatePopup()
    }

    private fun hitTestHandle(event: MotionEvent): Int {
        val target = activeTarget ?: return HANDLE_NONE
        if (target.textView.getSelectionHandleScreenBounds(selectionStart)?.contains(event.rawX, event.rawY) == true) return HANDLE_START
        if (target.textView.getSelectionHandleScreenBounds(selectionEnd)?.contains(event.rawX, event.rawY) == true) return HANDLE_END
        return HANDLE_NONE
    }

    private fun updateHandleDrag(rawX: Float, rawY: Float) {
        val target = activeTarget ?: return
        val focus = bodyOffsetForTouch(target, rawX, rawY, true)
        if (focus < 0) return
        val bodyStart = max(target.slice.bodyStartInSlice, 0)
        val bodyEnd = max(bodyStart, target.slice.bodyEndInSlice)
        if (draggingHandle == HANDLE_START) {
            val newStart = ui.clamp(focus, bodyStart, selectionEnd - 1)
            applySelectionRange(newStart, selectionEnd); anchorStart = newStart; anchorEnd = selectionEnd
        } else {
            val newEnd = ui.clamp(focus, selectionStart + 1, bodyEnd)
            applySelectionRange(selectionStart, newEnd); anchorStart = selectionStart; anchorEnd = newEnd
        }
    }

    private fun applySelectionRange(start: Int, end: Int) {
        val target = activeTarget ?: return
        val bodyStart = max(target.slice.bodyStartInSlice, 0)
        val bodyEnd = max(bodyStart, target.slice.bodyEndInSlice)
        selectionStart = ui.clamp(start, bodyStart, bodyEnd)
        selectionEnd = ui.clamp(end, selectionStart, bodyEnd)
        target.textView.setSelectionHighlightRange(selectionStart, selectionEnd)
    }

    private fun findTextTarget(event: MotionEvent?): Target? {
        if (event == null || state.currentChapterIndex !in state.chapters.indices) return null
        val navigationPages = content.getNavigationPagesForPage(
            state.currentChapterIndex,
            state.currentPageIndex,
            "selection_find_text_target",
        )
        val pages = navigationPages.pages
        if (pages.isEmpty()) return null
        if (isInsideView(event, views.pageBodyCurrent)) {
            val pageIndex = ui.clamp(state.currentPageIndex, 0, pages.size - 1)
            val slice = pages[pageIndex]
            return if (slice.hasBodyText()) Target(views.pageBodyCurrent, state.currentChapterIndex, pageIndex, slice) else null
        }
        if (isInsideView(event, views.pageBodyCurrentRight)) {
            val pageIndex = state.currentPageIndex + 1
            if (pageIndex in pages.indices) {
                val slice = pages[pageIndex]
                return if (slice.hasBodyText()) Target(views.pageBodyCurrentRight, state.currentChapterIndex, pageIndex, slice) else null
            }
        }
        return null
    }

    private fun isInsideActiveText(event: MotionEvent) = activeTarget?.let { isInsideView(event, it.textView) } == true
    private fun isInsideView(event: MotionEvent?, view: View?): Boolean {
        if (event == null || view == null || !view.isShown || view.width <= 0 || view.height <= 0) return false
        val location = IntArray(2); view.getLocationOnScreen(location)
        return event.rawX >= location[0] && event.rawX <= location[0] + view.width && event.rawY >= location[1] && event.rawY <= location[1] + view.height
    }

    private fun bodyOffsetForTouch(target: Target?, rawX: Float, rawY: Float, clampToBody: Boolean): Int {
        if (target == null || !target.slice.hasBodyText()) return -1
        val location = IntArray(2); target.textView.getLocationOnScreen(location)
        val localOffset = target.textView.offsetForTouch(rawX - location[0], rawY - location[1])
        val bodyStart = max(target.slice.bodyStartInSlice, 0); val bodyEnd = max(bodyStart, target.slice.bodyEndInSlice)
        if (bodyEnd <= bodyStart) return -1
        if (clampToBody) return ui.clamp(localOffset, bodyStart, bodyEnd)
        return if (localOffset in bodyStart until bodyEnd) localOffset else -1
    }

    private fun wordRangeFor(target: Target, localOffset: Int): WordRange? {
        val text = target.textView.text
        if (text.isNullOrEmpty()) return null
        val bodyStart = max(target.slice.bodyStartInSlice, 0); val bodyEnd = max(bodyStart, target.slice.bodyEndInSlice)
        if (bodyEnd <= bodyStart) return null
        val source = text.toString()
        var offset = ui.clamp(localOffset, bodyStart, bodyEnd - 1)
        if (source[offset].isWhitespace()) {
            if (offset + 1 < bodyEnd && !source[offset + 1].isWhitespace()) offset++
            else if (offset > bodyStart && !source[offset - 1].isWhitespace()) offset--
        }
        val iterator = BreakIterator.getWordInstance(Locale.getDefault()).apply { setText(source) }
        var start = iterator.preceding(offset + 1); var end = iterator.following(offset)
        if (start == BreakIterator.DONE || end == BreakIterator.DONE || start >= end) return fallbackRange(source, offset, bodyStart, bodyEnd)
        start = ui.clamp(start, bodyStart, bodyEnd); end = ui.clamp(end, bodyStart, bodyEnd)
        while (start < end && source[start].isWhitespace()) start++
        while (end > start && source[end - 1].isWhitespace()) end--
        return if (start >= end) fallbackRange(source, offset, bodyStart, bodyEnd) else WordRange(start, end)
    }

    private fun fallbackRange(source: String, offset: Int, bodyStart: Int, bodyEnd: Int): WordRange {
        val safeOffset = ui.clamp(offset, bodyStart, bodyEnd - 1)
        val end = safeOffset + Character.charCount(source.codePointAt(safeOffset))
        return WordRange(safeOffset, ui.clamp(end, safeOffset + 1, bodyEnd))
    }

    private fun showOrUpdatePopup() {
        if (!selectionActive || selectedText().isEmpty()) { dismissPopup(); return }
        ensurePopupWindow()
        val popup = popupWindow ?: return
        val root = views.readerRoot
        if (root.width <= 0 || root.height <= 0) return
        val wasShowing = popup.isShowing
        val shouldAnimateOpen = !wasShowing || popupAnimatingDismiss
        if (!wasShowing) { popupAnimatingDismiss = false; popup.showAtLocation(root, Gravity.NO_GRAVITY, 0, 0) }
        val popupContent = popup.contentView
        if (shouldAnimateOpen) { popupContent.animate().cancel(); popupAnimatingDismiss = false }
        popupContent.measure(View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(root.height, View.MeasureSpec.AT_MOST))
        val popupWidth = popupContent.measuredWidth; val popupHeight = popupContent.measuredHeight
        val rootLocation = IntArray(2); root.getLocationOnScreen(rootLocation)
        val margin = ui.dp(8)
        val avoidanceRect = selectionAvoidanceRect(rootLocation)
        var x = (lastRawX - rootLocation[0] - popupWidth / 2f).roundToInt()
        var y = (lastRawY - rootLocation[1] - popupHeight - ui.dp(18)).roundToInt()
        if (avoidanceRect != null) {
            x = (avoidanceRect.centerX() - popupWidth / 2f).roundToInt()
            val gap = ui.dp(12)
            val topY = (avoidanceRect.top - popupHeight - gap).roundToInt()
            val bottomY = (avoidanceRect.bottom + gap).roundToInt()
            val topFits = topY >= margin
            val bottomFits = bottomY + popupHeight <= root.height - margin
            val topSpace = avoidanceRect.top - margin - gap
            val bottomSpace = root.height - margin - avoidanceRect.bottom - gap
            y = when {
                topFits -> topY
                bottomFits -> bottomY
                topSpace >= bottomSpace -> margin
                else -> root.height - popupHeight - margin
            }
        }
        x = ui.clamp(x, margin, max(margin, root.width - popupWidth - margin))
        y = ui.clamp(y, margin, max(margin, root.height - popupHeight - margin))
        popup.update(x, y, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        if (shouldAnimateOpen) {
            popupContent.pivotX = popupWidth / 2f; popupContent.pivotY = popupHeight / 2f
            popupContent.scaleX = PredictiveBackScaleController.READER_MIN_SCALE; popupContent.scaleY = PredictiveBackScaleController.READER_MIN_SCALE; popupContent.alpha = 0f
            popupContent.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(180).setInterpolator(DecelerateInterpolator()).start()
        }
    }

    private fun selectionAvoidanceRect(rootLocation: IntArray): RectF? {
        val target = activeTarget ?: return null
        val rect = target.textView.getSelectionHighlightScreenBounds() ?: return null
        target.textView.getSelectionHandleScreenBounds(selectionStart)?.let(rect::union)
        target.textView.getSelectionHandleScreenBounds(selectionEnd)?.let(rect::union)
        rect.inset(-ui.dp(10).toFloat(), -ui.dp(10).toFloat())
        rect.offset(-rootLocation[0].toFloat(), -rootLocation[1].toFloat())
        return rect
    }

    private fun ensurePopupWindow() {
        if (popupWindow != null) return
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(ui.dp(8), ui.dp(7), ui.dp(8), ui.dp(7)); setBackgroundResource(R.drawable.bg_reader_menu_panel_solid)
        }
        val row1 = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            addView(createActionButton("复制", ::copySelection, true)); addView(createActionButton("分享", ::shareSelection, true)); addView(createActionButton("替换", ::replaceSelection, false))
        }
        container.addView(row1)
        val row2 = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            addView(createActionButton("搜索", ::searchSelection, true)); addView(createActionButton("朗读", ::speakSelection, true)); addView(createActionButton("编辑", ::editSelection, false))
        }
        container.addView(row2, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(4) })
        popupWindow = PopupWindow(container, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, false).apply {
            isOutsideTouchable = false; setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); isClippingEnabled = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) elevation = ui.dp(8).toFloat()
        }
    }

    private fun createActionButton(text: String, action: () -> Unit, marginEnd: Boolean) = Button(activity).apply {
        this.text = text; isAllCaps = false; textSize = 12f; minWidth = 0; minHeight = ui.dp(38)
        setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8)); setBackgroundResource(R.drawable.bg_reader_menu_button_solid); setTextColor(ui.themeColor(R.color.on_surface))
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { if (marginEnd) setMargins(0, 0, ui.dp(6), 0) }
        setOnClickListener { dismissPopup(); action() }
    }

    private fun copySelection() {
        val text = selectedText(); if (text.isEmpty()) return
        (activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText("选中文字", text))
        ui.showToast("已复制"); clearSelection()
    }
    private fun replaceSelection() {
        val text = selectedText(); val dialogs = libraryDialogs ?: return
        if (text.isEmpty()) return
        clearSelection(); dialogs.showRulesDialog(text)
    }

    private fun shareSelection() {
        val snapshot = selectionSnapshot() ?: return
        clearSelection()
        shareSelectionSnapshot(snapshot)
    }

    private fun editSelection() {
        val snapshot = selectionSnapshot() ?: return
        clearSelection()
        showEditSelectionDialog(snapshot)
    }

    private fun selectionSnapshot(): SelectionSnapshot? {
        val text = selectedText(); val target = activeTarget ?: return null
        if (text.isEmpty()) return null
        val chapterIndex = target.chapterIndex; val chapterStart = selectedChapterOffset()
        val excerpt = QuoteShareCard.contextExcerpt(content.getProcessedChapterText(chapterIndex), chapterStart, chapterStart + text.length)
        return SelectionSnapshot(
            text = text,
            contextBefore = excerpt.before,
            contextAfter = excerpt.after,
            title = state.book?.title ?: "",
            author = state.book?.author ?: "",
            chapter = state.chapters.getOrNull(chapterIndex)?.title ?: "",
        )
    }

    private fun shareSelectionSnapshot(snapshot: SelectionSnapshot) {
        val progressDialog = showShareGenerationDialog()
        runtime.safeExecute(Runnable {
            try {
                val card = QuoteShareCard.generate(activity, snapshot.text, snapshot.contextBefore, snapshot.contextAfter, snapshot.title, snapshot.author, snapshot.chapter)
                if (!activity.isReaderActive) { card.recyclePreview(); return@Runnable }
                activity.runOnReaderUiThread { progressDialog.dismiss(); showSharePreview(card) }
            } catch (error: Exception) {
                activity.runOnReaderUiThread { progressDialog.dismiss(); ui.showToast("生成分享卡失败: ${error.message}") }
            }
        }, "render quote share card")
    }

    private fun showEditSelectionDialog(snapshot: SelectionSnapshot) {
        val contentView = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundResource(R.drawable.bg_app_dialog)
            val padding = ui.dp(18); setPadding(padding, padding, padding, padding)
        }
        val headerRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val titleColumn = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        titleColumn.addView(TextView(activity).apply {
            text = "文字提取与复制"; setTextColor(ThemeModeHelper.resolveColor(activity, R.color.app_text_primary)); textSize = 22f; setTypeface(null, Typeface.BOLD)
        })
        titleColumn.addView(TextView(activity).apply {
            text = "可先调整选中文字，再复制或生成分享图"; setTextColor(ThemeModeHelper.resolveColor(activity, R.color.app_text_muted)); textSize = 13f
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, ui.dp(4), 0, 0) })
        headerRow.addView(titleColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val closeButton = Button(activity).apply {
            text = "×"; isAllCaps = false; minWidth = 0; minHeight = ui.dp(42); minimumHeight = ui.dp(42); textSize = 20f; gravity = Gravity.CENTER
            setPadding(0, 0, 0, ui.dp(2)); setBackgroundResource(R.drawable.bg_app_outline_button)
            setTextColor(ThemeModeHelper.resolveColor(activity, R.color.app_button_outline_text))
        }
        headerRow.addView(closeButton, LinearLayout.LayoutParams(ui.dp(42), ui.dp(42)).apply { marginStart = ui.dp(12) })
        contentView.addView(headerRow)

        val editText = EditText(activity).apply {
            setText(snapshot.text); setSelection(text?.length ?: 0)
            setTextColor(ThemeModeHelper.resolveColor(activity, R.color.app_text_primary))
            setHintTextColor(ThemeModeHelper.resolveColor(activity, R.color.app_text_muted))
            textSize = 15f; gravity = Gravity.START or Gravity.TOP
            setSingleLine(false); setHorizontallyScrolling(false); minLines = 8; maxLines = 14
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12)); setBackgroundResource(R.drawable.bg_app_input)
        }
        contentView.addView(editText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(260)).apply { setMargins(0, ui.dp(16), 0, 0) })

        val actionRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val cancelButton = previewActionButton("取消", false)
        val copyButton = previewActionButton("复制全文", false)
        val shareButton = previewActionButton("分享", true)
        actionRow.addView(cancelButton, weightedPreviewButtonParams(0))
        actionRow.addView(copyButton, weightedPreviewButtonParams(ui.dp(8)))
        actionRow.addView(shareButton, weightedPreviewButtonParams(ui.dp(8)))
        contentView.addView(actionRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, ui.dp(16), 0, 0) })

        val dialog = AlertDialog.Builder(activity).setView(contentView).create()
        closeButton.setOnClickListener { dialog.dismiss() }
        cancelButton.setOnClickListener { dialog.dismiss() }
        copyButton.setOnClickListener {
            val editedText = editText.text?.toString().orEmpty()
            if (editedText.isEmpty()) { ui.showToast("没有可复制的文字"); return@setOnClickListener }
            (activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText("选中文字", editedText))
            ui.showToast("已复制"); dialog.dismiss()
        }
        shareButton.setOnClickListener {
            val editedText = editText.text?.toString().orEmpty()
            if (editedText.trim().isEmpty()) { ui.showToast("没有可分享的文字"); return@setOnClickListener }
            dialog.dismiss(); shareSelectionSnapshot(snapshot.copy(text = editedText))
        }
        dialog.show()
        val window = dialog.window
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); window?.setWindowAnimations(R.style.AppPopDialogAnimation)
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val dialogWidth = min(activity.resources.displayMetrics.widthPixels - ui.dp(32), ui.dp(620))
        window?.setLayout(max(ui.dp(280), dialogWidth), ViewGroup.LayoutParams.WRAP_CONTENT)
        val backRegistration = PredictiveDialogDismissController.install(dialog, window, TransitionMotionModeHelper.isFluidMode(runtime.settingsStore), null)
        dialog.setOnDismissListener { backRegistration.unregister() }
        editText.post {
            editText.requestFocus()
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun showShareGenerationDialog(): AlertDialog {
        val contentView = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(22), ui.dp(18), ui.dp(22), ui.dp(18))
        }
        contentView.addView(ProgressBar(activity), LinearLayout.LayoutParams(ui.dp(32), ui.dp(32)))
        contentView.addView(TextView(activity).apply { text = "正在整理上下文并生成分享图片…"; setTextColor(ui.themeColor(R.color.on_surface)); textSize = 14f },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ui.dp(14) })
        return AlertDialog.Builder(activity).setView(contentView).setCancelable(false).create().apply {
            setCanceledOnTouchOutside(false); show(); window?.setBackgroundDrawableResource(R.drawable.bg_app_dialog)
        }
    }

    private fun showSharePreview(card: QuoteShareCard.GeneratedCard?) {
        val bitmap = card?.bitmap
        if (bitmap == null || bitmap.isRecycled) { ui.showToast("分享图片预览失败"); return }
        val scrollView = ScrollView(activity).apply { isFillViewport = false; overScrollMode = View.OVER_SCROLL_NEVER; isVerticalScrollBarEnabled = false }
        val contentView = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundResource(R.drawable.bg_app_dialog); val padding = ui.dp(18); setPadding(padding, padding, padding, padding)
        }
        scrollView.addView(contentView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        contentView.addView(TextView(activity).apply { text = "分享图片预览"; setTextColor(ThemeModeHelper.resolveColor(activity, R.color.app_text_primary)); textSize = 24f; setTypeface(null, Typeface.BOLD) })
        contentView.addView(TextView(activity).apply { text = "完整图片 · ${bitmap.width} × ${bitmap.height} PNG"; setTextColor(ThemeModeHelper.resolveColor(activity, R.color.app_text_muted)); textSize = 13f },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, ui.dp(4), 0, 0) })
        val previewFrame = FrameLayout(activity).apply { setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10)); setBackgroundResource(R.drawable.bg_app_card) }
        val availableWidth = max(ui.dp(240), activity.resources.displayMetrics.widthPixels - ui.dp(72))
        val scaledHeight = (availableWidth * bitmap.height.toFloat() / bitmap.width).roundToInt() + ui.dp(20)
        val previewHeight = max(ui.dp(180), min(ui.dp(520), scaledHeight))
        val previewImage = ImageView(activity).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER; setImageBitmap(bitmap) }
        previewFrame.addView(previewImage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        contentView.addView(previewFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, previewHeight).apply { setMargins(0, ui.dp(16), 0, 0) })
        val actionRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val saveButton = previewActionButton("保存到本地", false); val shareButton = previewActionButton("分享", true)
        actionRow.addView(saveButton, weightedPreviewButtonParams(0)); actionRow.addView(shareButton, weightedPreviewButtonParams(ui.dp(10)))
        contentView.addView(actionRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, ui.dp(16), 0, 0) })
        val closeButton = previewActionButton("关闭", false)
        contentView.addView(closeButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, ui.dp(12), 0, 0) })
        val dialog = AlertDialog.Builder(activity).setView(scrollView).create()
        saveButton.setOnClickListener { saveShareCard(card) }
        shareButton.setOnClickListener { try { activity.startActivity(QuoteShareCard.createShareIntent(card)) } catch (error: Exception) { ui.showToast("分享失败: ${error.message}") } }
        closeButton.setOnClickListener { dialog.dismiss() }
        dialog.show()
        val window = dialog.window
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); window?.setWindowAnimations(R.style.AppPopDialogAnimation)
        val backRegistration = PredictiveDialogDismissController.install(dialog, window, TransitionMotionModeHelper.isFluidMode(runtime.settingsStore), null)
        dialog.setOnDismissListener { backRegistration.unregister(); previewImage.setImageDrawable(null); card.recyclePreview() }
    }

    private fun previewActionButton(text: String, primary: Boolean) = Button(activity).apply {
        this.text = text; isAllCaps = false; minHeight = ui.dp(52); minimumHeight = ui.dp(52); textSize = 15f; gravity = Gravity.CENTER
        setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8)); setBackgroundResource(if (primary) R.drawable.bg_app_primary_button else R.drawable.bg_app_outline_button)
        setTextColor(ThemeModeHelper.resolveColor(activity, if (primary) R.color.app_button_primary_text else R.color.app_button_outline_text))
    }
    private fun weightedPreviewButtonParams(marginStart: Int) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(marginStart, 0, 0, 0) }

    private fun saveShareCard(card: QuoteShareCard.GeneratedCard) {
        pendingSaveCard = card
        activity.startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "image/png"; putExtra(Intent.EXTRA_TITLE, card.fileName())
        }, REQUEST_SAVE_QUOTE_CARD)
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQUEST_SAVE_QUOTE_CARD) return false
        val card = pendingSaveCard; pendingSaveCard = null
        val destination = data?.data
        if (resultCode != Activity.RESULT_OK || destination == null || card == null) return true
        runtime.safeExecute(Runnable {
            try { card.writeTo(activity, destination); activity.runOnReaderUiThread { ui.showToast("分享图片已保存") } }
            catch (error: Exception) { activity.runOnReaderUiThread { ui.showToast("保存失败: ${error.message}") } }
        }, "save quote share card")
        return true
    }

    private fun searchSelection() {
        val text = selectedText(); val dialogs = libraryDialogs ?: return
        if (text.isEmpty()) return
        clearSelection(); dialogs.showSearchDialog(text, true)
    }
    private fun speakSelection() {
        val text = selectedText(); val ttsController = tts ?: return
        if (text.isEmpty()) return
        clearSelection(); ttsController.speakTextOnce(text)
    }
    private fun selectedText(): String {
        val target = activeTarget
        if (!selectionActive || target == null || selectionEnd <= selectionStart) return ""
        val text = target.textView.text ?: return ""
        val safeStart = ui.clamp(selectionStart, 0, text.length); val safeEnd = ui.clamp(selectionEnd, safeStart, text.length)
        return text.subSequence(safeStart, safeEnd).toString()
    }
    private fun selectedChapterOffset(): Int {
        val target = activeTarget ?: return 0
        val bodyStart = max(target.slice.bodyStartInSlice, 0)
        return target.slice.start + max(0, selectionStart - bodyStart)
    }
    private fun cancelPendingLongPress() {
        pendingTarget?.textView?.removeCallbacks(longPressRunnable); pendingTarget = null; longPressPending = false
    }
    private fun dismissPopup() {
        val popup = popupWindow
        if (popup != null && popup.isShowing) {
            if (popupAnimatingDismiss) return
            popupAnimatingDismiss = true
            val popupContent = popup.contentView
            popupContent.animate().cancel()
            popupContent.animate().scaleX(PredictiveBackScaleController.READER_MIN_SCALE).scaleY(PredictiveBackScaleController.READER_MIN_SCALE)
                .alpha(0f).setDuration(130).setInterpolator(AccelerateInterpolator()).withEndAction {
                    popupAnimatingDismiss = false
                    popupWindow?.takeIf { it.isShowing }?.dismiss()
                }.start()
        }
    }

    private class Target(val textView: JustifiedPageTextView, val chapterIndex: Int, val pageIndex: Int, val slice: PageSlice)
    private class WordRange(val start: Int, val end: Int)
    private data class SelectionSnapshot(
        val text: String,
        val contextBefore: String,
        val contextAfter: String,
        val title: String,
        val author: String,
        val chapter: String,
    )

    companion object {
        private const val LONG_PRESS_TIMEOUT_MS = 600L
        private const val HANDLE_NONE = 0
        private const val HANDLE_START = 1
        private const val HANDLE_END = 2
        private const val REQUEST_SAVE_QUOTE_CARD = 2002
    }
}
