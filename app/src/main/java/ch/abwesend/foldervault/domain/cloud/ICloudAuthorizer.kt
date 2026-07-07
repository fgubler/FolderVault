package ch.abwesend.foldervault.domain.cloud

import android.content.Intent
import ch.abwesend.foldervault.domain.result.BinaryResult

interface ICloudAuthorizer {
    /**
     * Requests (or silently resolves) authorization for the cloud provider.
     *
     * [accountName] targets a specific account (e.g. a Drive email): an existing grant for that
     * account resolves silently, otherwise consent is requested for exactly that account.
     * `null` resolves whichever grant the platform picks (legacy behavior; only appropriate when
     * no config-specific account is known).
     */
    suspend fun authorize(accountName: String? = null): CloudAuthResult<ICloudStorageProvider>
    suspend fun authorizeFromIntent(data: Intent?): BinaryResult<ICloudStorageProvider, Exception>
    suspend fun clearAuthorization(): BinaryResult<Unit, Exception>
}
