package ch.abwesend.folderVault.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue
import io.kotest.core.spec.style.FunSpec

class ArchitectureLayerTest : FunSpec({

    val mainScope = Konsist.scopeFromProject()
        .slice { it.path.contains("/main/") && !it.path.contains("/test/") }

    test("domain layer has no android or infrastructure imports") {
        mainScope
            .files
            .filter { it.packagee?.name?.startsWith("ch.abwesend.foldervault.domain") == true }
            .assertTrue {
                it.imports.none { imp ->
                    imp.name.startsWith("android.") ||
                        // androidx.annotation.* is allowed: annotation-only, no Android runtime dep
                        (imp.name.startsWith("androidx.") && !imp.name.startsWith("androidx.annotation.")) ||
                        imp.name.startsWith("ch.abwesend.foldervault.infrastructure")
                }
            }
    }

    test("view layer does not import infrastructure directly") {
        mainScope
            .files
            .filter { it.packagee?.name?.startsWith("ch.abwesend.foldervault.view") == true }
            .assertTrue {
                it.imports.none { imp ->
                    imp.name.startsWith("ch.abwesend.foldervault.infrastructure")
                }
            }
    }

    test("only CrashlyticsSink imports FirebaseCrashlytics") {
        mainScope
            .files
            .filter { file ->
                !file.path.contains("CrashlyticsSink") &&
                    !file.path.contains("/test/") &&
                    !file.path.contains("/androidTest/")
            }
            .assertTrue {
                it.imports.none { imp ->
                    imp.name.contains("crashlytics", ignoreCase = true)
                }
            }
    }

    test("cloud provider specifics stay inside infrastructure.cloud.googledrive") {
        mainScope
            .files
            .filter { file ->
                val pkg = file.packagee?.name ?: ""
                !pkg.contains("cloud.googledrive") &&
                    !file.path.contains("/test/")
            }
            .assertTrue {
                it.imports.none { imp ->
                    imp.name.contains("googledrive", ignoreCase = true)
                }
            }
    }

    test("BinaryResult is used for fallible domain operations — no raw exceptions in domain interfaces") {
        mainScope
            .files
            .filter { file ->
                val pkg = file.packagee?.name ?: ""
                pkg.startsWith("ch.abwesend.foldervault.domain") &&
                    (file.name.startsWith("I") || file.name.endsWith("Repository") || file.name.endsWith("Service"))
            }
            .assertTrue {
                it.imports.none { imp -> imp.name == "kotlin.Exception" || imp.name == "java.lang.Exception" }
            }
    }

    // ── Naming & placement ────────────────────────────────────────────────────

    test("files with @Entity annotation reside in infrastructure.room and are named *Entity") {
        mainScope
            .files
            .filter { file -> file.imports.any { it.name == "androidx.room.Entity" } }
            .assertTrue { file ->
                file.path.contains("/infrastructure/room/") && file.name.endsWith("Entity.kt")
            }
    }

    test("files with @Dao annotation reside in infrastructure.room and are named *Dao") {
        mainScope
            .files
            .filter { file -> file.imports.any { it.name == "androidx.room.Dao" } }
            .assertTrue { file ->
                file.path.contains("/infrastructure/room/") && file.name.endsWith("Dao.kt")
            }
    }

    test("files named *ViewModel reside in the view layer") {
        mainScope
            .files
            .filter { file -> file.name.endsWith("ViewModel.kt") }
            .assertTrue { file ->
                file.path.contains("/view/")
            }
    }
})
