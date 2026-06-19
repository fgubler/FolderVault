package ch.abwesend.foldervault.infrastructure.room

import androidx.room.migration.Migration

/**
 * Registry of Room schema migrations. Empty for now — v1 is a fresh-install database; the spec
 * allows dropping data on schema changes during the pre-release phase. Add entries here as the
 * schema evolves post-v1.
 */
object DatabaseMigrations {
    val ALL: Array<Migration> = emptyArray()
}
