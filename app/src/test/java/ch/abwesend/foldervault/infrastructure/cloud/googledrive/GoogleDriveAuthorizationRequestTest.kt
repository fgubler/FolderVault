package ch.abwesend.foldervault.infrastructure.cloud.googledrive

import android.app.Application
import com.google.api.services.drive.DriveScopes
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Robolectric because [android.accounts.Account] touches the framework in its constructor —
 * runs only outside the Bash sandbox (`! ./gradlew test`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class GoogleDriveAuthorizationRequestTest {

    @Test
    fun requestWithoutAccountLeavesAccountUnset() {
        val request = buildAuthorizationRequest(null)

        assertNull(request.account)
    }

    @Test
    fun requestWithAccountTargetsTheGoogleAccount() {
        val request = buildAuthorizationRequest("user@test.com")

        assertEquals("user@test.com", request.account?.name)
        assertEquals(GOOGLE_ACCOUNT_TYPE, request.account?.type)
    }

    @Test
    fun requestAlwaysCarriesTheDriveFileAndEmailScopes() {
        val request = buildAuthorizationRequest("user@test.com")

        val scopeUris = request.requestedScopes.map { it.scopeUri }
        assertTrue(scopeUris.contains(DriveScopes.DRIVE_FILE))
        assertTrue(scopeUris.contains("email"))
    }
}
