package com.example.core.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.core.database.secondary.ArtifactsDatabase
import com.example.core.database.secondary.ArtifactsEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ARTIFACTS DB migration test (1 -> 2).
 *
 * Step 1: open v1 schema, seed one row, close.
 * Step 2: run MIGRATION_1_2, validate v2 schema with the seeded row preserved
 *         and the new integrityStatus index queryable.
 *
 * Lane note: this is an instrumentation test — requires a real device or
 * emulator. The lane compiles + writes the test; running it on operator's
 * box is BLOCKED-ON-DEVICE.
 */
@RunWith(AndroidJUnit4::class)
class ArtifactsMigrationTest {

  private val dbName = ArtifactsDatabase.NAME

  @get:Rule
  val helper = MigrationTestHelper(
    InstrumentationRegistry.getInstrumentation(),
    ArtifactsDatabase::class.java.canonicalName,
    FrameworkSQLiteOpenHelperFactory()
  )

  @Test
  fun migrate_1_to_2_preservesRowsAndAddsIndex() {
    helper.createDatabase(dbName, 1).apply {
      execSQL(
        "INSERT INTO media_artifacts " +
          "(id, parentArtifactId, chatId, createdAt, sha256Prefix, type, " +
          " visibility, mime, bytes, metadataJson, integrityStatus) " +
          "VALUES " +
          "('art-seed-1', NULL, 'chat-42', 1700000000000, 'abc123', " +
          " 'IMAGE', 'BOTH', 'image/png', 1024, '{}', 'OK')"
      )
      close()
    }

    helper.runMigrationsAndValidate(dbName, 2, true, ArtifactsDatabase.MIGRATION_1_2).apply {
      val cursor = query("SELECT * FROM media_artifacts WHERE id = 'art-seed-1'")
      assertNotNull("seed row must survive migration", cursor)
      cursor.moveToFirst()
      assertEquals("abc123", cursor.getString(cursor.getColumnIndexOrThrow("sha256Prefix")))
      assertEquals("BOTH", cursor.getString(cursor.getColumnIndexOrThrow("visibility")))
      cursor.close()

      val idxCursor = query(
        "SELECT name FROM sqlite_master WHERE type='index' AND name='index_media_artifacts_integrityStatus'"
      )
      assertTrue("integrityStatus index must exist after migration", idxCursor.moveToFirst())
      idxCursor.close()
      close()
    }
  }
}
