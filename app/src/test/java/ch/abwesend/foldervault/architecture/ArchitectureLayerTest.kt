package ch.abwesend.folderVault.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue
import io.kotest.core.spec.style.FunSpec

/**
 * Konsist-based enforcement of the layering rules described in `CLAUDE.md`:
 *   * `domain` depends on nothing (modulo a small allow-list of platform boundary types)
 *   * `view` and `infrastructure` may depend on `domain` but not on each other
 *   * Firebase Crashlytics access is confined to `infrastructure.logging`
 *   * Cloud-provider specifics stay inside `infrastructure.cloud.googledrive`
 *
 * Note on `file.name`: in Konsist 0.17 this is the file name **without** the `.kt` extension —
 * `endsWith("Foo")`, not `endsWith("Foo.kt")`.
 */
class ArchitectureLayerTest : FunSpec({

    val mainScope = Konsist.scopeFromProject()
        .slice { it.path.contains("/main/") && !it.path.contains("/test/") }

    /**
     * Android types that domain interfaces are allowed to mention because they're the natural
     * boundary at the cloud-authorization seam — both `view` and `infrastructure` need to see
     * them, so wrapping them would add complexity without architectural benefit.
     */
    val allowedAndroidBoundaryImports = setOf(
        "android.app.PendingIntent",
        "android.content.Intent",
    )

    test("domain layer has no android or infrastructure imports") {
        mainScope
            .files
            .filter { it.packagee?.name?.startsWith("ch.abwesend.foldervault.domain") == true }
            .assertTrue {
                it.imports.none { imp ->
                    val name = imp.name
                    if (name in allowedAndroidBoundaryImports) {
                        false
                    } else {
                        name.startsWith("android.") ||
                            // androidx.annotation.* is allowed: annotation-only, no Android runtime dep
                            (name.startsWith("androidx.") && !name.startsWith("androidx.annotation.")) ||
                            name.startsWith("ch.abwesend.foldervault.infrastructure")
                    }
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

    test("only infrastructure.logging imports FirebaseCrashlytics") {
        mainScope
            .files
            .filter { file ->
                file.packagee?.name?.startsWith("ch.abwesend.foldervault.infrastructure.logging") != true
            }
            .assertTrue {
                it.imports.none { imp ->
                    imp.name.startsWith("com.google.firebase.crashlytics")
                }
            }
    }

    test("cloud provider specifics stay inside infrastructure.cloud.googledrive") {
        mainScope
            .files
            .filter { file ->
                val pkg = file.packagee?.name ?: ""
                !pkg.startsWith("ch.abwesend.foldervault.infrastructure.cloud.googledrive") &&
                    // DI module legitimately needs to wire up the concrete cloud implementation
                    !pkg.startsWith("ch.abwesend.foldervault.di")
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
                file.path.contains("/infrastructure/room/") && file.name.endsWith("Entity")
            }
    }

    test("files with @Dao annotation reside in infrastructure.room and are named *Dao") {
        mainScope
            .files
            .filter { file -> file.imports.any { it.name == "androidx.room.Dao" } }
            .assertTrue { file ->
                file.path.contains("/infrastructure/room/") && file.name.endsWith("Dao")
            }
    }

    test("files named *ViewModel reside in the view layer") {
        mainScope
            .files
            .filter { file -> file.name.endsWith("ViewModel") }
            .assertTrue { file ->
                file.path.contains("/view/")
            }
    }
})
