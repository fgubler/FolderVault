package ch.abwesend.foldervault.domain.restore

interface IRestoreEngine {
    suspend fun scanSourceFolder(sourceUri: String): RestoreScanResult

    suspend fun decryptAll(
        sourceUri: String,
        outputUri: String,
        password: String,
        collisionPolicy: RestoreCollisionPolicy,
        onProgress: (RestoreProgress) -> Unit,
    ): RestoreResult

    /**
     * Restores a single picked file into [outputFileUri] (a document the caller already created via
     * the system "Save as" picker). No collision policy is needed because the picker chose the exact
     * target. Returns [RestoreResult.Success] with `decrypted = 1` (or `copied = 1` for a plain
     * file), [RestoreResult.InvalidPassword] only when decryption fails on the GCM tag check (a
     * wrong password), or [RestoreResult.Failure] for unreadable, corrupt or uncopyable files. On
     * any failure the pre-created output document is deleted again.
     */
    suspend fun decryptSingleFile(
        sourceFileUri: String,
        outputFileUri: String,
        password: String,
    ): RestoreResult
}
