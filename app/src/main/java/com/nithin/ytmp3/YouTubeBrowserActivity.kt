package com.nithin.ytmp3

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import java.io.File

/**
 * In-app YouTube browser. You sign in once (cookies stay on this phone, in the
 * app's private storage), then browse to any playlist – including private ones
 * like "Sounds" or "Liked videos" – and tap "Download this".
 */
class YouTubeBrowserActivity : AppCompatActivity() {

    companion object {
        const val RESULT_URL = "url"
        private const val START =
            "https://accounts.google.com/ServiceLogin?service=youtube&continue=" +
                "https%3A%2F%2Fm.youtube.com%2Ffeed%2Fplaylists"

        /** Encrypted (Android Keystore) copy of the sign-in cookies. */
        private fun encFile(ctx: Context) = File(ctx.filesDir, "youtube_cookies.enc")

        /** Old plain-text file from earlier versions – migrated and removed. */
        private fun legacyFile(ctx: Context) = File(ctx.filesDir, "youtube_cookies.txt")

        fun isSignedIn(ctx: Context): Boolean {
            migrate(ctx)
            return encFile(ctx).let { it.exists() && it.length() > 0 }
        }

        private fun migrate(ctx: Context) {
            val old = legacyFile(ctx)
            if (old.exists()) {
                runCatching { SecureStore.write(encFile(ctx), old.readBytes()) }
                old.delete()
            }
        }

        /**
         * Decrypts the cookies into a private temp file for yt-dlp. The caller must
         * delete it as soon as the download ends. Returns null when not signed in.
         */
        fun exportForDownload(ctx: Context): File? {
            if (!isSignedIn(ctx)) return null
            val plain = SecureStore.read(encFile(ctx)) ?: return null
            val f = File(ctx.noBackupFilesDir, "c-${System.nanoTime()}.txt")
            f.writeBytes(plain)
            plain.fill(0)
            return f
        }

        fun signOut(ctx: Context) {
            encFile(ctx).delete()
            legacyFile(ctx).delete()
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }

        /**
         * Store the WebView's YouTube cookies (encrypted) in the cookies.txt format yt-dlp reads.
         * Returns true if the account looks signed in.
         */
        fun saveCookies(ctx: Context): Boolean {
            val cm = CookieManager.getInstance()
            cm.flush()
            val raw = cm.getCookie("https://www.youtube.com") ?: return false
            val expiry = System.currentTimeMillis() / 1000 + 365L * 24 * 3600
            val sb = StringBuilder("# Netscape HTTP Cookie File\n")
            var signedIn = false
            raw.split(";").map { it.trim() }.filter { "=" in it }.forEach { pair ->
                val name = pair.substringBefore("=")
                val value = pair.substringAfter("=")
                if (name in setOf("SID", "SAPISID", "__Secure-3PSID", "LOGIN_INFO")) signedIn = true
                sb.append(".youtube.com\tTRUE\t/\tTRUE\t$expiry\t$name\t$value\n")
            }
            if (signedIn) SecureStore.write(encFile(ctx), sb.toString().toByteArray())
            legacyFile(ctx).delete()
            return signedIn
        }

        /** Only Google/YouTube pages may load in the sign-in browser. */
        private val allowedHosts = listOf(
            "youtube.com", "youtu.be", "google.com", "google.co.in", "gstatic.com",
            "googleusercontent.com", "googleapis.com", "ggpht.com", "ytimg.com", "googlevideo.com"
        )

        fun isAllowed(url: String?): Boolean {
            val host = runCatching { Uri.parse(url).host?.lowercase() }.getOrNull() ?: return false
            if (!url!!.startsWith("https://")) return false
            return allowedHosts.any { host == it || host.endsWith(".$it") }
        }
    }

    private lateinit var web: WebView
    private lateinit var useBtn: MaterialButton

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browser)

        val toolbar = findViewById<MaterialToolbar>(R.id.browserToolbar)
        toolbar.setNavigationOnClickListener { finishSaving(null) }

        web = findViewById(R.id.web)
        useBtn = findViewById(R.id.useBtn)

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Hardening: no access to phone files, no mixed http content
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setGeolocationEnabled(false)
            javaScriptCanOpenWindowsAutomatically = false
            // Look like normal Chrome so Google allows the sign-in page
            userAgentString = userAgentString.replace("; wv", "").replace(Regex("""Version/\S+\s"""), "")
        }
        web.webViewClient = object : WebViewClient() {
            // Block anything that isn't a Google/YouTube https page
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val ok = isAllowed(req.url.toString())
                if (!ok) Toast.makeText(this@YouTubeBrowserActivity, "Blocked: only YouTube/Google pages open here", Toast.LENGTH_SHORT).show()
                return !ok
            }
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) = refresh(url)
            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) = refresh(url)
        }

        useBtn.setOnClickListener {
            val url = web.url ?: return@setOnClickListener
            finishSaving(url)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finishSaving(null)
            }
        })

        web.loadUrl(if (isSignedIn(this)) "https://m.youtube.com/feed/playlists" else START)
    }

    private fun refresh(url: String) {
        val isPlaylist = "list=" in url
        val isVideo = "watch?" in url || "/shorts/" in url
        useBtn.isEnabled = isPlaylist || isVideo
        useBtn.text = when {
            isPlaylist -> "Download this playlist"
            isVideo -> "Download this song"
            else -> "Open a playlist or song"
        }
    }

    private fun finishSaving(url: String?) {
        val signedIn = saveCookies(this)
        if (!signedIn) {
            if (url != null) Toast.makeText(this, "Not signed in – private playlists won't work", Toast.LENGTH_LONG).show()
        }
        val data = Intent().apply { if (url != null) putExtra(RESULT_URL, url.replace("://m.youtube.com", "://www.youtube.com")) }
        setResult(Activity.RESULT_OK, data)
        finish()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
