package ch.abwesend.foldervault.domain.model

private const val DEFAULT_FILE_SIZE_LIMIT_BYTES = 256L * 1024 * 1024

data class AppSettings(
    val defaultSchedule: BackupSchedule = BackupSchedule.DAILY,
    val defaultChangedFilePolicy: ChangedFilePolicy = ChangedFilePolicy.DUPLICATE_WITH_TIMESTAMP,
    val defaultFileSizeLimitBytes: Long = DEFAULT_FILE_SIZE_LIMIT_BYTES,
    val theme: AppTheme = AppTheme.SYSTEM,
    val showOnboarding: Boolean = true,
    val defaultNetworkPolicy: NetworkPolicy = NetworkPolicy.WIFI_ONLY,
    val anonymousErrorReports: Boolean = true,
    /**
     * The known `FolderVault_<UUID>` backup roots, one per Google account that has been used by
     * at least one backup config. Empty until the first backup config is created.
     */
    val cloudRoots: List<CloudAccountRoot> = emptyList(),
) {
    /** Returns the backup root belonging to [accountIdentifier], or `null` if none exists yet. */
    fun rootForAccount(accountIdentifier: String): CloudAccountRoot? =
        cloudRoots.firstOrNull { it.accountIdentifier == accountIdentifier }
}
