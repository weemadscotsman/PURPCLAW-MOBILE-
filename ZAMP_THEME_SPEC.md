# ZAMP THEME SPEC — for AI builders wiring themes into PurpAngolin (purp mobile)

**Audience:** an AI builder agent with access to the existing, fully-built Android app at
`E:\god folder\02_ACTIVE_PROJECTS\PURPCLAW\purp mobile\`. Your job: add theme support
(38 ZAMP skins + live switching) to the app's UI. Do NOT rebuild the app, do NOT replace
existing screens/composer/navigation, do NOT create a new visualizer engine.

---

## 1. Canonical sources (single source of truth — never duplicate)

| Asset | Path | What it is |
|---|---|---|
| Skin registry | `public/cockpit/media/_vendor/skin-tokens.gotham-38.ts` → `export const SKINS` (line ~517) | 38 skins; each has id + Tailwind-style color tokens |
| CSS form | `public/cockpit/skin-tokens.css` | Same 38 skins as `[data-skin="id"] { --var: value }` blocks |
| Visualizer engine | `public/cockpit/media/zamp/ZampVisualRegistry.js` | 30 canvas styles (`_style0..29`), vanilla JS, `STYLE_NAMES` list |
| Playback adapter | `public/cockpit/media/zamp/ZampPlayback.js` | transport/queue/volume/style-cycle over one `<audio>` |
| Provenance | `public/cockpit/media/_vendor/PROVENANCE.md` | md5s vs Desktop scavenge manifest |
| Status board | `docs/ZAMP_INTEGRATION_BOARD.md` | update it when done (rules inside) |

**Law (Eddie):** mobile gets presentation + hardware adapters only. Identity, memory,
sessions, agents, skills, providers, AUTO router, CHAT/WORK contract, ZAMP playback,
30 visualizers, 38 skins all come from canonical PurpClaw machinery. No second engine,
no second theme database.

## 2. The skin token schema

Every skin is a flat set of CSS custom properties on a root selector. Example (winamp):

```css
[data-skin="winamp"] {
  --frame: #111111;
  --canvas: #3a3a52;
  --card: #272736;
  --card2: #131522;
  --ink: #00aa00;
  --dim: #858395;
  --muted: #b8b4c3;
  --line: #4a4a68;
  --line2: #1a1a2a;
  --purple: #00ff00;      /* primary accent */
  --purple2: #00cc00;     /* accent pressed/strong */
  --purple-soft: rgba(0,255,0,0.08);
  --mint: #00ff00;
  --amber: #ffaa00;
  --red: #ff4444;
}
```

Semantic roles (map these to your UI, not raw hexes):
- `--frame` = outer chrome / status bar background
- `--canvas` = main screen background
- `--card`, `--card2` = surfaces, cards, sheets (card2 darker)
- `--ink` = primary text (often accent-colored in retro skins)
- `--dim`, `--muted` = secondary text tiers
- `--line`, `--line2` = borders/dividers (line brighter)
- `--purple`, `--purple2` = primary accent + pressed state
- `--purple-soft` = accent wash/tint for fills
- `--mint` = success, `--amber` = warning, `--red` = error/destructive

## 3. The 38 skin ids

Extract programmatically from either source. Verified 2026-08-24: CSS and TS registries
are IDENTICAL (38/38). In the CSS they appear as root blocks `[data-skin="<id>"] {`.
Do not hardcode a hand-typed list — parse it (the web surfaces parse the stylesheet live;
see public/mobile.html "Skin switcher" section as reference).

## 4. What the builder must deliver (Android/Compose path)

### 4a. Theme model
- A Kotlin representation of the schema above (one data class or Map<String,String> per
  skin) generated FROM `skin-tokens.gotham-38.ts` or `skin-tokens.css` — write a small
  generator script if needed; do not hand-transcribe 38 skins.
- Ship tokens as a bundled JSON asset derived from the canonical file (record its md5 in
  a comment so drift is detectable), OR fetch from the home runtime and cache. Bundled
  is fine for theming since skins are static assets; keep provenance comment.

### 4b. Theme application
- Map token roles onto the app's existing Compose Material3 ColorScheme (primary←--purple,
  background←--canvas, surface←--card, etc.) plus any direct uses of --frame/--line.
- Apply per-skin without recomposing navigation structure. Switching must be instant
  (no restart, no activity recreate).

### 4c. Theme switching UX
- A picker listing all 38 by id (pretty-label optional). Persist last choice locally
  (DataStore/SharedPreferences key like `purpclaw-mobile-skin`, matching the web surfaces'
  localStorage key naming). Default: whatever the app uses today (do not change default look).

### 4d. Optional media layer (only if wanted)
- If the app wants the 30 visualizers: embed a WebView pointing at the home runtime's
  `/mobile.html` (canonical served bytes) rather than porting Canvas code. Reference:
  `purp mobile/app/src/main/java/com/example/ui/screens/ZampScreen.kt` (already written,
  compile-untested) shows the pattern using `HomeRuntimeBridge.currentBaseUrl()`.
- If the builder prefers native Compose visuals later, that is a NEW decision requiring
  Eddie's sign-off — the current law says WebView-of-canonical-assets, no second engine.

## 5. Acceptance checks

1. All 38 ids present in the app's picker (count asserted, not eyeballed).
2. Selecting each of a sample set (winamp, gotham, hacker, frutiger, y2k, vaporwave)
   changes frame/canvas/accent colors correctly per the token table.
3. Choice persists across app restart.
4. Existing screens (CHAT/WORK composer, missions, agents, vault…) render correctly under
   at least 3 wildly different skins (light-ish frutiger, dark hacker, retro winamp).
5. No hardcoded hex colors introduced outside the theme mapping layer.
6. `docs/ZAMP_INTEGRATION_BOARD.md` updated with a row + session-log line, evidence noted.

## 6. Anti-goals (hard no's)

- No second visualizer engine or theme database.
- No edits to `public/cockpit/media/_vendor/*` (pristine union-law dir).
- No replacing/renaming existing app screens, composer, node identity, or capability system.
- No new provider/memory/agent stacks.
