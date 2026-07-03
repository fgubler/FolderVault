package ch.abwesend.foldervault.domain.system

/**
 * Reads OS-level restrictions that can delay or block background backups. The view layer uses
 * this to tell the user whether action in the system settings would make backups more reliable;
 * the app itself can only send the user there — it must not change these settings.
 */
interface IBackgroundRestrictionChecker {
    /**
     * Returns `true` if the user has excluded the app from battery optimization, meaning the OS
     * will not put the app to sleep and scheduled backups start close to their planned time.
     */
    fun isIgnoringBatteryOptimizations(): Boolean

    /**
     * Returns `true` if Data Saver is currently blocking the app's background data on metered
     * networks. While active, backups that are allowed to use mobile data cannot run in the
     * background; Wi-Fi-only backups are unaffected.
     */
    fun isBackgroundDataRestricted(): Boolean
}
