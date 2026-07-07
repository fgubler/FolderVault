package ch.abwesend.foldervault.infrastructure.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import ch.abwesend.foldervault.domain.model.CloudAccountRoot
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import java.io.File
import java.nio.file.Files

/**
 * Runs against a real preferences DataStore backed by a temp file — no Android framework needed
 * thanks to the [DataStore]-taking constructor of [AppSettingsRepository].
 */
class AppSettingsRepositoryTest : StringSpec({

    val legacyIdKey = stringPreferencesKey("cloud_root_folder_id")
    val legacyNameKey = stringPreferencesKey("cloud_root_folder_name")
    val legacyAccountKey = stringPreferencesKey("cloud_root_account_identifier")
    val cloudRootsJsonKey = stringPreferencesKey("cloud_roots_json")

    val scopes = mutableListOf<CoroutineScope>()
    afterTest {
        scopes.forEach { it.cancel() }
        scopes.clear()
    }

    // The system temp dir is not writable in every environment (e.g. the sandboxed build) —
    // stay inside the module's build dir instead.
    val tempRoot = File("build/tmp/appSettingsRepositoryTest").apply { mkdirs() }

    fun newDataStore(): DataStore<Preferences> {
        val dir = Files.createTempDirectory(tempRoot.toPath(), "app-settings-test").toFile()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes += scope
        return PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "app_settings.preferences_pb")
        }
    }

    val root1 = CloudAccountRoot(
        accountIdentifier = "one@test.com",
        rootFolderId = "root-1",
        rootFolderName = "FolderVault_one",
    )
    val root2 = CloudAccountRoot(
        accountIdentifier = "two@test.com",
        rootFolderId = "root-2",
        rootFolderName = "FolderVault_two",
    )

    "cloudRoots survive a write/read round-trip" {
        val repo = AppSettingsRepository(newDataStore())

        repo.update { it.copy(cloudRoots = listOf(root1, root2)) }

        repo.settings.first().cloudRoots shouldBe listOf(root1, root2)
    }

    "legacy single-root keys migrate to a one-entry list on read" {
        val dataStore = newDataStore()
        dataStore.edit {
            it[legacyIdKey] = "legacy-root"
            it[legacyNameKey] = "FolderVault_legacy"
            it[legacyAccountKey] = "legacy@test.com"
        }
        val repo = AppSettingsRepository(dataStore)

        repo.settings.first().cloudRoots shouldBe listOf(
            CloudAccountRoot(
                accountIdentifier = "legacy@test.com",
                rootFolderId = "legacy-root",
                rootFolderName = "FolderVault_legacy",
            ),
        )
    }

    "incomplete legacy keys are ignored (no account to key the root by)" {
        val dataStore = newDataStore()
        dataStore.edit {
            it[legacyIdKey] = "legacy-root"
            it[legacyNameKey] = "FolderVault_legacy"
        }
        val repo = AppSettingsRepository(dataStore)

        repo.settings.first().cloudRoots shouldBe emptyList()
    }

    "first write after migration persists the JSON key and removes the legacy keys" {
        val dataStore = newDataStore()
        dataStore.edit {
            it[legacyIdKey] = "legacy-root"
            it[legacyNameKey] = "FolderVault_legacy"
            it[legacyAccountKey] = "legacy@test.com"
        }
        val repo = AppSettingsRepository(dataStore)

        repo.update { it }

        val prefs = dataStore.data.first()
        prefs[legacyIdKey] shouldBe null
        prefs[legacyNameKey] shouldBe null
        prefs[legacyAccountKey] shouldBe null
        prefs.contains(cloudRootsJsonKey) shouldBe true
        repo.settings.first().cloudRoots shouldBe listOf(
            CloudAccountRoot(
                accountIdentifier = "legacy@test.com",
                rootFolderId = "legacy-root",
                rootFolderName = "FolderVault_legacy",
            ),
        )
    }

    "unparseable cloud-roots JSON falls back to an empty list" {
        val dataStore = newDataStore()
        dataStore.edit { it[cloudRootsJsonKey] = "not-json" }
        val repo = AppSettingsRepository(dataStore)

        repo.settings.first().cloudRoots shouldBe emptyList()
    }
})
