package ch.abwesend.foldervault.domain.model

import androidx.annotation.StringRes
import ch.abwesend.foldervault.R

enum class NetworkPolicy(@StringRes val labelResId: Int) {
    WIFI_ONLY(R.string.network_wifi_only),
    ANY(R.string.network_any),
}
