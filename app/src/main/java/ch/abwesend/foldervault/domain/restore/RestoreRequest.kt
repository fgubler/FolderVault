package ch.abwesend.foldervault.domain.restore

/**
 * Everything a restore needs to run, handed from the UI to whichever host executes it. Deliberately
 * *not* passed through an `Intent`: it carries the backup password, and intent extras are visible
 * to the system's activity/service dispatch logging. The foreground service picks the request up
 * from `RestoreRunCoordinator` instead and is started with an empty intent.
 *
 * Both flows stage a request here, because both can run for a long time and neither may be tied to
 * the lifetime of the screen that started it. [mode] tells a screen whether the run in flight is
 * *its* run: only one restore runs at a time, and a folder run must not drive the single-file
 * screen's state (nor its result land on top of the single-file one).
 */
sealed interface RestoreRequest {

    /** Which UI flow staged this run. */
    val mode: RestoreMode

    /**
     * A whole backup folder, restored into an output tree. Both uris are SAF *tree* uris the user
     * picked in this session; the app holds persisted read (source) and read+write (output) grants.
     */
    data class WholeFolder(
        val sourceUri: String,
        val outputUri: String,
        val password: String,
        val collisionPolicy: RestoreCollisionPolicy,
    ) : RestoreRequest {
        override val mode: RestoreMode get() = RestoreMode.WHOLE_FOLDER
    }

    /**
     * One picked file, restored into the document the system "Save as" picker created (or handed
     * back for an overwrite). Both uris are single-document uris held under this session's
     * temporary grants — which is one reason no worker could ever resume such a run.
     */
    data class SingleFile(
        val sourceFileUri: String,
        val outputFileUri: String,
        val password: String,
    ) : RestoreRequest {
        override val mode: RestoreMode get() = RestoreMode.SINGLE_FILE
    }
}
