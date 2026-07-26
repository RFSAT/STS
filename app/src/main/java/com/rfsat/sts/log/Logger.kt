package com.rfsat.sts.log

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

enum class LogLevel(val label: String) { INFO("I"), WARNING("W"), ERROR("E") }

data class LogEntry(val timestampMs: Long, val level: LogLevel, val tag: String, val message: String) {
    override fun toString(): String {
        val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date(timestampMs))
        return "$ts ${level.label}/$tag: $message"
    }
}

/**
 * App-wide debug log with three severity levels.
 *
 * Entries are BOTH kept in memory and appended synchronously to a file in
 * private storage. The file is what survives a process death, which is
 * exactly when the log matters most. On startup the tail of the persisted
 * file is reloaded, so the previous session's final moments are visible.
 *
 * Every entry is also mirrored to android.util.Log for adb logcat.
 */
object Logger {
    private const val MAX_ENTRIES = 4000
    private const val MAX_FILE_BYTES = 512 * 1024L
    private val entries = CopyOnWriteArrayList<LogEntry>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var logFile: File? = null
    private val fileLock = Any()

    /** Call once from Application.onCreate. Reloads the persisted tail. */
    fun init(context: Context) {
        val f = File(context.filesDir, "sts_log.txt")
        logFile = f
        try {
            if (f.exists()) {
                if (f.length() > MAX_FILE_BYTES) trimFile(f)
                val previous = f.readLines().takeLast(500)
                if (previous.isNotEmpty()) {
                    entries.add(
                        LogEntry(
                            System.currentTimeMillis(), LogLevel.INFO, "Logger",
                            "---- ${previous.size} line(s) restored from previous session below ----"
                        )
                    )
                    previous.forEach { line ->
                        entries.add(LogEntry(System.currentTimeMillis(), levelFromLine(line), "prev", line))
                    }
                    entries.add(
                        LogEntry(
                            System.currentTimeMillis(), LogLevel.INFO, "Logger",
                            "---- end of previous session ----"
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            Log.e("Logger", "Failed to restore persisted log", t)
        }
    }

    private fun levelFromLine(line: String): LogLevel = when {
        line.contains(" E/") -> LogLevel.ERROR
        line.contains(" W/") -> LogLevel.WARNING
        else -> LogLevel.INFO
    }

    private fun trimFile(f: File) {
        runCatching {
            val keep = f.readLines().takeLast(1500)
            f.writeText(keep.joinToString("\n", postfix = "\n"))
        }
    }

    fun i(tag: String, message: String) = add(LogLevel.INFO, tag, message)
    fun w(tag: String, message: String) = add(LogLevel.WARNING, tag, message)
    fun e(tag: String, message: String, t: Throwable? = null) =
        add(LogLevel.ERROR, tag, if (t == null) message else "$message\n${Log.getStackTraceString(t)}")

    private fun add(level: LogLevel, tag: String, message: String) {
        val entry = LogEntry(System.currentTimeMillis(), level, tag, message)
        entries.add(entry)
        while (entries.size > MAX_ENTRIES) entries.removeAt(0)
        when (level) {
            LogLevel.INFO -> Log.i(tag, message)
            LogLevel.WARNING -> Log.w(tag, message)
            LogLevel.ERROR -> Log.e(tag, message)
        }
        val f = logFile
        if (f != null) {
            synchronized(fileLock) {
                runCatching {
                    f.appendText(entry.toString() + "\n")
                    if (f.length() > MAX_FILE_BYTES) trimFile(f)
                }
            }
        }
        listeners.forEach { runCatching { it() } }
    }

    fun snapshot(): List<LogEntry> = entries.toList()

    fun clear() {
        entries.clear()
        logFile?.let { f -> synchronized(fileLock) { runCatching { f.writeText("") } } }
        listeners.forEach { runCatching { it() } }
    }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    fun asText(minLevel: LogLevel = LogLevel.INFO): String =
        entries.filter { it.level.ordinal >= minLevel.ordinal }.joinToString("\n")
}
