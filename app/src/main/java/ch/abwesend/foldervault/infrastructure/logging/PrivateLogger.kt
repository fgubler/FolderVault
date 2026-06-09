package ch.abwesend.foldervault.infrastructure.logging

import ch.abwesend.foldervault.domain.logging.ILogger

internal class PrivateLogger(
    private val tag: String,
    private val local: LogSink,
    private val remote: LogSink,
) : ILogger {
    override fun debug(message: String) {
        local.debug(tag, message)
        remote.debug(tag, message)
    }

    override fun info(message: String) {
        local.info(tag, message)
        remote.info(tag, message)
    }

    override fun warning(message: String, error: Throwable?) {
        local.warning(tag, message, error)
        remote.warning(tag, message, error)
    }

    override fun error(message: String, error: Throwable?) {
        local.error(tag, message, error)
        remote.error(tag, message, error)
    }
}
