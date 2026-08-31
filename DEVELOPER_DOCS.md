# PurpClaw Sovereign Android Runtime — Developer Documentation

## 1. System Architecture Overview

PurpClaw Mobile is an **agentic hybrid operating system** designed for Android (API 34–36, arm64-v8a) with bidirectional session mesh linkage to a Home Workstation (PC Rig) and autonomous sovereign local failover.

```
+-------------------------------------------------------------------------+
|                        JETPACK COMPOSE UI LAYER                         |
|  Command Deck | Audit & Truth | Shared Canvas | Memory | Mesh & Tools   |
+-------------------------------------------------------------------------+
                                    │
                                    ▼
+-------------------------------------------------------------------------+
|                       MAIN VIEWMODEL & EVENT BUS                        |
|   StateFlows • Coroutine Scopes • Lifecycle-Aware Tool Dispatchers      |
+-------------------------------------------------------------------------+
        │                           │                           │
        ▼                           ▼                           ▼
+───────────────────+       +───────────────────+       +───────────────────+
| HARDWARE SECURITY |       | 7-LAYER MEMORY    |       | TOOL RUNTIME      |
| KeystoreSigner    |       | Room DB v7 SQLite |       | Battery, StatFs   |
| KeyStoreVault     |       | 7 Canonical       |       | Connectivity      |
| EC-P256 Hardware  |       | Layers (see §2a)  |       | Notification      |
| AES-GCM 256-bit   |       | Receipts & Audits |       | CameraX & Mic     |
+───────────────────+       +───────────────────+       +───────────────────+
        │                           │                           │
        └───────────────────────────┼───────────────────────────┘
                                    │
                                    ▼
+─────────────────────────────────────────────────────────────────────────+
|                     CAPABILITY TRUTH REGISTRY                           |
|       Enforces Anti-Mock Contract: LIVE_VERIFIED Requires Proof         |
+─────────────────────────────────────────────────────────────────────────+
        │                                                       │
        ▼                                                       ▼
+───────────────────────────+               +───────────────────────────+
| SESSION MESH COORDINATOR  |               | MULTI-PROVIDER ROUTER     |
| Real Home PC Heartbeat    |               | Sovereign Gemini Cloud    |
| WebSocket & REST RPC      |               | OpenRouter Multi-Model    |
| Sovereign Failover Engine |               | MiniMax (Text/TTS/Video)  |
| Event Spine Reconciler    |               | LocalModelHost (Gemma)    |
+───────────────────────────+               +───────────────────────────+
```

---

## 2. Cryptographic Hardware Proof Engine

PurpClaw binds execution integrity directly to Android hardware via `KeystoreReceiptSigner` and `KeyStoreVault`.

### A. Hardware Keypair Generation (`KeystoreReceiptSigner.kt`)
- **Key Alias**: `purpclaw_master_signing_key`
- **Key Store Provider**: `AndroidKeyStore`
- **Algorithm**: Elliptic Curve (`KeyProperties.KEY_ALGORITHM_EC`), 256-bit prime curve (`secp256r1` / `NIST P-256`).
- **Signature Algorithm**: `SHA256withECDSA`.
- **Purpose**: `PURPOSE_SIGN` | `PURPOSE_VERIFY`.
- **Digest**: `DIGEST_SHA256`.

### B. Execution Receipts (`ProofReceipt.kt`)
Every executed tool action and verified state change generates a signed immutable receipt:
```kotlin
data class ProofReceipt(
  val receiptId: String,
  val verificationStatus: String, // VERIFIED | FAILED
  val actor: String,
  val agent: String,
  val toolName: String,
  val modelUsed: String,
  val evidenceSummary: String,
  val proofHash: String,         // SHA-256(tool:args:output:duration)
  val timestamp: Long,
  val executionLeaseId: String,
  val signingKeyId: String,      // purpclaw_ec_p256_hw
  val signature: String          // Base64 ECDSA Signature
)
```

### C. AES-GCM Encrypted Secret Vault (`KeyStoreVault.kt`)
- **Key Alias**: `purpclaw_vault_master_key`
- **Algorithm**: `AES/GCM/NoPadding` (256-bit symmetric key).
- **IV Generation**: 12-byte cryptographically secure random IV prepended to ciphertext.
- **Storage**: Hardware-encrypted bytes stored in encrypted app preferences.

---

### §2a. Canonical Memory Layers (no local fork)

The 7-LAYER MEMORY store uses the canonical PurpClaw taxonomy, imported
verbatim from Purp main (`lib/commands/memory.js`, `CANON`):

```text
Episodic · Semantic · Procedural · Symbolic · Temporal · Counterfactual · Affective
```

These are the only valid memory layer names in this codebase. `Ephemeral`,
`Working`, and `ShortTerm` are NOT memory layers — ephemeral runtime state is a
runtime-lifecycle concept (`PURPCLAW_EPHEMERAL_RUNTIME_SPEC.md`), not persistent
memory. Memory cannot promote itself into project law (Steering Resolver
authority ladder applies on-device identically).

---

## 3. Capability Truth Registry & The Anti-Mock Contract

The `CapabilityTruthRegistry` guarantees that the UI never displays fake telemetry or fabricated status.

### States:
- 🟢 **`LIVE_VERIFIED`**: Subsystem has executed real input $\rightarrow$ real output $\rightarrow$ signed Keystore receipt on physical hardware.
- 🟡 **`PRESENT_UNVERIFIED`**: Implementation class and dependencies are compiled and loaded, but awaiting first hardware run receipt.
- 🟠 **`DEGRADED`**: Engine host running with degraded capabilities (e.g. LocalModelHost running without downloaded weights).
- 🔴 **`UNAVAILABLE`**: Coordinator structures exist in codebase, but multi-agent consensus/execution is not yet calibrated on phone hardware.
- ⚪ **`NOT_INSTALLED`**: Separate capability adapter or model weights bundle is not installed.
- 🛡️ **`PERMISSION_REQUIRED`**: Subsystem requires dynamic runtime permission grant (`CAMERA`, `RECORD_AUDIO`).

---

## 4. Native Tool Runtime Engine (`ToolRuntimeEngine.kt`)

The runtime executes real Android system services and physical sensors:

| Tool Name | Android API | Real Data Extracted |
| :--- | :--- | :--- |
| `android.device.info` | `android.os.Build` | Manufacturer, Model, Board, ABIs, SDK_INT |
| `android.battery.status` | `android.os.BatteryManager` | Capacity %, Charge Counter ($\mu\text{Ah}$), Current ($\mu\text{A}$), Charging State |
| `android.storage.status` | `android.os.StatFs` | Block size, Total Blocks, Available MB, Free Workspace bytes |
| `android.network.status` | `ConnectivityManager` | WiFi/Cellular transport, Internet capability, Down/Uplink Kbps |
| `android.clipboard.read` | `ClipboardManager` | System primary clip plain text extraction |
| `android.clipboard.write` | `ClipboardManager` | Ingests text into Android system clipboard |
| `android.notification.send`| `NotificationManager` | Posts real notifications on `purpclaw_runtime_channel` |
| `android.camera.capture` | `CameraX 1.5.0` | Frame captured to app scoped files + SHA-256 evidence digest |
| `android.tts.speak` | `TextToSpeech` + `Vibrator` | Synthesizes spoken audio and triggers haptic pulse |

---

## 5. Session Mesh Coordinator (`SessionMeshCoordinator.kt`)

### Mesh State Machine:
1. **`HOME_ONLINE`**: Active WebSocket/REST connection to Home PC Rig (`http://192.168.1.144:8000`).
2. **`DEGRADED`**: Heartbeat missed ($> 3000\text{ms}$). Retrying ping sequence.
3. **`SOVEREIGN_LOCAL`**: PC disconnected. Local phone runtime assumes autonomous control.
4. **`RECONNECTING`**: Home node re-detected; initiating session shadow reconciliation.

### Reconnection & Conflict Resolution Contract:
- **Timeline Ordering**: Events carry monotonically increasing `eventId` and UTC timestamps.
- **Merge Strategy**: Both PC turns and Phone turns are preserved in the DAG.
- **Property Conflicts**: If the same canvas task or memory item was modified concurrently on both nodes during a split, an explicit `CONFLICT` object is created requiring operator review.

---

## 6. Provider Routing Engine (`ProviderRouter.kt`)

Dispatches prompt executions with automatic fallback:
1. **OpenRouter**: `https://openrouter.ai/api/v1/chat/completions` (Bearer token from Vault).
2. **MiniMax Per-Capability Adapters**:
   - **Text**: `https://api.minimax.chat/v1/text/chatcompletion_v2` (`abab6.5s-chat`)
   - **TTS**: `https://api.minimax.chat/v1/t2a_v2` (`speech-01-turbo` binary audio stream)
   - **Video**: `https://api.minimax.chat/v1/query/video_generation` (`video-01`)
   - **Music**: `https://api.minimax.chat/v1/query/music_generation` (`music-01`)
3. **LocalModelHost**: On-device NNAPI/NPU Gemma inference host.
4. **Sovereign Gemini Cloud**: Fast, authenticated cloud fallback engine.
