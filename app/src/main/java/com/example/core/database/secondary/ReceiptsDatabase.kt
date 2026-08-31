package com.example.core.database.secondary

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * RECEIPTS DATABASE — receipts.db
 *
 * Spec §2 Gate B: routing-receipt ledger.
 * Lane-owned: wire-independent.
 *
 * Versioning:
 *   v1 — initial schema, all required indexes.
 *   v2 — adds `verificationStatus` column for the receipt-strict
 *        VERIFIED/NOT_APPLICABLE/FAILED enum (truth board §1 PROVEN
 *        arbitration).
 */
@Database(
  entities = [ReceiptEntity::class],
  version = ReceiptsDatabase.VERSION,
  exportSchema = true
)
abstract class ReceiptsDatabase : RoomDatabase() {

  abstract fun receiptDao(): ReceiptDao

  companion object {
    const val NAME = "receipts.db"
    const val VERSION = 2

    /**
     * v1 -> v2 — adds `verificationStatus` enum column.
     * Default 'NOT_APPLICABLE' preserves existing rows.
     */
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
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

    @Volatile
    private var INSTANCE: ReceiptsDatabase? = null

    fun getDatabase(context: Context): ReceiptsDatabase {
      return INSTANCE ?: synchronized(this) {
        val instance = Room.databaseBuilder(
          context.applicationContext,
          ReceiptsDatabase::class.java,
          NAME
        )
          .addMigrations(MIGRATION_1_2)
          .build()
        INSTANCE = instance
        instance
      }
    }
  }
}
