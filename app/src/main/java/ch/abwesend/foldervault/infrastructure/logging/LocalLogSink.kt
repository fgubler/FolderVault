package ch.abwesend.foldervault.infrastructure.logging

import android.content.Context
import android.util.Log
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal class LocalLogSink(
    context: Context,
    private val clock: Clock = Clock.systemDefaultZone(),
) : LogSink {
    private val logFiles = LocalLogFiles(context, clock)
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    override fun debug(tag: String, message: String) {
        Log.d(tag, message)
        appendEntry("DEBUG", tag, message)
    }

    override fun info(tag: String, message: String) {
        Log.i(tag, message)
        appendEntry("INFO", tag, message)
    }

    override fun warning(tag: String, message: String, error: Throwable?) {
        if (error != null) Log.w(tag, message, error) else Log.w(tag, message)
        appendEntry("WARN", tag, message, error)
    }

    override fun error(tag: String, message: String, error: Throwable?) {
        if (error != null) Log.e(tag, message, error) else Log.e(tag, message)
        appendEntry("ERROR", tag, message, error)
    }

    private fun appendEntry(level: String, tag: String, message: String, error: Throwable? = null) {
        val timestamp = Instant.now(clock).atZone(ZoneId.systemDefault()).format(formatter)
        val entry = buildString {
            append(timestamp)
            append(" [")
            append(level)
            append("] ")
            append(tag)
            append(": ")
            append(message)
            if (error != null) {
                appendLine()
                append(error.asStackTrace())
            }
        }
        logFiles.append(entry)
    }

    private fun Throwable.asStackTrace(): String {
        val writer = StringWriter()
        printStackTrace(PrintWriter(writer))
        return writer.toString()
    }
}
