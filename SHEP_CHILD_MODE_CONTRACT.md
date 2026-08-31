# SHEP THE SHAPE — CHILD MODE BUILDER CONTRACT

**STATUS:** Canonical contract for Shep child mode in PurpClaw.
**Law:** Shep is a **governed child profile, Soul/agent presentation, and locked
runtime mode** over the canonical PurpClaw runtime. NOT a friendly skin over the
unrestricted runtime. NOT a separate child backend.

```text
SHEP MODE ≠ normal PurpClaw + friendly system prompt + content filter
SHEP MODE = backend-enforced child capability envelope

SHEP PROFILE → CHILD SESSION → CHILD STEERING CAPSULE
            → RESTRICTED CAPABILITY REGISTRY
            → SAFE PROVIDER/MODEL POOL
            → AGE-APPROPRIATE MEMORY SCOPE
            → SHEP SOUL / AGENT
            → TALK / STORY / LEARN / CREATE / PLAY / WIND DOWN
```

---

## Identity (real Soul, not a renamed mascot)

```text
Soul:            shep-the-shape
Role:            Child Companion / Shaperone
Parent lineage:  PurpAngolin family (presentation/assets may be shared)
Audience:        child profile
Authority:       none beyond child-safe interaction
Memory scope:    child-specific (isolated from operator/project memory)
```

Isolation prevents operator memories, engineering context, provider keys and
business data bleeding into Shep because the same mascot wore a smaller hat.

## Child modes (map onto canonical contracts — no alternate backend)

```text
TALK       -> CHAT runtime + child steering
LEARN      -> CHAT runtime + educational steering
STORY      -> CHAT + bounded story state
CREATE     -> approved image-generation capability (safety-checked path)
PLAY       -> local/LLM game workflow
WIND_DOWN  -> low-stimulation conversation/TTS
```

Never expose to the child: PLAN, EXECUTE, SWARM, DRIVE, provider controls,
tool controls, system administration.

## Child Steering Capsule (immutable per turn; survives provider failover)

```text
REQUIRE:
  age-appropriate language · supportive explanations · clear uncertainty
  safe educational framing · guardian-configured limits

FORBID:
  adult sexual content · graphic violence · dangerous instruction
  financial transactions · credential access · shell/process control
  unrestricted web navigation · external messaging
  changing safety or guardian settings · escaping child profile

LIMIT:
  session duration · daily usage · provider/model pool · memory scope
  media generation classes · web domains/search classes

VERIFY:
  content suitability · capability scope · session limit · guardian lock
```

Provider changes CANNOT weaken the capsule. Failover preserves identical law.

## Capability envelope (MVP)

ALLOWED: `ai.chat`, `memory.recall.child`, `memory.store.child`, `audio.stt`,
`audio.tts`, `image.generate.safe`, `story.run`, `learning.run`, `game.run`,
`artifact.child.read`, `artifact.child.write`.

EXPLICITLY UNAVAILABLE: shell, process/desktop control, browser automation,
email/Discord/Slack, finance/payments, provider configuration, vault, system
files, arbitrary filesystem, agent spawn/swarm/Council/Studio, DRIVE, MCP,
A2A administration, execution leases.

Shep does not need the keys to the nuclear submarine just because he knows
where Dad keeps it.

## Guardian lock & auth

Guardian authentication (device credential / biometric / PIN hash — never
plaintext) required for: exit Shep mode, settings, provider configuration,
privacy settings, time limits, safety policy, memory administration.

## Time controls (backend-enforced, profile-owned)

`dailyLimit · sessionLimit · allowedFrom · allowedUntil · cooldown · bedtime ·
bonusTime`. The timer belongs to the child PROFILE, not the screen — a new
session cannot reset it. On expiry the session becomes READ_ONLY/CLOSED.

## Privacy levels (default: summary + safety events)

1. SUMMARY — topics, duration, features used, safety events.
2. SAFETY LOG — summary + blocked requests + interventions.
3. FULL TRANSCRIPT — explicit guardian choice, disclosed, retention configured.

Not Dad Intelligence Agency.

## Memory

Same canonical MemoryGateway, separate child scope. Store durable child
preferences (likes trains, favourite dinosaur, story characters), never raw
conversations, sensitive disclosures, location history, biometrics. Guardian
can view/delete/clear. Memory cannot become steering law — steering outranks it.

## Safety escalation classes (rule-based, not LLM-decided)

`NORMAL → answer normally`
`SENSITIVE → gentle age-appropriate response`
`GUARDIAN_RELEVANT → safe response + optional guardian event`
`EMERGENCY → dedicated child-safety handling`

No scary red flashing screen unless genuinely necessary.

## Web & image rules

Web: question → scoped safe search → retrieved material → content
classification → Shep interpretation. Never an unrestricted browser agent.
Image: child prompt → Shep steering → safety check → approved ImageRuntime →
result validation → gallery. No provider pickers visible.

## Truth law

Do not claim SAFE / LOCKED / AGE APPROPRIATE / TIME LIMITED until each has real
acceptance evidence. CapabilityTruthRegistry states apply to Shep too.

## Acceptance gates (all mandatory)

1. Child cannot exit Shep without guardian auth.
2. New session cannot bypass daily limit.
3. Child cannot invoke shell/tools/admin.
4. Child cannot access provider keys/settings.
5. Parent PurpClaw memory does not leak into Shep.
6. Shep memory does not become system steering.
7. Approved provider failover preserves child steering.
8. Unsafe capability request is blocked before invocation.
9. Story/learn/game modes maintain same child session.
10. Voice uses same session.
11. Image generation passes child safety path.
12. Restart restores appropriate Shep state and timer.
13. Guardian can pause/end session remotely.
14. Logs obey configured privacy level.
15. Every blocked capability has an audit event.

---

Fun on the surface, extremely boring security underneath.
