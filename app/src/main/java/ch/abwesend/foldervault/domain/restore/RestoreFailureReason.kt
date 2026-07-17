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
}
