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
     * wrong password), or [RestoreResult.Failure] for unreadable, corrupt or uncopyable files.
     *
     * On any failure — and on cancellation — the output document is deleted again, unless it is a
     * pre-existing document the user picked via "overwrite" that was never actually written to
     * (deleting that would destroy data the restore never touched). Large files are processed in
     * cancellation-check chunks, so a cancel aborts within one chunk of work; small files finish
     * their (short) run first and are then cleaned up.
     */
    suspend fun decryptSingleFile(
        sourceFileUri: String,
        outputFileUri: String,
        password: String,
    ): RestoreResult
}
