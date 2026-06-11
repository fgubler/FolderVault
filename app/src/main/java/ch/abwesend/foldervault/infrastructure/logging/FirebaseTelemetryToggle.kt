package ch.abwesend.foldervault.infrastructure.logging

import android.content.Context
import ch.abwesend.foldervault.domain.logging.ITelemetryToggle
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics

internal class FirebaseTelemetryToggle(private val context: Context) : ITelemetryToggle {
    override fun setEnabled(enabled: Boolean) {
        FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(enabled)
        FirebaseAnalytics.getInstance(context).setAnalyticsCollectionEnabled(enabled)
    }
}
