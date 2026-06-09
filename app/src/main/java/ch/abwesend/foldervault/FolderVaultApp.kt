package ch.abwesend.foldervault

import android.app.Application
import ch.abwesend.foldervault.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

class FolderVaultApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidLogger(Level.ERROR)
            androidContext(this@FolderVaultApp)
            modules(appModule)
        }
    }
}
