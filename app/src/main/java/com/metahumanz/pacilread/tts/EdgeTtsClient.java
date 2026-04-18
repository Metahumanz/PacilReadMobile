package com.metahumanz.pacilread.tts;

import android.util.Log;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public class EdgeTtsClient {
    private static final String TAG = "EdgeTtsClient";
    
    // Constants matching Win11 version
    private static final String TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4";
    private static final String CHROMIUM_FULL_VERSION = "143.0.3650.75";
    private static final String CHROMIUM_MAJOR_VERSION = CHROMIUM_FULL_VERSION.split("\\.")[0];
    private static final String SEC_MS_GEC_VERSION = "1-" + CHROMIUM_FULL_VERSION;
    private static final long WIN_EPOCH = 11644473600L;
    private static final double S_TO_NS = 1e9;

    private WebSocket webSocket;
    private final OkHttpClient client;
    private TtsCallback callback;
    private String currentText;
    private String currentVoice;
    private float currentRate;
    private String currentPitch;

    public interface TtsCallback {
        void onAudioChunk(byte[] chunk);
        void onDone();
        void onError(Exception e);
    }

    public EdgeTtsClient() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    public void synthesize(String text, String voice, float rate, String pitch, TtsCallback callback) {
        this.callback = callback;
        this.currentText = text;
        this.currentVoice = voice;
        this.currentRate = rate;
        this.currentPitch = pitch;
        try {
            String connId = UUID.randomUUID().toString().replace("-", "");
            String gecToken = generateSecMsGec();
            String muid = generateMuid();

            String wsUrl = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1" +
                    "?TrustedClientToken=" + TRUSTED_CLIENT_TOKEN +
                    "&ConnectionId=" + connId +
                    "&Sec-MS-GEC=" + gecToken +
                    "&Sec-MS-GEC-Version=" + SEC_MS_GEC_VERSION;

            Request request = new Request.Builder()
                    .url(wsUrl)
                    .addHeader("Pragma", "no-cache")
                    .addHeader("Cache-Control", "no-cache")
                    .addHeader("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
                    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + CHROMIUM_MAJOR_VERSION + ".0.0.0 Safari/537.36 Edg/" + CHROMIUM_MAJOR_VERSION + ".0.0.0")
                    .addHeader("Accept-Encoding", "gzip, deflate, br, zstd")
                    .addHeader("Accept-Language", "en-US,en;q=0.9")
                    .addHeader("Cookie", "muid=" + muid + ";")
                    .build();

            this.webSocket = client.newWebSocket(request, new EdgeWebSocketListener());

            // Send config and SSML after connection is open (handled in listener)
            // We'll wait for onOpen to send messages
            
        } catch (Exception e) {
            if (callback != null) callback.onError(e);
        }
    }

    public void cancel() {
        if (webSocket != null) {
            webSocket.cancel();
            webSocket = null;
        }
    }

    private String generateSecMsGec() {
        long ticks = System.currentTimeMillis() / 1000;
        ticks += WIN_EPOCH;
        ticks -= ticks % 300;
        double nsTicks = ticks * (S_TO_NS / 100);
        String strToHash = ((long)nsTicks) + TRUSTED_CLIENT_TOKEN;
        
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(strToHash.getBytes());
            StringBuilder hexString = new StringBuilder();
            for (byte b : digest) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString().toUpperCase();
        } catch (NoSuchAlgorithmException e) {
            Log.e(TAG, "SHA-256 not available", e);
            return "";
        }
    }

    private String generateMuid() {
        return UUID.randomUUID().toString().replace("-", "").toUpperCase();
    }

    private class EdgeWebSocketListener extends WebSocketListener {
        @Override
        public void onOpen(@NotNull WebSocket webSocket, @NotNull Response response) {
            Log.d(TAG, "Edge TTS WebSocket connected");
            
            // 1. Send speech config
            String configMsg = "X-Timestamp:" + new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).format(new java.util.Date()) +
                    "\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n" +
                    "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"},\"outputFormat\":\"audio-24khz-16bit-24kbps-mono-opus\"}}}}";
            webSocket.send(configMsg);

            // 2. Send SSML
            String reqId = UUID.randomUUID().toString().replace("-", "");
            String rateStr = currentRate >= 1.0f ? "+" + ((int)((currentRate - 1.0f) * 100)) + "%" : ((int)((currentRate - 1.0f) * 100)) + "%";
            if (rateStr.equals("+0%")) rateStr = "+0.00%";
            
            String ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='zh-CN'>" +
                    "<voice name='" + currentVoice + "'>" +
                    "<prosody rate='" + rateStr + "' pitch='" + (currentPitch != null ? currentPitch : "+0Hz") + "'>" +
                    currentText + "</prosody></voice></speak>";
            
            String ssmlMsg = "X-RequestId:" + reqId +
                    "\r\nContent-Type:application/ssml+xml\r\n" +
                    "X-Timestamp:" + new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).format(new java.util.Date()) +
                    "\r\nPath:ssml\r\n\r\n" + ssml;
            
            webSocket.send(ssmlMsg);
        }

        @Override
        public void onMessage(@NotNull WebSocket webSocket, @NotNull String text) {
            if (text.contains("Path:turn.end")) {
                if (callback != null) callback.onDone();
                webSocket.close(1000, "Done");
            }
        }

        @Override
        public void onMessage(@NotNull WebSocket webSocket, @NotNull ByteString bytes) {
            ByteBuffer buffer = bytes.asByteBuffer();
            if (buffer.remaining() >= 2) {
                int headerLen = buffer.getShort() & 0xFFFF;
                if (buffer.remaining() >= headerLen) {
                    buffer.position(buffer.position() + headerLen);
                    byte[] audioData = new byte[buffer.remaining()];
                    buffer.get(audioData);
                    if (callback != null) callback.onAudioChunk(audioData);
                }
            }
        }

        @Override
        public void onFailure(@NotNull WebSocket webSocket, @NotNull Throwable t, Response response) {
            Log.e(TAG, "Edge TTS WebSocket failure", t);
            if (callback != null) callback.onError(new Exception(t));
        }
    }
}
