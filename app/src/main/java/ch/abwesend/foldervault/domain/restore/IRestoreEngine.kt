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
}
