# FolderVault — prompt history

Newest entries at the top. Each entry covers a coding session or a coherent unit of work.
Started from the first real coding task; the review/planning conversation is out of scope (§12.3).

---

<!-- New entries go here -->

## 2026-07-08 — Charging-fallback review fixes: manual-run charging warning + message-log visibility

### What was requested
Fix findings 3 and 4 from the charging-only backup code review (`review.md`): (3) make the
charging fallback visible in the message log, and (4) warn the user when they tap "Back up now"
on a charging-only config while the device is not plugged in (mirroring the existing metered /
Wi-Fi override prompt).

### What was done (Task 4 — message-log visibility)
- New `MessageType.CHARGING_FALLBACK_SCHEDULED(notifies = false, R.string.msg_charging_fallback_scheduled)`.
- `ChargingFallbackTrigger.maybeSchedule` now also takes `backupMessageDao` + `runId` and writes
  one INFO `BackupMessage` via `coalesceInsert` at the moment the fallback is enqueued — still
  inside `BackupRunner`'s `NonCancellable` cancellation block. `messageText = null` so the row
  resolves its text from `type.labelResId` at display time (keeps the trigger free of a `Context`).
- `ChargingFallbackTriggerTest`: added the message DAO + runId to every call; asserts the message
  is inserted exactly once when the streak fires and never otherwise.

### What was done (Task 3 — manual-run charging warning)
- New `IChargingStateChecker` (domain/system) + `AndroidChargingStateChecker`
  (`BatteryManager.isCharging`), wired in `AppModule`. UI-hint only — the WorkManager charging
  constraint remains the real gate.
- `BackupDetailViewModel`: `showChargingOverridePrompt` state; `confirmChargingOverride` (run this
  once with `requiresCharging = false`) and `dismissChargingOverride` (cancel — schedules nothing).
  The metered and charging prompts resolve **sequentially** — the charging check runs only after
  the Wi-Fi prompt is settled, so the two dialogs never stack; the chosen network policy carries
  through via `pendingNetworkPolicy`.
- `BackupDetailScreen`: `ChargingOverrideDialog` (Back up anyway / Cancel) mirroring
  `MeteredOverrideDialog`, plus a `@Preview` for the new dialog.
- New strings: `dialog_charging_override_title/body` (reuses the shared `button_back_up_anyway` /
  `button_cancel`).
- `BackupDetailViewModelTest`: 5 new cases (prompt shown when unplugged; immediate schedule when
  charging; confirm; dismiss; and the metered→charging sequential combination).

### Decisions
- The charging dialog mirrors the metered one exactly: confirm→"Back up anyway" (run once without
  the charging constraint), dismiss / back / scrim→"Cancel" (schedules nothing — the normal
  periodic schedule still runs the backup once the device is charging). No "wait for charging"
  option, per review feedback.
- The fallback log row uses `messageText = null` + `MessageItem`'s existing `?: type.labelResId`
  fallback rather than resolving the string in the trigger — no `Context` needed there.

### Verified
- `./gradlew assembleDebug` ✅, `./gradlew detekt` ✅, `./gradlew test` ✅ (incl. Konsist).
  `BackupDetailViewModelTest` 13/13, `ChargingFallbackTriggerTest` 6/6.
- No `screenshotTest` source set exists (CPST disabled), so the screenshot sight-loop could not be
  run; the new dialog has a `@Preview` for when CPST is enabled.

## 2026-07-08 — Log-export result dialog no longer titled "Unexpected error"

### What was requested
On `DatabaseErrorScreen`, the dialog confirming a *successful* log-file export was shown with
the title "Unexpected error" (it reused `UnexpectedErrorDialog`). A success needs a fitting
title like "Export successful". The same misuse existed on `SettingsScreen`.

### What was done
- New `LogExportResultDialog(success: Boolean?, onDismiss)` component: title
  `dialog_export_log_success_title` ("Export successful") or `dialog_export_log_failed_title`
  ("Export failed"), body reuses the existing `export_log_success` / `export_log_failed` strings.
- `DatabaseGuardViewModel` and `SettingsViewModel` now expose the export outcome as
  `exportResult: StateFlow<Boolean?>` (+ `dismissExportResult()`) instead of a pre-baked
  `UiText`; the dialog derives title and body from the boolean.
- `exportTodayLogFile` now takes the destination URI as `String` (the screens call
  `uri.toString()`): the ViewModel only forwards it to `ILogExporter`, and dropping
  `android.net.Uri` keeps the ViewModel testable in plain-JVM Kotest without Robolectric.
- Tests (hand-written fakes): export success/failure exposure and dismissal in
  `DatabaseGuardViewModelTest`.

### Verified
`assembleDebug`, `detekt`, and the filtered `DatabaseGuardViewModelTest` run green in the
sandbox; full `./gradlew test` (MockK/Robolectric) left to the user.

## 2026-07-08 — Review fix Task 2: charging fallback keeps its constraint across continuations

### What was requested
Fix code-review finding 1 (`review.md`): when a run hits the time budget, `BackupWorker.doWork()`
re-enqueued via `scheduleOneTime(id, config.networkPolicy, config.requiresCharging)`. For a
charging-fallback run, `config.requiresCharging` is `false` by definition, so the continuation
silently lost the charging constraint AND moved from the fallback unique name to the one-time
name — the fallback protected only the first ~8-minute window, exactly where large backlogs need
it most.

### What was done
- **Mark fallback runs.** `BackupWorker.KEY_IS_CHARGING_FALLBACK` input-data flag, set in
  `BackupScheduler.scheduleChargingFallback`. `doWork()` reads it once at the top.
- **Extracted continuation decision.** New `BackupContinuationScheduler.scheduleContinuation(...)`
  (testable in isolation): a charging-fallback run re-enqueues via `scheduleChargingFallback`
  (forced charging constraint + dedicated name); every other run re-enqueues via `scheduleOneTime`
  carrying the config's own `requiresCharging`. `doWork()` now delegates to it. Because
  `scheduleChargingFallback` always re-sets the flag, a fallback that hits the budget repeatedly
  stays a fallback across every continuation.
- **KEEP-vs-REPLACE decision.** `scheduleChargingFallback` gained `replaceExisting: Boolean = false`.
  The continuation is enqueued from *within* the still-running fallback worker, which still holds
  the unique name (RUNNING is uncompleted work), so `ExistingWorkPolicy.KEEP` would silently
  swallow it. The continuation therefore passes `replaceExisting = true` → `REPLACE`. This
  self-replace supersedes the run that is already wrapping up (its DB row is already committed as
  `INITIAL_SYNC_IN_PROGRESS`, and the fallback trigger only fires from `BackupRunner`'s
  cancellation catch which has already returned — so no spurious CANCELLED row or re-trigger). The
  trigger path (`ChargingFallbackTrigger`) keeps the default `KEEP`, so duplicate fallbacks while
  one is pending are still no-ops.
- **Tests.** New `BackupContinuationSchedulerTest`: a fallback continuation calls
  `scheduleChargingFallback(replaceExisting = true)` and never `scheduleOneTime`; a normal
  continuation calls `scheduleOneTime` with the config's charging preference and never the
  fallback. Updated the hand-written `FakeBackupScheduler` in `DatabaseRecoveryServiceTest` for the
  new signature.

### Verified
`./gradlew assembleDebug`, `./gradlew test`, `./gradlew detekt` all green.

### Decisions carried forward
- `scheduleChargingFallback` is now dual-mode: KEEP for the trigger (dedupe pending fallbacks),
  REPLACE for a self-continuation. The charging constraint survives arbitrarily many continuations.

## 2026-07-08 — Review fix Task 1: separate WorkManager unique-work names for one-time runs

### What was requested
Fix code-review finding 5 (`review.md`): `BackupScheduler.scheduleOneTime` enqueued one-time work
under the SAME unique name as the periodic schedule (`BackupWorker.workName`) with
`ExistingWorkPolicy.REPLACE`. WorkManager shares one unique-name namespace across periodic and
one-time work, so every manual "back up now" and every time-budget continuation could permanently
cancel the config's periodic schedule — and nothing re-registered periodic work at app startup.
The user pre-decided the outcome ("skip the web-search: use unique names for one-time runs"), so
the verification step was skipped and the rename applied directly.

### What was done
- **Dedicated one-time name.** `BackupWorker.oneTimeWorkName(configId)` (prefix
  `foldervault_backup_one_time_`) alongside the existing periodic `workName` and
  `chargingFallbackWorkName`. `scheduleOneTime` now enqueues under this name, so its REPLACE can
  never touch the periodic schedule. The time-budget continuation in `BackupWorker.doWork()`
  already routes through `scheduleOneTime`, so it inherits the fix.
- **Concurrency guard moved into `BackupRunner`.** With one-time and periodic now under distinct
  names, WorkManager no longer serializes them. Added a process-wide per-configId `Mutex`
  (`perConfigLocks`) in the `BackupRunner` singleton; `runBackup` acquires it with `withLock` and
  delegates to a new private `runBackupExclusive`. A second run for the same config waits for the
  first (matching the serial-upload design); `withLock` is inline, so the lock releases on normal
  completion, cancellation, and error alike. Chosen over a WorkInfo state check because it needs
  no self-exclusion logic and can't race a still-starting worker.
- **`cancel` / `observeIsRunning` cover all three names** (periodic + one-time + charging fallback).
- **Startup safety net.** `FolderVaultApp.reRegisterPeriodicBackups()` re-registers periodic work
  for every non-paused config on app start via `schedulePeriodicIfNeeded` (which uses
  `ExistingPeriodicWorkPolicy.UPDATE` → idempotent). Guards against a broken DB like the existing
  `sweepStaleRunningBackupRuns`.
- **Tests.** New `BackupWorkNameTest` (pure, sandbox-runnable) asserts the three unique-work names
  are distinct, prefixed, and per-config. The scheduler's WorkManager wiring itself is only
  exercisable under Robolectric.
- Detekt: removed the now-stale `LongMethod` baseline entry for `runBackup` and moved the
  suppression inline onto `runBackupExclusive`.

### Verified
`./gradlew assembleDebug`, `./gradlew test`, `./gradlew detekt` all green.

### Decisions carried forward
- Manual/periodic/fallback runs each have their own WorkManager unique name; the in-process
  per-config mutex is now the sole guarantee that two runs of one config never overlap.
- Tasks 2–4 from `review.md` (fallback constraint on continuation, charging override prompt,
  fallback message-log row) are not yet done — stopped here for review per CLAUDE.md.

## 2026-07-07 — Opt-in completion notification after each finished backup run

### What was requested
A settings toggle to show a notification after each *finished* backup run — success or failure —
with the backup name, the number of uploaded files, and the outcome. Retried attempts and
cancelled runs must stay silent. Clarified with the user: when a run needs several attempts, the
notification only reports the file count of the last (successful) attempt — cross-attempt
aggregation was deemed not worth the complexity.

### What was done
- `AppSettings.notifyOnBackupCompletion` (default **off**) + DataStore key
  `notify_on_backup_completion` in `AppSettingsRepository`.
- Settings screen: new `NotificationsSection` composable with a `SwitchRow`; enabling the toggle
  immediately triggers the POST_NOTIFICATIONS runtime permission request (reusing the existing
  launcher). New `SettingsViewModel.setNotifyOnBackupCompletion`.
- `RunResult` now exposes `summary` as an abstract member (all variants already carried one).
- `BackupNotificationManager`:
  - new channel `foldervault_backup_completions` ("Finished backups", IMPORTANCE_DEFAULT);
  - pure `completionOutcomeOf(RunResult): BackupRunOutcome?` — `Success` → SUCCESS, unless
    `hitTimeBudget` (continuation re-enqueued → silent); `AuthLost` → silent (WorkManager
    retries); `FatalError` → FAILURE. Cancelled runs never produce a `RunResult`, so they are
    silent by construction;
  - `postCompletionNotificationIfEnabled(...)` gated on the setting, with a nullable file count
    (null when the run died before producing a summary); notification IDs use a `0x20000000`
    prefix so they never collide with problem-notification IDs;
  - deep-link pending intent + `nm.notify` extracted into shared `detailScreenPendingIntent` /
    `notifySafely` helpers (also used by the problems path).
- `BackupWorker`: posts the completion notification for terminal results only (mapped via
  `completionOutcomeOf`); `surfaceFatalError` also posts a FAILURE completion (no file count).
- Strings: channel name/description, success/failure titles, plural body
  `backup_notification_completion_text`, no-count failure body, settings label/description.
- Tests: `CompletionNotificationDecisionTest` (pure outcome mapping + ID-prefix isolation),
  two new `AppSettingsRepositoryTest` cases (default off, round-trip).

### Decisions carried forward
- Only the last attempt's upload count is reported — per-run, not per-attempt aggregation.
- A time-budget continuation (`hitTimeBudget`) is treated like a retry: silent until the final
  continuation run finishes.

## 2026-07-07 — Issue #17: UX improvements on the add/edit backup screen

### What was requested
GitHub issue #17: (1) keep the Save button visible at the bottom of the add/edit backup screen
instead of requiring the user to scroll to it; (2) show a confirmation dialog when the user
navigates back.

### What was done
- `AddEditBackupScreen`: the Save button (plus the inline validation error text) moved out of the
  scrolling column into a new `SaveBottomBar` wired into the Scaffold's `bottomBar`, with
  `imePadding` + `navigationBarsPadding` so it stays above the keyboard and system bars. The
  content column's own `imePadding` was dropped (the Scaffold inner padding now covers it).
- Back interception: `rememberConfirmingBackHandler()` registers a `BackHandler` (system
  back gesture) and returns the click handler for the top-bar back arrow; both show a
  `DiscardChangesDialog` ("Leave" / "Keep editing") before navigating back.
- New strings: `dialog_discard_changes_title/_body`, `button_discard`, `button_keep_editing`.
- `BackupDetailScreen`: the config-info section now shows an "Only while charging: Yes/No" row
  (the `requiresCharging` setting itself — independent of the automatic charging-only retry
  after repeated cancellations). The Pause/Resume button became **Disable/Enable** with no
  icon, to make clear it disables the backup config rather than pausing a running backup.
- `HomeScreen`: for consistency, the card indicator for a disabled backup changed from a
  Pause icon / "Paused" status text to a Block icon / "Disabled".

### Decisions
- A first version tracked a `pristineForm` baseline in the ViewModel and only asked when the
  form was actually dirty; on review the user chose the simpler behavior: **always ask on
  back**, no change detection. The dialog wording ("Leave without saving? Any changes you made
  will be lost.") is phrased so it is also correct when nothing was changed.

### Verification
- `assembleDebug` + `detekt` green (LongMethod on the screen composable resolved by extracting
  `rememberConfirmingBackHandler`). No new unit tests — the remaining logic is pure UI wiring.

## 2026-07-07 — Per-backup Google account (execution of PLAN-per-backup-google-account.md)

### What was requested
Read, question, improve, and execute the plan for letting each backup config use its own Google
account (previously the one authorized account was silently reused for every backup, with a
single install-wide `FolderVault_<UUID>` root in `AppSettings`).

### Decisions (asked before implementing)
- **Account is locked after creation** (plan's open decision): the add screen shows the system
  account chooser ("Connect to Google Drive" + a "Use a different account" button while unsaved);
  once the config is saved, its account can no longer be changed. Reconnecting in edit mode
  targets the config's stored account directly. This avoids stranded sub-folders and full
  re-uploads; moving a backup to another account = delete + recreate.
- The uncommitted firebaseBom downgrade (34.15.0 → 33.9.0, sandbox cache workaround) stays for now.

### What was done
- `AppSettings`: the three `cloudRoot*` fields replaced by `cloudRoots: List<CloudAccountRoot>`
  (new `@Serializable` domain type: account / rootFolderId / rootFolderName) + `rootForAccount()`.
  One root **per account** instead of one per install; still one sub-folder per config.
- `AppSettingsRepository`: `cloud_roots_json` DataStore key; migration-on-read from the three
  legacy keys (only when all three are present); the first write persists the JSON key and
  removes the legacy keys. Constructor now takes a `DataStore<Preferences>` (secondary
  constructor keeps the `Context` entry point) so tests run against a temp-file DataStore
  without Robolectric.
- `ICloudAuthorizer.authorize(accountName: String? = null)`; the Google impl targets the account
  via `AuthorizationRequest.Builder.setAccount(Account(name, "com.google"))` (verified present in
  cached play-services-auth 21.3.0). Request building extracted to internal
  `buildAuthorizationRequest()` as a testable seam.
- `AddEditBackupScreen`: system account chooser via `AccountManager.newChooseAccountIntent`
  (no permission needed on minSdk 26); `rememberOnConnectDrive()` branches add vs. edit mode;
  "Use a different account" `TextButton` shown only in add mode after connecting.
- `AddEditBackupViewModel`: `startDriveSetup(accountName)` (edit mode resolves the locked
  account from the existing config); `createCloudFolder` reuses/creates the root **per account**
  and appends to `cloudRoots`; `createNewSubFolder` authorizes with the connected account.
- Background pipeline authorizes with `config.cloudAccountIdentifier`: `BackupRunner` (initial
  auth + `writeRootMetadataWithReAuth`, which gained an account param) and
  `BackupUploader.handleAuthError`.
- Tests: `AppSettingsTest` (rootForAccount + JSON round-trip, pure Kotest),
  `AppSettingsRepositoryTest` (real DataStore on temp files — round-trip, legacy migration,
  legacy-key cleanup, corrupt-JSON fallback; sandbox-runnable), `GoogleDriveAuthorizationRequestTest`
  (Robolectric — account set/unset, scopes), `AddEditBackupViewModelTest` rewritten for
  per-account roots + account forwarding, `BackupUploaderTest` re-auth-with-account case.
- `CLAUDE.md` v1/v1.1 note updated: root is per account, account locked after creation.

### Verification
- `assembleDebug` + `detekt` green; all sandbox-runnable tests green (incl. the 9 new pure ones).
- MockK/Robolectric tests can't run in the Bash sandbox (known limitation) — full
  `! ./gradlew test` verification handed to the user, plus manual two-account device test.

## 2026-07-07 — Issues #13 + #15: no destructive migrations, database-error screen with recovery

### What was requested
Fix GitHub issues #13 (verify destructive DB migrations are never enabled) and #15 (when the
database fails to open, show an error screen with log export and a user-confirmed database
reset instead of crashing). Follow-up in a second session: the new tests failed inside the
Bash sandbox — adapt the code so the file-system access lives in its own class that tests can
replace, instead of working around the sandbox.

### What was done
- `IDatabaseRecoveryService` (domain) + `DatabaseRecoveryService` (infrastructure/room):
  health check forces the database open via `openHelper.writableDatabase`; reset deletes the
  database file(s), cancels all scheduled backup work, and reopens to a fresh schema.
- `IDatabaseFileAccess` + `RoomDatabaseFileAccess` (infrastructure/room): the physical
  file-system access (open / delete) extracted behind an interface so the recovery service is
  unit-testable without Robolectric or a real database file.
- `DatabaseGuard` (navigation) + `DatabaseGuardViewModel` + `DatabaseErrorScreen`: the app UI
  is only shown once the health check passes; the error screen offers "try again", "export
  today's log", and a confirmed destructive reset.
- `DatabaseArchitectureTest` (Konsist): production code must never call
  `fallbackToDestructiveMigration*` — guards issue #13 permanently.
- `DatabaseMigrationChainTest`: every schema-version bump needs a matching migration in
  `DatabaseMigrations.ALL`.
- Tests: `DatabaseRecoveryServiceTest` and `DatabaseGuardViewModelTest` rewritten as plain
  JVM Kotest specs with hand-written fakes (no Robolectric, no MockK).

### Issues resolved / environment findings
- Robolectric cannot run inside the Bash sandbox: it opens `~/.robolectric-download-lock`,
  but the sandbox allowlist entry is misspelled (`.roboelectric…`) — one-character fix in
  `.claude/settings.local.json` would unblock all Robolectric tests.
- MockK cannot run inside the sandbox either: its ByteBuddy agent fails to self-attach (JVM
  attach handshake is blocked); `-Djdk.attach.allowAttachSelf=true` does not help. Hence
  hand-written fakes for the new tests; existing MockK-based tests only run outside the sandbox.
- `firebaseBom` downgraded 34.15.0 → 33.9.0 and kept (user decision): 34.15.0 is not in the
  local Gradle cache and the sandbox blocks Maven downloads, while 33.9.0 resolves offline.

### Decisions carried forward
- The user-confirmed reset is the only "destructive migration" the app allows.
- New unit tests for logic around platform seams should abstract the seam (like
  `IDatabaseFileAccess`) and use fakes, so they stay runnable in restricted environments.

## 2026-07-03 — Settings: "Reliable background backups" section (battery optimization + Data Saver)

### What was requested
Help the user lift the two OS-level restrictions that can delay or block background backups:
battery optimization and Data Saver background-data blocking. Settings page only for now
(onboarding unchanged); deliberately no direct `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` request
to avoid Play Store policy risk — the app only jumps to the relevant system-settings screens.

### What was done
- `domain/system/IBackgroundRestrictionChecker` + `BackgroundRestrictionStatus`: domain seam for
  reading the two restriction states.
- `infrastructure/system/AndroidBackgroundRestrictionChecker`: `PowerManager
  .isIgnoringBatteryOptimizations()` and `ConnectivityManager.restrictBackgroundStatus ==
  RESTRICT_BACKGROUND_STATUS_ENABLED` (only Data-Saver-ON + not whitelisted counts as restricted).
- `SettingsViewModel.refreshBackgroundRestrictions()` exposes a `StateFlow`, re-read via
  `LifecycleResumeEffect` so the status updates when the user returns from the system settings.
- New settings section "Reliable background backups" between Notifications and Help: per
  restriction a status line (✓ in primary color when resolved), an info-icon popup explaining why
  resolving it helps, and a jump button:
  `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` (list screen, no permission needed) and
  `ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS` with `package:` URI (app-specific screen);
  both fall back to the app-details screen on `ActivityNotFoundException`.
- Added `androidx.lifecycle:lifecycle-runtime-compose` for `LifecycleResumeEffect`.
- Tests first: Robolectric shadow tests for the checker (5), Kotest ViewModel tests for the
  refresh flow (3 new); extracted `ReliableBackupsSection` / `HelpSection` composables to keep
  `SettingsContent` under the detekt `LongMethod` limit.
- Verified on the emulator: both buttons land on the correct system screens; granting the Doze
  exemption flips the status to ✓ on return to the app.

### Notes
- Emulator crash encountered during verification was a pre-existing stale-database Room identity
  mismatch from an older dev build — fixed by `pm clear`, unrelated to this change.
- Onboarding integration of the same section is a possible follow-up (reuse
  `ReliableBackupsSection`).

## 2026-07-03 — Restore screen: retry after wrong password, clearer texts

### What was requested
1. After a failed restore attempt (e.g. wrong password), allow the user to fix the password and
   retry directly — previously the "Start restore" button stayed disabled and the user had to
   reset the whole flow.
2. Rename the reset button ("Restore again") to make clear it clears the selection and starts over.
3. Adapt the helper text: if the Google Drive app is installed, folders on Google Drive can be
   selected directly in the system file picker, so backups can be restored straight from Drive
   without downloading first.

### What was changed
- `RestoreScreen.kt`: `PasswordAndStartSection` is now also enabled when the state is
  `RestoreState.Done` (not only `ReadyToStart`), enabling a retry with the same source/output.
- `strings.xml`:
  - `button_restore_again` → `button_restore_start_over` ("Start over (clear selection)").
  - `restore_explanation_body` rewritten: picking the backup folder directly on Google Drive via
    the file picker is now the primary path; downloading first is the fallback.
  - `restore_step1_header`: "downloaded backup folder" → "backup folder" (consistency).

### Verification
- `assembleDebug`, `test`, `detekt` all green. No tests reference the composable directly
  (pure UI-state wiring change; the ViewModel logic is unchanged).

## 2026-07-02 — Per-backup "only run while charging" + cancellation-streak fallback

### What was requested
1. Per-backup **"only run while charging"** toggle (default off).
2. When the toggle is off but a backup gets cancelled repeatedly, schedule a **single one-off
   charging-only run** without displacing the normal periodic schedule — a user who always turns
   the phone off while charging must still see periodic non-charging attempts.

### Design decisions
- **Trigger rule**: 3 consecutive cancellations. Any success resets the streak.
- **Threshold hard-coded** (`ChargingFallbackTrigger.CANCELLATION_STREAK_THRESHOLD = 3`) — not
  user-configurable.
- **Fallback isolation**: distinct WorkManager unique-work name
  (`foldervault_backup_charging_fallback_<configId>`), enqueued with `ExistingWorkPolicy.KEEP`
  so a second cancellation while a fallback is pending is a no-op. Periodic work under
  `foldervault_backup_<configId>` is untouched.
- **Short-circuit** when `requiresCharging=true`: no DAO query, no fallback — the periodic
  schedule already carries the constraint.
- **UI**: single `Switch` with info-icon popup inside the existing `ScheduleSection`, plain-language
  body text (matches the UX text-style convention).

### What was done
- **Room migration**: `BackupConfigEntity` gained `requiresCharging: Boolean = false`; database
  bumped v2 → v3; `MIGRATION_2_3` adds `INTEGER NOT NULL DEFAULT 0`; schema JSON regenerated at
  `app/schemas/.../3.json`.
- **DAO**: added `BackupRunDao.getRecentStatuses(configId, limit)` for streak detection.
- **Domain**: `BackupConfig.requiresCharging` field + repository mapping updates.
- **Scheduler interface**: `scheduleOneTime` / `schedulePeriodicIfNeeded` gained the flag;
  new `scheduleChargingFallback(configId, networkPolicy)` method; `buildConstraints` calls
  `setRequiresCharging`; `observeIsRunning` combines primary + fallback flows; `cancel`
  clears both unique-work names.
- **Runner**: `BackupRunner` gained `scheduler` ctor param; in its `NonCancellable`
  `CancellationException` catch it calls `ChargingFallbackTrigger.maybeSchedule(...)` right
  after `commitRunStats`. Streak-detection logic extracted into `ChargingFallbackTrigger` so
  it's unit-testable without spinning up the full runner.
- **DI**: `AppModule` wires the new scheduler argument into the `BackupRunner` factory.
- **ViewModels**: `AddEditBackupViewModel` — new form state field, setter, load/save
  propagation. `BackupDetailViewModel` — `backUpNow`, `confirmMeteredOverride`, `togglePause`
  all pass `current.requiresCharging`.
- **UI**: `AddEditBackupScreen.ScheduleSection` renders a `Row` with a label + info icon +
  `Switch`, matching the encryption-toggle pattern. New strings: `label_requires_charging`,
  `info_requires_charging_title`, `info_requires_charging_body`.

### Tests
- **New** `ChargingFallbackTriggerTest` — 5 Kotest cases: streak reached triggers fallback,
  streak broken by a success, insufficient history, short-circuit when `requiresCharging=true`,
  network-policy propagation. All green.
- **New** `MIGRATION_2_3` tests (default 0, existing rows preserved) — added to
  `DatabaseMigrationTest`. Total 5 tests, all green.
- **New** `BackupRunDao.getRecentStatuses` tests — 3 cases (ordering, limit, per-config
  isolation) added to `BackupRunDaoTest`. Total 11 tests, all green.
- **Updated** `BackupDetailViewModelTest` fixtures and `scheduler.scheduleOneTime(...)` verify
  calls to the 3-arg signature. 8 tests, all green.
- Other existing test fixtures compile unchanged thanks to the entity's
  `requiresCharging: Boolean = false` default.

### DoD gates
- ✅ `./gradlew assembleDebug` — clean
- ✅ `./gradlew test` — all green (ChargingFallbackTrigger 5/5, BackupRunDao 11/11,
  DatabaseMigration 5/5, BackupDetailViewModel 8/8, all other suites unchanged)
- ✅ `./gradlew detekt` — no issues
- ⏭ UI screenshot verification — pending (user to launch on emulator/device and confirm the
  toggle appears in Schedule & network, info dialog opens, and persistence survives an app
  restart)

## 2026-06-30 — Run-history follow-up: no more stuck-RUNNING rows + migration test

### What was done
Closed the gap from the previous slice where worker cancellation / auth-lost / process death left
`BackupRun` rows stuck in `RUNNING` forever, and added the missing migration test.

- **New status `BackupRunStatus.CANCELLED`** — distinct from `FAILED` (real error) so cancellation
  reads as "stopped, not broken" in the run-history list. Border colour: `outline`.
- **`BackupRunner` cancellation path** (`catch (e: CancellationException)`) now writes a final
  `CANCELLED` row before rethrowing, wrapped in `withContext(NonCancellable)` — the surrounding
  coroutine is already cancelled, so plain DAO calls would themselves throw and the row would
  stay stuck.
- **`BackupRunner` auth-lost early return** now calls `commitRunStats(..., FAILED, ...)` before
  returning `RunResult.AuthLost`. Previously the row was inserted as RUNNING and never updated.
- **Process-death sweeper** in `FolderVaultApp.onCreate`: on each app start, any RUNNING row
  older than 24h (`BackupRunDao.STALE_GRACE_WINDOW_MS`) is flipped to CANCELLED via
  `markStaleRunningAsCancelled(staleBefore, now)`. Background coroutine on `Dispatchers.IO`.

### Resumption semantics (decided)
A retried/scheduled-after-cancel run creates a **new row with a new `runId`**, not a continuation
of the cancelled one. Three reasons this fits the existing model:
1. `runBackup()` generates a fresh UUID per call.
2. File-level continuation is via `UploadedFileIndex` (already-uploaded files are skipped); the
   run-history table records per-attempt metadata only.
3. `INITIAL_SYNC_IN_PROGRESS` already creates multiple rows for one logical sync — same shape.

### Tests added
- `DatabaseMigrationTest` — opens a raw `SupportSQLiteDatabase` at v1 (only the BackupConfig
  table is created; other v1 tables are irrelevant to MIGRATION_1_2), runs the migration,
  verifies the new BackupRun table accepts inserts, all three indices exist, BackupConfig rows
  survive, and the FK cascade is active. Written **without** `MigrationTestHelper` — under
  AGP 9 the `$projectDir/schemas` directory does not get merged into the unit-test asset path
  that the helper reads from, so the helper throws `FileNotFoundException`. The raw-SQL
  approach is independent of asset wiring and still covers the migration's actual behaviour.
- `BackupRunDaoTest` — two new cases for `markStaleRunningAsCancelled` (flips only stale
  RUNNING rows; no-op when none are stale; finalised rows untouched even if old).
- `DatabaseMigrations.MIGRATION_1_2` visibility lifted from `private` to `internal` so the
  test in the same module can reference it directly.

### Files modified
`BackupRunStatus`, `strings.xml` (status_cancelled), `BackupRunDao` (sweeper query + grace
window const), `DatabaseMigrations` (visibility), `BackupRunner` (CancellationException path,
AuthLost path), `FolderVaultApp` (sweeper on startup), `BackupRunHistoryScreen` (CANCELLED
border colour).

### Files created
`DatabaseMigrationTest.kt`.

### Checks
`./gradlew assembleDebug` ✓ — `./gradlew test` ✓ (176 tests, +2) — `./gradlew detekt` ✓.
Manual smoke (cancel a running backup, kill the process mid-run, see rows flip to CANCELLED)
still pending — needs the app on a device.

### Decisions carried forward
- 24h grace window before sweep: long enough for legitimately long backups; a row older than
  this with no completion has effectively died with the process.
- Auth-lost is `FAILED`, not `CANCELLED` — the user can't recover without re-authorising, so
  the red border accurately conveys "this needs your attention".
- The `INITIAL_SYNC_IN_PROGRESS` border is unchanged (still primary/blue) — by design, that
  status represents a finalised-but-partial run, distinct from a cancelled one.

---

## 2026-06-30 — Backup run history sub-screen

### What was done
Persistent run history for backups, surfaced as a dedicated sub-screen reached from the backup detail screen.

- **Storage**: new Room entity `BackupRun` with a 1→2 migration. Per-config cap of 100 runs, pruned after each completed run. Schema v2 JSON exported under `app/schemas/`.
- **Lifecycle hooks**: `BackupRunner` writes a `RUNNING` row at run start and updates it with `completedAt` + final status/counts via `commitRunStats(runId, …)`. Cancellation / process-death paths leave the row with `completedAt = null`, status `RUNNING` — surfaces in the UI as "in progress".
- **UI**: new `BackupRunHistoryScreen` reached via a full-width `OutlinedButton` ("Run history") on `BackupDetailScreen`. List items mirror `MessageItem` — status-coloured border, timestamp header, file counts + duration.
- **Per-entry fields**: startedAt, completedAt, status, filesUploaded, filesSkipped, filesFailed, bytesUploaded.

### Files created
`BackupRunEntity`, `BackupRunDao`, `BackupRun` (domain), `IBackupRunRepository`, `BackupRunRepository`, `BackupRunHistoryViewModel`, `BackupRunHistoryScreen`, `BackupRunDaoTest`.

### Files modified
`FolderVaultDatabase` (v2 + new DAO), `DatabaseMigrations` (`MIGRATION_1_2`), `BackupRunner` (insert RUNNING row + `commitRunStats(runId, …)` updates + `pruneOld`), `AppModule` (DAO + repository + VM + threaded into `BackupRunner` factory), `AppDestination` (`BackupRunHistory(configId)`), `AppNavGraph`, `BackupDetailScreen` (`onShowRunHistory` plumbed through), `strings.xml`, `RoomDatabaseTest` (smoke-asserts new DAO).

### Issues resolved this session
- macOS APFS case-flip: the test directory had drifted to `app/src/test/java/ch/abwesend/folderVault/` (capital V) while git tracks `foldervault/` (lowercase). Detekt's `InvalidPackageDeclaration` rule reads the actual on-disk casing and flagged every file under `test/`. Fixed via the two-step rename (`folderVault` → `folderVault_tmp_rename` → `foldervault`) — the same workaround used in §14.1.
- Dropped an unused `BackupRunEntity` import from `RoomDatabaseTest.kt` (only `db.backupRunDao()` is referenced; the entity class itself is not).

### Checks
`./gradlew assembleDebug` ✓ — `./gradlew test` ✓ — `./gradlew detekt` ✓.
Manual smoke (run history list, RUNNING vs final status, CASCADE-on-config-delete) is still pending — needs the app on a device.

### Decisions carried forward
- The early-exit fatal path in `BackupRunner` (config-not-found) is intentionally **not** logged to history — there is no row to attach it to.
- Pruning uses the same SQL pattern as `BackupMessageDao.pruneOldestOverLimit` (DELETE … WHERE id NOT IN (SELECT id … ORDER BY startedAt DESC LIMIT 100)).
- `BackupRunStatus.RUNNING` was already in the domain enum; reused as the in-progress sentinel rather than adding a new state.
- `displayName` of a config is the user-visible label only; the cloud sub-folder name remains immutable (per §14.x v1 scope) and history rows are pinned to `configId`, so renames do not affect history.

---

## 2026-06-19 — Idempotent Drive uploads (fix retry-induced duplicates)

### Bug
Encrypted backups occasionally produced a duplicate of one file (different Drive file IDs, different timestamps) when the source folder contained multiple files. Reproducible intermittently; only on encrypted runs.

### Root cause
`Drive.files().create()` is not idempotent. `DriveRetryPolicy.withRetry` retried on `CloudTransientException` (IOException → classified as transient), so a network failure on the **response** path — after Drive already committed the upload — produced a second create call and a second file with the same name. Small files (single-round-trip direct multipart upload) are the most exposed because there's no chunk-level recovery; the user's 8 KB PDF surfacing as the duplicated one matches this.

### Fix
- **`DriveRetryPolicy.withRetry`** gains an optional `verifyAlreadySucceeded: suspend () -> T?` callback. It runs at the start of every *retry* attempt (never the first); a non-null return short-circuits the retry. Exceptions inside the probe are swallowed so a transient verify failure can't derail the actual retry.
- **`GoogleDriveRepository.uploadFile`** uses the hook to find a file in the parent by name (mime ≠ folder, excluding caller-supplied `excludeIds`) and reuses it if present, instead of creating a second copy.
- **`GoogleDriveRepository.createRootFolder`** had a parallel bug — `UUID.randomUUID()` was generated *inside* the retry block, so a retry produced a fresh name and a second orphan folder. UUID hoisted outside the retry; verify hook added so the just-created folder is reused.
- **`ICloudStorageProvider.uploadFile`** signature: added `excludeIds: Set<String> = emptySet()`. CHANGED_OVERWRITE must exclude `previousCloudFileId` so the prior version isn't mistaken for the just-uploaded duplicate the probe is hunting for.
- **`BackupUploader`** passes `setOfNotNull(task.previousCloudFileId)` through `tryUpload` and `handleAuthError`.

### Tests
- `DriveRetryPolicyTest`: `verifyAlreadySucceeded` not invoked on first attempt; non-null return short-circuits the block; null lets retry proceed; throwing probe doesn't derail the retry.
- `BackupUploaderTest`: NEW upload passes `emptySet()`; CHANGED_OVERWRITE passes `setOf(previousCloudFileId)`; recovery via short-circuit path completes with a single uploadFile call, `filesUploaded = 1`, `filesFailed = 0`.

### Decisions carried forward
- The find-by-name probe filters out folders (`mimeType != FOLDER_MIME_TYPE`) so a folder accidentally sharing the name can't be picked as a file.
- When the probe finds multiple candidates after exclusion, the newest by `createdTime` wins; older entries are logged but **not** deleted automatically — they're left for a future orphan-reaper pass to avoid silently destroying user data on a probe false positive.
- `assembleDebug` currently fails on this working tree because of an unrelated in-progress lifecycle/Nav3 bump (lifecycle 2.10→2.11 needs compileSdk 37). Not introduced by this change; `./gradlew test` and `./gradlew detekt` both pass.

---

## 2026-06-19 — Remove unused `.foldervault-meta.json` write

### What was done
- **Identified that the meta file is write-only in v1.** `BackupMeta.CLOUD_FILE_NAME` was written by `AddEditBackupViewModel.writeMetaFile()` after `createRootFolder()` but never read anywhere — `readRootMetadata` is only called for the per-run manifest (`BackupRunner`). The meta file is part of the v1.1 Picker / re-attach flow (spec §6.1, §7.3) that doesn't exist yet.
- **`AddEditBackupViewModel`**: dropped `writeMetaFile()` and its call from `createCloudFolder()`. Removed now-dead imports: `BackupMeta`, `CloudAuthException`, `ErrorResult`, `kotlinx.serialization.json.Json`, `java.time.Instant`.
- **Deleted both `BackupMeta` data classes**: `domain/backup/BackupMeta.kt` (the wired-up one) and `domain/cloud/BackupMeta.kt` (a duplicate that was never referenced — leftover from an earlier package layout).
- **`AddEditBackupViewModelTest`**: replaced the "writes meta file with CLOUD_FILE_NAME" test with "transitions to Done after folder is created" — asserts the cloud-setup state machine reaches `CloudSetupState.Done` with the expected `folderId`. The mocked `writeRootMetadata` expectation was removed.
- **`ICloudStorageProvider.writeRootMetadata` / `readRootMetadata` kept** — still used by `BackupRunner` for the per-run manifest.
- **`CLAUDE.md`**: updated the v1 / v1.1 scope-split bullet to record that the meta file is *not* written in v1 and should be re-added together with the Picker / re-attach flow in v1.1.

### Rationale
v1 always creates a fresh `FolderVault_<UUID>` root, so there is no flow that consults the meta file — the marker / display-name / created-at / encrypted-flag are only useful for re-attach UI. Writing a file that no code reads adds maintenance surface (two duplicate data classes, a test that only asserted the side-effect existed) for no v1 behaviour. v1.1 re-add will be straightforward: restore the data class, the `writeMetaFile` call site, and pair it with the read path.

### Checks
`./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:testDebugUnitTest` ✓ — the new test passes. The pre-existing detekt failure on `BackupDetailScreen.kt:79` (`withWrapHints` unused, from an uncommitted UX change at session start) is unrelated to this slice.

---

## 2026-06-11 — review-plan: Tier 6 (oversized files — switch to deferral semantics)

### What was done

- **6.1 `BackupUploader.processChannel`**: Removed the tier branch that dropped `OVERSIZED` tasks. All non-skipped tasks now call `uploadOne` regardless of tier. The post-loop `FILE_TOO_LARGE` emission block is the only place the tier is inspected.
- **6.2 `RunSummary.oversizedCount` rename**: Renamed to `oversizedUploaded` and added a companion field `oversizedDeferred: Int`. All read/write sites in `BackupUploader` updated.
- **6.3 Deferral tracking + single post-loop message**: In `processChannel`, when `shouldSkip && task.tier == OVERSIZED`, `summary.oversizedDeferred++` is incremented. After the loop, if `hitTimeBudget && oversizedDeferred > 0`, a single `FILE_TOO_LARGE` message at `INFO` severity is emitted (using the existing `coalesceInsert` dedup).
- **6.4 `strings.xml` copy update**: Updated `msg_file_too_large` to deferral wording ("Some large files were deferred and will be uploaded in the next run."). Renamed `label_default_file_size_limit` to "Defer files larger than (MB)" and updated `info_file_size_limit_title`/`info_file_size_limit_body` to describe deferral rather than a hard cap.
- **6.5 `BackupUploaderTest` replacement**: Deleted the old incorrect OVERSIZED test (asserted no-upload + WARNING). Added four new tests: (a) successful oversized upload increments `oversizedUploaded` and `filesUploaded`; (b) OVERSIZED task skipped after `hitTimeBudget` increments `oversizedDeferred` and emits exactly one INFO `FILE_TOO_LARGE` message; (c) two deferred OVERSIZED tasks still emit exactly one message; (d) no deferred OVERSIZED tasks means no `FILE_TOO_LARGE` message.

### All checks passed
`./gradlew assembleDebug` ✓ `./gradlew test` ✓ `./gradlew detekt` ✓

---

## 2026-06-11 — review-plan: Tier 5 (verification follow-ups)

### What was done

- **5.1 `WorkerErrorHandler`**: Added explicit `catch (e: CancellationException) { throw e }` before the broad `catch (e: Exception)` handler, so coroutine cancellation is no longer swallowed.
- **5.2 + 5.7.b Folder display-name helper**: Extracted `displayNameFromUri(uri: Uri): String` into new `view/util/UriUtils.kt`. Both call sites updated: `AddEditBackupScreen.kt` now calls the shared helper (gains the `takeIf { it.isNotBlank() }` guard it was missing); `AddEditBackupViewModel.extractFolderDisplayName` rewritten as a single-expression function using the helper.
- **5.3 Quota counter reset**: Added `summary.consecutiveQuotaCount = 0` on the success path in `BackupUploader.tryUpload`, so the counter genuinely tracks *consecutive* errors rather than total errors in the run.
- **5.4 Cross-run progress reset**: Narrowed the reset condition to `completedNormally` only. Failed and quota-exceeded runs (without `hitTimeBudget`) now leave the cross-run counters unchanged, matching the spec intent.
- **5.5 `Fvc1Cipher.toDecryptionError`**: Deleted the one-liner private wrapper; both call sites now call `classifyDecryptionError(...)` directly.
- **5.6 String naming**: Accepted current naming (`notif_problem_*`, `label_default_file_size_limit`, `", "` separator) as the new convention — no rename.
- **5.7.a `BackupDetailViewModel.backUpNow`**: Replaced `if (...) return` guard with `if (!...) { ... }` single-exit form.
- **5.7.c `RestoreViewModel.startRestore`**: Replaced two `?: return` early exits with a single `if (src != null && out != null)` guard wrapping the entire launch block.
- **5.7.d `BackupRunner` encryption-key null check**: Left as-is per the plan recommendation (already at 3–4 indentation levels; wrapping would push to 4–5).

### All checks passed
`./gradlew assembleDebug` ✓ `./gradlew test` ✓ `./gradlew detekt` ✓

---

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

