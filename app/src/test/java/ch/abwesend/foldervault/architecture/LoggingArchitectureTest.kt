package ch.abwesend.folderVault.architecture

import com.lemonappdev.konsist.api.Konsist
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

class LoggingArchitectureTest : FunSpec({
    test("Crashlytics APIs are only referenced from infrastructure.logging") {
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
                    import.name.contains("crashlytics", ignoreCase = true)
                }
            }
            .map { it.path }
            .shouldBeEmpty()
    }
})
