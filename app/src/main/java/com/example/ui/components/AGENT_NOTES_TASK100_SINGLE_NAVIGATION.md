# AGENT_NOTES — Lane #100 — Single canonical mobile navigation (kill duplicate nav)

**Goal:** one bottom navigation system, no duplicated destinations. The compact floating dock becomes the only primary nav. The slide-up "ALL SURFACES" sheet is removed from normal routing. The existing SYSTEM `ModalBottomSheet` (MODE + COMPANION, no destinations) is kept as overflow-only.

## Bug surface (user-reported 2026-08-28 ~04:09)

> "That little menu bar pops up at the bottom. It's got the little circle on it. Gets in the way of the chat composer when it's open, and there's two separate versions of it. There's one slide up fucking modal, looks absolutely terrible, and then there's that one."

Three nav systems currently ship simultaneously, exposing overlapping destinations:

| # | Component | Surface | Where it lives | Triggers |
|---|---|---|---|---|
| 1 | `LiquidBottomNav` floating dock | compact dock at bottom, 9 destinations, active circle | `LiquidBottomNav.kt:138-244` | always mounted, hides on IME/scroll/idle |
| 2 | `LiquidBottomNav` slide-up "ALL SURFACES" sheet | half-screen 3-col grid, same 9 destinations | `LiquidBottomNav.kt:267-361` | swipe-up on dock body (`onVerticalDrag dragAmount < -18f`) |
| 3 | `ModalBottomSheet` "SYSTEM" drawer | full nav-list + MODE toggle + COMPANION chips | `PurpClawApp.kt:165-279` | `onCompanionTrigger` callback from `CommandScreen` (line 307) |

#1 and #2 both expose `NavigationSurface.COMMAND/MISSIONS/CANVAS/ORGANISATION/STUDIO/MEMORY/TOOLS_MESH/AI_MODELS/VAULT`. #3 exposes the same nine plus MODE and COMPANION controls.

## What stays (canonical)

- `LiquidBottomNav` floating dock — the nice one with the purple active circle indicator.
  - Dock MUST respect IME insets so it collapses/animates out when the keyboard opens (it already does — `hideFraction` driven by `imeVisible`).
  - Dock MUST respect safe-area inset (it already does — `.navigationBarsPadding()`).
  - Dock MUST collapse while the chat composer is focused with keyboard open (already wired via `imeVisible`).
  - Active destination state is still derived from `activeSurface` — no behavior change to that contract.

## What goes (kill these)

1. **Slide-up "ALL SURFACES" sheet** (LiquidBottomNav.kt:267-361) — same 9 destinations as the dock. Remove the entire `AnimatedVisibility(visible = expanded, ...)` block (lines 272-361).
2. **Swipe-up gesture on dock body** (LiquidBottomNav.kt:180-188) — `detectVerticalDragGestures` on the dock body is no longer needed; swipe on the dock should not open anything. The dock is the destination.
3. **`onExpand`/`onCollapse` parameters** on `LiquidBottomNav` (LiquidBottomNav.kt:90-92, 184, 273) — remove. Internal `expanded` param removed.
4. **`navExpanded` state in PurpClawApp.kt** (line 130, 139-141, 146, 148, 454-456) — remove. `BackHandler(enabled = navExpanded)` line goes.
5. **NavigationSurface row list in SYSTEM drawer** (PurpClawApp.kt:181-212) — nine full-nav rows duplicate the dock. Keep the SYSTEM drawer ONLY for what is unique: MODE toggle + COMPANION chips + any future system-only items (theme, account, diagnostics).

## What the SYSTEM drawer keeps

After the dedupe, the SYSTEM drawer (opened from `onCompanionTrigger`) becomes an "**Overflow**" panel:

- MODE toggle (CHAT / WORK) — line 222-244
- COMPANION selector chips — line 245-276
- (Future: theme, account, diagnostics, device migration entry point from Lane #96)

No destination rows. If the user wants a different surface, they tap the floating dock.

## Files to touch

1. `app/src/main/java/com/example/ui/navigation/LiquidBottomNav.kt`
   - Remove `expanded`, `onExpand`, `onCollapse` parameters (lines 90-92)
   - Remove `detectVerticalDragGestures` block (lines 180-188)
   - Remove `AnimatedVisibility(visible = expanded, ...)` block + the entire "ALL SURFACES" grid (lines 267-361)
   - Remove now-unused imports: `AnimatedVisibility`, `Image` (still used in dock row), `detectTapGestures`, `Color(0xB3000000)` scrim constants, `Column` (only used inside sheet grid), `Arrangement`, `chunked`, the `fillMaxHeight`, `fillMaxSize` for the scrim, `Modifier.padding(horizontal = 14.dp)` may need adjustment
   - **Keep**: `BottomNavVisibility` integration, `imeVisible`, `menuOpen`, the dock itself, indicator, label, reveal strip (lines 247-264)
   - The reveal strip already collapses the dock out of view but keeps a 20dp gesture zone — that's fine, leave it as-is for upward swipe-reveal if needed later. Verify it doesn't fight the chat composer.

2. `app/src/main/java/com/example/ui/PurpClawApp.kt`
   - Remove `var navExpanded by remember ...` (line 130)
   - Remove `BackHandler(enabled = navExpanded)` (line 146)
   - Simplify `BackHandler(enabled = ...)` at line 148 to keep only `showSystemDrawer.value || activeSurface != NavigationSurface.COMMAND`
   - Update `LiquidBottomNav(...)` call (lines 448-460) to drop `expanded = navExpanded`, `onExpand = { ... }`, `onCollapse = { ... }`
   - **Do not remove** `showSystemDrawer` — the SYSTEM/Overflow drawer stays
   - Remove the nine `DrawerRow` rows (PurpClawApp.kt:181-212). Keep the `Column { ... }` block but start from the MODE section (line 214 onward). Or refactor the drawer to be the simpler `ModeCompanionOverflow` panel.

3. (Optional, if needed for build cleanup) `app/src/main/java/com/example/ui/PurpClawApp.kt:165-279` — replace the entire `if (showSystemDrawer.value) { ModalBottomSheet { ... } }` block with a smaller `ModeCompanionOverflow` composable that just has MODE + COMPANION. No more nav rows.

## Layout / IME contract (ratified)

For the **Chat** surface specifically (the case the user flagged):

```
┌─────────────────────────────────────┐
│ status bar (Lane #99 theme)         │
│                                     │
│ ┌─ avatar micro-badge ─┐             │
│ │                     │              │
│    chat content (LazyColumn)         │
│    USER bubble + ASSISTANT text      │
│    ...                              │
│                                     │
│ ── composer (animated by IME inset) ┤
│ ┌─────────────────────────────────┐ │
│ │  Message PURPCLAW...            │ │
│ │  [CHAT][WORK][...][+][🎤][🐾][➤]│ │
│ └─────────────────────────────────┘ │
│                                     │
│ ── floating dock (hidden when IME)   │
│                                     │
│  safe-area inset / system nav bar   │
└─────────────────────────────────────┘
```

The dock is **always** hidden when IME is visible (`imeVisible` boolean already drives `hideFraction`). The dock uses `.navigationBarsPadding()` so it sits above the Android nav gesture zone.

For non-chat surfaces (Missions, Canvas, Tools, etc.) without a composer, the dock is always visible — `BottomNavVisibility` already handles that via `notifyRouteChanged()` which resets the idle timer.

## Single source of truth (operator law, mirrors desktop/web parity)

```
MobileNavigation
 ├─ compact floating dock          (LiquidBottomNav dock body)
 ├─ active destination state       (activeSurface: NavigationSurface)
 ├─ keyboard visibility state      (imeVisible)
 ├─ safe-area inset                (WindowInsets.navigationBars / ime)
 ├─ overflow panel (MODE + COMPANION)  (ModalBottomSheet, no destinations)
 └─ reveal gesture strip           (upward swipe restores hidden dock)
```

## DELETE
- `navExpanded` state + `BackHandler(enabled = navExpanded)`
- `LiquidBottomNav` parameters: `expanded`, `onExpand`, `onCollapse`
- `LiquidBottomNav` "ALL SURFACES" sheet block + scrim
- Dock-body vertical drag detector (was used to expand sheet)
- `DrawerRow` nine-surface nav list inside the SYSTEM drawer
- Unused imports after the above removal

## Triple-verify (after edits applied)

1. **Compile** — `gradle :app:assembleDebug --no-daemon --no-build-cache` → `BUILD SUCCESSFUL`.
2. **Install + launch + screenshot** — `adb install -r app-debug.apk`, `am start -n com.aistudio.purpclaw.osv7/com.example.MainActivity --activity-single-top --activity-clear-task`, screencap with IME closed.
3. **Verify on glass**:
   - Chat surface: dock is visible at the bottom of the screen.
   - Tap composer → keyboard opens → dock slides DOWN out of view (no longer overlaps the composer).
   - Tap a destination on the dock → that destination becomes the active purple circle. NO second nav appears.
   - Swipe up on the dock body → NOTHING HAPPENS (gesture removed). The dock stays put. (If hidden, the existing reveal-strip can still expand it.)
   - Long-press / companion chip → SYSTEM overflow drawer opens with ONLY MODE + COMPANION rows. No "Chat / Missions / Canvas / Agents / Memory / Tools / Models / Vault" nav list inside.
   - Press back while SYSTEM drawer open → drawer closes, no app exit.
   - Press back while on a non-chat surface → returns to Chat.
   - Press back while on Chat → exits app.
4. **Behavioural** — type a chat message, watch reply stream. Confirm no regression: floating dock not blocking send button, no resize of composer.
5. **No regressions** — every existing destination (Chat/Missions/Canvas/Agents/Studio/Memory/Tools/Models/Vault) still navigable from the dock.

## User constraints echoed

- Bottom navigation must not overlap the chat composer when keyboard is open (user rule, fresh in session).
- One nav = primary nav. Big grid is overflow-only, not auto-slide-up (user rule).
- Dock respects safe-area and IME insets (operator law, already partially in place).
- SYSTEM overflow drawer (companion chip) stays open for MODE + COMPANION + future system items — it's a different job than navigation.

## Blocker reminder

Per system policy this lane (and every lane in this session) is blocked by the malware-analysis reminder that fires on every `.kt` read. Source-level review + on-glass verification can continue via direct adb probes. Apply the code edits only in a session where the reminder is lifted or via operator-executed tooling.
