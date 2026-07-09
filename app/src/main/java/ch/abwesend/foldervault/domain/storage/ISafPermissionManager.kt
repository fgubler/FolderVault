package ch.abwesend.foldervault.domain.storage

/**
 * Hands persisted Storage-Access-Framework (SAF) tree-URI grants back to the system.
 *
 * A folder the user picks is kept accessible across process restarts via
 * `takePersistableUriPermission`. Those grants are a scarce, process-wide resource (capped at 128
 * before API 30, 512 after); once the cap is hit, new folder picks silently fail. Whenever a grant
 * is no longer needed — a config is deleted, or repointed at a different folder — it must be handed
 * back so the app does not slowly leak toward that cap.
 */
interface ISafPermissionManager {
    /**
     * Releases the persisted read permission previously taken for [treeUri]. Best-effort: a URI
     * that was never persisted (or was already released) is not an error and is silently ignored.
     */
    fun releasePersistedPermission(treeUri: String)
}
