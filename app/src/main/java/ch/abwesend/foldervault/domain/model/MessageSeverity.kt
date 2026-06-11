package ch.abwesend.foldervault.domain.model

import androidx.annotation.StringRes
import ch.abwesend.foldervault.R

enum class MessageSeverity(@StringRes val labelResId: Int) {
    INFO(R.string.severity_info),
    WARNING(R.string.severity_warning),
    ERROR(R.string.severity_error),
    CRITICAL(R.string.severity_critical),
}
