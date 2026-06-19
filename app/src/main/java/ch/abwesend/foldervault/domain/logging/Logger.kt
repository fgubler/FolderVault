package ch.abwesend.foldervault.domain.logging

import android.util.Log

val Any.logger: ILogger get() = LoggerProvider.forTag(this::class.java.simpleName ?: "FolderVault")

/** Fallback used before [LoggerProvider.configure] is called (e.g. early startup or tests). */
internal class SimpleAndroidLogger(private val tag: String) : ILogger {
    override fun debug(message: String) { Log.d(tag, message) }
    override fun info(message: String) { Log.i(tag, message) }
    override fun warning(message: String, error: Throwable?) {
        if (error != null) Log.w(tag, message, error) else Log.w(tag, message)
    }
    override fun error(message: String, error: Throwable?) {
        if (error != null) Log.e(tag, message, error) else Log.e(tag, message)
    }
}
