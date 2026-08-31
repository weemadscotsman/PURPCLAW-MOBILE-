package com.example.core.model

/**
 * Podcast Studio data model: a live council podcast recorded as an ordered
 * transcript of speaker turns with per-seat voice tags and routing receipts.
 */
data class SpeakerTurn(
  val speakerName: String,
  val role: String,          // e.g. "HOST", "ARCHITECTURE", "HOSTILE REVIEWER"
  val voiceTag: String,      // seat key for TTS voice assignment
  val text: String,
  val timestamp: Long = System.currentTimeMillis(),
  /**
   * Tool calls invoked during this seat's turn (2026-08-26). Empty list when
   * the seat produced text only. Bumped schema version to 2 — older
   * readers tolerate missing field via JSON optString; newer parsers can
   * detect non-empty toolCalls and assert introspection actually fired.
   */
  val toolCalls: List<ToolCallSummary> = emptyList(),
  /**
   * STEP 13 (2026-08-27) — receipt-grounded grievances. Parsed from the
   * seat's spoken text via [dispatchId|eventId|skillId|memoryItemId|snapshotId=…]
   * tokens. A turn with zero citations means the seat made an uncited claim —
   * the verifier counts and flags those. Schema v3; old readers tolerate the
   * missing field via JSON opt.
   */
  val citations: List<EvidenceCitation> = emptyList()
)

/** One auditable receipt reference extracted from a seat's spoken turn. */
data class EvidenceCitation(
  val kind: String,        // dispatch | event | skill | memory | snapshot
  val refId: String,       // the raw ID after '='
  val context: String = "" // ~60 chars around the token, for audit display
)

/** Per-turn tool-call record used by Council introspection verification. */
data class ToolCallSummary(
  val name: String,
  val args: Map<String, String> = emptyMap(),
  val success: Boolean = true,
  val evidenceHash: String? = null
)

data class PodcastEpisode(
  val id: String,
  val title: String,
  val topic: String,
  val members: List<SpeakerTurn>,
  val verdictSummary: String,
  val routingReceipts: String,   // which provider/model produced the session
  val createdAt: Long = System.currentTimeMillis()
)
