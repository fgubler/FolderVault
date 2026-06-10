package ch.abwesend.foldervault.infrastructure.backup

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object RemoteNameBuilder {

    private val timestampFormatter = DateTimeFormatter
        .ofPattern("yyyy-MM-dd'T'HH-mm-ss'Z'")
        .withZone(ZoneOffset.UTC)

    fun buildName(localName: String, mode: UploadMode, encrypted: Boolean): String {
        val baseName = when (mode) {
            UploadMode.NEW, UploadMode.CHANGED_OVERWRITE -> localName
            UploadMode.CHANGED_DUPLICATE -> buildTimestampedName(localName, Instant.now())
        }
        return if (encrypted) "$baseName.crypt" else baseName
    }

    internal fun buildTimestampedName(localName: String, timestamp: Instant): String {
        val ts = timestampFormatter.format(timestamp)
        val lastDot = localName.lastIndexOf('.')
        return if (lastDot > 0) {
            val stem = localName.substring(0, lastDot)
            val ext = localName.substring(lastDot)
            "${stem}__${ts}${ext}"
        } else {
            "${localName}__${ts}"
        }
    }
}
