package com.metahumanz.pacilread.tts;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class OpusDecoder {
    private static final String TAG = "OpusDecoder";
    private MediaCodec codec;
    private final Handler handler;
    private volatile boolean isRunning = false;
    private final BlockingQueue<byte[]> inputQueue = new ArrayBlockingQueue<>(50);

    public interface Callback {
        void onPcmData(byte[] pcm, int sampleRate, int channels);
        void onError(Exception e);
    }

    public OpusDecoder() {
        handler = new Handler(Looper.getMainLooper());
    }

    public void start(Callback callback) {
        try {
            // MIME type for Opus
            String mime = MediaFormat.MIMETYPE_AUDIO_OPUS;
            MediaFormat format = MediaFormat.createAudioFormat(mime, 24000, 1);
            
            // Create codec
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();
            isRunning = true;

            new Thread(() -> {
                try {
                    decodeLoop(callback);
                } catch (Exception e) {
                    Log.e(TAG, "Decoder thread error", e);
                    if (callback != null) callback.onError(e);
                }
            }).start();

        } catch (IOException e) {
            Log.e(TAG, "Failed to create Opus decoder", e);
            if (callback != null) callback.onError(e);
        }
    }

    public void feed(byte[] opusFrame) {
        if (!isRunning) return;
        try {
            inputQueue.put(opusFrame);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void stop() {
        isRunning = false;
        if (codec != null) {
            try {
                codec.stop();
                codec.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing codec", e);
            }
            codec = null;
        }
    }

    private void decodeLoop(Callback callback) {
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

        while (isRunning || !inputQueue.isEmpty()) {
            // 1. Feed input
            int inIndex = codec.dequeueInputBuffer(10000);
            if (inIndex >= 0) {
                byte[] data = null;
                try {
                    data = inputQueue.take();
                } catch (InterruptedException e) {
                    break;
                }

                ByteBuffer buffer = codec.getInputBuffer(inIndex);
                if (buffer != null) {
                    buffer.clear();
                    buffer.put(data);
                    codec.queueInputBuffer(inIndex, 0, data.length, 0, 0);
                }
            }

            // 2. Get output
            int outIndex = codec.dequeueOutputBuffer(bufferInfo, 10000);
            if (outIndex >= 0) {
                ByteBuffer outBuffer = codec.getOutputBuffer(outIndex);
                if (outBuffer != null && bufferInfo.size > 0) {
                    byte[] pcm = new byte[bufferInfo.size];
                    outBuffer.get(pcm);
                    if (callback != null) {
                        callback.onPcmData(pcm, 24000, 1);
                    }
                }
                codec.releaseOutputBuffer(outIndex, false);
            } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat newFormat = codec.getOutputFormat();
                Log.d(TAG, "Output format changed: " + newFormat);
            }
        }
    }
}
