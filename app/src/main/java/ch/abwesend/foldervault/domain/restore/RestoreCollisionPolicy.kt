package ch.abwesend.foldervault.domain.restore

import androidx.annotation.StringRes
import ch.abwesend.foldervault.R

enum class RestoreCollisionPolicy(@StringRes val labelResId: Int) {
    SKIP(R.string.collision_skip),
    OVERWRITE(R.string.collision_overwrite),
    RENAME_WITH_SUFFIX(R.string.collision_rename),
}
