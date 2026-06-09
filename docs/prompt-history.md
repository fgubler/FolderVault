# FolderVault — prompt history

Newest entries at the top. Each entry covers a coding session or a coherent unit of work.
Started from the first real coding task; the review/planning conversation is out of scope (§12.3).

---

<!-- New entries go here -->

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

