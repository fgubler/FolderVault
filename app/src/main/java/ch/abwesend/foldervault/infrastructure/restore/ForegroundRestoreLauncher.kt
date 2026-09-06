package ch.abwesend.foldervault.infrastructure.restore

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import ch.abwesend.foldervault.domain.logging.logger
import ch.abwesend.foldervault.domain.restore.IForegroundRestoreLauncher
import kotlin.coroutines.cancellation.CancellationException

/**
 * Starts [RestoreForegroundService]. Every call site is a button on the visible restore screen —
 * "Start restore" for a folder, "Decrypt & save" for a single file — so the foreground-service
 * start is normally allowed; a refusal (most plausibly the dataSync time budget shared with the
 * backup service) is reported back rather than thrown, so the caller can run the restore itself
 * and warn the user to keep the app open.
 *
 * Note that a successful `startForegroundService` only means the service was *dispatched* — the
 * service's own `startForeground` can still be refused, which it handles by leaving the staged run
 * for the caller. The two paths are made safe by `RestoreRunCoordinator`'s claim handshake
 * (`tryClaim` / `releaseClaim` / `runClaimed`), which lets only one host win.
 */
class ForegroundRestoreLauncher(private val context: Context) : IForegroundRestoreLauncher {
    private val log get() = logger

    override fun start(): Boolean = try {
        ContextCompat.startForegroundService(context, Intent(context, RestoreForegroundService::class.java))
        log.info("Started the foreground restore service")
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warning("Could not start the foreground restore service — restoring in-app instead", e)
        false
    }
}
