package com.metahumanz.pacilread.tts;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class MimoTtsClient {
    private static final String ENDPOINT = "https://api.xiaomimimo.com/v1/chat/completions";
    private static final String MODEL = "mimo-v2.5-tts";
    private static final String DEFAULT_VOICE = "冰糖";
    public static final int SAMPLE_RATE = 24000;

    public static int getSampleRate() {
        return SAMPLE_RATE;
    }

    private volatile boolean cancelled = false;
    private volatile HttpURLConnection activeConnection;
    private volatile AudioTrack activeTrack;

    public synchronized void cancel() {
        cancelled = true;
        if (activeConnection != null) {
            activeConnection.disconnect();
            activeConnection = null;
        }
        releaseTrack(activeTrack);
        activeTrack = null;
    }

    public byte[] synthesize(String text, String apiKey, String voice) throws Exception {
        cancelled = false;
        if (TextUtils.isEmpty(apiKey)) {
            throw new IllegalStateException("MiMo API Key 为空");
        }
        if (TextUtils.isEmpty(text)) {
            return new byte[0];
        }
        return synthesizePcm16(text, apiKey, voice, true);
    }

    public void playPcm(byte[] pcm, float rate) throws Exception {
        if (pcm.length == 0) {
            throw new IllegalStateException("MiMo 返回空音频，请检查 API Key 或网络");
        }

        AudioTrack track = createTrack(pcm.length);
        activeTrack = track;

        float playbackRate = Math.max(0.5f, Math.min(rate, 2f));
        try {
            track.setPlaybackRate(Math.round(SAMPLE_RATE * playbackRate));
        } catch (Exception ignore) {
        }

        track.play();
        track.write(pcm, 0, pcm.length);
        SystemClock.sleep(80L);
        long estimatedMs = Math.round((pcm.length / 2f / SAMPLE_RATE) / playbackRate * 1000f) + 500L;
        long deadline = SystemClock.uptimeMillis() + estimatedMs;
        while (!cancelled && SystemClock.uptimeMillis() < deadline) {
            if (track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) break;
            SystemClock.sleep(40L);
        }

        if (!cancelled) {
            try {
                track.stop();
            } catch (Exception ignore) {
            }
        }
        releaseTrack(track);
        if (activeTrack == track) {
            activeTrack = null;
        }
    }

    public void speak(String text, String apiKey, String voice, float rate) throws Exception {
        cancelled = false;
        playPcm(synthesize(text, apiKey, voice), rate);
    }

    private byte[] synthesizePcm16(String text, String apiKey, String voice, boolean trackConnection) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        if (trackConnection) {
            activeConnection = connection;
        }
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(60000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("api-key", apiKey.trim());
        connection.setRequestProperty("Authorization", "Bearer " + apiKey.trim());

        JSONObject payload = new JSONObject();
        payload.put("model", MODEL);
        JSONArray messages = new JSONArray();
        JSONObject message = new JSONObject();
        message.put("role", "assistant");
        message.put("content", text);
        messages.put(message);
        payload.put("messages", messages);
        JSONObject audio = new JSONObject();
        audio.put("format", "pcm16");
        audio.put("voice", normalizeVoice(voice));
        payload.put("audio", audio);
        payload.put("stream", true);

        byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream outputStream = connection.getOutputStream()) {
            outputStream.write(body);
        }

        int responseCode = connection.getResponseCode();
        InputStream inputStream = responseCode >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (inputStream == null) {
            throw new IllegalStateException("MiMo 返回空响应");
        }
        if (responseCode < 200 || responseCode >= 300) {
            String errorText = readText(inputStream);
            throw new IllegalStateException("MiMo API 错误(" + responseCode + "): " + errorText);
        }

        ByteArrayOutputStream audioStream = new ByteArrayOutputStream();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while (!cancelled && (line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || "data: [DONE]".equals(trimmed) || !trimmed.startsWith("data: ")) {
                    continue;
                }
                try {
                    JSONObject json = new JSONObject(trimmed.substring(6));
                    JSONArray choices = json.optJSONArray("choices");
                    if (choices == null || choices.length() == 0) {
                        continue;
                    }
                    JSONObject choice = choices.optJSONObject(0);
                    if (choice == null) {
                        continue;
                    }
                    JSONObject delta = choice.optJSONObject("delta");
                    if (delta == null) {
                        continue;
                    }
                    JSONObject audioObject = delta.optJSONObject("audio");
                    if (audioObject == null) {
                        continue;
                    }
                    String audioData = audioObject.optString("data", "");
                    if (!audioData.isEmpty()) {
                        audioStream.write(Base64.decode(audioData, Base64.DEFAULT));
                    }
                } catch (Exception ignore) {
                }
            }
        } finally {
            connection.disconnect();
            if (trackConnection && activeConnection == connection) {
                activeConnection = null;
            }
        }

        if (cancelled) {
            return new byte[0];
        }
        return audioStream.toByteArray();
    }

    private String normalizeVoice(String voice) {
        if ("冰糖".equals(voice) || "茉莉".equals(voice) || "苏打".equals(voice) || "白桦".equals(voice)) {
            return voice;
        }
        return DEFAULT_VOICE;
    }

    private AudioTrack createTrack(int pcmLength) {
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build();
        int minBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        return new AudioTrack(attributes, format, Math.max(minBuffer * 2, pcmLength), AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
    }

    private String readText(InputStream inputStream) throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
            return builder.toString();
        }
    }

    private void releaseTrack(AudioTrack track) {
        if (track == null) {
            return;
        }
        try {
            track.pause();
        } catch (Exception ignore) {
        }
        try {
            track.flush();
        } catch (Exception ignore) {
        }
        try {
            track.release();
        } catch (Exception ignore) {
        }
    }
}
