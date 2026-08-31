package com.example.core.model

import com.example.core.database.DispatchLedgerEntity

/**
 * DISPATCH RECEIPT — durable, attributable record of ONE routing decision.
 *
 * Why a separate ledger from ProofReceipt:
 * - ProofReceipt is cryptographic (signature, evidence hash) and per-tool-
 *   execution. It says "this tool ran and produced this result."
 * - DispatchReceipt is routing-attribution (who decided this model answered,
 *   when, why, on whose behalf). It says "this dispatch went through this
 *   path; here is the decision surface at the moment of dispatch."
 * - Distinct concerns. Distinct stores. Distinct consumers.
 *
 * Step 12 builder spec (2026-08-27): every dispatch through ProviderRouter,
 * HomeRuntimeBridge.chatWithTools, or CouncilPodcastEngine.askSeat must mint
 * one of these. Acceptance: 5 dispatches through 5 different paths →
 * reconstructable from this ledger alone.
 *
 * LAW: in-memory RoutingReceipt (RoutingModels.kt) remains transient;
 * DispatchReceipt is the authoritative persisted record.
 */
data class DispatchReceipt(
  val dispatchId: String,                 // "dispatch_<8hex>", PK
  val timestampMs: Long,                  // wall clock at decision
  val observedAt: Long,                   // wall clock when first persisted
  val initiator: DispatchInitiator,       // who triggered the call
  val sourceTurnId: String?,              // TurnDao row id (null for orphan)
  val sourceAgentId: String?,            // Soul id (Babshaggoth, …); null when user is initiator
  val provider: String,                   // resolved upstream ("openrouter" | "nim" | …)
  val modelId: String,                    // exact model id
  val routeMode: ModelMode,               // AUTO | MANUAL
  val routerEnabled: Boolean,             // false when model_router == "disabled"
  val decisionSource: DecisionSource,     // where the decision came from
  val reason: String,                     // human-readable routing reason
  val leaseSnapshotId: String?,           // FK → ExecutionLease.id
  val capabilitySnapshotId: String?,      // FK → CapabilitySnapshot.snapshotId
  val latencyMs: Long,                    // provider round-trip
  val routingComputeMs: Long,             // selector time (0 for LOCAL_PIN)
  val estimatedCostCents: Double?,        // computeCostCents(...) result; null if not computed
  val errorCode: String? = null,          // non-null when dispatch failed
  val schemaVersion: Int = SCHEMA_VERSION
) {
  companion object {
    const val SCHEMA_VERSION = 1

    private val HEX = "0123456789abcdef"

    /** Mint a synthetic dispatchId in the canonical form "dispatch_<8hex>". */
    fun mintDispatchId(): String =
      "dispatch_" + (1..8).map { HEX.random() }.joinToString("")

    /**
     * Build a DispatchReceipt in one call. Caller passes raw fields; the
     * factory mints the id, sets observedAt, and stamps schemaVersion.
     */
    fun now(
      timestampMs: Long = System.currentTimeMillis(),
      observedAt: Long = timestampMs,
      initiator: DispatchInitiator,
      sourceTurnId: String?,
      sourceAgentId: String?,
      provider: String,
      modelId: String,
      routeMode: ModelMode,
      routerEnabled: Boolean,
      decisionSource: DecisionSource,
      reason: String,
      leaseSnapshotId: String?,
      capabilitySnapshotId: String?,
      latencyMs: Long,
      routingComputeMs: Long,
      estimatedCostCents: Double?,
      errorCode: String? = null
    ): DispatchReceipt = DispatchReceipt(
      dispatchId = mintDispatchId(),
      timestampMs = timestampMs,
      observedAt = observedAt,
      initiator = initiator,
      sourceTurnId = sourceTurnId,
      sourceAgentId = sourceAgentId,
      provider = provider,
      modelId = modelId,
      routeMode = routeMode,
      routerEnabled = routerEnabled,
      decisionSource = decisionSource,
      reason = reason,
      leaseSnapshotId = leaseSnapshotId,
      capabilitySnapshotId = capabilitySnapshotId,
      latencyMs = latencyMs,
      routingComputeMs = routingComputeMs,
      estimatedCostCents = estimatedCostCents,
      errorCode = errorCode
    )

    /** JSON-safe map shape consumed by system.router.inspect / last_decision / trace. */
    fun toMapForSerialization(r: DispatchReceipt): Map<String, Any?> = mapOf(
      "dispatchId" to r.dispatchId,
      "timestampMs" to r.timestampMs,
      "observedAt" to r.observedAt,
      "ageMs" to (System.currentTimeMillis() - r.observedAt),
      "initiator" to r.initiator.name,
      "sourceTurnId" to r.sourceTurnId,
      "sourceAgentId" to r.sourceAgentId,
      "provider" to r.provider,
      "modelId" to r.modelId,
      "routeMode" to r.routeMode.name,
      "routerEnabled" to r.routerEnabled,
      "decisionSource" to r.decisionSource.name,
      "reason" to r.reason,
      "leaseSnapshotId" to r.leaseSnapshotId,
      "capabilitySnapshotId" to r.capabilitySnapshotId,
      "latencyMs" to r.latencyMs,
      "routingComputeMs" to r.routingComputeMs,
      "estimatedCostCents" to r.estimatedCostCents,
      "errorCode" to r.errorCode,
      "schemaVersion" to r.schemaVersion
    )

    /** Reverse of [toEntity]: hydrate a DispatchReceipt from a Room row. */
    fun fromEntity(e: DispatchLedgerEntity): DispatchReceipt = DispatchReceipt(
      dispatchId = e.dispatchId,
      timestampMs = e.timestampMs,
      observedAt = e.observedAt,
      initiator = runCatching { DispatchInitiator.valueOf(e.initiator) }
        .getOrDefault(DispatchInitiator.UNKNOWN),
      sourceTurnId = e.sourceTurnId,
      sourceAgentId = e.sourceAgentId,
      provider = e.provider,
      modelId = e.modelId,
      routeMode = runCatching { ModelMode.valueOf(e.routeMode) }
        .getOrDefault(ModelMode.AUTO),
      routerEnabled = e.routerEnabled != 0,
      decisionSource = runCatching { DecisionSource.valueOf(e.decisionSource) }
        .getOrDefault(DecisionSource.UNKNOWN),
      reason = e.reason,
      leaseSnapshotId = e.leaseSnapshotId,
      capabilitySnapshotId = e.capabilitySnapshotId,
      latencyMs = e.latencyMs,
      routingComputeMs = e.routingComputeMs,
      estimatedCostCents = e.estimatedCostCents,
      errorCode = e.errorCode,
      schemaVersion = e.schemaVersion
    )
  }

  fun toEntity(): DispatchLedgerEntity = DispatchLedgerEntity(
    dispatchId = dispatchId,
    timestampMs = timestampMs,
    observedAt = observedAt,
    initiator = initiator.name,
    sourceTurnId = sourceTurnId,
    sourceAgentId = sourceAgentId,
    provider = provider,
    modelId = modelId,
    routeMode = routeMode.name,
    routerEnabled = if (routerEnabled) 1 else 0,
    decisionSource = decisionSource.name,
    reason = reason,
    leaseSnapshotId = leaseSnapshotId,
    capabilitySnapshotId = capabilitySnapshotId,
    latencyMs = latencyMs,
    routingComputeMs = routingComputeMs,
    estimatedCostCents = estimatedCostCents,
    errorCode = errorCode,
    schemaVersion = schemaVersion
  )
}

/**
 * Who triggered this call. Distinct from DecisionSource (which is "how the
 * route was decided"). Same dispatch can be MANUAL-initiated and AUTO-selected.
 */
enum class DispatchInitiator {
  MANUAL,    // user typed it directly
  MODEL,     // a Soul asked the router on its own (Council askSeat, worker pool)
  ROUTER,    // internal router-side event (cache refresh, fallback ping)
  FALLBACK,  // spawned by a previous fallback attempt
  WORKER,    // spawned by a background worker / scheduler
  UNKNOWN    // unattributable (legacy, repair, test)
}

/**
 * How the route was decided. Distinct from Initiator.
 *   LOCAL_PIN   — user pinned a specific model; no selector ran
 *   AUTO_SELECT — selector chose a candidate from the catalogue
 *   FALLBACK    — selector exhausted healthy primaries; fallback path used
 *   CORE_RELAY  — home core (:7780) made the decision; phone mirrored it
 *   UNKNOWN     — pre-Step-12 records, or repair / migration rows
 */
enum class DecisionSource {
  LOCAL_PIN,
  AUTO_SELECT,
  FALLBACK,
  CORE_RELAY,
  UNKNOWN
}