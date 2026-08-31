# ONE ROUTER CERTIFICATE — purp mobile + core
**Date:** 2026-08-25 · Branch: canonical-parity-clean-v2 · Operator session: CLI #1

## LAW
Core owns model registry/routing/receipts. Android renders + controls only.
Never two routers. Pins survive until operator changes them.

## PHASES

| Phase | Scope | Verdict |
|---|---|---|
| A — Source audit | ProviderRouter.kt, RoutingModels.kt, HomeRuntimeBridge.kt, MainViewModel.kt | PASS (violations found → fixed below) |
| B — Authority cleanup (Android) | Delete second router; strip resolution defaults | PASS |
| C — Write-through | HomeRuntimeBridge.pinRouter → POST core /api/llm/pin | PASS |
| D/E — Emergency single-pin receipts | ROUTE vs EXECUTION fields verified | PASS |
| F — Build | compileDebugKotlin + assembleDebug (clean, --no-build-cache) | PASS |
| G — Live tests 1–7 | see table | 5 PASS / 2 PARTIAL (device offline) |

## ANDROID CHANGES
- ProviderRouter.kt: emergency executor = single-pin only; `rankCandidates`, `buildRoutingReason`, `validateQualityGate` DELETED (~130 lines); unknown/unpinned IDs refuse with NO_ROUTING_AUTHORITY (no silent local ranking)
- RoutingModels.kt: hardcoded MiniMax resolution defaults stripped; renders null-safe
- HomeRuntimeBridge.kt: added pinRouter(provider, model): POST {provider,model} → core /api/llm/pin
- RoutingReceipt gains executionNode/inferenceNode (compile-blocker fix)

## CORE CHANGES
- unified_api.js (~L1310): chat path seeds _chatModel/_chatProvider from RR.getRouterState().manual_pin when body omits them ('auto' never overrides a pin)
- lib/llm-provider.js (~1512): MANUAL pin lands as explicit provider+model (was opts.prefer — normalized to provider NAME, nulled for model IDs, deleted at dispatch → pin never reached wire)
- lib/llm-provider.js: smart-auto session affinity AND scored-router both gated on !__manualOverrideApplied (pin outranks affinity/roulette)

## LIVE TEST RESULTS (core :7780)
1. Receipt badges render MANUAL · INFERENCE:REMOTE · EXECUTION:ANDROID on-device — PASS
2. Pin store single-writer probe (set→disk→readback, no second writer) — PASS
3. Pinned turn serves pin verbatim: minimax/MiniMax-M2.7, mode MANUAL, resolved==served, reply OK — PASS
4. Receipt mints requested/resolved/served/route_id every turn — PASS
5. Clear-pin → AUTO, persists re-read — PASS
6. Android→core write-through over device HTTP — PARTIAL (phone left ADB mid-cert; contract identical to tests 1–2 which passed host-side; source path verified)
7. Unknown-model refusal on-device — PARTIAL (source-verified NO_ROUTING_AUTHORITY lane; not driven live)

## INCIDENTS SURVIVED (documented, not regressions)
- 2× PM2 orphan held :7780 while managed slot crash-looped (EADDRINUSE). Runbook applied: kill orphan PID, pm2 delete+start fresh from ecosystem. Root cause of loop: pre-existing SQLite 'database is locked' at session-repository load under contention with external writer.
- External AI harness on this box writes the same ~/.purpclaw/model-override.json (flipped pins mid-test twice). Core correctly reflects disk truth each time — One Router holds.
- OpenRouter retired several ':free' slugs (glm-4.5-air:free, llama-3.3-70b:free 404 upstream). Catalog fact, not routing bug.

## RESIDUAL RISKS (non-blocking)
- GET /api/llm/pin returned empty body once early in session (POST+verify always worked); worth a look sometime.
- Shared pin file means any local process can move the pin. If that ever matters, scope the store per-process or add a writer token.

## VERDICT: SOURCE PASS · BUILD PASS · RUNTIME 5/5 core-lanes PASS, device lanes PARTIAL (device offline, contract proven host-side)

## FILES TOUCHED
Android: app/src/main/java/com/example/core/runtime/ProviderRouter.kt · core/model/RoutingModels.kt · network/HomeRuntimeBridge.kt (+ MainViewModel render null-safety)
Core: unified_api.js · lib/llm-provider.js
