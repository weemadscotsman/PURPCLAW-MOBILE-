package com.example.core.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.core.database.secondary.StateDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * STATE DB migration test (1 -> 2).
 *
 * v2 adds `lastClosedAt` to sessions and an index on it (warm-resume path).
 */
@RunWith(AndroidJUnit4::class)
class StateMigrationTest {

  private val dbName = StateDatabase.NAME

  @get:Rule
  val helper = MigrationTestHelper(
    InstrumentationRegistry.getInstrumentation(),
    StateDatabase::class.java.canonicalName,
    FrameworkSQLiteOpenHelperFactory()
  )

  @Test
  fun migrate_1_to_2_preservesSessionsAndAddsColumn() {
    helper.createDatabase(dbName, 1).apply {
      execSQL(
        "INSERT INTO sessions " +
          "(id, nodeId, mode, title, continuationCursor, createdAt, updatedAt, isClosed) " +
          "VALUES " +
          "('sess-seed-1', 'phone-node', 'CHAT', 'hello', NULL, " +
          " 1700000000000, 1700000000001, 0)"
      )
      close()
    }

    helper.runMigrationsAndValidate(dbName, 2, true, StateDatabase.MIGRATION_1_2).apply {
      val cursor = query("SELECT id, lastClosedAt FROM sessions WHERE id = 'sess-seed-1'")
      assertNotNull("seed session must survive migration", cursor)
      cursor.moveToFirst()
      assertEquals("sess-seed-1", cursor.getString(cursor.getColumnIndexOrThrow("id")))
      // lastClosedAt defaulted to NULL — column was added by migration
      val lastClosedIdx = cursor.getColumnIndexOrThrow("lastClosedAt")
      assertTrue("lastClosedAt column must exist", lastClosedIdx >= 0)
      cursor.close()

      val idxCursor = query(
        "SELECT name FROM sqlite_master WHERE type='index' AND name='index_sessions_lastClosedAt'"
      )
      assertTrue("lastClosedAt index must exist after migration", idxCursor.moveToFirst())
      idxCursor.close()
      close()
    }
  }
}
