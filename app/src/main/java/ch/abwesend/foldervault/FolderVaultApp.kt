package ch.abwesend.foldervault

import android.app.Application
import ch.abwesend.foldervault.di.appModule
import ch.abwesend.foldervault.domain.logging.ITelemetryToggle
import ch.abwesend.foldervault.domain.logging.LoggerProvider
import ch.abwesend.foldervault.domain.settings.IAppSettingsRepository
import ch.abwesend.foldervault.infrastructure.backup.BackupNotificationManager
import ch.abwesend.foldervault.infrastructure.logging.CrashlyticsSink
import ch.abwesend.foldervault.infrastructure.logging.LocalLogSink
import ch.abwesend.foldervault.infrastructure.logging.PrivateLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

class FolderVaultApp : Application() {
    override fun onCreate() {
        super.onCreate()
        configureLogging()
        startKoin {
            androidLogger(Level.ERROR)
            androidContext(this@FolderVaultApp)
            modules(appModule)
        }
        get<BackupNotificationManager>().createNotificationChannels()
        applyInitialTelemetrySettings()
    }

    private fun configureLogging() {
        val local = LocalLogSink()
        val remote = CrashlyticsSink()
        LoggerProvider.configure { tag -> PrivateLogger(tag, local, remote) }
    }

    private fun applyInitialTelemetrySettings() {
        val toggle = get<ITelemetryToggle>()
        val settingsRepo = get<IAppSettingsRepository>()
        CoroutineScope(Dispatchers.IO).launch {
            toggle.setEnabled(settingsRepo.settings.first().anonymousErrorReports)
        }
    }
}
