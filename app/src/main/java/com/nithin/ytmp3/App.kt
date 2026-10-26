package com.nithin.ytmp3

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {

    companion object {
        const val CHANNEL_ID = "downloads"

        /** Completes once the bundled yt-dlp + ffmpeg are unpacked (true = OK). */
        val ready = CompletableDeferred<Boolean>()

        @Volatile
        var initError: String? = null

        private const val DAY_MS = 24L * 60 * 60 * 1000

        /**
         * Updates the bundled yt-dlp. YouTube changes often, so a fresh yt-dlp
         * is the #1 fix when downloads break. Blocking: call from a background thread.
         */
        suspend fun updateEngine(ctx: Context, force: Boolean): String {
            if (!ready.await()) return "Engine failed to start: $initError"
            val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val last = prefs.getLong("lastEngineUpdate", 0L)
            if (!force && System.currentTimeMillis() - last < DAY_MS) return "Recently updated"
            return try {
                val status = YoutubeDL.getInstance()
                    .updateYoutubeDL(ctx, YoutubeDL.UpdateChannel.STABLE)
                prefs.edit().putLong("lastEngineUpdate", System.currentTimeMillis()).apply()
                when (status) {
                    YoutubeDL.UpdateStatus.DONE -> "Downloader engine updated"
                    YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "Engine is already up to date"
                    else -> "Update finished"
                }
            } catch (e: Exception) {
                "Update failed: ${e.message}"
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Unpacking python/ffmpeg takes a few seconds on first launch: keep it off the UI thread.
        scope.launch {
            // Free space left by interrupted downloads / installed updates
            if (!DownloadState.ui.value.running) java.io.File(cacheDir, "dl").deleteRecursively()
            java.io.File(cacheDir, "updates").listFiles()?.forEach { f ->
                val b = Regex("""build(\d+)""").find(f.name)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                if (b <= BuildConfig.VERSION_CODE) f.delete()
            }
            try {
                YoutubeDL.getInstance().init(this@App)
                FFmpeg.getInstance().init(this@App)
                ready.complete(true)
            } catch (e: Exception) {
                initError = e.message ?: e.toString()
                ready.complete(false)
                return@launch
            }
            updateEngine(this@App, force = false) // silent daily auto-update
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Download progress" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
