package com.example.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ProofReceiptTruthTest {
  @Test
  fun `new receipt is unverified until a verifier explicitly promotes it`() {
    val receipt = ProofReceipt(receiptId = "rcpt_test")

    assertEquals("UNKNOWN", receipt.result)
    assertEquals("UNVERIFIED", receipt.verificationStatus)
  }
}
