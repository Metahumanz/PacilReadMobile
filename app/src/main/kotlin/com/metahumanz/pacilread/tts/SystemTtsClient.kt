package com.metahumanz.pacilread.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.TextUtils
import android.util.Log
import com.metahumanz.pacilread.R
import java.util.concurrent.atomic.AtomicLong

class SystemTtsClient @JvmOverloads constructor(context: Context, enginePackageName: String? = "") {
    interface SpeakCallback {
        fun onStart()
        fun onDone()
        fun onError(message: String)
    }

    class EngineInfo(packageName: String?, label: String?) {
        @JvmField val packageName: String = packageName ?: ""
        @JvmField val label: String = label ?: ""
        override fun toString(): String = label
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var initSuccess = false
    @Volatile private var currentCallback: SpeakCallback? = null
    @Volatile private var queuedCount = 0
    @Volatile private var completedCount = 0
    @Volatile private var paused = false
    @Volatile private var firstSpeak = true
    private val utteranceSequence = AtomicLong()
    @Volatile private var audioFocusLossListener: Runnable? = null
    private var audioManager: AudioManager? = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false

    init {
        ensureTtsOnMain(enginePackageName?.trim()?.takeIf { it.isNotEmpty() })
    }

    private fun ensureTtsOnMain(enginePackageName: String?) {
        if (Looper.myLooper() == Looper.getMainLooper()) initTts(enginePackageName)
        else mainHandler.post { initTts(enginePackageName) }
    }

    private fun initTts(enginePackageName: String?) = synchronized(lock) {
        tts?.let { try { it.shutdown() } catch (_: Exception) {} }
        tts = null
        initSuccess = false
        tts = if (enginePackageName == null) {
            TextToSpeech(appContext, ::onInit)
        } else {
            TextToSpeech(appContext, ::onInit, enginePackageName)
        }
    }

    private fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.let { engine ->
                Log.i(TAG, "TTS init SUCCESS")
                engine.setOnUtteranceProgressListener(Listener())
                initSuccess = true
            }
        } else Log.e(TAG, "TTS init FAILED, status=$status")
    }

    fun speak(text: String?, rate: Float, callback: SpeakCallback?) {
        if (TextUtils.isEmpty(text)) {
            callback?.onDone()
            return
        }
        val engine = tts
        if (!initSuccess || engine == null) {
            callback?.onError("系统 TTS 未就绪")
            return
        }
        currentCallback = callback
        paused = false
        engine.setSpeechRate(Math.max(0.5f, Math.min(rate, 3f)))
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_${utteranceSequence.incrementAndGet()}")
        if (result != TextToSpeech.SUCCESS) {
            currentCallback = null
            callback?.onError("系统 TTS 开始朗读失败")
        }
    }

    fun speakAll(texts: List<String?>?, rate: Float, callback: SpeakCallback?) {
        if (texts.isNullOrEmpty()) {
            callback?.onDone()
            return
        }
        val engine = tts
        if (!initSuccess || engine == null) {
            callback?.onError("系统 TTS 未就绪")
            return
        }
        paused = false
        currentCallback = callback
        engine.setSpeechRate(Math.max(0.5f, Math.min(rate, 3f)))
        completedCount = 0
        var count = 0
        val first = firstSpeak
        firstSpeak = false
        for (i in texts.indices) {
            val text = texts[i]
            if (TextUtils.isEmpty(text)) continue
            val mode = if (first && i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val result = engine.speak(text, mode, null, "tts_${utteranceSequence.incrementAndGet()}")
            if (result != TextToSpeech.SUCCESS) {
                currentCallback = null
                queuedCount = 0
                completedCount = 0
                callback?.onError("系统 TTS 朗读失败")
                return
            }
            count++
        }
        queuedCount = count
    }

    fun pause() {
        paused = true
        tts?.let { try { it.stop() } catch (_: Exception) {} }
        currentCallback = null
    }

    fun stop() {
        paused = false
        firstSpeak = true
        tts?.let { try { it.stop() } catch (_: Exception) {} }
        currentCallback = null
        abandonAudioFocus()
    }

    fun shutdown() {
        stop()
        firstSpeak = true
        synchronized(lock) {
            tts?.let { try { it.shutdown() } catch (_: Exception) {} }
            tts = null
            initSuccess = false
        }
    }

    fun isInitSuccess(): Boolean = initSuccess && tts != null

    fun setEngine(enginePackageName: String?) {
        ensureTtsOnMain(enginePackageName?.trim()?.takeIf { it.isNotEmpty() })
    }

    fun requestAudioFocus(): Boolean {
        val manager = audioManager ?: return false
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attrs).setOnAudioFocusChangeListener(::onAudioFocusChange).build()
                hasAudioFocus = manager.requestAudioFocus(requireNotNull(focusRequest)) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                val result = manager.requestAudioFocus(::onAudioFocusChangeLegacy, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
                hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
            if (hasAudioFocus && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) playSilentSound()
        } catch (error: Exception) {
            Log.e(TAG, "Failed to request audio focus", error)
            hasAudioFocus = false
        }
        return hasAudioFocus
    }

    fun setAudioFocusLossListener(listener: Runnable?) {
        audioFocusLossListener = listener
    }

    fun abandonAudioFocus() {
        val manager = audioManager
        if (!hasAudioFocus || manager == null) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
                manager.abandonAudioFocusRequest(requireNotNull(focusRequest))
            } else {
                @Suppress("DEPRECATION")
                manager.abandonAudioFocus(null)
            }
        } catch (_: Exception) {
        }
        hasAudioFocus = false
    }

    private fun onAudioFocusChange(focusChange: Int) = handleAudioFocusChange(focusChange)
    private fun onAudioFocusChangeLegacy(focusChange: Int) = handleAudioFocusChange(focusChange)
    private fun handleAudioFocusChange(focusChange: Int) {
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            pause()
            audioFocusLossListener?.let(mainHandler::post)
        }
    }

    private fun playSilentSound() {
        try {
            MediaPlayer.create(appContext, R.raw.silent_sound)?.let { player ->
                player.start()
                player.setOnCompletionListener { it.release() }
            }
        } catch (_: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private inner class Listener : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            Log.d(TAG, "onStart id=$utteranceId cb=${currentCallback != null}")
            currentCallback?.onStart()
        }

        override fun onDone(utteranceId: String?) {
            val done = completedCount + 1
            completedCount = done
            val remaining = queuedCount - done
            val callback = currentCallback
            Log.d(TAG, "onDone id=$utteranceId done=$done queued=$queuedCount remaining=$remaining cb=${callback != null}")
            if (remaining <= 0) {
                currentCallback = null
                queuedCount = 0
                completedCount = 0
            }
            callback?.onDone()
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onError(utteranceId: String?) {
            Log.e(TAG, "onError id=$utteranceId cb=${currentCallback != null}")
            val callback = currentCallback
            currentCallback = null
            queuedCount = 0
            completedCount = 0
            callback?.onError("系统 TTS 朗读失败")
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            Log.e(TAG, "onError id=$utteranceId code=$errorCode cb=${currentCallback != null}")
            val callback = currentCallback
            currentCallback = null
            queuedCount = 0
            completedCount = 0
            callback?.onError("系统 TTS 朗读失败 (code=$errorCode)")
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            Log.d(TAG, "onStop id=$utteranceId interrupted=$interrupted paused=$paused cb=${currentCallback != null} queued=$queuedCount")
            if (!paused) {
                val callback = currentCallback
                currentCallback = null
                queuedCount = 0
                completedCount = 0
                if (callback != null && interrupted) callback.onError("系统 TTS 已被停止")
            }
        }
    }

    companion object {
        private const val TAG = "SystemTts"

        @JvmStatic
        fun queryAvailableEngines(context: Context): List<EngineInfo> {
            val list = ArrayList<EngineInfo>()
            var temp: TextToSpeech? = null
            try {
                temp = TextToSpeech(context.applicationContext, null)
                temp.engines?.forEach { list.add(EngineInfo(it.name, it.label)) }
            } catch (error: Exception) {
                Log.e(TAG, "Failed to query engines", error)
            } finally {
                temp?.let { try { it.shutdown() } catch (_: Exception) {} }
            }
            return list
        }
    }
}
