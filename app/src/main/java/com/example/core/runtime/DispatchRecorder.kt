package com.example.core.runtime

import com.example.core.database.DispatchLedgerDao
import com.example.core.database.DispatchLedgerEntity
import com.example.core.model.DispatchReceipt

/**
 * DISPATCH RECORDER — single-responsibility writer for the routing-decision
 * ledger (see DispatchReceipt / DispatchLedgerDao).
 *
 * Why a thin wrapper:
 * - Callers (ProviderRouter, HomeRuntimeBridge, CouncilPodcastEngine.askSeat)
 *   never touch the DAO directly. They get a recorder, hand it a receipt,
 *   get the dispatchId back, move on.
 * - The recorder is the only place that knows the conversion policy
 *   (DispatchReceipt → DispatchLedgerEntity) and the observedAt stamping
 *   convention.
 *
 * Law: a recorder MUST be invoked for every dispatch decision, even on
 * failure. A failed dispatch with `errorCode != null` still belongs in the
 * ledger — that *is* the decision provenance.
 */
class DispatchRecorder(private val dao: DispatchLedgerDao) {

  /**
   * Persist one receipt. Returns the dispatchId used (mints one if the
   * receipt arrived without one). Stamps observedAt to "now" if missing.
   */
  suspend fun record(receipt: DispatchReceipt): String {
    val stamped = receipt.copy(observedAt = receipt.observedAt.takeIf { it > 0 }
      ?: System.currentTimeMillis())
    dao.insert(toEntity(stamped))
    return stamped.dispatchId
  }

  /** Pure conversion — exposed for parity-test usage. */
  fun toEntity(r: DispatchReceipt): DispatchLedgerEntity = r.toEntity()
}