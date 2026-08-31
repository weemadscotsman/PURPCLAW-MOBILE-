package com.example.core.database.secondary

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * STATE DATABASE — state.db
 *
 * Spec §2 Gate B: sessions, turns, capability states.
 * Lane-owned: wire-independent.
 *
 * Versioning:
 *   v1 — initial schema, three tables (sessions/turns/capability_states).
 *   v2 — adds continuationCursor index + extended capability health
 *        via ALTER TABLE (additive, non-destructive).
 */
@Database(
  entities = [
    SessionEntity::class,
    TurnEntity::class,
    CapabilityStateEntity::class
  ],
  version = StateDatabase.VERSION,
  exportSchema = true
)
abstract class StateDatabase : RoomDatabase() {

  abstract fun sessionDao(): SessionDao
  abstract fun turnDao(): TurnDao
  abstract fun capabilityStateDao(): CapabilityStateDao

  companion object {
    const val NAME = "state.db"
    const val VERSION = 2

    /**
     * v1 -> v2 — adds `lastClosedAt` column on sessions so the
     * warm-resume path can find the latest closed session quickly
     * (spec §3.3 step 9 — continuationCursor restore).
     */
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
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

    @Volatile
    private var INSTANCE: StateDatabase? = null

    fun getDatabase(context: Context): StateDatabase {
      return INSTANCE ?: synchronized(this) {
        val instance = Room.databaseBuilder(
          context.applicationContext,
          StateDatabase::class.java,
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
