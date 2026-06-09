package ch.abwesend.foldervault

import android.app.Application
import ch.abwesend.foldervault.di.appModule
import ch.abwesend.foldervault.domain.logging.LoggerProvider
import ch.abwesend.foldervault.infrastructure.logging.CrashlyticsSink
import ch.abwesend.foldervault.infrastructure.logging.LocalLogSink
import ch.abwesend.foldervault.infrastructure.logging.PrivateLogger
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
    }

    private fun configureLogging() {
        val local = LocalLogSink()
        val remote = CrashlyticsSink()
        LoggerProvider.configure { tag -> PrivateLogger(tag, local, remote) }
    }
}
