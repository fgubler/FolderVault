package ch.abwesend.foldervault.view.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import ch.abwesend.foldervault.view.screens.DatabaseErrorScreen
import ch.abwesend.foldervault.view.viewmodel.DatabaseGuardState
import ch.abwesend.foldervault.view.viewmodel.DatabaseGuardViewModel
import org.koin.androidx.compose.koinViewModel

/**
 * Gate in front of [AppNavGraph]: the normal UI only renders once the local database verifiably
 * opens. While the check runs, an empty surface is shown (the check takes a few milliseconds);
 * if it fails, [DatabaseErrorScreen] takes over with recovery options instead of the app
 * crashing on the first database query.
 */
@Composable
fun DatabaseGuard(
    startDestination: AppDestination,
    modifier: Modifier = Modifier,
) {
    val viewModel: DatabaseGuardViewModel = koinViewModel()
    val state by viewModel.state.collectAsState()

    when (state) {
        is DatabaseGuardState.Checking -> Box(modifier = modifier.fillMaxSize())
        is DatabaseGuardState.Healthy -> AppNavGraph(startDestination = startDestination)
        is DatabaseGuardState.Error -> DatabaseErrorScreen(viewModel = viewModel, modifier = modifier)
    }
}
