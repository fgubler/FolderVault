package ch.abwesend.foldervault.infrastructure.backup

/** Why a foreground-service run was cooperatively stopped before draining its queue. */
enum class ForegroundStopReason {
    /** The user tapped the notification's Stop action. */
    USER_REQUESTED,

    /** The network stopped satisfying the run's policy (e.g. Wi-Fi-only lost Wi-Fi). */
    NETWORK_POLICY_VIOLATED,

    /** The OS signalled the dataSync foreground-service time limit (Android 15+). */
    OS_TIMEOUT,
}

/**
 * Decides whether a foreground-service run that stopped with work remaining hands over to a
 * WorkManager continuation. Extracted from [BackupForegroundService] so the decision is
 * unit-testable without the service.
 */
internal object ForegroundHandoverPolicy {

    /**
     * A `null` [reason] means the run hit its own time budget — continue in the background,
     * like any worker continuation. Network loss and OS timeout also continue: WorkManager's
     * constraints make the continuation wait for a suitable network / execution window. Only an
     * explicit user stop suppresses the continuation — the periodic schedule (or a manual
     * "back up now") resumes the sync later instead.
     */
    fun shouldScheduleContinuation(reason: ForegroundStopReason?): Boolean =
        reason != ForegroundStopReason.USER_REQUESTED
}
