# Build Prompt: "FolderVault" — an incremental folder-backup app for Android

You are building a **new, standalone Android app**. Its job is to back up a
user-selected local folder (and all of its subfolders, recursively) to a cloud destination
(Google Drive first), incrementally and efficiently, with optional client-side encryption.

**Starting point:** a fresh, empty "standard" Android Studio project already exists (Compose
template, `MainActivity`, default Gradle setup, version catalog). Do **not** scaffold a new
project or re-run any project wizard. Build on top of the existing project: add dependencies to
the existing version catalog / `build.gradle`, replace the placeholder `MainActivity` content,
and create the package structure below. Assume the standard template files are already present.

The app is a sibling of an existing app called **PrivateContacts**. We are **not** sharing a
library between the two apps; instead, several well-tested building blocks are **copied** from
PrivateContacts into this new project (with the package renamed). Those files are provided
verbatim at the end of this prompt under "Reused code from PrivateContacts". Copy them in,
adjust the package, and build on top of them.

Work like a senior Android engineer: produce compiling, idiomatic, tested Kotlin. Ask before
inventing requirements that are not covered here. Implement in vertical slices (data → domain →
worker → UI) and keep each slice compiling.

---

## 1. Product concept (put this in the README and the onboarding)

FolderVault continuously mirrors a local folder to the cloud as an **append-only archive**.
It is optimized for **files that rarely change** — photo libraries, document archives, scans,
exports — not for live working directories with constant edits.

**Strengths (state these to the user):**
- Set-and-forget: pick a folder once, new files get uploaded automatically.
- Incremental: only new or changed files are uploaded; unchanged files are never re-sent.
- Safe: it never deletes anything in the cloud. Deleting locally does not delete the backup.
- Private: optional client-side encryption — the cloud provider sees only encrypted bytes.

**Weaknesses / non-goals (state these honestly to the user in onboarding):**
- Not a two-way sync. It is a one-way push (local → cloud). It does not restore yet (restore is
  a future feature; design data models so restore is possible later).
- Not ideal for frequently-changing files: changed files are uploaded as **new timestamped
  copies**, so a file edited daily produces many cloud versions (a retention policy mitigates this).
- Detects changes by modification-time and size, not by content. A tool that rewrites a file
  without changing its size and mtime will not be detected.

---

## 2. Tech stack & conventions (hard requirements)

- **Kotlin 2.0+**, coroutines + Flow throughout.
- **Jetpack Compose** UI, **Material 3** components, dynamic color where available.
- **Navigation: Nav3** (the new `androidx.navigation3` type-safe navigation). Use a typed
  back stack of `@Serializable` destination objects.
- **Room** for persistence (the backup configs + the upload index).
- **DataStore (Preferences)** for app-wide settings (global defaults, onboarding-seen flag).
- **WorkManager** for scheduling and running backup jobs (survives reboot, OS-managed).
- **Koin** for dependency injection.
- **MVVM**: ViewModels expose `StateFlow`; Composables are stateless and hoist state.
- Use the copied **`BinaryResult<TValue, TError>`** type for any operation that can fail
  (mirror PrivateContacts' convention). Prefer `runCatchingAsResult { }`.
- Use an injected **`IDispatchers`** abstraction (copied) rather than hard-coding `Dispatchers.IO`.
- **Testing**: JUnit5 + MockK for unit tests. Write tests for the diff/change-detection logic,
  the encryption round-trip, the path-mirroring logic, and the retention logic at minimum.
- **UI / instrumentation-style tests run on the JVM via Robolectric** (`@RunWith(RobolectricTestRunner::class)`,
  `robolectric` + `androidx.compose.ui:ui-test-junit4` + `createComposeRule()`), so Compose
  screens (onboarding carousel, home list, add/edit flow) can be tested without a device/emulator.
  Note: Robolectric runs on JUnit4 — keep these in a JUnit4 source set alongside the JUnit5 unit
  tests (Jupiter for plain unit tests, Vintage/JUnit4 for the Robolectric/Compose tests).
- **Compose Preview screenshot rendering with the official CPST plugin**
  (`com.android.compose.screenshot`). This serves two purposes: UI regression tests AND — more
  importantly during development — a way for the coding agent to *see* what each screen looks
  like (see §11, "Coding-agent feedback loop"). Setup:
    - Add the `com.android.compose.screenshot` plugin (use the latest alpha version) and the
      `screenshotTestImplementation(androidx.compose.ui:ui-tooling)` dependency.
    - Enable `android.experimental.enableScreenshotTest=true` (in `gradle.properties` and the
      module's `experimentalProperties`).
    - Put screenshot-preview composables in the dedicated **`src/screenshotTest`** source set (CPST
      requires its own source set — distinct from the Robolectric/`test` set above).
    - For every screen and every notable state (empty home list, populated home list, each
      onboarding card, add/edit form states, detail screen, error/warning states) add a `@Preview`
      composable in that source set, exercising light & dark via `uiMode` where relevant.
    - Generate reference PNGs with `./gradlew updateDebugScreenshotTest`; validate later changes with
      `./gradlew validateDebugScreenshotTest`. Generated images land under the screenshotTest
      reference directory; the HTML report is at
      `app/build/reports/screenshotTest/preview/debug/index.html`.
    - The CPST plugin is **experimental/alpha** — pin exact versions in the catalog and, if a Gradle
      task fails due to a version-compatibility issue, web-search the current required AGP/Kotlin/JDK
      matrix rather than guessing.
- **Architecture tests with Konsist** (`com.lemonappdev:konsist`), **not** ArchUnit. Enforce layer
  boundaries (e.g. `domain` must not depend on `infrastructure` or `view`; cloud-provider
  specifics stay behind `ICloudStorageProvider`), naming conventions, and that fallible operations
  return `BinaryResult`. Run these as ordinary JVM tests.
- **Static analysis with Detekt** (`io.gitlab.arturbosch.detekt` Gradle plugin). Add a
  `detekt.yml` config, wire it into the build, and keep the codebase warning-clean. Prefer the
  Compose ruleset where available. Treat Detekt issues as build-relevant (at least report; ideally
  fail CI on new issues).
- Modern Android idioms only: sealed interfaces for UI state and results, data classes for
  models, extension functions, no deprecated APIs, no mutable state held in Composables.
- Suggested base package: `ch.abwesend.foldervault` (adjust if you prefer; be consistent).
- **Single build flavor** targeting the Play Store (no F-Droid/no-Google flavor) — the Drive
  backend needs Google Play Services. Crashlytics is always present, gated by the user setting (§9).

### 2.1 Starting version catalog (`gradle/libs.versions.toml`)

Use the catalog below as a **starting point**. **Before pinning, check whether a newer _stable_
release exists** for each entry (e.g. Google's Maven, Maven Central, the Gradle Plugin Portal, or
the library's release notes) and **prefer the newest stable version** — only fall back to a
pre-release (alpha/beta/rc) when no stable line exists yet (currently the case for CPST). Keep the
catalog as the single source of truth; do not hard-code versions in module `build.gradle` files.
After updating, run a Gradle sync/build and resolve any version-alignment issues (Kotlin ↔ Compose
compiler ↔ AGP especially).

The values below reflect the latest known-good versions as of mid-2026; treat them as a floor,
not a ceiling:

```toml
[versions]
# Build / language — verify the AGP↔Kotlin↔KSP↔Compose-compiler matrix matches
agp = "8.13.0"                  # check for newer stable AGP
kotlin = "2.2.10"               # check for newer stable Kotlin (drives Compose compiler + KSP)
ksp = "2.2.10-2.0.2"            # MUST track the Kotlin version
coreKtx = "1.16.0"
lifecycle = "2.9.0"
activityCompose = "1.10.1"

# Compose — prefer the BOM and let it govern artifact versions
composeBom = "2026.05.00"       # check for newer stable Compose BOM
material3 = "1.4.0"             # usually governed by the BOM; pin only if needed

# Navigation 3 (now STABLE — do not use a 0.x/dev build)
navigation3 = "1.0.0"           # check for newer stable (1.1.x line is in beta)
kotlinxSerialization = "1.8.0"  # for Nav3 type-safe routes + crypto payloads

# Persistence / background / DI
room = "2.7.0"                  # check for newer stable
datastore = "1.1.1"             # check for newer stable
workManager = "2.10.0"          # check for newer stable
koin = "4.0.0"                  # check for newer stable (koin-androidx-compose + koin-workmanager)

# Google Drive + auth
playServicesAuth = "21.3.0"     # com.google.android.gms:play-services-auth (AuthorizationClient)
googleApiClient = "2.7.0"       # com.google.api-client:google-api-client-android
driveApi = "v3-rev20240914-2.0.0"  # com.google.apis:google-api-services-drive — check for newer
credentials = "1.5.0"           # androidx.credentials (CredentialManager)

# Quality / analysis
detekt = "1.23.8"               # check for newer stable (2.0 is in pre-release as of now)
konsist = "0.17.3"              # check for newer stable

# Testing
junitJupiter = "5.11.0"         # JUnit5 for plain unit tests
junit4 = "4.13.2"               # required by Robolectric + Compose UI test
mockk = "1.14.0"
robolectric = "4.14"            # check for newer stable
coroutinesTest = "1.10.1"

# Compose Preview Screenshot Testing (EXPERIMENTAL/alpha — no stable line yet)
composeScreenshot = "0.0.1-alpha10"  # use the newest available alpha
```

> Notes: the exact pre-release suffixes (Compose BOM date, KSP suffix, CPST alpha, Drive API rev)
> change frequently — look them up rather than trusting the strings above verbatim. The hard
> constraint is internal consistency: Kotlin, the Compose compiler plugin, KSP, and AGP must be a
> compatible set; if a build fails on a "Compose Compiler / Kotlin version" error, fix that
> alignment first.

---

## 3. Architecture overview

Layered, with the cloud provider fully abstracted so a second provider can be added later
without touching domain or UI:

```
view/        Compose screens, ViewModels, navigation (Nav3)
domain/      models, service interfaces, use-cases, BinaryResult, result extensions
infrastructure/
  room/                 entities, DAOs, database, type-converters, migrations
  settings/             DataStore-backed settings repository
  crypto/               EncryptionRepository, AndroidKeyStoreRepository (copied + extended)
  storage/              SAF / DocumentFile traversal of the local source tree
  cloud/                ICloudStorageProvider (abstraction) + googledrive/ implementation
  backup/               scheduler, worker(s), the analyzer→queue→uploader pipeline
```

### 3.1 The cloud abstraction (critical)

Define a provider-agnostic interface in `domain` and keep all Google-specific types behind it.
This generalizes PrivateContacts' `IGoogleDriveRepository` to support nested folders and
explicit remote filenames:

```kotlin
interface ICloudStorageProvider {
    suspend fun getAccountIdentifier(): BinaryResult<String, Exception>      // e.g. email

    /** Create the root backup folder; returns provider id + display name. */
    suspend fun createRootFolder(displayName: String): BinaryResult<CloudFolder, Exception>

    /** True if the folder still exists and is writable. */
    suspend fun hasFolderAccess(folderId: String): BinaryResult<Boolean, Exception>

    /** Get-or-create a child folder under [parentId] by name (used to mirror the local tree). */
    suspend fun getOrCreateChildFolder(parentId: String, name: String): BinaryResult<CloudFolder, Exception>

    /** List immediate children (files + folders) of a folder. Used both to mirror/reconcile the
     *  tree and to power the "pick an existing folder" browser at setup (§7.3). */
    suspend fun listChildren(folderId: String): BinaryResult<List<CloudEntry>, Exception>

    /**
     * Upload a local payload into [parentId] under the EXPLICIT [remoteName]
     * (caller controls the name: handles .crypt suffix and timestamp-on-change suffix).
     * Stream from [content] so large files don't load fully into memory.
     */
    suspend fun uploadFile(
        parentId: String,
        remoteName: String,
        mimeType: String,
        content: UploadContent,        // wraps an InputStream provider + known length if available
    ): BinaryResult<CloudFile, Exception>

    /** Read/write the small plaintext manifest sidecar (§5.10). Read returns null if absent.
     *  The manifest is plaintext JSON even for encrypted backups (names/structure are already
     *  visible in the mirrored tree, so it leaks nothing extra — see §5.10). */
    suspend fun readManifest(rootFolderId: String): BinaryResult<ByteArray?, Exception>
    suspend fun writeManifest(rootFolderId: String, bytes: ByteArray): BinaryResult<Unit, Exception>

    suspend fun deleteFile(fileId: String): BinaryResult<Unit, Exception>     // retention + manifest replace
}
```

Provide a separate `ICloudAuthorizer` abstraction (generalize
`IGoogleDriveAuthorizationRepository`) returning an authorized `ICloudStorageProvider` or a
`ConsentRequired(PendingIntent)` result for the silent-auth-fails case.

The **Google Drive implementation** is adapted directly from the copied
`GoogleDriveRepository` / `GoogleDriveAuthorizationRepository` (see reused code). Extend it with
`getOrCreateChildFolder` (query `'<parent>' in parents and name = '<n>' and
mimeType = 'application/vnd.google-apps.folder' and trashed = false`, create if absent) and the
streaming `uploadFile(parentId, remoteName, …)`. Keep the modern `AuthorizationClient` +
`DriveScopes.DRIVE_FILE` scope and the `UUID`-suffixed unique root-folder name.

---

## 4. Data model (Room)

### 4.1 `BackupConfig` (one row per backup the user sets up)
- `id` (UUID string, PK)
- `displayName: String`
- `sourceTreeUri: String` — the SAF tree Uri from the folder picker (persisted read permission)
- `cloudProvider: String` — enum-as-string, e.g. `GOOGLE_DRIVE`
- `cloudRootFolderId: String`, `cloudRootFolderName: String`
- `cloudAccountIdentifier: String` — e.g. account email
- `schedule: BackupSchedule` — enum: `MANUAL_ONLY`, `DAILY`, `WEEKLY`, `MONTHLY`
  (null/absent ⇒ fall back to the global default schedule from settings)
- `changedFilePolicy: ChangedFilePolicy` — enum: `DUPLICATE_WITH_TIMESTAMP` (default),
  `OVERWRITE`, `IGNORE`. **Global default = `DUPLICATE_WITH_TIMESTAMP`.**
- `encryptionEnabled: Boolean`
- `encryptedPasswordBlob: String?` — the per-backup password, wrapped via the KeyStore key
  (see §6). Never store the password in plaintext.
- `retentionPolicy: RetentionPolicy` — see §5.6; default `KEEP_ALL` (disabled).
- `networkPolicy: NetworkPolicy` — enum: `WIFI_ONLY` (default) or `ANY`.
- `createdAt`, `lastRunAt: Long?`, `lastRunStatus` (enum), counters for last run
  (filesUploaded, filesSkipped, filesFailed, bytesUploaded).

### 4.2 `UploadedFileIndex` (the heart of incremental sync — one row per uploaded local file)
This is the **durable source of truth** for "what have we already uploaded and in what state".
We never trust the cloud listing for change-detection (see §6 on why encryption makes remote
hashing useless).
- `id` (autogenerated PK)
- `backupConfigId: String` (FK, indexed)
- `relativePath: String` — path of the file relative to the source-tree root, POSIX-style
  (`sub/dir/report.pdf`). **This is the file identity** across runs.
- `localLastModified: Long` — mtime recorded at last successful upload
- `localSize: Long` — size recorded at last successful upload
- `cloudFileId: String` — provider id of the uploaded object
- `remoteName: String` — the actual name used in the cloud (may carry `.crypt` and/or a
  timestamp suffix)
- `uploadedAt: Long`
- `isCurrentVersion: Boolean` — for timestamp-duplication: the latest uploaded version of this
  relativePath. Older versions are kept as rows for retention bookkeeping. **Maintain this
  atomically:** when a new version is recorded for a `(backupConfigId, relativePath)`, flip the
  previous current row's flag to `false` and insert/mark the new row `true` **in a single Room
  transaction**, so a crash can never leave zero or two "current" rows. Applies to both
  `OVERWRITE` and `DUPLICATE_WITH_TIMESTAMP`.
- Unique index on `(backupConfigId, relativePath, uploadedAt)`; consider a partial/unique guard so
  at most one row per `(backupConfigId, relativePath)` has `isCurrentVersion = true`. Query helper
  to fetch the current version row for a `(backupConfigId, relativePath)`.

Provide DAOs with the queries the pipeline needs (lookup current version by relativePath, list
all versions for a relativePath ordered by uploadedAt, list distinct relativePaths for a config).
Include a `DatabaseMigrations` scaffold and bump-able schema version following PrivateContacts'
pattern.

### 4.3 `BackupMessage` (Room-backed — see §8 for the full messaging design)
PrivateContacts kept a short list of error/warning strings in DataStore to show at next startup.
Here, messages are a **central observability feature** for an app that mostly runs unattended, so
they live in Room (queryable, per-backup, with read/dismiss state and history). Full behavior in §8; the entity:
- `id` (autogenerated PK)
- `backupConfigId: String?` (FK, indexed; null = app-global message)
- `runId: String?` — groups all messages produced by one worker run (for coalescing)
- `timestamp: Long`
- `severity: MessageSeverity` — `INFO` | `WARNING` | `ERROR` | `CRITICAL`
- `type: MessageType` — a programmatic enum (e.g. `AUTH_LOST`, `FOLDER_UNREADABLE`,
  `FILE_TOO_LARGE`, `UPLOAD_FAILED`, `ENCRYPTION_FAILED`, `INITIAL_SYNC_COMPLETE`,
  `QUOTA_EXCEEDED`, `UNRELIABLE_TIMESTAMPS`, …). Each `type` carries a constant `notifies: Boolean`
  (see §8.2) so messaging and notification routing are driven by type, not free text.
- `messageText: String?` — the **already-resolved** display text (resolved at write time), and/or
  rely on the `type` enum + `formatArgs` to build the text **at display time**. Do **NOT** persist a
  raw `@StringRes Int`: Android resource IDs are not stable across app builds, so a stored id can
  resolve to the wrong (or no) string after an update. Store resolved text or the enum+args, never
  the res id.
- `formatArgs: List<String>` — structured args for rendering a `type`-based message at display time.
- `relativePath: String?` — for file-specific issues.
- `count: Int` (default 1) — for coalesced messages ("12 files failed to upload") rather than
  thousands of rows.
- `readAt: Long?`, `dismissed: Boolean` (default false).
- Indices on `(backupConfigId, timestamp)` and `(backupConfigId, dismissed)`.

DAO: observe (Flow) unread/undismissed messages per backup and app-wide; insert/coalesce within a
run; mark read/dismissed (individually and "mark all for backup"); count by severity; and a prune
query for retention (see §8.4).

### 4.4 `NotificationThrottleState` (small table to dedupe repeat alerts across runs)
- `key: String` (PK) — e.g. `"$backupConfigId:$messageType"`.
- `lastNotifiedAt: Long`, `lastRunId: String?`.
  Used by §8.3 so a persistent condition (e.g. `AUTH_LOST` every run) alerts once, not every run.

---

## 5. The backup pipeline (analyzer → bounded queue → uploader)

Implemented inside a single `CoroutineWorker` invocation (so it survives reboot via WorkManager
and runs in the background with a foreground notification for long runs). The in-memory queue is
**not** persisted — durability comes from the Room `UploadedFileIndex`: on the next run the
analyzer simply recomputes the diff from the filesystem against the index, so a crash just means
"redo the diff", never "lose track". This satisfies crash-safety without a persisted queue.

### 5.1 Producer/consumer over a bounded channel — but **upload strictly serially**

```
analyzer coroutine  ──►  Channel<UploadTask>(capacity = N)  ──►  single uploader coroutine
```

> **Design priority: completed units of work over throughput.** Unlike PrivateContacts (where the
> backup file is already encrypted on disk and the worker only uploads), this app must **encrypt
> each file as part of the run**. The OS can cancel a `CoroutineWorker` at any moment (timeout,
> doze, memory pressure, reboot). Therefore we deliberately **do NOT parallelize uploads**: one
> file fully encrypted, uploaded, and indexed is permanent progress; five files each half-uploaded
> is wasted work that must restart. The uploader processes **one file at a time, start to finish**,
> and commits its index row before taking the next. This makes progress monotonic and crash-safe
> at single-file granularity.

- **Analyzer** (producer): recursively walks the SAF tree with `DocumentFile`
  (`DocumentFile.fromTreeUri(...)` then depth-first; each `listFiles()` is a ContentResolver
  round-trip, so traversal is the slow part — start emitting tasks as soon as the first files are
  discovered rather than after the whole walk). For each file it decides new / changed / unchanged
  by comparing `(lastModified, size)` against the `UploadedFileIndex` current-version row for that
  `relativePath`:
    - **new** (no index row) → emit an upload task (mode = NEW)
    - **changed** (row exists, mtime newer OR size differs) → emit task per `changedFilePolicy`:
        - `DUPLICATE_WITH_TIMESTAMP` → upload as a new timestamped name (mode = CHANGED_DUPLICATE)
        - `OVERWRITE` → upload, then delete the old cloud object *after* success (mode = CHANGED_OVERWRITE)
        - `IGNORE` → skip
    - **unchanged** → skip (never re-upload)
    - **Unreliable `lastModified` (SAF caveat).** Some document providers (SD cards, USB-OTG, certain
      cloud-backed providers) return `null`, `0`, or a bogus mtime from `DocumentFile.lastModified()`.
      Treat **`null` and `0` as "mtime unavailable"** (do not compare them as a real timestamp — `0`
      is 1970 and would mark every file changed/unchanged incorrectly). Fallback logic when mtime is
      unavailable for a file:
        1. Compare **size only** against the index row. Different size ⇒ changed; same size ⇒ provisionally unchanged.
        2. When size is also inconclusive (same size but no usable mtime, no index row yet, or first
           reconcile), use a **cloud-existence check** (`listChildren` of the mirrored parent, match by
           the deterministic remote name incl. `.crypt`) as the tiebreaker: if the expected remote
           object already exists, treat as unchanged; otherwise upload.
        3. **Warn the user** (a `WARNING` `BackupMessage`, e.g. type `UNRELIABLE_TIMESTAMPS`) that this
           source reports unreliable modification times, so change-detection on it is size-based and may
           miss in-place edits that don't change file size. Warn once per run per backup, not per file.
    - It sends into the bounded channel; a full channel suspends the analyzer (natural backpressure —
      prevents the "10,000 files at once → crash" failure mode without manual batching).
    - **Deletions are ignored**: if an indexed relativePath no longer exists locally, do nothing
      (never delete remotely). Optionally flag it in the index for a future UI hint.
- **Two-tier ordering so one giant file never head-of-line-blocks the rest.** Each task is tagged
  **normal** (size ≤ the per-file size limit, §5.5) or **oversized** (size > the limit). The
  uploader drains **all normal tasks first, serially, then all oversized tasks, serially**. Bulk
  small/normal files thus complete quickly and durably; the risky large files are attempted last,
  so if the worker is killed mid-giant-file, everything before it is already committed and the next
  run resumes at the giant file. (Still strictly serial — ordering, not concurrency, solves the
  blocking concern; concurrency would reintroduce the "partially uploaded" waste and spike
  memory/temp usage exactly when the OS is most likely to kill us.) Simplest correct
  implementation: analyzer routes into two channels (or the uploader processes the normal channel
  to completion, then the oversized one); within each tier, natural discovery order is fine.
- **Uploader** (single consumer): for each task, in order:
    1. Ensure the mirrored remote subfolder chain exists (get-or-create per path segment, cached
       within the run — §5.3).
    2. **If encryption is on, encrypt to a temp file in app-private staging** (§6, §5.2): stream
       plaintext → `CipherInputStream` → `<staging>/<uuid>.crypt`. If off, the upload streams
       straight from the source `DocumentFile`'s `InputStream`.
    3. Upload via `ICloudStorageProvider.uploadFile(parentId, remoteName, mimeType, content)`,
       streaming from the temp file (or source stream).
    4. On success, write/update the `UploadedFileIndex` row in a transaction; on `CHANGED_OVERWRITE`,
       delete the previous cloud object **after** the new upload succeeds (never before).
    5. **Always delete the temp file in a `finally`**, whether the upload succeeded, failed, or threw.

Close the channel(s) when the analyzer finishes; let the uploader drain; collect a run summary
(uploaded / skipped / failed / bytes / oversized-warned) and persist it onto the `BackupConfig`.

### 5.2 Staging directory & crash cleanup (because encryption happens in-process)

- Use a dedicated app-private staging root, e.g. `context.cacheDir/encrypt-staging/` (or
  `filesDir/...`). Nothing durable ever lives here — it only holds in-flight encrypted scratch.
- **Each run gets its own sub-directory named with both a UUID and a date**, e.g.
  `encrypt-staging/<runId-uuid>_<yyyy-MM-dd>/`. The run encrypts its temp `.crypt` files there and
  deletes each one in a `finally` (§5.1 step 5) during normal operation.
- **Cleanup is age-based, run at worker start (not a blanket wipe):** scan the staging root and
  delete only sub-directories whose embedded date is **≥ 2 days old**. This removes leftovers from
  runs the OS killed mid-encrypt, while **never touching a concurrent or very recent run's dir** —
  important because a blanket "wipe everything on start" could corrupt another in-flight run. (Per-
  config work is already serialized in §5.7; the per-run dir + 2-day age rule additionally guards
  cross-config overlap and clock-edge cases.) The current run always works in its own fresh dir.
- Stream both encryption and upload (flat memory regardless of file size). Do **not** read whole
  files into RAM. Always stage to a temp file (single code path — no in-RAM special case for small
  files).
- For unencrypted uploads, prefer streaming directly from the `DocumentFile` `InputStream` (no temp
  copy needed) unless the provider API requires a `java.io.File`/known length, in which case stage
  to a plain temp copy and clean it up the same way.

### 5.3 Remote folder structure — **mirror the local tree**
For a local file at `sub/dir/report.pdf`, ensure cloud folders `sub` then `dir` exist under the
backup root, and place the file in `dir`. Cache `relativeFolderPath → cloudFolderId` within a run
to avoid repeated get-or-create calls.

### 5.4 Naming rules
- Unchanged base name when first uploaded: `report.pdf` (or `report.pdf.crypt` if encrypted).
- Changed file under `DUPLICATE_WITH_TIMESTAMP`: insert an ISO-8601 **filename-safe** UTC
  timestamp before the extension chain, e.g.
  `report__2026-05-31T14-30-00Z.pdf` (and `…​.pdf.crypt` when encrypted). Do **not** use `:` —
  use `-` in the time component. Keep the original extension so the file stays openable.

### 5.5 Per-file size limit (ordering + warning, never a skip)
- A single **global default size limit** lives in DataStore (default ≈ **256 MB**; per-backup
  override is a future enhancement — design the config/settings so it can be added without
  migration pain). Expose it in settings.
- The limit is a **soft signal**, not a hard cap: files over it are **still uploaded**, just placed
  in the **oversized tier** (§5.1) so they run last and don't block the rest, and the user is
  **warned** in the run summary / detail screen (e.g. "3 large files (>256 MB) may need several
  runs to finish"). A file is **never silently skipped** because of size.
- Rationale: a WorkManager window is bounded (~10 min), and without resumable uploads a file too
  large to finish in one window will restart each run. The warning sets expectations; the ordering
  ensures it doesn't starve everything else.

### 5.6 Retention policy (per backup, default disabled)
`RetentionPolicy`: `KEEP_ALL` (default) | `KEEP_LAST_N(n)` | `KEEP_NEWER_THAN(days)`.
After a successful run, for each `relativePath` with multiple versions, delete the cloud objects
(and index rows) that fall outside the policy, keeping at least the current version. Only this
retention step is ever allowed to delete from the cloud.

### 5.7 Scheduling
Adapt the copied `BackupScheduler`/WorkManager pattern:
- A **periodic** unique work per active backup config (or a single periodic worker that iterates
  active configs — your call; if per-config, key the unique work name by config id). Honor each
  config's `schedule`, falling back to the global default from DataStore.
- Constraints from `networkPolicy`: `WIFI_ONLY` ⇒ `NetworkType.UNMETERED`, `ANY` ⇒
  `NetworkType.CONNECTED`; plus `setRequiresBatteryNotLow(true)`, `setRequiresStorageNotLow(true)`.
- A **one-time** "Back up now" trigger per config.
- **Serialize all work for a given config onto one unique work name/chain** so a periodic run and a
  manual "Back up now" can never run concurrently for the same backup (concurrent runs would race
  on the staging dir and the index). Use the same unique name for both, with an appropriate
  `ExistingWorkPolicy`/`ExistingPeriodicWorkPolicy`; different configs may run independently.
- Reuse the copied `WorkerErrorHandler` for retry/cancellation handling and a foreground
  notification (progress: "Uploading 240 / 5,300 files").

### 5.8 Finishing a large initial sync across the run-window limit (continuation)
A WorkManager run (incl. a long-running foreground worker) is bounded (~10 min on modern Android).
An initial backup of thousands of files **will not finish in one window** — and waiting for the
next *periodic* slot (hours/a day later) would make the initial sync take days. So the worker must
re-enqueue itself promptly when it hits the limit with work remaining:
- Track time/units within the run. When the analyzer/uploader still has tasks but the run is near
  its time budget, **stop cleanly at a committed-file boundary** (never mid-file) and finish the
  run as **"made progress, incomplete"**.
- For that case, **re-run soon** rather than waiting for the periodic cadence: either return
  `Result.retry()` (WorkManager re-enqueues with backoff — initial backoff is short, ~10–30 s) or
  enqueue a fresh **expedited one-time** continuation of the same unique work. Prefer whichever
  keeps the gap short; the goal is to drain the initial sync over **minutes/hours, not days**.
- **Distinguish "incomplete-but-healthy" from "failing".** Genuine failures (auth lost, folder
  unreadable, repeated upload errors) use normal retry/backoff and may grow the interval; a healthy
  "ran out of time, made progress" should come back promptly and **not** let exponential backoff
  starve it. (E.g. reset/῾cap the effective backoff for the progress case, or use a fresh expedited
  request rather than `retry()` so backoff doesn't accumulate.)
- A run that finds nothing left to do returns `Result.success()` and reverts to the normal periodic
  schedule. This is safe because each file is durably committed (§5.1) and the analyzer's diff skips
  everything already indexed, so continuations are cheap and monotonic.

### 5.9 Index reconciliation against the cloud (reinstall / new device / cleared data)
The `UploadedFileIndex` is local Room data. If it is **empty but the cloud root already contains a
prior backup** (reinstall, new phone, "clear data"), the analyzer must **not** treat every file as
new and re-upload the whole archive (that would duplicate everything). Before the first normal diff
for such a config, run a **one-time reconciliation**:
- Prefer the **cloud manifest** (§5.10) if present: read it to rebuild index rows exactly
  (`relativePath`, original `mtime`, `size`, `cloudFileId`, `remoteName`) — exact, no re-upload.
- If no manifest (or it's unreadable): reconcile by **matching mirrored remote names** under each
  folder. Remote names are deterministic (`name.ext`, plus `.crypt` when encrypted; changed-file
  copies carry the timestamp suffix — the **current** version is the suffix-less / newest one).
  For each matched local file, **backfill the index row's `mtime`/`size` from the current local
  file** (assume unchanged since a cloud copy exists) so the next run won't needlessly re-upload.
  Files present locally but not in the cloud are genuinely new → upload.
- This pairs with letting the user **target an existing Drive folder** at setup (§7.3) instead of
  always creating a fresh one.

### 5.10 Cloud manifest (sidecar for exact reconciliation + future restore)
Maintain a small **manifest object inside the backup's cloud root** (e.g. `.foldervault-manifest`)
that mirrors the index: per current file, `relativePath`, `mtime`, `size`, `cloudFileId`,
`remoteName`, plus a schema version.
- **Write it last, after all uploads in a run succeed**, so it never references objects that didn't
  finish. Rewriting a small JSON each run is negligible next to the data.
- **Privacy: the manifest is plaintext JSON in all cases — it does NOT need to be encrypted.** It
  contains the original file names, folder structure, plus per-file `mtime`/`size` — but the
  mirror-tree design already exposes the names and structure in Drive in plaintext (e.g.
  `sub/dir/report.pdf.crypt` reveals the path and base name regardless of encryption; this is the
  accepted "encrypt content, not names" trade-off of §6). The only extra metadata is the plaintext
  file's size/mtime, which is very low-sensitivity and far less revealing than the filenames that
  are already visible. So the manifest leaks nothing beyond what listing the cloud folder would, and
  keeping it plaintext keeps reconciliation simple (no password needed just to read it).
- The manifest is the durable cloud-side mirror of the index and the basis for exact reconciliation
  (§5.9) and a future restore feature. If it is missing/corrupt, the app degrades gracefully to
  filename-matching reconciliation.

### 5.11 Cloud error handling & resilience
The provider implementation and the uploader must handle the realities of a long-running cloud sync:

- **Duplicate-named folders (Drive get-or-create race).** Drive permits multiple folders with the
  same name under one parent, and an interrupted `getOrCreateChildFolder` can create duplicates
  across runs. So: the "get" query may return **more than one** match — **pick a deterministic
  winner** (e.g. the oldest by `createdTime`, tie-broken by lexicographically smallest id) and use
  it consistently; never error on duplicates. Only create a new folder when the query returns none.
  (This keeps the mirrored tree stable even if a prior run was killed mid-folder-creation.)
- **Access-token expiry mid-run.** The `AuthorizationClient` token is short-lived (~1h); a
  multi-hour initial sync will outlive it and uploads will start returning **401**. On a 401 (or
  equivalent auth error), the uploader should **attempt a silent re-authorization once**
  (`authorize()` → if `Authorized`, rebuild the provider with the fresh token and retry the current
  file), and only if that fails surface `AUTH_LOST` and end the run for re-scheduling. Do not fail
  the whole run on the first 401.
- **Rate limiting (HTTP 429 / `userRateLimitExceeded`, `rateLimitExceeded`).** Common at scale.
  Apply **exponential backoff with jitter** and retry the current file a bounded number of times
  before giving up on it. (The copied `WorkerErrorHandler` only handles cancellation — add real
  backoff for transient HTTP errors, either here or by extending it.)
- **Quota exceeded / storage full (`storageQuotaExceeded`) and persistent rate limiting.** When the
  destination is full (or rate limiting persists beyond the retry budget): **warn the user once per
  run** (a single `QUOTA_EXCEEDED` / rate-limit `BackupMessage`, not one per file — this notifies
  per §8.2), then **skip the affected file** and continue; if the condition clearly applies to the
  whole destination (storage full), **stop the rest of the run** gracefully (it will resume/retry
  on a later run once the user frees space). Never spam a message per failed file — coalesce.
- Distinguish **transient** (retry: 429, 5xx, network blips, single 401) from **terminal-for-now**
  (stop/skip: quota full, folder unreadable, repeated auth failure) errors, and route each to the
  right retry/skip/stop + messaging path.

### 5.12 Explicit non-goals for v1 (future enhancements)
- **No resumable/chunked uploads.** A large file killed mid-upload restarts from zero next run; the
  size limit (§5.5) + warning manage expectations. Drive *does* support resumable upload sessions —
  note this as a future enhancement (persist session URI + committed offset per in-flight file) but
  do **not** implement it now.
- **No parallel uploads.** Serial-only by design (see §5.1).
- Per-backup size-limit override and a restore/download path are future work; keep data models
  forward-compatible with them (the manifest of §5.10 is designed to enable restore later).

---

## 6. Encryption (content only; names stay, plus `.crypt`)

Copy `EncryptionRepository` and `AndroidKeyStoreRepository` from PrivateContacts (see reused
code) and **keep these unchanged**:
- Password storage: the per-backup password is wrapped with the AES-256-GCM key from the
  Android KeyStore (`encryptPassword`/`decryptPassword`, the JSON `EncryptedPasswordPayload`).
  Store only the wrapped blob in `BackupConfig.encryptedPasswordBlob`. Use a distinct KeyStore
  alias for this app.
- Key derivation parameters for file encryption: **AES-256-GCM**, **PBKDF2WithHmacSHA256**,
  **310,000 iterations**, 16-byte random salt, 12-byte random IV, 128-bit GCM tag.

**Extend it for binary files (this is new — the original only encrypts `String`):**
The current `encrypt(plaintext: String, password)` produces a JSON envelope with base64 fields.
That is fine for small text but unsuitable for arbitrary/large binary files (base64 bloat, no
streaming). Add a **streaming binary `.crypt` container**:

- Encrypt **raw bytes**, not UTF-8 strings.
- File layout (all multi-byte integers big-endian):
    - magic: 4 bytes `"FVC1"`
    - version: 1 byte (= 1)
    - kdf id: 1 byte (= 1 for PBKDF2WithHmacSHA256)
    - iterations: 4-byte int (= 310000)
    - salt length: 1 byte, then salt bytes (16)
    - iv length: 1 byte, then iv bytes (12)
    - then the AES-256-GCM ciphertext stream (GCM tag appended by the cipher) to EOF
- Encrypt by deriving the key (same PBKDF2 as the copied code), init `Cipher` in GCM mode, and
  stream the local file through a `CipherInputStream`/`CipherOutputStream` so memory stays flat
  for large files.
- Decrypt (for the future restore feature) reverses this: read header, derive key from the
  supplied password, stream-decrypt. An invalid password surfaces as `AEADBadTagException` ⇒ map
  to a typed `DecryptionError.INVALID_PASSWORD`, mirroring the existing `decrypt` error mapping.
- Remote naming when encrypted: append `.crypt` to the (otherwise unchanged) filename; the
  mirrored folder names are **not** encrypted. Use a generic binary mime type for the upload
  (e.g. `application/octet-stream`).

Same-plaintext-encrypts-differently (random IV per run) is expected and fine: it means we must
**never** use a remote content hash for change-detection. The local `UploadedFileIndex`
`(mtime, size)` is the only change signal. Make this explicit in a code comment.

Encryption happens **in the uploader, per file, as part of the run** (unlike PrivateContacts,
where the ciphertext already existed on disk). Always stream plaintext through a `CipherInputStream`
into a temp `.crypt` file in the app-private staging dir, then upload that temp file, then delete it
in a `finally` — see §5.1 (serial, one-file-at-a-time) and §5.2 (staging dir + startup wipe for
crash cleanup). Never load whole files into RAM; there is no in-RAM fast path.

---

## 7. UI / UX

Material 3, modern and appealing, dynamic color, light/dark/system theme. Nav3 typed navigation.

### 7.1 First-launch onboarding — **swipeable card carousel** (HorizontalPager)
Shown once (persist a `onboardingComplete` flag in DataStore; allow re-opening from settings).
Clean, classic, with page indicators and a Skip / Next / Get-started flow. Cards must cover:
1. What it does — incremental folder backup to your cloud.
2. **Best for files that rarely change** (photos, scans, document archives).
3. How incremental upload works — only new/changed files are sent; unchanged files never re-sent.
4. **Honest limitations** — one-way push (no restore yet), changed files create timestamped
   copies, change-detection is by date+size not content, never deletes from the cloud.
5. Privacy — optional client-side encryption; the cloud sees only encrypted bytes; if you forget
   the password the backup cannot be recovered (no reset).
6. **Stay informed** — because the app runs in the background, ask for notification permission here
   (`POST_NOTIFICATIONS`, Android 13+) with the rationale that it lets the app warn you if a backup
   stops working (§8.4). Degrade gracefully if denied; offer re-prompt from settings.

### 7.2 Home screen — list of active backups
- Each backup is a **card** showing: display name, source folder (friendly name), cloud
  destination (provider + account + folder), schedule, encryption on/off, and a **state line**
  (see §7.6). For a settled backup that reads as a last-run summary
  (status icon, "1,204 files • last run 2h ago • 3 failed"); for one still completing its initial
  sync it instead reads as ongoing progress ("Initial backup: 2,310 / 9,840 files • continues in
  the next run"). Tapping opens the detail screen.
- **FloatingActionButton** to add a new backup → launches the add/edit flow.
- Empty state when no backups exist, pointing at the FAB.

### 7.3 Add / edit backup flow
- Pick **source folder** (SAF `ACTION_OPEN_DOCUMENT_TREE`, take persistable read permission).
- Set up **cloud destination**: trigger authorization (handle the `ConsentRequired` `PendingIntent`
  via an `ActivityResultLauncher`), then let the user choose between:
    - **Create a new backup folder** (the `UUID`-suffixed unique folder, the default), or
    - **Use an existing folder** — list the folders the app can see in the account (via
      `listChildren`/a lightweight folder browser) and let the user select one. This is what enables
      re-attaching to a prior backup after reinstall/new device; on selection, run the reconciliation
      of §5.9 (prefer the cloud manifest §5.10, else filename-matching) so the existing contents are
      adopted into the index instead of re-uploaded.
- Set **schedule** (or "use global default"), **changed-file policy**, **network policy**,
  **encryption** (with password entry + confirm; explain the no-recovery warning), and optional
  **retention policy**.

### 7.4 Detail screen
Show everything about the backup: source folder, destination folder, schedule, changed-file
policy, encryption status, retention policy, network policy, full last-run statistics, the
cross-run progress state (§7.6), and the **Room-backed message list** for this backup (§8): a
severity-styled, observable list of recent `BackupMessage`s with mark-read / dismiss / clear-all
actions, including the coalesced "N files failed" rows and the "large files may need several runs"
warning from §5.5. Actions:
- **"Back up now"**, **edit**, **pause/resume**.
- **"Check my password"** (for encrypted backups): prompt for a candidate password, decrypt the
  stored `encryptedPasswordBlob` (KeyStore-wrapped, §6) to obtain the real password, and compare —
  confirming the user still remembers it **without needing a canary file or any cloud round-trip**
  (the app encrypts with exactly that stored password, so matching it is sufficient). Show a clear
  ✓/✗ result. This guards against the "forgot my password, discovered only at restore" disaster.
- **Delete**: removes the config and **cascade-cleans local tables** (its `UploadedFileIndex`,
  `BackupMessage`, and `NotificationThrottleState` rows). It **never deletes anything in Google
  Drive** — the backed-up files and folder remain; the confirm dialog states this and tells the
  user they can delete the cloud folder themselves in the Google Drive app if they wish.

### 7.5 Settings (DataStore)
Global default schedule, global default changed-file policy, **global default per-file size limit**
(default ≈ 256 MB; drives the oversized-tier ordering + warning of §5.5), theme, re-show
onboarding, default network policy, and a toggle for **anonymous error reports** (Crashlytics) —
**on by default / opt-out**; disabling it turns Crashlytics collection off entirely. Redaction
(§9) always applies regardless. A control to re-request notification permission (§8.4) also lives
here.

### 7.6 Cross-run progress is a first-class state (not a failure)
Because uploads are serial and a WorkManager window is bounded (~10 min), the **initial backup of a
large folder will legitimately span many runs**. The UI must communicate this so an incomplete run
never looks like a stall or error.
- Persist enough on `BackupConfig` (or a small companion table) to render progress *across* runs,
  not just within one: e.g. `totalFilesDiscovered`, `filesUploadedTotal`, `lastRunCompletedNormally`,
  and a derived state enum: `IDLE`, `RUNNING`, `INITIAL_SYNC_IN_PROGRESS` (more files remain than
  uploaded and no run has yet drained the queue), `UP_TO_DATE`, `COMPLETED_WITH_WARNINGS`,
  `FAILED`.
- During the initial sync show **determinate-ish progress** ("2,310 / 9,840 files") and an
  explicit, reassuring note that it continues automatically in subsequent runs — distinct from any
  error styling.
- The **foreground notification** the worker shows should carry the same "N / M files, continues
  next run" message, not just a spinner.
- Treat WorkManager `Result.retry()` / OS cancellation as normal mid-initial-sync; only surface a
  real error state for actual failures (auth lost, folder unreadable, repeated upload errors).

---

## 8. Messaging & notifications (central feature for an unattended app)

This app mostly runs in the background and is rarely opened, so the user must be able to (a) see a
durable, queryable history of what happened, and (b) be **proactively alerted** to problems that
silently break backups — without opening the app. This replaces and extends PrivateContacts'
DataStore string-list.

### 8.1 Message store (Room)
- Persist every notable event as a `BackupMessage` row (§4.3): `INFO`/`WARNING` for the log,
  `ERROR`/`CRITICAL` for things that need attention. Use the programmatic `type` enum, not free
  text, so behavior is driven by type.
- Write messages from the worker as the run proceeds. **Coalesce within a run**: don't insert
  thousands of `UPLOAD_FAILED` rows — keep one row per `(runId, backupConfigId, type)` and
  increment `count` (e.g. "12 files failed to upload"). Per-file detail can live in the log/logcat;
  the user-facing row is the summary.
- The UI observes messages via a DAO `Flow`. The detail screen (§7.4) shows the per-backup list
  with severity styling; allow mark-read and dismiss (single + "clear all for this backup"). A
  small unread-by-severity badge can appear on the home card.

### 8.2 What gets a notification — driven by message **type**, not blanket severity
- Each `MessageType` carries a constant `notifies: Boolean`. Notification-worthy types are the
  ones that mean **backups are silently not working** or need user action — e.g. `AUTH_LOST`,
  `FOLDER_UNREADABLE`, `QUOTA_EXCEEDED`, `ENCRYPTION_FAILED`, repeated `UPLOAD_FAILED`. Routine
  warnings like `FILE_TOO_LARGE` are **logged but do not notify** (they're expected and handled by
  the oversized-tier ordering of §5.5).
- This keeps the signal/noise right: the "my backup quietly broke for 3 weeks" failure mode is
  exactly what we alert on; the routine stuff stays in the in-app list.

### 8.3 Notification behavior (coalesced + throttled)
- **Two notification channels**, so the user tunes them independently:
    - **"Backup status"** — LOW importance, silent: the ongoing WorkManager foreground/progress
      notification (the "N / M files, continues next run" of §7.6).
    - **"Backup problems"** — DEFAULT/HIGH importance: alerts for `notifies` messages.
- **Granularity: one summary notification per run per backup.** At the end of a run, if that run
  produced any `notifies` messages for a backup, post a single coalesced notification
  ("Backup 'Photos' had problems: authorization expired; 12 files failed"). Never one notification
  per message. Tapping it deep-links (via Nav3) into that backup's detail screen.
- **Throttle repeats across runs** using `NotificationThrottleState` (§4.4): keyed by
  `backupConfigId:type`, suppress re-notifying for the same condition within a window (e.g. 24h /
  until the condition clears). So a persistent `AUTH_LOST` alerts once, not every run. When the
  condition resolves (a later successful run), clear the throttle key so a future recurrence can
  alert again. Optionally post a positive `INFO` ("Backup 'Photos' is working again") — keep that
  on the status channel or make it opt-in.

### 8.4 POST_NOTIFICATIONS permission (Android 13+) & retention
- Request `POST_NOTIFICATIONS` during **onboarding** (§7.1) with a clear rationale: "this app runs
  in the background — allow notifications so we can tell you if a backup stops working." Degrade
  gracefully if denied: the in-app message list remains the source of truth, and re-prompting can
  be offered from settings.
- **Retention/cap** on the `BackupMessage` table so months of unattended runs don't grow it
  unbounded: prune after a successful run — e.g. keep the last N messages per backup (a few
  hundred) and/or drop `INFO`/`WARNING` older than X days, always keeping unresolved
  `ERROR`/`CRITICAL` until dismissed or resolved.
- Reuse the copied notification/foreground-info patterns from PrivateContacts' backup workers as a
  starting point for channel creation and `ForegroundInfo`.

---

## 9. Privacy & logging (redaction is structural, not optional)

Privacy is a core value of this app. We use **Firebase Crashlytics** for crash/error reporting,
but crash telemetry must never compromise the user's privacy. Two hard rules:

1. **File *content* is never logged anywhere** — not locally, not remotely. (Plaintext bytes only
   ever pass through the encrypt/upload stream; they must not appear in any log, breadcrumb, or
   exception.)
2. **File *names and paths* are logged only locally.** Anything sent to Crashlytics gets a
   **redacted** form: the **first letter of the file name + the extension**, e.g. `report.pdf` →
   `r***.pdf`, `IMG_2024.jpg` → `I***.jpg`, a dotfile `.env` → `.***`, no-extension `notes` →
   `n***`. Relative folder paths are redacted segment-by-segment the same way (or replaced with a
   depth indicator) — never the real path.

### 9.1 Two-sink logger (the boundary is enforced by construction)
Implement logging with **two sinks behind one logger interface** so redaction can't be forgotten by
a caller:
- **Local sink** (logcat in debug; optionally an in-app/file log): receives **full detail** —
  real file names and relative paths are fine here, since this never leaves the device.
- **Crashlytics sink**: receives only **redacted** strings. This sink runs every message and every
  recorded exception through the redactor before it reaches Firebase. Because redaction lives in the
  sink, no individual log call site has to remember to scrub — the structural guarantee is "nothing
  reaches Crashlytics un-redacted."

Provide a small pure utility, e.g. `FileNameRedactor.redact(name): String` and
`redactPath(relativePath): String`, with unit tests for the cases above (normal name, multi-dot
name like `archive.tar.gz` → `a***.tar.gz` or `a***.gz` — pick one and test it, dotfile,
no-extension, empty). A Konsist/lint guard can additionally assert that Crashlytics APIs
(`FirebaseCrashlytics`, `recordException`, `log`, `setCustomKey`) are only referenced from inside
the logging infrastructure package, so app code can't bypass the redacting sink.

### 9.2 Watch the sneaky leak paths
Redacting explicit log statements is not enough; the agent must also handle:
- **Exception messages & stack traces.** `FileNotFoundException`, Drive API errors, SAF errors,
  etc. frequently embed the full path/filename in their message — and Crashlytics captures it.
  Never pass a raw caught exception straight to the Crashlytics sink: wrap/sanitize so the message
  and any path-bearing fields are redacted first. (The `BinaryResult`/`runCatchingAsResult` pattern
  is a good choke point to attach sanitization.)
- **The `relativePath` we deliberately store** in `BackupMessage`/`UploadedFileIndex` is full-detail
  for local Room use only; any code path that forwards message/index detail to Crashlytics must
  redact.
- **Crashlytics custom keys / breadcrumbs** (e.g. a "currentFile" key for debugging) go to Firebase
  too — same redaction applies.

### 9.3 User control
Crash reporting is tied to the existing **"anonymous error reports" setting** (§7.5): **on by
default (opt-out)** — the user can disable it in settings, which disables Crashlytics collection
entirely. Even when enabled, the redaction rules above always apply. (Mirror the spirit of
PrivateContacts' Crashlytics toggle.)

> **Single build flavor.** Unlike PrivateContacts (which has a Google-Play and an F-Droid flavor),
> this app ships **one Play-Store flavor only** — do **not** add an F-Droid/no-Google flavor. The
> Google Drive integration depends on Google Play Services (`play-services-auth` /
> `AuthorizationClient`), which isn't available on F-Droid, so a non-Google flavor would have no
> working cloud backend. Crashlytics is therefore always present (gated only by the user setting),
> and copied PrivateContacts code that is split across `googlePlay`/`fdroid` source sets should be
> collapsed into the single main source set.

---

## 10. Reused code from PrivateContacts (copy in, rename package)

These files are battle-tested in PrivateContacts and should be copied into the new project with
the package renamed (`ch.abwesend.privatecontacts.*` → your package) and small adaptations noted
inline. They are reproduced verbatim below so you can paste them.

> Adaptation notes:
> - Replace logging (`ch.abwesend.privatecontacts.domain.lib.logging.logger`) with **the two-sink,
    >   privacy-redacting logger from §9** (not a plain port of PrivateContacts' single logger); keep
    >   call sites. All copied files that log should route through it.
> - `injectAnywhere()` is a Koin helper — replace with your Koin DI accessor.
> - Keep the crypto constants and algorithms byte-for-byte identical.
> - For the cloud layer, treat the two Google files as the concrete implementation behind your
    >   new `ICloudStorageProvider` / `ICloudAuthorizer` interfaces, and add `getOrCreateChildFolder`
    >   + the explicit-remote-name streaming `uploadFile` as described in §3.1.

## APPENDIX: Reused source files (verbatim from PrivateContacts)

### 8.1 BinaryResult + Success/Error + ResultExtensions

#### `BinaryResult.kt`
```kotlin
/*
 * Private Contacts
 * Copyright (c) 2023.
 * Florian Gubler
 */

package ch.abwesend.privatecontacts.domain.model.result.generic

/**
 * Construct to represent either a successful result or an error.
 * See also ResultExtensions.kt for additional methods: extension-methods have the advantage of inlining.
 */
sealed interface BinaryResult<out TValue, out TError> {
    fun getValueOrNull(): TValue?
    fun getErrorOrNull(): TError?
}
```

#### `SuccessResult.kt`
```kotlin
/*
 * Private Contacts
 * Copyright (c) 2023.
 * Florian Gubler
 */

package ch.abwesend.privatecontacts.domain.model.result.generic

data class SuccessResult<TValue>(val value: TValue) : BinaryResult<TValue, Nothing> {
    override fun getValueOrNull(): TValue? = value
    override fun getErrorOrNull(): Nothing? = null
}
```

#### `ErrorResult.kt`
```kotlin
/*
 * Private Contacts
 * Copyright (c) 2023.
 * Florian Gubler
 */

package ch.abwesend.privatecontacts.domain.model.result.generic

data class ErrorResult<TError>(val error: TError) : BinaryResult<Nothing, TError> {
    override fun getValueOrNull(): Nothing? = null
    override fun getErrorOrNull(): TError = error
}
```

#### `ResultExtensions.kt`
```kotlin
/*
 * Private Contacts
 * Copyright (c) 2026.
 * Florian Gubler
 */

package ch.abwesend.privatecontacts.domain.model.result.generic

import kotlinx.coroutines.CancellationException

inline fun <T> runCatchingAsResult(block: () -> T): BinaryResult<T, Exception> {
    return try {
        SuccessResult(block())
    } catch (e: CancellationException) {
        throw e // do not catch coroutine-cancellations
    } catch (e: Exception) {
        ErrorResult(e)
    }
}

inline fun <T> runCatchingOnResult(block: () -> BinaryResult<T, Exception>): BinaryResult<T, Exception> {
    return try {
        block()
    } catch (e: CancellationException) {
        throw e // do not catch coroutine-cancellations
    } catch (e: Exception) {
        ErrorResult(e)
    }
}

inline fun <TValue, TError, T> BinaryResult<TValue, TError>.mapValue(
    mapper: (TValue) -> T
): BinaryResult<T, TError> = when (this) {
    is ErrorResult -> ErrorResult(error)
    is SuccessResult -> SuccessResult(mapper(value))
}

inline fun <TValue, TError, T> BinaryResult<TValue, TError>.mapError(
    mapper: (TError) -> T
): BinaryResult<TValue, T> = when (this) {
    is SuccessResult -> SuccessResult(value)
    is ErrorResult -> ErrorResult(mapper(error))
}

inline fun <TValue, TError, T> BinaryResult<TValue, TError>.mapValueToResult(
    mapper: (TValue) -> BinaryResult<T, TError>
): BinaryResult<T, TError> = when (this) {
    is ErrorResult -> this
    is SuccessResult -> mapper(value)
}

inline fun <TValue, TError> BinaryResult<TValue, TError>.ifSuccess(
    block: (TValue) -> Unit
): BinaryResult<TValue, TError> {
    when (this) {
        is SuccessResult -> block(value)
        is ErrorResult -> Unit
    }
    return this
}

inline fun <TValue, TError> BinaryResult<TValue, TError>.ifError(
    block: (TError) -> Unit
): BinaryResult<TValue, TError> {
    when (this) {
        is ErrorResult -> block(error)
        is SuccessResult -> Unit
    }
    return this
}
```

### 8.2 IDispatchers + Dispatchers + mapAsync/mapAsyncChunked

#### `Dispatchers.kt`
```kotlin
/*
 * Private Contacts
 * Copyright (c) 2022.
 * Florian Gubler
 */

package ch.abwesend.privatecontacts.domain.lib.coroutine

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

interface IDispatchers {
    val default: CoroutineDispatcher
    val io: CoroutineDispatcher
    val main: CoroutineDispatcher
    val mainImmediate: CoroutineDispatcher
}

object Dispatchers : IDispatchers {
    override val default: CoroutineDispatcher = Dispatchers.Default
    override val io: CoroutineDispatcher = Dispatchers.IO
    override val main: CoroutineDispatcher = Dispatchers.Main
    override val mainImmediate: CoroutineDispatcher = Dispatchers.Main.immediate
}
```

#### `CoroutineUtil.kt`
```kotlin
package ch.abwesend.privatecontacts.domain.lib.coroutine

import ch.abwesend.privatecontacts.domain.util.Constants
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** runs the [mapper] over each element in parallel */
suspend fun <T, S> Collection<T>.mapAsync(mapper: suspend (T) -> S): List<S> = coroutineScope {
    val deferred = map { async { mapper(it) } }
    deferred.awaitAll()
}

/**
 * same as [mapAsync] but chunked:
 *   - all elements in a chunk are run in parallel
 *   - chunks are run sequentially
 */
suspend fun <T, S> Collection<T>.mapAsyncChunked(
    chunkSize: Int = Constants.defaultChunkSize,
    mapper: suspend (T) -> S
): List<S> = coroutineScope {
    chunked(chunkSize).flatMap { chunk ->
        chunk.mapAsync(mapper)
    }
}
```

### 8.3 EncryptionRepository + AndroidKeyStoreRepository

#### `EncryptionRepository.kt`
```kotlin
/*
 * Private Contacts
 * Copyright (c) 2026.
 * Florian Gubler
 */

package ch.abwesend.privatecontacts.infrastructure.repository

import android.security.keystore.KeyProperties
import ch.abwesend.privatecontacts.domain.lib.logging.logger
import ch.abwesend.privatecontacts.domain.model.importexport.DecryptionError
import ch.abwesend.privatecontacts.domain.model.result.generic.BinaryResult
import ch.abwesend.privatecontacts.domain.model.result.generic.ifError
import ch.abwesend.privatecontacts.domain.model.result.generic.mapError
import ch.abwesend.privatecontacts.domain.model.result.generic.runCatchingAsResult
import ch.abwesend.privatecontacts.domain.repository.IEncryptionRepository
import ch.abwesend.privatecontacts.domain.repository.IKeyStoreRepository
import ch.abwesend.privatecontacts.domain.util.injectAnywhere
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class EncryptionRepository : IEncryptionRepository {
    private val keyStoreRepository: IKeyStoreRepository by injectAnywhere()

    companion object {
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val AES_KEY_SIZE_BITS = 256
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val GCM_IV_LENGTH_BYTES = 12

        private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val PBKDF2_ITERATIONS = 310_000
        private const val PBKDF2_SALT_LENGTH_BYTES = 16

        private const val JSON_VERSION = 1
    }

    // ---- File encryption (password-based AES-256-GCM with PBKDF2) ----

    override fun encrypt(plaintext: String, password: String): BinaryResult<String, Exception> = runCatchingAsResult {
        val salt = generateRandomBytes(PBKDF2_SALT_LENGTH_BYTES)
        val initializationVector = generateRandomBytes(GCM_IV_LENGTH_BYTES)
        val key = deriveKey(password, salt, PBKDF2_ITERATIONS, AES_KEY_SIZE_BITS)

        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, initializationVector))
        }
        val ciphertextBytes = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        val encoder = Base64.getEncoder()
        val payload = EncryptedPayload(
            version = JSON_VERSION,
            algorithm = AES_GCM_TRANSFORMATION,
            kdf = PBKDF2_ALGORITHM,
            iterations = PBKDF2_ITERATIONS,
            keySize = AES_KEY_SIZE_BITS,
            tagLength = GCM_TAG_LENGTH_BITS,
            salt = encoder.encodeToString(salt),
            iv = encoder.encodeToString(initializationVector),
            ciphertext = encoder.encodeToString(ciphertextBytes),
        )
        Json.encodeToString(payload)
    }.ifError { logger.error("Encryption failed", it) }

    override fun decrypt(ciphertext: String, password: String): BinaryResult<String, DecryptionError> = runCatchingAsResult {
        val decoder = Base64.getDecoder()
        val payload = Json.decodeFromString<EncryptedPayload>(ciphertext)
        val salt = decoder.decode(payload.salt)
        val iv = decoder.decode(payload.iv)
        val data = decoder.decode(payload.ciphertext)

        val key = deriveKey(password, salt, payload.iterations, payload.keySize)
        val cipher = Cipher.getInstance(payload.algorithm).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(payload.tagLength, iv))
        }
        cipher.doFinal(data).toString(Charsets.UTF_8)
    }.mapError { exception ->
        when (exception) {
            is AEADBadTagException -> {
                logger.error("Decryption failed due to invalid password", exception)
                DecryptionError.INVALID_PASSWORD
            }
            is SerializationException -> {
                logger.error("Not a valid JSON file", exception)
                DecryptionError.INVALID_FILE
            }
            is IllegalArgumentException -> {
                logger.error("Decryption failed due to invalid JSON structure", exception)
                DecryptionError.INVALID_FILE
            }
            else -> {
                logger.error("Decryption failed", exception)
                DecryptionError.UNKNOWN
            }
        }
    }

    private fun deriveKey(password: String, salt: ByteArray, iterations: Int, keySize: Int): SecretKey {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, keySize)
        val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, KeyProperties.KEY_ALGORITHM_AES)
    }

    // ---- Password storage (KeyStore-backed AES-256-GCM) ----

    override fun encryptPassword(password: String): BinaryResult<String, Exception> = runCatchingAsResult {
        val key = keyStoreRepository.getOrCreateKey()
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            .apply { init(Cipher.ENCRYPT_MODE, key) }
        val cipherText = cipher.doFinal(password.toByteArray(Charsets.UTF_8))

        val encoder = Base64.getEncoder()
        val payload = EncryptedPasswordPayload(
            version = JSON_VERSION,
            algorithm = AES_GCM_TRANSFORMATION,
            tagLength = GCM_TAG_LENGTH_BITS,
            iv = encoder.encodeToString(cipher.iv),
            ciphertext = encoder.encodeToString(cipherText),
        )
        Json.encodeToString(payload)
    }.ifError { logger.error("Password encryption failed", it) }

    override fun decryptPassword(encryptedPassword: String): BinaryResult<String, Exception> = runCatchingAsResult {
        val key = keyStoreRepository.getKey()
            ?: throw IllegalStateException("No KeyStore key available")

        val payload = Json.decodeFromString<EncryptedPasswordPayload>(encryptedPassword)
        val decoder = Base64.getDecoder()
        val iv = decoder.decode(payload.iv)
        val data = decoder.decode(payload.ciphertext)

        val cipher = Cipher.getInstance(payload.algorithm).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(payload.tagLength, iv))
        }
        cipher.doFinal(data).toString(Charsets.UTF_8)
    }.ifError { logger.error("Password decryption failed", it) }

    override fun deleteKeyStoreKey(): Boolean = keyStoreRepository.deleteKey()

    private fun generateRandomBytes(size: Int): ByteArray {
        val bytes = ByteArray(size)
        SecureRandom().nextBytes(bytes)
        return bytes
    }
}

@Serializable
private data class EncryptedPasswordPayload(
    val version: Int,
    val algorithm: String,
    val tagLength: Int,
    val iv: String,
    val ciphertext: String,
)

@Serializable
private data class EncryptedPayload(
    val version: Int,
    val algorithm: String,
    val kdf: String,
    val iterations: Int,
    val keySize: Int,
    val tagLength: Int,
    val salt: String,
    val iv: String,
    val ciphertext: String,
)
```

#### `AndroidKeyStoreRepository.kt`
```kotlin
/*
 * Private Contacts
 * Copyright (c) 2026.
 * Florian Gubler
 */

package ch.abwesend.privatecontacts.infrastructure.repository

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import ch.abwesend.privatecontacts.domain.lib.logging.logger
import ch.abwesend.privatecontacts.domain.repository.IKeyStoreRepository
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class AndroidKeyStoreRepository : IKeyStoreRepository {
    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEYSTORE_KEY_ALIAS = "PrivateContactsBackupKey"
        private const val AES_KEY_SIZE_BITS = 256
    }

    override fun getOrCreateKey(): SecretKey {
        val existing = withKeyStore { it.getKey(KEYSTORE_KEY_ALIAS, null) as? SecretKey }
        if (existing != null) return existing

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setKeySize(AES_KEY_SIZE_BITS)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    override fun getKey(): SecretKey? = withKeyStore { keyStore ->
        keyStore.getKey(KEYSTORE_KEY_ALIAS, null) as? SecretKey
    }

    override fun deleteKey(): Boolean {
        return try {
            withKeyStore { keyStore ->
                if (keyStore.containsAlias(KEYSTORE_KEY_ALIAS)) {
                    keyStore.deleteEntry(KEYSTORE_KEY_ALIAS)
                    logger.debug("Deleted KeyStore key for backup encryption")
                }
            }
            true
        } catch (e: Exception) {
            logger.warning("Failed to delete KeyStore key", e)
            false
        }
    }

    private fun <T> withKeyStore(block: (KeyStore) -> T): T {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).also { it.load(null) }
        return block(keyStore)
    }
}
```

### 8.4 Google Drive layer

#### `GoogleDriveRepository.kt`
```kotlin
/*
 * Private Contacts
 * Copyright (c) 2026.
 * Florian Gubler
 */

package ch.abwesend.privatecontacts.infrastructure.backup.googledrive.repository

import ch.abwesend.privatecontacts.domain.lib.coroutine.IDispatchers
import ch.abwesend.privatecontacts.domain.lib.logging.logger
import ch.abwesend.privatecontacts.domain.model.importexport.googledrive.GoogleDriveFile
import ch.abwesend.privatecontacts.domain.model.importexport.googledrive.GoogleDriveFolder
import ch.abwesend.privatecontacts.domain.model.importexport.googledrive.GoogleDriveFolderInfo
import ch.abwesend.privatecontacts.domain.model.result.generic.BinaryResult
import ch.abwesend.privatecontacts.domain.model.result.generic.runCatchingAsResult
import ch.abwesend.privatecontacts.domain.service.interfaces.IGoogleDriveRepository
import ch.abwesend.privatecontacts.domain.util.injectAnywhere
import com.google.api.client.http.FileContent
import com.google.api.services.drive.Drive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import com.google.api.services.drive.model.File as DriveFile

class GoogleDriveRepository(private val drive: Drive) : IGoogleDriveRepository {
    private val dispatchers: IDispatchers by injectAnywhere()

    companion object {
        private const val BACKUPS_FOLDER_NAME_PREFIX = "PrivateContacts_Backups"
        private const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"
    }

    override suspend fun getAccountEmail(): BinaryResult<String, Exception> =
        withContext(dispatchers.io) {
            runCatchingAsResult {
                drive.about().get()
                    .setFields("user")
                    .execute()
                    .user
                    .emailAddress
            }
        }

    override suspend fun createBackupFolder(): BinaryResult<GoogleDriveFolder, Exception> =
        withContext(dispatchers.io) {
            runCatchingAsResult {
                val folderInfo = createFolder()
                logger.info("Google Drive backup configured with folder: $folderInfo")

                GoogleDriveFolder(
                    folderId = folderInfo.id,
                    folderName = folderInfo.name,
                )
            }
        }

    override suspend fun hasFolderAccess(folderId: String, folderName: String): BinaryResult<Boolean, Exception> =
        withContext(dispatchers.io) {
            runCatchingAsResult {
                drive.files().get(folderId)
                    .setFields("id, name, trashed, capabilities")
                    .execute()
                    ?.let { file ->
                        val nameMatches = file.name == folderName
                        val notTrashed = file.trashed != true
                        val canWrite = file.capabilities?.canEdit == true
                        nameMatches && notTrashed && canWrite
                    } ?: false
            }
        }

    override suspend fun findExistingFiles(folderId: String, fileName: String): List<GoogleDriveFile> =
        withContext(dispatchers.io) {
            val query = "'$folderId' in parents and name = '$fileName' and trashed = false"
            drive.files().list()
                .setQ(query)
                .setFields("files(id, name)")
                .setSpaces("drive")
                .execute()
                .files
                .orEmpty()
                .mapNotNull { file ->
                    file.id?.let { id -> file.name?.let { name -> GoogleDriveFile(id, name) } }
                }
        }

    override suspend fun listAllFiles(folderId: String): List<GoogleDriveFile> =
        withContext(dispatchers.io) {
            val query = "'$folderId' in parents and trashed = false"
            drive.files().list()
                .setQ(query)
                .setFields("files(id, name)")
                .setSpaces("drive")
                .execute()
                .files
                .orEmpty()
                .mapNotNull { file ->
                    file.id?.let { id -> file.name?.let { name -> GoogleDriveFile(id, name) } }
                }
        }

    override suspend fun deleteFile(fileId: String): Boolean =
        withContext(dispatchers.io) {
            try {
                drive.files().delete(fileId).execute()
                logger.info("Deleted file from Google Drive: $fileId")
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warning("Failed to delete file from Google Drive: $fileId", e)
                false
            }
        }

    override suspend fun uploadFile(folderId: String, localFile: File, mimeType: String): GoogleDriveFile? =
        withContext(dispatchers.io) {
            val metadata = DriveFile().apply {
                name = localFile.name
                parents = listOf(folderId)
            }
            val content = FileContent(mimeType, localFile)
            val uploaded = drive.files().create(metadata, content)
                .setFields("id, name")
                .execute()
            logger.info("Uploaded file to Google Drive: ${uploaded.name} (${uploaded.id})")
            uploaded.id?.let { id -> uploaded.name?.let { name -> GoogleDriveFile(id, name) } }
        }

    private fun createFolder(): GoogleDriveFolderInfo {
        val folderName = "${BACKUPS_FOLDER_NAME_PREFIX}_${UUID.randomUUID()}"
        val metadata = DriveFile().apply {
            name = folderName
            mimeType = FOLDER_MIME_TYPE
        }
        val folder = drive.files().create(metadata)
            .setFields("id, name")
            .execute()
        logger.info("Created Google Drive folder: ${folder.name} (${folder.id})")
        return GoogleDriveFolderInfo(id = folder.id, name = folder.name)
    }
}
```

#### `GoogleDriveAuthorizationRepository.kt`
```kotlin
/*
 * Private Contacts
 * Copyright (c) 2026.
 * Florian Gubler
 */

package ch.abwesend.privatecontacts.infrastructure.backup.googledrive.repository

import android.content.Context
import android.content.Intent
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import ch.abwesend.privatecontacts.domain.lib.coroutine.IDispatchers
import ch.abwesend.privatecontacts.domain.lib.logging.logger
import ch.abwesend.privatecontacts.domain.model.importexport.googledrive.GoogleDriveAuthResult
import ch.abwesend.privatecontacts.domain.model.result.generic.BinaryResult
import ch.abwesend.privatecontacts.domain.model.result.generic.ifError
import ch.abwesend.privatecontacts.domain.model.result.generic.runCatchingAsResult
import ch.abwesend.privatecontacts.domain.service.interfaces.IGoogleDriveAuthorizationRepository
import ch.abwesend.privatecontacts.domain.service.interfaces.IGoogleDriveRepository
import ch.abwesend.privatecontacts.domain.util.injectAnywhere
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

class GoogleDriveAuthorizationRepository(private val context: Context) : IGoogleDriveAuthorizationRepository {
    private val dispatchers: IDispatchers by injectAnywhere()

    companion object {
        private const val APP_NAME = "PrivateContacts"
    }

    override suspend fun clearAuthorization(): BinaryResult<Unit, Exception> = withContext(dispatchers.io) {
        runCatchingAsResult {
            CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
            logger.debug("Google Drive authorization cleared")
        }.ifError { logger.error("Failed to clear Google Drive authorization", it) }
    }

    override suspend fun authorize(): GoogleDriveAuthResult<IGoogleDriveRepository> = withContext(dispatchers.io) {
        try {
            val authorizationResult = requestAuthorization()
            if (authorizationResult.hasResolution()) {
                authorizationResult.pendingIntent
                    ?.let { GoogleDriveAuthResult.ConsentRequired(it) }
                    ?: GoogleDriveAuthResult.Error
            } else {
                val driveRepository = authorizationResult.buildDriveRepository()
                GoogleDriveAuthResult.Authorized(data = driveRepository)
            }
        } catch (e: CancellationException) {
            logger.warning("Interrupted while requesting authorization.")
            throw e
        } catch (e: Exception) {
            logger.error("Failed to request authorization", e)
            GoogleDriveAuthResult.Error
        }
    }

    override suspend fun authorizeFromIntent(
        data: Intent?,
    ): BinaryResult<IGoogleDriveRepository, Exception> = withContext(dispatchers.io) {
        runCatchingAsResult {
            val result = Identity.getAuthorizationClient(context)
                .getAuthorizationResultFromIntent(data)
            result.buildDriveRepository()
        }.ifError { logger.error("Failed to handle authorization result", it) }
    }

    private fun buildDriveService(accessToken: GoogleDriveAccessToken): Drive {
        val initializer = HttpRequestInitializer { request ->
            request.headers.authorization = "Bearer ${accessToken.value}"
        }

        return Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            initializer,
        ).setApplicationName(APP_NAME).build()
    }

    private fun buildAuthorizationRequest(): AuthorizationRequest {
        val scopes = listOf(
            Scope(DriveScopes.DRIVE_FILE),
            Scope("email"),
        )
        return AuthorizationRequest.builder().setRequestedScopes(scopes).build()
    }

    /**
     * Authorizes via the modern AuthorizationClient API.
     * Must be called on a background thread (uses [Tasks.await]).
     * @return the [AuthorizationResult] containing either an access token or a [android.app.PendingIntent] for consent.
     */
    private suspend fun requestAuthorization(): AuthorizationResult = withContext(dispatchers.io) {
        val client = Identity.getAuthorizationClient(context)
        Tasks.await(client.authorize(buildAuthorizationRequest()))
    }

    private fun AuthorizationResult.extractAccessToken(): GoogleDriveAccessToken {
        return accessToken?.let { GoogleDriveAccessToken(it) }
            ?: throw IllegalStateException("Authorization succeeded but no access token returned")
    }

    private fun AuthorizationResult.buildDriveRepository(): IGoogleDriveRepository {
        val token = extractAccessToken()
        val drive = buildDriveService(token)
        return GoogleDriveRepository(drive)
    }
}

@JvmInline
private value class GoogleDriveAccessToken(val value: String)
```

#### `IGoogleDriveRepository.kt`
```kotlin
/*
 * Private Contacts
 * Copyright (c) 2026.
 * Florian Gubler
 */

package ch.abwesend.privatecontacts.domain.service.interfaces

import ch.abwesend.privatecontacts.domain.model.importexport.googledrive.GoogleDriveFile
import ch.abwesend.privatecontacts.domain.model.importexport.googledrive.GoogleDriveFolder
import ch.abwesend.privatecontacts.domain.model.result.generic.BinaryResult
import java.io.File

/** Abstracts Google Drive file operations given a valid, authenticated session. */
interface IGoogleDriveRepository {
    suspend fun getAccountEmail(): BinaryResult<String, Exception>

    suspend fun createBackupFolder(): BinaryResult<GoogleDriveFolder, Exception>

    /** @return true if the folder exists and is accessible for read & write. */
    suspend fun hasFolderAccess(folderId: String, folderName: String): BinaryResult<Boolean, Exception>

    suspend fun findExistingFiles(folderId: String, fileName: String): List<GoogleDriveFile>
    suspend fun listAllFiles(folderId: String): List<GoogleDriveFile>
    suspend fun deleteFile(fileId: String): Boolean
    suspend fun uploadFile(folderId: String, localFile: File, mimeType: String): GoogleDriveFile?
}
```

#### `GoogleDriveFile.kt`
```kotlin
/*
 * Private Contacts
 * Copyright (c) 2026.
 * Florian Gubler
 */

package ch.abwesend.privatecontacts.domain.model.importexport.googledrive

data class GoogleDriveFile(val id: String, val name: String)
```

#### `GoogleDriveFolder.kt`
```kotlin
/*
 * Private Contacts
 * Copyright (c) 2026.
 * Florian Gubler
 */

package ch.abwesend.privatecontacts.domain.model.importexport.googledrive

data class GoogleDriveFolder(
    val folderId: String,
    val folderName: String,
)
```

#### `GoogleDriveFolderInfo.kt`
```kotlin
/*
 * Private Contacts
 * Copyright (c) 2026.
 * Florian Gubler
 */

package ch.abwesend.privatecontacts.domain.model.importexport.googledrive

data class GoogleDriveFolderInfo(val id: String, val name: String)
```

#### `GoogleDriveAuthResult.kt`
```kotlin
/*
 * Private Contacts
 * Copyright (c) 2026.
 * Florian Gubler
 */

package ch.abwesend.privatecontacts.domain.model.importexport.googledrive

import android.app.PendingIntent

sealed interface GoogleDriveAuthResult<out T> {
    data class ConsentRequired(val pendingIntent: PendingIntent) : GoogleDriveAuthResult<Nothing>
    data class Authorized<T>(val data: T) : GoogleDriveAuthResult<T>
    data object Error : GoogleDriveAuthResult<Nothing>
}
```

### 8.5 Worker / scheduler patterns to follow

#### `BackupScheduler.kt`
```kotlin
/*
 * Private Contacts
 * Copyright (c) 2026.
 * Florian Gubler
 */

package ch.abwesend.privatecontacts.infrastructure.backup

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import ch.abwesend.privatecontacts.domain.lib.logging.logger
import ch.abwesend.privatecontacts.domain.service.interfaces.IBackupScheduler
import ch.abwesend.privatecontacts.infrastructure.backup.worker.ContactBackupWorker
import ch.abwesend.privatecontacts.infrastructure.backup.worker.GoogleDriveBackupWorker
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

class BackupScheduler(private val context: Context) : IBackupScheduler {
    private companion object {
        const val WORK_NAME = "periodic_contact_backup_v1"
        const val ONE_TIME_WORK_NAME = "one_time_contact_backup"
        const val DRIVE_WORK_NAME = "periodic_drive_backup_v1"
    }

    override fun schedulePeriodicBackup() {
        schedulePeriodicLocalBackup()
        schedulePeriodicDriveBackup()
    }

    private fun schedulePeriodicLocalBackup() {
        try {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .setRequiresStorageNotLow(true)
                .setRequiresCharging(false)
                .setRequiresDeviceIdle(false)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<ContactBackupWorker>(
                repeatInterval = 24,
                repeatIntervalTimeUnit = TimeUnit.HOURS,
            )
                .setConstraints(constraints)
                .setInitialDelay(30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                uniqueWorkName = WORK_NAME,
                existingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.UPDATE,
                request = workRequest,
            )

            logger.info("Periodic backup work scheduled")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Failed to schedule periodic backup", e)
        }
    }

    override fun triggerOneTimeBackup() {
        try {
            val inputData = workDataOf(ContactBackupWorker.OVERRIDE_BACKUP_FREQUENCY to true)

            val localWork = OneTimeWorkRequestBuilder<ContactBackupWorker>()
                .setInputData(inputData)
                .setInitialDelay(10, TimeUnit.SECONDS)
                .build()

            val driveWork = OneTimeWorkRequestBuilder<GoogleDriveBackupWorker>()
                .setConstraints(getGoogleDriveConstraints())
                .build()

            WorkManager.getInstance(context)
                .beginUniqueWork(ONE_TIME_WORK_NAME, ExistingWorkPolicy.REPLACE, localWork)
                .then(driveWork)
                .enqueue()

            logger.info("One-time backup work triggered (local + drive) with 10s delay")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Failed to trigger one-time backup", e)
        }
    }

    private fun schedulePeriodicDriveBackup() {
        try {
            val driveWorkRequest = PeriodicWorkRequestBuilder<GoogleDriveBackupWorker>(
                repeatInterval = 24,
                repeatIntervalTimeUnit = TimeUnit.HOURS,
            )
                .setConstraints(getGoogleDriveConstraints())
                .setInitialDelay(1, TimeUnit.HOURS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                uniqueWorkName = DRIVE_WORK_NAME,
                existingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.UPDATE,
                request = driveWorkRequest,
            )

            logger.info("Periodic Drive backup work scheduled")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Failed to schedule periodic Drive backup", e)
        }
    }

    private fun getGoogleDriveConstraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .setRequiresStorageNotLow(true)
        .setRequiresCharging(false)
        .setRequiresDeviceIdle(false)
        .build()
}
```

#### `GoogleDriveBackupWorker.kt`
```kotlin
/*
 * Private Contacts
 * Copyright (c) 2026.
 * Florian Gubler
 */

package ch.abwesend.privatecontacts.infrastructure.backup.worker

import android.content.Context
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import ch.abwesend.privatecontacts.R
import ch.abwesend.privatecontacts.domain.lib.coroutine.mapAsync
import ch.abwesend.privatecontacts.domain.lib.logging.logger
import ch.abwesend.privatecontacts.domain.model.backup.BackupMessage
import ch.abwesend.privatecontacts.domain.model.backup.BackupMessageSeverity
import ch.abwesend.privatecontacts.domain.model.backup.NumberOfBackupsToKeep
import ch.abwesend.privatecontacts.domain.model.backup.resolveContactTypes
import ch.abwesend.privatecontacts.domain.model.contact.ContactType
import ch.abwesend.privatecontacts.domain.model.importexport.googledrive.GoogleDriveAuthResult
import ch.abwesend.privatecontacts.domain.model.result.generic.BinaryResult
import ch.abwesend.privatecontacts.domain.model.result.generic.ErrorResult
import ch.abwesend.privatecontacts.domain.model.result.generic.SuccessResult
import ch.abwesend.privatecontacts.domain.repository.IBackupMessageRepository
import ch.abwesend.privatecontacts.domain.service.interfaces.IGoogleDriveAuthorizationRepository
import ch.abwesend.privatecontacts.domain.service.interfaces.IGoogleDriveRepository
import ch.abwesend.privatecontacts.domain.settings.ISettingsState
import ch.abwesend.privatecontacts.domain.settings.Settings
import ch.abwesend.privatecontacts.domain.util.injectAnywhere
import ch.abwesend.privatecontacts.infrastructure.backup.repository.BackupNotificationRepository
import ch.abwesend.privatecontacts.infrastructure.backup.util.WorkerErrorHandler
import ch.abwesend.privatecontacts.infrastructure.backup.util.backupFilenamePrefix
import ch.abwesend.privatecontacts.view.screens.importexport.shared.ImportExportConstants.CRYPT_FILE_EXTENSION
import ch.abwesend.privatecontacts.view.screens.importexport.shared.ImportExportConstants.CRYPT_PRETENDING_MIME_TYPE
import ch.abwesend.privatecontacts.view.screens.importexport.shared.ImportExportConstants.VCF_FILE_EXTENSION
import ch.abwesend.privatecontacts.view.screens.importexport.shared.ImportExportConstants.VCF_MAIN_MIME_TYPE
import kotlinx.coroutines.CancellationException
import java.io.File
import java.util.UUID

class GoogleDriveBackupWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    private val googleDriveAuthRepository: IGoogleDriveAuthorizationRepository by injectAnywhere()
    private val backupMessageRepository: IBackupMessageRepository by injectAnywhere()
    private val backupNotificationRepository: BackupNotificationRepository by injectAnywhere()

    companion object {
        private val errorHandler = WorkerErrorHandler()
    }

    private suspend fun addErrorMessage(text: String, severity: BackupMessageSeverity) {
        backupMessageRepository.addDriveMessage(BackupMessage(text = text, severity = severity))
    }

    override suspend fun doWork(): Result {
        return errorHandler.doWorkWithErrorHandling(
            workDescription = "Google Drive backup upload",
            addPersistedErrorMessage = { textRes, args ->
                val text = applicationContext.getString(textRes, *args)
                addErrorMessage(text = text, severity = BackupMessageSeverity.ERROR)
            }
        ) {
            logger.debug("Starting Google Drive backup upload")
            val settings = Settings.nextOrDefault()

            val metaData = when (val result = checkPreConditions(settings)) {
                is SuccessResult -> result.value
                is ErrorResult -> return@doWorkWithErrorHandling result.error
            }

            val driveRepository = when (val result = getGoogleDriveRepository()) {
                is SuccessResult -> result.value
                is ErrorResult -> return@doWorkWithErrorHandling result.error
            }

            val contactTypes = settings.backupContactScope.resolveContactTypes()
            val encrypted = settings.backupEncryptionEnabled

            val retryRequired = contactTypes
                .mapAsync { type -> uploadLocalBackup(driveRepository, metaData, type, encrypted) }
                .any { it == UploadResult.RETRY }

            if (retryRequired) {
                logger.warning("Google Drive backup upload completed with failures")
                Result.retry()
            } else {
                logger.debug("Google Drive backup upload completed successfully")
                cleanupOldDriveBackups(driveRepository, metaData.folderId, settings.numberOfBackupsToKeep)
                Result.success()
            }
        }
    }

    /**
     * @return [SuccessResult] if all preconditions are met and the backup should continue.
     *  [ErrorResult] if any preconditions are not met and the backup should be aborted.
     */
    private suspend fun checkPreConditions(settings: ISettingsState): BinaryResult<MetaData, Result> {
        if (!settings.googleDriveBackupEnabled) {
            logger.debug("Google Drive backup is disabled, skipping")
            return ErrorResult(Result.success())
        }

        val folderId = settings.googleDriveFolderId
        if (folderId.isEmpty()) {
            logger.warning("Google Drive folder not configured, skipping")
            addErrorMessage(
                text = applicationContext.getString(R.string.drive_backup_folder_not_configured_warning),
                severity = BackupMessageSeverity.WARNING,
            )
            return ErrorResult(Result.failure())
        }

        val backupFolder = settings.backupFolder
        if (backupFolder.isEmpty()) {
            logger.warning("Local backup folder not configured, skipping Drive upload")
            addErrorMessage(
                text = applicationContext.getString(R.string.drive_backup_local_folder_not_configured_warning),
                severity = BackupMessageSeverity.WARNING,
            )
            return ErrorResult(Result.success())
        }

        val documentFolder = DocumentFile.fromTreeUri(applicationContext, backupFolder.toUri())
        if (documentFolder == null || !documentFolder.canRead()) {
            logger.warning("Cannot read local backup folder")
            addErrorMessage(
                text = applicationContext.getString(R.string.drive_backup_local_folder_unreadable_error),
                severity = BackupMessageSeverity.ERROR,
            )
            return ErrorResult(Result.retry())
        }

        return SuccessResult(MetaData(folderId = folderId, localBackupFolder = documentFolder))
    }

    private suspend fun getGoogleDriveRepository(): BinaryResult<IGoogleDriveRepository, Result> {
        val unauthorizedWarning = "No Google authorization available, skipping Drive backup"

        return when (val driveResult = googleDriveAuthRepository.authorize()) {
            is GoogleDriveAuthResult.Authorized -> SuccessResult(driveResult.data)
            is GoogleDriveAuthResult.ConsentRequired -> {
                logger.warning("Authorization requires user consent, cannot obtain token silently")
                logger.warning(unauthorizedWarning)
                addErrorMessage(
                    text = applicationContext.getString(R.string.drive_backup_account_not_signed_in_error),
                    severity = BackupMessageSeverity.ERROR,
                )
                ErrorResult(Result.failure())
            }
            is GoogleDriveAuthResult.Error -> {
                logger.warning(unauthorizedWarning)
                addErrorMessage(
                    text = applicationContext.getString(R.string.drive_backup_account_not_signed_in_error),
                    severity = BackupMessageSeverity.ERROR,
                )
                ErrorResult(Result.failure())
            }
        }
    }

    private suspend fun uploadLocalBackup(
        driveRepository: IGoogleDriveRepository,
        metaData: MetaData,
        type: ContactType,
        encrypted: Boolean,
    ): UploadResult {
        val newestFile = findNewestLocalBackup(metaData.localBackupFolder, type, encrypted)
        if (newestFile == null) {
            logger.warning("No local backup found for $type contacts")
            addErrorMessage(
                text = applicationContext.getString(R.string.drive_backup_no_local_file_warning, type.name.lowercase()),
                severity = BackupMessageSeverity.WARNING,
            )
            return UploadResult.ABORT
        }

        val alreadyUploaded = driveRepository
            .findExistingFiles(metaData.folderId, newestFile.name.orEmpty())
            .isNotEmpty()

        if (alreadyUploaded) {
            logger.debug("Backup ${newestFile.name} already uploaded to Drive")
            return UploadResult.SUCCESS
        }

        val success = uploadLocalBackup(
            driveRepository = driveRepository,
            folderId = metaData.folderId,
            documentFile = newestFile,
            encrypted = encrypted
        )
        return if (success) UploadResult.SUCCESS else UploadResult.RETRY
    }

    private suspend fun cleanupOldDriveBackups(
        driveRepository: IGoogleDriveRepository,
        folderId: String,
        numberOfBackupsToKeep: NumberOfBackupsToKeep,
    ) {
        ContactType.entries.forEach { type ->
            try {
                val prefix = type.backupFilenamePrefix
                val backupFiles = driveRepository.listAllFiles(folderId)
                    .filter { it.name.startsWith(prefix) }
                    .sortedBy { it.name } // date-based naming ensures lexicographic = chronological

                val toDelete = (backupFiles.size - numberOfBackupsToKeep.maxCount).coerceAtLeast(0)
                backupFiles.take(toDelete).forEach { file ->
                    logger.debug("Deleting old Drive backup: ${file.name}")
                    driveRepository.deleteFile(file.id)
                }
                logger.info("Deleted $toDelete old Drive backups for $type")
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warning("Failed to delete old Drive backups for $type", e)
                addErrorMessage(
                    text = applicationContext.getString(R.string.backup_delete_old_failed_warning),
                    severity = BackupMessageSeverity.WARNING,
                )
            }
        }
    }

    private fun findNewestLocalBackup(
        folder: DocumentFile,
        type: ContactType,
        encrypted: Boolean,
    ): DocumentFile? {
        val prefix = type.backupFilenamePrefix
        val extension = if (encrypted) CRYPT_FILE_EXTENSION else VCF_FILE_EXTENSION

        return folder.listFiles()
            .filter { file ->
                val name = file.name
                !name.isNullOrEmpty() && name.startsWith(prefix) && name.endsWith(".$extension")
            }
            .maxByOrNull { it.name.orEmpty() } // date-based naming ensures lexicographic = chronological
    }

    private suspend fun uploadLocalBackup(
        driveRepository: IGoogleDriveRepository,
        folderId: String,
        documentFile: DocumentFile,
        encrypted: Boolean,
    ): Boolean {
        return try {
            val tempFile = copyToTempFile(documentFile) ?: return false
            try {
                val mimeType = if (encrypted) CRYPT_PRETENDING_MIME_TYPE else VCF_MAIN_MIME_TYPE
                driveRepository.uploadFile(folderId, tempFile, mimeType)
                true
            } finally {
                tempFile.delete()
            }
        } catch (e: CancellationException) {
            logger.warning("Interrupted while uploading ${documentFile.name} to Google Drive")
            throw e // do not catch coroutine-cancellations
        } catch (e: Exception) {
            logger.error("Failed to upload ${documentFile.name} to Google Drive", e)
            addErrorMessage(
                text = applicationContext.getString(
                    R.string.drive_backup_upload_file_failed_error,
                    documentFile.name.orEmpty(),
                ),
                severity = BackupMessageSeverity.ERROR,
            )
            false
        }
    }

    private fun copyToTempFile(documentFile: DocumentFile): File? {
        val name = documentFile.name ?: UUID.randomUUID().toString()
        val tempFile = File(applicationContext.cacheDir, name)
        return applicationContext.contentResolver.openInputStream(documentFile.uri)
            ?.use { input -> tempFile.outputStream().use { output -> input.copyTo(output) } }
            ?.let { tempFile }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return backupNotificationRepository.createForegroundInfo()
    }
}

private data class MetaData(
    val folderId: String,
    val localBackupFolder: DocumentFile,
)

private enum class UploadResult {
    SUCCESS,
    ABORT,
    RETRY,
}
```

#### `WorkerErrorHandler.kt`
```kotlin
/*
 * Private Contacts
 * Copyright (c) 2026.
 * Florian Gubler
 */

package ch.abwesend.privatecontacts.infrastructure.backup.util

import androidx.work.ListenableWorker
import ch.abwesend.privatecontacts.R
import ch.abwesend.privatecontacts.domain.lib.logging.logger
import kotlinx.coroutines.CancellationException

class WorkerErrorHandler {
    companion object {
        const val MAX_RETRY_COUNT = 20
    }

    private var retryCounter = 0 // the counter will be reset on garbage-collection

    suspend fun doWorkWithErrorHandling(
        workDescription: String,
        addPersistedErrorMessage: suspend (Int, Array<out String>) -> Unit,
        block: suspend () -> ListenableWorker.Result
    ): ListenableWorker.Result = try {
        block().also {
            retryCounter = 0 // reset the counter after a run without exception
        }
    } catch (e: CancellationException) {
        logger.debug("$workDescription cancelled", e)
        retryCounter++

        // randomness to avoid an infinite loop if JVM resets retryCounter
        if (retryCounter < MAX_RETRY_COUNT && Math.random() > 0.01) {
            logger.warning("$workDescription cancelled in attempt $retryCounter: re-trying")
            ListenableWorker.Result.retry()
        } else {
            logger.warning("$workDescription failed due to cancellation in attempt $retryCounter")
            retryCounter = 0
            ListenableWorker.Result.failure()
        }
    } catch (e: Exception) {
        retryCounter = 0
        logger.error("$workDescription failed", e)
        addPersistedErrorMessage(R.string.backup_worker_failed_unexpectedly_error, arrayOf(workDescription))
        ListenableWorker.Result.failure()
    }
}
```

---

## 11. Coding-agent feedback loop (you are likely being run by Claude Code)

This prompt is expected to be executed by an autonomous coding agent.

### 11.1 Visual sight-loop (don't build UI blind)
To avoid building UI "blind", use the screenshot tooling as a sight loop, not just as regression
tests:

- **After implementing or changing any screen or notable UI state**, add/update its `@Preview` in
  the `src/screenshotTest` source set, run `./gradlew updateDebugScreenshotTest`, then **open and
  visually inspect the generated PNG(s)** (and the HTML report) before considering the screen
  done. Treat the rendered image as ground truth — check spacing, alignment, theming, empty/long
  states, light & dark — and iterate until it looks right. Do not move to the next screen on the
  basis of code alone.
- Prefer this JVM-rendered preview loop (fast, no device) for layout/appearance work. Use it for
  every onboarding card, the home empty + populated states, each add/edit form step, the detail
  screen, and error/warning states.
- For whole-flow / navigation behaviour that a static preview can't capture (multi-step add/edit,
  consent `PendingIntent`, real Drive auth), additionally verify on an emulator when one is
  available: build, install, drive the flow, and capture the screen with
  `adb exec-out screencap -p > screen.png`, then view it. (No MCP server is required for this; it
  only needs permission to run `adb`.)
- Keep a short running note of which screens have been visually verified so coverage is auditable.
- If CPST Gradle tasks fail for version/compatibility reasons (it is alpha), web-search the
  current AGP/Kotlin/JDK requirement matrix and adjust the catalog rather than disabling the loop.

### 11.2 Persistent project conventions — create and maintain a `CLAUDE.md`
Create a **`CLAUDE.md`** at the repo root (under source control) that captures the durable
working agreements so they survive across sessions and future agents. Seed it with: the
build/test/lint commands (`./gradlew build`, the JUnit5 + Robolectric test tasks, `detekt`, the
Konsist tests, `updateDebugScreenshotTest`), the layering rules (§3), the conventions from §2
(BinaryResult, IDispatchers, serial-upload philosophy, etc.), the sight-loop habit (§11.1), and
the prompt-history habit (§11.3). Keep it concise and update it whenever a convention changes.

Include in `CLAUDE.md` an explicit **"Definition of Done" checklist** the agent runs through
before treating any unit of work as complete (these are gates, not suggestions — the
prompt-history update sits among them precisely so it isn't forgotten):

```
Before considering a task done:
[ ] Code compiles:            ./gradlew assembleDebug   (no errors)
[ ] Unit tests pass:          ./gradlew test            (JUnit5 + Robolectric/Compose)
[ ] Architecture tests pass:  Konsist guards green
[ ] Linter is happy:          ./gradlew detekt          (no new issues)
[ ] UI verified visually:     screenshots rendered & inspected for any screen touched (§11.1)
[ ] Prompt history updated:   docs/prompt-history.md has an entry for this work (§11.3)
[ ] CLAUDE.md updated:        only if a durable convention changed
```

Adjust the exact Gradle task names to the project's actual setup. The intent is a single,
always-run gate so compilation, tests, lint, visual check, and the vibe-coding log are kept in
lockstep with the code.

### 11.3 Maintain a prompt history (this is a vibe-coded project)
The author intends to develop this app largely through conversational/"vibe" coding, and wants a
durable record of that — **without having to remember to ask for it each time.** Therefore, as a
standing instruction recorded in `CLAUDE.md`:

- Maintain a markdown file, e.g. **`docs/prompt-history.md`** (under source control), that logs the
  prompts/instructions that drive development.
- **Append to it as part of completing work** — treat it like updating a changelog: when a unit of
  work is done (a feature, fix, or refactor prompted by the user), add a dated entry summarizing
  what was requested and what changed. This is enforced by the Definition-of-Done checklist in
  §11.2, so it happens automatically rather than on request.
- For a burst of several short back-and-forth prompts, **do not transcribe each one** — combine and
  summarize them into a single coherent entry. For a substantial standalone instruction, capture it
  more faithfully. Favor the *intent and outcome* over verbatim text.
- Suggested entry shape: a date/heading, a one-to-three sentence summary of what was asked, and a
  short note of the resulting changes (files/areas touched). Keep it readable as a development
  narrative, newest entries at the top or clearly dated.
- **Scope:** this applies only once *actual app development* begins. The conversation that produced
  *this* initial build prompt is **out of scope** and should not be retro-logged — start the
  history from the first real coding task.

---

## 12. Starter config files (Detekt & Konsist)

Use these as starting points; adapt package names to the chosen base package and tune rules to
taste. They are deliberately strict-but-reasonable.

### 12.1 `config/detekt/detekt.yml`

Run via the `io.gitlab.arturbosch.detekt` plugin with `buildUponDefaultConfig = true` (so this
file only overrides what it needs). Add `detekt-formatting` (ktlint wrapper) and, if available for
the chosen version, the Compose rule set. Wire a `detekt` Gradle task into the build and keep it
green.

```yaml
build:
  maxIssues: 0            # fail on any issue once the baseline is clean
  excludeCorrectable: false

config:
  validation: true
  warningsAsErrors: false

processors:
  active: true

console-reports:
  active: true

complexity:
  active: true
  LongMethod:
    threshold: 60
  LongParameterList:
    functionThreshold: 7
    constructorThreshold: 8     # Room entities / config data classes can be wide
  TooManyFunctions:
    active: true
    thresholdInClasses: 20
    ignorePrivate: true
  CyclomaticComplexMethod:
    threshold: 18
  NestedBlockDepth:
    threshold: 5

coroutines:
  active: true
  GlobalCoroutineUsage:
    active: true            # we use an injected scope, never GlobalScope
  SuspendFunWithFlowReturnType:
    active: true
  RedundantSuspendModifier:
    active: true

exceptions:
  active: true
  TooGenericExceptionCaught:
    active: false           # the BinaryResult/runCatchingAsResult pattern catches Exception on purpose
  SwallowedException:
    active: true
  ThrowingExceptionInMain:
    active: true

naming:
  active: true
  FunctionNaming:
    # allow @Composable PascalCase functions
    ignoreAnnotated: ['Composable']
  TopLevelPropertyNaming:
    constantPattern: '[A-Z][A-Za-z0-9]*'

performance:
  active: true
  SpreadOperator:
    active: false           # WorkManager/string-format varargs make this noisy

potential-bugs:
  active: true
  LateinitUsage:
    active: true
  UnsafeCallOnNullableType:
    active: true

style:
  active: true
  MaxLineLength:
    maxLineLength: 120
  ForbiddenComment:
    values: ['FIXME:', 'STOPSHIP:']
  MagicNumber:
    active: true
    ignoreNumbers: ['-1', '0', '1', '2', '100', '1000', '1024']
    ignoreEnums: true
    ignoreAnnotation: true
    ignorePropertyDeclaration: true
  ReturnCount:
    max: 4                  # the worker precondition/early-return style needs a few
  UnusedPrivateMember:
    active: true
    ignoreAnnotated: ['Preview']    # screenshotTest previews look unused to detekt

formatting:
  active: true
  android: true
  autoCorrect: true
  Indentation:
    indentSize: 4
  MaximumLineLength:
    maxLineLength: 120

comments:
  active: true
  AbsentOrWrongFileLicense:
    active: false           # enable + set licenseTemplateFile if you want the header enforced
```

### 12.2 Konsist architecture tests (`src/test/.../architecture/`)

Konsist guards run as ordinary JUnit5 tests. These enforce the layering from §3 (`view` →
`domain` ← `infrastructure`, with `domain` depending on neither) and the project's conventions
(BinaryResult for fallible repository ops, cloud specifics confined to the Drive package, Room
entities/DAOs placed correctly). Adjust the package roots to the real base package.

```kotlin
package <base>.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.architecture.KoArchitectureCreator.assertArchitecture
import com.lemonappdev.konsist.api.architecture.Layer
import com.lemonappdev.konsist.api.ext.list.modifierprovider.withoutOverrideModifier
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.jupiter.api.Test

private const val BASE = "<base>"   // e.g. "ch.abwesend.foldervault"

class ArchitectureLayerTest {

    private val scope = Konsist.scopeFromProduction()

    @Test
    fun `clean layering is respected`() {
        scope.assertArchitecture {
            val view = Layer("view", "$BASE.view..")
            val domain = Layer("domain", "$BASE.domain..")
            val infrastructure = Layer("infrastructure", "$BASE.infrastructure..")

            // domain is the pure core: it must not know about UI or infrastructure
            domain.dependsOnNothing(view, infrastructure)
            // view and infrastructure may both depend on domain, but not on each other
            view.doesNotDependOn(infrastructure)
            infrastructure.doesNotDependOn(view)
        }
    }
}

class CloudAbstractionTest {

    @Test
    fun `google drive specifics stay behind the cloud abstraction`() {
        // Google Drive types may only be referenced inside the drive implementation package.
        Konsist.scopeFromProduction()
            .files
            .filterNot { it.path.contains("/infrastructure/cloud/googledrive/") }
            .assertTrue { file ->
                file.imports.none { import ->
                    import.name.startsWith("com.google.api.services.drive") ||
                        import.name.startsWith("com.google.android.gms.auth")
                }
            }
    }

    @Test
    fun `domain references only the provider-agnostic interfaces`() {
        Konsist.scopeFromProduction()
            .files
            .filter { it.path.contains("/domain/") }
            .assertTrue { file ->
                file.imports.none { it.name.contains(".googledrive.") }
            }
    }
}

class ResultConventionTest {

    @Test
    fun `repository methods that can fail return BinaryResult`() {
        // Convention guard: public, non-overridden functions in repository interfaces/classes
        // that perform IO should return BinaryResult rather than throwing.
        // Tune the selector to your naming; this is a starting point, relax where it is too strict.
        Konsist.scopeFromProduction()
            .classesAndInterfaces()
            .filter { it.name.endsWith("Repository") }
            .flatMap { it.functions() }
            .withoutOverrideModifier()
            .filter { it.hasPublicOrDefaultModifier && it.hasSuspendModifier }
            .assertTrue { func ->
                val type = func.returnType?.name.orEmpty()
                type.startsWith("BinaryResult") ||
                    type == "Unit" || type == "Boolean" ||           // trivial side-effect ops
                    type.startsWith("List") || type.startsWith("Flow")
            }
    }
}

class NamingAndPlacementTest {

    @Test
    fun `Room entities live in the room package and end with Entity`() {
        Konsist.scopeFromProduction()
            .classes()
            .filter { it.hasAnnotationWithName("Entity") }
            .assertTrue {
                it.resideInPackage("..infrastructure.room..") && it.name.endsWith("Entity")
            }
    }

    @Test
    fun `DAOs are interfaces annotated with Dao and end with Dao`() {
        Konsist.scopeFromProduction()
            .interfaces()
            .filter { it.name.endsWith("Dao") }
            .assertTrue { it.hasAnnotationWithName("Dao") }
    }

    @Test
    fun `ViewModels reside in the view layer and end with ViewModel`() {
        Konsist.scopeFromProduction()
            .classes()
            .filter { it.name.endsWith("ViewModel") }
            .assertTrue { it.resideInPackage("..view..") }
    }
}
```

> Konsist's exact API (extension names, selector signatures) shifts between minor versions. If a
> symbol above doesn't resolve for the resolved Konsist version, consult the current Konsist API
> reference and adjust — the *intent* of each guard is what matters, not the literal call.

---

## 13. Deliverables & order of work

1. Wire up dependencies & tooling in the **existing** project starting from the version catalog in
   §2.1 — **first checking each entry for a newer stable release and bumping to it** — covering
   Compose/Material3, Nav3, Room, DataStore, WorkManager, Koin, Google Drive + AuthorizationClient,
   kotlinx-serialization, plus the **Detekt** plugin (with the starter `config/detekt/detekt.yml`
   from §12.1), **Konsist** and
   **Robolectric** test dependencies, the **CPST** (`com.android.compose.screenshot`) plugin +
   `screenshotTest` source set, and the JUnit5 + JUnit4 test setup described in §2. Get a clean
   Gradle sync/build (resolve the Kotlin/Compose/AGP/KSP alignment) before proceeding. Establish
   the package structure and a Koin module. Replace the template `MainActivity` with the Nav3 host
   and placeholder screens. Also create the `CLAUDE.md` (§11.2) and an empty `docs/prompt-history.md`
   (§11.3) so the working conventions and the vibe-coding log exist from the start.
2. Copy in the reused building blocks (§10) and make them compile under the new package.
3. **Privacy-aware logging foundation (§9)**: the two-sink logger (full-detail local sink +
   auto-redacting Crashlytics sink), the `FileNameRedactor` utility + unit tests, and the
   Crashlytics opt-out wiring. Build this early — everything else logs through it.
4. Room schema (`BackupConfig`, `UploadedFileIndex`, `BackupMessage`, `NotificationThrottleState`)
    + DAOs + DataStore settings.
5. `ICloudStorageProvider` / `ICloudAuthorizer` + Google Drive implementation (extended), including
   the §5.11 resilience behavior: deterministic handling of duplicate-named folders, silent
   token re-auth on 401, exponential-backoff retry on 429/5xx, and once-per-run warn-and-skip
   (or stop) on quota-full / persistent rate limiting.
6. Encryption extension for binary streaming `.crypt` (§6) + unit tests (round-trip, wrong
   password).
7. The analyzer→queue→uploader pipeline (§5): **serial** single-file uploads, two-tier
   (normal-then-oversized) ordering, temp-file encryption staging with startup wipe + per-file
   `finally` cleanup, the `(mtime, size)` change-detection **with the null/0 mtime → size →
   cloud-existence fallback (§5.1)**, cloud-reconciliation for empty-index/existing-folder cases
   (§5.9), and the plaintext cloud manifest (§5.10). Unit tests for change-detection
   (incl. unreliable-mtime fallback), two-tier ordering, staging cleanup, reconciliation, manifest
   round-trip, and retention.
8. Worker + scheduler (§5.7) including **per-config unique serialization** and the **run-window
   continuation** logic (§5.8) that re-enqueues promptly to drain a large initial sync over
   minutes/hours rather than days.
9. Messaging & notifications (§8): Room-backed `BackupMessage` writes with per-run coalescing, the
   two notification channels, type-driven `notifies` routing, per-run coalesced "problem"
   notifications with deep-link, cross-run throttling, and message retention/pruning. Unit tests
   for coalescing and throttle logic.
10. UI: onboarding carousel (incl. the notification-permission card), home list + FAB, add/edit
    flow (incl. create-new-or-pick-existing Drive folder, §7.3), detail screen with the message
    list and the "Check my password" action (§7.4), settings (§7) — building each screen with the
    §11 screenshot sight-loop (add `@Preview`, render, inspect PNG, iterate).
11. Tests (JUnit5 unit + Robolectric/Compose UI + CPST preview screenshots), Konsist architecture
    tests (starting from §12.2, incl. the Crashlytics-confinement guard from §9.1), a clean Detekt
    run, README (use §1), and a short ARCHITECTURE.md.

When something here is ambiguous or under-specified, ask before guessing. Keep every slice
compiling and prefer small, reviewable steps.
