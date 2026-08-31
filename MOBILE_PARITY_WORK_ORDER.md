# PURPCLAW MOBILE PARITY WORK ORDER

**Status:** AUTHORITATIVE work order for purp mobile. Received from operator 2026-08-26.
**Law:** This orders execution priority for all mobile work until the parity statement (§59) is demonstrably true.

## PRIMARY OBJECTIVE

Finish PurpClaw Mobile / PurpAngolin to functional parity with PurpClaw Main wherever parity makes sense, while remaining a native Android execution surface.

- Do NOT redesign the existing mobile app
- Do NOT replace working runtime logic
- Do NOT fork a second architecture
- Do NOT create duplicate engines/stores/registries/routers/model systems/agent systems/memory systems or fake mobile-only implementations

One canonical PurpClaw state model. Mobile consumes registries; never hardcodes independently. Two execution surfaces of one system, truthful about capability differences.

> Anything the operator can ask PurpClaw Main to do should also be askable from Mobile. Mobile executes locally, sends to Main, uses remote inference, or reports clearly that the capability is unavailable. It must never pretend.

chat first → compact → touch first → fast → truthful → fully capable

## KEY LAWS (condensed — full text preserved in conversation record)

1. **Canonical truth** — agents/souls/councils/divisions/jobs/tools/skills/slash/providers/models/health/routing/memory/projects/files/sessions/permissions/leases/receipts/verification/capabilities/status/settings/artifacts/Podcast Studio/OpenPurp/Screen Relay all come from the same normalized registry shape (`node/targets/providers/models/capabilities/health/permissions/executionSurfaces/connectedPeers`). Every surface answers WHO AM I / WHERE AM I RUNNING / WHAT CAN I DO / WHAT MODEL SERVED THIS TURN.
2. **Execution identity** — PHONE_LOCAL / HOME_PC / REMOTE_PROVIDER / OPENPURP_RUNNER. Never blur inference with execution. Footer format `INFERENCE: X · EXECUTION: Y` extended, not discarded.
3. **CHAT/WORK law** — CHAT conversational (reason/propose/inspect safe state); WORK unlocks real execution set. No static allowlists contradicting the runtime capability registry.
4. **Chat stays alive during work** — parent chat never freezes because a worker runs. Full lifecycle: spawn→soul→provider→execute→stream→verify→return. Operator can inspect/steer/pause/resume/cancel/reroute/replace mid-flight.
5. **Agents/Souls/Divisions** — live canonical data, no hardcoded cards. Actions: spawn/assign/delegate/pause/resume/cancel/inspect/message/reroute/terminate.
6. **Councils** — REAL child agents with distinct souls/roles doing independent analysis→debate→challenge→evidence→synthesis→verdict. Not eight personas in one prompt.
7. **Live Council Podcast** — host→council→souls→model assignment→runtime-grounded dialogue→speaker ordering→TTS voice routing→live chat transcript→Podcast Studio episode with transcript/participants/voices/routing receipts/evidence/verdict. No fake "podcast complete".
8. **Model router parity** — MANUAL = exact provider+model, fail closed, `MANUAL ✗ PIN VIOLATION pinned:X served:Y`. AUTO only may switch. Pools isolated (global AUTO / OpenRouter free / NIM). MiniMax Native distinct from NVIDIA/OpenRouter MiniMax.
9. **Live catalogs** — dynamic discovery; no stale hardcoded names. OpenRouter/NIM/MiniMax Native/Gemini/DeepSeek/Kimi/Qwen/OpenAI/Ollama where configured.
10. **OpenPurp** — UI name OpenPurp, runner stays `openclaude`. Jobs via Main; stream stdout/events/tools/files/artifacts; cancel; crash detection; trace retained. OpenPurp is a runner, not the brain.
11. **Android native capabilities** — runtime-detected only; Capability Truth states AVAILABLE/DENIED/UNAVAILABLE/DEGRADED/REMOTE_ONLY.
12–16. Camera/vision, files+ZIP intake (traversal-safe, inventory, classify, provenance), Projects (canonical), Missions (full lifecycle + persistence surviving navigation/lock/restart), Child jobs (first-class live surface).
17–18. **7-layer memory** — same live system, no redesign; search/inspect/filter/provenance/delete/pin. Evidence-backed learning: `ok === true`, observation→evidence→verification→confidence→write decision.
19–20. **Repo Judge** (/judge + semantic) PASS/FAIL/FLAGGED/EMPTY_INTAKE with evidence. **TVG** on every meaningful change; "done" is not evidence.
21. **Audit & Truth** — the operator's "prove it" panel.
22. **Vault** — encrypted, local; show configured/valid/invalid/expired/unreachable without revealing secrets. Never log keys.
23–24. **Onboarding** (WELCOME→…→HATCH→ADOPT→READY, beginner key order OpenRouter→NIM→MiniMax→Kimi→DeepSeek→Qwen→OpenAI) and **Hatch & Adopt** birth certificate/adoption papers generated from actual onboarding state.
25. **Screen Relay** — eyes-only viewer for Main displays; enumerate/capture/compress/swipe/auto-cycle/pin/refresh/zoom; modes ON DEMAND/5s/10s/1s; quality LOW/BALANCED/HIGH. No remote mouse/keyboard/automation/OCR/cloud upload.
26–28. **Voice** (tap→listen→partial→final→submit→response→TTS→interrupt→resume→stop; same pipeline for CHAT+WORK), **multi-voice TTS** (voice registry, soul→voice, queue, interrupt, ducking, degraded status when backend fails — never present one voice as eight), **Podcast Studio** as real workspace (new/live/council episodes, transcript, pause/resume, save/export/history).
29–34. Tools & Mesh (real tools, filterable by ANDROID/MAIN/REMOTE/MCP/A2A/LOCAL), Skills (canonical registry), Slash commands (registry-discovered: /agent /skill /tool /instruction /workflow /goal /judge), Semantic chat convergence, Tool-call streaming regression law (args can never vanish), Long-horizon jobs (persisted trace, reconnect, bounded memory).
35–38. Session persistence (survive refresh/kill/restart/rotation; restore pin/mode/mission state; no stale running status), Network truth states (MAIN CONNECTED/RECONNECTING/OFFLINE/PHONE STANDALONE/DEGRADED), Standalone usefulness, Main-connected gains.
39–46. Settings truth parity, no UI redesign (chat-first identity stands), responsive drawers, tighter assistant typography, PurpAngolin states (canonical asset system), companions as normal widgets, icon system (semantic registry only after recrop QA — quarantined crops stay out of production).
47–49. **Capability Truth endpoint** (id/label/node/source/available/health/permission/executionTarget/reasonUnavailable/lastVerified) consumed everywhere; explicit permission states; authenticated Mobile↔Main connection, no public control endpoints.
50–55. Thin-wrapper principle (native code only at capability boundaries), offline/degraded honesty, artifacts (preview/open/share/download/send-to-Main/attach-to-chat), normalized event stream with collapsible raw traces, receipts as evidence, performance bounds.
56–59. Status page (behavioral parity only), machine-readable parity matrix, 40 named E2E tests (critical ones ×3 under TVG), §59 done-statement.

## IMPLEMENTATION ORDER (binding)

1. Canonical runtime/capability truth
2. Main ↔ Mobile connection truth
3. CHAT/WORK execution authority
4. Provider/model router parity *(free-only catalogue law already landed 2026-08-26)*
5. Session persistence
6. Tool-call streaming correctness
7. Android-native tool capability registry
8. Agent spawning / child jobs
9. steer/pause/resume/cancel/reroute
10. Missions / Auto-PLAN
11. Souls / Divisions
12. Councils
13. 7-layer memory parity
14. Repo Judge / TVG / receipts
15. Files / ZIP / projects / artifacts
16. Voice / TTS
17. multi-voice Council
18. Podcast Studio
19. OpenPurp
20. Screen Relay
21. Vault / onboarding
22. Hatch & Adopt
23. Settings truth rebuild
24. Widgets / companions
25. Icon integration after asset QA
26. Performance / long-run hardening
27. Full parity matrix
28. Final Android device E2E

## FINAL BUILD LAW

Do not build PurpClaw Main Lite. Do not build a remote control pretending to be an AI app. Do not build a disconnected Android fork.

Build **PURPCLAW MOBILE**: a sovereign Android PurpClaw node that can think remotely, act locally, command Main, supervise agents, verify work and continue the same system from the operator's pocket.

The desktop has the bigger body. The phone has different senses. It is still the same Claw.
