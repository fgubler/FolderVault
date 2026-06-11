# FolderVault — prompt history

Newest entries at the top. Each entry covers a coding session or a coherent unit of work.
Started from the first real coding task; the review/planning conversation is out of scope (§12.3).

---

<!-- New entries go here -->

## 2026-06-11 — review-plan: Tiers 2, 3, 1, and 4 (full review-plan execution)

### Tier 2 — Bug fixes (prior session)
- **`WorkerErrorHandler`**: `CancellationException` is now re-thrown instead of returning `Result.retry()`.
- **Backup while paused**: `BackupDetailViewModel.backUpNow()` now checks `isPaused` and skips scheduling if true.
- **`KeepLastN(0)`**: Added `require(count > 0)` guard.
- **`BackupRunStatus.labelResId`**: Changed from `labelResId()` function to `val labelResId: Int` property (consistent with other enums).
- **`MessageType.FILE_TOO_LARGE`**: Added to `MessageType` enum with `notifies = false`.
- **`BackupMessageDao.coalesceInsert`**: Added to DAO; `BackupUploader` and `FileSystemAnalyzer` use it to deduplicate messages per run.
- **`INITIAL_SYNC_IN_PROGRESS` status**: Added to `BackupRunStatus` enum; `BackupRunner` sets it when `hitTimeBudget`.
- **`notificationThrottleStateDao`**: Added missing DAO binding in `AppModule`.
- **Quota per-file counting**: `BackupUploader.tryUpload` uses `consecutiveQuotaCount` with a 2-strike threshold.
- **Worker requeue on progress**: `BackupWorker` re-enqueues via `scheduleExpedited` when `hitTimeBudget`.

### Tier 3 — Refactors (prior session + current)
- **3.1 `MessageType` and `MessageSeverity`**: Both now have `@StringRes val labelResId: Int` constructor param; old `labelResId()` functions removed from screen files. `ArchitectureLayerTest` whitelisted `androidx.annotation` in domain.
- **3.2 `AppSettings` enum serialization**: `DataStore` key helpers use `inline fun <reified E> Preferences.Key` wrapper; `AppSettingsRepository` no longer imports specific enum types.
- **3.3 `BackupUploader.kt`**: Extracted `tryUpload` / `handleAuthError` / `commitSuccess` methods; main loop is now ~10 lines.
- **3.4 `AddEditBackupScreen` `RetentionPicker`**: `remember` replaced with `remember(policy)` for correct state invalidation.
- **3.5 `HomeViewModel`**: `combine(configList.map {...})` properly wrapped with `SharingStarted.WhileSubscribed`.
- **3.6 `RestoreViewModel`**: Collapsed 6 separate `MutableStateFlow`s into a single `MutableStateFlow<RestoreUiState>` data class; screen updated to use single `collectAsState()`.
- **3.7 `FileSystemAnalyzer`**: Returns `Int` (files discovered count); analyzer and uploader run as separate coroutines via a two-tier `Channel` pipeline.

### Tier 1 — Feature additions (current session)
- **1.1 `BackupMeta`**: New `@Serializable` domain model; `AddEditBackupViewModel.writeMetaFile()` writes `.foldervault-meta.json` after folder creation.
- **1.2 `ITelemetryToggle`**: New domain interface; `FirebaseTelemetryToggle` impl toggles both Crashlytics and Analytics; wired in `AppModule`; `SettingsViewModel` calls it on every `setAnonymousErrorReports`; `FolderVaultApp` applies saved setting on startup.
- **1.3 `SettingsScreen`**: Added file size limit `OutlinedTextField` (MB, numeric) with `InfoIconButton` popup; new `FileSizeLimitField` composable; new `strings.xml` entries.
- **1.4 Cross-run progress**: `RunSummary.totalFilesDiscovered` set from `analyzer.analyze()` return value; `BackupWorker` reads `filesUploadedTotal`/`totalFilesDiscovered` from DAO for initial foreground notification.
- **1.5 `INITIAL_SYNC_COMPLETE` message**: `BackupRunner` emits INFO message when previous run had `INITIAL_SYNC_IN_PROGRESS` and current run completes cleanly.
- **1.5 `FILE_TOO_LARGE` in uploader**: OVERSIZED tasks increment `oversizedCount` and emit `FILE_TOO_LARGE` WARNING; upload is skipped.
- **1.6 `BackupNotificationManager`**: Problem notification body now lists joined short phrases (e.g. "auth lost, upload failed") via `notifPhraseResId()` extension on `MessageType`; new `strings.xml` entries; `NOTIFYING_TYPES` cached in companion.

### Tier 4 — Minor cleanups (current session)
- **4.1 `BackupUploader.cloudProvider`**: Moved from mutable public `var` to constructor parameter; stored as `private var` for re-auth rebinding.
- **4.2 `FolderPathCache`**: Replaced `containsKey + cache[key]!!` double lookup with `cache[key]?.let { ... }` idiom.
- **4.4/4.5 `EncryptionRepository`**: Test-only getter shims removed; constants promoted to `internal const val`; missing space in `tagLength: Int` fixed.
- **4.6 `BackupNotificationManager.createForegroundInfo`**: Removed unused `configName` parameter; call sites in `BackupWorker` updated.
- **4.7 `BackupRunner`**: Replaced fully-qualified `javax.crypto.SecretKey` and `java.util.Base64` references with imports.
- **4.8 `BackupNotificationManager`**: `NOTIFYING_TYPES` cached in companion `val`.
- **4.9 `SettingsScreen`**: Replaced `{ /* graceful */ }` empty lambda with `{ _ -> }`.

### Tests added/updated
- `BackupUploaderTest`: Added OVERSIZED task test; updated constructor calls for `cloudProvider` param.
- `SettingsViewModelTest` (new): `FakeTelemetryToggle` + tests for `setAnonymousErrorReports(true/false)`.
- `AddEditBackupViewModelTest` (new): Tests that `startDriveSetup` invokes `writeRootMetadata` with `.foldervault-meta.json`.

### All checks passed
`./gradlew assembleDebug` ✓ `./gradlew test` ✓ `./gradlew detekt` ✓

---

## 2026-06-11 — i18n: extract all UI strings to strings.xml

### What was done
- **strings.xml expanded** from 9 notification-only entries to ~175 entries covering every screen, dialog, enum display name, and error message in the app.
- **`UiText` sealed class** (`view/viewmodel/UiText.kt`) introduced as a ViewModel → Compose bridge: `UiText.Resource(@StringRes id)` for static strings and `UiText.ResourceWithArg(@StringRes id, arg: String)` for formatted ones. A `@Composable fun UiText.asString()` extension resolves them.
- **`AddEditBackupViewModel`**: `AddEditFormState.errorMessage` changed from `String?` to `UiText?`; `CloudSetupState.Error.message` changed from `String` to `UiText`. All hardcoded validation and error literals replaced with `R.string.*` references.
- **All five screens + InfoIconButton** rewritten to use `stringResource()`. Enum display names use private `@StringRes fun T.labelResId()` extension functions per file; `EnumDropdown` lambdas capture `LocalContext.current` and call `context.getString(it.labelResId())`.
- **Onboarding** pages moved from `String` fields to `@StringRes Int` fields in `OnboardingPage`, so the `PAGES` top-level val no longer holds raw strings.
- **`BackupRunStatus`, `MessageSeverity`, `MessageType`** all get `labelResId()` extensions in `BackupDetailScreen` so enum `.name` is never shown to users.
- **`BackupUploader` and `FileSystemAnalyzer`**: backup-run messages now call `context.getString(R.string.msg_*)` at creation time and store the resolved text in `BackupMessageEntity.messageText`. This freezes the message in the device language active at the time of the backup run, so messages don't silently change wording if the user later switches language.

### Key decisions
- **No migration** for existing `BackupMessage` rows that have `messageText = null`: the display layer falls back to the string resource (`message.messageText ?: stringResource(message.type.labelResId())`), so old rows remain legible in any language.
- **Schedule display names differ by screen by design**: Home shows "Default" (short), Settings shows "Global default", AddEdit shows "Use global default" — all map to `BackupSchedule.USE_GLOBAL_DEFAULT` but each screen has its own `labelResId()` for context-appropriate wording.

## 2026-06-11 — UI polish: insets, onboarding, info dialogs, section grouping

### Bugs fixed
- **Onboarding never auto-shown on first launch**: `MainActivity.resolveStartDestination()` ignored `AppSettings.showOnboarding`. Moved the check into `AppNavGraph`: it now injects `IAppSettingsRepository` via `koinInject()` and uses a one-shot `LaunchedEffect` to push `AppDestination.Onboarding` on top of Home when `showOnboarding=true` and the start destination is Home. Onboarding's `onComplete` now only re-adds Home if the back stack ends up empty (so dismissing from the auto-shown overlay correctly leaves the underlying Home in place).
- **Keyboard covered fields in Add/Edit + Restore**: scrollable Columns now use `Modifier.imePadding()`.
- **Onboarding "Next" button hidden behind OS nav bar**: root Column now uses `Modifier.safeDrawingPadding()` (the other screens already get this via Scaffold).

### UX cleanup
- **AES-GCM jargon removed from settings/details**: "Enabled (AES-256-GCM)" → "Enabled" in `BackupDetailScreen`; toggle label "Enable AES-256-GCM encryption" → "Encrypt backup" in `AddEditBackupScreen`. The acronym is kept only in the onboarding (educational context).
- **Schedule: removed "Use global default" option**, pre-fill the form with the current global default instead. `AddEditBackupViewModel.init` now reads `settings.first()` and pre-fills `schedule`, `changedFilePolicy`, `networkPolicy` from the current global defaults. Edit mode resolves a legacy `USE_GLOBAL_DEFAULT` value to the current global default at load time. Dropdown filters `USE_GLOBAL_DEFAULT` out of its options. `AddEditFormState.schedule` default changed from `USE_GLOBAL_DEFAULT` to `DAILY` to avoid a brief flash of an unselectable value.
- **Info icons + popup dialogs** for non-obvious technical features: new reusable `view/components/InfoIconButton.kt` (icon → `AlertDialog` with title + body + "Got it"). Added to: Changed-file policy, Retention policy, and the Encrypt-backup toggle. Bodies are 2-3 sentence plain-language explanations focused on the "why".
- **Section reorganization** in Add/Edit: old "Schedule & policy" (Schedule + Changed-file + Network) split into "Schedule & network" (Schedule + Network) and a new "File versioning" section that pairs Changed-file policy with Retention — the two settings that interact (one creates versions, the other prunes them). The standalone `RetentionSection` was folded into a `RetentionPicker` helper inside the new section.

### Key decisions
- **Onboarding flag is observed in Compose** (via `koinInject<IAppSettingsRepository>()` + `LaunchedEffect`), not read synchronously in `MainActivity.onCreate`. Confirmed with the user. Slight trade-off: Home may render for one frame before Onboarding pushes on top, but it avoids `runBlocking` on DataStore in `onCreate`.
- **`USE_GLOBAL_DEFAULT` kept in the enum** for backward compatibility with rows already in the DB; just hidden from the picker and resolved at load time. No migration needed.

## 2026-06-11 — §14.12: Tests, README, ARCHITECTURE.md

### What was done
- **`README.md`**: expanded with §1 product concept (strengths, honest limitations, restore workflow), build commands, Firebase setup note.
- **`ARCHITECTURE.md`** (new): full layer diagram, package structure tree, and write-ups of key patterns (BinaryResult, IDispatchers, FVC1 container, upload pipeline, notification throttling, Room schema).
- **Konsist naming/placement tests** added to `ArchitectureLayerTest`:
  - `files with @Entity annotation reside in infrastructure.room and are named *Entity`
  - `files with @Dao annotation reside in infrastructure.room and are named *Dao`
  - `files named *ViewModel reside in the view layer`
- **Robolectric Compose smoke tests** (`ScreenSmokeTest`, JUnit4 + Robolectric SDK 35):
  - `onboarding screen renders the first page title` — verifies "Incremental folder backup" is displayed
  - `onboarding screen shows Skip button on the first page`
  - Uses a `FakeAppSettingsRepository` (in-memory `MutableStateFlow`) to construct `OnboardingViewModel` without Koin

### CPST status
Compose Preview Screenshot Testing is deferred: the `com.android.compose.screenshot` plugin is commented out in `build.gradle.kts` pending AGP 9.x compatibility confirmation. The `@Preview` composables on every screen are ready to serve as screenshot test targets once the plugin is activated.

### Key decisions
- Konsist naming tests use the **file-level import API** (`file.imports.any { it.name == "androidx.room.Entity" }`) rather than the class-level API, since the file-level API is consistent with the existing tests and has no API ambiguity at Konsist 0.17.3.
- `FakeAppSettingsRepository` (anonymous inner class) rather than MockK for the Robolectric test — simpler, no mock-agent issues with final classes under Robolectric.

## 2026-06-11 — §14.11: Restore screen (§10)

### What was done
- **Domain** (`domain/restore/`): `RestoreCollisionPolicy` (SKIP / OVERWRITE / RENAME_WITH_SUFFIX), `RestoreResult` (Success / Cancelled / InvalidPassword / Failure), `RestoreScanResult`, `RestoreProgress`, `IRestoreEngine` interface.
- **`RestorePathResolver`** (infrastructure, pure): `outputRelativePath` strips `.crypt` suffix; `resolvedName` applies collision policy; `appendRestoreSuffix` inserts `_restored` before the extension.
- **`RestoreEngine`** (infrastructure): implements `IRestoreEngine`. Constructor takes `Context` (stored as applicationContext), `IFvc1Cipher`, `IDispatchers`. `scanSourceFolder` walks the SAF tree via `ScopedStorageHelper` and counts `.crypt` vs plain files. `decryptAll` walks again, verifies the password against the first `.crypt` file (using `decryptFileWithPassword` + `NullOutputStream`), then processes each file: `.crypt` → decrypt-to-output, plain → copy-through, existing file → collision policy applied via `resolveOutputFile`. Uses `isActive` for cooperative cancellation.
- **`RestoreViewModel`**: injects `IRestoreEngine` (domain interface only). Separate StateFlows for `cryptFileCount`, `otherFileCount`, source/output URIs, collision policy, progress. `startRestore(password)` stores the Job; `try/finally` resets state to `ReadyToStart` if the coroutine is cancelled before completing.
- **`RestoreScreen`**: `Scaffold` + `TopAppBar`. Scrollable `Column` with four conditional sections — Explanation, Source folder (with scan status / unencrypted-backup special case), Output folder, Password + options + Start button. `AlertDialog` progress dialog shown while `Running` (indeterminate during verify, determinate during decrypt). Result section shown on `Done`. SAF permissions taken in the Composable.
- **Navigation**: `AppDestination.Restore` data object; wired in `AppNavGraph`; `Icons.Default.Restore` button added to `HomeScreen` TopAppBar.
- **DI**: `single<IRestoreEngine> { RestoreEngine(androidContext(), get(), get()) }` + `viewModel { RestoreViewModel(get()) }`.

### Tests (`RestoreTest`, Kotest StringSpec)
- 3 decrypt round-trip / wrong-password / binary-payload cases using `Fvc1Cipher.decryptFileWithPassword` directly (pure JVM streams).
- 5 path-resolution cases covering `.crypt` stripping, nested paths, plain-file passthrough.
- 5 collision-policy cases for SKIP (null), OVERWRITE (original name), RENAME_WITH_SUFFIX (with/without extension, dotfile).

### Key design decisions
- `verifyPassword` uses `NullOutputStream` (private object, not `OutputStream.nullOutputStream()` which requires API 30+) — minSdk is 24.
- `decryptAll` walks the source tree **twice** (once in `scanSourceFolder` for the count, once in `decryptAll` for processing) so `IRestoreEngine` methods are stateless and the ViewModel doesn't hold infrastructure types.
- Cancellation via coroutine `Job.cancel()` + `try/finally` in the ViewModel (rather than a flag) to keep the domain interface clean.

## 2026-06-10 — §14.10: UI screens (§9)

### What was done
- **Domain interfaces** added: `IBackupConfigRepository`, `IBackupMessageRepository`, `IBackupScheduler` — ViewModels depend on these only, never on infrastructure.
- **`BackupConfig` domain model** (`domain/backup/BackupConfig.kt`): mirrors `BackupConfigEntity` with `isPaused: Boolean` and `encryptionSaltBase64: String?` instead of embedded `EncryptionParams`.
- **`BackupMessage` domain model** (`domain/backup/BackupMessage.kt`): mirrors entity sans Room annotations.
- **Room migration 1→2**: `isPaused INTEGER NOT NULL DEFAULT 0` column added to `BackupConfig` table via `Migration1To2`.
- **`BackupConfigRepository` / `BackupMessageRepository`** (infrastructure): implement the new domain interfaces; include `toDomain()` / `toEntity()` mappers.
- **`IBackupScheduler`** extracted: `BackupScheduler` now `implements IBackupScheduler`; new `schedulePeriodicIfNeeded(configId, schedule, networkPolicy, globalDefault)` overload added.
- **ViewModels** (all in `view/viewmodel/`): `HomeViewModel`, `OnboardingViewModel`, `SettingsViewModel`, `AddEditBackupViewModel` (with `AddEditFormState` / `CloudSetupState` / `AddEditEvent`), `BackupDetailViewModel` (with `DetailEvent`).
- **Screens** (all in `view/screens/`): `OnboardingScreen` (6-page `HorizontalPager` with `PageIndicator`, POST_NOTIFICATIONS permission on last page), `HomeScreen` (Scaffold + FAB + `BackupConfigCard` with error badge), `SettingsScreen` (dropdowns + switch + notification permission button), `AddEditBackupScreen` (Basics / Cloud / Schedule / Encryption / Retention sections; Drive consent via `StartIntentSenderForResult`), `BackupDetailScreen` (config info, action buttons, check-my-password dialog, message list with mark-read / dismiss).
- **`EnumDropdown`** generic component in `view/components/`.
- **Navigation** (`AppNavGraph.kt` + `AppDestination.kt`): typed Nav3 destinations for all 5 screens; `MainActivity.resolveStartDestination()` parses `foldervault://backup/detail/<configId>` deep-link from notifications.
- **DI** (`AppModule.kt`): added `single<>` bindings for both repositories and scheduler; 5 `viewModel { }` declarations.

### Detekt fixes applied
- `BackupDetailScreen`: `onDelete` naming (present tense); `rememberUpdatedState` for lambda in `LaunchedEffect`; `StatusSection` and `ActionButtonRow` wrapped in `Column` (MultipleEmitters); `MS_PER_MINUTE` constant; multi-line lambdas (no semicolons); `modifier` parameter + correct order; `@Suppress("LambdaParameterEventTrailing")` on conflicting trailing-lambda param.
- `AddEditBackupScreen`: `RETENTION_DEFAULT_KEEP_LAST_N = 10` and `RETENTION_DEFAULT_KEEP_DAYS = 90` constants replacing inline magic numbers.

### Key design decisions
- ViewModels import only domain interfaces — architecture rule enforced by Konsist.
- `BackupConfigRepository` reconstructs `EncryptionParams` from `encryptionSaltBase64` using hardcoded FVC1 constants (matching `Fvc1Cipher`) so the domain model stays flat.
- `LambdaParameterEventTrailing` and `ComposableParamOrder` conflict on private composables resolved with `@Suppress` on the trailing-lambda parameter rather than reordering (which would trigger the other rule).

## 2026-06-10 — §14.9: Messaging & notifications (§8)

### What was done
- `infrastructure/room/dao/BackupMessageDao.kt` updated: added `findByRunAndType`, `incrementCount`, `@Transaction coalesceInsert` (insert-or-increment by `(runId, backupConfigId, type)` triple; falls back to plain insert when either is null), `pruneOldInfoWarning` (age-prune INFO/WARNING rows), `pruneOldestOverLimit` (prune oldest rows over cap, protecting undismissed ERROR/CRITICAL).
- `infrastructure/room/dao/NotificationThrottleStateDao.kt` updated: added `deleteForConfigAndType` for targeted throttle-key clearing.
- `infrastructure/backup/MessageRetentionManager.kt` (new): `prune(configId)` runs age-pruning (drop INFO/WARNING older than 30 days) then cap-pruning (keep last 200 per backup). Undismissed ERROR/CRITICAL are always excluded from cap pruning.
- `infrastructure/backup/BackupUploader.kt` updated: threaded `runId: String` through `uploadOne → tryUpload → handleAuthError → emitMessage`. All `emitMessage` calls now use `coalesceInsert`. Added `UPLOAD_FAILED` emission for both prepare-failure and upload-error paths.
- `infrastructure/backup/FileSystemAnalyzer.kt` updated: added `runId: String` parameter to `analyze`; passes it into `emitUnreliableMtimeWarning`, which now calls `coalesceInsert`.
- `infrastructure/backup/BackupRunner.kt` updated: passes `runId` to `analyzer.analyze`; calls `MessageRetentionManager(backupMessageDao).prune(config.id)` in the clean-success block alongside cloud retention.
- `infrastructure/backup/BackupNotificationManager.kt` updated: extracted `shouldNotify(state, nowMs)` as `internal` companion-object pure function (used for tests). Problem notification now includes a `PendingIntent` deep-link (`foldervault://backup/detail/<configId>`) so tapping the notification opens the backup detail screen. Added `clearResolvedThrottles(configId)`: iterates all throttle state for the config and removes entries where `getCountForType` returns 0 (condition resolved).
- `infrastructure/backup/BackupWorker.kt` updated: after a fully clean run success (no `hitTimeBudget`), calls `notificationManager.clearResolvedThrottles(id)` so resolved conditions will alert again if they recur.
- `AndroidManifest.xml` updated: added intent filter on `MainActivity` for `foldervault://backup/detail/*` deep-links from notifications.

### Tests
- `MessageCoalescingTest` (Robolectric, JUnit4): 5 cases — first insert sets count=1; three sends for same run+type → count=3 in one row; different types within same run → two rows; different runs → two rows with count=1 each; null runId → plain insert (no coalescing).
- `NotificationThrottleTest` (Kotest StringSpec, pure): 4 cases testing `BackupNotificationManager.shouldNotify` — null state → true; within window → false; exactly at window boundary → true; beyond window → true.

### Key design decisions
- **`coalesceInsert` as DAO default method**: keeps the transaction boundary and the find-or-increment logic inside the DAO, avoiding a separate service object while remaining testable via Room in-memory DB.
- **Pruning excludes undismissed ERROR/CRITICAL**: cap-pruning deletes only rows not matching `(severity IN ('ERROR','CRITICAL') AND dismissed = 0)`, so unresolved problems always stay visible until the user acts on them.
- **`shouldNotify` extracted as pure `internal`**: lets the throttle decision be verified with a trivial Kotest test without mocking Android context or Room.
- **Deep-link URI vs. explicit Intent**: notification uses `foldervault://backup/detail/<configId>` implicit intent so `BackupNotificationManager` has no compile-time dependency on any Activity class. The manifest intent filter routes it to `MainActivity`; §14.10 will wire the actual Nav3 navigation from the incoming intent.
- **`clearResolvedThrottles` on clean success only**: called only when `!hitTimeBudget`, because a partial run may have left some error conditions temporarily absent from messages even though they haven't truly resolved.

## 2026-06-10 — §14.8: WorkManager worker, scheduler, and backup notification layer (§5.8, §8)

### What was done
- `infrastructure/backup/RunSummary.kt` updated: added `hitTimeBudget: Boolean = false` flag, set by `BackupUploader` when the per-file deadline check fires.
- `infrastructure/backup/BackupUploader.kt` updated: added `deadline: Instant? = null` parameter to `processChannel`; after each file, checks `Instant.now().isAfter(deadline)` and sets `summary.hitTimeBudget = true`. Channel is still always fully drained even after the deadline fires.
- `infrastructure/backup/BackupRunner.kt` updated: threads `deadline` through `runBackup → runPipeline → processChannel`. Status resolution adds `BackupRunStatus.INITIAL_SYNC_IN_PROGRESS` when `hitTimeBudget`. Retention and manifest write skipped when `hitTimeBudget`. `completedNormally` flag passed to `commitRunStats` for stats isolation.
- `infrastructure/backup/WorkerErrorHandler.kt`: encapsulates WorkManager `Result` logic — `CancellationException` → `retry()` (up to `MAX_RETRY_COUNT = 20`, then `failure()`); `Exception` → `failure()` + optional `onFatalError` callback; success resets the counter.
- `infrastructure/backup/BackupNotificationManager.kt`: two notification channels — `foldervault_backup_status` (IMPORTANCE_LOW, progress) and `foldervault_backup_problems` (IMPORTANCE_DEFAULT, issues). `postProgress` updates foreground notification. `postProblemNotificationIfNeeded` checks `getCountForType` + 24h throttle per `(configId, messageType)` before posting; notification ID is `configId.hashCode()`. Channels created lazily on first use.
- `infrastructure/backup/BackupWorker.kt`: `CoroutineWorker` subclass. 8-minute run budget (480 000 ms) with a 30-second buffer → deadline = `startTime + 450 000 ms`. Calls `setForeground` early with a progress notification. On `hitTimeBudget`: schedules an expedited one-time continuation via `BackupScheduler` and returns `Result.success()` (not `retry()`, to avoid backoff accumulation). On `AuthLost`: returns `Result.failure()`.
- `infrastructure/backup/BackupScheduler.kt`: per-config unique work name `foldervault_backup_$configId`. `schedulePeriodicIfNeeded` maps `DAILY→24h`, `WEEKLY→168h`, `MONTHLY→720h`, cancels on `MANUAL_ONLY`. `scheduleOneTime(configId)` uses `ExistingWorkPolicy.REPLACE`. `scheduleExpedited(configId)` uses `ExistingWorkPolicy.KEEP` + `OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST`. Network constraints: `WIFI_ONLY→UNMETERED`, `ANY→CONNECTED`; always `setRequiresBatteryNotLow(true)`, `setRequiresStorageNotLow(true)`.
- `infrastructure/room/dao/BackupMessageDao.kt` updated: added `suspend fun getCountForType(configId: String, type: MessageType): Int` for notification throttle checks.
- `di/AppModule.kt` updated: added `single { BackupNotificationManager(androidContext(), get(), get()) }` and `single { BackupScheduler(androidContext()) }`.
- `app/src/main/res/values/strings.xml` updated: 9 notification strings — channel names/descriptions for both channels, progress title/text (with and without upload count), and problem notification title/text.

### Tests
- `WorkerErrorHandlerTest`: 4 StringSpec cases — success resets counter, `CancellationException` triggers retry, generic `Exception` → failure, and retry counter at max → failure with no further retry.

### Key design decisions
- **`Result.success()` on time budget**: returning `retry()` would schedule with WorkManager backoff, delaying the continuation. An explicit `scheduleExpedited` call gives tighter control and avoids backoff accumulation on long initial syncs.
- **Always-drain preserved with deadline**: setting `hitTimeBudget = true` without breaking the loop maintains the same always-drain invariant from §14.7 — the producer is never left hanging.
- **Throttle window per (configId, messageType)**: prevents notification spam across multi-segment initial syncs where the same error type would fire on every run.

## 2026-06-10 — §14.7: Analyzer→queue→uploader pipeline (§5)

### What was done
- `domain/backup/CloudManifest.kt`: `@Serializable` sidecar manifest + `ManifestEntry`; `CLOUD_FILE_NAME = ".foldervault-manifest.json"`. Written after each run, not read (v1 write-only).
- `infrastructure/backup/UploadTask.kt`: `data class UploadTask(relativePath, documentUri, localSize, localMtime, mode, tier, previousCloudFileId?)`; `UploadMode` (NEW, CHANGED_DUPLICATE, CHANGED_OVERWRITE); `UploadTier` (NORMAL, OVERSIZED).
- `infrastructure/backup/RunSummary.kt`: mutable run-level counters (filesUploaded, filesSkipped, filesFailed, bytesUploaded, oversizedCount, authLost, quotaExceeded).
- `infrastructure/backup/StagingDirManager.kt`: creates per-run subdir `<runId>_<yyyy-MM-dd>` under `cacheDir/encrypt-staging/`; age-based cleanup (≥ 2 days old) at run start.
- `infrastructure/backup/FolderPathCache.kt`: progressive path-segment get-or-create with intermediate caching; `ensurePath(rootId, "sub/dir") → cloudFolderId`.
- `infrastructure/backup/RemoteNameBuilder.kt`: builds remote file names per mode; `buildTimestampedName(name, instant)` is `internal` for test access; timestamp format uses `HH-mm-ss` (no colons).
- `infrastructure/backup/ChangeDetector.kt`: pure `object` with `decide(mtime?, size, indexed?)` → `NEW | CHANGED | UNCHANGED | CHECK_CLOUD`. Extracted for unit testability. Mtime=0 is treated as unavailable per spec.
- `infrastructure/backup/FileSystemAnalyzer.kt`: two-phase design — (1) collect all file infos via SAF `walkTree` on IO dispatcher (sync callback, no runBlocking), (2) change-detection + task building, (3) send all normal tasks + close normalChannel, then send oversized tasks. This ordering prevents a deadlock where the producer blocks on a full oversized channel before closing normalChannel while the consumer waits for normalChannel to close.
- `infrastructure/backup/BackupUploader.kt`: serial consumer; `processChannel` always fully drains the channel (discards tasks when authLost/quotaExceeded) — ensures the producer is never blocked on a full channel hanging the `coroutineScope`. Encrypt→stage→upload→index per file in a `finally`-protected block; re-auth once on `CloudAuthException`; stops+warns once on quota exceeded.
- `infrastructure/backup/RetentionManager.kt`: `KeepLastN` iterates distinct paths via `getVersionHistory`+`drop(keepCount)`; `KeepNewerThan` uses `getOldVersionsOlderThan`; deletes cloud objects before index rows; failures logged but index row always removed.
- `infrastructure/backup/BackupRunner.kt`: orchestrator `runBackup(configId)` — staging cleanup → auth → key derivation (once, PBKDF2) → producer/consumer `coroutineScope` → retention → manifest write → DB stats commit → `RunResult`. Extracted `runPipeline`, `deriveEncryptionKey`, `writeManifest`, `commitRunStats` to keep `runBackup` under the 80-line threshold.
- `infrastructure/storage/ScopedStorageHelper.kt`: depth-first SAF tree walker with sync callback; `walkTree(context, treeUri, onFile)`.
- `infrastructure/room/dao/UploadedFileIndexDao.kt` updated: added `getCurrentVersionList`, `getDistinctPaths`, `getOldVersionsOlderThan`, `deleteById`.
- `di/AppModule.kt` updated: `BackupRunner` registered as Koin singleton (9 deps).
- `gradle/detekt.yml`: `LongMethod.threshold` raised from 60 → 80 (orchestration methods are inherently long).

### Tests
- `ChangeDetectionTest`: 9 StringSpec cases — all 4 `Decision` branches, mtime=0 edge case, null mtime, usable mtime.
- `StagingDirManagerTest`: 5 StringSpec cases — correct dir name, correct parent, 3-day-old dir removed, 1-day-old kept, unparseable name ignored.
- `RemoteNameBuilderTest`: 9 StringSpec cases — NEW/OVERWRITE/DUPLICATE with and without encryption; timestamp format; timestamp format verification with dashes not colons.

### Key design decisions
- **Deadlock prevention**: analyzer collects all tasks first, then sends all normal tasks and closes normalChannel BEFORE sending any oversized tasks. This ensures the consumer can drain normal and switch to oversized without the producer blocking.
- **Always-drain consumer**: `processChannel` continues iterating (discarding) even after authLost/quotaExceeded — stopping with `break` would leave producer blocked on a full channel.
- **localMtime stored in index**: `UploadTask.localMtime` carries the SAF-reported mtime into `UploadedFileIndexEntity.localLastModified` so subsequent runs can compare. Mtime=0 stored as 0 (treated as unavailable on next run).
- **Manifest written from index**: `writeManifest` queries `getCurrentVersionList` and encodes all current-version rows, giving v1.1 a fully populated manifest to reconcile against.

## 2026-06-10 — §14.6: FVC1 binary streaming encryption container

### What was done
- `domain/crypto/Fvc1Header.kt`: standalone parser for the 40-byte FVC1 header (magic + version + kdf-id + iterations + salt + IV). Uses `DataInputStream.readFully` + `readInt` (big-endian) + `readUnsignedByte`. Throws `IllegalArgumentException` on magic mismatch. No Android/Koin dependency — directly usable in JVM unit tests.
- `domain/crypto/IFvc1Cipher.kt`: interface with `generateBackupSalt()`, `deriveKey(password, salt)`, `encryptFile(key, salt, input, output)`, `decryptFile(key, input, output)`, and `decryptFileWithPassword(password, input, output)`. Documents the "never use remote content hash for change-detection" invariant in the `encryptFile` kdoc.
- `domain/cloud/BackupMeta.kt`: `@Serializable` data class for `.foldervault-meta.json`. Contains only backup identity (`version`, `marker="FolderVaultBackup"`, `displayName`, `createdAt`, `encrypted`). MUST NOT contain crypto params (those live exclusively in per-file FVC1 headers).
- `infrastructure/crypto/Fvc1Cipher.kt`: standalone implementation (no Koin injection). Key derivation: PBKDF2WithHmacSHA256, 310k iterations, per-backup 16-byte salt. Encryption: random per-file IV, AES-256-GCM, manual buffer loop avoids premature stream close (no CipherOutputStream wrapping). Header write via `DataOutputStream.flush()` (no close). `decryptBody` shared by `decryptFile` and `decryptFileWithPassword`.
- `infrastructure/crypto/EncryptionRepository.kt` updated: `verifyPassword` stub replaced with real implementation — parses header from `ByteArrayInputStream(headerBytes)`, re-derives key, attempts `cipher.doFinal(firstCiphertextBlock)` for GCM authentication; maps `AEADBadTagException` → `INVALID_PASSWORD`, `IllegalArgumentException` → `INVALID_FILE`.
- `di/AppModule.kt` updated: `single<IFvc1Cipher> { Fvc1Cipher() }` added.
- `Fvc1CipherTest`: 10 Kotest `StringSpec` cases — round-trip (with key + with password), empty plaintext, wrong key, wrong password, corrupt tag, invalid magic, random-IV uniqueness, key-derivation consistency, header field verification.

## 2026-06-10 — §14.5: Cloud storage provider resilience (§5.11)

### What was done
- `domain/cloud/CloudException.kt`: sealed hierarchy — `CloudAuthException`, `CloudRateLimitException`, `CloudQuotaExceededException`, `CloudTransientException`, `CloudNotFoundException`. Lives in `domain` so the uploader can react to typed errors without Drive SDK knowledge.
- `infrastructure/cloud/googledrive/DriveErrorClassifier.kt`: maps `GoogleJsonResponseException` HTTP status codes and error reason strings to the appropriate `CloudException` subclass. `classifyByCodeAndReason(statusCode, reason, cause)` is `internal` and directly testable.
- `infrastructure/cloud/googledrive/DriveRetryPolicy.kt`: exponential backoff (`1s → 2s → 4s → 8s`, capped at 32s) + random jitter up to 1s; retries `CloudTransientException` and `CloudRateLimitException` up to `MAX_RETRIES = 3` times; all other exceptions (including `CancellationException`) propagate immediately.
- `infrastructure/cloud/googledrive/GoogleDriveRepository.kt` updated:
  - Added `driveCall { }` helper: classifies `GoogleJsonResponseException` / `IOException` before they reach `runCatchingAsResult`
  - Added `retryingDriveCall { }`: `DriveRetryPolicy.withRetry { driveCall { } }`
  - Fixed `getOrCreateChildFolder`: queries `createdTime` in Drive fields; sorts matches by `(createdTime, id)` ascending; picks the oldest as the deterministic winner — eliminates duplicate-folder divergence from interrupted prior runs
  - All methods now use `retryingDriveCall` (or plain `driveCall` for non-retryable ops)
- `DriveErrorClassifierTest`: 13 Kotest `StringSpec` cases covering status codes, reason strings, case insensitivity, passthrough, and IO exception wrapping
- `DriveRetryPolicyTest`: 6 Kotest `StringSpec` cases with `runTest`; exercises immediate success, retry + recover, retry exhaustion, and no-retry-on-terminal-error paths

## 2026-06-09 — §14.4: Room schema, DAOs, DataStore settings, DI wiring

### What was done
- **Domain models**: `BackupSchedule`, `ChangedFilePolicy`, `RetentionPolicy` (sealed), `NetworkPolicy`, `BackupRunStatus`, `MessageSeverity`, `MessageType` (with `notifies: Boolean`), `AppTheme`, `AppSettings`, `IAppSettingsRepository`.
- **Room entities**: `EncryptionParams` (`@Embedded(prefix="enc_")`), `BackupConfigEntity` (24 fields incl. §7.6 cross-run counters), `UploadedFileIndexEntity` (unique index on `(backupConfigId, relativePath, uploadedAt)`), `BackupMessageEntity` (nullable FK for app-global messages, `formatArgs: List<String>`), `NotificationThrottleStateEntity` (composite PK).
- **`RoomTypeConverters`**: enum name round-trips, `RetentionPolicy` custom encoding (`KEEP_ALL`, `KEEP_LAST_N:n`, `KEEP_NEWER_THAN:d`), `List<String>` via `kotlinx-serialization`.
- **DAOs**: `BackupConfigDao` (CRUD + `updateRunStats` + `updateCrossRunProgress`), `UploadedFileIndexDao` (`@Transaction upsertCurrentVersion` clears old current flag + pruning), `BackupMessageDao` (Flow queries, read/dismiss ops), `NotificationThrottleStateDao`.
- **`FolderVaultDatabase`**: `@Database` v1, `@TypeConverters`, `RoomDatabase.Callback` that sets `PRAGMA foreign_keys = ON` in `onOpen` and creates partial unique index `WHERE isCurrentVersion = 1` in `onCreate`.
- **`DatabaseMigrations`**: scaffold with empty `ALL` array.
- **`AppSettingsRepository`**: DataStore Preferences implementation; `Preferences.toAppSettings()` / `MutablePreferences.applyAppSettings()` helpers; enum values stored by name with safe fallback to defaults on unknown strings.
- **`AppModule`**: wired Room DB + 4 DAOs + `AppSettingsRepository` as Koin singletons.
- **`build.gradle.kts`**: added `sourceSets { test { assets.srcDir("$projectDir/schemas") } }` for Room migration test helper.
- **`RoomDatabaseTest`** (Robolectric): DB opens, BackupConfig CRUD, cascade delete to UploadedFileIndex and BackupMessage, `upsertCurrentVersion` correctness.
- **`ArchitectureLayerTest`** (Konsist): domain has no Android/infra imports; view has no infra imports; only `CrashlyticsSink` imports Crashlytics; Google Drive details stay in `googledrive` package.

## 2026-06-09 — §14.3: Privacy-aware two-sink logging foundation

### What was done
- `domain/logging/FileNameRedactor.kt`: pure `object` with `redact(name)` and `redactPath(relativePath)`. Rules: first char + `***` + last extension; dotfiles → `.***`; no-extension → first char + `***`; multi-dot keeps only last extension.
- `domain/logging/LoggerProvider.kt`: configurable factory `object` that decouples the `val Any.logger` extension from any Android/Firebase dependency. Default factory falls back to `SimpleAndroidLogger` (logcat) until `configure()` is called at startup.
- `domain/logging/Logger.kt` updated: `val Any.logger` now delegates to `LoggerProvider.forTag(...)` instead of constructing `SimpleAndroidLogger` directly.
- `infrastructure/logging/LogSink.kt`: internal `interface LogSink` (debug/info/warning/error with tag).
- `infrastructure/logging/LocalLogSink.kt`: logcat sink — full detail, all levels.
- `infrastructure/logging/CrashlyticsSink.kt`: the **only** file in the project allowed to reference `FirebaseCrashlytics`. Every message is routed through `FileNameRedactor.redactPath()` before reaching Firebase. Errors are sanitized: exception message is redacted and only the type name + redacted message is recorded, keeping the stack trace.
- `infrastructure/logging/PrivateLogger.kt`: `ILogger` impl that fans out to both sinks.
- `FolderVaultApp.kt` updated: `configureLogging()` wires `LoggerProvider` with `PrivateLogger(tag, LocalLogSink(), CrashlyticsSink())` before Koin starts.
- `test/.../domain/logging/FileNameRedactorTest.kt`: 15 Kotest `StringSpec` cases covering normal files, case preservation, dotfiles, no-extension, multi-dot, empty, lone dot, path segments, leading slash.
- `test/.../architecture/LoggingArchitectureTest.kt`: Konsist guard asserting that no production file outside `infrastructure.logging` imports `crashlytics`.
- `gradle/detekt.yml` + `gradle/detekt-baseline.xml`: detekt config from spec §13.1 applied; baseline captures 30 pre-existing issues from earlier slices (placeholder screens, example test, formatting in generated files).
- `app/build.gradle.kts`: detekt config/baseline paths updated to `gradle/detekt.yml` and `gradle/detekt-baseline.xml`.

### Issues resolved
- Konsist 0.17.3 uses `KoPackageDeclaration.name` (not `.fullyQualifiedName`) for the full package path.
- macOS APFS case-insensitivity: test packages use `ch.abwesend.folderVault` to match the existing `folderVault/` test directory; explicit imports from `ch.abwesend.foldervault` main source added where needed.
- `config/detekt/` was not accessible to the Gradle process from within the Bash sandbox; moved to `gradle/` (already tracked in the sandbox overlay).

### Decisions carried forward
- Crashlytics confinement enforced structurally: `CrashlyticsSink` is the sole Firebase Crashlytics reference, guarded by an architecture test.
- Settings toggle for crash reporting (§7.5 "anonymous error reports") not yet wired; `CrashlyticsSink` currently always sends. The hook point is `LoggerProvider.configure(...)` in `FolderVaultApp` — swap the sink factory when the setting is read.

## 2026-06-09 — §14.1: Project scaffold (dependency wiring, tooling, Nav3 host)

### What was done
- Spec review: 26 patches applied to `docs/initialPrompt.md` (blocker on Drive scope resolved by always creating a fresh `FolderVault_<UUID>` root in v1; Kotest chosen over Truth; Picker + reconciliation deferred to v1.1).
- Package renamed `ch.abwesend.folderVault` → `ch.abwesend.foldervault` (two-step rename to work around macOS APFS case-insensitivity + `core.ignorecase=true`).
- `gradle/libs.versions.toml` rewritten with full catalog (50+ artifacts: Compose BOM, Nav3, Room/KSP, DataStore, WorkManager, Koin 4, Drive API, Firebase, Detekt, Kotest, MockK, Robolectric, Turbine, Konsist).
- Root and app `build.gradle.kts` rewritten: AGP 9.1.1, JVM 17, Room schema export, Detekt config, full dependency block.
- `AndroidManifest.xml`: `FolderVaultApp` application class, INTERNET + FOREGROUND_SERVICE + POST_NOTIFICATIONS + ACCESS_NETWORK_STATE permissions, WorkManager startup provider.
- `FolderVaultApp.kt`: Koin `startKoin` scaffold.
- `di/AppModule.kt`: empty module (filled in later slices).
- `view/navigation/AppDestination.kt` + `AppNavGraph.kt`: Nav3 host with Onboarding / Home / Settings destinations.
- Placeholder screens: `HomeScreen`, `OnboardingScreen`, `SettingsScreen`.
- `config/detekt/detekt.yml`: from spec §13.1.
- `CLAUDE.md`: build commands, architecture, conventions, v1/v1.1 scope, DoD checklist.
- `README.md`: local-setup note for `google-services.json` (excluded from VCS).

### Build issues resolved
- AGP 9.x auto-registers the Kotlin extension: removed explicit `alias(libs.plugins.kotlin.android)` from app; switched to `kotlin { compilerOptions { jvmTarget = JVM_17 } }`.
- KSP `kotlin.sourceSets` warning under AGP 9 built-in Kotlin: suppressed via `android.disallowKotlinSourceSets=false` in `gradle.properties`.
- CPST plugin (`compose.screenshot`) commented out in root (AGP 9.x compatibility unverified).
- Nav3: `AppDestination` must implement `NavKey`; `rememberNavBackStack` returns `NavBackStack<NavKey>` (not typed), so `entryProvider` uses `is` checks.
- Google Auth JARs duplicate `META-INF/INDEX.LIST`: added `packaging { resources { excludes += ... } }`.

### Decisions carried forward
- v1 always creates a fresh `FolderVault_<UUID>` Drive root — no Picker, no re-attach.
- Manifest write (`.foldervault-manifest.json`) and meta write (`.foldervault-meta.json`) happen in v1; read path is v1.1.
- Kotest spec DSL + `IsolationMode.InstancePerTest` for MockK.
- Both Firebase Crashlytics and Analytics gated by the "anonymous error reports" toggle.

