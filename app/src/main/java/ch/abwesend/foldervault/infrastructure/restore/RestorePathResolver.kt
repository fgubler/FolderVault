package ch.abwesend.foldervault.infrastructure.restore

import ch.abwesend.foldervault.domain.restore.RestoreCollisionPolicy

internal object RestorePathResolver {
    private const val CRYPT_SUFFIX = ".crypt"
    private const val RESTORE_SUFFIX = "_restored"

    fun outputRelativePath(inputPath: String, isCrypt: Boolean): String =
        if (isCrypt) inputPath.removeSuffix(CRYPT_SUFFIX) else inputPath

    fun resolvedName(fileName: String, policy: RestoreCollisionPolicy): String? =
        when (policy) {
            RestoreCollisionPolicy.SKIP -> null
            RestoreCollisionPolicy.OVERWRITE -> fileName
            RestoreCollisionPolicy.RENAME_WITH_SUFFIX -> indexedRestoreName(fileName, 1)
        }

    /**
     * Builds the [index]-th collision-avoidance name: index 1 → `name_restored.ext`, index 2 →
     * `name_restored_2.ext`, and so on. Lets the caller loop until it finds a free name so a second
     * restore into the same folder does not silently collide with an already-restored copy (BUG-7).
     */
    fun indexedRestoreName(fileName: String, index: Int): String {
        val suffix = if (index <= 1) RESTORE_SUFFIX else "${RESTORE_SUFFIX}_$index"
        val dot = fileName.lastIndexOf('.')
        return if (dot > 0) {
            fileName.substring(0, dot) + suffix + fileName.substring(dot)
        } else {
            fileName + suffix
        }
    }
}
