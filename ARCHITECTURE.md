# FolderVault — Architecture

## Layer overview

```
┌─────────────────────────────────────────┐
│  view/          Compose screens + VMs   │
│  (Navigation3, Material3, Koin VMs)     │
└────────────────┬────────────────────────┘
                 │ depends on
┌────────────────▼────────────────────────┐
│  domain/        Pure Kotlin models,     │
│                 interfaces, BinaryResult│
└────────────────▲────────────────────────┘
                 │ depends on
┌────────────────┴────────────────────────┐
│  infrastructure/  Android + I/O         │
│  (Room, WorkManager, SAF, Drive SDK,    │
│   DataStore, Fvc1Cipher, logging)       │
└─────────────────────────────────────────┘
```

**Rules enforced by Konsist tests (`ArchitectureLayerTest`):**
- `domain` depends on nothing (no Android, no infrastructure imports)
- `view` depends on `domain` only — never on `infrastructure` directly
- `infrastructure` depends on `domain` only — never on `view`
- Firebase/Crashlytics imports confined to `infrastructure/logging/CrashlyticsSink.kt`
- Google Drive SDK imports confined to `infrastructure/cloud/googledrive/`

## Package structure

```
ch.abwesend.foldervault
├── domain/
│   ├── backup/       BackupConfig, BackupMessage, IBackupConfigRepository,
│   │                 IBackupMessageRepository, IBackupScheduler, CloudManifest
│   ├── cloud/        ICloudStorageProvider, ICloudAuthorizer, CloudEntry,
│   │                 CloudAuthResult, CloudException, BackupMeta
│   ├── coroutine/    IDispatchers, AppDispatchers, CoroutineUtil
│   ├── crypto/       IFvc1Cipher, IEncryptionRepository, IKeyStoreRepository,
│   │                 Fvc1Header, DecryptionError
│   ├── logging/      ILogger, Logger, LoggerProvider, FileNameRedactor
│   ├── model/        AppSettings, AppTheme, BackupRunStatus, BackupSchedule, …
│   ├── restore/      IRestoreEngine, RestoreResult, RestoreProgress,
│   │                 RestoreCollisionPolicy, RestoreScanResult
│   ├── result/       BinaryResult<TValue,TError>, SuccessResult, ErrorResult,
│   │                 ResultExtensions (runCatchingAsResult, mapError, …)
│   ├── settings/     IAppSettingsRepository
│   └── util/         Constants, KoinUtil
│
├── infrastructure/
│   ├── backup/       BackupConfigRepository, BackupMessageRepository,
│   │                 BackupRunner, BackupUploader, BackupWorker, BackupScheduler,
│   │                 FileSystemAnalyzer, ChangeDetector, RemoteNameBuilder,
│   │                 RetentionManager, MessageRetentionManager, StagingDirManager,
│   │                 BackupNotificationManager, WorkerErrorHandler, RunSummary
│   ├── cloud/
│   │   └── googledrive/  GoogleDriveRepository, GoogleDriveAuthorizationRepository,
│   │                     DriveErrorClassifier, DriveRetryPolicy
│   ├── crypto/       Fvc1Cipher, EncryptionRepository, AndroidKeyStoreRepository
│   ├── logging/      PrivateLogger (two-sink), LocalLogSink, CrashlyticsSink
│   ├── restore/      RestoreEngine, RestorePathResolver
│   ├── room/         FolderVaultDatabase (v2), DatabaseMigrations,
│   │   ├── dao/          BackupConfigDao, UploadedFileIndexDao,
│   │   │                 BackupMessageDao, NotificationThrottleStateDao
│   │   ├── entity/       BackupConfigEntity, UploadedFileIndexEntity,
│   │   │                 BackupMessageEntity, NotificationThrottleStateEntity,
│   │   │                 EncryptionParams
│   │   └── converter/    RoomTypeConverters
│   ├── settings/     AppSettingsRepository (DataStore-backed)
│   └── storage/      ScopedStorageHelper (SAF/DocumentFile traversal)
│
└── view/
    ├── components/   EnumDropdown
    ├── navigation/   AppDestination, AppNavGraph
    ├── screens/      HomeScreen, OnboardingScreen, SettingsScreen,
    │                 AddEditBackupScreen, BackupDetailScreen, RestoreScreen
    └── viewmodel/    HomeViewModel, OnboardingViewModel, SettingsViewModel,
                      AddEditBackupViewModel, BackupDetailViewModel, RestoreViewModel
```

## Key patterns

### `BinaryResult<TValue, TError>`
All fallible operations return `BinaryResult` rather than throwing. Use `runCatchingAsResult { }` to wrap throwing code, and `.mapError { }` / `.mapValue { }` to transform results. `SuccessResult` and `ErrorResult` are the two concrete subtypes.

### `IDispatchers`
Coroutine dispatchers are injected via `IDispatchers` (implemented by `AppDispatchers`) rather than hard-coded. This makes all dispatcher-using code testable without reflection.

### FVC1 encryption container
Each `.crypt` file is self-describing: the FVC1 header embeds the PBKDF2 salt and AES-GCM IV. Key derivation runs once per backup per run (PBKDF2, 310 000 iterations); the derived `SecretKey` is reused across all files in that run. Restore works on any device from the downloaded files alone — no local database needed.

### Upload pipeline
Files are uploaded **serially**, one at a time: analyze → queue (two-tier: normal then oversized) → encrypt to staging temp file → upload → index. The `BackupWorker` enforces a run-time budget and re-enqueues promptly to drain large initial syncs across multiple runs.

### Notification throttling
`BackupNotificationManager` throttles repeated problem notifications per backup × error-type pair using `NotificationThrottleStateDao`. A problem notification carries a deep-link `foldervault://backup/detail/<configId>` to open the detail screen directly.

### Room schema (v2)
Room database version 2 (migration 1→2 adds `isPaused` column). Foreign-key constraints with `onDelete = CASCADE` between `UploadedFileIndex` → `BackupConfig` and `BackupMessage` → `BackupConfig`. FK enforcement enabled via `execSQL("PRAGMA foreign_keys = ON")` in the open callback.

## Testing

| Layer | Approach |
|---|---|
| Pure domain logic | Kotest `StringSpec` / `FunSpec`, pure JVM |
| Crypto | Kotest `StringSpec`, round-trip with `ByteArrayInputStream/OutputStream` |
| Room / DAOs | Robolectric `@RunWith(RobolectricTestRunner)`, in-memory database |
| Compose screens | Robolectric + `createComposeRule()` |
| Architecture | Konsist guards in `src/test/.../architecture/` |
| Static analysis | Detekt with custom rule set (`config/detekt/detekt.yml`) |
