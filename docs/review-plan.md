# FolderVault — review fixes plan

Derived from a code review + spec-compliance audit on 2026-06-11.
Execution order is **Tier 2 → Tier 3 → Tier 1 → Tier 4** (see end of file for rationale).

After each tier, run:

```bash
./gradlew assembleDebug && ./gradlew test && ./gradlew detekt
```

**Architecture invariants** (CLAUDE.md):
- `domain/` imports nothing Android-/infra-specific.
- `view/` and `infrastructure/` may import `domain/` but not each other.
- Only `infrastructure/logging/CrashlyticsSink.kt` may import `FirebaseCrashlytics` — enforced by `LoggingArchitectureTest`.
- Cloud-provider specifics stay inside `infrastructure/cloud/googledrive/`.

---

## Execution order

1. **Tier 2 — Bugs** (small, isolated, stabilize foundation)
2. **Tier 3 — Duplication / abstraction** (mechanical refactors with broad reach)
3. **Tier 1 — Spec gaps** (functional additions — most behaviour change; benefits from clean enum labels from Tier 3)
4. **Tier 4 — Minor cleanups** (cosmetic; do last so they don't conflict with earlier edits)

---

## Tier 2 — Bugs

### 2.1 Stop swallowing `CancellationException`
**File**: `app/src/main/java/ch/abwesend/foldervault/infrastructure/backup/WorkerErrorHandler.kt:23-33`
Remove the `CancellationException` branch entirely. Coroutines contract requires it to propagate. WorkManager will treat the run as cancelled normally. Keep the `Exception` branch for retry-or-fail logic.
**Tests**: update `WorkerErrorHandlerTest` accordingly.

### 2.2 Fix garbled folder display name
**File**: `app/src/main/java/ch/abwesend/foldervault/view/viewmodel/AddEditBackupViewModel.kt:269`
Extract:
```kotlin
private fun displayNameFromTreeUri(uri: Uri): String =
    uri.lastPathSegment?.substringAfterLast(':')?.takeIf { it.isNotBlank() } ?: uri.toString()
```
(or move to `ScopedStorageHelper`). Replace both call sites — line 88 and the broken `extractFolderDisplayName` at line 269.

### 2.3 Block manual "Back up now" when paused
**File**: `app/src/main/java/ch/abwesend/foldervault/view/viewmodel/BackupDetailViewModel.kt:55`
Read the current config; if `isPaused` is true, return early (or emit a user-visible info message via the existing event channel).

### 2.4 Guard `RetentionPolicy.KeepLastN(count)` against 0
**Files**:
- `app/src/main/java/ch/abwesend/foldervault/domain/model/RetentionPolicy.kt`
- `app/src/main/java/ch/abwesend/foldervault/infrastructure/backup/RetentionManager.kt`

Add `init { require(count >= 1) { "KeepLastN.count must be >= 1" } }` to `KeepLastN`. In `RetentionManager.applyKeepLastN`, additionally clamp via `keepCount.coerceAtLeast(1)` (defence-in-depth).

### 2.5 Prune messages even on incomplete runs
**File**: `app/src/main/java/ch/abwesend/foldervault/infrastructure/backup/BackupRunner.kt:107`
Move `MessageRetentionManager(backupMessageDao).prune(config.id)` to a `finally` block (or unconditional tail of `runBackup`), separate from cloud retention. Table mustn't grow unbounded during long initial syncs.

### 2.6 Drive `getOrCreateChildFolder` paging
**File**: `app/src/main/java/ch/abwesend/foldervault/infrastructure/cloud/googledrive/GoogleDriveRepository.kt:92`
Page via `setPageSize(1000)` and walk `nextPageToken` until null when collecting matches, then pick the oldest by `(createdTime, id)`.

### 2.7 `BackupWorker` `AuthLost` → retry instead of fail
**File**: `app/src/main/java/ch/abwesend/foldervault/infrastructure/backup/BackupWorker.kt:73`
Return `Result.retry()` (handled by `WorkerErrorHandler` for the max-retry cap) so WorkManager backoff kicks in per §5.8.

### 2.8 Thread real `runId` into worker notifications
**Files**:
- `app/src/main/java/ch/abwesend/foldervault/infrastructure/backup/BackupRunner.kt`
- `app/src/main/java/ch/abwesend/foldervault/infrastructure/backup/BackupWorker.kt:55`

Add `runId` to `RunResult.Success` / `RunResult.AuthLost` / etc. Return it from `BackupRunner.runBackup`. Pass into `notificationManager.postProblemNotificationIfNeeded` and `clearResolvedThrottles`. Remove the comment "simplified — in v1.1, runId from RunSummary/RunResult".

### 2.9 Progress / problem notification ID collision risk
**File**: `app/src/main/java/ch/abwesend/foldervault/infrastructure/backup/BackupNotificationManager.kt`
Use a wide constant offset for problem IDs:
```kotlin
private fun problemId(configId: String) = (configId.hashCode() and 0x0FFFFFFF) or 0x10000000
```
Or use distinct channels' tag namespace via `notify(tag, id, ...)`.

### 2.10 Emit `ENCRYPTION_FAILED` typed message before throwing
**File**: `app/src/main/java/ch/abwesend/foldervault/infrastructure/backup/BackupRunner.kt:97`
Replace `?: error("Failed to decrypt backup password")` with: emit `BackupMessage(type = ENCRYPTION_FAILED, severity = ERROR, ...)` via `BackupMessageDao.coalesceInsert`, then return `RunResult.Failure(reason)`. Don't throw.

### 2.11 Per-file quota handling
**File**: `app/src/main/java/ch/abwesend/foldervault/infrastructure/backup/BackupUploader.kt:234`
Spec §5.11: only treat as terminal "when the condition clearly applies to the whole destination". Simplest fix: don't flip `summary.quotaExceeded = true` until the **second** consecutive `CloudQuotaExceededException`; skip the first as a per-file warning (emit `QUOTA_EXCEEDED` once, continue).

### 2.12 Honour `displayName` param or drop it
**Files**:
- `app/src/main/java/ch/abwesend/foldervault/domain/cloud/ICloudStorageProvider.kt`
- `app/src/main/java/ch/abwesend/foldervault/infrastructure/cloud/googledrive/GoogleDriveRepository.kt:59`

Spec mandates `FolderVault_<UUID>` root (CLAUDE.md). **Drop the `displayName` parameter** from `ICloudStorageProvider.createRootFolder` and all callers. The user's chosen display name lives only in the local `BackupConfig` row and the `.foldervault-meta.json` (added in 1.1).

### 2.13 Use `writeByte` instead of `write(int)` for byte fields
**File**: `app/src/main/java/ch/abwesend/foldervault/infrastructure/crypto/Fvc1Cipher.kt:99-106`
Replace `dos.write(FVC1_VERSION)` etc. with `dos.writeByte(FVC1_VERSION)`. Semantic only; output identical for values < 256.

### 2.14 Fix `verifyPassword` doc-string
**File**: `app/src/main/java/ch/abwesend/foldervault/infrastructure/crypto/EncryptionRepository.kt:79`
Remove the reference to `.foldervault-meta.json.crypt` (meta is plaintext). Replace with "any FVC1 file from the backup" (matches actual usage in `RestoreEngine`).

### 2.15 Remove dead `schedulePeriodicIfNeeded` overload
**File**: `app/src/main/java/ch/abwesend/foldervault/infrastructure/backup/BackupScheduler.kt:38-80`
Drop the `(config: BackupConfigEntity, ...)` overload; keep the one declared in `IBackupScheduler`. Removes ~40 lines of duplicated when/build/enqueue logic.

---

## Tier 3 — Duplication / abstraction

### 3.1 Push `@StringRes labelResId` onto enum members
**Affects 5 screens.**

Add `@StringRes val labelResId: Int` constructor parameter to:
- `domain/model/NetworkPolicy.kt`
- `domain/model/ChangedFilePolicy.kt`
- `domain/model/AppTheme.kt`
- `domain/model/MessageSeverity.kt`
- `domain/model/MessageType.kt`
- `domain/model/BackupRunStatus.kt`
- `domain/restore/RestoreCollisionPolicy.kt`

Keep `RetentionPolicy` (sealed, complex) and `BackupSchedule` (per-screen wording is deliberate per prompt-history §23) as-is.

Delete the local `labelResId()` extension functions in:
- `view/screens/HomeScreen.kt`
- `view/screens/SettingsScreen.kt`
- `view/screens/AddEditBackupScreen.kt`
- `view/screens/BackupDetailScreen.kt`
- `view/screens/RestoreScreen.kt`

Update `BackupUploader.resolveMessageText` and `FileSystemAnalyzer.kt:171` to call `context.getString(type.labelResId)` directly. **Delete** the `resolveMessageText` when-table.

**Note**: `domain/` may use `@StringRes` because it's an annotation only (`androidx.annotation`), which is allowed (no Android-runtime dependency). Verify `ArchitectureLayerTest` still passes; whitelist `androidx.annotation.*` if needed.

### 3.2 Extract enum preference helpers
**File**: `app/src/main/java/ch/abwesend/foldervault/infrastructure/settings/AppSettingsRepository.kt:34-64`

Add file-local helpers:
```kotlin
private inline fun <reified E : Enum<E>> Preferences.enum(key: Preferences.Key<String>, default: E): E =
    this[key]?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: default

private fun <E : Enum<E>> MutablePreferences.setEnum(key: Preferences.Key<String>, value: E) {
    this[key] = value.name
}
```
Replace all seven repeated blocks.

### 3.3 Extract `withStreams` helper in RestoreEngine
**File**: `app/src/main/java/ch/abwesend/foldervault/infrastructure/restore/RestoreEngine.kt:107-171`

One helper that opens input + (optionally) output via SAF, runs the block inside `use {}`, and centralises error logging. Apply to `verifyPassword`, `decryptEntry`, `copyEntry`.

### 3.4 Centralise `toDecryptionError`
**Duplicate in**:
- `app/src/main/java/ch/abwesend/foldervault/infrastructure/crypto/EncryptionRepository.kt:92`
- `app/src/main/java/ch/abwesend/foldervault/infrastructure/crypto/Fvc1Cipher.kt:110`

Move to a top-level function in `app/src/main/java/ch/abwesend/foldervault/domain/crypto/DecryptionError.kt` (or a small `Fvc1.classifyException`). Reference from both call sites.

### 3.5 Single `RetentionCountField` composable
**File**: `app/src/main/java/ch/abwesend/foldervault/view/screens/AddEditBackupScreen.kt:384-408`

Extract `RetentionCountField(value, onValueChange, @StringRes labelRes)`; both branches differ only in label + emitted subtype.

### 3.6 `RestoreViewModel` single state flow
**File**: `app/src/main/java/ch/abwesend/foldervault/view/viewmodel/RestoreViewModel.kt:29-45`

Collapse 6 `MutableStateFlow`s into one `MutableStateFlow<RestoreUiState>` data class. Update `RestoreScreen` to a single `collectAsState`. Matches the pattern already used by `AddEditBackupViewModel.form`.

### 3.7 Reuse `FolderPathCache.ensurePath` in analyzer
**File**: `app/src/main/java/ch/abwesend/foldervault/infrastructure/backup/FileSystemAnalyzer.kt:151-161`

Replace ad-hoc `resolveCloudFolderForPath` with a passed-in `FolderPathCache` instance (constructor or method param) so a single run doesn't pay get-or-create costs twice for the same path.

### 3.8 Pre-collect error-badge counts in `HomeViewModel`
**File**: `app/src/main/java/ch/abwesend/foldervault/view/screens/HomeScreen.kt:142`

Currently `viewModel.errorBadgeCount(config.id)` returns a fresh `Flow<Int>` on every recomposition. Expose `errorBadgeCounts: StateFlow<Map<String, Int>>` from `HomeViewModel`. `BackupConfigCard` receives an `Int` directly; no per-item Flow allocation.

---

## Tier 1 — Spec gaps (functional requirements missing in v1)

### 1.1 Write `.foldervault-meta.json` on backup creation — §6.1, §7.3
**File**: `app/src/main/java/ch/abwesend/foldervault/view/viewmodel/AddEditBackupViewModel.kt:173`

After creating the Drive folder in `createCloudFolder()`, build:
```kotlin
val meta = BackupMeta(
    version = 1,
    marker = "FolderVaultBackup",
    displayName = displayName,
    createdAt = Instant.now().toString(),
    encrypted = encryptionEnabled,
)
```
Upload it as **plaintext** (never encrypted) to the cloud root, filename `.foldervault-meta.json`. Use `kotlinx.serialization` (already wired). Use `cloudProvider.uploadFile` or whichever upload primitive is available.

### 1.2 Wire anonymous-error-reports toggle to Firebase — §7.5
**Architecture constraint**: Crashlytics imports may only live in `infrastructure/logging/CrashlyticsSink.kt`. Use indirection.

**Files**:
- New: `app/src/main/java/ch/abwesend/foldervault/domain/logging/ITelemetryToggle.kt` — interface with `fun setEnabled(enabled: Boolean)`.
- New: `app/src/main/java/ch/abwesend/foldervault/infrastructure/logging/FirebaseTelemetryToggle.kt` — calls both `FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(enabled)` and `FirebaseAnalytics.getInstance(context).setAnalyticsCollectionEnabled(enabled)`. **This file must be added to the `LoggingArchitectureTest` whitelist** (the test currently allows only `CrashlyticsSink`).
- Wire via Koin in `di/AppModule.kt`.
- `SettingsViewModel.setAnonymousErrorReports` (`view/viewmodel/SettingsViewModel.kt:36`): inject `ITelemetryToggle` and call `setEnabled(enabled)` after persisting.
- `FolderVaultApp.configureLogging()`: apply current setting at app start by reading `IAppSettingsRepository.settings.first().sendAnonymousErrorReports` and calling `telemetryToggle.setEnabled(...)`.

### 1.3 Expose default file-size limit in Settings UI — §5.5, §7.5
**File**: `app/src/main/java/ch/abwesend/foldervault/view/screens/SettingsScreen.kt`

Add an `OutlinedTextField` (numeric, unit: MB) bound to `SettingsViewModel.setDefaultFileSizeLimit`. New string `pref_default_file_size_limit_label` (+ helper text) in `strings.xml`. Convert MB ↔ bytes in the ViewModel. Place inside the existing settings list with an info-icon popup explaining the impact (per UX style memory).

### 1.4 Update cross-run progress so the home card shows real X/Y — §7.6
**Files**:
- `app/src/main/java/ch/abwesend/foldervault/infrastructure/backup/BackupRunner.kt`
- `app/src/main/java/ch/abwesend/foldervault/infrastructure/backup/BackupWorker.kt:37`

`BackupRunner.commitRunStats`: also call `BackupConfigDao.updateCrossRunProgress(configId, totalFilesDiscovered, filesUploadedTotal)`. Compute `totalFilesDiscovered` from the analyzer's full discovered count (thread it through `RunResult` or `RunSummary`); `filesUploadedTotal = previous + summary.filesUploaded`. **Reset both to 0** on a clean run with no `hitTimeBudget` (and on `INITIAL_SYNC_COMPLETE`).

`BackupWorker.kt:37`: read current cross-run counters from `BackupConfigDao.findById(id)` instead of passing `0, 0` to `createForegroundInfo`.

### 1.5 Emit `FILE_TOO_LARGE` and `INITIAL_SYNC_COMPLETE` messages — §5.5, §7.6, §8.1
**Files**:
- `app/src/main/java/ch/abwesend/foldervault/infrastructure/backup/BackupUploader.kt`
- `app/src/main/java/ch/abwesend/foldervault/infrastructure/backup/BackupRunner.kt`

`BackupUploader`: when `task.tier == OVERSIZED` and skipped, `coalesceInsert` a `FILE_TOO_LARGE` message (WARNING). Coalesces across the run.

`BackupRunner` after a successful clean run (no `hitTimeBudget`, `authLost`, or `quotaExceeded`): if the **previous** run had `INITIAL_SYNC_IN_PROGRESS` status, insert one `INITIAL_SYNC_COMPLETE` message (INFO) and reset cross-run progress counters.

### 1.6 List actual problems in notification text — §8.3
**File**: `app/src/main/java/ch/abwesend/foldervault/infrastructure/backup/BackupNotificationManager.kt`

`postProblemNotificationIfNeeded`: build the body from `pendingTypes` (already computed). Map each `MessageType` to a short phrase via a new `notification_problem_*` string per type, join with "; ". Pass into `R.string.backup_notification_problems_text` as a formatted arg (or build the entire string in code).

**Depends on Tier 3.1**: if `MessageType` has `labelResId`, reuse it (or add a separate `notificationLabelResId` if the wording must differ from the in-app message label).

---

## Tier 4 — Minor cleanups

### 4.1 `BackupUploader.cloudProvider` mutable public var
**File**: `app/src/main/java/ch/abwesend/foldervault/infrastructure/backup/BackupUploader.kt:42`
Pass `cloudProvider` as a constructor argument; remove the public mutable `var` set by `BackupRunner.kt:85`. Internal re-auth flow can wrap it in an `AtomicReference` or rebind via a `var` inside the uploader.

### 4.2 `FolderPathCache.kt:27-29` — double map lookup
Replace `containsKey + cache[key]!!` with `cache[key]?.let { currentId = it; continue }`.

### 4.3 `FileSystemAnalyzer.kt:33-34` — drop the `!!`
Use `val mtime = localMtime?.takeIf { it != 0L }`; branch on the resulting nullable.

### 4.4 `EncryptionRepository.kt:103-117` — drop test-only getters
Drop the `gcm*` / `pbkdf2*` / `aesKeySizeBits` getters; promote the underlying constants to `internal const val` and reference them directly from tests.

### 4.5 `EncryptionRepository.kt:124` — missing space
Change `tagLength:Int,` → `tagLength: Int,`.

### 4.6 `BackupNotificationManager.createForegroundInfo` — unused `configName`
**File**: `app/src/main/java/ch/abwesend/foldervault/infrastructure/backup/BackupNotificationManager.kt:60`
Remove the unused `configName` parameter; update call sites in `BackupWorker.kt:37,86`.

### 4.7 `BackupRunner.kt:174-175` — fully-qualified types
Import `javax.crypto.SecretKey` and `java.util.Base64`; drop fully-qualified names in signatures.

### 4.8 `BackupNotificationManager.kt:84` — cache filter
Cache `MessageType.entries.filter { it.notifies }` in a companion `val`.

### 4.9 `SettingsScreen.kt:59` — empty callback comment
Rename to `{ _ -> }` placeholder (no misleading "graceful" comment).

---

## Test discipline (per CLAUDE.md §12.0.1)

Each fix that changes logic must come with a test update or new test. Concretely:

- **2.1** `WorkerErrorHandler` — update `WorkerErrorHandlerTest` to assert `CancellationException` rethrows (replace the current "retry" case).
- **2.3** Manual back-up while paused — add a `BackupDetailViewModel` test that calls `backUpNow()` on a paused config and asserts no `scheduler.scheduleOneTime` call.
- **2.4** `KeepLastN(0)` — add a Kotest case that `require` throws.
- **2.11** Per-file quota — add a `BackupUploader` test that the first `CloudQuotaExceededException` only emits a message, the second flips `quotaExceeded = true`.
- **3.2** Enum prefs helper — extend `RoomDatabaseTest`/an `AppSettingsRepositoryTest` (new) to cover one round-trip per enum.
- **1.1** Meta write — add a test that `createCloudFolder` invokes the cloud provider's upload primitive with `.foldervault-meta.json`.
- **1.2** Telemetry toggle — `FakeTelemetryToggle` + a `SettingsViewModel` test asserting `setEnabled(true/false)` is called.
- **1.5** `FILE_TOO_LARGE` and `INITIAL_SYNC_COMPLETE` — extend `BackupUploader` and `BackupRunner` tests respectively.

## Definition of Done (per CLAUDE.md)

After each tier completes:
- [ ] `./gradlew assembleDebug` — no errors
- [ ] `./gradlew test` — all green
- [ ] `./gradlew detekt` — no new issues
- [ ] `docs/prompt-history.md` updated with a dated entry per tier (or per coherent slice)
- [ ] `CLAUDE.md` updated if a durable convention changed (e.g. addition of `ITelemetryToggle` whitelist in `LoggingArchitectureTest`)

---

## Rationale for ordering

- **Tier 2 first**: stabilises the foundation. Each item is small and isolated; little risk of conflict with later edits.
- **Tier 3 second**: refactors are mechanical and broad-reach. Doing them before Tier 1 means new functional code (Tier 1) lands on a cleaner base and gets to consume the new abstractions (e.g. Tier 1.6 reuses Tier 3.1's enum labels).
- **Tier 1 third**: behaviour additions. Largest blast radius — needs the stable base from Tiers 2/3.
- **Tier 4 last**: cosmetic-only. Doing it last avoids merge churn against earlier mechanical refactors that may already touch the same lines.

---

## Tier 5 — Verification follow-ups (added 2026-06-11)

After a verification pass on Tiers 1–4, the items below were found applied-but-flawed or violating the project's "no early returns unless preventing massive indentation" style rule. Execute these after the main tiers and before closing out the review.

### Style rule reminder

**The user strongly dislikes early-return statements** (`return`, `return null`, `return ""`, `?: return`, etc.). Prefer single-exit functions expressed via `if/else`, `when`, `?.let`, `?:` Elvis, `requireNotNull`, `check()`, or `BinaryResult.flatMap` chaining. Early returns are acceptable **only** when an `if/else` wrap would push the rest of the body to 4+ levels of indentation. Guard-style early returns at the top of short functions are NOT acceptable.

### 5.1 `WorkerErrorHandler` still swallows `CancellationException`
**File**: `app/src/main/java/ch/abwesend/foldervault/infrastructure/backup/WorkerErrorHandler.kt:17`
The Tier 2.1 fix removed the explicit `CancellationException` branch, but the broad `catch (e: Exception)` still catches it (`CancellationException` extends `RuntimeException` → `Exception`). The existing `WorkerErrorHandlerTest` will fail.

**Fix**: add an explicit re-throw branch *before* the `Exception` catch:
```kotlin
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    ...
}
```

### 5.2 Folder display-name helper still duplicated
**Files**:
- `app/src/main/java/ch/abwesend/foldervault/view/viewmodel/AddEditBackupViewModel.kt:286-290`
- `app/src/main/java/ch/abwesend/foldervault/view/screens/AddEditBackupScreen.kt:88`

The ViewModel-side helper added by Tier 2.2 fixed the garbled output, but the screen-side inline version at `AddEditBackupScreen.kt:88` remained and is missing the blank-guard (`takeIf { it.isNotBlank() }`).

**Fix**: extract `fun displayNameFromTreeUri(uri: Uri): String` into `infrastructure/storage/ScopedStorageHelper.kt` (or a new `view/util` file — domain is too pure for `Uri`). Both call sites should consume the helper.

### 5.3 Quota counter isn't actually "consecutive"
**File**: `app/src/main/java/ch/abwesend/foldervault/infrastructure/backup/BackupUploader.kt:236-245` (+ `RunSummary.kt:13`)

Tier 2.11 added `summary.consecutiveQuotaCount` but it's never reset when a non-quota upload succeeds between two quota errors. The name promises "consecutive" but the behaviour is "total quota errors in run".

**Fix (pick one)**:
- **Preferred**: reset `summary.consecutiveQuotaCount = 0` after a successful upload (i.e. at the end of the success path in `uploadOne`), so the counter genuinely tracks consecutive errors.
- **Or**: rename to `quotaErrorCount` and adjust the comment to match actual semantics.

### 5.4 Cross-run progress resets too eagerly
**File**: `app/src/main/java/ch/abwesend/foldervault/infrastructure/backup/BackupRunner.kt:289-295`

Tier 1.4 added the reset, but it fires on every non-`hitTimeBudget` run, including failed / quota-exceeded ones. Spec intent (per the plan): only reset on a *clean* run (no `authLost`, no `quotaExceeded`, no `hitTimeBudget`), or when emitting `INITIAL_SYNC_COMPLETE`.

**Fix**: narrow the reset condition to match the same gate already used for `INITIAL_SYNC_COMPLETE` emission (`cleanRun` flag). Move the reset call into the same `if (cleanRun) { ... }` branch as `INITIAL_SYNC_COMPLETE`.

### 5.5 Redundant `toDecryptionError` wrapper in `Fvc1Cipher`
**File**: `app/src/main/java/ch/abwesend/foldervault/infrastructure/crypto/Fvc1Cipher.kt:110` (+ call sites `:70`, `:81`)

Tier 3.4 centralised `classifyDecryptionError` in `domain/crypto/DecryptionError.kt`, but `Fvc1Cipher` kept a private wrapper `private fun toDecryptionError(e) = classifyDecryptionError(e)`. Pure noise.

**Fix**: inline the wrapper — replace both call sites (`:70`, `:81`) with direct calls to `classifyDecryptionError(...)` and delete the private function.

### 5.6 String-resource naming deviations
**Files**:
- `app/src/main/res/values/strings.xml:15-20` — `notif_problem_*` keys (plan said `notification_problem_*`)
- `app/src/main/res/values/strings.xml:200` — `label_default_file_size_limit` (plan said `pref_default_file_size_limit_label`)
- `app/src/main/java/ch/abwesend/foldervault/infrastructure/backup/BackupNotificationManager.kt:136` — join separator `", "` (plan said `"; "`)

Functional intent is met; the deviations are cosmetic. **Decision needed before fixing**: either rename to match the plan, or accept current naming as the new convention.

**Recommendation**: accept current naming (shorter, no behaviour change). If you choose to standardise, rename in one commit.

### 5.7 Early-return violations to refactor

**5.7.a** `app/src/main/java/ch/abwesend/foldervault/view/viewmodel/BackupDetailViewModel.kt:56`
Currently:
```kotlin
fun backUpNow() {
    if (config.value?.isPaused == true) return
    scheduler.scheduleOneTime(configId)
}
```
Refactor to:
```kotlin
fun backUpNow() {
    if (config.value?.isPaused != true) {
        scheduler.scheduleOneTime(configId)
    }
}
```

**5.7.b** `app/src/main/java/ch/abwesend/foldervault/view/viewmodel/AddEditBackupViewModel.kt:287`
Currently:
```kotlin
private fun displayNameFromTreeUri(uriString: String): String {
    if (uriString.isBlank()) return ""
    val uri = Uri.parse(uriString)
    return uri.lastPathSegment?.substringAfterLast(':')?.takeIf { it.isNotBlank() } ?: uriString
}
```
Refactor to single expression:
```kotlin
private fun displayNameFromTreeUri(uriString: String): String =
    if (uriString.isBlank()) ""
    else Uri.parse(uriString).lastPathSegment?.substringAfterLast(':')?.takeIf { it.isNotBlank() } ?: uriString
```
*(Note: when 5.2 extracts the helper to `ScopedStorageHelper`, apply the same single-exit shape there.)*

**5.7.c** `app/src/main/java/ch/abwesend/foldervault/view/viewmodel/RestoreViewModel.kt:76-77`
Currently:
```kotlin
fun startRestore(password: String) {
    val snapshot = _state.value
    val src = snapshot.sourceUri ?: return
    val out = snapshot.outputUri ?: return
    // ... rest of body
}
```
Refactor to a single guarded block:
```kotlin
fun startRestore(password: String) {
    val snapshot = _state.value
    val src = snapshot.sourceUri
    val out = snapshot.outputUri
    if (src != null && out != null) {
        // ... rest of body using src + out
    }
}
```
Function is only one indentation level deep before the change; the `if` block does not push code into "massive indentation" territory.

**5.7.d (borderline — decision needed)** `app/src/main/java/ch/abwesend/foldervault/infrastructure/backup/BackupRunner.kt:121`
Currently inside `runBackup`, deeply nested in `try` / `coroutineScope`:
```kotlin
if (encryptionKey == null) {
    backupMessageDao.coalesceInsert(BackupMessage(type = ENCRYPTION_FAILED, ...))
    return RunResult.FatalError(runId, "Failed to derive encryption key")
}
// ... rest of runBackup body
```
This is borderline: the function is already at 3–4 indentation levels, so wrapping the rest in `else { ... }` would push it to 4–5 levels. **Recommendation**: leave as-is and document the exemption with a brief comment, OR refactor `runBackup` to extract the post-key-derivation body into a private helper so both branches stay flat.

**Decision needed from the human** before executing 5.7.d.

### Tier 5 — Definition of Done

- [ ] All 5.1–5.5 items applied; 5.6 decided (rename or accept) and applied if renaming.
- [ ] 5.7.a, 5.7.b, 5.7.c refactored; 5.7.d resolved per human decision.
- [ ] `./gradlew assembleDebug && ./gradlew test && ./gradlew detekt` — green.
- [ ] `docs/prompt-history.md` updated with a Tier 5 dated entry.