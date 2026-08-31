package com.example.core.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * DISPATCH LEDGER — persisted DispatchReceipt (see com.example.core.model.DispatchReceipt).
 *
 * Distinct from ProofReceiptEntity: that store is per-tool-execution
 * cryptographic evidence. This store is per-routing-decision provenance.
 *
 * Step 12 builder spec (2026-08-27): every dispatch through ProviderRouter,
 * HomeRuntimeBridge.chatWithTools, or CouncilPodcastEngine.askSeat must write
 * one row. Acceptance: 5 dispatches through 5 different paths → this table
 * reconstructs them with the expected initiator / decisionSource mix.
 *
 * Schema: PK dispatchId. Indexed on (sourceTurnId, timestampMs) so the
 * verifier can pull per-turn reconstruction in one query.
 */
@Entity(
  tableName = "dispatch_ledger",
  indices = [
    Index(value = ["sourceTurnId", "timestampMs"]),
    Index(value = ["timestampMs"])
  ]
)
data class DispatchLedgerEntity(
  @PrimaryKey val dispatchId: String,
  val timestampMs: Long,
  val observedAt: Long,
  val initiator: String,         // DispatchInitiator.name
  val sourceTurnId: String?,
  val sourceAgentId: String?,
  val provider: String,
  val modelId: String,
  val routeMode: String,         // ModelMode.name
  val routerEnabled: Int,        // 0|1; SQLite has no Boolean
  val decisionSource: String,    // DecisionSource.name
  val reason: String,
  val leaseSnapshotId: String?,
  val capabilitySnapshotId: String?,
  val latencyMs: Long,
  val routingComputeMs: Long,
  val estimatedCostCents: Double?,
  val errorCode: String?,
  val schemaVersion: Int
)