package ch.abwesend.foldervault.infrastructure.room

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe

/**
 * Guards GitHub issue #13 together with [DatabaseArchitectureTest]: since destructive
 * migrations are forbidden, every schema-version bump MUST come with an explicit migration.
 * This test fails as soon as [FolderVaultDatabase.DB_VERSION] is raised without adding the
 * matching entry to [DatabaseMigrations.ALL].
 */
class DatabaseMigrationChainTest : FunSpec({
    test("registered migrations form an unbroken chain from version 1 to the current schema version") {
        val migrations = DatabaseMigrations.ALL.sortedBy { it.startVersion }

        migrations.shouldNotBeEmpty()
        migrations.first().startVersion shouldBe 1
        migrations.last().endVersion shouldBe FolderVaultDatabase.DB_VERSION
        migrations.zipWithNext().forEach { (previous, next) ->
            next.startVersion shouldBe previous.endVersion
        }
    }
})
