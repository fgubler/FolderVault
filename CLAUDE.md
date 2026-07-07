# FolderVault — working conventions for Claude Code

## Build & verify commands
```bash
./gradlew assembleDebug          # compile
./gradlew test                   # JVM tests (Kotest + Robolectric + Konsist)
./gradlew detekt                 # static analysis
# ./gradlew updateDebugScreenshotTest   # CPST — enable once plugin is active
# ./gradlew validateDebugScreenshotTest
```

## Package
`ch.abwesend.foldervault` (all-lowercase).

## Architecture layers
```
view/           Compose screens, ViewModels, navigation (Nav3)
domain/         models, service interfaces, use-cases, BinaryResult
infrastructure/
  room/         entities, DAOs, database, migrations
  settings/     DataStore-backed settings repository
  crypto/       EncryptionRepository, AndroidKeyStoreRepository
  storage/      SAF / DocumentFile traversal
  cloud/        ICloudStorageProvider (abstraction) + googledrive/ impl
  backup/       scheduler, worker, analyzer→queue→uploader pipeline
  logging/      PrivateLogger (two-sink: LocalLogSink + CrashlyticsSink), ONLY place for Firebase
```
Dependency rule: `domain` depends on nothing; `view` and `infrastructure` may depend on `domain`
but not on each other. Cloud-provider specifics stay inside `infrastructure/cloud/googledrive/`.
Crashlytics confinement: ONLY `infrastructure/logging/CrashlyticsSink.kt` may import
`FirebaseCrashlytics` — enforced by `LoggingArchitectureTest`.

## Key conventions
- **`BinaryResult<TValue, TError>`** for any fallible operation. Use `runCatchingAsResult { }`.
- **`IDispatchers`** injected — never hard-code `Dispatchers.IO` etc.
- **`catch (e: Exception)`** must honor coroutine cancellation: call `e.rethrowCancellation()` first,
  unconditionally `throw e`, or pair it with a preceding `catch (X: CancellationException)`.
  Enforced by `CancellationRethrowArchitectureTest`.
- **Serial uploads**: one file fully encrypted + uploaded + indexed before the next. No parallelism.
- **Kotest** spec DSL for unit tests (e.g. `StringSpec`, `FunSpec`). Set
  `isolationMode = IsolationMode.InstancePerTest` when using MockK to get a fresh mock per test.
- **Konsist** architecture tests live in `src/test/.../architecture/`.
- **Robolectric** / Compose UI tests use JUnit4 (`@RunWith(RobolectricTestRunner::class)`) —
  they run on the JUnit5 platform via the Vintage engine.
- **Prefer hand-written fakes over MockK** for new tests of logic behind a domain / platform
  seam (see `IDatabaseFileAccess` + `DatabaseRecoveryServiceTest`): extract the platform access
  behind an interface and fake it. MockK's agent and Robolectric's jar download cannot run in
  the Bash sandbox, so MockK/Robolectric tests are only verifiable outside it.

### Style
- Prefer KDoc style comments over normal comments on methods, classes and properties
- Avoid early returns unless they bring a lot of benefit

## Sandbox: when to ask the user instead of working around it
Never change code, dependencies, or build config just to make something pass *inside* the
sandbox. If one of these comes up, stop and ask — each is a one-liner for the user:

- **A dependency (or new version) is not in the local Gradle cache** — Maven downloads are
  blocked in the sandbox. Do NOT downgrade/pin to whatever happens to be cached; ask the user
  to run `! ./gradlew assembleDebug` (the `!` prefix runs outside the sandbox) to fill the
  cache, then continue.
- **Robolectric tests fail with `Couldn't create lock file ~/.robolectric-download-lock`** or
  a `MavenDependencyResolver` download error — sandbox/profile issue, not a code issue. Ask the
  user to run the tests via `! ./gradlew test` (or to restart so the Gradle daemon picks up a
  corrected sandbox profile).
- **MockK fails with agent-attach errors / `Could not initialize class io.mockk.impl.JvmMockKGateway`**
  (plus cascading `NoClassDefFoundError`s in unrelated tests) — MockK cannot run in the sandbox
  at all. Write hand-written fakes for NEW tests; for existing MockK/Robolectric tests, ask the
  user to verify with `! ./gradlew test` instead of rewriting them.
- **The Gradle daemon misbehaves or holds stale state** (e.g. an old sandbox profile) — never
  run `./gradlew --stop` (it breaks the sandbox JDK toolchain); ask the user to restart the
  daemon / session.
- **`.claude/settings.local.json` needs a change** (sandbox allowlist, network hosts) — propose
  the exact edit and let the user approve/apply it.

Rule of thumb: if a failure message points at `~/.robolectric-download-lock`, agent attach,
"Could not resolve <artifact>", or "Operation not permitted" on a path outside the project,
it is the sandbox — report it and ask, don't adapt the code around it.

## v1 / v1.1 scope split
- **v1 always creates a fresh `FolderVault_<UUID>` cloud root.** No "use existing folder", no
  Google Picker, no §5.9 reconciliation. Reinstalling means re-uploading from scratch.
- **One shared root per install + a sub-folder per backup config.** The root id lives in
  `AppSettings` (DataStore); each `BackupConfigEntity` stores its `cloudSubFolderId` /
  `cloudSubFolderName`. Sub-folder names follow `<displayName>_<6-hex of SHA256(treeUri)>`
  (`SubFolderNameBuilder`) and are immutable after first creation — renaming `displayName`
  later does NOT rename the Drive folder (v1.1).
- **v1 writes** the per-run manifest (`.foldervault-manifest.json`) inside each sub-folder.
  The identity meta file (`.foldervault-meta.json` from spec §6.1) is **not** written in v1 —
  re-add it together with the Picker / re-attach flow in v1.1, where it will actually be read.
- Mark anything touching the Picker or reconciliation clearly as `// v1.1`.

## Definition of Done (run before each checkpoint hand-off)
- [ ] Tests written first for logic (§12.0.1)
- [ ] `./gradlew assembleDebug` — no errors
- [ ] `./gradlew test` — all green
- [ ] `./gradlew detekt` — no new issues
- [ ] UI screens visually verified via screenshots (§12.1)
- [ ] `docs/prompt-history.md` updated with a dated entry (§12.3)
- [ ] `CLAUDE.md` updated if a durable convention changed
- [ ] Slice summarized and handed back for review before the next (§12.0)

## Spec reference
Full specification: `docs/initialPrompt.md`
Prompt history: `docs/prompt-history.md`
