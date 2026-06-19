package ch.abwesend.foldervault.infrastructure.logging

import android.app.Application
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LocalLogSinkTest {

    @Test
    fun `debug delegates built entry to async appender`() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val capturedEntry = AtomicReference<String?>(null)
        val sink = LocalLogSink(context, appendAsync = { entry -> capturedEntry.set(entry) })

        sink.debug("TestTag", "hello world")

        val entry = capturedEntry.get()
        assertNotNull(entry)
        assertTrue(entry.contains("[DEBUG] TestTag: hello world"))
    }

    @Test
    fun `file appender failure does not break logger call`() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val sink = LocalLogSink(context, appendAsync = { throw IllegalStateException("boom") })

        var thrown: Throwable? = null
        runCatching { sink.info("TestTag", "still works") }
            .onFailure { thrown = it }

        assertEquals(null, thrown)
    }
}
