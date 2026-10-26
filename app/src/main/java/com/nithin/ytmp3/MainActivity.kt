package com.nithin.ytmp3

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var urlLayout: TextInputLayout
    private lateinit var urlInput: TextInputEditText
    private lateinit var playlistSwitch: MaterialSwitch
    private lateinit var qualityDropdown: AutoCompleteTextView
    private lateinit var downloadBtn: MaterialButton
    private lateinit var cancelBtn: MaterialButton
    private lateinit var statusText: TextView
    private lateinit var titleText: TextView
    private lateinit var itemText: TextView
    private lateinit var savedText: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var logText: TextView
    private lateinit var logToggle: MaterialButton
    private lateinit var updateBtn: MaterialButton

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    private val qualities = listOf("320", "256", "192", "128", "best")
    private val qualityLabels = listOf(
        "MP3 · 320 kbps",
        "MP3 · 256 kbps",
        "MP3 · 192 kbps",
        "MP3 · 128 kbps",
        "Original audio (no re-encode, not MP3)"
    )
    private var quality = "320"
    private var userTouchedSwitch = false

    private lateinit var accountText: TextView

    private val browseYouTube =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            updateAccountText()
            val url = res.data?.getStringExtra(YouTubeBrowserActivity.RESULT_URL) ?: return@registerForActivityResult
            userTouchedSwitch = false
            urlInput.setText(url)
            playlistSwitch.isChecked = "list=" in url
            startDownload()
        }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlLayout = findViewById(R.id.urlLayout)
        urlInput = findViewById(R.id.urlInput)
        playlistSwitch = findViewById(R.id.playlistSwitch)
        qualityDropdown = findViewById(R.id.qualityDropdown)
        downloadBtn = findViewById(R.id.downloadBtn)
        cancelBtn = findViewById(R.id.cancelBtn)
        statusText = findViewById(R.id.statusText)
        titleText = findViewById(R.id.titleText)
        itemText = findViewById(R.id.itemText)
        savedText = findViewById(R.id.savedText)
        progress = findViewById(R.id.progress)
        logText = findViewById(R.id.logText)
        logToggle = findViewById(R.id.logToggle)
        updateBtn = findViewById(R.id.updateBtn)

        // Quality picker (remembered)
        quality = prefs.getString("quality", "320") ?: "320"
        qualityDropdown.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, qualityLabels)
        )
        qualityDropdown.setText(qualityLabels[qualities.indexOf(quality).coerceAtLeast(0)], false)
        qualityDropdown.setOnItemClickListener { _, _, pos, _ ->
            quality = qualities[pos]
            prefs.edit().putString("quality", quality).apply()
        }

        findViewById<MaterialButton>(R.id.pasteBtn).setOnClickListener { pasteFromClipboard() }

        accountText = findViewById(R.id.accountText)
        accountText.setOnClickListener {
            if (YouTubeBrowserActivity.isSignedIn(this)) {
                YouTubeBrowserActivity.signOut(this)
                updateAccountText()
                Toast.makeText(this, "Signed out", Toast.LENGTH_SHORT).show()
            }
        }
        updateAccountText()
        findViewById<MaterialButton>(R.id.myYoutubeBtn).setOnClickListener {
            browseYouTube.launch(Intent(this, YouTubeBrowserActivity::class.java))
        }

        urlInput.doAfterTextChanged { text ->
            urlLayout.error = null
            val url = Links.extract(text?.toString())
            if (url != null && !userTouchedSwitch) {
                playlistSwitch.isChecked = Links.looksLikeCollection(url)
            }
        }
        urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) { startDownload(); true } else false
        }
        playlistSwitch.setOnClickListener { userTouchedSwitch = true }

        downloadBtn.setOnClickListener { startDownload() }
        cancelBtn.setOnClickListener { DownloadService.cancel(this) }

        logToggle.setOnClickListener {
            val show = logText.visibility != View.VISIBLE
            logText.visibility = if (show) View.VISIBLE else View.GONE
            logToggle.text = if (show) "Hide log" else "Show log"
        }

        updateBtn.setOnClickListener {
            updateBtn.isEnabled = false
            updateBtn.text = "Updating…"
            lifecycleScope.launch {
                val msg = withContext(Dispatchers.IO) { App.updateEngine(applicationContext, force = true) }
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                updateBtn.isEnabled = true
                updateBtn.text = "Update engine"
            }
        }

        findViewById<MaterialButton>(R.id.appUpdateBtn).setOnClickListener { checkAppUpdate(manual = true) }
        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar).subtitle =
            "Version ${BuildConfig.VERSION_NAME}"
        if (savedInstanceState == null) checkAppUpdate(manual = false)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DownloadState.ui.collect { render(it) }
            }
        }

        // Only act on a share once (not again after screen rotation)
        if (savedInstanceState == null) handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** Shared from the YouTube app -> fill in the link and start right away. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val url = Links.extract(intent.getStringExtra(Intent.EXTRA_TEXT)) ?: return
        userTouchedSwitch = false
        urlInput.setText(url)
        playlistSwitch.isChecked = Links.looksLikeCollection(url)
        startDownload()
    }

    // ------------------------------------------------------------ app self-update

    private fun checkAppUpdate(manual: Boolean) {
        lifecycleScope.launch {
            val rel = withContext(Dispatchers.IO) { Updater.latest() }
            when {
                rel == null -> if (manual) toast("Couldn't check for updates (offline, or the GitHub repo is private)")
                rel.build <= Updater.currentBuild -> if (manual) toast("You have the latest version (build ${Updater.currentBuild})")
                else -> MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("Update available")
                    .setMessage("Build ${rel.build} is ready (you have build ${Updater.currentBuild}).\n" +
                        "Download ${"%.0f".format(rel.sizeMb)} MB and install?")
                    .setPositiveButton("Update") { _, _ -> downloadAndInstall(rel) }
                    .setNegativeButton("Later", null)
                    .show()
            }
        }
    }

    private fun downloadAndInstall(rel: Updater.Release) {
        if (!Updater.canInstall(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle("One-time permission")
                .setMessage("Turn on “Allow from this source” on the next screen, come back, and tap Update again.")
                .setPositiveButton("Open settings") { _, _ -> Updater.openInstallPermission(this) }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        val bar = LinearProgressIndicator(this).apply { max = 100; setPadding(64, 32, 64, 0) }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Downloading update…")
            .setView(bar)
            .setCancelable(false)
            .show()
        lifecycleScope.launch {
            try {
                val apk = withContext(Dispatchers.IO) {
                    Updater.download(applicationContext, rel) { pct ->
                        runOnUiThread { bar.setProgressCompat(pct, true) }
                    }
                }
                dialog.dismiss()
                Updater.install(this@MainActivity, apk)
            } catch (e: Exception) {
                dialog.dismiss()
                toast("Update download failed: ${e.message}")
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun updateAccountText() {
        accountText.text = if (YouTubeBrowserActivity.isSignedIn(this))
            "Signed in to YouTube ✓ (private playlists work) · tap to sign out"
        else
            "Tip: “My YouTube” lets you sign in and download private lists like Sounds"
    }

    private fun pasteFromClipboard() {
        val cm = getSystemService(ClipboardManager::class.java)
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(this)?.toString()
        val url = Links.extract(text)
        if (url == null) {
            Toast.makeText(this, "No link on the clipboard", Toast.LENGTH_SHORT).show()
        } else {
            userTouchedSwitch = false
            urlInput.setText(url)
        }
    }

    private fun startDownload() {
        val url = Links.extract(urlInput.text?.toString())
        if (url == null) {
            urlLayout.error = "Paste a YouTube link first"
            return
        }
        if (!Links.isYouTube(url)) {
            urlLayout.error = "That's not a YouTube link (YouTube → Share → Copy link)"
            return
        }
        DownloadService.start(this, url, playlistSwitch.isChecked, quality)
        val what = if (playlistSwitch.isChecked) "Playlist added" else "Added"
        Toast.makeText(this, "$what to downloads", Toast.LENGTH_SHORT).show()
        urlInput.setText("")
        userTouchedSwitch = false
    }

    private fun render(s: DownloadState.Ui) {
        statusText.text = s.status
        titleText.text = if (s.running && s.lastSaved.isEmpty()) s.title else s.lastSaved.let {
            if (it.isEmpty()) "" else "Last saved: $it"
        }
        titleText.visibility = if (titleText.text.isNullOrEmpty()) View.GONE else View.VISIBLE

        if (s.itemCount > 1) {
            itemText.visibility = View.VISIBLE
            itemText.text = "Song ${s.itemIndex} of ${s.itemCount}"
        } else {
            itemText.visibility = View.GONE
        }

        progress.setProgressCompat(s.progress.coerceIn(0, 100), true)

        val parts = mutableListOf<String>()
        if (s.saved > 0) parts += "Saved ${s.saved} file(s)"
        if (s.failed > 0) parts += "${s.failed} failed"
        if (s.queued > 0) parts += "${s.queued} link(s) waiting"
        parts += "Folder: Download/${DownloadService.OUT_DIR}"
        savedText.text = parts.joinToString(" · ")

        cancelBtn.visibility = if (s.running) View.VISIBLE else View.GONE
        downloadBtn.text = if (s.running) "Add to queue" else "Download MP3"
        logText.text = s.log.ifEmpty { "(no output yet)" }
    }
}
