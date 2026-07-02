package ch.abwesend.foldervault.view.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import ch.abwesend.foldervault.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private const val MS_PER_MINUTE = 60_000L
private const val MS_PER_HOUR = 60L * MS_PER_MINUTE
private const val MS_PER_DAY = 24L * MS_PER_HOUR
private const val MINUTES_THRESHOLD_MS = 5L * MS_PER_HOUR
private const val HOURS_THRESHOLD_MS = 3L * MS_PER_DAY

/**
 * Formats a past timestamp relative to now using tiered granularity:
 * - under 1 minute: "just now"
 * - under 5 hours: "Nm ago"
 * - under 3 days: "Nh ago"
 * - otherwise: absolute localized date
 */
@Composable
fun formatRelativeAgo(pastEpochMillis: Long): String {
    val elapsedMs = System.currentTimeMillis() - pastEpochMillis
    return when {
        elapsedMs < MS_PER_MINUTE -> stringResource(R.string.time_ago_just_now)
        elapsedMs < MINUTES_THRESHOLD_MS ->
            stringResource(R.string.time_ago_minutes, (elapsedMs / MS_PER_MINUTE).toInt())
        elapsedMs < HOURS_THRESHOLD_MS ->
            stringResource(R.string.time_ago_hours, (elapsedMs / MS_PER_HOUR).toInt())
        else -> {
            val locale = LocalConfiguration.current.locales[0]
            val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
            formatter.format(Instant.ofEpochMilli(pastEpochMillis).atZone(ZoneId.systemDefault()))
        }
    }
}
