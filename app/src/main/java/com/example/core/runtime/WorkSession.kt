package com.example.core.runtime

import com.example.core.model.DispatchReceipt
import java.util.UUID

/**
 * WORK SESSION — persistent session tying a voice/delegated request
 * to a bounded execution lifecycle: plan → execute → verify → receipt → notification.
 *
 * Invariants:
 * - One session = one objective (voice request or explicit job).
 * - Session lives in purpclaw_state preferences (single source of truth, no duplicate stores).
 * - Execution is either: WorkManager deferrable (retryable) or foreground-service active.
 * - "complete" only fires after verification receipt, not because the model got bored.
 * - All produced artifacts + code + receipts are saved to the phone with dispatchId references.
 */
data class WorkSession(
  val sessionId: String = UUID.randomUUID().toString(),
  val objective: String,               // the original voice request / job description
  val plan: String,                    // decomposed plan / capability requirements
  val acceptanceCriteria: String,      // signed-off success conditions
  var status: WorkSession.Status = WorkSession.Status.PLANNING, // PLANNING | EXECUTING | VERIFYING | COMPLETED | CANCELLED
  val createdAt: Long = System.currentTimeMillis(),
  var updatedAt: Long = System.currentTimeMillis(),
  var executionId: String? = null,   // Workermanager workId OR foreground-service leaseId
  var provider: String? = null,      // which brain served this turn (NIM / OpenRouter / etc.)
  val artifactRefs: List<ArtifactRef> = emptyList(),  // generated code/files/receipts
  val receiptChain: List<DispatchReceipt> = emptyList() // audit trail of decisions
) {
  enum class Status { PLANNING, EXECUTING, VERIFYING, COMPLETED, CANCELLED }

  data class ArtifactRef(
    val key: String,          // unique key within the session (e.g. "code_001", "report.pdf")
    val path: String,         // absolute or relative path on the phone's external files dir
    val mimeType: String,     // used for intent resolution (OPEN CODE / OPEN RESULT / RECEIPT)
    val description: String   // human-readable label for the notification
  )

  fun isComplete(): Boolean = status == Status.COMPLETED
  fun isActive(): Boolean = status in setOf(Status.PLANNING, Status.EXECUTING, Status.VERIFYING)
  fun cancel(): WorkSession = copy(status = Status.CANCELLED, updatedAt = System.currentTimeMillis())
}

/**
 * Notification action codes sent when the user taps a notification action.
 * These map to Intent actions that launch the app with a specific mode.
 */
enum class NotificationAction {
  OPEN_CODE,     // land in workspace / file browser showing generated code
  OPEN_RESULT,   // open the generated artifact (PDF, DOCX, image, website, etc.)
  RECEIPT,       // view the DispatchReceipt audit trail
  RESUME,        // resume / continue the session (e.g. swap provider, revise plan)
  CANCEL         // mark the session as CANCELLED and clean up
}