package com.nithin.ytmp3

object Links {
    private val urlRegex = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)

    /** Pulls the first link out of shared/pasted text (YouTube shares often add extra words). */
    fun extract(text: String?): String? {
        if (text.isNullOrBlank()) return null
        urlRegex.find(text)?.let { return it.value.trimEnd(')', ']', '.', ',', '"', '\'') }
        // Allow bare "youtu.be/xyz" or "www.youtube.com/..." without https://
        val t = text.trim()
        if (t.startsWith("youtu", true) || t.startsWith("www.youtu", true) ||
            t.startsWith("m.youtube", true) || t.startsWith("music.youtube", true)
        ) return "https://$t"
        return null
    }

    /**
     * Guess whether the link means "everything": a playlist page or a channel.
     * A normal video opened from inside a playlist (watch?v=..&list=..) counts as ONE video;
     * flip the switch on if you want the whole list.
     */
    fun looksLikeCollection(url: String): Boolean {
        val u = url.lowercase()
        if ("/playlist" in u) return true
        if ("list=" in u && "watch?" !in u && "youtu.be/" !in u) return true
        return listOf("/@", "/channel/", "/c/", "/user/").any { it in u }
    }
}
