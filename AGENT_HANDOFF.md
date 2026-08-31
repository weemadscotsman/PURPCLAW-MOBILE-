# PurpClaw Sovereign Android — Agent Handoff & Runbook

## Overview
This document guides any incoming agent or engineer taking over the PurpClaw Android codebase. The application is built under the strict **Anti-Mock Build Contract**: status screens and capability registries reflect cryptographic receipts and real hardware probe outcomes only.

---

## The 7 Canonical Vertical Slices

**Slice registry (frozen — do not renumber):**

```text
001 Home Session Mesh
002 Execution + signed receipt
003 Voice
004 Camera / Vision
005 Memory cold restart
006 Shared Canvas / cross-session handoff
007 DRIVE / autonomous governed work
```

Slices 006 and 007 are first-class product requirements, not appendices.
DRIVE (007) is delegated steering authority over the canonical Steering
Resolver — see `PURPCLAW_STEERING_RESOLVER_CONTRACT.md` in Purp main. There is
no separate autonomous-policy engine on Android.

### 🟣 SLICE 001 — Home-First Session Mesh (Hardware Gate)
**Objective**: Prove bidirectional streaming and autonomous failover between one Android phone and one Home PC Rig.

#### Acceptance Checklist:
1. **Pairing & Discovery**:
   - Phone connects to Home PC WebSocket (`ws://<HOME_PC_IP>:8000/mesh/stream`).
   - Home node responds to `GET /health` with `{"nodeId": "HOME-RIG-01", "status": "ONLINE"}`.
   - Record `nodeId`, `sessionId`, `latencyMs`, and `heartbeat` in local database.
2. **Canonical Session Sync**:
   - Operator types on PC: `"PC SAYS ALPHA"`.
   - Android phone receives the turn automatically over WebSocket event spine without manual refresh.
   - Operator types on Android: `"PHONE SAYS BRAVO"`.
   - PC receives the turn in the same `sessionId`.
3. **Physical Network Disconnect Gate**:
   - Disconnect PC network cable or kill Home PC server.
   - Phone detects missed heartbeat within 3 pings.
   - Transition: `HOME_ONLINE` $\rightarrow$ `DEGRADED` $\rightarrow$ `SOVEREIGN_LOCAL`.
4. **Sovereign Local Response**:
   - Send: `"PHONE OFFLINE CHECK"`.
   - Phone routes prompt to OpenRouter / Gemini Cloud / Local Gemma fallback.
   - Stores turn in local SQLite Room database with status `SOVEREIGN_GENERATED`.
5. **Session Shadow & Split-Brain Reconnect**:
   - While disconnected, add event on PC and event on Phone.
   - Re-enable PC network.
   - Phone reconnects, compares `lastSynchronizedEventId`, and merges events without overwriting.

---

### 🟣 SLICE 002 — Bounded Tool Execution Law
**Objective**: Prove execution bounds and cryptographic receipt generation.

#### Acceptance Checklist:
1. Tap **Arm Lease** on Command Deck $\rightarrow$ UI prompts for duration (e.g. 5 minutes) and max calls (e.g. 10).
2. Execute `android.device.info` tool.
3. Verify output includes real `Build.MANUFACTURER` and `Build.MODEL`.
4. Check **Audit & Proof** screen:
   - Receipt generated with status `VERIFIED`.
   - `proofHash` is SHA-256 digest of execution.
   - `signature` verified using AndroidKeyStore public key.
5. Tap **Revoke Lease** $\rightarrow$ attempt tool call $\rightarrow$ must fail with `SECURITY ERROR: Lease revoked`.

---

### 🟣 SLICE 003 — Voice Roundtrip (STT $\rightarrow$ LLM $\rightarrow$ TTS)
**Objective**: Live speech transcription and low-latency acoustic speech playback.

#### Acceptance Checklist:
1. Grant `android.permission.RECORD_AUDIO`.
2. Tap microphone icon $\rightarrow$ speak into phone $\rightarrow$ verify real words populate input box.
3. Dispatch turn $\rightarrow$ model generates response $\rightarrow$ Native TTS or Kokoro streams voice output.
4. Tap anywhere to speak $\rightarrow$ barge-in interruption immediately halts speech output.

---

### 🟣 SLICE 004 — CameraX Optical Vision Frame & SHA-256 Digest
**Objective**: Optical evidence capture for spatial analysis.

#### Acceptance Checklist:
1. Grant `android.permission.CAMERA`.
2. Trigger `android.camera.capture`.
3. Verify `.jpg` file created in `/data/user/0/com.example/files/workspace/evidence/`.
4. Compute SHA-256 hash of image file and verify receipt generated in `ProofReceipt` table.

---

### 🟣 SLICE 005 — 7-Layer Persistent Memory Cold Restart
**Objective**: Ensure the seven canonical memory layers persist across process termination.

**CANONICAL MEMORY TAXONOMY (imported verbatim from PurpClaw main — one canonical registry per concept, no Android fork):**

```text
1. Episodic        2. Semantic       3. Procedural
4. Symbolic        5. Temporal       6. Counterfactual
7. Affective
```

Canonical source of truth: `lib/commands/memory.js` (`CANON`) in Purp main.
The names `Ephemeral`, `Working`, `ShortTerm` are NOT canonical layer names and
must not appear as memory layers in this codebase. (Ephemeral runtime state is a
runtime-lifecycle concept from `PURPCLAW_EPHEMERAL_RUNTIME_SPEC.md`, not a
persistent memory layer.)

#### Acceptance Checklist:
1. Add custom memory entries across all 7 canonical layers (Episodic, Semantic, Procedural, Symbolic, Temporal, Counterfactual, Affective).
2. Force kill app via Android App Info $\rightarrow$ **Force Stop**.
3. Reopen PurpClaw $\rightarrow$ verify all 7 layers hydrate with zero data loss.

---

### 🟣 SLICE 006 — Shared Canvas / Cross-Session Handoff
**Objective**: One shared mission canvas across phone + PC; handoff without data loss or silent overwrites.

#### Acceptance Checklist:
1. Create a canvas task on PC; edit on Phone in the same session.
2. Force split-brain (disconnect), mutate both sides, reconnect.
3. Verify DAG merge preserves both sides and concurrent property edits produce an explicit `CONFLICT` requiring operator review — never a silent last-write-wins.

---

### 🟣 SLICE 007 — DRIVE / Autonomous Governed Work
**Objective**: Operator arms DRIVE once; the Steering Resolver compiles a delegation capsule and the system autonomously transitions CHAT → PLAN → EXECUTE → SWARM → VERIFY → CHAT with no further operator gestures.

DRIVE is **delegated steering authority**, not a chatbot mode. The capsule fields
(`allowedControlSurfaces`, `humanGateRequired`, `maxParallelism`,
`destructiveMutationsAllowed`) bound everything. Certification gates C23–C26
(`PURPCLAW_STEERING_RESOLVER_CONTRACT.md`) are mandatory before this slice may
be marked LIVE_VERIFIED.

#### Acceptance Checklist:
1. Arm DRIVE → verify a signed delegation record exists with `authority = 900`, `effect = DELEGATE`.
2. Give a task requiring planning + execution + verification → prove full transition cycle with zero secondary operator input (`DRIVE_SELF_ROUTING`).
3. Ask "what is 87?" under DRIVE → prove CHAT only, 0 tools, 0 agents (`DRIVE_MINIMUM_NECESSARY_ACTION`).
4. Drive a task into a destructive/external boundary → prove HUMAN_GATE pause + resume-after-approval (`DRIVE_BOUNDARY_ENFORCEMENT`).
5. Revoke DRIVE mid-task → prove active authority closes, new invocations rejected, checkpoint resumable (`DRIVE_REVOCATION`).

---

## Transitioning Modules from `PRESENT_UNVERIFIED` to `LIVE_VERIFIED`

When executing verification tests on hardware, call:
```kotlin
viewModel.truthRegistry.updateSubsystemState(
  id = "subsystem.mesh.home_session",
  state = CapabilityTruthState.LIVE_VERIFIED,
  health = "Hardware WebSocket probe succeeded; latency: 18ms; 0 dropped turns.",
  evidenceId = "rcpt_mesh_001"
)
```
This automatically triggers `KeystoreReceiptSigner` to sign the state transition with hardware keys.
