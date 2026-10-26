package com.nithin.ytmp3

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Spotify links: Spotify's audio is DRM-protected, so we only read the song list
 * (title + artist) from Spotify's public embed page, then find and download each
 * song from YouTube. Works for public tracks, albums and playlists
 * (not your private "Liked Songs").
 */
object Spotify {

    data class Track(val title: String, val artist: String)
    data class Collection(val name: String, val tracks: List<Track>)

    private val linkRe = Regex(
        """open\.spotify\.com/(?:intl-[a-z]+/)?(?:embed/)?(track|album|playlist)/([A-Za-z0-9]+)""",
        RegexOption.IGNORE_CASE
    )

    fun isSpotify(url: String): Boolean {
        val u = url.lowercase()
        return "open.spotify.com/" in u || "spotify.link/" in u || "spotify.app.link/" in u
    }

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"

    private fun get(url: String): Pair<String, String> {
        var current = url
        repeat(6) {
            val c = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 20_000
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept-Language", "en")
            }
            val code = c.responseCode
            if (code in 300..399) {
                current = URL(URL(current), c.getHeaderField("Location")).toString()
                c.disconnect()
                return@repeat
            }
            if (code != 200) throw IllegalStateException("Spotify returned HTTP $code")
            val body = c.inputStream.bufferedReader().use { it.readText() }
            return current to body
        }
        throw IllegalStateException("Too many redirects")
    }

    /** Blocking network call. */
    fun fetch(url: String): Collection {
        // Short links (spotify.link/...) redirect to open.spotify.com first
        var real = url
        if (linkRe.find(url) == null) {
            val (finalUrl, body) = get(url)
            real = linkRe.find(finalUrl)?.value ?: linkRe.find(body)?.value
                ?: throw IllegalArgumentException("Not a Spotify track, album or playlist link")
        }
        val m = linkRe.find(real) ?: throw IllegalArgumentException("Unsupported Spotify link")
        val (type, id) = m.destructured
        val (_, html) = get("https://open.spotify.com/embed/${type.lowercase()}/$id")
        val entity = findEntity(html) ?: throw IllegalStateException("Couldn't read the Spotify page")

        val name = entity.optString("name").ifBlank { entity.optString("title") }
        val list = entity.optJSONArray("trackList")
        val tracks = if (list != null && list.length() > 0) {
            (0 until list.length()).mapNotNull { i ->
                val t = list.optJSONObject(i) ?: return@mapNotNull null
                val title = t.optString("title")
                if (title.isBlank()) null else Track(title, artistsOf(t))
            }
        } else {
            listOf(Track(name, artistsOf(entity)))
        }
        return Collection(name, tracks)
    }

    private fun artistsOf(o: JSONObject): String {
        val arr: JSONArray? = o.optJSONArray("artists")
        if (arr != null && arr.length() > 0) {
            return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name") }
                .filter { it.isNotBlank() }.joinToString(", ")
        }
        return o.optString("subtitle").replace(' ', ' ').trim()
    }

    /** The embed page carries the data in one of a few script tags, depending on Spotify's version. */
    private fun findEntity(html: String): JSONObject? {
        fun script(id: String): String? =
            Regex("""<script[^>]*id="$id"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
                .find(html)?.groupValues?.get(1)?.trim()

        fun b64(s: String) = String(Base64.decode(s, Base64.DEFAULT))

        try {
            script("__NEXT_DATA__")?.let { s ->
                JSONObject(s).optJSONObject("props")?.optJSONObject("pageProps")
                    ?.optJSONObject("state")?.optJSONObject("data")?.optJSONObject("entity")
                    ?.let { return it }
            }
        } catch (_: Exception) { }
        try {
            script("resource")?.let { return JSONObject(b64(it)) }
        } catch (_: Exception) { }
        try {
            script("initial-state")?.let { s ->
                JSONObject(b64(s)).optJSONObject("data")?.optJSONObject("entity")?.let { return it }
            }
        } catch (_: Exception) { }
        return null
    }
}
