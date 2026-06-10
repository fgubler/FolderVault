package ch.abwesend.foldervault

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import ch.abwesend.foldervault.ui.theme.FolderVaultTheme
import ch.abwesend.foldervault.view.navigation.AppDestination
import ch.abwesend.foldervault.view.navigation.AppNavGraph

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val startDestination = resolveStartDestination(intent)
        setContent {
            FolderVaultTheme {
                AppNavGraph(startDestination = startDestination)
            }
        }
    }

    private fun resolveStartDestination(intent: Intent?): AppDestination {
        val data = intent?.data ?: return AppDestination.Home
        val isDetailLink = data.scheme == "foldervault" &&
            data.host == "backup" &&
            data.pathSegments.firstOrNull() == "detail"
        if (isDetailLink) {
            val configId = data.pathSegments.getOrNull(1)
            if (!configId.isNullOrBlank()) return AppDestination.BackupDetail(configId)
        }
        return AppDestination.Home
    }
}
