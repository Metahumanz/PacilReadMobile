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
    private val callbackLock = Any()
    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var initSuccess = false
    @Volatile private var currentCallback: SpeakCallback? = null
    @Volatile private var queuedCount = 0
    @Volatile private var completedCount = 0
    @Volatile private var paused = false
    @Volatile private var activeUtteranceIds: Set<String> = emptySet()
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
        paused = false
        engine.setSpeechRate(Math.max(0.5f, Math.min(rate, 3f)))
        val utteranceId = nextUtteranceId()
        setActiveUtterances(setOf(utteranceId), callback)
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            clearActiveUtterances()
            callback?.onError("系统 TTS 开始朗读失败")
        }
    }

    fun speakAll(texts: List<String?>?, rate: Float, callback: SpeakCallback?) {
        val pendingTexts = texts?.filterNotNull()?.filter { it.isNotEmpty() }.orEmpty()
        if (pendingTexts.isEmpty()) {
            callback?.onDone()
            return
        }
        val engine = tts
        if (!initSuccess || engine == null) {
            callback?.onError("系统 TTS 未就绪")
            return
        }
        paused = false
        engine.setSpeechRate(Math.max(0.5f, Math.min(rate, 3f)))
        val utteranceIds = pendingTexts.map { nextUtteranceId() }
        setActiveUtterances(utteranceIds.toSet(), callback)
        for (i in pendingTexts.indices) {
            val text = pendingTexts[i]
            val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val result = engine.speak(text, mode, null, utteranceIds[i])
            if (result != TextToSpeech.SUCCESS) {
                clearActiveUtterances()
                try { engine.stop() } catch (_: Exception) {}
                callback?.onError("系统 TTS 朗读失败")
                return
            }
        }
    }

    fun pause() {
        paused = true
        clearActiveUtterances()
        tts?.let { try { it.stop() } catch (_: Exception) {} }
    }

    fun stop() {
        paused = false
        clearActiveUtterances()
        tts?.let { try { it.stop() } catch (_: Exception) {} }
        abandonAudioFocus()
    }

    fun shutdown() {
        stop()
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
        if (hasAudioFocus) return true
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

    private fun nextUtteranceId(): String = "tts_${utteranceSequence.incrementAndGet()}"

    private fun setActiveUtterances(utteranceIds: Set<String>, callback: SpeakCallback?) = synchronized(callbackLock) {
        activeUtteranceIds = utteranceIds
        currentCallback = callback
        queuedCount = utteranceIds.size
        completedCount = 0
    }

    private fun clearActiveUtterances() = synchronized(callbackLock) {
        activeUtteranceIds = emptySet()
        currentCallback = null
        queuedCount = 0
        completedCount = 0
    }

    @Suppress("DEPRECATION")
    private inner class Listener : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            val callback = synchronized(callbackLock) {
                if (utteranceId == null || utteranceId !in activeUtteranceIds) return
                currentCallback
            }
            Log.d(TAG, "onStart id=$utteranceId cb=${callback != null}")
            callback?.onStart()
        }

        override fun onDone(utteranceId: String?) {
            var done = 0
            var total = 0
            var remaining = 0
            val callback = synchronized(callbackLock) {
                if (utteranceId == null || utteranceId !in activeUtteranceIds) return
                activeUtteranceIds = activeUtteranceIds - utteranceId
                done = completedCount + 1
                completedCount = done
                total = queuedCount
                remaining = total - done
                val activeCallback = currentCallback
                if (remaining <= 0) {
                    currentCallback = null
                    queuedCount = 0
                    completedCount = 0
                    activeUtteranceIds = emptySet()
                }
                activeCallback
            }
            Log.d(TAG, "onDone id=$utteranceId done=$done queued=$total remaining=$remaining cb=${callback != null}")
            callback?.onDone()
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onError(utteranceId: String?) {
            val callback = synchronized(callbackLock) {
                if (utteranceId == null || utteranceId !in activeUtteranceIds) return
                currentCallback.also {
                    activeUtteranceIds = emptySet()
                    currentCallback = null
                    queuedCount = 0
                    completedCount = 0
                }
            }
            Log.e(TAG, "onError id=$utteranceId cb=${callback != null}")
            callback?.onError("系统 TTS 朗读失败")
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            val callback = synchronized(callbackLock) {
                if (utteranceId == null || utteranceId !in activeUtteranceIds) return
                currentCallback.also {
                    activeUtteranceIds = emptySet()
                    currentCallback = null
                    queuedCount = 0
                    completedCount = 0
                }
            }
            Log.e(TAG, "onError id=$utteranceId code=$errorCode cb=${callback != null}")
            callback?.onError("系统 TTS 朗读失败 (code=$errorCode)")
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            Log.d(TAG, "onStop id=$utteranceId interrupted=$interrupted paused=$paused cb=${currentCallback != null} queued=$queuedCount")
            if (!paused) {
                val callback = synchronized(callbackLock) {
                    if (utteranceId == null || utteranceId !in activeUtteranceIds) return
                    currentCallback.also {
                        activeUtteranceIds = emptySet()
                        currentCallback = null
                        queuedCount = 0
                        completedCount = 0
                    }
                }
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
