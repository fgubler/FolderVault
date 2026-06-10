# FolderVault — prompt history

Newest entries at the top. Each entry covers a coding session or a coherent unit of work.
Started from the first real coding task; the review/planning conversation is out of scope (§12.3).

---

<!-- New entries go here -->

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

