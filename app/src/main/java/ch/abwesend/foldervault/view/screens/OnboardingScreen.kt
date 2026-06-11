package ch.abwesend.foldervault.view.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ch.abwesend.foldervault.ui.theme.FolderVaultTheme
import ch.abwesend.foldervault.view.viewmodel.OnboardingViewModel
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

private data class OnboardingPage(
    val icon: ImageVector,
    val title: String,
    val body: String,
)

private val PAGES = listOf(
    OnboardingPage(
        icon = Icons.Default.Cloud,
        title = "Incremental folder backup",
        body = "FolderVault backs up a folder on your device to Google Drive, " +
            "automatically and in the background.",
    ),
    OnboardingPage(
        icon = Icons.Default.FolderOpen,
        title = "Best for files that rarely change",
        body = "Photos, scans, and document archives are ideal. " +
            "FolderVault is not designed for frequently-changing databases or app data.",
    ),
    OnboardingPage(
        icon = Icons.Default.SyncAlt,
        title = "Only new or changed files",
        body = "Each run uploads only files that are new or have changed. " +
            "Unchanged files are never re-sent — saving time and data.",
    ),
    OnboardingPage(
        icon = Icons.Default.Warning,
        title = "Honest limitations",
        body = "One-way push only — recovery means downloading from Drive " +
            "and decrypting in-app. Changed files create timestamped copies. " +
            "Re-installing the app means re-uploading everything from scratch. " +
            "Nothing is ever deleted from Drive automatically.",
    ),
    OnboardingPage(
        icon = Icons.Default.Lock,
        title = "Your files stay private",
        body = "Enable AES-256-GCM encryption and only encrypted bytes ever " +
            "leave your device. If you forget the password, the backup cannot " +
            "be recovered — there is no reset.",
    ),
    OnboardingPage(
        icon = Icons.Default.Notifications,
        title = "Stay informed",
        body = "Allow notifications so FolderVault can alert you if a backup " +
            "stops working — without you having to open the app.",
    ),
)

@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    viewModel: OnboardingViewModel = koinViewModel(),
) {
    val pagerState = rememberPagerState { PAGES.size }
    val scope = rememberCoroutineScope()
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ ->
        // graceful regardless of outcome
    }

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) { page ->
            OnboardingPageCard(page = PAGES[page])
        }

        PageIndicator(
            count = PAGES.size,
            current = pagerState.currentPage,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
        )

        OnboardingNavButtons(
            isLastPage = pagerState.currentPage == PAGES.lastIndex,
            onSkip = {
                viewModel.markOnboardingComplete()
                onComplete()
            },
            onNext = {
                if (pagerState.currentPage < PAGES.lastIndex) {
                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    viewModel.markOnboardingComplete()
                    onComplete()
                }
            },
        )
    }
}

@Composable
private fun OnboardingPageCard(page: OnboardingPage) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = page.icon,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = page.title,
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = page.body,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PageIndicator(count: Int, current: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
    ) {
        repeat(count) { index ->
            val color = if (index == current) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            }
            Surface(
                shape = CircleShape,
                color = color,
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(if (index == current) 10.dp else 8.dp),
            ) {}
        }
    }
}

@Composable
private fun OnboardingNavButtons(
    isLastPage: Boolean,
    onSkip: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        OutlinedButton(onClick = onSkip) { Text("Skip") }
        Button(onClick = onNext) {
            Text(if (isLastPage) "Get started" else "Next")
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun OnboardingScreenPreview() {
    FolderVaultTheme {
        OnboardingPageCard(page = PAGES[0])
    }
}
