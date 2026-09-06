package ch.abwesend.foldervault.infrastructure.restore

import android.app.Application
import android.app.Notification
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import ch.abwesend.foldervault.domain.coroutine.AppDispatchers
import ch.abwesend.foldervault.domain.coroutine.IDispatchers
import ch.abwesend.foldervault.domain.restore.IRestoreRunCoordinator
import ch.abwesend.foldervault.domain.restore.RestoreCollisionPolicy
import ch.abwesend.foldervault.domain.restore.RestoreMode
import ch.abwesend.foldervault.domain.restore.RestoreProgress
import ch.abwesend.foldervault.domain.restore.RestoreRequest
import ch.abwesend.foldervault.domain.restore.RestoreResult
import ch.abwesend.foldervault.domain.restore.RestoreRunState
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Robolectric tests driving the real [RestoreForegroundService] through `onStartCommand`, over a
 * hand-written [FakeRestoreRunCoordinator] whose `runClaimed` can be held open so a test can act
 * while a restore is "in flight".
 *
 * Covers the review findings the service had no coverage for at all:
 * - B7: the Android 15 dataSync time limit calls ONLY the two-argument `onTimeout(startId, fgsType)`.
 *   Without that override the call landed in [android.app.Service]'s empty default and the platform
 *   killed the app with `ForegroundServiceDidNotStopInTimeException`.
 * - S11: a start that cannot claim must not tear the service down while it is running a restore —
 *   the no-argument `stopSelf()` stops the service regardless of outstanding start commands.
 * - B9: a start that has nothing to claim must still call `startForeground` before stopping. Every
 *   start comes through `startForegroundService`, and stopping with that obligation outstanding
 *   crashes the app with `Context.startForegroundService() did not then call
 *   Service.startForeground()`.
 *
 * plus the pre-existing contract: claim before promoting, hand the claim back when the promotion
 * is refused, and stop again when there is nothing staged.
 *
 * No WorkManager here (unlike `BackupForegroundServiceTest`) — a restore has no continuation, and
 * `initializeTestWorkManager` is what drags Room, and therefore SQLite, into that test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RestoreForegroundServiceTest {

    private lateinit var coordinator: FakeRestoreRunCoordinator
    private lateinit var notificationManager: RestoreNotificationManager
    private lateinit var progressNotification: Notification

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        coordinator = FakeRestoreRunCoordinator()
        notificationManager = mockk(relaxed = true)
        progressNotification = NotificationCompat.Builder(context, "test-channel")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .build()
        every { notificationManager.buildProgressNotification(any(), any()) } returns progressNotification

        startKoin {
            modules(
                module {
                    single<IRestoreRunCoordinator> { coordinator }
                    single { notificationManager }
                    single<IDispatchers> { AppDispatchers }
                }
            )
        }
    }

    @After
    fun tearDown() {
        coordinator.releaseRun()
        stopKoin()
    }

    @Test
    fun `a staged restore is claimed, promoted to the foreground and executed`() {
        coordinator.stage()
        // Held open so the assertions see a service that is still hosting its run. Without this the
        // fake's runClaimed returns the moment it has counted the latch down, launchRun's finally
        // calls stopForeground(STOP_FOREGROUND_REMOVE), and Robolectric's ShadowService nulls
        // lastForegroundNotification — a race against the run's dispatcher that this test lost
        // whenever the machine was busy (green alone, red in the full suite). Released in tearDown.
        coordinator.holdRunOpen()
        val service = startedService()

        assertTrue(coordinator.runStarted.await(10, TimeUnit.SECONDS), "the restore should have started")
        assertTrue(coordinator.markedHostedInForegroundService, "the UI must learn the run is service-hosted")
        val shadow = shadowOf(service)
        assertNotNull(shadow.lastForegroundNotification, "the service must promote itself to the foreground")
        assertEquals(
            RestoreNotificationManager.PROGRESS_NOTIFICATION_ID,
            shadow.lastForegroundNotificationId,
        )
    }

    @Test
    fun `the service stops again when there is nothing staged to take over`() {
        // The screen's own fallback already took the run, or the user stopped it before the
        // service got going.
        val service = startedService()

        assertFalse(coordinator.claimed, "there was nothing to claim")
        assertTrue(shadowOf(service).isStoppedBySelf, "an idle restore service must not linger")
    }

    @Test
    fun `a start with nothing to claim still promotes before stopping itself`() {
        // Every start arrives via startForegroundService, which obliges the app to call
        // startForeground even for a start it has nothing to do for: stopping with that obligation
        // outstanding crashes the whole app with "did not then call Service.startForeground()".
        // Asserted through the notification build rather than the shadow's lastForegroundNotification,
        // which stopForeground(STOP_FOREGROUND_REMOVE) has already cleared by the time we look.
        startedService()

        verify(exactly = 1) { notificationManager.buildProgressNotification(any(), any()) }
    }

    @Test
    fun `a refused foreground promotion hands the claim back instead of running unprotected`() {
        // Robolectric cannot raise a real ForegroundServiceStartNotAllowedException, so the
        // promotion is made to fail from inside the same try block — what is asserted is the
        // service's reaction to *any* refusal, which is what enterForeground() catches.
        every { notificationManager.buildProgressNotification(any(), any()) } throws
            IllegalStateException("foreground start not allowed")
        coordinator.stage()

        val service = startedService()

        assertEquals(1, coordinator.releaseClaimCount, "the claim must go back so the screen can run it")
        assertFalse(coordinator.claimed)
        assertFalse(coordinator.runStarted.await(1, TimeUnit.SECONDS), "a background service must not run it")
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `the notification stop action requests a cooperative stop`() {
        coordinator.stage()
        val service = startedService()
        assertTrue(coordinator.runStarted.await(10, TimeUnit.SECONDS))

        service.onStartCommand(stopIntent(), 0, 2)

        assertTrue(coordinator.stopRequested, "the stop must be cooperative, not a cancellation")
    }

    @Test
    fun `the dataSync onTimeout overload stops the run and the service`() {
        // B7: before the fix nothing handled the OS time limit, so the platform killed the app.
        coordinator.stage()
        val service = startedService()
        assertTrue(coordinator.runStarted.await(10, TimeUnit.SECONDS))

        service.onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        assertTrue(coordinator.stopRequested, "the timeout must stop the run cooperatively")
        assertTrue(awaitStoppedBySelf(service), "the service must stop itself before the platform does")
    }

    @Test
    fun `a run that will not drain within the timeout budget still stops the service`() {
        // The budget is the platform's, not ours: an undrainable run is abandoned rather than
        // letting ForegroundServiceDidNotStopInTimeException take the whole app down.
        coordinator.stage()
        coordinator.holdRunOpen()
        val service = startedService()
        assertTrue(coordinator.runStarted.await(10, TimeUnit.SECONDS))

        service.onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        assertTrue(
            awaitStoppedBySelf(service, timeoutMs = 15_000),
            "the service must give up on the drain and stop anyway",
        )
    }

    @Test
    fun `a start that cannot claim leaves a running restore alone`() {
        // S11: tryClaim() fails while this service's own run holds the claim. The no-argument
        // stopSelf() the code used to call there stops the service regardless of outstanding
        // start commands — taking the live restore down with it.
        coordinator.stage()
        coordinator.holdRunOpen()
        val service = startedService()
        assertTrue(coordinator.runStarted.await(10, TimeUnit.SECONDS))

        service.onStartCommand(startIntent(), 0, 2)

        assertFalse(
            shadowOf(service).isStoppedBySelf,
            "a declined start must not stop the service while it is hosting a restore",
        )
        coordinator.releaseRun()
        assertTrue(awaitStoppedBySelf(service), "the service stops once its own run is done")
    }

    @Test
    fun `a burst of per-file progress is sampled into few notification posts`() {
        // S20: a folder restore reports progress once per file, so an unsampled collector posts a
        // notification per restored file — thousands of binder round-trips for a counter that
        // changes faster than anyone can read it. The sample must be a rate limit rather than a
        // filter, so the latest counts still arrive (the second assertion).
        coordinator.stage()
        coordinator.holdRunOpen()
        val service = startedService()
        assertTrue(coordinator.runStarted.await(10, TimeUnit.SECONDS))

        repeat(FILE_BURST) { index ->
            coordinator.publishProgress(RestoreProgress.Files(FILE_BURST, index + 1, 0, "file-$index"))
        }

        // The opening `progress = null` post plus, at most, one sampled post of the burst: the
        // whole burst lands well inside a single sampling window.
        verify(atMost = 2) { notificationManager.updateProgressNotification(any(), any()) }
        verify(timeout = 5_000) {
            notificationManager.updateProgressNotification(
                RestoreProgress.Files(FILE_BURST, FILE_BURST, 0, "file-${FILE_BURST - 1}"),
                any(),
            )
        }
        coordinator.releaseRun()
        assertTrue(awaitStoppedBySelf(service))
    }

    private fun startedService(): RestoreForegroundService {
        val service = Robolectric.buildService(RestoreForegroundService::class.java).create().get()
        service.onStartCommand(startIntent(), 0, 1)
        return service
    }

    private fun startIntent() =
        Intent(ApplicationProvider.getApplicationContext(), RestoreForegroundService::class.java)

    private fun stopIntent() = startIntent().apply { action = RestoreForegroundService.ACTION_STOP }

    /** The service stops itself from a coroutine, so the assertion has to wait for it. */
    private fun awaitStoppedBySelf(service: RestoreForegroundService, timeoutMs: Long = 10_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(ApplicationProvider.getApplicationContext<Application>().mainLooper).idle()
            if (shadowOf(service).isStoppedBySelf) return true
            Thread.sleep(SETTLE_POLL_MS)
        }
        return false
    }

    private companion object {
        private const val SETTLE_POLL_MS = 20L

        /** Files "restored" back-to-back in the sampling test — the shape of a real folder run. */
        private const val FILE_BURST = 200
    }
}

/**
 * Hand-written stand-in for [IRestoreRunCoordinator] (the project prefers a fake over a mock behind
 * a domain seam). [holdRunOpen] keeps `runClaimed` suspended so a test can act while a restore is
 * genuinely in flight — the situation every one of these findings is about.
 */
private class FakeRestoreRunCoordinator : IRestoreRunCoordinator {

    private val _state = MutableStateFlow<RestoreRunState>(RestoreRunState.Idle)
    override val state: StateFlow<RestoreRunState> = _state.asStateFlow()

    private var stagedRequest: RestoreRequest? = null
    private var runGate: CountDownLatch? = null

    var claimed = false
        private set
    var releaseClaimCount = 0
        private set
    var markedHostedInForegroundService = false
        private set
    var stopRequested = false
        private set

    val runStarted = CountDownLatch(1)

    /** Stages a run the way the real coordinator's `start` does, `Running` state included. */
    fun stage() {
        stagedRequest = RestoreRequest.WholeFolder("source", "output", "password", RestoreCollisionPolicy.SKIP)
        _state.value = RestoreRunState.Running(
            mode = RestoreMode.WHOLE_FOLDER,
            progress = null,
            hostedInForegroundService = false,
        )
    }

    /** Publishes progress into the run state the way a real engine callback would. */
    fun publishProgress(progress: RestoreProgress) {
        _state.update { current ->
            if (current is RestoreRunState.Running) current.copy(progress = progress) else current
        }
    }

    /** Makes [runClaimed] block until [releaseRun], simulating a long restore. */
    fun holdRunOpen() {
        runGate = CountDownLatch(1)
    }

    fun releaseRun() {
        runGate?.countDown()
    }

    override fun start(request: RestoreRequest): Boolean {
        stage()
        return true
    }

    override fun tryClaim(): Boolean {
        val canClaim = stagedRequest != null && !claimed
        if (canClaim) claimed = true
        return canClaim
    }

    override fun releaseClaim() {
        claimed = false
        releaseClaimCount++
    }

    override suspend fun runClaimed() {
        runStarted.countDown()
        runGate?.await()
        stagedRequest = null
        claimed = false
        _state.value = RestoreRunState.Finished(RestoreMode.WHOLE_FOLDER, RestoreResult.Success(1, 0, 0, 0))
    }

    override fun markHostedInForegroundService() {
        markedHostedInForegroundService = true
    }

    override fun requestStop() {
        stopRequested = true
        releaseRun()
    }

    override fun acknowledgeResult() {
        _state.value = RestoreRunState.Idle
    }
}
