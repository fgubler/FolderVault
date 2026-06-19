package ch.abwesend.foldervault.domain.logging

/**
 * Holds the [ILogger] factory used by the [logger] extension. Infrastructure installs the real
 * factory (Crashlytics + local) at app startup; before that — or in tests that don't configure
 * one — a silent [NoOpLogger] is used so nothing crashes for the lack of a logger.
 */
object LoggerProvider {
    @Volatile
    private var factory: (String) -> ILogger = { NoOpLogger }

    fun configure(factory: (String) -> ILogger) {
        this.factory = factory
    }

    internal fun forTag(tag: String): ILogger = factory(tag)
}

private object NoOpLogger : ILogger {
    override fun debug(message: String) = Unit
    override fun info(message: String) = Unit
    override fun warning(message: String, error: Throwable?) = Unit
    override fun error(message: String, error: Throwable?) = Unit
}
