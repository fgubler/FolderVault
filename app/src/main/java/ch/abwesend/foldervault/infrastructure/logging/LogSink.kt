package ch.abwesend.foldervault.infrastructure.logging

internal interface LogSink {
    fun debug(tag: String, message: String)
    fun info(tag: String, message: String)
    fun warning(tag: String, message: String, error: Throwable?)
    fun error(tag: String, message: String, error: Throwable?)
}
