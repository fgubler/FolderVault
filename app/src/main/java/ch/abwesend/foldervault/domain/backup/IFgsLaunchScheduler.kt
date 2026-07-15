package ch.abwesend.foldervault.domain.backup

import ch.abwesend.foldervault.domain.model.NetworkPolicy

/**
 * Trampoline that lets a *background* run reach the foreground service.
 *
 * This is **not** a scheduler — WorkManager owns all backup timing. Its only job is to work around
 * one platform restriction: Android 12+ forbids starting a foreground service from the background,
 * and a WorkManager worker cannot start one itself. The one documented exception is the callback of
 * an *exact* alarm, so this sets a one-shot exact alarm a few seconds out whose receiver starts the
 * service. The worker calls this (instead of running the backup inline) when a scheduled or
 * continuation run needs the service's long window — see the `BackupWorker` trampoline decision.
 *
 * Setting an exact alarm requires the `SCHEDULE_EXACT_ALARM` permission on API 31+; implementations
 * therefore report whether the launch could be scheduled so the caller can fall back to running the
 * run inline.
 */
interface IFgsLaunchScheduler {
    /**
     * Schedules the near-immediate foreground-service launch for [configId], forwarding the
     * *effective* [networkPolicy] and [requiresCharging] (reused for the WorkManager continuation
     * if the service later hands the run back). Returns `false` when exact alarms are not permitted,
     * in which case the caller must run the backup inline instead.
     */
    fun scheduleImmediateLaunch(
        configId: String,
        networkPolicy: NetworkPolicy,
        requiresCharging: Boolean,
    ): Boolean

    /**
     * Whether an exact alarm can currently be set — `true` on API < 31 (no permission exists) or
     * when `SCHEDULE_EXACT_ALARM` is granted on API 31+. Callers use this to decide up front
     * whether the exact-alarm path is available (see `ExecutionStrategySelector`); it can still go
     * stale, so [scheduleImmediateLaunch] re-checks and reports failure defensively.
     */
    fun isExactAlarmPermitted(): Boolean

    /** Cancels a pending trampoline alarm for [configId] (e.g. when the config is deleted). */
    fun cancel(configId: String)
}
