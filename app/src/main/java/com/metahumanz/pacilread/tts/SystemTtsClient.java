package com.metahumanz.pacilread.tts;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.TextUtils;

import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class SystemTtsClient {
    private static final long INIT_TIMEOUT_SECONDS = 10L;
    private static final long SPEAK_TIMEOUT_MINUTES = 2L;

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CountDownLatch initLatch = new CountDownLatch(1);

    private volatile TextToSpeech textToSpeech;
    private volatile int initStatus = TextToSpeech.ERROR;
    private volatile CountDownLatch activeSpeakLatch;
    private volatile String activeUtteranceId;
    private volatile boolean activeUtteranceFailed = false;
    private volatile String activeUtteranceError;
    private volatile boolean cancelled = false;

    public SystemTtsClient(Context context) {
        appContext = context.getApplicationContext();
        if (Looper.myLooper() == Looper.getMainLooper()) {
            initialize();
        } else {
            mainHandler.post(this::initialize);
        }
    }

    public void speak(String text, float rate) throws Exception {
        if (TextUtils.isEmpty(text)) {
            return;
        }
        cancelled = false;
        if (!initLatch.await(INIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw new IllegalStateException("系统 TTS 初始化超时");
        }
        TextToSpeech tts = textToSpeech;
        if (initStatus != TextToSpeech.SUCCESS || tts == null) {
            throw new IllegalStateException("系统 TTS 初始化失败");
        }

        CountDownLatch speakLatch = new CountDownLatch(1);
        String utteranceId = "system_tts_" + SystemClock.uptimeMillis();
        activeSpeakLatch = speakLatch;
        activeUtteranceId = utteranceId;
        activeUtteranceFailed = false;
        activeUtteranceError = null;

        tts.stop();
        tts.setSpeechRate(Math.max(0.5f, Math.min(rate, 2f)));
        int result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId);
        if (result != TextToSpeech.SUCCESS) {
            clearActiveUtterance(utteranceId);
            throw new IllegalStateException("系统 TTS 开始朗读失败");
        }

        try {
            if (!speakLatch.await(SPEAK_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                cancel();
                throw new IllegalStateException("系统 TTS 朗读超时");
            }
            if (!cancelled && activeUtteranceFailed) {
                throw new IllegalStateException(
                        activeUtteranceError == null ? "系统 TTS 朗读失败" : activeUtteranceError
                );
            }
        } finally {
            clearActiveUtterance(utteranceId);
        }
    }

    public void cancel() {
        cancelled = true;
        TextToSpeech tts = textToSpeech;
        if (tts != null) {
            try {
                tts.stop();
            } catch (Exception ignore) {
            }
        }
        CountDownLatch speakLatch = activeSpeakLatch;
        if (speakLatch != null) {
            speakLatch.countDown();
        }
    }

    public void shutdown() {
        cancel();
        TextToSpeech tts = textToSpeech;
        if (tts != null) {
            try {
                tts.shutdown();
            } catch (Exception ignore) {
            }
            textToSpeech = null;
        }
    }

    private void initialize() {
        textToSpeech = new TextToSpeech(appContext, status -> {
            initStatus = status;
            TextToSpeech tts = textToSpeech;
            if (status == TextToSpeech.SUCCESS && tts != null) {
                try {
                    tts.setLanguage(Locale.getDefault());
                } catch (Exception ignore) {
                }
                try {
                    tts.setPitch(1f);
                } catch (Exception ignore) {
                }
                try {
                    tts.setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build());
                } catch (Exception ignore) {
                }
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override
                    public void onStart(String utteranceId) {
                    }

                    @Override
                    public void onDone(String utteranceId) {
                        finishUtterance(utteranceId, false, null);
                    }

                    @Override
                    public void onError(String utteranceId) {
                        finishUtterance(utteranceId, true, "系统 TTS 朗读失败");
                    }

                    @Override
                    public void onStop(String utteranceId, boolean interrupted) {
                        if (!cancelled) {
                            finishUtterance(utteranceId, true, "系统 TTS 已停止");
                        } else {
                            finishUtterance(utteranceId, false, null);
                        }
                    }
                });
            }
            initLatch.countDown();
        });
    }

    private void finishUtterance(String utteranceId, boolean failed, String errorMessage) {
        if (utteranceId == null || !utteranceId.equals(activeUtteranceId)) {
            return;
        }
        activeUtteranceFailed = failed;
        activeUtteranceError = errorMessage;
        CountDownLatch speakLatch = activeSpeakLatch;
        if (speakLatch != null) {
            speakLatch.countDown();
        }
    }

    private void clearActiveUtterance(String utteranceId) {
        if (utteranceId == null || !utteranceId.equals(activeUtteranceId)) {
            return;
        }
        activeUtteranceId = null;
        activeSpeakLatch = null;
        activeUtteranceFailed = false;
        activeUtteranceError = null;
    }
}
