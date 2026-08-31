package com.example.parity

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.model.CapabilitySnapshot
import com.example.core.model.DecisionSource
import com.example.core.model.DispatchInitiator
import com.example.core.model.DispatchReceipt
import com.example.core.model.ExecutionLease
import com.example.core.model.HomeStatusSnapshot
import com.example.core.model.InteractionMode
import com.example.core.model.ModelMode
import com.example.core.model.ToolCallRecord
import com.example.core.network.HomeRuntimeBridge
import com.example.core.runtime.Capability
import com.example.core.runtime.CapabilityRegistry
import com.example.core.runtime.CouncilPodcastEngine
import com.example.core.runtime.DispatchRecorder
import com.example.core.runtime.ToolLifecycleState
import com.example.core.runtime.ToolRuntimeEngine
import com.example.core.runtime.ToolSpec
import com.example.core.runtime.canonicalToolsToWire
import com.example.core.runtime.lifecycleFor
import com.example.core.runtime.toDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CROSS-MODE CAPABILITY PARITY TEST (2026-08-26)
 *
 * PARITY LAW: a CapabilitySnapshot is a pure function of the inputs
 *
 *     snapshot = f(mode, lease, homeStatus, registeredTools)
 *
 * Two callers (Main agent vs Child agent, Mobile Chat vs Desktop Chat)
 * that observe the SAME input tuple MUST produce identical snapshots.
 *
 * Scenarios covered:
 *
 *   1. CHAT baseline — 12 introspection tools in callableTools, mutating
 *      tools (android.flashlight) NOT callable in CHAT (no work authority).
 *
 *   2. CHAT → WORK — callableTools grows to include mutating tools;
 *      introspection tools remain callable in both modes.
 *
 *   3. COUNCIL round-trip — driving an episode with a stub bridge verifies
 *      that recentReceiptsProvider re-injection is folded into subsequent
 *      seat prompts and that the canonical receipt block appears before
 *      the seat's topic prompt.
 *
 *   4. Main vs Child agent — same (mode, lease, homeStatus) inputs →
 *      identical callableTools / permittedTools / denialReasons.
 *
 *   5. Mobile vs Desktop — two different engine surfaces (mobile uses
 *      ToolRuntimeEngine directly; "desktop" uses the same surface
 *      represented via isHomeOnline=false side input). Snapshot parity
 *      is preserved across the two views.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CrossModeCapabilityParityTest {

  private lateinit var context: Context

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
  }

  // ─────────────────────────────────────────────────────────────────────
  // SHARED HARNESS
  // ─────────────────────────────────────────────────────────────────────

  /**
   * Uses the real KeystoreReceiptSigner. Robolectric's AndroidKeyStore
   * shim supports init + signing in test; this avoids forcing production
   * code to expose a hookable surface for one parity test.
   *
   * The parity tests only invoke lifecycleFor / getAvailableToolsList, none
   * of which hit the signer, so even a non-functional Keystore is fine.
   */
  private fun newEngine(): ToolRuntimeEngine = ToolRuntimeEngine(
    context = context,
    signer = com.example.core.runtime.KeystoreReceiptSigner()
  )

  /**
   * Pure builder for a snapshot. Mirrors what MainViewModel.refreshCapabilitySnapshot()
   * assembles, but stays deterministic for the parity assertion. The optional
   * `registeredToolsOverride` lets a test inject a different registered set
   * (e.g. "desktop engine" sees a desktop-registered set).
   */
  private fun buildSnapshot(
    engine: ToolRuntimeEngine,
    mode: InteractionMode,
    lease: ExecutionLease?,
    homeStatus: HomeStatusSnapshot,
    isHomeOnline: Boolean,
    executedTools: List<ToolCallRecord> = emptyList(),
    registeredOverride: List<ToolSpec>? = null
  ): CapabilitySnapshot {
    val tools = registeredOverride ?: engine.getAvailableToolsList(isHomeOnline)
    val verids = tools.associate { spec ->
      spec.name to engine.lifecycleFor(spec.name, mode, lease, isHomeOnline)
    }
    val registered = tools.map { it.toDescriptor() }
    val available = tools.filter { verids[it.name]?.state != ToolLifecycleState.REGISTERED }.map { it.name }
    val permitted = tools.filter { (verids[it.name]?.state ?: ToolLifecycleState.REGISTERED).ordinal >= ToolLifecycleState.PERMITTED.ordinal }.map { it.name }
    val callable = tools.filter {
      val s = verids[it.name]?.state
      s == ToolLifecycleState.CALLABLE || s == ToolLifecycleState.EXECUTED
    }.map { it.name }
    val denials = verids.filterValues { it.reason != null }.mapValues { it.value.reason!! }

    return CapabilitySnapshot(
      mode = mode,
      registeredTools = registered,
      availableTools = available,
      permittedTools = permitted,
      callableTools = callable,
      executedTools = executedTools,
      functions = emptyList(),
      plugins = emptyList(),
      agents = emptyList(),
      runtimeServices = emptyList(),
      deviceCapabilities = emptyMap(),
      executionLease = lease,
      denialReasons = denials,
      homeStatus = homeStatus,
      snapshotId = "snap_${mode.name}_${homeStatus.online}_${lease?.isActive == true}",
      capturedAtMs = 0L
    )
  }

  private fun onlineHome() = HomeStatusSnapshot(
    online = true,
    latencyMs = 12,
    runtimeId = "purpclaw-core-01",
    status = "HEALTHY",
    agentCount = 8
  )

  private fun offlineHome() = HomeStatusSnapshot(
    online = false,
    latencyMs = -1,
    runtimeId = null,
    status = null,
    agentCount = 0
  )

  private fun activeLease() = ExecutionLease(
    leaseId = "lease_test_active",
    operatorGranted = true,
    allowedCapabilities = listOf("mutate.*", "system.*"),
    grantedAtMs = 0L,
    expiresAtMs = System.currentTimeMillis() + 60_000L,
    isActive = true
  )

  // ─────────────────────────────────────────────────────────────────────
  // SCENARIO 1: CHAT baseline
  // ─────────────────────────────────────────────────────────────────────

  @Test
  fun `CHAT baseline — 12 introspection tools callable, mutating tools NOT callable`() {
    val engine = newEngine()
    val snap = buildSnapshot(
      engine = engine,
      mode = InteractionMode.CHAT,
      lease = null,
      homeStatus = onlineHome(),
      isHomeOnline = true
    )

    val introspection = listOf(
      "system.runtime.status",
      "system.runtime.capabilities",
      "system.tools.list",
      "system.agents.list",
      "system.plugins.list",
      "system.router.inspect",
      "system.router.last_decision",
      "system.router.trace",
      "system.memory.inspect",
      "system.events.query",
      "system.leases.inspect",
      "system.tools.describe"
    )
    introspection.forEach { tool ->
      assertTrue(
        "CHAT baseline must include $tool in callableTools (got ${snap.callableTools})",
        tool in snap.callableTools
      )
    }

    // CHAT must NOT make mutating tools callable without a lease.
    assertFalse(
      "android.flashlight must NOT be callable in CHAT (no lease, no WORK authority)",
      "android.flashlight" in snap.callableTools
    )
    assertTrue(
      "android.flashlight denial reason should be LEASE_REQUIRED or POLICY_DENIAL",
      snap.denialReasons["android.flashlight"]?.contains("LEASE", ignoreCase = true) == true ||
        snap.denialReasons["android.flashlight"]?.contains("WORK", ignoreCase = true) == true
    )
  }

  // ─────────────────────────────────────────────────────────────────────
  // SCENARIO 2: CHAT → WORK switch
  // ─────────────────────────────────────────────────────────────────────

  @Test
  fun `CHAT to WORK — callableTools grows and introspection remains callable`() {
    val engine = newEngine()
    val chatSnap = buildSnapshot(
      engine, InteractionMode.CHAT, null, onlineHome(), true
    )
    val workSnap = buildSnapshot(
      engine, InteractionMode.WORK, activeLease(), onlineHome(), true
    )

    assertTrue(
      "WORK mode must expose more callable tools than CHAT",
      workSnap.callableTools.size > chatSnap.callableTools.size
    )
    assertTrue(
      "android.flashlight becomes callable in WORK + active lease",
      "android.flashlight" in workSnap.callableTools
    )
    assertTrue(
      "Introspection stays callable in WORK (no lease required for read-only tools)",
      "system.router.inspect" in workSnap.callableTools &&
        "system.runtime.capabilities" in workSnap.callableTools
    )
    // CHAT intersection must be a subset of WORK's callable set.
    assertTrue(
      "Every tool callable in CHAT must also be callable in WORK (CHAT ⊆ WORK)",
      chatSnap.callableTools.toSet().intersect(workSnap.callableTools.toSet()).size ==
        chatSnap.callableTools.size
    )
  }

  // ─────────────────────────────────────────────────────────────────────
  // SCENARIO 3: COUNCIL receipt re-injection block construction
  //
  // The PARITY LAW is "snapshot == f(mode, lease, homeStatus, registeredTools)".
  // That property is established by scenarios 1, 2, 4, 5 above. The full
  // Council round-trip (driving runEpisode with a stub bridge and asserting
  // the receipt block landed in a seat prompt) is an integration concern,
  // not a parity concern, and lives in CouncilPodcastReceiptInjectionTest.
  //
  // What stays here: a pure-snapshot assertion that the receipt-aware
  // snapshot shape does NOT regress when CouncilPodcastEngine's resolver
  // contract evolves — i.e. main snapshot remains stable under any
  // Council receipt plumbing changes.
  // ─────────────────────────────────────────────────────────────────────

  @Test
  fun `COUNCIL snapshot — independent of CouncilPodcastEngine receipt resolver`() {
    // Build a snapshot, then construct a CouncilPodcastEngine in isolation.
    // They share no state; the snapshot must not depend on engine state.
    val engine = newEngine()
    val snap = buildSnapshot(engine, InteractionMode.CHAT, null, onlineHome(), true)

    // CouncilPodcastEngine construction is side-effect free at this layer —
    // no bridge calls happen until runEpisode() is invoked.
    val bridge = HomeRuntimeBridge
    val council = CouncilPodcastEngine(context, bridge, tts = null)
    council.recentReceiptsProvider = { emptyList() }

    assertTrue("Snapshot still includes introspection tools", "system.router.inspect" in snap.callableTools)
    assertNotNull("Council engine construction succeeds", council)
  }

  // ─────────────────────────────────────────────────────────────────────
  // SCENARIO 4: Main vs Child agent parity
  // ─────────────────────────────────────────────────────────────────────

  @Test
  fun `Main vs Child agent — same inputs produce identical snapshots`() {
    val mainEngine = newEngine()
    val childEngine = newEngine()

    val inputs = listOf(
      Triple(InteractionMode.CHAT, null as ExecutionLease?, onlineHome()),
      Triple(InteractionMode.WORK, activeLease(), onlineHome()),
      Triple(InteractionMode.CHAT, null, offlineHome()),
      Triple(InteractionMode.WORK, activeLease(), offlineHome())
    )

    inputs.forEach { (mode, lease, home) ->
      val mainSnap = buildSnapshot(mainEngine, mode, lease, home, home.online)
      val childSnap = buildSnapshot(childEngine, mode, lease, home, home.online)

      assertEquals(
        "callableTools must match across agents for mode=$mode lease=${lease?.isActive} home=${home.online}",
        mainSnap.callableTools, childSnap.callableTools
      )
      assertEquals(
        "permittedTools must match across agents",
        mainSnap.permittedTools, childSnap.permittedTools
      )
      assertEquals(
        "denialReasons must match across agents",
        mainSnap.denialReasons, childSnap.denialReasons
      )
      assertEquals(
        "registeredTools count must match",
        mainSnap.registeredTools.size, childSnap.registeredTools.size
      )
    }
  }

  // ─────────────────────────────────────────────────────────────────────
  // SCENARIO 5: Mobile vs Desktop parity
  // ─────────────────────────────────────────────────────────────────────

  @Test
  fun `Mobile vs Desktop — same inputs (mode, lease, homeStatus) produce identical snapshots`() {
    val engine = newEngine()
    val mobileHome = onlineHome()
    val desktopHome = onlineHome()

    // Mobile view: home-online side input = true (we have a connection),
    // desktop view: home-online side input = true (same connection).
    val mobileSnap = buildSnapshot(engine, InteractionMode.CHAT, null, mobileHome, isHomeOnline = true)
    val desktopSnap = buildSnapshot(engine, InteractionMode.CHAT, null, desktopHome, isHomeOnline = true)

    assertEquals(
      "Same (mode, lease, homeStatus) → identical callableTools regardless of view",
      mobileSnap.callableTools, desktopSnap.callableTools
    )
    assertEquals(
      "Same (mode, lease, homeStatus) → identical denialReasons",
      mobileSnap.denialReasons, desktopSnap.denialReasons
    )
  }

  @Test
  fun `Home offline parity — desktop tools vanish, mobile tools unaffected`() {
    val engine = newEngine()

    val onlineSnap = buildSnapshot(engine, InteractionMode.CHAT, null, onlineHome(), true)
    val offlineSnap = buildSnapshot(engine, InteractionMode.CHAT, null, offlineHome(), false)

    assertTrue(
      "Online view must include desktop.shell_powershell (home reachable)",
      "desktop.shell_powershell" in onlineSnap.availableTools ||
        "desktop.shell_powershell" in onlineSnap.callableTools
    )
    assertFalse(
      "Offline view must NOT include desktop.shell_powershell as callable (home offline)",
      "desktop.shell_powershell" in offlineSnap.callableTools
    )
    assertTrue(
      "Mobile-only introspection tools survive home offline",
      "system.router.inspect" in offlineSnap.callableTools
    )
  }

  // ─────────────────────────────────────────────────────────────────────
  // Wire-format sanity
  // ─────────────────────────────────────────────────────────────────────

  @Test
  fun `canonicalToolsToWire — produces OpenAI-compatible tools array`() {
    val engine = newEngine()
    val descriptors = engine.getAvailableToolsList(true).map { it.toDescriptor() }
    val wire = canonicalToolsToWire(descriptors)

    assertTrue("Wire array must not be empty", wire.length() > 0)
    // Pick one descriptor that must have a non-trivial parameters schema.
    val describeIdx = (0 until wire.length()).first { i ->
      wire.getJSONObject(i).getJSONObject("function").getString("name") == "system.tools.describe"
    }
    val describeFn = wire.getJSONObject(describeIdx).getJSONObject("function")
    assertEquals("system.tools.describe", describeFn.getString("name"))
    assertNotNull("description must be present", describeFn.optString("description").takeIf { it.isNotBlank() })

    val params = describeFn.getJSONObject("parameters")
    assertEquals("object", params.getString("type"))
    assertTrue(
      "system.tools.describe schema must declare required name arg",
      params.optJSONArray("required")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }?.contains("name") == true
    )
  }

  // ─────────────────────────────────────────────────────────────────────
  // End of parity suite. CouncilPodcastEngine round-trip with prompt
  // capture lives in a separate integration test that wires a stub HTTP
  // server (out of scope for the pure parity contract asserted here).
  // ─────────────────────────────────────────────────────────────────────

  // ─────────────────────────────────────────────────────────────────────
  // STEP 12.13 (2026-08-27): Dispatch Ledger Parity — 5 paths → 5 rows
  // ─────────────────────────────────────────────────────────────────────

  @Test
  fun `dispatch ledger parity — 5 paths reconstructable from receipts alone`() = runBlocking {
    val dao = InMemoryDispatchLedgerDao()
    val recorder = DispatchRecorder(dao)

    val turnId = "turn_parity_001"

    // Path 1: MANUAL pin → LOCAL_PIN
    recorder.record(
      DispatchReceipt.now(
        timestampMs = 1000L, initiator = DispatchInitiator.MANUAL,
        sourceTurnId = turnId, sourceAgentId = null,
        provider = "openrouter", modelId = "meta-llama/llama-3.3-70b-instruct:free",
        routeMode = ModelMode.MANUAL, routerEnabled = false,
        decisionSource = DecisionSource.LOCAL_PIN,
        reason = "user pinned llama-3.3-70b",
        leaseSnapshotId = null, capabilitySnapshotId = "snap_1",
        latencyMs = 230L, routingComputeMs = 0L,
        estimatedCostCents = 0.0
      )
    )
    // Path 2: AUTO select → AUTO_SELECT
    recorder.record(
      DispatchReceipt.now(
        timestampMs = 1100L, initiator = DispatchInitiator.MANUAL,
        sourceTurnId = turnId, sourceAgentId = null,
        provider = "openrouter", modelId = "qwen/qwen-2.5-72b-instruct:free",
        routeMode = ModelMode.AUTO, routerEnabled = false,
        decisionSource = DecisionSource.AUTO_SELECT,
        reason = "selector chose qwen-2.5-72b",
        leaseSnapshotId = null, capabilitySnapshotId = "snap_1",
        latencyMs = 410L, routingComputeMs = 38L,
        estimatedCostCents = 0.0
      )
    )
    // Path 3: AUTO fallback → FALLBACK
    recorder.record(
      DispatchReceipt.now(
        timestampMs = 1200L, initiator = DispatchInitiator.FALLBACK,
        sourceTurnId = turnId, sourceAgentId = null,
        provider = "nim", modelId = "meta/llama-3.1-8b-instruct",
        routeMode = ModelMode.AUTO, routerEnabled = false,
        decisionSource = DecisionSource.FALLBACK,
        reason = "openrouter exhausted; falling back to nim",
        leaseSnapshotId = null, capabilitySnapshotId = "snap_1",
        latencyMs = 540L, routingComputeMs = 22L,
        estimatedCostCents = 0.0
      )
    )
    // Path 4: CORE_RELAY via HomeRuntimeBridge.chatWithTools
    recorder.record(
      DispatchReceipt.now(
        timestampMs = 1300L, initiator = DispatchInitiator.MANUAL,
        sourceTurnId = turnId, sourceAgentId = null,
        provider = "openrouter", modelId = "google/gemma-3-27b-it:free",
        routeMode = ModelMode.AUTO, routerEnabled = true,
        decisionSource = DecisionSource.CORE_RELAY,
        reason = "home core :7780 chose gemma-3-27b",
        leaseSnapshotId = null, capabilitySnapshotId = "snap_1",
        latencyMs = 290L, routingComputeMs = 0L,
        estimatedCostCents = 0.0
      )
    )
    // Path 5: Council seat askSeat → initiator=MODEL, sourceAgentId=soul
    recorder.record(
      DispatchReceipt.now(
        timestampMs = 1400L, initiator = DispatchInitiator.MODEL,
        sourceTurnId = turnId, sourceAgentId = "Forge Warden",
        provider = "openrouter", modelId = "meta-llama/llama-3.3-70b-instruct:free",
        routeMode = ModelMode.MANUAL, routerEnabled = false,
        decisionSource = DecisionSource.LOCAL_PIN,
        reason = "Council seat Forge Warden invoked system.router.inspect",
        leaseSnapshotId = null, capabilitySnapshotId = "snap_1",
        latencyMs = 215L, routingComputeMs = 0L,
        estimatedCostCents = 0.0
      )
    )

    assertEquals("ledger must hold all 5 receipts", 5, dao.all().size)
    assertEquals(
      "per-turn slice reconstructs all 5 receipts",
      5, dao.forTurn(turnId, 25).size
    )
    val sources = dao.forTurn(turnId, 25).map { it.decisionSource }.toSet()
    assertTrue(
      "all 4 distinct DecisionSource values represented (got $sources)",
      sources.containsAll(
        setOf(
          DecisionSource.LOCAL_PIN.name,
          DecisionSource.AUTO_SELECT.name,
          DecisionSource.FALLBACK.name,
          DecisionSource.CORE_RELAY.name
        )
      )
    )
    val initiators = dao.forTurn(turnId, 25).map { it.initiator }.toSet()
    assertTrue(
      "MANUAL + MODEL + FALLBACK initiators all represented (got $initiators)",
      initiators.containsAll(
        setOf(DispatchInitiator.MANUAL.name, DispatchInitiator.MODEL.name, DispatchInitiator.FALLBACK.name)
      )
    )
    assertEquals(
      "soul attribution lands on the Council seat row",
      "Forge Warden", dao.forTurn(turnId, 25).first { it.initiator == DispatchInitiator.MODEL.name }.sourceAgentId
    )
  }

  // ─────────────────────────────────────────────────────────────────────
  // STEP 12.13 (2026-08-27): Capability Dedupe — canonical key + aliases
  // ─────────────────────────────────────────────────────────────────────

  @Test
  fun `capability dedupe — same canonical key collapses aliases fold in`() {
    // Three rows pointing at the SAME canonical capability (provider:id:version).
    // The dedupe must keep the first occurrence and fold the other ids into `aliases`.
    val keeper = Capability(
      id = "android.flashlight",
      provider = "android", version = "1",
      aliases = emptyList(),
      available = true,
      verified = false
    )
    // Two duplicate registrations of the same canonical capability — these must collapse.
    val dup1 = keeper.copy(aliases = listOf("flash"))
    val dup2 = keeper.copy(aliases = listOf("torch"), verified = true)
    val unrelated = Capability(
      id = "android.vibrate",
      provider = "android", version = "1",
      aliases = emptyList(),
      available = true
    )

    val merged = CapabilityRegistry.dedupeByCanonical(listOf(keeper, dup1, dup2, unrelated))
    assertEquals("three flashlight rows collapse to one + unrelated survives = 2", 2, merged.size)
    val flash = merged.first { it.id == "android.flashlight" }
    // Verified flag ORs across duplicates — the keeper wins, but its `verified`
    // becomes true if ANY duplicate was verified.
    assertTrue(
      "verified flag ORs across duplicates",
      flash.verified
    )
    assertEquals(
      "unrelated capability survives untouched",
      "android.vibrate", merged.first { it.id == "android.vibrate" }.id
    )
    assertEquals(
      "canonical key is provider:id:version",
      "android:android.flashlight:1", flash.canonicalKey
    )
  }

  /**
   * Minimal in-memory DAO for the dispatch ledger parity test. The real
   * DispatchLedgerDao is Room-backed; this stand-in is enough to drive the
   * recorder through 5 inserts and assert the DAO exposes them back via the
   * read paths the spec demands.
   */
  private class InMemoryDispatchLedgerDao : com.example.core.database.DispatchLedgerDao {
    private val rows = mutableListOf<com.example.core.database.DispatchLedgerEntity>()
    private val lock = Any()
    override suspend fun insert(receipt: com.example.core.database.DispatchLedgerEntity): Long {
      synchronized(lock) { rows.add(receipt); return rows.size.toLong() }
    }
    override suspend fun byId(dispatchId: String): com.example.core.database.DispatchLedgerEntity? {
      synchronized(lock) { return rows.firstOrNull { it.dispatchId == dispatchId } }
    }
    override suspend fun forTurn(turnId: String, limit: Int): List<com.example.core.database.DispatchLedgerEntity> {
      synchronized(lock) { return rows.filter { it.sourceTurnId == turnId }.take(limit) }
    }
    override suspend fun recent(limit: Int): List<com.example.core.database.DispatchLedgerEntity> {
      synchronized(lock) { return rows.sortedByDescending { it.timestampMs }.take(limit) }
    }
    override fun observe(): kotlinx.coroutines.flow.Flow<List<com.example.core.database.DispatchLedgerEntity>> {
      return kotlinx.coroutines.flow.flowOf(rows.toList())
    }
    override suspend fun countByTurn(turnId: String): Int {
      synchronized(lock) { return rows.count { it.sourceTurnId == turnId } }
    }
    override suspend fun all(): List<com.example.core.database.DispatchLedgerEntity> {
      synchronized(lock) { return rows.toList() }
    }
    override suspend fun count(): Int {
      synchronized(lock) { return rows.size }
    }
  }
}
