package com.example.core.db.migrations.receipts

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Receipts migration 1 -> 2 — adds `verificationStatus` enum column.
 */
class M_1_2 : Migration(1, 2) {
  override fun migrate(database: SupportSQLiteDatabase) {
    database.execSQL(
      "ALTER TABLE `routing_receipts` ADD COLUMN `verificationStatus` " +
        "TEXT NOT NULL DEFAULT 'NOT_APPLICABLE'"
    )
    database.execSQL(
      "CREATE INDEX IF NOT EXISTS `index_routing_receipts_verificationStatus` " +
        "ON `routing_receipts` (`verificationStatus`)"
    )
  }
}
