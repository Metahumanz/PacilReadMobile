package com.metahumanz.pacilread.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import android.text.TextUtils
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

open class MimoTtsClient {
    @Volatile private var cancelled = false
    @Volatile private var activeConnection: HttpURLConnection? = null
    @Volatile private var activeTrack: AudioTrack? = null

    @Synchronized
    fun cancel() {
        cancelled = true
        activeConnection?.disconnect()
        activeConnection = null
        releaseTrack(activeTrack)
        activeTrack = null
    }

    @Throws(Exception::class)
    fun synthesize(text: String?, apiKey: String?, voice: String?): ByteArray {
        cancelled = false
        if (TextUtils.isEmpty(apiKey)) throw IllegalStateException("MiMo API Key 为空")
        if (TextUtils.isEmpty(text)) return ByteArray(0)
        return synthesizePcm16(requireNotNull(text), requireNotNull(apiKey), voice, true)
    }

    @Throws(Exception::class)
    fun playPcm(pcm: ByteArray, rate: Float) {
        if (pcm.isEmpty()) throw IllegalStateException("MiMo 返回空音频，请检查 API Key 或网络")
        val track = createTrack(pcm.size)
        activeTrack = track
        val playbackRate = Math.max(0.5f, Math.min(rate, 2f))
        try {
            track.playbackRate = Math.round(SAMPLE_RATE * playbackRate)
        } catch (_: Exception) {
        }
        track.play()
        track.write(pcm, 0, pcm.size)
        SystemClock.sleep(80L)
        val estimatedMs = Math.round(pcm.size / 2f / SAMPLE_RATE / playbackRate * 1000f) + 500L
        val deadline = SystemClock.uptimeMillis() + estimatedMs
        while (!cancelled && SystemClock.uptimeMillis() < deadline) {
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) break
            SystemClock.sleep(40L)
        }
        if (!cancelled) try {
            track.stop()
        } catch (_: Exception) {
        }
        releaseTrack(track)
        if (activeTrack === track) activeTrack = null
    }

    @Throws(Exception::class)
    fun speak(text: String?, apiKey: String?, voice: String?, rate: Float) {
        cancelled = false
        playPcm(synthesize(text, apiKey, voice), rate)
    }

    @Throws(Exception::class)
    private fun synthesizePcm16(text: String, apiKey: String, voice: String?, trackConnection: Boolean): ByteArray {
        val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
        if (trackConnection) activeConnection = connection
        connection.connectTimeout = 10_000
        connection.readTimeout = 60_000
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("api-key", apiKey.trim())
        connection.setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
        val payload = JSONObject().apply {
            put("model", MODEL)
            put("messages", JSONArray().put(JSONObject().put("role", "assistant").put("content", text)))
            put("audio", JSONObject().put("format", "pcm16").put("voice", normalizeVoice(voice)))
            put("stream", true)
        }
        connection.outputStream.use { it.write(payload.toString().toByteArray(StandardCharsets.UTF_8)) }
        val responseCode = connection.responseCode
        val inputStream = if (responseCode >= 400) connection.errorStream else connection.inputStream
            ?: throw IllegalStateException("MiMo 返回空响应")
        if (responseCode !in 200..299) throw IllegalStateException("MiMo API 错误($responseCode): ${readText(inputStream)}")

        val audioStream = ByteArrayOutputStream()
        try {
            BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8)).use { reader ->
                while (!cancelled) {
                    val line = reader.readLine() ?: break
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed == "data: [DONE]" || !trimmed.startsWith("data: ")) continue
                    try {
                        val choices = JSONObject(trimmed.substring(6)).optJSONArray("choices") ?: continue
                        if (choices.length() == 0) continue
                        val audioData = choices.optJSONObject(0)?.optJSONObject("delta")?.optJSONObject("audio")
                            ?.optString("data", "").orEmpty()
                        if (audioData.isNotEmpty()) audioStream.write(Base64.decode(audioData, Base64.DEFAULT))
                    } catch (_: Exception) {
                    }
                }
            }
        } finally {
            connection.disconnect()
            if (trackConnection && activeConnection === connection) activeConnection = null
        }
        return if (cancelled) ByteArray(0) else audioStream.toByteArray()
    }

    private fun normalizeVoice(voice: String?): String =
        if (voice == "冰糖" || voice == "茉莉" || voice == "苏打" || voice == "白桦") voice else DEFAULT_VOICE

    private fun createTrack(pcmLength: Int): AudioTrack {
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
        val minBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack(attributes, format, Math.max(minBuffer * 2, pcmLength), AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE)
    }

    @Throws(Exception::class)
    private fun readText(inputStream: InputStream): String =
        BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8)).use { reader ->
            buildString {
                while (true) append(reader.readLine() ?: break)
            }
        }

    private fun releaseTrack(track: AudioTrack?) {
        if (track == null) return
        try { track.pause() } catch (_: Exception) {}
        try { track.flush() } catch (_: Exception) {}
        try { track.release() } catch (_: Exception) {}
    }

    companion object {
        private const val ENDPOINT = "https://api.xiaomimimo.com/v1/chat/completions"
        private const val MODEL = "mimo-v2.5-tts"
        private const val DEFAULT_VOICE = "冰糖"
        const val SAMPLE_RATE = 24000
        @JvmStatic fun getSampleRate(): Int = SAMPLE_RATE
    }
}
