package ch.abwesend.foldervault.infrastructure.backup

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import ch.abwesend.foldervault.domain.model.AppSettings
import ch.abwesend.foldervault.domain.model.MessageType
import ch.abwesend.foldervault.domain.settings.IAppSettingsRepository
import ch.abwesend.foldervault.infrastructure.room.dao.BackupMessageDao
import ch.abwesend.foldervault.infrastructure.room.dao.NotificationThrottleStateDao
import ch.abwesend.foldervault.infrastructure.room.entity.NotificationThrottleStateEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Pins the scope of the per-run problem notification (spec §8.3: "if *that run* produced any
 * notifying messages"). The regression it guards: a still-undismissed `UPLOAD_FAILED` warning
 * from an earlier run kept re-triggering "Backup 'X' had issues: upload failed" after every
 * later, perfectly clean run — once per 24 h throttle window — although nothing had failed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ProblemNotificationScopeTest {

    private val configId = "config-1"
    private val thisRun = "run-2"

    private lateinit var context: Context
    private lateinit var messageDao: BackupMessageDao
    private lateinit var throttleDao: NotificationThrottleStateDao
    private lateinit var manager: BackupNotificationManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        messageDao = mockk(relaxed = true)
        throttleDao = mockk(relaxed = true)
        val settingsRepository = mockk<IAppSettingsRepository> {
            every { settings } returns flowOf(AppSettings())
        }
        coEvery { throttleDao.getState(any(), any()) } returns null
        coEvery { messageDao.getCountForRunAndType(any(), any(), any()) } returns 0
        manager = BackupNotificationManager(context, throttleDao, messageDao, settingsRepository)
        manager.createNotificationChannels()
    }

    @Test
    fun `a clean run stays silent even while an older run's warning is still undismissed`() = runTest {
        // The config-wide count still sees the stale warning of an earlier run...
        coEvery { messageDao.getCountForType(configId, MessageType.UPLOAD_FAILED) } returns 1
        // ...but this run produced nothing.
        coEvery { messageDao.getCountForRunAndType(thisRun, configId, MessageType.UPLOAD_FAILED) } returns 0

        manager.postProblemNotificationIfNeeded(configId, "Photos", thisRun)

        assertEquals(0, postedNotifications())
        coVerify(exactly = 0) { throttleDao.upsert(any()) }
    }

    @Test
    fun `a run that produced a notifying message posts the problem notification`() = runTest {
        coEvery { messageDao.getCountForRunAndType(thisRun, configId, MessageType.UPLOAD_FAILED) } returns 3

        manager.postProblemNotificationIfNeeded(configId, "Photos", thisRun)

        assertEquals(1, postedNotifications())
        coVerify(exactly = 1) {
            throttleDao.upsert(
                match {
                    it.backupConfigId == configId &&
                        it.messageType == MessageType.UPLOAD_FAILED &&
                        it.lastRunId == thisRun
                }
            )
        }
    }

    @Test
    fun `a message of this run is still throttled by a recent notification of the same type`() = runTest {
        coEvery { messageDao.getCountForRunAndType(thisRun, configId, MessageType.UPLOAD_FAILED) } returns 1
        coEvery { throttleDao.getState(configId, MessageType.UPLOAD_FAILED) } returns NotificationThrottleStateEntity(
            backupConfigId = configId,
            messageType = MessageType.UPLOAD_FAILED,
            lastNotifiedAt = System.currentTimeMillis(),
            lastRunId = "run-1",
        )

        manager.postProblemNotificationIfNeeded(configId, "Photos", thisRun)

        assertEquals(0, postedNotifications())
    }

    private fun postedNotifications(): Int {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return shadowOf(nm).size()
    }
}
