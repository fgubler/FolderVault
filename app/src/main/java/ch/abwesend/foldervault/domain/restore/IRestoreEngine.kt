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
     * Restores a single picked file into the destination folder [outputFolderUri] (a tree the
     * caller picked via the system folder picker). The output file is created by the engine with
     * [outputFileName] as its base name; if a file of that name already exists in the folder, a
     * unique name is generated automatically (`name_restored`, `name_restored_2`, …) so a restore
     * can never overwrite an existing file. Returns [RestoreResult.Success] with `decrypted = 1`
     * (or `copied = 1` for a plain file), [RestoreResult.InvalidPassword] only when decryption
     * fails on the GCM tag check (a wrong password), or [RestoreResult.Failure] for unreadable,
     * corrupt or uncopyable files.
     *
     * On any failure — and on cancellation — the freshly created output document is deleted again.
     * Large files are processed in cancellation-check chunks, so a cancel aborts within one chunk
     * of work; small files finish their (short) run first and are then cleaned up.
     */
    suspend fun decryptSingleFile(
        sourceFileUri: String,
        outputFolderUri: String,
        outputFileName: String,
        password: String,
    ): RestoreResult
}
