package com.metahumanz.pacilread.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.metahumanz.pacilread.R
import com.metahumanz.pacilread.ReaderActivity
import com.metahumanz.pacilread.model.BookRecord
import com.metahumanz.pacilread.model.ChapterRecord
import com.metahumanz.pacilread.model.ReplacementRuleRecord
import com.metahumanz.pacilread.reader.ReplacementEngine
import com.metahumanz.pacilread.storage.JsonDatabase
import com.metahumanz.pacilread.storage.SettingsStore
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.regex.Pattern
import kotlin.math.max
import kotlin.math.min

class TtsPlaybackService : Service() {
    fun interface Listener {
        fun onPlaybackChanged(snapshot: TtsPlaybackSnapshot)
    }

    inner class LocalBinder : Binder() {
        fun snapshot(): TtsPlaybackSnapshot = this@TtsPlaybackService.snapshot

        fun addListener(listener: Listener?) {
            listener ?: return
            listeners.add(listener)
            mainHandler.post { listener.onPlaybackChanged(snapshot) }
        }

        fun removeListener(listener: Listener?) {
            listeners.remove(listener)
        }
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val loadExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mimoPlaybackExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mimoPrefetchExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mimoPrefetchLock = Object()
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val sleepTimeout = Runnable(::stopPlayback)

    private lateinit var database: JsonDatabase
    private lateinit var settings: SettingsStore
    private lateinit var systemTts: SystemTtsClient
    private lateinit var mimoPlayback: MimoTtsClient
    private lateinit var mimoPrefetch: MimoTtsClient
    private lateinit var mediaSession: MediaSession
    private lateinit var notificationManager: NotificationManager

    private var book: BookRecord? = null
    private var chapters: List<ChapterRecord> = ArrayList()
    private var rules: List<ReplacementRuleRecord> = ArrayList()
    private var currentUnits: List<SpeechUnit> = ArrayList()
    private var currentChapterIndex = -1
    private var currentUnitIndex = -1
    @Volatile private var active = false
    @Volatile private var paused = false
    private var foregroundStarted = false
    private var sleepDeadlineElapsed = 0L
    @Volatile private var sessionGeneration = 0
    private var systemReadyRetries = 0
    private var prefetchedPcm: ByteArray? = null
    private var prefetchedKey = ""
    private var mimoPrefetchInFlight = false
    private var systemPlaybackToken = 0
    private var systemRetryKey = ""
    private var systemRetryCount = 0
    private var systemPlaybackWatchdog: Runnable? = null
    @Volatile private var snapshot = stoppedSnapshot()

    override fun onCreate() {
        super.onCreate()
        database = JsonDatabase.getInstance(this)
        settings = SettingsStore(this)
        systemTts = SystemTtsClient(this, settings.ttsSystemEnginePackage)
        systemTts.setAudioFocusLossListener(Runnable(::pausePlayback))
        mimoPlayback = MimoTtsClient()
        mimoPrefetch = MimoTtsClient()
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        createMediaSession()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                ensureForeground("正在准备听书", "加载章节...")
                setSleepTimer(intent.getLongExtra(EXTRA_TIMER_MILLIS, 0L))
                startSession(
                    intent.getLongExtra(EXTRA_BOOK_ID, -1L),
                    intent.getIntExtra(EXTRA_CHAPTER_INDEX, 0),
                    intent.getIntExtra(EXTRA_CHAR_OFFSET, 0),
                )
            }
            ACTION_PAUSE -> pausePlayback()
            ACTION_RESUME -> resumePlayback()
            ACTION_STOP -> stopPlayback()
            ACTION_SET_TIMER -> {
                if (active) setSleepTimer(intent.getLongExtra(EXTRA_TIMER_MILLIS, 0L)) else stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        sessionGeneration++
        invalidateSystemPlayback()
        mainHandler.removeCallbacksAndMessages(null)
        loadExecutor.shutdownNow()
        mimoPlaybackExecutor.shutdownNow()
        mimoPrefetchExecutor.shutdownNow()
        systemTts.shutdown()
        mimoPlayback.cancel()
        mimoPrefetch.cancel()
        mediaSession.release()
        super.onDestroy()
    }

    private fun startSession(bookId: Long, chapterIndex: Int, charOffset: Int) {
        val generation = ++sessionGeneration
        systemTts.setEngine(settings.ttsSystemEnginePackage)
        active = true
        paused = false
        systemReadyRetries = 0
        invalidateSystemPlayback()
        resetSystemRetry()
        publishLoading(bookId)
        systemTts.stop()
        mimoPlayback.cancel()
        mimoPrefetch.cancel()
        clearPrefetch()
        loadExecutor.execute {
            val loadedBook = database.getBook(bookId)
            val loadedChapters = database.getChapters(bookId, false)
            val loadedRules = database.getReplacementRules(bookId)
            mainHandler.post {
                if (generation != sessionGeneration || !active) return@post
                if (loadedBook == null || loadedChapters.isEmpty()) {
                    stopPlayback()
                    return@post
                }
                book = loadedBook
                chapters = loadedChapters
                rules = loadedRules
                currentChapterIndex = clamp(chapterIndex, 0, chapters.size - 1)
                currentUnits = buildUnits(currentChapterIndex)
                currentUnitIndex = firstUnitAtOrAfter(currentUnits, max(0, charOffset))
                if (currentUnitIndex >= currentUnits.size && !advanceToNextChapter()) {
                    stopPlayback()
                    return@post
                }
                playCurrent()
            }
        }
    }

    private fun playCurrent() {
        if (!active || paused) return
        val current = currentRef()
        if (current == null) {
            stopPlayback()
            return
        }
        if (settings.ttsEngine != "mimo" && !systemTts.isInitSuccess()) {
            if (systemReadyRetries++ < 40) mainHandler.postDelayed(::playCurrent, 150L)
            else stopPlayback()
            return
        }
        systemReadyRetries = 0
        database.updateProgress(book!!.id, current.chapterIndex, current.unit.start)
        publish(TtsPlaybackSnapshot.STATE_PLAYING, current)
        if (!systemTts.requestAudioFocus()) {
            stopPlayback()
            return
        }
        if (settings.ttsEngine == "mimo") playMimo(current) else playSystemUnit(current)
    }

    private fun playSystemUnit(current: SpeechRef) {
        // 某些系统 TTS 会接受 QUEUE_ADD 却不启动第二条，因此每句完成后显式提交下一句。
        val generation = sessionGeneration
        val playbackKey = key(current)
        if (systemRetryKey != playbackKey) {
            systemRetryKey = playbackKey
            systemRetryCount = 0
        }
        val playbackToken = ++systemPlaybackToken
        scheduleSystemPlaybackWatchdog(current, generation, playbackToken)
        systemTts.speak(current.unit.text, settings.ttsRate, object : SystemTtsClient.SpeakCallback {
            override fun onStart() = Unit

            override fun onDone() {
                mainHandler.post {
                    if (!isCurrentSystemPlayback(generation, playbackToken)) return@post
                    clearSystemPlaybackWatchdog()
                    resetSystemRetry()
                    advanceOne()
                    playCurrent()
                }
            }

            override fun onError(message: String) {
                mainHandler.post {
                    if (!isCurrentSystemPlayback(generation, playbackToken)) return@post
                    Log.w(TAG, "系统 TTS 朗读失败，准备恢复当前句：$playbackKey, $message")
                    retryOrStopSystemPlayback(playbackKey)
                }
            }
        })
    }

    private fun scheduleSystemPlaybackWatchdog(current: SpeechRef, generation: Int, playbackToken: Int) {
        clearSystemPlaybackWatchdog()
        val playbackKey = key(current)
        val watchdog = Runnable {
            if (!isCurrentSystemPlayback(generation, playbackToken)) return@Runnable
            Log.w(TAG, "系统 TTS 超时未完成，准备恢复当前句：$playbackKey")
            retryOrStopSystemPlayback(playbackKey)
        }
        systemPlaybackWatchdog = watchdog
        mainHandler.postDelayed(
            watchdog,
            TtsPlaybackPolicy.systemUtteranceTimeoutMillis(current.unit.text.length, settings.ttsRate),
        )
    }

    private fun retryOrStopSystemPlayback(playbackKey: String) {
        clearSystemPlaybackWatchdog()
        systemPlaybackToken++
        systemTts.stop()
        if (active && !paused && systemRetryKey == playbackKey && systemRetryCount < SYSTEM_TTS_MAX_RETRIES) {
            systemRetryCount++
            mainHandler.postDelayed(::playCurrent, SYSTEM_TTS_RETRY_DELAY_MS)
            return
        }
        stopPlayback()
    }

    private fun isCurrentSystemPlayback(generation: Int, playbackToken: Int): Boolean =
        active && !paused && generation == sessionGeneration && playbackToken == systemPlaybackToken

    private fun clearSystemPlaybackWatchdog() {
        systemPlaybackWatchdog?.let(mainHandler::removeCallbacks)
        systemPlaybackWatchdog = null
    }

    private fun invalidateSystemPlayback() {
        systemPlaybackToken++
        clearSystemPlaybackWatchdog()
    }

    private fun resetSystemRetry() {
        systemRetryKey = ""
        systemRetryCount = 0
    }

    private fun playMimo(current: SpeechRef) {
        val generation = sessionGeneration
        val key = key(current)
        val next = nextRef()
        mimoPlaybackExecutor.execute {
            try {
                // 第二句若仍在预取，等待并复用该请求，避免重复合成和丢弃先返回的结果。
                val cached = takeOrAwaitPrefetched(key, generation)
                if (!active || paused || generation != sessionGeneration) return@execute
                if (next != null) prefetchMimo(next, generation)
                val pcm = cached ?: mimoPlayback.synthesize(
                    current.unit.text,
                    settings.ttsMimoApiKey,
                    settings.ttsMimoVoice,
                )
                if (!active || paused || generation != sessionGeneration) return@execute
                mimoPlayback.playPcm(pcm, settings.ttsRate)
                mainHandler.post {
                    if (!active || paused || generation != sessionGeneration) return@post
                    advanceOne()
                    playCurrent()
                }
            } catch (error: Exception) {
                Log.e(TAG, "MiMo 当前句播放失败：$key", error)
                mainHandler.post { if (generation == sessionGeneration) stopPlayback() }
            }
        }
    }

    private fun prefetchMimo(next: SpeechRef, generation: Int) {
        val key = key(next)
        synchronized(mimoPrefetchLock) {
            if (key == prefetchedKey && (mimoPrefetchInFlight || prefetchedPcm != null)) return
            prefetchedKey = key
            prefetchedPcm = null
            mimoPrefetchInFlight = true
        }
        mimoPrefetchExecutor.execute {
            try {
                synchronized(mimoPrefetchLock) {
                    if (!active || paused || generation != sessionGeneration || key != prefetchedKey) {
                        if (key == prefetchedKey) mimoPrefetchInFlight = false
                        mimoPrefetchLock.notifyAll()
                        return@execute
                    }
                }
                val pcm = mimoPrefetch.synthesize(next.unit.text, settings.ttsMimoApiKey, settings.ttsMimoVoice)
                synchronized(mimoPrefetchLock) {
                    if (active && !paused && generation == sessionGeneration && key == prefetchedKey) {
                        prefetchedPcm = pcm.takeIf { it.isNotEmpty() }
                        mimoPrefetchInFlight = false
                    }
                    mimoPrefetchLock.notifyAll()
                }
            } catch (error: Exception) {
                Log.w(TAG, "MiMo 下一句预取失败：$key", error)
                synchronized(mimoPrefetchLock) {
                    if (key == prefetchedKey) mimoPrefetchInFlight = false
                    mimoPrefetchLock.notifyAll()
                }
            }
        }
    }

    private fun takeOrAwaitPrefetched(key: String, generation: Int): ByteArray? = synchronized(mimoPrefetchLock) {
        if (key != prefetchedKey) return@synchronized null
        val deadline = SystemClock.uptimeMillis() + MIMO_PREFETCH_WAIT_TIMEOUT_MS
        while (key == prefetchedKey && mimoPrefetchInFlight && active && !paused && generation == sessionGeneration) {
            val remaining = deadline - SystemClock.uptimeMillis()
            if (remaining <= 0L) break
            try {
                mimoPrefetchLock.wait(remaining)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        if (key != prefetchedKey || generation != sessionGeneration) return@synchronized null
        val result = prefetchedPcm ?: return@synchronized null
        prefetchedPcm = null
        prefetchedKey = ""
        mimoPrefetchInFlight = false
        result
    }

    private fun clearPrefetch() {
        synchronized(mimoPrefetchLock) {
            prefetchedPcm = null
            prefetchedKey = ""
            mimoPrefetchInFlight = false
            mimoPrefetchLock.notifyAll()
        }
    }

    private fun pausePlayback() {
        if (!active || paused) return
        paused = true
        sessionGeneration++
        invalidateSystemPlayback()
        resetSystemRetry()
        systemTts.pause()
        mimoPlayback.cancel()
        mimoPrefetch.cancel()
        clearPrefetch()
        publish(TtsPlaybackSnapshot.STATE_PAUSED, currentRef())
    }

    private fun resumePlayback() {
        if (!active || !paused) return
        paused = false
        sessionGeneration++
        playCurrent()
    }

    private fun stopPlayback() {
        if (!active && !foregroundStarted) return
        active = false
        paused = false
        sessionGeneration++
        invalidateSystemPlayback()
        resetSystemRetry()
        systemTts.stop()
        mimoPlayback.cancel()
        mimoPrefetch.cancel()
        clearPrefetch()
        mainHandler.removeCallbacks(sleepTimeout)
        sleepDeadlineElapsed = 0L
        snapshot = stoppedSnapshot()
        notifyListeners()
        updateMediaSession()
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        stopSelf()
    }

    private fun setSleepTimer(durationMillis: Long) {
        mainHandler.removeCallbacks(sleepTimeout)
        if (durationMillis <= 0L) {
            sleepDeadlineElapsed = 0L
        } else {
            sleepDeadlineElapsed = TtsSleepTimer.deadlineFrom(SystemClock.elapsedRealtime(), durationMillis)
            mainHandler.postDelayed(sleepTimeout, durationMillis)
        }
        val current = currentRef()
        publish(
            if (paused) TtsPlaybackSnapshot.STATE_PAUSED
            else if (active) TtsPlaybackSnapshot.STATE_PLAYING
            else TtsPlaybackSnapshot.STATE_STOPPED,
            current,
        )
    }

    private fun buildUnits(chapterIndex: Int): List<SpeechUnit> {
        val units = ArrayList<SpeechUnit>()
        if (chapterIndex < 0 || chapterIndex >= chapters.size) return units
        val chapter = chapters[chapterIndex]
        val text = ReplacementEngine.apply(
            database.resolveChapterText(
                chapter.bookId,
                chapter.id,
                chapter.bodyText,
                chapter.bodyTextPath,
                chapter.bodyTextStorage,
            ),
            rules,
        )
        val matcher = SEGMENT_PATTERN.matcher(text)
        var groupStart = -1
        var groupEnd = -1
        val grouped = StringBuilder()
        while (matcher.find()) {
            val segment = matcher.group()
            if (segment == null || segment.trim().isEmpty()) continue
            if (groupStart < 0) groupStart = matcher.start()
            groupEnd = matcher.end()
            grouped.append(segment)
            if (endsSentence(segment)) {
                units.add(SpeechUnit(groupStart, groupEnd, grouped.toString().trim()))
                groupStart = -1
                grouped.setLength(0)
            }
        }
        if (groupStart >= 0 && grouped.isNotEmpty()) {
            units.add(SpeechUnit(groupStart, groupEnd, grouped.toString().trim()))
        }
        return units
    }

    private fun firstUnitAtOrAfter(units: List<SpeechUnit>, offset: Int): Int {
        var index = 0
        while (index < units.size && units[index].end <= offset) index++
        return index
    }

    private fun advanceToNextChapter(): Boolean {
        while (currentChapterIndex + 1 < chapters.size) {
            currentChapterIndex++
            currentUnits = buildUnits(currentChapterIndex)
            currentUnitIndex = 0
            if (currentUnits.isNotEmpty()) return true
        }
        return false
    }

    private fun advanceOne() {
        currentUnitIndex++
        if (currentUnitIndex < currentUnits.size) return
        if (!advanceToNextChapter()) stopPlayback()
    }

    private fun currentRef(): SpeechRef? {
        if (!active || currentChapterIndex < 0 || currentChapterIndex >= chapters.size ||
            currentUnitIndex < 0 || currentUnitIndex >= currentUnits.size
        ) return null
        return SpeechRef(currentChapterIndex, currentUnits[currentUnitIndex])
    }

    private fun nextRef(): SpeechRef? {
        if (currentUnitIndex + 1 < currentUnits.size) {
            return SpeechRef(currentChapterIndex, currentUnits[currentUnitIndex + 1])
        }
        for (index in currentChapterIndex + 1 until chapters.size) {
            val units = buildUnits(index)
            if (units.isNotEmpty()) return SpeechRef(index, units[0])
        }
        return null
    }

    private fun publishLoading(bookId: Long) {
        snapshot = TtsPlaybackSnapshot(
            bookId,
            "正在准备听书",
            "",
            -1,
            -1,
            -1,
            TtsPlaybackSnapshot.STATE_LOADING,
            sleepDeadlineElapsed,
        )
        notifyListeners()
        updateNotification()
    }

    private fun publish(state: String, current: SpeechRef?) {
        val bookTitle = book?.title
        val chapterTitle = if (current == null || current.chapterIndex >= chapters.size) "" else chapters[current.chapterIndex].title
        snapshot = TtsPlaybackSnapshot(
            book?.id ?: -1L,
            bookTitle,
            chapterTitle,
            current?.chapterIndex ?: -1,
            current?.unit?.start ?: -1,
            current?.unit?.end ?: -1,
            state,
            sleepDeadlineElapsed,
        )
        notifyListeners()
        updateMediaSession()
        updateNotification()
    }

    private fun notifyListeners() {
        val current = snapshot
        for (listener in listeners) listener.onPlaybackChanged(current)
    }

    private fun createMediaSession() {
        mediaSession = MediaSession(this, "PacilReadTts")
        mediaSession.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = resumePlayback()
            override fun onPause() = pausePlayback()
            override fun onStop() = stopPlayback()
        })
        mediaSession.isActive = true
    }

    private fun updateMediaSession() {
        val state = if (!active) PlaybackState.STATE_STOPPED else if (paused) PlaybackState.STATE_PAUSED else PlaybackState.STATE_PLAYING
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP
        mediaSession.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build(),
        )
        mediaSession.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, snapshot.bookTitle)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, snapshot.chapterTitle)
                .build(),
        )
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "听书播放", NotificationManager.IMPORTANCE_LOW)
        channel.description = "显示后台听书播放状态和控制按钮"
        notificationManager.createNotificationChannel(channel)
    }

    private fun ensureForeground(title: String?, text: String?) {
        startForeground(NOTIFICATION_ID, buildNotification(title, text, false))
        foregroundStarted = true
    }

    private fun updateNotification() {
        if (!foregroundStarted) return
        notificationManager.notify(
            NOTIFICATION_ID,
            buildNotification(snapshot.bookTitle, snapshot.chapterTitle, snapshot.isPaused()),
        )
    }

    private fun buildNotification(title: String?, text: String?, isPaused: Boolean): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, ReaderActivity::class.java)
                .putExtra("book_id", snapshot.bookId)
                .putExtra("bookmark_chapter_order_index", snapshot.chapterIndex)
                .putExtra("bookmark_chapter_offset", snapshot.sentenceStart)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            pendingFlags(),
        )
        val toggleIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, TtsPlaybackService::class.java).setAction(if (isPaused) ACTION_RESUME else ACTION_PAUSE),
            pendingFlags(),
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, TtsPlaybackService::class.java).setAction(ACTION_STOP),
            pendingFlags(),
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(if (title.isNullOrBlank()) "PacilRead 听书" else title)
            .setContentText(text.orEmpty())
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(active && !paused)
            .addAction(Notification.Action.Builder(0, if (isPaused) "继续" else "暂停", toggleIntent).build())
            .addAction(Notification.Action.Builder(0, "停止", stopIntent).build())
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1),
            )
            .build()
    }

    private fun pendingFlags(): Int = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    private fun key(ref: SpeechRef): String = "${ref.chapterIndex}:${ref.unit.start}:${ref.unit.end}"

    private fun endsSentence(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val trimmed = text.trim()
        return when (trimmed[trimmed.length - 1]) {
            '。', '！', '？', '!', '?' -> true
            else -> false
        }
    }

    private fun clamp(value: Int, low: Int, high: Int): Int = max(low, min(value, high))

    private fun stoppedSnapshot(): TtsPlaybackSnapshot = TtsPlaybackSnapshot(
        -1L,
        "",
        "",
        -1,
        -1,
        -1,
        TtsPlaybackSnapshot.STATE_STOPPED,
        0L,
    )

    private class SpeechUnit(val start: Int, val end: Int, val text: String)
    private class SpeechRef(val chapterIndex: Int, val unit: SpeechUnit)

    companion object {
        private const val TAG = "TtsPlaybackService"
        const val ACTION_START = "com.metahumanz.pacilread.tts.START"
        const val ACTION_PAUSE = "com.metahumanz.pacilread.tts.PAUSE"
        const val ACTION_RESUME = "com.metahumanz.pacilread.tts.RESUME"
        const val ACTION_STOP = "com.metahumanz.pacilread.tts.STOP"
        const val ACTION_SET_TIMER = "com.metahumanz.pacilread.tts.SET_TIMER"
        const val EXTRA_BOOK_ID = "book_id"
        const val EXTRA_CHAPTER_INDEX = "chapter_index"
        const val EXTRA_CHAR_OFFSET = "char_offset"
        const val EXTRA_TIMER_MILLIS = "timer_millis"
        private const val CHANNEL_ID = "tts_playback"
        private const val NOTIFICATION_ID = 4021
        private const val SYSTEM_TTS_MAX_RETRIES = 1
        private const val SYSTEM_TTS_RETRY_DELAY_MS = 250L
        private const val MIMO_PREFETCH_WAIT_TIMEOUT_MS = 65_000L
        private val SEGMENT_PATTERN: Pattern = Pattern.compile("[^ \\n\\t。！？.!?,，;；、]+[。！？.!?,，;；、]*")
    }
}
