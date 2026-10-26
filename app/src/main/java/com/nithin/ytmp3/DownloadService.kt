package com.nithin.ytmp3

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Foreground service that downloads one link at a time (a link can be a whole
 * playlist/channel), converts to MP3 and saves into Download/YT-MP3.
 * Runs with the screen off and keeps going if you leave the app.
 */
class DownloadService : Service() {

    data class Task(val url: String, val collection: Boolean, val quality: String)

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_COLLECTION = "collection"
        private const val EXTRA_QUALITY = "quality"
        const val ACTION_CANCEL = "com.nithin.ytmp3.CANCEL"
        private const val NOTIF_PROGRESS = 1
        private const val NOTIF_DONE = 2
        const val OUT_DIR = "YT-MP3"

        private val AUDIO_EXT = setOf("mp3", "m4a", "opus", "ogg", "webm", "aac", "flac", "wav")

        fun start(ctx: Context, url: String, collection: Boolean, quality: String) {
            val i = Intent(ctx, DownloadService::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_COLLECTION, collection)
                .putExtra(EXTRA_QUALITY, quality)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun cancel(ctx: Context) {
            ctx.startService(Intent(ctx, DownloadService::class.java).setAction(ACTION_CANCEL))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = ConcurrentLinkedQueue<Task>()
    private var worker: Job? = null           // touched only on the main thread
    @Volatile private var processId: String? = null
    @Volatile private var cancelled = false
    @Volatile private var skippedAll = false   // yt-dlp said "already in archive"
    private var lastNotify = 0L
    private var sessionSaved = 0
    private var sessionFailed = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelAll()
            if (worker == null) stopSelf()
            return START_NOT_STICKY
        }

        val url = intent?.getStringExtra(EXTRA_URL)
        goForeground(notification("Starting…", "", 0, indeterminate = true))
        if (url == null) {
            if (worker == null) stopSelf()
            return START_NOT_STICKY
        }

        queue.add(
            Task(
                url,
                intent.getBooleanExtra(EXTRA_COLLECTION, false),
                intent.getStringExtra(EXTRA_QUALITY) ?: "320"
            )
        )
        DownloadState.update { it.copy(running = true, queued = queue.size) }
        if (worker == null) {
            sessionSaved = 0
            sessionFailed = 0
            DownloadState.update { it.copy(saved = 0, failed = 0, lastSaved = "") }
            worker = scope.launch { runQueue() }
        }
        return START_NOT_STICKY
    }

    private fun goForeground(n: Notification) {
        ServiceCompat.startForeground(
            this, NOTIF_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private suspend fun runQueue() {
        DownloadState.update { it.copy(status = "Preparing downloader…") }
        if (!App.ready.await()) {
            DownloadState.update { it.copy(status = "Engine failed to start", running = false) }
            DownloadState.log("Init error: ${App.initError}")
            queue.clear()
        }

        while (true) {
            val task = queue.poll() ?: break
            DownloadState.update { it.copy(queued = queue.size) }
            cancelled = false
            process(task)
        }

        withContext(Dispatchers.Main) {
            if (queue.isEmpty()) {
                worker = null
                finishSession()
            } else {
                worker = scope.launch { runQueue() }
            }
        }
    }

    private fun process(task: Task) {
        if (Spotify.isSpotify(task.url)) { processSpotify(task); return }

        val tmp = File(cacheDir, "dl/${System.currentTimeMillis()}").apply { mkdirs() }
        DownloadState.update {
            it.copy(status = "Fetching info…", title = task.url, progress = 0, itemIndex = 0, itemCount = 0)
        }
        notifyProgress("Fetching info…", task.url, 0, true, force = true)

        skippedAll = false
        runYtDlp(task.url, task.collection, task.quality, tmp, trackMode = false)

        // Save whatever finished (also after errors in part of a playlist)
        val before = sessionSaved
        sweep(tmp, isFinal = true)
        if (sessionSaved == before && !cancelled && !skippedAll) {
            sessionFailed++
            DownloadState.update {
                it.copy(failed = sessionFailed, status = "Failed – see log. Try “Update engine”.")
            }
        }
        tmp.deleteRecursively()
    }

    /** Spotify: read the song list, then download each song's best YouTube match. */
    private fun processSpotify(task: Task) {
        DownloadState.update {
            it.copy(status = "Reading Spotify list…", title = task.url, progress = 0, itemIndex = 0, itemCount = 0)
        }
        notifyProgress("Reading Spotify list…", task.url, 0, true, force = true)

        val list = try {
            Spotify.fetch(task.url)
        } catch (e: Exception) {
            DownloadState.log("ERROR (Spotify): ${e.message}")
            sessionFailed++
            DownloadState.update { it.copy(failed = sessionFailed, status = "Couldn't read that Spotify link") }
            return
        }
        DownloadState.log("Spotify: ${list.name} · ${list.tracks.size} song(s)")

        list.tracks.forEachIndexed { i, t ->
            if (cancelled) return
            DownloadState.update {
                it.copy(
                    itemIndex = i + 1, itemCount = list.tracks.size, progress = 0,
                    status = "Searching YouTube…", title = "${t.title} – ${t.artist}"
                )
            }
            val tmp = File(cacheDir, "dl/sp-${System.currentTimeMillis()}").apply { mkdirs() }
            skippedAll = false
            // "Official audio" helps pick the studio version over live/cover videos
            val query = "ytsearch1:${t.title} ${t.artist} official audio"
            runYtDlp(
                query, false, task.quality, tmp, trackMode = true,
                extra = listOf(
                    "--parse-metadata" to "${literal(t.title)}:(?P<meta_title>.+)",
                    "--parse-metadata" to "${literal(t.artist)}:(?P<meta_artist>.+)"
                )
            )
            val before = sessionSaved
            sweep(tmp, isFinal = true) { info -> Naming.fileBaseFor(info, t.title) }
            if (skippedAll) {
                DownloadState.log("Already downloaded: ${t.title}")
            } else if (sessionSaved == before && !cancelled) {
                sessionFailed++
                DownloadState.update { it.copy(failed = sessionFailed) }
                DownloadState.log("Not found on YouTube: ${t.title} – ${t.artist}")
            }
            tmp.deleteRecursively()
        }
    }

    /** Text used as a yt-dlp template literal: no field markers, no ':' separators. */
    private fun literal(s: String) =
        s.replace("%", "%%").replace(":", " ").replace(Regex("""\s+"""), " ").trim().ifBlank { "Unknown" }

    private fun runYtDlp(
        target: String,
        collection: Boolean,
        quality: String,
        tmp: File,
        trackMode: Boolean,
        extra: List<Pair<String, String>> = emptyList()
    ) {
        // Decrypted sign-in cookies exist only for the length of this download
        val cookieTemp = YouTubeBrowserActivity.exportForDownload(this)
        val req = YoutubeDLRequest(target).apply {
            addOption("-f", "bestaudio/best")
            addOption("-x")
            if (quality == "best") {
                addOption("--audio-format", "best")
            } else {
                addOption("--audio-format", "mp3")
                addOption("--audio-quality", "${quality}K")
            }
            for ((k, v) in extra) addOption(k, v)
            addOption("--embed-metadata")
            addOption("--embed-thumbnail")
            addOption("--convert-thumbnails", "jpg")
            addOption("--write-info-json")              // used to build SongName_Language
            addOption("--no-write-playlist-metafiles")
            addOption("--ignore-errors")                // skip private/removed videos in a playlist
            addOption("--no-mtime")
            // Skip songs downloaded before (re-sharing a playlist only fetches new songs)
            if (getSharedPreferences("settings", MODE_PRIVATE).getBoolean("skipDone", true)) {
                addOption("--download-archive", File(filesDir, "downloaded.txt").absolutePath)
            }
            if (!trackMode) addOption(if (collection) "--yes-playlist" else "--no-playlist")
            // Signed in via "My YouTube": lets yt-dlp read private playlists (Sounds, Liked…)
            cookieTemp?.let { addOption("--cookies", it.absolutePath) }
            // Temporary names are the video id; the final name is set when saving.
            addOption("-o", tmp.absolutePath + "/%(id)s.%(ext)s")
        }

        val itemRe = Regex("""Downloading (?:item|video) (\d+) of (\d+)""")
        val destRe = Regex("""\[download] Destination: (.+)""")
        val id = "job-${System.nanoTime()}"
        processId = id

        try {
            YoutubeDL.getInstance().execute(req, id, false) { progress, _, line ->
                DownloadState.log(line)
                if ("has already been recorded in the archive" in line) {
                    skippedAll = true
                    DownloadState.update { it.copy(status = "Already downloaded – skipped") }
                }
                if (!trackMode) itemRe.find(line)?.let { m ->
                    val i = m.groupValues[1].toInt()
                    val n = m.groupValues[2].toInt()
                    sweep(tmp, isFinal = false)      // previous item is finished: save it now
                    DownloadState.update { it.copy(itemIndex = i, itemCount = n, progress = 0) }
                }
                when {
                    destRe.containsMatchIn(line) ->
                        DownloadState.update { it.copy(status = "Downloading…") }
                    line.startsWith("[ExtractAudio]") ->
                        DownloadState.update { it.copy(status = "Converting to MP3…") }
                    line.startsWith("[EmbedThumbnail]") ->
                        DownloadState.update { it.copy(status = "Adding cover art…") }
                    line.startsWith("[youtube] Extracting URL") ->
                        DownloadState.update { it.copy(status = "Fetching info…") }
                }
                if (progress >= 0f) {
                    DownloadState.update { it.copy(progress = progress.toInt()) }
                }
                val s = DownloadState.ui.value
                val head = if (s.itemCount > 1) "${s.itemIndex}/${s.itemCount} · ${s.status}" else s.status
                notifyProgress(head, s.lastSaved, progress.toInt().coerceIn(0, 100), false)
            }
        } catch (e: YoutubeDL.CanceledException) {
            DownloadState.log("Cancelled")
        } catch (e: Exception) {
            if (!cancelled) {
                DownloadState.log("ERROR: ${e.message?.lines()?.lastOrNull { it.isNotBlank() } ?: e}")
            }
        } finally {
            processId = null
            cookieTemp?.delete()
        }
    }

    /**
     * Move finished audio files out of the temp folder into Download/YT-MP3,
     * renamed to SongName_Language.ext.
     */
    @Synchronized
    private fun sweep(tmp: File, isFinal: Boolean, nameFor: ((JSONObject) -> String)? = null) {
        val files = tmp.walkTopDown()
            .filter { it.isFile }
            .filter { it.extension.lowercase() in AUDIO_EXT }
            .filter { !it.name.contains(".temp.") && !it.name.contains(".part") }
            .toList()

        for (audio in files) {
            val base = audio.name.substringBefore('.')
            val infoFile = File(audio.parentFile, "$base.info.json")
            if (!infoFile.exists() && !isFinal) continue
            // A ".webm" next to an unfinished conversion is not done yet
            if (!isFinal && isIntermediate(audio)) continue

            val info = try {
                JSONObject(infoFile.readText())
            } catch (e: Exception) {
                JSONObject().put("id", base)
            }
            val name = (nameFor?.invoke(info) ?: Naming.fileBase(info)) + "." + audio.extension.lowercase()
            val sub = ""   // everything (singles and playlists) goes in Download/YT-MP3

            if (saveToDownloads(audio, name, sub)) {
                sessionSaved++
                DownloadState.update { it.copy(saved = sessionSaved, lastSaved = name) }
                DownloadState.log("Saved: Download/$OUT_DIR/${if (sub.isEmpty()) "" else "$sub/"}$name")
            }
            audio.delete()
            infoFile.delete()
        }
    }

    /** True for a downloaded source file that is still waiting to be converted. */
    private fun isIntermediate(audio: File): Boolean {
        val ext = audio.extension.lowercase()
        if (ext == "mp3") return false
        // Same id already converted to mp3 or still converting -> this is the source
        val base = audio.name.substringBefore('.')
        return audio.parentFile?.listFiles()?.any {
            it != audio && it.name.startsWith("$base.") && it.extension.lowercase() in AUDIO_EXT
        } ?: false
    }

    private fun saveToDownloads(src: File, displayName: String, sub: String): Boolean {
        val relPath = buildString {
            append(Environment.DIRECTORY_DOWNLOADS).append('/').append(OUT_DIR)
            if (sub.isNotEmpty()) append('/').append(sub)
        }
        val mime = when (src.extension.lowercase()) {
            "mp3" -> "audio/mpeg"
            "m4a", "aac" -> "audio/mp4"
            "opus", "ogg" -> "audio/ogg"
            "webm" -> "audio/webm"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            else -> "audio/*"
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                src.inputStream().use { it.copyTo(out) }
            } ?: throw IllegalStateException("no output stream")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            DownloadState.log("Save failed for $displayName: ${e.message}")
            false
        }
    }

    private fun cancelAll() {
        queue.clear()
        cancelled = true
        processId?.let { YoutubeDL.getInstance().destroyProcessById(it) }
        DownloadState.update { it.copy(queued = 0, status = "Cancelling…") }
    }

    private fun finishSession() {
        val saved = sessionSaved
        val failed = sessionFailed
        val status = when {
            cancelled -> "Cancelled · saved $saved file(s)"
            saved > 0 -> "Done · saved $saved file(s)"
            else -> DownloadState.ui.value.status
        }
        DownloadState.update { it.copy(running = false, status = status, progress = if (saved > 0) 100 else 0) }

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        val nm = getSystemService(NotificationManager::class.java)
        val text = "Saved $saved file(s) to Download/$OUT_DIR" + if (failed > 0) " · $failed failed" else ""
        nm.notify(
            NOTIF_DONE,
            NotificationCompat.Builder(this, App.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(status)
                .setContentText(text)
                .setContentIntent(openAppIntent())
                .setAutoCancel(true)
                .build()
        )
        stopSelf()
    }

    // ---------------------------------------------------------------- notifications

    private fun notifyProgress(title: String, text: String, pct: Int, indeterminate: Boolean, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotify < 700) return
        lastNotify = now
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_PROGRESS, notification(title, text, pct, indeterminate))
    }

    private fun notification(title: String, text: String, pct: Int, indeterminate: Boolean): Notification {
        val cancelPi = PendingIntent.getService(
            this, 1,
            Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, App.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, pct, indeterminate)
            .setContentIntent(openAppIntent())
            .addAction(0, "Cancel", cancelPi)
            .build()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    override fun onDestroy() {
        processId?.let { YoutubeDL.getInstance().destroyProcessById(it) }
        scope.cancel()
        super.onDestroy()
    }
}
