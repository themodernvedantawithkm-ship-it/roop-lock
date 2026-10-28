package com.deepfaceswap.android

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A running record of what the app actually did, in order.
 *
 * Written because every failure so far arrived as "it failed" with nothing
 * behind it. The engine returns a real error string, and the service was
 * discarding it and substituting "The swap could not be completed" - so the one
 * sentence that would have explained the fault was thrown away at the last step.
 *
 * Entries are kept in memory, newest last, capped so a long video cannot grow it
 * without bound. Everything is timestamped to the millisecond, because the useful
 * question is usually what happened *just before* the failure.
 */
object TraceLog {

    private const val CAPACITY = 600
    private val entries = ArrayDeque<String>(CAPACITY)
    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** One line. Thread-safe: the swap service writes from its own threads. */
    @Synchronized
    fun add(tag: String, message: String) {
        val line = "${stamp.format(Date())}  ${tag.padEnd(9)} $message"
        entries.addLast(line)
        while (entries.size > CAPACITY) entries.removeFirst()
    }

    @Synchronized
    fun lines(): List<String> = entries.toList()

    @Synchronized
    fun clear() = entries.clear()

    /** The last [count] entries, for the report. */
    fun tail(count: Int = 200): List<String> = lines().takeLast(count)

    @Synchronized
    fun asText(count: Int = 200): String =
        if (entries.isEmpty()) "(nothing recorded yet)" else tail(count).joinToString("\n")
}
