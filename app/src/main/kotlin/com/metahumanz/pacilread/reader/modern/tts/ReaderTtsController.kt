package com.metahumanz.pacilread.reader.modern.tts

import android.Manifest
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.Button
import android.widget.NumberPicker
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.metahumanz.pacilread.AppUiUtils
import com.metahumanz.pacilread.R
import com.metahumanz.pacilread.reader.PageSlice
import com.metahumanz.pacilread.reader.modern.ModernReaderActivity
import com.metahumanz.pacilread.reader.modern.ReaderRuntime
import com.metahumanz.pacilread.reader.modern.ReaderSessionState
import com.metahumanz.pacilread.reader.modern.ReaderUiUtils
import com.metahumanz.pacilread.reader.modern.ReaderViewRefs
import com.metahumanz.pacilread.reader.modern.content.ReaderContentController
import com.metahumanz.pacilread.reader.modern.dialog.ReaderDialogSupport
import com.metahumanz.pacilread.reader.modern.paging.ReaderNavigationController
import com.metahumanz.pacilread.reader.modern.paging.ReaderPagingAnimator
import com.metahumanz.pacilread.reader.modern.ui.ReaderChromeController
import com.metahumanz.pacilread.tts.MimoTtsClient
import com.metahumanz.pacilread.tts.SystemTtsClient
import com.metahumanz.pacilread.tts.TtsPlaybackService
import com.metahumanz.pacilread.tts.TtsPlaybackSnapshot
import com.metahumanz.pacilread.tts.TtsSleepTimer
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.regex.Pattern
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

class ReaderTtsController(
    private val activity: ModernReaderActivity,
    private val runtime: ReaderRuntime,
    private val views: ReaderViewRefs,
    private val state: ReaderSessionState,
    private val ui: ReaderUiUtils,
    private val dialogSupport: ReaderDialogSupport,
) {
    private val ttsUnits = ArrayList<SpeechUnit>()
    private val mimoPcmCache = LinkedHashMap<Int, ByteArray>()
    private val pendingHighlightTasks = CopyOnWriteArrayList<Runnable>()
    private var batchQueued = false
    private var playbackBinder: TtsPlaybackService.LocalBinder? = null
    private var playbackBound = false
    private var playbackBindingRequested = false
    private var stagedSleepDurationMillis = 0L
    private val playbackListener = TtsPlaybackService.Listener(::applyPlaybackSnapshot)
    private val playbackConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            playbackBinder = service as TtsPlaybackService.LocalBinder
            playbackBound = true
            playbackBindingRequested = true
            playbackBinder?.addListener(playbackListener)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            playbackBound = false
            playbackBindingRequested = false
            playbackBinder = null
        }
    }
    private lateinit var navigation: ReaderNavigationController
    private lateinit var content: ReaderContentController
    private lateinit var paging: ReaderPagingAnimator
    private lateinit var chrome: ReaderChromeController

    fun attachControllers(navigation: ReaderNavigationController, content: ReaderContentController, paging: ReaderPagingAnimator, chrome: ReaderChromeController) {
        this.navigation = navigation; this.content = content; this.paging = paging; this.chrome = chrome
    }

    fun isActive() = state.ttsActive

    fun bindPlaybackService() {
        if (playbackBound || playbackBindingRequested) return
        try { playbackBindingRequested = activity.bindService(Intent(activity, TtsPlaybackService::class.java), playbackConnection, 0) } catch (_: Exception) {}
    }

    fun unbindPlaybackService() {
        if (!playbackBound && !playbackBindingRequested) return
        try { playbackBinder?.removeListener(playbackListener); activity.unbindService(playbackConnection) } catch (_: Exception) {}
        playbackBound = false; playbackBindingRequested = false; playbackBinder = null
    }

    fun toggleTts() {
        if (state.ttsPaused) { resumeTts(); return }
        if (state.ttsActive) { pauseTts(); return }
        if (runtime.settingsStore.ttsEngine == "mimo" && runtime.settingsStore.ttsMimoApiKey.isBlank()) { ui.showToast("请先在设置页填写 MiMo API Key"); return }
        if (state.chapters.isEmpty()) { ui.showToast("当前位置没有可朗读的文本"); return }
        startTtsFrom(state.currentChapterIndex, content.currentCharOffset())
    }

    fun startTtsFrom(chapterIndex: Int, charOffset: Int) {
        if (runtime.settingsStore.ttsEngine == "mimo" && runtime.settingsStore.ttsMimoApiKey.isBlank()) { ui.showToast("请先在设置页填写 MiMo API Key"); return }
        val timerDuration = currentSleepTimerDuration()
        stagedSleepDurationMillis = timerDuration
        state.ttsActive = true; state.ttsPaused = false; state.ttsSessionId++
        chrome.styleReaderMenuButton(views.ttsButton, true)
        requestNotificationPermissionIfNeeded()
        val intent = Intent(activity, TtsPlaybackService::class.java).setAction(TtsPlaybackService.ACTION_START)
            .putExtra(TtsPlaybackService.EXTRA_BOOK_ID, state.bookId).putExtra(TtsPlaybackService.EXTRA_CHAPTER_INDEX, chapterIndex)
            .putExtra(TtsPlaybackService.EXTRA_CHAR_OFFSET, max(charOffset, 0)).putExtra(TtsPlaybackService.EXTRA_TIMER_MILLIS, timerDuration)
        ContextCompat.startForegroundService(activity, intent)
        if (!playbackBound && !playbackBindingRequested) {
            playbackBindingRequested = activity.bindService(Intent(activity, TtsPlaybackService::class.java), playbackConnection, Context.BIND_AUTO_CREATE)
        }
    }

    fun stopTts() {
        state.ttsActive = false; state.ttsPaused = false; state.ttsSessionId++
        activity.startService(Intent(activity, TtsPlaybackService::class.java).setAction(TtsPlaybackService.ACTION_STOP))
        cancelHighlightProgression()
        synchronized(mimoPcmCache) { mimoPcmCache.clear() }
        state.ttsHighlightPageIndex = -1; state.ttsHighlightStart = -1; state.ttsHighlightEnd = -1
        updateTtsHighlight(); chrome.styleReaderMenuButton(views.ttsButton, false)
    }

    fun pauseTts() {
        if (!state.ttsActive || state.ttsPaused) return
        state.ttsPaused = true
        activity.startService(Intent(activity, TtsPlaybackService::class.java).setAction(TtsPlaybackService.ACTION_PAUSE))
        cancelHighlightProgression(); synchronized(mimoPcmCache) { mimoPcmCache.clear() }
        state.ttsHighlightPageIndex = -1; state.ttsHighlightStart = -1; state.ttsHighlightEnd = -1
        updateTtsHighlight(); chrome.styleReaderMenuButton(views.ttsButton, true)
    }

    fun resumeTts() {
        if (!state.ttsActive || !state.ttsPaused) return
        state.ttsPaused = false
        activity.startService(Intent(activity, TtsPlaybackService::class.java).setAction(TtsPlaybackService.ACTION_RESUME))
    }

    fun updateTtsHighlight() {
        views.pageBodyCurrent.clearHighlight(); views.pageBodyCurrentRight.clearHighlight()
        if (state.ttsHighlightStart < 0 || state.ttsHighlightEnd <= state.ttsHighlightStart) return
        if (state.ttsHighlightPageIndex == state.currentPageIndex + 1 && views.pageBodyCurrentRight.visibility == View.VISIBLE) {
            views.pageBodyCurrentRight.setHighlightRange(state.ttsHighlightStart, state.ttsHighlightEnd); return
        }
        views.pageBodyCurrent.setHighlightRange(state.ttsHighlightStart, state.ttsHighlightEnd)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3002)
    }

    private fun applyPlaybackSnapshot(playback: TtsPlaybackSnapshot?) {
        playback ?: return
        if (playback.state == TtsPlaybackSnapshot.STATE_STOPPED) stagedSleepDurationMillis = 0L
        val sameBook = playback.bookId == state.bookId
        state.ttsActive = sameBook && playback.isActive()
        state.ttsPaused = state.ttsActive && playback.isPaused()
        chrome.styleReaderMenuButton(views.ttsButton, state.ttsActive)
        if (!state.ttsActive || state.ttsPaused || playback.chapterIndex < 0) {
            state.ttsHighlightPageIndex = -1; state.ttsHighlightStart = -1; state.ttsHighlightEnd = -1; updateTtsHighlight(); return
        }
        state.ttsChapterIndex = playback.chapterIndex
        syncServiceHighlight(playback, 0)
    }

    private fun syncServiceHighlight(playback: TtsPlaybackSnapshot, retry: Int) {
        if (!state.ttsActive || playback.chapterIndex !in state.chapters.indices) return
        if (state.currentChapterIndex != playback.chapterIndex) {
            navigation.openChapter(playback.chapterIndex, playback.sentenceStart, true, if (playback.chapterIndex >= state.currentChapterIndex) 1 else -1)
            if (retry < 3) runtime.mainHandler.postDelayed({ syncServiceHighlight(playback, retry + 1) }, paging.readerFlipDurationMs() + 40L)
            return
        }
        val pages = content.getPagesForChapter(playback.chapterIndex)
        if (pages.isNullOrEmpty()) return
        var pageIndex = -1
        for (i in pages.indices) if (playback.sentenceStart >= pages[i].start && playback.sentenceStart < pages[i].end) { pageIndex = i; break }
        if (pageIndex < 0) return
        val lastVisible = state.currentPageIndex + content.pagesPerScreen() - 1
        if ((pageIndex < state.currentPageIndex || pageIndex > lastVisible) && retry < 3) {
            navigation.openChapter(playback.chapterIndex, playback.sentenceStart, true, if (pageIndex >= state.currentPageIndex) 1 else -1)
            runtime.mainHandler.postDelayed({ syncServiceHighlight(playback, retry + 1) }, paging.readerFlipDurationMs() + 40L); return
        }
        val slice = pages[pageIndex]; val bodyStart = max(0, slice.bodyStartInSlice)
        state.ttsHighlightPageIndex = pageIndex
        state.ttsHighlightStart = bodyStart + max(0, playback.sentenceStart - slice.start)
        state.ttsHighlightEnd = bodyStart + max(state.ttsHighlightStart - bodyStart, min(slice.end, playback.sentenceEnd) - slice.start)
        updateTtsHighlight()
    }

    private fun setSleepTimerDuration(durationMillis: Long) {
        stagedSleepDurationMillis = max(0L, durationMillis)
        if (!state.ttsActive) return
        activity.startService(Intent(activity, TtsPlaybackService::class.java).setAction(TtsPlaybackService.ACTION_SET_TIMER)
            .putExtra(TtsPlaybackService.EXTRA_TIMER_MILLIS, stagedSleepDurationMillis))
    }

    private fun currentSleepTimerDuration(): Long {
        playbackBinder?.snapshot()?.let { if (it.sleepDeadlineElapsed > 0L) return max(0L, it.sleepDeadlineElapsed - SystemClock.elapsedRealtime()) }
        return stagedSleepDurationMillis
    }

    fun showTtsDialog() {
        val contentView = LayoutInflater.from(activity).inflate(R.layout.dialog_tts, null, false)
        val engineSpinner = contentView.findViewById<Spinner>(R.id.tts_spinner_engine)
        val seekBar = contentView.findViewById<SeekBar>(R.id.tts_seek_rate)
        val mimoVoiceLayout = contentView.findViewById<View>(R.id.tts_layout_mimo_voice)
        val mimoVoiceSpinner = contentView.findViewById<Spinner>(R.id.tts_spinner_mimo_voice)
        val systemEngineLayout = contentView.findViewById<View>(R.id.tts_layout_system_engine)
        val systemEngineSpinner = contentView.findViewById<Spinner>(R.id.tts_spinner_system_engine)
        val valueText = contentView.findViewById<TextView>(R.id.tts_text_rate)
        val noteText = contentView.findViewById<TextView>(R.id.tts_text_note)
        val toggleButton = contentView.findViewById<Button>(R.id.tts_button_toggle)
        val stopButton = contentView.findViewById<Button>(R.id.tts_button_stop)
        val timerSliderModeButton = contentView.findViewById<Button>(R.id.tts_button_timer_slider_mode)
        val timerPreciseModeButton = contentView.findViewById<Button>(R.id.tts_button_timer_precise_mode)
        val timerSliderLayout = contentView.findViewById<View>(R.id.tts_layout_timer_slider)
        val timerPreciseLayout = contentView.findViewById<View>(R.id.tts_layout_timer_precise)
        val timerSeekBar = contentView.findViewById<SeekBar>(R.id.tts_seek_timer)
        val timerText = contentView.findViewById<TextView>(R.id.tts_text_timer)
        val timerHours = contentView.findViewById<NumberPicker>(R.id.tts_picker_timer_hours)
        val timerMinutes = contentView.findViewById<NumberPicker>(R.id.tts_picker_timer_minutes)
        val timerSeconds = contentView.findViewById<NumberPicker>(R.id.tts_picker_timer_seconds)
        val timerDuration = longArrayOf(currentSleepTimerDuration())
        val timerMode = arrayOf(runtime.settingsStore.ttsTimerMode)
        timerHours.minValue = 0; timerHours.maxValue = 23; timerMinutes.minValue = 0; timerMinutes.maxValue = 59; timerSeconds.minValue = 0; timerSeconds.maxValue = 59
        val twoDigit = NumberPicker.Formatter { String.format(Locale.ROOT, "%02d", it) }
        timerHours.setFormatter(twoDigit); timerMinutes.setFormatter(twoDigit); timerSeconds.setFormatter(twoDigit)
        TtsSleepTimer.millisToPrecise(timerDuration[0]).let { timerHours.value = it[0]; timerMinutes.value = it[1]; timerSeconds.value = it[2] }
        timerSeekBar.progress = TtsSleepTimer.millisToSliderProgress(timerDuration[0]); timerText.text = formatTimerDuration(timerDuration[0])
        val refreshTimerMode = Runnable {
            val slider = timerMode[0] == "slider"
            timerSliderLayout.visibility = if (slider) View.VISIBLE else View.GONE; timerPreciseLayout.visibility = if (slider) View.GONE else View.VISIBLE
            AppUiUtils.styleSelectionButton(activity, timerSliderModeButton, slider); AppUiUtils.styleSelectionButton(activity, timerPreciseModeButton, !slider)
        }
        timerSliderModeButton.setOnClickListener { timerMode[0] = "slider"; runtime.settingsStore.ttsTimerMode = "slider"; timerSeekBar.progress = TtsSleepTimer.millisToSliderProgress(timerDuration[0]); refreshTimerMode.run() }
        timerPreciseModeButton.setOnClickListener {
            timerMode[0] = "precise"; runtime.settingsStore.ttsTimerMode = "precise"
            TtsSleepTimer.millisToPrecise(timerDuration[0]).let { timerHours.value = it[0]; timerMinutes.value = it[1]; timerSeconds.value = it[2] }; refreshTimerMode.run()
        }
        refreshTimerMode.run()
        timerSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { if (fromUser) { timerDuration[0] = TtsSleepTimer.sliderProgressToMillis(progress); timerText.text = formatTimerDuration(timerDuration[0]); setSleepTimerDuration(timerDuration[0]) } }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        val preciseListener = NumberPicker.OnValueChangeListener { _, _, _ ->
            timerDuration[0] = TtsSleepTimer.preciseToMillis(timerHours.value, timerMinutes.value, timerSeconds.value)
            timerText.text = formatTimerDuration(timerDuration[0]); setSleepTimerDuration(timerDuration[0])
        }
        timerHours.setOnValueChangedListener(preciseListener); timerMinutes.setOnValueChangedListener(preciseListener); timerSeconds.setOnValueChangedListener(preciseListener)
        engineSpinner.adapter = dialogSupport.buildSpinnerAdapter(TTS_ENGINE_LABELS); engineSpinner.setSelection(indexOf(TTS_ENGINE_KEYS, runtime.settingsStore.ttsEngine, 0), false)
        mimoVoiceSpinner.adapter = dialogSupport.buildSpinnerAdapter(TTS_MIMO_VOICE_LABELS); mimoVoiceSpinner.setSelection(indexOf(TTS_MIMO_VOICE_KEYS, runtime.settingsStore.ttsMimoVoice, 0), false)
        val engines = SystemTtsClient.queryAvailableEngines(activity)
        systemEngineSpinner.adapter = dialogSupport.buildSpinnerAdapter(Array(engines.size) { engines[it].label })
        val currentEngineIndex = engines.indexOfFirst { it.packageName == runtime.settingsStore.ttsSystemEnginePackage }.let { if (it < 0) 0 else it }
        systemEngineSpinner.setSelection(currentEngineIndex, false)
        seekBar.progress = ui.clamp(((runtime.settingsStore.ttsRate - 0.5f) * 10f).roundToInt(), 0, 15)
        valueText.text = String.format(Locale.SIMPLIFIED_CHINESE, "%.1f 倍", runtime.settingsStore.ttsRate)
        updateTtsDialogViews(runtime.settingsStore.ttsEngine, mimoVoiceLayout, systemEngineLayout, noteText)
        engineSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { TTS_ENGINE_KEYS[position].let { runtime.settingsStore.ttsEngine = it; updateTtsDialogViews(it, mimoVoiceLayout, systemEngineLayout, noteText) } }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        systemEngineSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { if (position in engines.indices) runtime.settingsStore.ttsSystemEnginePackage = engines[position].packageName }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        mimoVoiceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { runtime.settingsStore.ttsMimoVoice = TTS_MIMO_VOICE_KEYS[position] }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        seekBar.setOnSeekBarChangeListener(ReaderDialogSupport.SimpleSeekListener(Runnable {
            val rate = 0.5f + seekBar.progress / 10f; valueText.text = String.format(Locale.SIMPLIFIED_CHINESE, "%.1f 倍", rate); runtime.settingsStore.ttsRate = rate
        }))
        toggleButton.text = when { state.ttsPaused -> "继续听书"; state.ttsActive -> "暂停听书"; else -> "开始听书" }
        stopButton.visibility = if (state.ttsActive || state.ttsPaused) View.VISIBLE else View.GONE
        val dialog = AlertDialog.Builder(activity).setView(contentView).create()
        toggleButton.setOnClickListener { when { state.ttsPaused -> resumeTts(); state.ttsActive -> pauseTts(); else -> toggleTts() }; dialog.dismiss() }
        stopButton.setOnClickListener { stopTts(); dialog.dismiss() }
        dialogSupport.showStyledDialog(dialog)
    }

    private fun playCurrentTtsUnit() {
        if (!state.ttsActive) { Log.d(TAG, "playCurrentTtsUnit: ttsActive=false, return"); return }
        if (state.ttsChapterIndex !in state.chapters.indices) { Log.d(TAG, "playCurrentTtsUnit: bad chapter index, stop"); stopTts(); return }
        if (state.isAnimating || state.interactivePaging) { Log.d(TAG, "playCurrentTtsUnit: isAnimating=${state.isAnimating} interactivePaging=${state.interactivePaging}, delay retry"); scheduleTtsPlayback(paging.readerFlipDurationMs() + 60L); return }
        if (state.currentTtsUnitIndex >= ttsUnits.size) { Log.d(TAG, "playCurrentTtsUnit: index=${state.currentTtsUnitIndex} >= size=${ttsUnits.size}, next chapter"); advanceToNextTtsChapter(); return }
        if (state.currentChapterIndex != state.ttsChapterIndex) {
            val pendingUnit = ttsUnits[ui.clamp(state.currentTtsUnitIndex, 0, max(ttsUnits.size - 1, 0))]
            navigation.openChapter(state.ttsChapterIndex, pendingUnit.start, true, if (state.ttsChapterIndex >= state.currentChapterIndex) 1 else -1)
            scheduleTtsPlayback(paging.readerFlipDurationMs() + 60L); return
        }
        val pages = content.getPagesForChapter(state.ttsChapterIndex)
        if (pages.isNullOrEmpty()) { advanceToNextTtsChapter(); return }
        val firstVisibleSlice = pages[ui.clamp(state.currentPageIndex, 0, pages.size - 1)]
        while (state.currentTtsUnitIndex < ttsUnits.size && ttsUnits[state.currentTtsUnitIndex].end <= firstVisibleSlice.start) state.currentTtsUnitIndex++
        if (state.currentTtsUnitIndex >= ttsUnits.size) { advanceToNextTtsChapter(); return }
        val groupCount = computeGroupUnitCount(); val unit = ttsUnits[state.currentTtsUnitIndex]
        val visiblePage = findVisiblePageForUnit(pages, unit)
        if (visiblePage == null) {
            val lastVisibleSlice = pages[ui.clamp(state.currentPageIndex + content.pagesPerScreen() - 1, 0, pages.size - 1)]
            if (unit.start >= lastVisibleSlice.end) { if (navigation.pageDown()) scheduleTtsPlayback(paging.readerFlipDurationMs() + 60L) else advanceToNextTtsChapter(); return }
            navigation.openChapter(state.ttsChapterIndex, unit.start, true, -1); scheduleTtsPlayback(paging.readerFlipDurationMs() + 60L); return
        }
        val highlightSlice = visiblePage.slice
        val highlightStartOffset = max(textStartWithoutLeadingSymbols(unit), highlightSlice.start)
        val highlightEndOffset = min(textEndWithoutTrailingPunctuation(unit), highlightSlice.end)
        if (highlightEndOffset <= highlightStartOffset) { if (navigation.pageDown()) scheduleTtsPlayback(paging.readerFlipDurationMs() + 60L) else advanceToNextTtsChapter(); return }
        val bodyOffsetInSlice = max(highlightSlice.bodyStartInSlice, 0)
        state.ttsHighlightPageIndex = visiblePage.pageIndex
        state.ttsHighlightStart = bodyOffsetInSlice + highlightStartOffset - highlightSlice.start
        state.ttsHighlightEnd = bodyOffsetInSlice + highlightEndOffset - highlightSlice.start
        Log.d(TAG, "playCurrentTtsUnit: set highlight unitIdx=${state.currentTtsUnitIndex} pageIndex=${state.ttsHighlightPageIndex} start=${state.ttsHighlightStart} end=${state.ttsHighlightEnd}")
        updateTtsHighlight(); activity.markReadingActivity(); speakCurrentTtsGroup(groupCount)
    }

    private fun rebuildTtsUnitsForChapter(chapterIndex: Int, minOffset: Int): Boolean {
        ttsUnits.clear()
        if (state.chapters.isEmpty()) { state.ttsChapterIndex = -1; state.currentTtsUnitIndex = -1; return false }
        state.ttsChapterIndex = ui.clamp(chapterIndex, 0, state.chapters.size - 1)
        val matcher = TTS_SEGMENT_PATTERN.matcher(content.getProcessedChapterText(state.ttsChapterIndex))
        while (matcher.find()) matcher.group()?.takeIf { it.trim().isNotEmpty() }?.let { ttsUnits.add(SpeechUnit(matcher.start(), matcher.end(), it)) }
        state.currentTtsUnitIndex = 0
        while (state.currentTtsUnitIndex < ttsUnits.size && ttsUnits[state.currentTtsUnitIndex].end <= minOffset) state.currentTtsUnitIndex++
        return state.currentTtsUnitIndex < ttsUnits.size
    }

    private fun advanceTtsPlayback(consumedUnits: Int) { if (state.ttsActive) { state.currentTtsUnitIndex += max(consumedUnits, 0); playCurrentTtsUnit() } }
    private fun findVisiblePageForUnit(pages: List<PageSlice>?, unit: SpeechUnit?): VisiblePage? {
        if (pages.isNullOrEmpty() || unit == null) return null
        val first = ui.clamp(state.currentPageIndex, 0, pages.size - 1); val last = ui.clamp(first + content.pagesPerScreen() - 1, first, pages.size - 1)
        for (index in first..last) if (unit.start < pages[index].end && unit.end > pages[index].start) return VisiblePage(index, pages[index])
        return null
    }
    private fun scheduleTtsPlayback(delayMillis: Long) {
        val sessionId = state.ttsSessionId
        runtime.mainHandler.postDelayed({ if (state.ttsActive && sessionId == state.ttsSessionId) playCurrentTtsUnit() }, max(delayMillis, 20L))
    }
    private fun advanceToNextTtsChapter() {
        if (!state.ttsActive) return
        if (state.ttsChapterIndex >= state.chapters.size - 1) { stopTts(); return }
        val nextChapterIndex = state.ttsChapterIndex + 1
        navigation.openChapter(nextChapterIndex, 0, true, 1)
        val sessionId = state.ttsSessionId
        runtime.mainHandler.postDelayed({ if (state.ttsActive && sessionId == state.ttsSessionId) { rebuildTtsUnitsForChapter(nextChapterIndex, 0); playCurrentTtsUnit() } }, paging.readerFlipDurationMs() * 2L + 60L)
    }
    private fun computeGroupUnitCount() = computeGroupUnitCountAt(state.currentTtsUnitIndex)
    private fun computeGroupUnitCountAt(startIndex: Int): Int {
        var count = 1
        while (startIndex + count < ttsUnits.size && !endsWithFullSentence(ttsUnits[startIndex + count - 1].text)) count++
        return count
    }
    private fun buildGroupText(startIndex: Int, groupCount: Int): String = buildString { for (i in 0 until groupCount) append(ttsUnits[startIndex + i].text) }.trim()

    private fun preloadMimoGroups(afterUnitIndex: Int, afterGroupCount: Int, sessionId: Int, apiKey: String, voice: String) {
        var nextStart = afterUnitIndex + afterGroupCount
        repeat(MIMO_PRECACHE_AHEAD) {
            if (nextStart >= ttsUnits.size) return
            val gc = computeGroupUnitCountAt(nextStart); val text = buildGroupText(nextStart, gc); val cacheKey = nextStart
            runtime.safeExecuteSynthesis(Runnable {
                if (!state.ttsActive || sessionId != state.ttsSessionId) return@Runnable
                try { val pcm = runtime.mimoTtsClient.synthesize(text, apiKey, voice); synchronized(mimoPcmCache) { if (state.ttsActive && sessionId == state.ttsSessionId) mimoPcmCache[cacheKey] = pcm } } catch (_: Exception) {}
            }, "preload MiMo TTS")
            nextStart += gc
        }
    }

    private fun scheduleHighlightProgression(startIdx: Int, groupCount: Int, sessionId: Int, pcm: ByteArray, rate: Float) {
        if (groupCount <= 1 || !state.ttsActive || sessionId != state.ttsSessionId) return
        val totalMs = ((pcm.size / 2f / MimoTtsClient.getSampleRate()) / max(rate, 0.5f) * 1000f).roundToLong()
        var totalLen = 0; for (i in 0 until groupCount) if (startIdx + i < ttsUnits.size) totalLen += ttsUnits[startIdx + i].text.length
        if (totalLen <= 0 || totalMs <= 200) return
        var cumulativeLen = 0
        for (i in 0 until groupCount) if (startIdx + i < ttsUnits.size) {
            val delayMs = (cumulativeLen.toFloat() / totalLen * totalMs).roundToLong(); cumulativeLen += ttsUnits[startIdx + i].text.length
            buildHighlightTask(sessionId, ttsUnits[startIdx + i]).let { pendingHighlightTasks.add(it); runtime.mainHandler.postDelayed(it, max(delayMs, 50L)) }
        }
    }

    private fun buildHighlightTask(sessionId: Int, unit: SpeechUnit): Runnable = object : Runnable {
        private var retries = 0
        override fun run() {
            if (!state.ttsActive || sessionId != state.ttsSessionId) return
            if (state.isAnimating || state.interactivePaging) { if (retries++ < 30) runtime.mainHandler.postDelayed(this, paging.readerFlipDurationMs() + 20L); return }
            val pages = content.getPagesForChapter(state.ttsChapterIndex)
            if (pages.isNullOrEmpty()) return
            val visible = findVisiblePageForUnit(pages, unit)
            if (visible != null) {
                state.ttsHighlightPageIndex = visible.pageIndex
                val body = max(visible.slice.bodyStartInSlice, 0); val start = max(textStartWithoutLeadingSymbols(unit), visible.slice.start); val end = min(textEndWithoutTrailingPunctuation(unit), visible.slice.end)
                state.ttsHighlightStart = body + start - visible.slice.start; state.ttsHighlightEnd = body + end - visible.slice.start
                if (state.ttsHighlightEnd > state.ttsHighlightStart) updateTtsHighlight(); return
            }
            val firstVisible = ui.clamp(state.currentPageIndex, 0, pages.size - 1)
            if (unit.start >= pages[firstVisible].end && navigation.pageDown() && retries++ < 30) runtime.mainHandler.postDelayed(this, paging.readerFlipDurationMs() + 20L)
        }
    }

    private fun highlightUnit(unitIndex: Int) {
        if (unitIndex !in ttsUnits.indices) return
        val pages = content.getPagesForChapter(state.ttsChapterIndex); if (pages.isNullOrEmpty()) return
        val unit = ttsUnits[unitIndex]; val visible = findVisiblePageForUnit(pages, unit)
        if (visible == null) {
            val lastSlice = pages[ui.clamp(state.currentPageIndex + content.pagesPerScreen() - 1, 0, pages.size - 1)]
            if (unit.start >= lastSlice.end && navigation.pageDown()) scheduleTtsPlayback(paging.readerFlipDurationMs() + 60L)
            return
        }
        val start = max(textStartWithoutLeadingSymbols(unit), visible.slice.start); val end = min(textEndWithoutTrailingPunctuation(unit), visible.slice.end)
        if (end <= start) return
        val body = max(visible.slice.bodyStartInSlice, 0)
        state.ttsHighlightPageIndex = visible.pageIndex; state.ttsHighlightStart = body + start - visible.slice.start; state.ttsHighlightEnd = body + end - visible.slice.start
        updateTtsHighlight()
    }

    private fun scheduleSystemHighlightProgression(startIdx: Int, groupCount: Int, sessionId: Int, rate: Float) {
        if (groupCount <= 1 || !state.ttsActive || sessionId != state.ttsSessionId) return
        var totalLen = 0; for (i in 0 until groupCount) if (startIdx + i < ttsUnits.size) totalLen += ttsUnits[startIdx + i].text.length
        if (totalLen <= 0) return
        val totalMs = (totalLen * 250f / max(rate, 0.5f)).roundToLong(); if (totalMs <= 200) return
        var cumulative = 0
        for (i in 0 until groupCount) if (startIdx + i < ttsUnits.size) {
            val delay = (cumulative.toFloat() / totalLen * totalMs).roundToLong(); cumulative += ttsUnits[startIdx + i].text.length
            buildHighlightTask(sessionId, ttsUnits[startIdx + i]).let { pendingHighlightTasks.add(it); runtime.mainHandler.postDelayed(it, max(delay, 50L)) }
        }
    }

    private fun cancelHighlightProgression() { for (task in pendingHighlightTasks) runtime.mainHandler.removeCallbacks(task); pendingHighlightTasks.clear() }
    private fun speakCurrentTtsGroup(groupCount: Int) { if (state.currentTtsUnitIndex !in ttsUnits.indices) advanceToNextTtsChapter() else speakWithCurrentEngine(groupCount) }

    private fun speakWithCurrentEngine(groupCount: Int) {
        val groupText = buildGroupText(state.currentTtsUnitIndex, groupCount)
        if (groupText.isEmpty()) { advanceTtsPlayback(groupCount); return }
        val sessionId = state.ttsSessionId; val consumedUnits = groupCount; val engine = runtime.settingsStore.ttsEngine; val engineLabel = engineLabel(engine)
        if (engine == "mimo") {
            runtime.safeExecuteTts(Runnable {
                try {
                    if (!state.ttsActive || sessionId != state.ttsSessionId) return@Runnable
                    val apiKey = runtime.settingsStore.ttsMimoApiKey; val voice = runtime.settingsStore.ttsMimoVoice; val playbackRate = runtime.settingsStore.ttsRate
                    val pcm = synchronized(mimoPcmCache) { mimoPcmCache.remove(state.currentTtsUnitIndex) }
                        ?: runtime.mimoTtsClient.synthesize(groupText, apiKey, voice)
                    preloadMimoGroups(state.currentTtsUnitIndex, consumedUnits, sessionId, apiKey, voice)
                    scheduleHighlightProgression(state.currentTtsUnitIndex, consumedUnits, sessionId, pcm, playbackRate)
                    runtime.mimoTtsClient.playPcm(pcm, playbackRate); cancelHighlightProgression()
                    activity.runOnReaderUiThread { if (state.ttsActive && sessionId == state.ttsSessionId) advanceTtsPlayback(consumedUnits) }
                } catch (error: Exception) {
                    activity.runOnReaderUiThread { if (state.ttsActive && sessionId == state.ttsSessionId) { stopTts(); ui.showToast("$engineLabel 听书失败: ${error.message}") } }
                }
            }, "play MiMo TTS")
            return
        }
        if (batchQueued) return
        if (!runtime.systemTtsClient.requestAudioFocus()) { ui.showToast("未获取到音频焦点"); stopTts(); return }
        val allTexts = ArrayList<String>(); val allStarts = ArrayList<Int>(); val allCounts = ArrayList<Int>()
        var index = state.currentTtsUnitIndex
        while (index < ttsUnits.size) {
            val count = computeGroupUnitCountAt(index); val text = buildGroupText(index, count)
            if (text.isNotEmpty()) { allTexts.add(text); allStarts.add(index); allCounts.add(count) }
            index += count
        }
        if (allTexts.isEmpty()) { advanceToNextTtsChapter(); return }
        batchQueued = true
        val rate = runtime.settingsStore.ttsRate
        var completed = 0
        scheduleSystemHighlightProgression(allStarts[0], allCounts[0], sessionId, rate)
        runtime.systemTtsClient.speakAll(allTexts, rate, object : SystemTtsClient.SpeakCallback {
            override fun onStart() = Unit
            override fun onDone() {
                val i = completed++
                val units = allCounts[i]
                Log.d(TAG, "onDone: i=$i units=$units totalGroups=${allTexts.size}")
                activity.runOnReaderUiThread {
                    if (!state.ttsActive || sessionId != state.ttsSessionId) { Log.d(TAG, "onDone ui: state inactive, skip"); return@runOnReaderUiThread }
                    cancelHighlightProgression()
                    if (i + 1 >= allTexts.size) { batchQueued = false; Log.d(TAG, "onDone ui: last group, batchQueued=false") }
                    else scheduleSystemHighlightProgression(allStarts[i + 1], allCounts[i + 1], sessionId, rate)
                    advanceTtsPlayback(units)
                }
            }
            override fun onError(message: String) {
                batchQueued = false
                activity.runOnReaderUiThread { if (state.ttsActive && sessionId == state.ttsSessionId) { cancelHighlightProgression(); stopTts(); ui.showToast("系统 TTS 听书失败: $message") } }
            }
        })
    }

    private fun textEndWithoutTrailingPunctuation(unit: SpeechUnit): Int {
        var length = unit.text.length
        while (length > 0 && isTrailingPunctuation(unit.text[length - 1])) length--
        return if (length == 0) unit.end else unit.start + length
    }
    private fun textStartWithoutLeadingSymbols(unit: SpeechUnit): Int {
        var offset = 0
        while (offset < unit.text.length && isLeadingSymbol(unit.text[offset])) offset++
        return unit.start + offset
    }
    private fun endsWithFullSentence(text: String?): Boolean {
        val ending = text?.trim()?.lastOrNull() ?: return false
        return ending == '。' || ending == '！' || ending == '？' || ending == '!' || ending == '?'
    }
    private fun isTrailingPunctuation(value: Char): Boolean = when (value) {
        '。', '！', '？', '!', '?', '，', ',', '；', ';', '、', '.', '：', '"', '“', '”', '」', '』' -> true
        else -> false
    }
    private fun isLeadingSymbol(value: Char): Boolean = when (value) {
        '"', '“', '”', '\'', '「', '『' -> true
        else -> false
    }
    private fun updateTtsDialogViews(engine: String?, mimoVoiceLayout: View?, systemEngineLayout: View?, noteText: TextView?) {
        mimoVoiceLayout?.visibility = if (engine == "mimo") View.VISIBLE else View.GONE
        systemEngineLayout?.visibility = if (engine != "mimo") View.VISIBLE else View.GONE
        noteText ?: return
        noteText.text = if (engine == "mimo") "MiMo 模式会调用小米云端 TTS，模型为 mimo-v2.5-tts，API Key 请在设置页维护。\nMiMo 听书不推荐调整语速倍率。" else "系统 TTS 使用设备内置语音引擎，无需联网。"
    }
    private fun engineLabel(engine: String?) = if (engine == "mimo") "MiMo" else "系统 TTS"
    private fun formatTimerDuration(durationMillis: Long): String {
        if (durationMillis <= 0L) return "关闭"
        val totalMinutes = max(1L, (durationMillis / 60_000f).roundToLong()); val hours = totalMinutes / 60L; val minutes = totalMinutes % 60L
        return when { hours == 0L -> "$minutes 分钟后停止"; minutes == 0L -> "$hours 小时后停止"; else -> "$hours 小时 $minutes 分钟后停止" }
    }
    private fun indexOf(values: Array<String>, target: String?, fallback: Int): Int = values.indexOf(target).let { if (it < 0) fallback else it }
    private class SpeechUnit(val start: Int, val end: Int, val text: String)
    private class VisiblePage(val pageIndex: Int, val slice: PageSlice)

    companion object {
        private const val TAG = "TtsHighlight"
        private val TTS_SEGMENT_PATTERN = Pattern.compile("[^ \\n\\t。！？.!?,，;；、]+[。！？.!?,，;；、]*")
        private val TTS_ENGINE_KEYS = arrayOf("system", "mimo")
        private val TTS_ENGINE_LABELS = arrayOf("系统 TTS", "小米 MiMo")
        private val TTS_MIMO_VOICE_KEYS = arrayOf("冰糖", "茉莉", "苏打", "白桦")
        private val TTS_MIMO_VOICE_LABELS = arrayOf("冰糖（女声）", "茉莉（女声）", "苏打（男声）", "白桦（男声）")
        private const val MIMO_PRECACHE_AHEAD = 2
    }
}
