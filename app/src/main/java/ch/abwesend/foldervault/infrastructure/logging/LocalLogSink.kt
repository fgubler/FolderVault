package ch.abwesend.foldervault.infrastructure.logging

import android.util.Log

internal class LocalLogSink : LogSink {
    override fun debug(tag: String, message: String) { Log.d(tag, message) }
    override fun info(tag: String, message: String) { Log.i(tag, message) }
    override fun warning(tag: String, message: String, error: Throwable?) {
        if (error != null) Log.w(tag, message, error) else Log.w(tag, message)
    }
    override fun error(tag: String, message: String, error: Throwable?) {
        if (error != null) Log.e(tag, message, error) else Log.e(tag, message)
    }
}
