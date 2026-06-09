package ch.abwesend.foldervault.view.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import ch.abwesend.foldervault.ui.theme.FolderVaultTheme

@Composable
fun OnboardingScreen(onComplete: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = "Onboarding carousel — coming in slice #10")
    }
}

@Preview(showBackground = true)
@Composable
private fun OnboardingScreenPreview() {
    FolderVaultTheme { OnboardingScreen(onComplete = {}) }
}
