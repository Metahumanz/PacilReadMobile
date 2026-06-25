package com.metahumanz.pacilread.reader.modern

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.metahumanz.pacilread.storage.JsonDatabase
import com.metahumanz.pacilread.storage.SettingsStore
import com.metahumanz.pacilread.sync.ReadingStatsSyncManager
import com.metahumanz.pacilread.sync.WebDavClient
import com.metahumanz.pacilread.sync.WebDavProgressSyncCoordinator
import com.metahumanz.pacilread.tts.MimoTtsClient
import com.metahumanz.pacilread.tts.SystemTtsClient
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

class ReaderRuntime(context: Context) {
    @JvmField val executor = Executors.newSingleThreadExecutor()
    @JvmField val paginationExecutor = Executors.newSingleThreadExecutor()
    @JvmField val ttsExecutor = Executors.newSingleThreadExecutor()
    @JvmField val synthesisExecutor = Executors.newSingleThreadExecutor()
    @JvmField val mainHandler = Handler(Looper.getMainLooper())
    @JvmField val databaseHelper: JsonDatabase = JsonDatabase.getInstance(context)
    @JvmField val settingsStore = SettingsStore(context)
    @JvmField val webDavClient = WebDavClient(settingsStore)
    @JvmField val progressSyncCoordinator = WebDavProgressSyncCoordinator(databaseHelper, settingsStore, webDavClient)
    @JvmField val readingStatsSyncManager = ReadingStatsSyncManager(context, databaseHelper, settingsStore, webDavClient)
    @JvmField val mimoTtsClient = MimoTtsClient()
    @JvmField val systemTtsClient = SystemTtsClient(context, settingsStore.ttsSystemEnginePackage)
    @Volatile private var shutdown = false

    fun isShutdown(): Boolean = shutdown

    fun safeExecute(task: Runnable?, label: String?): Boolean = safeExecute(executor, task, label)
    fun safeExecutePagination(task: Runnable?, label: String?): Boolean = safeExecute(paginationExecutor, task, label)
    fun safeExecuteTts(task: Runnable?, label: String?): Boolean = safeExecute(ttsExecutor, task, label)
    fun safeExecuteSynthesis(task: Runnable?, label: String?): Boolean = safeExecute(synthesisExecutor, task, label)

    private fun safeExecute(targetExecutor: java.util.concurrent.ExecutorService?, task: Runnable?, label: String?): Boolean {
        if (task == null || targetExecutor == null || shutdown || targetExecutor.isShutdown) return false
        return try {
            targetExecutor.execute {
                try {
                    if (!shutdown) task.run()
                } catch (error: RuntimeException) {
                    Log.w(TAG, "Reader background task failed: ${safeLabel(label)}", error)
                }
            }
            true
        } catch (error: RejectedExecutionException) {
            Log.d(TAG, "Reader background task rejected after shutdown: ${safeLabel(label)}", error)
            false
        }
    }

    private fun safeLabel(label: String?): String = if (label.isNullOrBlank()) "unnamed" else label

    fun shutdown() {
        shutdown = true
        mainHandler.removeCallbacksAndMessages(null)
        try {
            systemTtsClient.shutdown()
        } catch (error: RuntimeException) {
            Log.w(TAG, "Failed to shutdown system TTS", error)
        }
        executor.shutdownNow()
        paginationExecutor.shutdownNow()
        ttsExecutor.shutdownNow()
        synthesisExecutor.shutdownNow()
        try {
            mimoTtsClient.cancel()
        } catch (error: RuntimeException) {
            Log.w(TAG, "Failed to cancel MiMo TTS", error)
        }
    }

    companion object {
        private const val TAG = "PacilReadReader"
    }
}
