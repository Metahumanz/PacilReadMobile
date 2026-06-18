package com.metahumanz.pacilread.tts;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import com.metahumanz.pacilread.R;
import com.metahumanz.pacilread.ReaderActivity;
import com.metahumanz.pacilread.model.BookRecord;
import com.metahumanz.pacilread.model.ChapterRecord;
import com.metahumanz.pacilread.model.ReplacementRuleRecord;
import com.metahumanz.pacilread.reader.ReplacementEngine;
import com.metahumanz.pacilread.storage.JsonDatabase;
import com.metahumanz.pacilread.storage.SettingsStore;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TtsPlaybackService extends Service {
    public static final String ACTION_START = "com.metahumanz.pacilread.tts.START";
    public static final String ACTION_PAUSE = "com.metahumanz.pacilread.tts.PAUSE";
    public static final String ACTION_RESUME = "com.metahumanz.pacilread.tts.RESUME";
    public static final String ACTION_STOP = "com.metahumanz.pacilread.tts.STOP";
    public static final String ACTION_SET_TIMER = "com.metahumanz.pacilread.tts.SET_TIMER";
    public static final String EXTRA_BOOK_ID = "book_id";
    public static final String EXTRA_CHAPTER_INDEX = "chapter_index";
    public static final String EXTRA_CHAR_OFFSET = "char_offset";
    public static final String EXTRA_TIMER_MILLIS = "timer_millis";

    private static final String CHANNEL_ID = "tts_playback";
    private static final int NOTIFICATION_ID = 4021;
    private static final Pattern SEGMENT_PATTERN =
            Pattern.compile("[^ \\n\\t。！？.!?,，;；、]+[。！？.!?,，;；、]*");

    public interface Listener {
        void onPlaybackChanged(TtsPlaybackSnapshot snapshot);
    }

    public final class LocalBinder extends Binder {
        public TtsPlaybackSnapshot snapshot() {
            return snapshot;
        }

        public void addListener(Listener listener) {
            if (listener == null) return;
            listeners.add(listener);
            mainHandler.post(() -> listener.onPlaybackChanged(snapshot));
        }

        public void removeListener(Listener listener) {
            listeners.remove(listener);
        }
    }

    private final LocalBinder binder = new LocalBinder();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService loadExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService mimoPlaybackExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService mimoPrefetchExecutor = Executors.newSingleThreadExecutor();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Runnable sleepTimeout = this::stopPlayback;

    private JsonDatabase database;
    private SettingsStore settings;
    private SystemTtsClient systemTts;
    private MimoTtsClient mimoPlayback;
    private MimoTtsClient mimoPrefetch;
    private MediaSession mediaSession;
    private NotificationManager notificationManager;

    private BookRecord book;
    private List<ChapterRecord> chapters = new ArrayList<>();
    private List<ReplacementRuleRecord> rules = new ArrayList<>();
    private List<SpeechUnit> currentUnits = new ArrayList<>();
    private int currentChapterIndex = -1;
    private int currentUnitIndex = -1;
    private boolean active;
    private boolean paused;
    private boolean foregroundStarted;
    private long sleepDeadlineElapsed;
    private int sessionGeneration;
    private int systemReadyRetries;
    private byte[] prefetchedPcm;
    private String prefetchedKey = "";
    private volatile TtsPlaybackSnapshot snapshot = stoppedSnapshot();

    @Override
    public void onCreate() {
        super.onCreate();
        database = JsonDatabase.getInstance(this);
        settings = new SettingsStore(this);
        systemTts = new SystemTtsClient(this, settings.getTtsSystemEnginePackage());
        systemTts.setAudioFocusLossListener(this::pausePlayback);
        mimoPlayback = new MimoTtsClient();
        mimoPrefetch = new MimoTtsClient();
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createNotificationChannel();
        createMediaSession();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_START.equals(action)) {
            ensureForeground("正在准备听书", "加载章节...");
            setSleepTimer(intent.getLongExtra(EXTRA_TIMER_MILLIS, 0L));
            startSession(
                    intent.getLongExtra(EXTRA_BOOK_ID, -1L),
                    intent.getIntExtra(EXTRA_CHAPTER_INDEX, 0),
                    intent.getIntExtra(EXTRA_CHAR_OFFSET, 0)
            );
        } else if (ACTION_PAUSE.equals(action)) {
            pausePlayback();
        } else if (ACTION_RESUME.equals(action)) {
            resumePlayback();
        } else if (ACTION_STOP.equals(action)) {
            stopPlayback();
        } else if (ACTION_SET_TIMER.equals(action)) {
            if (active) {
                setSleepTimer(intent.getLongExtra(EXTRA_TIMER_MILLIS, 0L));
            } else {
                stopSelf(startId);
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        sessionGeneration++;
        mainHandler.removeCallbacksAndMessages(null);
        loadExecutor.shutdownNow();
        mimoPlaybackExecutor.shutdownNow();
        mimoPrefetchExecutor.shutdownNow();
        systemTts.shutdown();
        mimoPlayback.cancel();
        mimoPrefetch.cancel();
        mediaSession.release();
        super.onDestroy();
    }

    private void startSession(long bookId, int chapterIndex, int charOffset) {
        int generation = ++sessionGeneration;
        systemTts.setEngine(settings.getTtsSystemEnginePackage());
        active = true;
        paused = false;
        systemReadyRetries = 0;
        publishLoading(bookId);
        systemTts.stop();
        mimoPlayback.cancel();
        mimoPrefetch.cancel();
        clearPrefetch();
        loadExecutor.execute(() -> {
            BookRecord loadedBook = database.getBook(bookId);
            List<ChapterRecord> loadedChapters = database.getChapters(bookId, false);
            List<ReplacementRuleRecord> loadedRules = database.getReplacementRules(bookId);
            mainHandler.post(() -> {
                if (generation != sessionGeneration || !active) return;
                if (loadedBook == null || loadedChapters.isEmpty()) {
                    stopPlayback();
                    return;
                }
                book = loadedBook;
                chapters = loadedChapters;
                rules = loadedRules;
                currentChapterIndex = clamp(chapterIndex, 0, chapters.size() - 1);
                currentUnits = buildUnits(currentChapterIndex);
                currentUnitIndex = firstUnitAtOrAfter(currentUnits, Math.max(0, charOffset));
                if (currentUnitIndex >= currentUnits.size() && !advanceToNextChapter()) {
                    stopPlayback();
                    return;
                }
                playCurrent();
            });
        });
    }

    private void playCurrent() {
        if (!active || paused) return;
        SpeechRef current = currentRef();
        if (current == null) {
            stopPlayback();
            return;
        }
        if (!"mimo".equals(settings.getTtsEngine()) && !systemTts.isInitSuccess()) {
            if (systemReadyRetries++ < 40) {
                mainHandler.postDelayed(this::playCurrent, 150L);
            } else {
                stopPlayback();
            }
            return;
        }
        systemReadyRetries = 0;
        database.updateProgress(book.id, current.chapterIndex, current.unit.start);
        publish(TtsPlaybackSnapshot.STATE_PLAYING, current);
        if (!systemTts.requestAudioFocus()) {
            stopPlayback();
            return;
        }
        if ("mimo".equals(settings.getTtsEngine())) playMimo(current);
        else playSystemBatch(current);
    }

    private void playSystemBatch(SpeechRef current) {
        SpeechRef next = nextRef();
        List<String> texts = new ArrayList<>();
        texts.add(current.unit.text);
        if (next != null) texts.add(next.unit.text);
        int generation = sessionGeneration;
        int batchSize = texts.size();
        int[] completed = new int[]{0};
        systemTts.speakAll(texts, settings.getTtsRate(), new SystemTtsClient.SpeakCallback() {
            @Override public void onStart() {}

            @Override
            public void onDone() {
                mainHandler.post(() -> {
                    if (!active || paused || generation != sessionGeneration) return;
                    completed[0]++;
                    advanceOne();
                    SpeechRef now = currentRef();
                    if (now != null) {
                        database.updateProgress(book.id, now.chapterIndex, now.unit.start);
                        publish(TtsPlaybackSnapshot.STATE_PLAYING, now);
                    }
                    if (completed[0] >= batchSize) playCurrent();
                });
            }

            @Override
            public void onError(String message) {
                mainHandler.post(() -> {
                    if (generation == sessionGeneration) stopPlayback();
                });
            }
        });
    }

    private void playMimo(SpeechRef current) {
        int generation = sessionGeneration;
        String key = key(current);
        byte[] cached = takePrefetched(key);
        SpeechRef next = nextRef();
        if (next != null) prefetchMimo(next, generation);
        mimoPlaybackExecutor.execute(() -> {
            try {
                byte[] pcm = cached != null ? cached : mimoPlayback.synthesize(
                        current.unit.text, settings.getTtsMimoApiKey(), settings.getTtsMimoVoice());
                if (!active || paused || generation != sessionGeneration) return;
                mimoPlayback.playPcm(pcm, settings.getTtsRate());
                mainHandler.post(() -> {
                    if (!active || paused || generation != sessionGeneration) return;
                    advanceOne();
                    playCurrent();
                });
            } catch (Exception error) {
                mainHandler.post(() -> {
                    if (generation == sessionGeneration) stopPlayback();
                });
            }
        });
    }

    private void prefetchMimo(SpeechRef next, int generation) {
        String key = key(next);
        synchronized (this) {
            if (key.equals(prefetchedKey)) return;
            prefetchedKey = key;
            prefetchedPcm = null;
        }
        mimoPrefetchExecutor.execute(() -> {
            try {
                byte[] pcm = mimoPrefetch.synthesize(
                        next.unit.text, settings.getTtsMimoApiKey(), settings.getTtsMimoVoice());
                synchronized (TtsPlaybackService.this) {
                    if (active && generation == sessionGeneration && key.equals(prefetchedKey)) {
                        prefetchedPcm = pcm;
                    }
                }
            } catch (Exception ignored) {
            }
        });
    }

    private synchronized byte[] takePrefetched(String key) {
        if (!key.equals(prefetchedKey) || prefetchedPcm == null) return null;
        byte[] result = prefetchedPcm;
        prefetchedPcm = null;
        prefetchedKey = "";
        return result;
    }

    private synchronized void clearPrefetch() {
        prefetchedPcm = null;
        prefetchedKey = "";
    }

    private void pausePlayback() {
        if (!active || paused) return;
        paused = true;
        sessionGeneration++;
        systemTts.pause();
        mimoPlayback.cancel();
        mimoPrefetch.cancel();
        clearPrefetch();
        SpeechRef current = currentRef();
        publish(TtsPlaybackSnapshot.STATE_PAUSED, current);
    }

    private void resumePlayback() {
        if (!active || !paused) return;
        paused = false;
        sessionGeneration++;
        playCurrent();
    }

    private void stopPlayback() {
        if (!active && !foregroundStarted) return;
        active = false;
        paused = false;
        sessionGeneration++;
        systemTts.stop();
        mimoPlayback.cancel();
        mimoPrefetch.cancel();
        clearPrefetch();
        mainHandler.removeCallbacks(sleepTimeout);
        sleepDeadlineElapsed = 0L;
        snapshot = stoppedSnapshot();
        notifyListeners();
        updateMediaSession();
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            foregroundStarted = false;
        }
        stopSelf();
    }

    private void setSleepTimer(long durationMillis) {
        mainHandler.removeCallbacks(sleepTimeout);
        if (durationMillis <= 0L) {
            sleepDeadlineElapsed = 0L;
        } else {
            sleepDeadlineElapsed = TtsSleepTimer.deadlineFrom(
                    SystemClock.elapsedRealtime(), durationMillis);
            mainHandler.postDelayed(sleepTimeout, durationMillis);
        }
        SpeechRef current = currentRef();
        publish(paused ? TtsPlaybackSnapshot.STATE_PAUSED
                : active ? TtsPlaybackSnapshot.STATE_PLAYING : TtsPlaybackSnapshot.STATE_STOPPED, current);
    }

    private List<SpeechUnit> buildUnits(int chapterIndex) {
        List<SpeechUnit> units = new ArrayList<>();
        if (chapterIndex < 0 || chapterIndex >= chapters.size()) return units;
        ChapterRecord chapter = chapters.get(chapterIndex);
        String text = ReplacementEngine.apply(database.resolveChapterText(
                chapter.bookId, chapter.id, chapter.bodyText, chapter.bodyTextPath, chapter.bodyTextStorage), rules);
        Matcher matcher = SEGMENT_PATTERN.matcher(text);
        int groupStart = -1;
        int groupEnd = -1;
        StringBuilder grouped = new StringBuilder();
        while (matcher.find()) {
            String segment = matcher.group();
            if (segment == null || segment.trim().isEmpty()) continue;
            if (groupStart < 0) groupStart = matcher.start();
            groupEnd = matcher.end();
            grouped.append(segment);
            if (endsSentence(segment)) {
                units.add(new SpeechUnit(groupStart, groupEnd, grouped.toString().trim()));
                groupStart = -1;
                grouped.setLength(0);
            }
        }
        if (groupStart >= 0 && grouped.length() > 0) {
            units.add(new SpeechUnit(groupStart, groupEnd, grouped.toString().trim()));
        }
        return units;
    }

    private int firstUnitAtOrAfter(List<SpeechUnit> units, int offset) {
        int index = 0;
        while (index < units.size() && units.get(index).end <= offset) index++;
        return index;
    }

    private boolean advanceToNextChapter() {
        while (currentChapterIndex + 1 < chapters.size()) {
            currentChapterIndex++;
            currentUnits = buildUnits(currentChapterIndex);
            currentUnitIndex = 0;
            if (!currentUnits.isEmpty()) return true;
        }
        return false;
    }

    private void advanceOne() {
        currentUnitIndex++;
        if (currentUnitIndex < currentUnits.size()) return;
        if (!advanceToNextChapter()) stopPlayback();
    }

    private SpeechRef currentRef() {
        if (!active || currentChapterIndex < 0 || currentChapterIndex >= chapters.size()
                || currentUnitIndex < 0 || currentUnitIndex >= currentUnits.size()) return null;
        return new SpeechRef(currentChapterIndex, currentUnits.get(currentUnitIndex));
    }

    private SpeechRef nextRef() {
        if (currentUnitIndex + 1 < currentUnits.size()) {
            return new SpeechRef(currentChapterIndex, currentUnits.get(currentUnitIndex + 1));
        }
        for (int index = currentChapterIndex + 1; index < chapters.size(); index++) {
            List<SpeechUnit> units = buildUnits(index);
            if (!units.isEmpty()) return new SpeechRef(index, units.get(0));
        }
        return null;
    }

    private void publishLoading(long bookId) {
        snapshot = new TtsPlaybackSnapshot(bookId, "正在准备听书", "", -1, -1, -1,
                TtsPlaybackSnapshot.STATE_LOADING, sleepDeadlineElapsed);
        notifyListeners();
        updateNotification();
    }

    private void publish(String state, SpeechRef current) {
        String bookTitle = book == null ? "" : book.title;
        String chapterTitle = current == null || current.chapterIndex >= chapters.size()
                ? "" : chapters.get(current.chapterIndex).title;
        int start = current == null ? -1 : current.unit.start;
        int end = current == null ? -1 : current.unit.end;
        int chapterIndex = current == null ? -1 : current.chapterIndex;
        snapshot = new TtsPlaybackSnapshot(
                book == null ? -1L : book.id,
                bookTitle,
                chapterTitle,
                chapterIndex,
                start,
                end,
                state,
                sleepDeadlineElapsed
        );
        notifyListeners();
        updateMediaSession();
        updateNotification();
    }

    private void notifyListeners() {
        TtsPlaybackSnapshot current = snapshot;
        for (Listener listener : listeners) listener.onPlaybackChanged(current);
    }

    private void createMediaSession() {
        mediaSession = new MediaSession(this, "PacilReadTts");
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { resumePlayback(); }
            @Override public void onPause() { pausePlayback(); }
            @Override public void onStop() { stopPlayback(); }
        });
        mediaSession.setActive(true);
    }

    private void updateMediaSession() {
        int state = !active ? PlaybackState.STATE_STOPPED
                : paused ? PlaybackState.STATE_PAUSED : PlaybackState.STATE_PLAYING;
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_STOP;
        mediaSession.setPlaybackState(new PlaybackState.Builder()
                .setActions(actions)
                .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build());
        mediaSession.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, snapshot.bookTitle)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, snapshot.chapterTitle)
                .build());
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "听书播放", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("显示后台听书播放状态和控制按钮");
        notificationManager.createNotificationChannel(channel);
    }

    private void ensureForeground(String title, String text) {
        Notification notification = buildNotification(title, text, false);
        startForeground(NOTIFICATION_ID, notification);
        foregroundStarted = true;
    }

    private void updateNotification() {
        if (!foregroundStarted) return;
        notificationManager.notify(NOTIFICATION_ID,
                buildNotification(snapshot.bookTitle, snapshot.chapterTitle, snapshot.isPaused()));
    }

    private Notification buildNotification(String title, String text, boolean isPaused) {
        PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                0,
                new Intent(this, ReaderActivity.class)
                        .putExtra("book_id", snapshot.bookId)
                        .putExtra("bookmark_chapter_order_index", snapshot.chapterIndex)
                        .putExtra("bookmark_chapter_offset", snapshot.sentenceStart)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
                pendingFlags()
        );
        PendingIntent toggleIntent = PendingIntent.getService(
                this,
                1,
                new Intent(this, TtsPlaybackService.class)
                        .setAction(isPaused ? ACTION_RESUME : ACTION_PAUSE),
                pendingFlags()
        );
        PendingIntent stopIntent = PendingIntent.getService(
                this,
                2,
                new Intent(this, TtsPlaybackService.class).setAction(ACTION_STOP),
                pendingFlags()
        );
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title == null || title.isBlank() ? "PacilRead 听书" : title)
                .setContentText(text == null ? "" : text)
                .setContentIntent(contentIntent)
                .setOnlyAlertOnce(true)
                .setOngoing(active && !paused)
                .addAction(new Notification.Action.Builder(
                        0, isPaused ? "继续" : "暂停", toggleIntent).build())
                .addAction(new Notification.Action.Builder(0, "停止", stopIntent).build())
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1))
                .build();
    }

    private int pendingFlags() {
        return PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
    }

    private String key(SpeechRef ref) {
        return ref.chapterIndex + ":" + ref.unit.start + ":" + ref.unit.end;
    }

    private boolean endsSentence(String text) {
        if (text == null || text.isBlank()) return false;
        char last = text.trim().charAt(text.trim().length() - 1);
        return last == '。' || last == '！' || last == '？' || last == '!' || last == '?';
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private TtsPlaybackSnapshot stoppedSnapshot() {
        return new TtsPlaybackSnapshot(-1L, "", "", -1, -1, -1,
                TtsPlaybackSnapshot.STATE_STOPPED, 0L);
    }

    private static final class SpeechUnit {
        final int start;
        final int end;
        final String text;

        SpeechUnit(int start, int end, String text) {
            this.start = start;
            this.end = end;
            this.text = text;
        }
    }

    private static final class SpeechRef {
        final int chapterIndex;
        final SpeechUnit unit;

        SpeechRef(int chapterIndex, SpeechUnit unit) {
            this.chapterIndex = chapterIndex;
            this.unit = unit;
        }
    }
}
