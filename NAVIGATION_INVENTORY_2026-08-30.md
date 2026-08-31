# PurpClaw Navigation Inventory — 2026-08-30

This is the required archaeology map for the NOSKIM navigation P0. Running code and
device proof outrank this document.

| Required item | Existing owner / action | Mobile home | Desktop home | State before this change |
|---|---|---|---|---|
| New Chat | Room `event_spine_turns.sessionId`; desktop `newMission()` | Canonical overflow → New Chat | `#newmission` | Desktop live; mobile missing |
| Chats/history | Room turns grouped by `sessionId`; desktop `/api/sessions` | Canonical overflow → Recent Chats | sidebar Recent/Pinned | Desktop live; mobile missing |
| Chat | `NavigationSurface.COMMAND` / current session | overflow + every-surface back | main stage | Live |
| Missions | `AgentTowerManager.activeMissions` | Missions | primary nav | Live |
| Library/artifacts | `CanvasManager` canvas artifacts + intake/artifact databases | Canvas & Library | Projects/Tools drawers | Partial: no dedicated union library |
| Scheduled | canonical jobs/automations owner not yet identified on Android | disabled/unavailable until owner is proven | not present in current primary nav | Missing; do not fake |
| Plugins/addons | canonical plugin registry owner not yet exposed by Android UI | Tools & Plugins (tools are live; plugin projection pending) | Tools/Skills technical nav | Partial |
| Projects/canvas | `CanvasManager` | Canvas & Library | Projects drawer | Live, different presentation |
| Agents | `AgentTowerManager` + canonical roster | Agents & Skills | Agents accordion | Live |
| Skills | canonical skill roster in Organisation screen | Agents & Skills | Skills accordion | Live |
| Council | `AgentTowerManager.councils` | Council | Dynamic Council | Live |
| Podcast Studio | `CouncilPodcastEngine` singleton | Podcast Studio | council/studio runtime | Live mobile surface |
| Memory | existing seven-layer memory gateway/store | Memory | Memory Vault | Live |
| Tools | `ToolRuntimeEngine` canonical descriptors | Tools & Plugins | Tool Library/Runner | Live |
| Providers/models | `ProviderRouter` + vault | Settings · AI & Models | Settings/backend controls | Live |
| Voice | `VoiceModeController`, rendered inside AI & Models settings | Settings · AI & Models | Settings → Voice | Live |
| Vision/intake | camera/intake actions in composer + ToolRuntime | composer + Tools | composer/tools | Live/partial by permission |
| Runtime/system/audit | capability registry, receipts, vault | Audit & Vault | System/Settings | Live |
| Settings | `AiModelsSettingsScreen` and `SettingsRegistry` | Settings · AI & Models | System → Settings | Was hidden from Chat; now exposed |
| Media player | canonical ZAMP state | not yet in mobile nav sheet | fixed desktop sidebar bottom zone | Desktop live; mobile parity pending |
| Profile/identity | canonical identity state | not yet in mobile nav sheet | fixed desktop sidebar bottom zone | Desktop live; mobile parity pending |

## Current mutation

- Mobile navigation now opens from a professional Menu icon in the Chat composer.
- The existing bottom sheet owns New Chat, Recent Chats, every already-real mobile
  surface, mode and companion selection.
- New Chat and history switch the existing `_activeSessionId`; they do not create a
  second store or copy turns.
- Active session ID persists through the existing `purpclaw_state` preferences.
- Scheduled, the dedicated union Library, plugin registry projection, media and profile
  remain explicitly partial/missing until their canonical owners are wired.

## Remaining shared-definition gate

Desktop and Android still encode presentation lists separately. Before claiming the
cross-surface navigation P0 complete, extract a single surface-neutral navigation
manifest/registry and make both adapters consume it, with tests that every real item is
represented exactly once or has a documented alias.
