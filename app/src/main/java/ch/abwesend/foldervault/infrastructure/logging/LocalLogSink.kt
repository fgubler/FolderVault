package ch.abwesend.foldervault.infrastructure.logging

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal class LocalLogSink(
    context: Context,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val appendAsync: ((String) -> Unit)? = null,
) : LogSink {
    private val logFiles = LocalLogFiles(context, clock)
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val asyncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        runCatching { (appendAsync ?: ::appendToFileAsync)(entry) }
            .onFailure { Log.e("LocalLogSink", "Failed to dispatch local logfile append", it) }
    }

    private fun appendToFileAsync(entry: String) {
        asyncScope.launch {
            runCatching { logFiles.append(entry) }
                .onFailure { Log.e("LocalLogSink", "Failed to append local logfile entry asynchronously", it) }
        }
    }

    private fun Throwable.asStackTrace(): String {
        val writer = StringWriter()
        printStackTrace(PrintWriter(writer))
        return writer.toString()
    }
}
