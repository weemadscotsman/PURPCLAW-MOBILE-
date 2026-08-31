package com.example.core.database.secondary

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * RECEIPTS.DB — routing-receipt ledger.
 *
 * Spec §2 (receipts.db — routing-receipt ledger).
 * Schema: receiptId, sessionId, turnId, surface, toolId, args,
 *         execution.ok, verification.basis, ts, nodeId
 *
 * This is the mobile mirror of the desktop routing-receipt ledger;
 * each turn POSTs one row to the desktop side once the wire lands.
 *
 * Indexes: PK on receiptId; secondary on (sessionId), (turnId),
 * (surface), (ts), (nodeId).
 */

@Entity(
  tableName = "routing_receipts",
  indices = [
    Index(value = ["sessionId"]),
    Index(value = ["turnId"]),
    Index(value = ["surface"]),
    Index(value = ["ts"]),
    Index(value = ["nodeId"])
  ]
)
data class ReceiptEntity(
  @PrimaryKey val receiptId: String,
  val sessionId: String,
  val turnId: String,
  val surface: String,         // mobile | cli | web | tui
  val toolId: String?,
  val args: String?,           // serialized args JSON
  val executionOk: Boolean,
  val verificationBasis: String,
  val ts: Long,
  val nodeId: String
)

@Dao
interface ReceiptDao {
  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(receipt: ReceiptEntity)

  @Query("SELECT * FROM routing_receipts WHERE receiptId = :id")
  suspend fun byId(id: String): ReceiptEntity?

  @Query("SELECT * FROM routing_receipts WHERE sessionId = :sessionId ORDER BY ts ASC")
  fun forSession(sessionId: String): Flow<List<ReceiptEntity>>

  @Query("SELECT * FROM routing_receipts WHERE turnId = :turnId ORDER BY ts ASC")
  suspend fun forTurn(turnId: String): List<ReceiptEntity>

  @Query("SELECT * FROM routing_receipts WHERE nodeId = :nodeId ORDER BY ts DESC LIMIT :limit")
  suspend fun recentForNode(nodeId: String, limit: Int = 100): List<ReceiptEntity>

  @Query("SELECT COUNT(*) FROM routing_receipts")
  suspend fun count(): Int
}
