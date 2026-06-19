package ch.abwesend.foldervault.infrastructure.backup

import ch.abwesend.foldervault.domain.logging.logger
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Manages app-private staging directories for in-flight encrypted temp files.
 * Each run gets its own subdir: <stagingRoot>/<runId>_<yyyy-MM-dd>/
 * Cleanup at startup removes dirs whose embedded date is >= 2 days old.
 */
class StagingDirManager(private val stagingRoot: File) {

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    fun createRunDir(runId: String): File {
        val date = LocalDate.now().format(dateFormatter)
        return File(stagingRoot, "${runId}_$date").also { it.mkdirs() }
    }

    fun cleanupOldDirs() {
        val today = LocalDate.now()
        stagingRoot.listFiles()?.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            val date = extractDate(dir.name) ?: return@forEach
            if (ChronoUnit.DAYS.between(date, today) >= 2) {
                dir.deleteRecursively()
            }
        }
    }

    private fun extractDate(dirName: String): LocalDate? {
        val dateStr = dirName.substringAfterLast('_', "")
        return try {
            LocalDate.parse(dateStr, dateFormatter)
        } catch (e: Exception) {
            logger.warning("Malformed staging dir name '$dirName' — skipping cleanup", e)
            null
        }
    }
}
