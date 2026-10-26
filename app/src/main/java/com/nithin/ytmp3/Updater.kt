package com.nithin.ytmp3

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-updater: compares this app's build number with the newest GitHub Release
 * of the repo, downloads the new APK and opens Android's installer.
 * (The GitHub repo must be public for the phone to see the releases.)
 */
object Updater {

    private const val REPO = "chennakeshavadasa/yt-mp3"

    data class Release(val build: Int, val apkUrl: String, val sizeMb: Double)

    val currentBuild: Int get() = BuildConfig.VERSION_CODE

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "YT-MP3-app")
        }

    /** Newest release, or null if it can't be read (offline, private repo…). Blocking. */
    fun latest(): Release? = try {
        val c = open("https://api.github.com/repos/$REPO/releases/latest")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        if (c.responseCode != 200) null else {
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val build = j.optString("tag_name").removePrefix("build-").toIntOrNull()
            val assets = j.optJSONArray("assets")
            var url: String? = null
            var size = 0.0
            if (assets != null) for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) {
                    url = a.optString("browser_download_url")
                    size = a.optLong("size") / 1e6
                    break
                }
            }
            if (build == null || url == null) null else Release(build, url, size)
        }
    } catch (e: Exception) {
        null
    }

    /** Downloads the APK into the app cache. Blocking; reports 0–100. */
    fun download(ctx: Context, rel: Release, onProgress: (Int) -> Unit): File {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "YT-MP3-build${rel.build}.apk")

        var c = open(rel.apkUrl)
        // GitHub redirects to its file server (a different host): follow manually
        var hops = 0
        while (c.responseCode in 300..399 && hops++ < 5) {
            val next = c.getHeaderField("Location") ?: break
            c.disconnect()
            c = open(next)
        }
        if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
        val total = c.contentLengthLong
        c.inputStream.use { input ->
            out.outputStream().use { output ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                var last = -1
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    done += n
                    if (total > 0) {
                        val pct = (done * 100 / total).toInt()
                        if (pct != last) { last = pct; onProgress(pct) }
                    }
                }
            }
        }
        return out
    }

    /** True if Android already lets this app install updates. */
    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    /** One-time switch: "Allow from this source". */
    fun openInstallPermission(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
