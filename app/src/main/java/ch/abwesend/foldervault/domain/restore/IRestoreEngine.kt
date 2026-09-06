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
     * Restores a single picked file into the destination document [outputFileUri] — the file the
     * user named in the system "Save as" file picker. The picker itself decides the name and the
     * location, and it is the picker (not this engine) that resolves a collision with an existing
     * file: picking an existing name means the user confirmed overwriting it, so its previous
     * content is replaced. Returns [RestoreResult.Success] with `decrypted = 1` (or `copied = 1`
     * for a plain file), [RestoreResult.InvalidPassword] only when decryption fails on the GCM tag
     * check (a wrong password), or [RestoreResult.Failure] for unreadable, corrupt or uncopyable
     * files.
     *
     * On any failure — and on cancellation — the output document is deleted again, so no truncated
     * plaintext is left behind masquerading as a restored file. Large files are processed in
     * cancellation-check chunks, so a cancel aborts within one chunk of work; small files finish
     * their (short) run first and are then cleaned up.
     */
    suspend fun decryptSingleFile(
        sourceFileUri: String,
        outputFileUri: String,
        password: String,
    ): RestoreResult
}
