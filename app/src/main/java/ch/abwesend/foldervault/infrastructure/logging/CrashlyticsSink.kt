package ch.abwesend.foldervault.infrastructure.logging

import ch.abwesend.foldervault.domain.logging.FileNameRedactor
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Only file in the project allowed to reference [FirebaseCrashlytics] (see `LoggingArchitectureTest`).
 *
 * Breadcrumb messages are logged verbatim: callers are responsible for wrapping any file
 * name/path with [FileNameRedactor.redactPath]/[FileNameRedactor.redact] before logging, and
 * `LogPathRedactionArchitectureTest` enforces that. Routing whole messages through
 * [FileNameRedactor.redactPath] used to collapse every breadcrumb to a few characters (a
 * slash-free sentence was treated as one file name), which made remote diagnostics useless.
 *
 * Exception messages are not authored by us, so there is no call site to fix; they are passed
 * through [FileNameRedactor.redactPathsIn], which scrubs only path-like tokens and leaves the
 * rest of the message intact.
 */
internal class CrashlyticsSink : LogSink {
    private val crashlytics: FirebaseCrashlytics get() = FirebaseCrashlytics.getInstance()

    override fun debug(tag: String, message: String) =
        crashlytics.log("D/$tag: $message")

    override fun info(tag: String, message: String) =
        crashlytics.log("I/$tag: $message")

    override fun warning(tag: String, message: String, error: Throwable?) =
        crashlytics.log("W/$tag: $message")

    override fun error(tag: String, message: String, error: Throwable?) {
        crashlytics.log("E/$tag: $message")
        error?.let { crashlytics.recordException(sanitize(it)) }
    }

    /** Keeps the exception type and scrubs only path-like tokens from its (uncontrolled) message. */
    private fun sanitize(error: Throwable): Throwable {
        val safeMessage = error.message?.let { FileNameRedactor.redactPathsIn(it) } ?: ""
        return Exception("${error::class.simpleName}: $safeMessage").also {
            it.stackTrace = error.stackTrace
        }
    }
}
