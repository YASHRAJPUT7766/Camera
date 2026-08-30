package com.testlab.camerasec.log

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Categories of events this lab records. Kept small and descriptive on purpose:
 * every entry should read like a plain-English report of what Android did,
 * not a raw debug dump.
 */
enum class LogCategory {
    PERMISSION,
    CAMERA,
    LIFECYCLE,
    STREAMING,
    CAMERA_SWITCH,
    CONNECTION
}

data class LogEntry(
    val timestamp: Long,
    val category: LogCategory,
    val message: String
) {
    fun formattedTime(): String =
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(timestamp))
}

/**
 * Simple in-memory, in-process event log for the whole app.
 *
 * This is intentionally local only:
 *  - no analytics SDK
 *  - no network call
 *  - no disk persistence beyond this process's memory
 *
 * It exists purely so the person running the test can see, in order, exactly
 * what the app and Android did — permission grants/denials, camera start/stop,
 * lifecycle transitions, streaming start/stop, camera switches, and dashboard
 * connect/disconnect events.
 */
object SecurityTestLog {

    private const val MAX_ENTRIES = 500

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries = _entries.asStateFlow()

    fun log(category: LogCategory, message: String) {
        val entry = LogEntry(System.currentTimeMillis(), category, message)
        val updated = (_entries.value + entry).takeLast(MAX_ENTRIES)
        _entries.value = updated
    }

    fun clear() {
        _entries.value = emptyList()
    }
}
