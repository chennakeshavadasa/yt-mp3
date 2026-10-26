package com.nithin.ytmp3

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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

        fun cookieFile(ctx: Context) = File(ctx.filesDir, "youtube_cookies.txt")

        fun isSignedIn(ctx: Context) = cookieFile(ctx).let { it.exists() && it.length() > 0 }

        fun signOut(ctx: Context) {
            cookieFile(ctx).delete()
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }

        /**
         * Write the WebView's YouTube cookies in Netscape cookies.txt format for yt-dlp.
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
            cookieFile(ctx).writeText(sb.toString())
            return signedIn
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
            // Look like normal Chrome so Google allows the sign-in page
            userAgentString = userAgentString.replace("; wv", "").replace(Regex("""Version/\S+\s"""), "")
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest) = false
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
            cookieFile(this).delete()
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
