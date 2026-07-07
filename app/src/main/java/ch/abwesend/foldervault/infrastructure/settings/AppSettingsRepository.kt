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
import ch.abwesend.foldervault.domain.logging.logger
import ch.abwesend.foldervault.domain.model.AppSettings
import ch.abwesend.foldervault.domain.model.CloudAccountRoot
import ch.abwesend.foldervault.domain.settings.IAppSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

private fun <E : Enum<E>> MutablePreferences.setEnum(key: Preferences.Key<String>, value: E) {
    this[key] = value.name
}

class AppSettingsRepository(private val dataStore: DataStore<Preferences>) : IAppSettingsRepository {

    /** Production entry point — backed by the `app_settings` preferences file. */
    constructor(context: Context) : this(context.dataStore)

    private inline fun <reified E : Enum<E>> Preferences.enum(key: Preferences.Key<String>, default: E): E {
        val raw = this[key] ?: return default
        return try {
            enumValueOf<E>(raw)
        } catch (e: IllegalArgumentException) {
            val typeName = E::class.simpleName
            this@AppSettingsRepository.logger.warning(
                "Stored $typeName value '$raw' for key ${key.name} could not be parsed; falling back to $default",
                e,
            )
            default
        }
    }

    override val settings: Flow<AppSettings> = dataStore.data.map { it.toAppSettings() }

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        dataStore.edit { prefs ->
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
            cloudRoots = readCloudRoots(),
        )
    }

    /**
     * Reads the per-account backup roots from [Keys.CLOUD_ROOTS_JSON].
     *
     * Migration-on-read: installs older than the per-account-root feature stored a single root in
     * the three legacy `cloud_root_*` keys. When the JSON key is absent but all three legacy keys
     * are present, they are synthesized into a one-entry list. The legacy keys are only ever read
     * here; [applyAppSettings] writes the JSON key and removes them.
     */
    private fun Preferences.readCloudRoots(): List<CloudAccountRoot> {
        val json = this[Keys.CLOUD_ROOTS_JSON]
        return if (json != null) {
            try {
                Json.decodeFromString<List<CloudAccountRoot>>(json)
            } catch (e: SerializationException) {
                this@AppSettingsRepository.logger.warning(
                    "Stored cloud roots could not be parsed; falling back to empty list",
                    e,
                )
                emptyList()
            }
        } else {
            readLegacyCloudRoot()?.let { listOf(it) } ?: emptyList()
        }
    }

    private fun Preferences.readLegacyCloudRoot(): CloudAccountRoot? {
        val legacyId = this[LegacyKeys.CLOUD_ROOT_FOLDER_ID]
        val legacyName = this[LegacyKeys.CLOUD_ROOT_FOLDER_NAME]
        val legacyAccount = this[LegacyKeys.CLOUD_ROOT_ACCOUNT_IDENTIFIER]
        return if (legacyId != null && legacyName != null && legacyAccount != null) {
            CloudAccountRoot(
                accountIdentifier = legacyAccount,
                rootFolderId = legacyId,
                rootFolderName = legacyName,
            )
        } else {
            null
        }
    }

    private fun MutablePreferences.applyAppSettings(s: AppSettings) {
        setEnum(Keys.DEFAULT_SCHEDULE, s.defaultSchedule)
        setEnum(Keys.DEFAULT_CHANGED_FILE_POLICY, s.defaultChangedFilePolicy)
        set(Keys.DEFAULT_FILE_SIZE_LIMIT_BYTES, s.defaultFileSizeLimitBytes)
        setEnum(Keys.THEME, s.theme)
        set(Keys.SHOW_ONBOARDING, s.showOnboarding)
        setEnum(Keys.DEFAULT_NETWORK_POLICY, s.defaultNetworkPolicy)
        set(Keys.ANONYMOUS_ERROR_REPORTS, s.anonymousErrorReports)
        set(Keys.CLOUD_ROOTS_JSON, Json.encodeToString(s.cloudRoots))
        // The legacy single-root keys were folded into CLOUD_ROOTS_JSON on read — drop them so
        // they don't linger forever.
        remove(LegacyKeys.CLOUD_ROOT_FOLDER_ID)
        remove(LegacyKeys.CLOUD_ROOT_FOLDER_NAME)
        remove(LegacyKeys.CLOUD_ROOT_ACCOUNT_IDENTIFIER)
    }

    private object Keys {
        val DEFAULT_SCHEDULE = stringPreferencesKey("default_schedule")
        val DEFAULT_CHANGED_FILE_POLICY = stringPreferencesKey("default_changed_file_policy")
        val DEFAULT_FILE_SIZE_LIMIT_BYTES = longPreferencesKey("default_file_size_limit_bytes")
        val THEME = stringPreferencesKey("theme")
        val SHOW_ONBOARDING = booleanPreferencesKey("show_onboarding")
        val DEFAULT_NETWORK_POLICY = stringPreferencesKey("default_network_policy")
        val ANONYMOUS_ERROR_REPORTS = booleanPreferencesKey("anonymous_error_reports")
        val CLOUD_ROOTS_JSON = stringPreferencesKey("cloud_roots_json")
    }

    /** Pre-per-account-root keys, kept only for the migration-on-read in [readCloudRoots]. */
    private object LegacyKeys {
        val CLOUD_ROOT_FOLDER_ID = stringPreferencesKey("cloud_root_folder_id")
        val CLOUD_ROOT_FOLDER_NAME = stringPreferencesKey("cloud_root_folder_name")
        val CLOUD_ROOT_ACCOUNT_IDENTIFIER = stringPreferencesKey("cloud_root_account_identifier")
    }
}
