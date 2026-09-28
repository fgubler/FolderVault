package ch.abwesend.foldervault.view

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import ch.abwesend.foldervault.domain.restore.RestoreMode
import ch.abwesend.foldervault.domain.restore.RestoreResult
import ch.abwesend.foldervault.ui.theme.FolderVaultTheme
import ch.abwesend.foldervault.view.screens.RestoreSuccessScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RestoreSuccessScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val calls = mutableListOf<String>()

    @Test
    fun `a decrypted single file gets the one-file sentence and the file-flavoured buttons`() {
        setScreen(RestoreMode.SINGLE_FILE, RestoreResult.Success(decrypted = 1, copied = 0, skipped = 0, failed = 0))

        composeTestRule.onNodeWithText("Restore complete").assertIsDisplayed()
        composeTestRule.onNodeWithText("The file was decrypted and saved.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Restore another file").assertIsDisplayed()
        composeTestRule.onNodeWithText("Done").assertIsDisplayed()
    }

    @Test
    fun `a plain single file saved as a copy says so instead of counting zero decrypted files`() {
        setScreen(RestoreMode.SINGLE_FILE, RestoreResult.Success(decrypted = 0, copied = 1, skipped = 0, failed = 0))

        composeTestRule
            .onNodeWithText("The file was not encrypted, so it was saved as an unchanged copy.")
            .assertIsDisplayed()
    }

    @Test
    fun `a folder restore lists every non-zero counter`() {
        setScreen(RestoreMode.WHOLE_FOLDER, RestoreResult.Success(decrypted = 3, copied = 1, skipped = 2, failed = 1))

        composeTestRule
            .onNodeWithText("Restored 3 encrypted file(s), copied 1 plain file(s), skipped 2, 1 failed.")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Restore another folder").assertIsDisplayed()
    }

    @Test
    fun `the two buttons drive the two callbacks`() {
        setScreen(RestoreMode.WHOLE_FOLDER, RestoreResult.Success(decrypted = 1, copied = 0, skipped = 0, failed = 0))

        composeTestRule.onNodeWithText("Restore another folder").performClick()
        composeTestRule.onNodeWithText("Done").performClick()

        assertEquals(listOf("another", "done"), calls)
    }

    private fun setScreen(mode: RestoreMode, result: RestoreResult.Success) {
        composeTestRule.setContent {
            FolderVaultTheme {
                RestoreSuccessScreen(
                    mode = mode,
                    result = result,
                    onRestoreAnother = { calls += "another" },
                    onDone = { calls += "done" },
                )
            }
        }
    }
}
