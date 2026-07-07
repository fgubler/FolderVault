package ch.abwesend.foldervault.architecture

import com.lemonappdev.konsist.api.Konsist
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * Guards GitHub issue #13: destructive Room migrations must never be enabled in production
 * code. A missing migration has to surface as an error the user can act on (database-error
 * screen with an explicit, confirmed reset) instead of Room silently wiping local data.
 *
 * Matches actual calls (`fallbackToDestructiveMigration…(`), so merely mentioning the API
 * name in a comment is not a violation.
 */
class DatabaseArchitectureTest : FunSpec({
    test("production code never enables destructive Room migrations") {
        val destructiveMigrationCall = Regex("""fallbackToDestructiveMigration\w*\s*\(""")
        Konsist.scopeFromProject()
            .files
            .filter { it.path.contains("/main/") }
            .filter { destructiveMigrationCall.containsMatchIn(it.text) }
            .map { it.path }
            .shouldBeEmpty()
    }
})
