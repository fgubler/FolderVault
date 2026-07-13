package ch.abwesend.foldervault.domain.backup

import ch.abwesend.foldervault.domain.model.NetworkPolicy

/**
 * Starts a backup run in the foreground service (long run window, ongoing progress
 * notification) instead of scheduling it through WorkManager. Used for the initial upload,
 * which would otherwise be chopped into many short WorkManager windows.
 *
 * Must only be invoked while the app is in the foreground (a user-visible action): Android 12+
 * forbids starting a foreground service from the background.
 */
interface IForegroundBackupLauncher {
    /**
     * Starts the foreground-service run for [configId]. [networkPolicy] is the *effective*
     * policy after any user override prompts (e.g. "run on mobile data anyway"), and
     * [requiresCharging] the effective charging requirement — both are also reused for the
     * WorkManager continuation if the service has to hand the run over.
     */
    fun start(configId: String, networkPolicy: NetworkPolicy, requiresCharging: Boolean)
}
