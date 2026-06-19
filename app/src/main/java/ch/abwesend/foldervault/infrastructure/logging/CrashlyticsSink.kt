package ch.abwesend.foldervault.infrastructure.logging

import ch.abwesend.foldervault.domain.logging.FileNameRedactor
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Only file in the project allowed to reference [FirebaseCrashlytics] (see `LoggingArchitectureTest`).
 *
 * All messages are routed through [FileNameRedactor] before reaching Firebase so file names/paths
 * never appear in crash telemetry.
 */
internal class CrashlyticsSink : LogSink {
    private val crashlytics: FirebaseCrashlytics get() = FirebaseCrashlytics.getInstance()

    override fun debug(tag: String, message: String) =
        crashlytics.log("D/$tag: ${FileNameRedactor.redactPath(message)}")

    override fun info(tag: String, message: String) =
        crashlytics.log("I/$tag: ${FileNameRedactor.redactPath(message)}")

    override fun warning(tag: String, message: String, error: Throwable?) =
        crashlytics.log("W/$tag: ${FileNameRedactor.redactPath(message)}")

    override fun error(tag: String, message: String, error: Throwable?) {
        crashlytics.log("E/$tag: ${FileNameRedactor.redactPath(message)}")
        error?.let { crashlytics.recordException(sanitize(it)) }
    }

    /** Strips the exception message (may contain absolute paths) and keeps only type + redacted form. */
    private fun sanitize(error: Throwable): Throwable {
        val safeMessage = error.message?.let { FileNameRedactor.redactPath(it) } ?: ""
        return Exception("${error::class.simpleName}: $safeMessage").also {
            it.stackTrace = error.stackTrace
        }
    }
}
