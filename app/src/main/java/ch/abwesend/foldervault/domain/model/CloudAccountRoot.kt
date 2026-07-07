package ch.abwesend.foldervault.domain.model

import kotlinx.serialization.Serializable

/**
 * The `FolderVault_<UUID>` Drive root belonging to one Google account.
 *
 * There is one root per account (not one per install): every backup config targeting
 * [accountIdentifier] places its sub-folder under this root.
 */
@Serializable
data class CloudAccountRoot(
    /** Account identifier (Drive email) that owns this root. */
    val accountIdentifier: String,
    /** Drive folder ID of the root. */
    val rootFolderId: String,
    /** Display name of the root (`FolderVault_<UUID>`). */
    val rootFolderName: String,
)
