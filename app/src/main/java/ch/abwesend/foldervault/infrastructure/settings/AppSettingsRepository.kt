package ch.abwesend.foldervault.infrastructure.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import ch.abwesend.foldervault.domain.model.AppSettings
import ch.abwesend.foldervault.domain.model.AppTheme
import ch.abwesend.foldervault.domain.model.BackupSchedule
import ch.abwesend.foldervault.domain.model.ChangedFilePolicy
import ch.abwesend.foldervault.domain.model.NetworkPolicy
import ch.abwesend.foldervault.domain.settings.IAppSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

class AppSettingsRepository(private val context: Context) : IAppSettingsRepository {

    override val settings: Flow<AppSettings> = context.dataStore.data.map { it.toAppSettings() }

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val updated = transform(prefs.toAppSettings())
            prefs.applyAppSettings(updated)
        }
    }

    private fun Preferences.toAppSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            defaultSchedule = this[Keys.DEFAULT_SCHEDULE]
                ?.let { runCatching { BackupSchedule.valueOf(it) }.getOrNull() }
                ?: d.defaultSchedule,
            defaultChangedFilePolicy = this[Keys.DEFAULT_CHANGED_FILE_POLICY]
                ?.let { runCatching { ChangedFilePolicy.valueOf(it) }.getOrNull() }
                ?: d.defaultChangedFilePolicy,
            defaultFileSizeLimitBytes = this[Keys.DEFAULT_FILE_SIZE_LIMIT_BYTES]
                ?: d.defaultFileSizeLimitBytes,
            theme = this[Keys.THEME]
                ?.let { runCatching { AppTheme.valueOf(it) }.getOrNull() }
                ?: d.theme,
            showOnboarding = this[Keys.SHOW_ONBOARDING] ?: d.showOnboarding,
            defaultNetworkPolicy = this[Keys.DEFAULT_NETWORK_POLICY]
                ?.let { runCatching { NetworkPolicy.valueOf(it) }.getOrNull() }
                ?: d.defaultNetworkPolicy,
            anonymousErrorReports = this[Keys.ANONYMOUS_ERROR_REPORTS] ?: d.anonymousErrorReports,
        )
    }

    private fun MutablePreferences.applyAppSettings(s: AppSettings) {
        set(Keys.DEFAULT_SCHEDULE, s.defaultSchedule.name)
        set(Keys.DEFAULT_CHANGED_FILE_POLICY, s.defaultChangedFilePolicy.name)
        set(Keys.DEFAULT_FILE_SIZE_LIMIT_BYTES, s.defaultFileSizeLimitBytes)
        set(Keys.THEME, s.theme.name)
        set(Keys.SHOW_ONBOARDING, s.showOnboarding)
        set(Keys.DEFAULT_NETWORK_POLICY, s.defaultNetworkPolicy.name)
        set(Keys.ANONYMOUS_ERROR_REPORTS, s.anonymousErrorReports)
    }

    private object Keys {
        val DEFAULT_SCHEDULE = stringPreferencesKey("default_schedule")
        val DEFAULT_CHANGED_FILE_POLICY = stringPreferencesKey("default_changed_file_policy")
        val DEFAULT_FILE_SIZE_LIMIT_BYTES = longPreferencesKey("default_file_size_limit_bytes")
        val THEME = stringPreferencesKey("theme")
        val SHOW_ONBOARDING = booleanPreferencesKey("show_onboarding")
        val DEFAULT_NETWORK_POLICY = stringPreferencesKey("default_network_policy")
        val ANONYMOUS_ERROR_REPORTS = booleanPreferencesKey("anonymous_error_reports")
    }
}
