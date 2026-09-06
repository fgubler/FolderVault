package ch.abwesend.foldervault.domain.restore

/**
 * Progress of a running restore.
 *
 * The two flows measure genuinely different things, so they report different shapes rather than
 * squeezing one into the other: a whole-folder restore knows how many files it has left, while a
 * single-file restore has exactly one file and can only say how far into it the bytes have got.
 * Reporting the single-file run as "0 / 1 files" would be accurate and useless — a multi-gigabyte
 * decrypt would sit at 0 for its entire duration.
 */
sealed interface RestoreProgress {

    /** Whole-folder restore: one unit per file. */
    data class Files(
        val total: Int,
        val processed: Int,
        val failed: Int,
        val currentFileName: String,
    ) : RestoreProgress

    /**
     * Single-file restore: one unit per byte of the *source*. For a decrypt that is the ciphertext,
     * which is what the reading side can actually measure. [totalBytes] is `null` when the document
     * provider does not report a size (SAF returns 0 for "unknown", not just for "empty"), which
     * leaves the bar indeterminate while still showing that the run is moving.
     */
    data class Bytes(
        val processedBytes: Long,
        val totalBytes: Long?,
    ) : RestoreProgress {
        /** Completed fraction in `0f..1f`, or `null` when the source size is unknown. */
        val fraction: Float?
            get() = totalBytes
                ?.takeIf { it > 0L }
                ?.let { (processedBytes.toFloat() / it.toFloat()).coerceIn(0f, 1f) }

        /** Completed percentage in `0..100`, or `null` when the source size is unknown. */
        val percent: Int?
            get() = fraction?.let { (it * PERCENT).toInt() }

        private companion object {
            private const val PERCENT = 100
        }
    }
}
