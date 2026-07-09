# FolderVault

An incremental folder-backup app for Android that continuously mirrors local folders to Google Drive with optional client-side encryption.

## What it does

FolderVault lets you set up **any number of independent backups**. Each backup continuously mirrors **one local folder (recursively)** to **one cloud folder** as an **append-only archive**. It is optimized for **files that rarely change** — photo libraries, document archives, scans, exports — not for live working directories with constant edits.

### Strengths
- **Multiple backups**: define several, each mapping a different folder to its own cloud destination, managed and scheduled independently.
- **Set-and-forget**: pick a folder once; new files get uploaded automatically on the configured schedule.
- **Incremental**: only new or changed files are uploaded. Unchanged files are never re-sent.
- **Safe by default**: cloud files are never deleted on their own, and deleting a file locally never deletes its backup. Cloud files are only ever removed when *you* opt in — by choosing the "overwrite changed files" policy or a retention policy that prunes old versions.
- **Private**: optional AES-256-GCM client-side encryption — the cloud provider sees only encrypted bytes.

### Limitations (honest)
- **One-way push only** — not a two-way sync. To recover files, download the backup folder from Google Drive using the Drive app and, if encrypted, decrypt the downloaded folder in this app.
- **Not ideal for frequently-changing files**: changed files are uploaded as new timestamped copies, so a file edited daily produces many cloud versions. A retention policy mitigates this.
- **Change detection by mtime + size, not by content**. A tool that rewrites a file without changing its mtime or size will not be detected as changed.
- **Re-installing the app means re-uploading from scratch** (v1). The cloud data is preserved; only the local upload index is lost.

## Setup

### Prerequisites
- Android 8.0 (API 26) or later
- A Google account for Google Drive storage

### Firebase configuration
After cloning the repository, add the Firebase configuration file before building:

```
app/google-services.json
```

This file is excluded from version control because it contains project-specific Firebase keys.
Obtain it from the Firebase console under **Project settings → Your apps → google-services.json**.

### macOS: Gradle JVM args

On macOS, add the following line to `local.properties` so Gradle has enough heap and a writable SQLite temp directory:

```
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8 -Djava.io.tmpdir=/Users/[username]/.gradle/tmp/java -Dorg.sqlite.tmpdir=/Users/[username]/.gradle/tmp/sqlite
```

Replace `[username]` with your macOS user name, and create the directory if it does not exist:

```bash
mkdir -p ~/.gradle/tmp/sqlite
```
That equivalent line in `~/.gradle/gradle.properties` needs to be adapted with these two tmpdir-paths as well.

### Build commands

```bash
./gradlew assembleDebug        # compile
./gradlew test                 # JVM unit tests (Kotest + Robolectric + Konsist)
./gradlew detekt               # static analysis
```

### Troubleshooting: MockK / Robolectric tests fail after a Claude Code session

If `./gradlew test` suddenly fails en masse with
`Could not initialize class io.mockk.impl.JvmMockKGateway`
(`Could not self-attach to current VM`) or
`Couldn't create lock file ~/.robolectric-download-lock (Operation not permitted)`,
the Gradle daemon was started inside Claude Code's macOS sandbox and is still carrying that
sandbox profile. Test workers forked by such a daemon cannot attach the MockK agent or write
the Robolectric lock file. Restart the daemon from a normal terminal:

```bash
./gradlew --stop
./gradlew test
```

The reverse also holds: a daemon started from a normal terminal is un-sandboxed, and later
sandboxed Claude Code builds that reuse it will run MockK/Robolectric tests fine.

## Restore (decrypt a locally-downloaded backup)

Encrypted backups can only be decrypted by this app. To restore:

1. Use the Google Drive app to download your backup folder to local storage.
2. Open FolderVault → tap the Restore icon in the top bar.
3. Pick the downloaded folder and an empty output folder.
4. Enter your backup password — every `.crypt` file will be decrypted locally.

No network connection is required for restore. The encryption key is derived from the password + the per-file salt embedded in each file header, so restore works on any device, even after reinstalling.
