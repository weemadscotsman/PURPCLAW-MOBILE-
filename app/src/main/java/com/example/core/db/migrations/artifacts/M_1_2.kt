package com.example.core.db.migrations.artifacts

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Artifacts migration 1 -> 2 — adds index on `integrityStatus`.
 *
 * Proxy class lives at `core/db/migrations/artifacts/` per spec layout;
 * the same migration object is also referenced by `ArtifactsDatabase.MIGRATION_1_2`
 * so that runtime builds and migration tests use the identical execSQL
 * sequence. Single source of truth.
 */
class M_1_2 : Migration(1, 2) {
  override fun migrate(database: SupportSQLiteDatabase) {
    database.execSQL(
      "CREATE INDEX IF NOT EXISTS `index_media_artifacts_integrityStatus` " +
        "ON `media_artifacts` (`integrityStatus`)"
    )
  }
}
