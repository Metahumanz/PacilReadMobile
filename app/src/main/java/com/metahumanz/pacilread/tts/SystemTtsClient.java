package com.metahumanz.pacilread.tts;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.TextUtils;
import android.util.Log;

import com.metahumanz.pacilread.R;

import java.util.ArrayList;
import java.util.List;

public final class SystemTtsClient {
    private static final String TAG = "SystemTts";

    public interface SpeakCallback {
        void onStart();
        void onDone();
        void onError(String message);
    }

    public static final class EngineInfo {
        public final String packageName;
        public final String label;

        public EngineInfo(String packageName, String label) {
            this.packageName = packageName == null ? "" : packageName;
            this.label = label == null ? "" : label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();

    private volatile TextToSpeech tts;
    private volatile boolean initSuccess = false;
    private volatile SpeakCallback currentCallback;
    private volatile int queuedCount = 0;
    private volatile int completedCount = 0;
    private volatile boolean paused = false;
    private volatile boolean firstSpeak = true;

    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private boolean hasAudioFocus = false;

    public SystemTtsClient(Context context) {
        this(context, "");
    }

    public SystemTtsClient(Context context, String enginePackageName) {
        appContext = context.getApplicationContext();
        audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);

        String engine = (enginePackageName == null || enginePackageName.trim().isEmpty())
                ? null : enginePackageName.trim();
        ensureTtsOnMain(engine);
    }

    private void ensureTtsOnMain(String enginePackageName) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            initTts(enginePackageName);
        } else {
            mainHandler.post(() -> initTts(enginePackageName));
        }
    }

    private void initTts(String enginePackageName) {
        synchronized (lock) {
            if (tts != null) {
                try { tts.shutdown(); } catch (Exception ignore) {}
                tts = null;
            }
            initSuccess = false;
            if (enginePackageName == null) {
                tts = new TextToSpeech(appContext, this::onInit);
            } else {
                tts = new TextToSpeech(appContext, this::onInit, enginePackageName);
            }
        }
    }

    private void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            TextToSpeech engine = tts;
            if (engine != null) {
                Log.i(TAG, "TTS init SUCCESS");
                engine.setOnUtteranceProgressListener(new Listener());
                initSuccess = true;
            }
        } else {
            Log.e(TAG, "TTS init FAILED, status=" + status);
        }
    }

    // ---------- Speak ----------
    public void speak(String text, float rate, SpeakCallback callback) {
        if (TextUtils.isEmpty(text)) {
            if (callback != null) callback.onDone();
            return;
        }
        TextToSpeech engine = tts;
        if (!initSuccess || engine == null) {
            if (callback != null) callback.onError("系统 TTS 未就绪");
            return;
        }
        currentCallback = callback;
        paused = false;
        engine.setSpeechRate(Math.max(0.5f, Math.min(rate, 3.0f)));
        int result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null,
                "tts_" + System.currentTimeMillis());
        if (result != TextToSpeech.SUCCESS) {
            currentCallback = null;
            if (callback != null) callback.onError("系统 TTS 开始朗读失败");
        }
    }

    /**
     * Queue all texts at once like Legado does — first QUEUE_FLUSH, rest QUEUE_ADD.
     * This keeps the TTS engine's internal queue non-empty, preventing gaps.
     */
    public void speakAll(List<String> texts, float rate, SpeakCallback callback) {
        if (texts == null || texts.isEmpty()) {
            if (callback != null) callback.onDone();
            return;
        }
        TextToSpeech engine = tts;
        if (!initSuccess || engine == null) {
            if (callback != null) callback.onError("系统 TTS 未就绪");
            return;
        }
        paused = false;
        currentCallback = callback;
        engine.setSpeechRate(Math.max(0.5f, Math.min(rate, 3.0f)));

        completedCount = 0;
        int count = 0;
        boolean first = firstSpeak;
        firstSpeak = false;
        for (int i = 0; i < texts.size(); i++) {
            String text = texts.get(i);
            if (TextUtils.isEmpty(text)) continue;
            int mode = (first && i == 0) ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD;
            int result = engine.speak(text, mode, null,
                    "tts_" + System.currentTimeMillis());
            if (result != TextToSpeech.SUCCESS) {
                currentCallback = null;
                queuedCount = 0;
                completedCount = 0;
                if (callback != null) callback.onError("系统 TTS 朗读失败");
                return;
            }
            count++;
        }
        queuedCount = count;
    }

    // ---------- Control ----------
    public void pause() {
        paused = true;
        TextToSpeech engine = tts;
        if (engine != null) {
            try { engine.stop(); } catch (Exception ignore) {}
        }
        currentCallback = null;
    }

    public void stop() {
        paused = false;
        firstSpeak = true;
        TextToSpeech engine = tts;
        if (engine != null) {
            try { engine.stop(); } catch (Exception ignore) {}
        }
        currentCallback = null;
        abandonAudioFocus();
    }

    public void shutdown() {
        stop();
        firstSpeak = true;
        synchronized (lock) {
            if (tts != null) {
                try { tts.shutdown(); } catch (Exception ignore) {}
                tts = null;
            }
            initSuccess = false;
        }
    }

    public boolean isInitSuccess() {
        return initSuccess && tts != null;
    }

    // ---------- Engine management ----------
    public void setEngine(String enginePackageName) {
        String engine = (enginePackageName == null || enginePackageName.trim().isEmpty())
                ? null : enginePackageName.trim();
        ensureTtsOnMain(engine);
    }

    public static List<EngineInfo> queryAvailableEngines(Context context) {
        List<EngineInfo> list = new ArrayList<>();
        TextToSpeech temp = null;
        try {
            temp = new TextToSpeech(context.getApplicationContext(), null);
            List<TextToSpeech.EngineInfo> engines = temp.getEngines();
            if (engines != null) {
                for (TextToSpeech.EngineInfo info : engines) {
                    list.add(new EngineInfo(info.name, info.label));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to query engines", e);
        } finally {
            if (temp != null) {
                try { temp.shutdown(); } catch (Exception ignore) {}
            }
        }
        return list;
    }

    // ---------- Audio focus ----------
    public boolean requestAudioFocus() {
        if (audioManager == null) return false;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                AudioAttributes attrs = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build();
                focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(attrs)
                        .setOnAudioFocusChangeListener(this::onAudioFocusChange)
                        .build();
                int result = audioManager.requestAudioFocus(focusRequest);
                hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
            } else {
                int result = audioManager.requestAudioFocus(
                        this::onAudioFocusChangeLegacy,
                        AudioManager.STREAM_MUSIC,
                        AudioManager.AUDIOFOCUS_GAIN);
                hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
            }
            if (hasAudioFocus && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                playSilentSound();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to request audio focus", e);
            hasAudioFocus = false;
        }
        return hasAudioFocus;
    }

    public void abandonAudioFocus() {
        if (!hasAudioFocus || audioManager == null) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
                audioManager.abandonAudioFocusRequest(focusRequest);
            } else {
                audioManager.abandonAudioFocus(null);
            }
        } catch (Exception ignore) {}
        hasAudioFocus = false;
    }

    private void onAudioFocusChange(int focusChange) {
        handleAudioFocusChange(focusChange);
    }

    private void onAudioFocusChangeLegacy(int focusChange) {
        handleAudioFocusChange(focusChange);
    }

    private void handleAudioFocusChange(int focusChange) {
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS ||
                focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            pause();
        }
    }

    private void playSilentSound() {
        try {
            MediaPlayer mp = MediaPlayer.create(appContext, R.raw.silent_sound);
            if (mp != null) {
                mp.start();
                mp.setOnCompletionListener(MediaPlayer::release);
            }
        } catch (Exception ignore) {}
    }

    // ---------- Utterance listener ----------
    private class Listener extends UtteranceProgressListener {
        @Override
        public void onStart(String utteranceId) {
            Log.d(TAG, "onStart id=" + utteranceId + " cb=" + (currentCallback != null));
            SpeakCallback cb = currentCallback;
            if (cb != null) cb.onStart();
        }

        @Override
        public void onDone(String utteranceId) {
            int done = completedCount + 1;
            completedCount = done;
            int remaining = queuedCount - done;
            SpeakCallback cb = currentCallback;
            Log.d(TAG, "onDone id=" + utteranceId + " done=" + done + " queued=" + queuedCount + " remaining=" + remaining + " cb=" + (cb != null));
            if (remaining <= 0) {
                currentCallback = null;
                queuedCount = 0;
                completedCount = 0;
            }
            if (cb != null) cb.onDone();
        }

        @Override
        public void onError(String utteranceId) {
            Log.e(TAG, "onError id=" + utteranceId + " cb=" + (currentCallback != null));
            SpeakCallback cb = currentCallback;
            currentCallback = null;
            queuedCount = 0;
            completedCount = 0;
            if (cb != null) cb.onError("系统 TTS 朗读失败");
        }

        @Override
        public void onError(String utteranceId, int errorCode) {
            Log.e(TAG, "onError id=" + utteranceId + " code=" + errorCode + " cb=" + (currentCallback != null));
            SpeakCallback cb = currentCallback;
            currentCallback = null;
            queuedCount = 0;
            completedCount = 0;
            if (cb != null) cb.onError("系统 TTS 朗读失败 (code=" + errorCode + ")");
        }

        @Override
        public void onStop(String utteranceId, boolean interrupted) {
            Log.d(TAG, "onStop id=" + utteranceId + " interrupted=" + interrupted + " paused=" + paused + " cb=" + (currentCallback != null) + " queued=" + queuedCount);
            if (!paused) {
                SpeakCallback cb = currentCallback;
                currentCallback = null;
                queuedCount = 0;
                completedCount = 0;
                if (cb != null && interrupted) {
                    cb.onError("系统 TTS 已被停止");
                }
            }
        }
    }
}
