package com.example.core.model

import com.example.core.database.ProofReceiptEntity

/**
 * Full Cryptographic Hardware Proof Receipt schema.
 * Replaces naive string-prefix checks with rigorous signature, evidence hash,
 * and test-identity verification.
 */
data class ProofReceipt(
  val receiptId: String,
  val receiptType: String = "TOOL_EXECUTION",     // e.g. "HARDWARE_ACCEPTANCE", "TOOL_EXECUTION", "MESH_HANDSHAKE"
  val subsystemId: String = "subsystem.android.toolruntime", // Target subsystem ID
  val testId: String = "SLICE_ACCEPTANCE",        // Specific acceptance test
  val deviceId: String = "AndroidDevice",         // Physical Android Device ID / Build.FINGERPRINT
  val nodeId: String = "phone-android-node-01",   // e.g. "phone-android-node-01"
  val sessionId: String = "ses_canonical_01",     // Canonical active session ID
  val startedAt: Long = System.currentTimeMillis(),
  val completedAt: Long = System.currentTimeMillis(),
  val result: String = "UNKNOWN",                 // PASS | FAIL | UNKNOWN
  val inputHash: String = "",                     // SHA-256 digest of test input / parameters
  val outputHash: String = "",                    // SHA-256 digest of result payload
  val evidenceHash: String = "",                  // SHA-256 digest of complete execution trace
  val signingKeyId: String = "purpclaw_hardware_root_key_v1", // Key alias in AndroidKeyStore
  val signatureAlgorithm: String = "SHA256withECDSA", // e.g. "SHA256withECDSA"
  val signature: String = "",                     // Base64 ECDSA Signature over canonical proof payload
  val nonce: String = "nonce_0",
  val schemaVersion: Int = 1,
  val verificationStatus: String = "UNVERIFIED",
  val actor: String = "PurpClaw Phone",
  val agent: String = "System",
  val toolName: String = "",
  val modelUsed: String = "Hardware Keystore",
  val evidenceSummary: String = "",
  val proofHash: String = "",
  val timestamp: Long = completedAt,
  val executionLeaseId: String = ""
) {
  fun computeCanonicalPayload(): String {
    return "$receiptId:$subsystemId:$testId:$deviceId:$nodeId:$sessionId:$result:$inputHash:$outputHash:$evidenceHash:$startedAt:$completedAt:$nonce"
  }

  fun toEntity(): ProofReceiptEntity {
    return ProofReceiptEntity(
      receiptId = receiptId,
      actor = actor,
      agent = agent,
      toolName = if (toolName.isNotBlank()) toolName else subsystemId,
      modelUsed = modelUsed,
      evidenceSummary = if (evidenceSummary.isNotBlank()) evidenceSummary else "Proof for $testId: result=$result",
      proofHash = if (proofHash.isNotBlank()) proofHash else evidenceHash,
      timestamp = timestamp,
      executionLeaseId = executionLeaseId,
      signingKeyId = signingKeyId,
      signature = signature,
      verificationStatus = verificationStatus
    )
  }
}
