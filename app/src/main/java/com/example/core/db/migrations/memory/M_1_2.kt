package com.example.core.db.migrations.memory

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Memory migration 1 -> 2 — adds `nodeId` column + index for cross-device
 * memory attribution.
 */
class M_1_2 : Migration(1, 2) {
  override fun migrate(database: SupportSQLiteDatabase) {
    database.execSQL(
      "ALTER TABLE `memory_atoms` ADD COLUMN `nodeId` TEXT NOT NULL DEFAULT 'phone-node'"
    )
    database.execSQL(
      "CREATE INDEX IF NOT EXISTS `index_memory_atoms_nodeId` " +
        "ON `memory_atoms` (`nodeId`)"
    )
  }
}
