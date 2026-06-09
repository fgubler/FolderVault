package ch.abwesend.foldervault.domain.cloud

import android.content.Intent
import ch.abwesend.foldervault.domain.result.BinaryResult

interface ICloudAuthorizer {
    suspend fun authorize(): CloudAuthResult<ICloudStorageProvider>
    suspend fun authorizeFromIntent(data: Intent?): BinaryResult<ICloudStorageProvider, Exception>
    suspend fun clearAuthorization(): BinaryResult<Unit, Exception>
}
