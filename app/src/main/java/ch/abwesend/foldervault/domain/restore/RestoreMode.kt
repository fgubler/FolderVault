package ch.abwesend.foldervault.domain.restore

import androidx.annotation.StringRes
import ch.abwesend.foldervault.R

/** The two ways to restore a backup: the whole downloaded folder, or a single picked file. */
enum class RestoreMode(@StringRes val labelResId: Int) {
    WHOLE_FOLDER(R.string.restore_mode_whole_folder),
    SINGLE_FILE(R.string.restore_mode_single_file),
}
