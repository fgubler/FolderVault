package ch.abwesend.foldervault.infrastructure.logging

import android.app.Application
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LocalLogFilesTest {

    private lateinit var logsDir: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().context
        logsDir = File(context.filesDir, "logs")
        if (logsDir.exists()) logsDir.deleteRecursively()
    }

    @After
    fun tearDown() {
        if (logsDir.exists()) logsDir.deleteRecursively()
    }

    @Test
    fun `append writes to today's logfile`() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val fixedClock = Clock.fixed(Instant.parse("2026-06-19T10:15:30Z"), ZoneId.of("UTC"))
        val localLogFiles = LocalLogFiles(context, fixedClock)

        localLogFiles.append("hello")

        val todayFile = File(logsDir, "2026-06-19.log")
        assertTrue(todayFile.exists())
        assertTrue(todayFile.readText().contains("hello"))
    }

    @Test
    fun `append cleans up files older than 30 days`() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val fixedClock = Clock.fixed(Instant.parse("2026-06-19T10:15:30Z"), ZoneId.of("UTC"))
        val localLogFiles = LocalLogFiles(context, fixedClock)

        logsDir.mkdirs()
        File(logsDir, "2026-05-20.log").writeText("keep")
        File(logsDir, "2026-05-19.log").writeText("delete")

        localLogFiles.append("new entry")

        assertTrue(File(logsDir, "2026-05-20.log").exists())
        assertFalse(File(logsDir, "2026-05-19.log").exists())
    }
}
