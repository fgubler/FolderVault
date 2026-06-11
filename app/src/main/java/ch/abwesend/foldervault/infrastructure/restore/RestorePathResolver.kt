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
            RestoreCollisionPolicy.RENAME_WITH_SUFFIX -> appendRestoreSuffix(fileName)
        }

    private fun appendRestoreSuffix(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) {
            name.substring(0, dot) + RESTORE_SUFFIX + name.substring(dot)
        } else {
            name + RESTORE_SUFFIX
        }
    }
}
