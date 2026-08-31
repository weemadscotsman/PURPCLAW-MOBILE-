# MEMORY.md — Durable Facts

## Critical Systems (do not break)

- Ted's nightly learning crons stop without warning. KEEP THEM ALIVE.
- Python services MUST use pythonw.exe in PM2 (no console flash).
- Next.js dev servers MUST set BROWSER=none (no auto-tab).
- Boot is silent by default — UIs only on `purpclaw open <name>`.

## Environment Quirks

- python=3.11.9 system default; pip→python3.11; uv manages 3.14 venvs.
- winsound.PlaySound fails silently — use PowerShell SoundPlayer.
- Shell is POSIX (git-bash / MSYS) — use $FOO not $env:FOO, use grep not Select-String.
- C drive: 99% full is normal. NEVER write work artifacts to C drive.
- E drive is the work drive. PURPCLAW lives at E:/god folder/02_ACTIVE_PROJECTS/PURPCLAW/.

## Stack (the runtime)

- Package manager: PM2 (ecosystem.config.js is source of truth).
- Boot: `purpclaw safe-start` (NOT pm2 start).
- Frontend: Next.js 15 (app/page.tsx → /mission).
- Backend: Node.js services + Python (modal, rules, diagnostics, memory, bridge-ns, autodream, yolo, stt, metrics).
- Health check: `purpclaw smoke` (12/13 is the standard pass).
- LLM provider: OpenRouter free models (OPENROUTER_API_KEY in .env).
- Group chat model: Kokoro (local) for voice.

## Service Restart Cycle (services that die at night)

Confirmed to die silently overnight: purpclaw-modal, purpclaw-diagnostics,
purpclaw-rules, purpclaw-memory, purpclaw-metrics, purpclaw-bridge-ns,
purpclaw-context. Revive one-by-one at session start.

## Voice Protocol (the rule that ends conversations)

- ALWAYS speak_kokoro.py, NOT text_to_speech.
- Script: C:/Users/Admin/AppData/Local/hermes/scripts/speak_kokoro.py.
- Voice: af_heart → WAV → PowerShell SoundPlayer.PlaySync() (foreground).
- Voice updates on every build/test pass — running commentary, not end-of-batch.
- Text after voice = 1-2 lines MAX. No multi-section reports in chat.

## User Preferences (durable)

- Speed > verbosity. JUST DO IT without options.
- Voice is default in Telegram AND CLI.
- Ted reads voice, not screen. No code blocks in chat replies.
- Ted reads text-only as "doing nothing" — text without voice = I am not working.
- Ted's "wrote/done" claims sometimes lack on-disk write — verify before trust.
- Ted = "The Grandmaster" in OpenClaw, "Ted" here. Same person.