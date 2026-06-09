package ch.abwesend.foldervault.domain.logging

object LoggerProvider {
    @Volatile
    private var factory: (String) -> ILogger = ::SimpleAndroidLogger

    fun configure(factory: (String) -> ILogger) {
        this.factory = factory
    }

    internal fun forTag(tag: String): ILogger = factory(tag)
}
