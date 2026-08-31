package com.example.core.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.core.database.secondary.MemoryDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * MEMORY DB migration test (1 -> 2).
 *
 * v2 adds `nodeId` column (default 'phone-node') + index.
 */
@RunWith(AndroidJUnit4::class)
class MemoryMigrationTest {

  private val dbName = MemoryDatabase.NAME

  @get:Rule
  val helper = MigrationTestHelper(
    InstrumentationRegistry.getInstrumentation(),
    MemoryDatabase::class.java.canonicalName,
    FrameworkSQLiteOpenHelperFactory()
  )

  @Test
  fun migrate_1_to_2_preservesAtomsAndBackfillsNodeId() {
    helper.createDatabase(dbName, 1).apply {
      execSQL(
        "INSERT INTO memory_atoms " +
          "(id, layer, scope, source, kind, importance, content, createdAt, durable, supersededBy) " +
          "VALUES " +
          "('mem-seed-1', 'EPISODIC', 'USER', 'CHAT', 'FACT', 0.8, " +
          " 'first memory', 1700000000000, 1, NULL)"
      )
      close()
    }

    helper.runMigrationsAndValidate(dbName, 2, true, MemoryDatabase.MIGRATION_1_2).apply {
      val cursor = query("SELECT id, nodeId FROM memory_atoms WHERE id = 'mem-seed-1'")
      assertNotNull("seed atom must survive migration", cursor)
      cursor.moveToFirst()
      assertEquals("mem-seed-1", cursor.getString(cursor.getColumnIndexOrThrow("id")))
      assertEquals(
        "phone-node",
        cursor.getString(cursor.getColumnIndexOrThrow("nodeId"))
      )
      cursor.close()

      val idxCursor = query(
        "SELECT name FROM sqlite_master WHERE type='index' AND name='index_memory_atoms_nodeId'"
      )
      assertTrue("nodeId index must exist after migration", idxCursor.moveToFirst())
      idxCursor.close()
      close()
    }
  }
}
