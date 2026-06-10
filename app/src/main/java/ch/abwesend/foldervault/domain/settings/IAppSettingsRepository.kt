package ch.abwesend.foldervault.domain.settings

import ch.abwesend.foldervault.domain.model.AppSettings
import kotlinx.coroutines.flow.Flow

interface IAppSettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun update(transform: (AppSettings) -> AppSettings)
}
