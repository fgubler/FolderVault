package ch.abwesend.foldervault.domain.restore

/**
 * Everything a whole-folder restore needs to run, handed from the UI to whichever host executes
 * it. Deliberately *not* passed through an `Intent`: it carries the backup [password], and intent
 * extras are visible to the system's activity/service dispatch logging. The foreground service
 * picks the request up from `RestoreRunCoordinator` instead and is started with an empty intent.
 *
 * Both uris are SAF tree uris the user picked in this session; the app holds persisted read
 * (source) and read+write (output) grants for them.
 */
data class RestoreRequest(
    val sourceUri: String,
    val outputUri: String,
    val password: String,
    val collisionPolicy: RestoreCollisionPolicy,
)
