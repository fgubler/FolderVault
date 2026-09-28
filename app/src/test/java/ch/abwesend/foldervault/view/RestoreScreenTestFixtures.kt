package ch.abwesend.foldervault.view

import ch.abwesend.foldervault.domain.coroutine.IDispatchers
import ch.abwesend.foldervault.domain.restore.IForegroundRestoreLauncher
import ch.abwesend.foldervault.domain.restore.IRestoreEngine
import ch.abwesend.foldervault.domain.restore.RestoreCollisionPolicy
import ch.abwesend.foldervault.domain.restore.RestoreProgress
import ch.abwesend.foldervault.domain.restore.RestoreResult
import ch.abwesend.foldervault.domain.restore.RestoreRunControl
import ch.abwesend.foldervault.domain.restore.RestoreScanResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers

/*
 * Fixtures shared by the Compose tests of the restore screen. They live in one file on purpose:
 * a file-private top-level class still compiles to a package-level JVM class, so two test files
 * each declaring their own `private class GatedRestoreEngine` produced one class and a
 * `NoSuchMethodError` in whichever test compiled second.
 */

internal object UnconfinedDispatchers : IDispatchers {
    override val default = Dispatchers.Unconfined
    override val io = Dispatchers.Unconfined
    override val main = Dispatchers.Unconfined
    override val mainImmediate = Dispatchers.Unconfined
}

/** The OS refused the foreground service, so the coordinator's in-app fallback takes the run. */
internal object NoForegroundRestoreLauncher : IForegroundRestoreLauncher {
    override fun start(): Boolean = false
}

/**
 * Suspends both restore calls on [decryptGate] and the folder scan on [scanGate] (open by default),
 * so a test can assert on the intermediate state before letting each step finish. [decryptResult]
 * is what both restore calls answer once released.
 */
internal class GatedRestoreEngine(
    private val decryptGate: CompletableDeferred<Unit>,
    private val scanGate: CompletableDeferred<Unit> = CompletableDeferred(Unit),
) : IRestoreEngine {

    var decryptResult: RestoreResult = RestoreResult.Success(decrypted = 1, copied = 0, skipped = 0, failed = 0)

    override suspend fun scanSourceFolder(sourceUri: String): RestoreScanResult {
        scanGate.await()
        return RestoreScanResult(cryptFileCount = 1, otherFileCount = 0)
    }

    @Suppress("LongParameterList")
    override suspend fun decryptAll(
        sourceUri: String,
        outputUri: String,
        password: String,
        collisionPolicy: RestoreCollisionPolicy,
        runControl: RestoreRunControl,
        onProgress: (RestoreProgress) -> Unit,
    ): RestoreResult {
        decryptGate.await()
        return decryptResult
    }

    @Suppress("LongParameterList")
    override suspend fun decryptSingleFile(
        sourceFileUri: String,
        outputFileUri: String,
        password: String,
        runControl: RestoreRunControl,
        onProgress: (RestoreProgress) -> Unit,
    ): RestoreResult {
        decryptGate.await()
        return decryptResult
    }
}
