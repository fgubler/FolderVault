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
```
Dependency rule: `domain` depends on nothing; `view` and `infrastructure` may depend on `domain`
but not on each other. Cloud-provider specifics stay inside `infrastructure/cloud/googledrive/`.

## Key conventions
- **`BinaryResult<TValue, TError>`** for any fallible operation. Use `runCatchingAsResult { }`.
- **`IDispatchers`** injected — never hard-code `Dispatchers.IO` etc.
- **Serial uploads**: one file fully encrypted + uploaded + indexed before the next. No parallelism.
- **Kotest** spec DSL for unit tests (e.g. `StringSpec`, `FunSpec`). Set
  `isolationMode = IsolationMode.InstancePerTest` when using MockK to get a fresh mock per test.
- **Konsist** architecture tests live in `src/test/.../architecture/`.
- **Robolectric** / Compose UI tests use JUnit4 (`@RunWith(RobolectricTestRunner::class)`) —
  they run on the JUnit5 platform via the Vintage engine.

## v1 / v1.1 scope split
- **v1 always creates a fresh `FolderVault_<UUID>` cloud root.** No "use existing folder", no
  Google Picker, no §5.9 reconciliation. Reinstalling means re-uploading from scratch.
- **v1 writes** the per-run manifest (`.foldervault-manifest.json`) and the identity meta file
  (`.foldervault-meta.json`) so v1.1 can add Picker + reconciliation without restructuring.
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
