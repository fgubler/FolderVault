package ch.abwesend.folderVault.architecture

import com.lemonappdev.konsist.api.Konsist
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * Crashlytics access is confined to the `infrastructure.logging` package — see `CLAUDE.md`.
 *
 * We match on the actual `com.google.firebase.crashlytics.*` import path (not just any
 * identifier containing "crashlytics"), so importing our own `CrashlyticsSink` class from
 * elsewhere — e.g. `FolderVaultApp` wiring it into [LoggerProvider] — is intentionally NOT
 * a violation. The rule we enforce is: only this one package may touch the Firebase SDK.
 */
class LoggingArchitectureTest : FunSpec({
    test("Firebase Crashlytics APIs are only referenced from infrastructure.logging") {
        val loggingPackage = "ch.abwesend.foldervault.infrastructure.logging"
        Konsist.scopeFromProject()
            .files
            .filterNot { file ->
                val path = file.path
                path.contains("/test/") ||
                    path.contains("/androidTest/") ||
                    (file.packagee?.name?.startsWith(loggingPackage) == true)
            }
            .filter { file ->
                file.imports.any { import ->
                    import.name.startsWith("com.google.firebase.crashlytics")
                }
            }
            .map { it.path }
            .shouldBeEmpty()
    }
})
