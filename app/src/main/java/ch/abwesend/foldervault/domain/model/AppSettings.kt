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
     * Drive folder ID of the shared per-install backup root (`FolderVault_<UUID>`).
     * `null` until the first backup config is created.
     */
    val cloudRootFolderId: String? = null,
    /** Display name of the shared per-install backup root. `null` until first config is created. */
    val cloudRootFolderName: String? = null,
    /**
     * Account identifier (Drive email) that owns the shared root. Used to detect account switches —
     * if the active provider's account no longer matches, the root must be recreated.
     */
    val cloudRootAccountIdentifier: String? = null,
)
