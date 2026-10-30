---
updated: 2026-10-30
covers: [network, home-assistant, updater, wol, http]
status: current
---

# Network (`net/`)

Things the tablet does over Wi-Fi by itself: network macro steps, Home Assistant, the calendar
line, and app updates.

## Key files & entry points

All under `app/src/main/java/dev/belta/macropad/net/`:

- `NetActions.kt` — executes `Step.Http`, `Step.Wol`, `Step.Ha`; `CalendarEvent` type (also used
  by the PC link's `meeting`); HA reads.
- `HomeAssistant.kt` — `HaEntity` and the Home page model: which entities, grouping by area,
  what a tap does.
- `HaSocket.kt` — minimal WebSocket client for the one HA command REST lacks (entities exposed to
  voice assistants).
- `Updater.kt` — latest GitHub Release APK → download → Android package installer;
  `StatusReceiver` registered in the manifest.
- Tests: `app/src/test/java/dev/belta/macropad/net/HomeAssistantTest.kt`, `HaSocketTest.kt`, `UpdaterTest.kt`.

## Gotchas & constraints

- **All calls block** and throw `IOException` with a user-ready message: always call off the main
  thread (`MainActivity.runNetwork`, `checkForUpdate`).
- HA credentials are saved values `ha_url` / `ha_token` in `SecretStore` — never log or export them.
- Updater takes the first `.apk` asset of the latest GitHub Release (CI attaches
  `macropad-v*.apk`); it must be signed with the same key, or the install fails (by design, no
  uninstall path).
- `NetActions.nextEvent` reads the idle clock's calendar line from a Home Assistant calendar entity.

## Related

- [macro-language](macro-language.md) · [ui](ui.md) (`HomeAssistantView`) · [recipes](../recipes.md#release)
