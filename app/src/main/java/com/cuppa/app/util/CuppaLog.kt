package com.cuppa.app.util

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * CuppaLog — In-memory ring buffer logger combined with Android's system Logcat.
 *
 * Ensures logs are always captured reliably in-memory regardless of Android SELinux
 * restrictions on reading logcat, while preserving standard logcat output.
 */
object CuppaLog {
    private const val MAX_LOGS = 5000
    private val buffer = ConcurrentLinkedDeque<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    init {
        i("CuppaLog", "Cuppa logging subsystem initialized (capacity: $MAX_LOGS entries)")
    }

    fun v(tag: String, msg: String, tr: Throwable? = null) {
        if (tr != null) Log.v(tag, msg, tr) else Log.v(tag, msg)
        record("V", tag, msg, tr)
    }

    fun d(tag: String, msg: String, tr: Throwable? = null) {
        if (tr != null) Log.d(tag, msg, tr) else Log.d(tag, msg)
        record("D", tag, msg, tr)
    }

    fun i(tag: String, msg: String, tr: Throwable? = null) {
        if (tr != null) Log.i(tag, msg, tr) else Log.i(tag, msg)
        record("I", tag, msg, tr)
    }

    fun w(tag: String, msg: String, tr: Throwable? = null) {
        if (tr != null) Log.w(tag, msg, tr) else Log.w(tag, msg)
        record("W", tag, msg, tr)
    }

    fun w(tag: String, tr: Throwable) {
        Log.w(tag, tr.message, tr)
        record("W", tag, tr.message ?: "Warning", tr)
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        if (tr != null) Log.e(tag, msg, tr) else Log.e(tag, msg)
        record("E", tag, msg, tr)
    }

    fun e(tag: String, tr: Throwable) {
        Log.e(tag, tr.message, tr)
        record("E", tag, tr.message ?: "Error", tr)
    }

    private fun record(level: String, tag: String, msg: String, tr: Throwable?) {
        val time = timeFormat.format(Date())
        val entry = if (tr != null) {
            "[$time] [$level/$tag] $msg\n${Log.getStackTraceString(tr).trimEnd()}"
        } else {
            "[$time] [$level/$tag] $msg"
        }
        buffer.add(entry)
        while (buffer.size > MAX_LOGS) {
            buffer.pollFirst()
        }
    }

    fun getAllLogs(): String {
        return if (buffer.isEmpty()) "No logs captured yet." else buffer.joinToString("\n")
    }

    fun clear() {
        buffer.clear()
        i("CuppaLog", "Log buffer cleared")
    }
}
