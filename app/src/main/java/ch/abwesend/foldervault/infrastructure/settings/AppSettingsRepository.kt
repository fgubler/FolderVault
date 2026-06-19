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
import ch.abwesend.foldervault.domain.settings.IAppSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

private inline fun <reified E : Enum<E>> Preferences.enum(key: Preferences.Key<String>, default: E): E =
    this[key]?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: default

private fun <E : Enum<E>> MutablePreferences.setEnum(key: Preferences.Key<String>, value: E) {
    this[key] = value.name
}

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
            defaultSchedule = enum(Keys.DEFAULT_SCHEDULE, d.defaultSchedule),
            defaultChangedFilePolicy = enum(Keys.DEFAULT_CHANGED_FILE_POLICY, d.defaultChangedFilePolicy),
            defaultFileSizeLimitBytes = this[Keys.DEFAULT_FILE_SIZE_LIMIT_BYTES] ?: d.defaultFileSizeLimitBytes,
            theme = enum(Keys.THEME, d.theme),
            showOnboarding = this[Keys.SHOW_ONBOARDING] ?: d.showOnboarding,
            defaultNetworkPolicy = enum(Keys.DEFAULT_NETWORK_POLICY, d.defaultNetworkPolicy),
            anonymousErrorReports = this[Keys.ANONYMOUS_ERROR_REPORTS] ?: d.anonymousErrorReports,
            cloudRootFolderId = this[Keys.CLOUD_ROOT_FOLDER_ID],
            cloudRootFolderName = this[Keys.CLOUD_ROOT_FOLDER_NAME],
            cloudRootAccountIdentifier = this[Keys.CLOUD_ROOT_ACCOUNT_IDENTIFIER],
        )
    }

    private fun MutablePreferences.applyAppSettings(s: AppSettings) {
        setEnum(Keys.DEFAULT_SCHEDULE, s.defaultSchedule)
        setEnum(Keys.DEFAULT_CHANGED_FILE_POLICY, s.defaultChangedFilePolicy)
        set(Keys.DEFAULT_FILE_SIZE_LIMIT_BYTES, s.defaultFileSizeLimitBytes)
        setEnum(Keys.THEME, s.theme)
        set(Keys.SHOW_ONBOARDING, s.showOnboarding)
        setEnum(Keys.DEFAULT_NETWORK_POLICY, s.defaultNetworkPolicy)
        set(Keys.ANONYMOUS_ERROR_REPORTS, s.anonymousErrorReports)
        setOrRemove(Keys.CLOUD_ROOT_FOLDER_ID, s.cloudRootFolderId)
        setOrRemove(Keys.CLOUD_ROOT_FOLDER_NAME, s.cloudRootFolderName)
        setOrRemove(Keys.CLOUD_ROOT_ACCOUNT_IDENTIFIER, s.cloudRootAccountIdentifier)
    }

    private fun MutablePreferences.setOrRemove(key: Preferences.Key<String>, value: String?) {
        if (value == null) remove(key) else set(key, value)
    }

    private object Keys {
        val DEFAULT_SCHEDULE = stringPreferencesKey("default_schedule")
        val DEFAULT_CHANGED_FILE_POLICY = stringPreferencesKey("default_changed_file_policy")
        val DEFAULT_FILE_SIZE_LIMIT_BYTES = longPreferencesKey("default_file_size_limit_bytes")
        val THEME = stringPreferencesKey("theme")
        val SHOW_ONBOARDING = booleanPreferencesKey("show_onboarding")
        val DEFAULT_NETWORK_POLICY = stringPreferencesKey("default_network_policy")
        val ANONYMOUS_ERROR_REPORTS = booleanPreferencesKey("anonymous_error_reports")
        val CLOUD_ROOT_FOLDER_ID = stringPreferencesKey("cloud_root_folder_id")
        val CLOUD_ROOT_FOLDER_NAME = stringPreferencesKey("cloud_root_folder_name")
        val CLOUD_ROOT_ACCOUNT_IDENTIFIER = stringPreferencesKey("cloud_root_account_identifier")
    }
}
