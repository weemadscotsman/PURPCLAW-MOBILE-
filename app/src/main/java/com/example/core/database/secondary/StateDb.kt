package com.example.core.database.secondary

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * STATE.DB — sessions, turns, capability states.
 *
 * Spec §2: state.db (sessions, turns, capability states).
 * Truth board §11: per-session state container.
 *
 * Indexes (PK on id; FK on sessionId + capabilityKey per spec).
 * Schema version 1.
 */

@Entity(
  tableName = "sessions",
  indices = [Index(value = ["nodeId"]), Index(value = ["updatedAt"])]
)
data class SessionEntity(
  @PrimaryKey val id: String,
  val nodeId: String,
  val mode: String,            // CHAT | WORK
  val title: String?,
  val continuationCursor: String?,
  val createdAt: Long,
  val updatedAt: Long,
  val isClosed: Boolean = false
)

@Entity(
  tableName = "turns",
  foreignKeys = [
    ForeignKey(
      entity = SessionEntity::class,
      parentColumns = ["id"],
      childColumns = ["sessionId"],
      onDelete = ForeignKey.CASCADE
    )
  ],
  indices = [
    Index(value = ["sessionId"]),
    Index(value = ["createdAt"]),
    Index(value = ["nodeId"])
  ]
)
data class TurnEntity(
  @PrimaryKey val id: String,
  val sessionId: String,
  val nodeId: String,
  val role: String,        // USER | ASSISTANT | TOOL | SYSTEM
  val mode: String,        // CHAT | WORK
  val sequence: Int,
  val content: String,
  val createdAt: Long
)

@Entity(
  tableName = "capability_states",
  indices = [
    Index(value = ["capabilityKey"]),
    Index(value = ["sessionId"]),
    Index(value = ["updatedAt"])
  ]
)
data class CapabilityStateEntity(
  @PrimaryKey val id: String,
  val sessionId: String?,
  val capabilityKey: String,
  val health: String,    // WARM | COLD | DEGRADED | OFFLINE
  val lastInvocationMs: Long,
  val updatedAt: Long,
  val snapshotJson: String
)

@Dao
interface SessionDao {
  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(session: SessionEntity)

  @Query("SELECT * FROM sessions WHERE id = :id")
  suspend fun byId(id: String): SessionEntity?

  @Query("SELECT * FROM sessions WHERE isClosed = 0 ORDER BY updatedAt DESC")
  fun openSessions(): Flow<List<SessionEntity>>

  @Query("SELECT * FROM sessions WHERE nodeId = :nodeId ORDER BY updatedAt DESC")
  fun forNode(nodeId: String): Flow<List<SessionEntity>>

  @Query("SELECT COUNT(*) FROM sessions")
  suspend fun count(): Int
}

@Dao
interface TurnDao {
  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(turn: TurnEntity)

  @Query("SELECT * FROM turns WHERE sessionId = :sessionId ORDER BY sequence ASC")
  fun forSession(sessionId: String): Flow<List<TurnEntity>>

  @Query("SELECT * FROM turns ORDER BY createdAt DESC LIMIT :limit")
  suspend fun recent(limit: Int = 50): List<TurnEntity>

  @Query("SELECT COUNT(*) FROM turns WHERE sessionId = :sessionId")
  suspend fun countForSession(sessionId: String): Int
}

@Dao
interface CapabilityStateDao {
  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(state: CapabilityStateEntity)

  @Query("SELECT * FROM capability_states WHERE capabilityKey = :key LIMIT 1")
  suspend fun byKey(key: String): CapabilityStateEntity?

  @Query("SELECT * FROM capability_states ORDER BY updatedAt DESC")
  fun observe(): Flow<List<CapabilityStateEntity>>

  @Query("SELECT COUNT(*) FROM capability_states")
  suspend fun count(): Int
}
