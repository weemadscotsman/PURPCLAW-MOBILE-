package com.example.core.db.migrations.state

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * State migration 1 -> 2 — adds `lastClosedAt` column on `sessions` for
 * warm-resume continuationCursor lookup (spec §3.3 step 9).
 */
class M_1_2 : Migration(1, 2) {
  override fun migrate(database: SupportSQLiteDatabase) {
    database.execSQL(
      "ALTER TABLE `sessions` ADD COLUMN `lastClosedAt` INTEGER DEFAULT NULL"
    )
    database.execSQL(
      "CREATE INDEX IF NOT EXISTS `index_sessions_lastClosedAt` " +
        "ON `sessions` (`lastClosedAt`)"
    )
  }
}
