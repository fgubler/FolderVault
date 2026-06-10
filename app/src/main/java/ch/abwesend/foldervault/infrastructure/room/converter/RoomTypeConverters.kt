package ch.abwesend.foldervault.infrastructure.room.converter

import androidx.room.TypeConverter
import ch.abwesend.foldervault.domain.model.BackupRunStatus
import ch.abwesend.foldervault.domain.model.BackupSchedule
import ch.abwesend.foldervault.domain.model.ChangedFilePolicy
import ch.abwesend.foldervault.domain.model.MessageSeverity
import ch.abwesend.foldervault.domain.model.MessageType
import ch.abwesend.foldervault.domain.model.NetworkPolicy
import ch.abwesend.foldervault.domain.model.RetentionPolicy
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val RETENTION_KEEP_ALL = "KEEP_ALL"
private const val RETENTION_KEEP_LAST_N_PREFIX = "KEEP_LAST_N:"
private const val RETENTION_KEEP_NEWER_THAN_PREFIX = "KEEP_NEWER_THAN:"

class RoomTypeConverters {

    // ── Enum converters (stored as stable name strings, never ordinals) ────────

    @TypeConverter
    fun backupScheduleToString(v: BackupSchedule): String = v.name

    @TypeConverter
    fun stringToBackupSchedule(v: String): BackupSchedule = BackupSchedule.valueOf(v)

    @TypeConverter
    fun changedFilePolicyToString(v: ChangedFilePolicy): String = v.name

    @TypeConverter
    fun stringToChangedFilePolicy(v: String): ChangedFilePolicy = ChangedFilePolicy.valueOf(v)

    @TypeConverter
    fun networkPolicyToString(v: NetworkPolicy): String = v.name

    @TypeConverter
    fun stringToNetworkPolicy(v: String): NetworkPolicy = NetworkPolicy.valueOf(v)

    @TypeConverter
    fun backupRunStatusToString(v: BackupRunStatus): String = v.name

    @TypeConverter
    fun stringToBackupRunStatus(v: String): BackupRunStatus = BackupRunStatus.valueOf(v)

    @TypeConverter
    fun messageSeverityToString(v: MessageSeverity): String = v.name

    @TypeConverter
    fun stringToMessageSeverity(v: String): MessageSeverity = MessageSeverity.valueOf(v)

    @TypeConverter
    fun messageTypeToString(v: MessageType): String = v.name

    @TypeConverter
    fun stringToMessageType(v: String): MessageType = MessageType.valueOf(v)

    // ── RetentionPolicy (sealed class) ────────────────────────────────────────

    @TypeConverter
    fun retentionPolicyToString(v: RetentionPolicy): String = when (v) {
        is RetentionPolicy.KeepAll -> RETENTION_KEEP_ALL
        is RetentionPolicy.KeepLastN -> "$RETENTION_KEEP_LAST_N_PREFIX${v.count}"
        is RetentionPolicy.KeepNewerThan -> "$RETENTION_KEEP_NEWER_THAN_PREFIX${v.days}"
    }

    @TypeConverter
    fun stringToRetentionPolicy(v: String): RetentionPolicy = when {
        v == RETENTION_KEEP_ALL -> RetentionPolicy.KeepAll
        v.startsWith(RETENTION_KEEP_LAST_N_PREFIX) ->
            RetentionPolicy.KeepLastN(v.removePrefix(RETENTION_KEEP_LAST_N_PREFIX).toInt())
        v.startsWith(RETENTION_KEEP_NEWER_THAN_PREFIX) ->
            RetentionPolicy.KeepNewerThan(v.removePrefix(RETENTION_KEEP_NEWER_THAN_PREFIX).toInt())
        else -> RetentionPolicy.KeepAll
    }

    // ── List<String> (for BackupMessage.formatArgs) ───────────────────────────

    @TypeConverter
    fun stringListToJson(v: List<String>): String = Json.encodeToString(v)

    @TypeConverter
    fun jsonToStringList(v: String): List<String> = Json.decodeFromString(v)
}
