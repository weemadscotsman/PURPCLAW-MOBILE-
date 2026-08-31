# PurpClaw Integration Checklist & Backend Setup Guide

## 1. TEST-ONLY SESSION MESH FIXTURE (NOT canonical PurpClaw)

> **STATUS: TEST FIXTURE.** The server below is an isolated protocol test
> fixture for exercising Slice 001 wire behaviour (WebSocket framing, heartbeat,
> disconnect detection, split-brain merge). It is **NOT** the Home PurpClaw
> runtime and must never be presented as such.
>
> **PRODUCTION LAW:** Android attaches to the canonical PurpClaw main runtime
> and its existing session/A2A/event-spine infrastructure. Running a second
> "pretend-PurpClaw" service alongside Purp main would recreate the exact
> duplicate-system problem this constitution exists to kill. Production Slice
> 001 acceptance requires the canonical runtime as the home node — this fixture
> is for protocol development only.

To satisfy **Slice 001 (Home-First Session Mesh)** *protocol tests*, run this lightweight WebSocket / HTTP server on your Home PC. Note the `/health` payload below is **hardcoded demo data** (`HOME-PC-RIG-01`, static load/uptime) — acceptable only because this is a fixture; real health data comes from the canonical runtime.

### Option A: Python Fast-API Mesh Server (`server.py`)
```python
import asyncio
import json
import time
from fastapi import FastAPI, WebSocket, WebSocketDisconnect
import uvicorn

app = FastAPI(title="PurpClaw Home Mesh Node")

connected_clients = set()
canonical_timeline = []

@app.get("/health")
async def health():
    return {
        "nodeId": "HOME-PC-RIG-01",
        "status": "ONLINE",
        "workstationLoad": "14%",
        "uptimeSeconds": 142050,
        "timestamp": int(time.time() * 1000)
    }

@app.websocket("/mesh/stream")
async def websocket_endpoint(websocket: WebSocket):
    await websocket.accept()
    connected_clients.add(websocket)
    print("[MESH] Phone connected to Home Mesh WebSocket")
    try:
        while True:
            data = await websocket.receive_text()
            message = json.loads(data)
            print(f"[TURN] Received from {message.get('nodeId')}: {message.get('content')}")
            
            # Broadcast turn to all connected clients
            canonical_timeline.append(message)
            for client in list(connected_clients):
                if client != websocket:
                    await client.send_text(json.dumps(message))
    except WebSocketDisconnect:
        connected_clients.remove(websocket)
        print("[MESH] Phone disconnected from Home Mesh")

if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8000)
```

Run on PC:
```bash
pip install fastapi uvicorn websockets
python server.py
```

---

## 2. API Key Ingestion — CREDENTIAL LAW

**Two environments, two laws. Do not mix them.**

### HOME DEV ENVIRONMENT (workstation)
`.env` injection is permitted **only** as an adapter input to Purp main's
existing secure provider bootstrap on the workstation. This is a development
convenience, not the device contract.

### ANDROID DEVICE VAULT (the only path on-device)
```text
NO plaintext .env credential storage on Android. Ever.
```
Keys enter exclusively through:
```text
Settings → Provider → KeyStoreVault
```
and are sealed with AES-GCM hardware keys (`KeyStoreVault.kt`). Keys must leave
Compose/ViewModel state after ingestion — never retained in UI memory.

### Keys Supported:
1. `OPENROUTER_API_KEY`: Enables routing to 100+ models (Claude 3.5 Sonnet, Llama 3.3 70B, DeepSeek R1).
2. `MINIMAX_API_KEY`: Enables MiniMax `abab6.5s-chat`, `t2a_v2` voice, and `video-01` adapters.
3. `GEMINI_API_KEY`: Cloud fallback provider (pre-configured in container environment).

---

## 3. On-Device Gemma / Local LLM Weights Setup

To transition `LocalModelHost` from `DEGRADED` to `LIVE_VERIFIED`:

1. Download quantized model weights:
   - `gemma-2b-it-cpu-int4.bin` or `gemma-2b-it-gpu-int4.bin`
2. Push to the Android device's app-scoped files directory:
   ```bash
   adb push gemma-2b-it-cpu-int4.bin /data/data/com.example/files/models/
   ```
3. In PurpClaw UI: Select **Local Host (On-Device)** in provider picker.

---

## 4. Hardware Permission Grant Matrix

Ensure the following permissions are granted on the physical Android device:

| Permission | Purpose | ADB Quick Grant Command |
| :--- | :--- | :--- |
| `CAMERA` | CameraX optical frame capture | `adb shell pm grant com.example android.permission.CAMERA` |
| `RECORD_AUDIO` | Live microphone speech recognition (STT) | `adb shell pm grant com.example android.permission.RECORD_AUDIO` |
| `POST_NOTIFICATIONS` | Background agent status notifications | `adb shell pm grant com.example android.permission.POST_NOTIFICATIONS` |

---

## 5. Integration Task Matrix & Status

- [x] **Room Database Schema v7**: Multi-agent memory layers, leases, receipts, snapshots, and canvas DAG entities deployed.
- [x] **Android Keystore EC-P256 Proof Signer**: Hardware key generation and payload signing active.
- [x] **KeyStoreVault AES-GCM**: Hardware symmetric encryption for API secrets.
- [x] **Anti-Mock Capability Truth Registry**: Dynamic states (`LIVE_VERIFIED`, `PRESENT_UNVERIFIED`, `DEGRADED`, `UNAVAILABLE`, `NOT_INSTALLED`, `PERMISSION_REQUIRED`).
- [x] **Native Android Tools Engine**: Battery, storage, network, clipboard, notification, CameraX, and TTS.
- [x] **Home-First Session Mesh Coordinator**: WebSocket / REST client with auto-failover to sovereign local execution.
- [x] **Multi-Provider Fallback Router**: OpenRouter, MiniMax capability adapters, Local Host, and Gemini Cloud.
- [x] **UI Truth Enforcement**: Banners on UNAVAILABLE / NOT_INSTALLED modules (Swarm, Council, Studio) to prevent mock illusions.
- [ ] **Physical Hardware Slice 001 Test Run**: Run PC server, connect phone over LAN, perform disconnect & split-brain test.
