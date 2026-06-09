package ch.abwesend.foldervault.domain.logging

interface ILogger {
    fun debug(message: String)
    fun info(message: String)
    fun warning(message: String, error: Throwable? = null)
    fun error(message: String, error: Throwable? = null)
}
