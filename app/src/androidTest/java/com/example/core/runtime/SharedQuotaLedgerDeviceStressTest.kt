package com.example.core.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** On-device TVG: exercises the real process-wide ledger without burning API quota. */
@RunWith(AndroidJUnit4::class)
class SharedQuotaLedgerDeviceStressTest {

  @Test
  fun chatWorkVoiceAndPodcastShareOneNimBurstAndExcessIsDelayed() = runBlocking {
    SharedQuotaLedger.configureProvider(
      "nim",
      SharedQuotaLedger.ProviderPolicy(
        hardMaxRpm = 40, operatingRpm = 36, maxConcurrency = 3, burstCapacity = 3
      )
    )
    val modes = listOf(
      SharedQuotaLedger.Priority.INTERACTIVE, // CHAT
      SharedQuotaLedger.Priority.INTERACTIVE, // VOICE
      SharedQuotaLedger.Priority.WORK,
      SharedQuotaLedger.Priority.PODCAST
    )
    val results = (0 until 100).map { index ->
      async(Dispatchers.Default) {
        SharedQuotaLedger.acquire("nim", "device_stress_$index", 0, modes[index % modes.size])
      }
    }.map { it.await() }

    val granted = results.count { it is SharedQuotaLedger.AcquireResult.Granted }
    val delayed = results.filterIsInstance<SharedQuotaLedger.AcquireResult.Denied>()
    assertTrue("one shared burst must cap all four modes at 3, got $granted", granted <= 3)
    assertEquals(100 - granted, delayed.size)
    assertTrue("excess calls need a real delay hint", delayed.all { it.backoffMs > 0L })
    assertEquals(granted.toLong(), SharedQuotaLedger.telemetry().first { it.provider == "nim" }.active)
    repeat(granted) { SharedQuotaLedger.release("nim") }
  }
}
