package com.example.core.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.core.database.secondary.ReceiptsDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * RECEIPTS DB migration test (1 -> 2).
 *
 * v2 adds `verificationStatus` enum column (default 'NOT_APPLICABLE') + index.
 */
@RunWith(AndroidJUnit4::class)
class ReceiptsMigrationTest {

  private val dbName = ReceiptsDatabase.NAME

  @get:Rule
  val helper = MigrationTestHelper(
    InstrumentationRegistry.getInstrumentation(),
    ReceiptsDatabase::class.java.canonicalName,
    FrameworkSQLiteOpenHelperFactory()
  )

  @Test
  fun migrate_1_to_2_preservesReceiptsAndAddsColumn() {
    helper.createDatabase(dbName, 1).apply {
      execSQL(
        "INSERT INTO routing_receipts " +
          "(receiptId, sessionId, turnId, surface, toolId, args, " +
          " executionOk, verificationBasis, ts, nodeId) " +
          "VALUES " +
          "('r-seed-1', 'sess-1', 'turn-1', 'mobile', NULL, '{}', " +
          " 1, 'topActivity=foo', 1700000000000, 'phone-android-node-01')"
      )
      close()
    }

    helper.runMigrationsAndValidate(dbName, 2, true, ReceiptsDatabase.MIGRATION_1_2).apply {
      val cursor = query(
        "SELECT receiptId, verificationStatus FROM routing_receipts WHERE receiptId = 'r-seed-1'"
      )
      assertNotNull("seed receipt must survive migration", cursor)
      cursor.moveToFirst()
      assertEquals("r-seed-1", cursor.getString(cursor.getColumnIndexOrThrow("receiptId")))
      assertEquals(
        "NOT_APPLICABLE",
        cursor.getString(cursor.getColumnIndexOrThrow("verificationStatus"))
      )
      cursor.close()

      val idxCursor = query(
        "SELECT name FROM sqlite_master WHERE type='index' " +
          "AND name='index_routing_receipts_verificationStatus'"
      )
      assertTrue("verificationStatus index must exist after migration", idxCursor.moveToFirst())
      idxCursor.close()
      close()
    }
  }
}
