package com.nithin.ytmp3

import org.json.JSONObject

/**
 * Builds the file name "SongName_Language" from yt-dlp's video info.
 *
 * Song name  : YouTube Music "track" tag if present, otherwise the video title with
 *              the usual clutter removed ("Official Video", "| Lyrical", "4K", ...).
 * Language   : 1) a language word in the title  ("... | Kannada Song")
 *              2) YouTube's own language field
 *              3) a language word in the tags
 *              4) the script the title is written in (ಕನ್ನಡ -> Kannada, हिन्दी -> Hindi ...)
 *              5) a language word near the top of the description
 *              6) "Unknown"
 */
object Naming {

    private val languages = listOf(
        "Kannada", "Hindi", "Tamil", "Telugu", "Malayalam", "Marathi", "Bengali",
        "Punjabi", "Gujarati", "Odia", "Tulu", "Konkani", "Bhojpuri", "Urdu",
        "Sanskrit", "Assamese", "English", "Korean", "Japanese", "Spanish",
        "French", "German", "Arabic", "Italian", "Portuguese", "Russian", "Chinese"
    )

    // Common alternate spellings
    private val aliases = mapOf(
        "bangla" to "Bengali", "oriya" to "Odia", "k-pop" to "Korean", "kpop" to "Korean",
        "j-pop" to "Japanese", "jpop" to "Japanese", "bollywood" to "Hindi",
        "sandalwood" to "Kannada", "tollywood" to "Telugu", "kollywood" to "Tamil",
        "mollywood" to "Malayalam"
    )

    private val isoCodes = mapOf(
        "en" to "English", "hi" to "Hindi", "kn" to "Kannada", "ta" to "Tamil",
        "te" to "Telugu", "ml" to "Malayalam", "mr" to "Marathi", "bn" to "Bengali",
        "pa" to "Punjabi", "gu" to "Gujarati", "or" to "Odia", "ur" to "Urdu",
        "sa" to "Sanskrit", "as" to "Assamese", "ko" to "Korean", "ja" to "Japanese",
        "es" to "Spanish", "fr" to "French", "de" to "German", "ar" to "Arabic",
        "it" to "Italian", "pt" to "Portuguese", "ru" to "Russian", "zh" to "Chinese"
    )

    fun fileBase(info: JSONObject): String {
        val lang = language(info)
        val song = songName(info, lang).ifBlank { info.optString("id", "audio") }
        return "${song}_$lang"
    }

    /**
     * Spotify songs: the clean Spotify title is the song name; language comes from the
     * YouTube match (its title/tags often say "Kannada", "Hindi"…) or the title's script.
     */
    fun fileBaseFor(info: JSONObject, songTitle: String): String {
        var lang = language(info)
        if (lang == "Unknown") lang = language(JSONObject().put("title", songTitle))
        val song = songName(JSONObject().put("title", songTitle), lang).ifBlank { sanitize(songTitle) }
        return "${song}_$lang"
    }

    // ---------------------------------------------------------------- language

    fun language(info: JSONObject): String {
        val title = info.optString("title")
        findLanguageWord(title)?.let { return it }

        val code = info.optString("language").lowercase().substringBefore('-')
        isoCodes[code]?.let { return it }

        val tags = info.optJSONArray("tags")
        if (tags != null) {
            val joined = (0 until tags.length()).joinToString(" ") { tags.optString(it) }
            findLanguageWord(joined)?.let { return it }
        }

        scriptLanguage(title)?.let { return it }

        findLanguageWord(info.optString("description").take(400))?.let { return it }

        return "Unknown"
    }

    private fun findLanguageWord(text: String): String? {
        if (text.isBlank()) return null
        val lower = text.lowercase()
        for (lang in languages) {
            if (Regex("""\b${lang.lowercase()}\b""").containsMatchIn(lower)) return lang
        }
        for ((alias, lang) in aliases) {
            if (Regex("""(^|[^a-z])${Regex.escape(alias)}($|[^a-z])""").containsMatchIn(lower)) return lang
        }
        return null
    }

    /** Detect language from the Unicode script of the title. */
    private fun scriptLanguage(text: String): String? {
        val counts = HashMap<String, Int>()
        for (ch in text) {
            val lang = when (ch.code) {
                in 0x0C80..0x0CFF -> "Kannada"
                in 0x0900..0x097F -> "Hindi"
                in 0x0B80..0x0BFF -> "Tamil"
                in 0x0C00..0x0C7F -> "Telugu"
                in 0x0D00..0x0D7F -> "Malayalam"
                in 0x0980..0x09FF -> "Bengali"
                in 0x0A00..0x0A7F -> "Punjabi"
                in 0x0A80..0x0AFF -> "Gujarati"
                in 0x0B00..0x0B7F -> "Odia"
                in 0x0600..0x06FF -> "Urdu"
                in 0xAC00..0xD7AF, in 0x1100..0x11FF -> "Korean"
                in 0x3040..0x30FF -> "Japanese"
                in 0x4E00..0x9FFF -> "Chinese"
                in 0x0400..0x04FF -> "Russian"
                else -> null
            } ?: continue
            counts[lang] = (counts[lang] ?: 0) + 1
        }
        // Japanese titles mix kana + kanji: any kana means Japanese
        if ((counts["Japanese"] ?: 0) > 0) return "Japanese"
        return counts.maxByOrNull { it.value }?.key
    }

    // ---------------------------------------------------------------- song name

    private val bracketNoise = Regex(
        """[(\[{【][^)\]}】]*?(official|video|lyric|lyrical|audio|full|song|hd|4k|8k|1080|720|music|visuali[sz]er|teaser|trailer|remaster|from|feat|ft\.|prod|version|explicit|cover)[^)\]}】]*[)\]}】]""",
        RegexOption.IGNORE_CASE
    )

    private val wordNoise = Regex(
        """\b(official\s+)?(full\s+)?(hd\s+)?(video|lyric(al)?|audio)\s+songs?\b""" +
            """|\bofficial\s+(music\s+|lyric(al)?\s+)?(video|audio)\b""" +
            """|\blyric(al)?\s+video\b|\bfull\s+songs?\b|\bmusic\s+video\b""" +
            """|\b(4k|8k|hd|hq|1080p|720p)\b|\bnew\s+songs?\b|\b(19|20)\d\d\s+songs?\b""",
        RegexOption.IGNORE_CASE
    )

    private fun clean(v: String?) = v?.takeIf { it.isNotBlank() && it != "null" }?.trim()

    private fun words(s: String) = s.split(' ').count { it.isNotBlank() }

    /**
     * The visible video title is the main source. (YouTube's hidden "track" tag is
     * unreliable – for some songs it holds only part of the name, e.g. "You"
     * for "Run To You" – so it's only a last resort.)
     */
    fun songName(info: JSONObject, language: String): String {
        val title = clean(info.optString("title")) ?: ""
        val track = clean(info.optString("track"))

        // "Song | Movie | Singer" -> "Song"
        val firstSeg = title.split('|', '｜', '•').firstOrNull { it.isNotBlank() }?.trim() ?: title

        var name = bracketNoise.replace(firstSeg, " ")
        name = wordNoise.replace(name, " ")
        // leftover trailing "Song" / "Songs" ("Belageddu Lyrical Video Song" -> "Belageddu")
        name = Regex("""\s+songs?\s*$""", RegexOption.IGNORE_CASE).replace(name.trim(), "")

        // "Artist - Song" vs "Song - Movie": drop the part that is exactly the channel/artist name
        if (" - " in name || " – " in name) {
            val parts = name.split(" - ", " – ").map { it.trim() }.filter { it.isNotEmpty() }
            val who = listOf("artist", "creator", "uploader", "channel")
                .mapNotNull { clean(info.optString(it))?.lowercase() }
            val rest = parts.filterNot { p ->
                val pl = p.lowercase()
                who.any { w -> pl == w || (pl.length >= 6 && w.startsWith(pl)) }
            }
            name = rest.firstOrNull() ?: parts.firstOrNull() ?: name
        }

        // Don't repeat the language: "Kesariya Hindi" -> "Kesariya"
        if (language != "Unknown") {
            name = Regex("""\b${language}\b(\s+songs?)?""", RegexOption.IGNORE_CASE).replace(name, " ")
        }
        name = sanitize(name)

        // Safety net: cleaning must never shrink a real title to a fragment.
        val plain = sanitize(bracketNoise.replace(firstSeg, " ")).ifBlank { sanitize(firstSeg) }
        if (name.length < 3 || (words(name) == 1 && words(plain) >= 3 && name.length <= 5)) {
            name = plain
        }
        if (name.isBlank()) name = track?.let { sanitize(it) } ?: ""
        return name
    }

    fun sanitize(s: String): String =
        s.replace(Regex("""[\\/:*?"<>|#%{}^~\[\]`]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '-', '_', '.', ',', '–', '(', ')')
            .take(80)
            .trim()
}
