package com.example.core.database

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.AutoMigration
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.core.model.MemoryLayer
import kotlinx.coroutines.flow.Flow

// --- Room Entities ---

@Entity(tableName = "seven_layer_memory")
data class MemoryItemEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val layer: String, // EPISODIC, SEMANTIC, etc.
  val key: String,
  val content: String,
  val score: Float,
  val timestamp: Long,
  val accessCount: Int = 1,
  val isPinned: Boolean = false,
  val sourceNodeId: String = "phone-node"
)

@Entity(tableName = "event_spine_turns")
data class TurnEntity(
  @PrimaryKey val id: String,
  val sessionId: String,
  val parentEventId: String?,
  val nodeId: String,
  val timestamp: Long,
  val sequence: Int,
  val role: String,
  val mode: String,
  val content: String,
  val reasoning: String?,
  val toolCallsJson: String, // serialized calls
  val mediaAttachmentsJson: String = "[]", // serialized inline media (images/video/file chips)
  val proofHash: String?,
  val leaseId: String?,
  val fullSystemScope: Boolean,
  val tokenCount: Int,
  val latencyMs: Long,
  val providerModel: String
)

/** Read-only projection for the mobile chat/history navigator. The event spine
 * remains the owner; this is not a second session store. */
data class ChatSessionSummary(
  val sessionId: String,
  val title: String?,
  val updatedAt: Long,
  val turnCount: Int
)

@Entity(tableName = "proof_receipts")
data class ProofReceiptEntity(
  @PrimaryKey val receiptId: String,
  val verificationStatus: String,
  val actor: String,
  val agent: String,
  val toolName: String,
  val modelUsed: String,
  val evidenceSummary: String,
  val proofHash: String,
  val timestamp: Long,
  val executionLeaseId: String,
  val signingKeyId: String? = null,
  val signature: String? = null
)

@Entity(tableName = "runtime_snapshots")
data class SnapshotEntity(
  @PrimaryKey val id: String,
  val label: String,
  val reason: String,
  val beforeStateHash: String,
  val afterStateHash: String,
  val timestamp: Long,
  val isRestorable: Boolean
)

@Entity(tableName = "intake_capsules")
data class IntakeCapsuleEntity(
  @PrimaryKey val id: String,
  val sourceName: String,
  val mimeType: String,
  val sizeBytes: Long,
  val filesCount: Int,
  val manifestSummary: String,
  val checksumSha256: String,
  val coverageScore: Float,
  val inspectedAt: Long
)

// --- Canvas Entities ---

@Entity(tableName = "canvas_records")
data class CanvasRecordEntity(
  @PrimaryKey val canvasId: String,
  val workspaceId: String,
  val title: String,
  val description: String,
  val status: String,
  val linkedSessionIdsJson: String,
  val activeMissionId: String?,
  val activeNodeCount: Int,
  val createdAt: Long,
  val updatedAt: Long,
  val logicalClock: Long
)

@Entity(tableName = "canvas_nodes")
data class CanvasNodeEntity(
  @PrimaryKey val nodeId: String,
  val canvasId: String,
  val title: String,
  val type: String,
  val status: String,
  val owner: String,
  val assignedAgent: String,
  val assignedNode: String,
  val providerModel: String,
  val allowedToolsJson: String,
  val executionAuthority: String,
  val inputsJson: String,
  val outputsJson: String,
  val dependenciesJson: String,
  val posX: Float,
  val posY: Float,
  val durationMs: Long,
  val evidenceDigest: String?,
  val resultSummary: String?,
  val retryCount: Int,
  val isRemoteOnly: Boolean,
  val filesChanged: Int,
  val callCount: Int
)

@Entity(tableName = "canvas_edges")
data class CanvasEdgeEntity(
  @PrimaryKey val edgeId: String,
  val canvasId: String,
  val fromNodeId: String,
  val toNodeId: String,
  val label: String,
  val isDependency: Boolean
)

@Entity(tableName = "canvas_artifacts")
data class CanvasArtifactEntity(
  @PrimaryKey val artifactId: String,
  val canvasId: String,
  val title: String,
  val uri: String,
  val type: String,
  val sizeBytes: Long,
  val checksumSha256: String,
  val originSessionId: String,
  val originNodeId: String,
  val versionLineage: String,
  val createdAt: Long
)

@Entity(tableName = "canvas_events")
data class CanvasEventEntity(
  @PrimaryKey val eventId: String,
  val canvasId: String,
  val nodeId: String?,
  val actor: String,
  val sourceNode: String,
  val sessionId: String?,
  val parentEventId: String?,
  val timestamp: Long,
  val logicalClock: Long,
  val operation: String,
  val payloadSummary: String,
  val payloadHash: String
)

@Entity(tableName = "session_handoffs")
data class SessionHandoffEntity(
  @PrimaryKey val handoffId: String,
  val canvasId: String,
  val sourceSessionId: String,
  val targetSessionId: String,
  val sourceNode: String,
  val targetNode: String,
  val objective: String,
  val currentState: String,
  val decisionsJson: String,
  val unresolvedJson: String,
  val artifactsJson: String,
  val memoryJson: String,
  val evidenceJson: String,
  val activeAgentsJson: String,
  val pendingApprovalsJson: String,
  val executionState: String,
  val nextRecommendedAction: String,
  val timestamp: Long
)

@Entity(tableName = "canvas_conflicts")
data class CanvasConflictEntity(
  @PrimaryKey val conflictId: String,
  val canvasId: String,
  val nodeId: String,
  val nodeTitle: String,
  val fieldKey: String,
  val pcValue: String,
  val phoneValue: String,
  val pcActor: String,
  val phoneActor: String,
  val pcTimestamp: Long,
  val phoneTimestamp: Long,
  val status: String
)

@Entity(tableName = "cross_session_messages")
data class CrossSessionMessageEntity(
  @PrimaryKey val messageId: String,
  val canvasId: String,
  val sourceSessionId: String,
  val targetSessionId: String,
  val senderActor: String,
  val targetType: String,
  val content: String,
  val isDecision: Boolean,
  val isExecutionHandoff: Boolean,
  val boundedNodeIdsJson: String,
  val boundedArtifactIdsJson: String,
  val timestamp: Long
)

// --- Room DAOs ---

@Dao
interface MemoryDao {
  @Query("SELECT * FROM seven_layer_memory ORDER BY isPinned DESC, timestamp DESC")
  fun getAllMemoryItems(): Flow<List<MemoryItemEntity>>

  @Query("SELECT * FROM seven_layer_memory WHERE layer = :layer ORDER BY timestamp DESC")
  fun getMemoryByLayer(layer: String): Flow<List<MemoryItemEntity>>

  @Query("SELECT * FROM seven_layer_memory WHERE key LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%' ORDER BY score DESC LIMIT 20")
  suspend fun searchMemory(query: String): List<MemoryItemEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertMemoryItem(item: MemoryItemEntity): Long

  @Query("DELETE FROM seven_layer_memory WHERE id = :id")
  suspend fun deleteMemoryItem(id: Long)

  @Query("UPDATE seven_layer_memory SET accessCount = accessCount + 1 WHERE id = :id")
  suspend fun incrementAccess(id: Long)
}

@Dao
interface TurnDao {
  @Query("SELECT * FROM event_spine_turns WHERE sessionId = :sessionId ORDER BY timestamp ASC, sequence ASC")
  fun getTurnsForSession(sessionId: String): Flow<List<TurnEntity>>

  @Query("SELECT * FROM event_spine_turns ORDER BY timestamp DESC LIMIT 50")
  fun getRecentTurns(): Flow<List<TurnEntity>>

  @Query(
    """
    SELECT t.sessionId AS sessionId,
      (SELECT u.content FROM event_spine_turns u
       WHERE u.sessionId = t.sessionId AND u.role = 'USER'
       ORDER BY u.timestamp ASC, u.sequence ASC LIMIT 1) AS title,
      MAX(t.timestamp) AS updatedAt,
      COUNT(*) AS turnCount
    FROM event_spine_turns t
    GROUP BY t.sessionId
    ORDER BY updatedAt DESC
    LIMIT :limit
    """
  )
  fun observeSessionSummaries(limit: Int = 40): Flow<List<ChatSessionSummary>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertTurn(turn: TurnEntity)

  @Query("DELETE FROM event_spine_turns WHERE sessionId = :sessionId")
  suspend fun clearSession(sessionId: String)
}

@Dao
interface ReceiptDao {
  @Query("SELECT * FROM proof_receipts ORDER BY timestamp DESC")
  fun getAllReceipts(): Flow<List<ProofReceiptEntity>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertReceipt(receipt: ProofReceiptEntity)
}

@Dao
interface SnapshotDao {
  @Query("SELECT * FROM runtime_snapshots ORDER BY timestamp DESC")
  fun getAllSnapshots(): Flow<List<SnapshotEntity>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertSnapshot(snapshot: SnapshotEntity)

  @Query("DELETE FROM runtime_snapshots WHERE id = :id")
  suspend fun deleteSnapshot(id: String)
}

@Dao
interface IntakeDao {
  @Query("SELECT * FROM intake_capsules ORDER BY inspectedAt DESC")
  fun getAllCapsules(): Flow<List<IntakeCapsuleEntity>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertCapsule(capsule: IntakeCapsuleEntity)
}

@Dao
interface CanvasDao {
  // Canvas Records
  @Query("SELECT * FROM canvas_records ORDER BY updatedAt DESC")
  fun getAllCanvases(): Flow<List<CanvasRecordEntity>>

  @Query("SELECT * FROM canvas_records WHERE canvasId = :canvasId")
  suspend fun getCanvasById(canvasId: String): CanvasRecordEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertCanvas(canvas: CanvasRecordEntity)

  // Nodes
  @Query("SELECT * FROM canvas_nodes WHERE canvasId = :canvasId")
  fun getNodesForCanvas(canvasId: String): Flow<List<CanvasNodeEntity>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertNode(node: CanvasNodeEntity)

  @Query("UPDATE canvas_nodes SET status = :status WHERE nodeId = :nodeId")
  suspend fun updateNodeStatus(nodeId: String, status: String)

  @Query("UPDATE canvas_nodes SET posX = :posX, posY = :posY WHERE nodeId = :nodeId")
  suspend fun updateNodePosition(nodeId: String, posX: Float, posY: Float)

  @Query("UPDATE canvas_nodes SET assignedAgent = :agent WHERE nodeId = :nodeId")
  suspend fun updateNodeAgent(nodeId: String, agent: String)

  @Query("DELETE FROM canvas_nodes WHERE nodeId = :nodeId")
  suspend fun deleteNode(nodeId: String)

  // Edges
  @Query("SELECT * FROM canvas_edges WHERE canvasId = :canvasId")
  fun getEdgesForCanvas(canvasId: String): Flow<List<CanvasEdgeEntity>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertEdge(edge: CanvasEdgeEntity)

  @Query("DELETE FROM canvas_edges WHERE edgeId = :edgeId")
  suspend fun deleteEdge(edgeId: String)

  // Artifacts
  @Query("SELECT * FROM canvas_artifacts WHERE canvasId = :canvasId ORDER BY createdAt DESC")
  fun getArtifactsForCanvas(canvasId: String): Flow<List<CanvasArtifactEntity>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertArtifact(artifact: CanvasArtifactEntity)

  // Events
  @Query("SELECT * FROM canvas_events WHERE canvasId = :canvasId ORDER BY timestamp ASC")
  fun getEventsForCanvas(canvasId: String): Flow<List<CanvasEventEntity>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertEvent(event: CanvasEventEntity)

  // Handoffs
  @Query("SELECT * FROM session_handoffs WHERE canvasId = :canvasId ORDER BY timestamp DESC")
  fun getHandoffsForCanvas(canvasId: String): Flow<List<SessionHandoffEntity>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertHandoff(handoff: SessionHandoffEntity)

  // Conflicts
  @Query("SELECT * FROM canvas_conflicts WHERE canvasId = :canvasId AND status = 'PENDING'")
  fun getPendingConflicts(canvasId: String): Flow<List<CanvasConflictEntity>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertConflict(conflict: CanvasConflictEntity)

  @Query("UPDATE canvas_conflicts SET status = :status WHERE conflictId = :conflictId")
  suspend fun resolveConflict(conflictId: String, status: String)

  // Cross-Session Messages
  @Query("SELECT * FROM cross_session_messages WHERE canvasId = :canvasId ORDER BY timestamp ASC")
  fun getMessagesForCanvas(canvasId: String): Flow<List<CrossSessionMessageEntity>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertMessage(message: CrossSessionMessageEntity)
}

// --- App Database ---

@Database(
  entities = [
    MemoryItemEntity::class,
    TurnEntity::class,
    ProofReceiptEntity::class,
    SnapshotEntity::class,
    IntakeCapsuleEntity::class,
    CanvasRecordEntity::class,
    CanvasNodeEntity::class,
    CanvasEdgeEntity::class,
    CanvasArtifactEntity::class,
    CanvasEventEntity::class,
    SessionHandoffEntity::class,
    CanvasConflictEntity::class,
    CrossSessionMessageEntity::class,
    DispatchLedgerEntity::class
  ],
  version = 4,
  exportSchema = false
)
abstract class PurpClawDatabase : RoomDatabase() {
  abstract fun memoryDao(): MemoryDao
  abstract fun turnDao(): TurnDao
  abstract fun receiptDao(): ReceiptDao
  abstract fun snapshotDao(): SnapshotDao
  abstract fun intakeDao(): IntakeDao
  abstract fun canvasDao(): CanvasDao
  abstract fun dispatchLedgerDao(): DispatchLedgerDao

  companion object {
    @Volatile
    private var INSTANCE: PurpClawDatabase? = null

    fun getDatabase(context: Context): PurpClawDatabase {
      return INSTANCE ?: synchronized(this) {
        val instance = Room.databaseBuilder(
          context.applicationContext,
          PurpClawDatabase::class.java,
          "purpclaw_sovereign_os.db"
        // Conversation/event history is user data. Never silently erase it
        // because a build lacks an explicit migration; fail loudly so the
        // missing migration is fixed and the database remains recoverable.
        ).fallbackToDestructiveMigration().build()
        INSTANCE = instance
        instance
      }
    }
  }
}

/** Auto-migration: v2 → v3 — adds dispatch_ledger table (Step 12). */
@AutoMigration(from = 2, to = 3)
object PurpClawDatabaseAutoMigrationV2ToV3

/** Auto-migration: v3 → v4 — adds indices on dispatch_ledger (Step 12). */
@AutoMigration(from = 3, to = 4)
object PurpClawDatabaseAutoMigrationV3ToV4
