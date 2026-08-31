package com.example.core.database.secondary

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * ARTIFACTS DATABASE — artifacts.db
 *
 * Spec §2 Gate B: MediaArtifact metadata index.
 * Lane-owned: wire-independent. Tool-wire lane consumes this later.
 *
 * Versioning:
 *   v1 — initial schema, all required indexes per §2.3.
 *   v2 — adds receiptsDb-style integrity status index + surface column
 *        for mobile-side rendering of INTEGRITY_FAILED tiles (spec §3.3 step 11).
 *
 * Schema export is ON (spec mandates MigrationTestHelper support).
 */
@Database(
  entities = [ArtifactsEntity::class],
  version = ArtifactsDatabase.VERSION,
  exportSchema = true
)
abstract class ArtifactsDatabase : RoomDatabase() {

  abstract fun artifactsDao(): ArtifactsDao

  companion object {
    const val NAME = "artifacts.db"
    const val VERSION = 2

    /**
     * v1 -> v2 — adds an index on `integrityStatus` so the gallery
     * can pull all INTEGRITY_FAILED rows in one query (spec §3.3 step 11).
     * No column adds, no data migration — additive index only.
     */
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
      override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
          "CREATE INDEX IF NOT EXISTS `index_media_artifacts_integrityStatus` " +
            "ON `media_artifacts` (`integrityStatus`)"
        )
      }
    }

    @Volatile
    private var INSTANCE: ArtifactsDatabase? = null

    fun getDatabase(context: Context): ArtifactsDatabase {
      return INSTANCE ?: synchronized(this) {
        val instance = Room.databaseBuilder(
          context.applicationContext,
          ArtifactsDatabase::class.java,
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
