package ch.abwesend.foldervault.infrastructure.logging

import android.content.Context
import android.net.Uri
import android.util.Log
import ch.abwesend.foldervault.domain.logging.ILogExporter
import ch.abwesend.foldervault.domain.result.rethrowCancellation
import java.io.File
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private const val MAX_AGE_DAYS = 30L

internal class LocalLogFiles(
    context: Context,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ILogExporter {
    private val appContext = context.applicationContext
    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    private val logsDirectory = File(appContext.filesDir, "logs")
    private val lock = Any()

    fun append(entry: String) {
        synchronized(lock) {
            try {
                if (!logsDirectory.exists()) logsDirectory.mkdirs()
                cleanupExpiredLogs(LocalDate.now(clock))
                todayLogFile().appendText("$entry\n")
            } catch (e: Exception) {
                e.rethrowCancellation()
                Log.e("LocalLogFiles", "Failed to append local logfile entry", e)
            }
        }
    }

    override fun exportTodayLog(destinationUri: String): Boolean {
        val source = todayLogFile()
        if (!source.exists()) return false

        return try {
            appContext.contentResolver.openOutputStream(Uri.parse(destinationUri))?.use { output ->
                source.inputStream().use { input ->
                    input.copyTo(output)
                }
            } != null
        } catch (e: Exception) {
            e.rethrowCancellation()
            Log.e("LocalLogFiles", "Failed to export today's logfile", e)
            false
        }
    }

    private fun todayLogFile(): File =
        File(logsDirectory, "${LocalDate.now(clock).format(dateFormatter)}.log")

    private fun cleanupExpiredLogs(today: LocalDate) {
        val files = logsDirectory.listFiles() ?: return
        files.forEach { file ->
            val date = file.nameWithoutExtension.toLocalDateOrNull() ?: return@forEach
            if (date.isBefore(today.minusDays(MAX_AGE_DAYS))) {
                file.delete()
            }
        }
    }

    private fun String.toLocalDateOrNull(): LocalDate? =
        runCatching { LocalDate.parse(this, dateFormatter) }.getOrNull()
}
