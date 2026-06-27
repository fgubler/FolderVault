package ch.abwesend.foldervault.domain.logging

/**
 * Exports the application's local log files to a user-chosen location.
 *
 * The destination is represented as a SAF URI string so that the domain layer stays free of
 * Android types — callers in the view layer convert their `android.net.Uri` via `toString()`.
 */
interface ILogExporter {
    /**
     * Copies today's log file to [destinationUri].
     *
     * @return `true` if the log was exported, `false` if there is no log to export or the
     *   destination could not be written to.
     */
    fun exportTodayLog(destinationUri: String): Boolean
}
