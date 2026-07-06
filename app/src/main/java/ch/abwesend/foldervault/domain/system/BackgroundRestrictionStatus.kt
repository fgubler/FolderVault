package ch.abwesend.foldervault.domain.system

/**
 * Snapshot of the OS restrictions reported by [IBackgroundRestrictionChecker], as shown in the
 * settings screen. The defaults represent the state before the first refresh.
 */
data class BackgroundRestrictionStatus(
    val ignoringBatteryOptimizations: Boolean = false,
    val backgroundDataRestricted: Boolean = false,
)
