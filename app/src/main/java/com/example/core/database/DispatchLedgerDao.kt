package com.example.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * DISPATCH LEDGER DAO — durable routing-decision provenance.
 *
 * Read patterns (from the Step 12 spec):
 * - byId: verifier + system.router.last_decision lookup
 * - forTurn: per-turn reconstruction (5-dispatch acceptance test)
 * - recent: system.router.trace fallback when no turn filter
 * - observe: live feed for cockpit / diagnostic UI
 * - countByTurn: acceptance assertion (e.g. exactly 5 per turn)
 * - all: parity-test harness — assert full ledger contents
 *
 * No `delete` exposed: this ledger is append-only provenance.
 */
@Dao
interface DispatchLedgerDao {

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(receipt: DispatchLedgerEntity): Long

  @Query("SELECT * FROM dispatch_ledger WHERE dispatchId = :dispatchId")
  suspend fun byId(dispatchId: String): DispatchLedgerEntity?

  @Query("""
    SELECT * FROM dispatch_ledger
    WHERE sourceTurnId = :turnId
    ORDER BY timestampMs ASC
    LIMIT :limit
  """)
  suspend fun forTurn(turnId: String, limit: Int = 25): List<DispatchLedgerEntity>

  @Query("""
    SELECT * FROM dispatch_ledger
    ORDER BY timestampMs DESC
    LIMIT :limit
  """)
  suspend fun recent(limit: Int = 50): List<DispatchLedgerEntity>

  @Query("SELECT * FROM dispatch_ledger ORDER BY timestampMs DESC")
  fun observe(): Flow<List<DispatchLedgerEntity>>

  @Query("SELECT COUNT(*) FROM dispatch_ledger WHERE sourceTurnId = :turnId")
  suspend fun countByTurn(turnId: String): Int

  /** Full ledger snapshot for acceptance / parity tests. */
  @Query("SELECT * FROM dispatch_ledger ORDER BY timestampMs ASC")
  suspend fun all(): List<DispatchLedgerEntity>

  /** Total rows — used by system.router.inspect.dispatchCount. */
  @Query("SELECT COUNT(*) FROM dispatch_ledger")
  suspend fun count(): Int
}