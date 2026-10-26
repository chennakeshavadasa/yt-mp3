package com.nithin.ytmp3

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Shared live state between the download service and the screen. */
object DownloadState {

    data class Ui(
        val running: Boolean = false,
        val status: String = "Ready",
        val title: String = "",
        val itemIndex: Int = 0,
        val itemCount: Int = 0,
        val progress: Int = 0,
        val saved: Int = 0,
        val failed: Int = 0,
        val queued: Int = 0,
        val lastSaved: String = "",
        val log: String = ""
    )

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui

    private val lines = ArrayDeque<String>()

    fun update(f: (Ui) -> Ui) = _ui.update(f)

    @Synchronized
    fun log(line: String) {
        val clean = line.trim()
        if (clean.isEmpty()) return
        lines.addLast(clean)
        while (lines.size > 120) lines.removeFirst()
        val text = lines.joinToString("\n")
        _ui.update { it.copy(log = text) }
    }
}
