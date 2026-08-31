package com.example.core.runtime

import com.example.core.model.DecisionSource
import com.example.core.model.DispatchInitiator
import com.example.core.model.ModelMode

/**
 * ROUTING DECISION CONTEXT — carrier that flows alongside the call from
 * selector → executor → recorder.
 *
 * Reuses fields that already exist on RoutingState where they overlap; does
 * NOT duplicate them. The carrier is the *minimum* extra shape the recorder
 * needs that RoutingState doesn't already convey (initiator, source identity,
 * routerEnabled flag at the moment of dispatch, decision source classification).
 *
 * Why a separate carrier:
 * - Selector logic must be testable without dragging in the full database.
 * - Recorder must be wired into three call sites (ProviderRouter,
 *   HomeRuntimeBridge, CouncilPodcastEngine.askSeat) without each one
 *   re-deriving the same context from RoutingState.
 * - Snapshot ids (lease, capability) are FK targets; they travel with the
 *   decision, not the RoutingState.
 */
data class RoutingDecisionContext(
  val initiator: DispatchInitiator,
  val sourceTurnId: String?,
  val sourceAgentId: String?,
  val routeMode: ModelMode,
  val routerEnabled: Boolean,
  val decisionSource: DecisionSource,
  val reason: String,
  val leaseSnapshotId: String?,
  val capabilitySnapshotId: String?,
  val routingComputeMs: Long,
  val estimatedCostCents: Double?
) {
  companion object {
    /** No-op context — used by code paths that haven't been migrated yet. */
    val UNKNOWN: RoutingDecisionContext = RoutingDecisionContext(
      initiator = DispatchInitiator.UNKNOWN,
      sourceTurnId = null,
      sourceAgentId = null,
      routeMode = ModelMode.AUTO,
      routerEnabled = false,
      decisionSource = DecisionSource.UNKNOWN,
      reason = "untracked",
      leaseSnapshotId = null,
      capabilitySnapshotId = null,
      routingComputeMs = 0L,
      estimatedCostCents = null
    )
  }
}