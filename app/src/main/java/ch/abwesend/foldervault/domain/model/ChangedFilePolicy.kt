package ch.abwesend.foldervault.domain.model

import androidx.annotation.StringRes
import ch.abwesend.foldervault.R

enum class ChangedFilePolicy(@StringRes val labelResId: Int) {
    DUPLICATE_WITH_TIMESTAMP(R.string.changed_file_keep_timestamp),
    OVERWRITE(R.string.changed_file_overwrite),
    IGNORE(R.string.changed_file_skip),
}
