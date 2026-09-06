package ch.abwesend.foldervault.domain.restore

import androidx.annotation.StringRes
import ch.abwesend.foldervault.R

/**
 * Why a restore run failed, as a typed reason instead of pre-rendered English text, so the
 * user-facing message is resolved from string resources in the view layer (same pattern as
 * [RestoreCollisionPolicy] / [RestoreMode]).
 */
enum class RestoreFailureReason(@StringRes val messageResId: Int) {
    SOURCE_FOLDER_NOT_ACCESSIBLE(R.string.restore_failure_source_folder),
    OUTPUT_FOLDER_NOT_ACCESSIBLE(R.string.restore_failure_output_folder),
    SOURCE_FILE_NOT_ACCESSIBLE(R.string.restore_failure_source_file),
    OUTPUT_FILE_NOT_ACCESSIBLE(R.string.restore_failure_output_file),
    FILE_HEADER_NOT_READABLE(R.string.restore_failure_header_not_readable),
    INVALID_ENCRYPTED_FILE(R.string.restore_failure_invalid_encrypted_file),
    DECRYPTION_FAILED(R.string.restore_failure_decryption),
    COPY_FAILED(R.string.restore_failure_copy),

    /**
     * The "Save as" picker returned the source document itself. Writing into it would truncate
     * the encrypted backup that is being read — an unrecoverable loss — so the restore refuses.
     */
    OUTPUT_SAME_AS_SOURCE(R.string.restore_failure_same_as_source),

    /**
     * The output folder picked for a whole-folder restore *is* the backup folder. Every plain
     * (non-encrypted) file in the tree would then resolve to itself as its own output, and with
     * [RestoreCollisionPolicy.OVERWRITE] the collision handling would delete it before the copy
     * that was meant to recreate it could read it — destroying the file outright.
     */
    OUTPUT_FOLDER_SAME_AS_SOURCE(R.string.restore_failure_folder_same_as_source),

    /**
     * The host executing the run went away mid-restore — the foreground service was destroyed, or
     * the process is shutting down. Distinct from a user stop, which reports partial counts.
     */
    RUN_INTERRUPTED(R.string.restore_failure_interrupted),
}
