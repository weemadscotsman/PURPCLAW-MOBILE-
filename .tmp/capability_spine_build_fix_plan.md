# Build Break — Capability Spine Repair (2026-08-26)

Two compile breaks blocking Step 10 + Step 11. Both confirmed by reading the
actual class surfaces.

## Break 1 — ToolRuntimeEngine.kt:760

`system.leases.inspect` dispatcher references `JSONArray(...)` but the file
only imports `org.json.JSONObject`. Single-line import fix.

## Break 2 — MainViewModel.kt:240-360 (`refreshCapabilitySnapshot`)

Function reads invented fields that do not exist on the actual classes:

| Code reference | Real surface |
|---|---|
| `meshCoordinator.lastLatencyMs.value` | NOT a field — `MeshNode` doesn't expose it; this function must derive latency from a probe or leave at 0 |
| `meshCoordinator.lastRuntimeId.value` | NOT a field — `MeshNode.runtimeId` exists on `MeshNode`, accessed via `_homeNode.value.runtimeId` if even that |
| `meshCoordinator.lastAgentCount.value` | NOT a field — agent count only exists as `MeshNode.memoryVersion.toInt()` (the prior session used memoryVersion as agent-count proxy) |
| `meshCoordinator.lastError.value` | Real: `meshCoordinator.homeNodeHealthError` (String?) |
| `meshCoordinator.lastAgentDescriptors.value` | WRONG — agents come from `HomeRuntimeBridge.fetchAgentRoster()` (already exposed via `_agentRoster`) or `toolRuntime.agentDescriptorsResolver` (the resolver I planned to wire) |
| `meshCoordinator.lastRuntimeServices.value` | NOT a field — no such concept anywhere on `SessionMeshCoordinator` |
| `db.receiptDao().recentForSession(_activeSessionId.value, 25)` | NOT a DAO method — `proof_receipts` DAO (`@Dao` near line 270) only exposes `getAllReceipts(): Flow<List<ProofReceiptEntity>>`. No `receiptDao()` accessor on `PurpClawDatabase` either — only `proofReceiptDao()` exists |
| `recentReceipts.map { it.toModel() }` | Entity → model converter not present; only `ProofReceiptEntity` exists |
| `providerRouter.lastRoutingReceipt.value` | Field exists on `RoutingState` (`routingState.value.lastRoutingReceipt`?) — needs deeper read |
| `providerRouter.routingTraceFor(sid)` | Not present on `ProviderRouter` |
| `workingMemory.value` | NOT a member field — `MainViewModel` doesn't have a `workingMemory` flow |
| `db.receiptDao()` | Wrong — DAO is `db.proofReceiptDao()` returning `ProofReceiptDao` |

Plus: the line range declared `_capabilitySnapshot` and `leaseHistory` but
`_memoryItems` is referenced (line 295) without being declared. And the
lifecycle import is missing.

## Repair strategy

Rewrite `refreshCapabilitySnapshot()` against the actual surfaces, accepting
zero-fidelity where the API doesn't carry the data:

- Home status fields: derive from `meshCoordinator.meshStatus.value`,
  `meshCoordinator.homeNode.value` (MeshNode), `meshCoordinator.homeNodeHealthError`,
  `_agentRoster.value` (already declared). No `lastLatencyMs` / `lastRuntimeId` /
  `lastAgentCount` / `lastError` — drop them and pass sensible defaults
  (latency = -1 if not probed, agent count from `_agentRoster.value.size`).
- Agents: map `_agentRoster.value` into `AgentDescriptor` shape.
- Runtime services: hand-build a list with Phone runtime + Home if online,
  per current `homeNode` state. No "lastRuntimeServices" exists.
- Recent receipts: read via `db.proofReceiptDao().getAllReceipts().first()` —
  drop the per-session filter since the DAO doesn't expose one. Map
  `ProofReceiptEntity` → `ToolCallRecord`.
- Lease history: keep the local `leaseHistory` mutable list.
- Inject the resolvers that DO exist on ToolRuntimeEngine:
  `capabilitySnapshotResolver`, `routingStateResolver`,
  `lastRoutingReceiptResolver`, `routingTraceResolver`,
  `agentDescriptorsResolver`, `memorySnapshotResolver`,
  `activeLeasesResolver`.
- Drop the `workingMemory.value` reference. Memory snapshot becomes
  `_memoryItems.value.size` only.
- `meshCoordinator` -> use `meshCoordinator` field reference (already
  declared at line 104). Keep that name.
- `proofReceiptEntity → ToolCallRecord`: write inline mapper or skip it
  and pass empty `executedTools`.

This is a FUNCTIONAL CORRECTNESS fix, not a design expansion. After repair:

1. `./gradlew :app:assembleDebug` -> BUILD SUCCESSFUL
2. Run parity test (already wired) -> Step 9 passes
3. Install + record podcast -> Step 10
4. Spawn verifier -> Step 11
