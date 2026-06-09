package ch.abwesend.foldervault.domain.cloud

import android.app.PendingIntent

sealed interface CloudAuthResult<out T> {
    data class ConsentRequired(val pendingIntent: PendingIntent) : CloudAuthResult<Nothing>
    data class Authorized<T>(val data: T) : CloudAuthResult<T>
    data object Error : CloudAuthResult<Nothing>
}
