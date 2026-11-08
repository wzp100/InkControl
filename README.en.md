<p align="center"><img src="docs/brand/banner.svg" alt="InkControl — Hisense A5 Pro 经典版" width="100%"></p>

<p align="center"><a href="README.md">简体中文</a> · <a href="docs/wiki/README.en.md"><b>Docs</b></a> · <a href="docs/wiki/agent-handbook.en.md">Start here, agents</a> · <a href="https://github.com/liuqianhe/InkControl/releases">Downloads</a></p>

# More control over your E-ink display

**InkControl · 墨控** brings display algorithms, transitions, lock-screen controls, and stock-launcher themes to the **Hisense A5 Pro 经典版 (Classic Edition)** with its **monochrome E-ink screen**. One app contains its matching Magisk backend.

**All 91 enhancements ship off by default** (except the four lock-screen device-status switches and the E-Ink panel's pin-screen size alignment, which are on). Enable what you need, retain your selections, and see configuration intent separately from runtime acknowledgement.

| Display | Motion | Lock screen | Stock launcher | System |
| --- | --- | --- | --- | --- |
| Per-mode algorithms; separate video settings | Effects, previews, temperature timing curves | Weather, lyrics, notification count, four status items, six styles | Three themes; independent icons, wallpaper, widgets; long-press lists up to four shortcuts | Quick-settings tiles, white navigation bar, system-settings snapshots, system-app removal |
| Grayscale and binary groups; independent hysteresis | Existing display path and mode boundaries | Individual controls and conditional restoration; notifications and device status are mutually exclusive; Pixel uses a white background and an up-swipe hint; no stock bottom-bar overlay | Original layout and app positions retained | Every item is an independent switch; turning it off restores stock behaviour |

> **Test devices only recommended: back up important data to a computer or independent storage first. Boot failure, permanent data loss, and unrecoverable device damage are possible. Read [risks and disclaimer](#risks-and-disclaimer) before installation.**

## Choose your starting point

| I want to… | Read |
| --- | --- |
| Install and get started | [Getting started](docs/wiki/getting-started.en.md) |
| Understand modes, video, and hysteresis | [Display algorithms](docs/wiki/algorithms.en.md) |
| Diagnose slow refresh or inactive settings | [Operations and recovery](docs/wiki/operations.en.md) |
| Continue development and understand prior work | [Agent handbook](docs/wiki/agent-handbook.en.md) → [Research](docs/wiki/research.en.md) |
| Locate code, evidence, and release automation | [Architecture](docs/wiki/architecture.en.md) · [Evidence](docs/wiki/evidence.en.md) · [Build and release](docs/wiki/build-and-release.en.md) |

## Getting started

1. Download a signed APK from [Releases](https://github.com/liuqianhe/InkControl/releases). Read its notes and checksums. Suffixes such as `-rc16` / `-r19` are candidate rounds; the higher the number, the newer the build.
2. Open **Install**, check compatibility, export the bundled backend ZIP, and install it through Magisk.
3. Enable InkControl in a Vector/LSPosed-compatible framework for System Framework, System UI, and the stock launcher.
4. Restart fully, check **Diagnostics**, then enable individual enhancements.

The backend checks `HLTE203T / Android 10 / arm64` plus the firmware fingerprint and exact vendor-input hashes (the fingerprint string reads `L1732.6.01.02`, which differs in prefix from `ro.software.version`; both denote the same build). Without Root you can browse guides, switch languages, preview effects, and export the installer. See [installation and recovery](docs/wiki/getting-started.en.md).

> **Verified device and system:** The only model accepted on physical hardware so far is the **Hisense A5 Pro 经典版 `HLTE203T`**, Android 10 (API 29), `arm64-v8a`, system firmware **`L1732.6.14.01.B10`** (`ro.software.version`; `ro.build.display.id` is `QP1A.190711.020`, and `ro.build.fingerprint` still reads `L1732.6.01.02`). InkControl depends on that firmware's stock launcher (`com.hmct.vision`), stock theme, and display-backend internals, so **other models, other system versions, or modified ROMs are likely incompatible** and are not guaranteed to work or be recoverable. Before changing devices or upgrading the system, review the risks below and back up first.

> **Signing migration:** rc13 introduced a fixed new release identity. Back up configuration and uninstall the old signed app before migrating to the new app and matching backend. Subsequent updates retain that identity.

## A look inside

<table><tr><th>Home</th><th>Module settings</th><th>Launcher shortcuts</th></tr><tr><td><img src="docs/media/ui-home.png" alt="Home page: readiness chip, 25 of 74 enabled, per-category counts" width="260"></td><td><img src="docs/media/ui-settings.png" alt="Module settings: install, diagnostics, feature ordering, launcher shortcuts, lab, glossary, language and toast duration" width="260"></td><td><img src="docs/media/ui-shortcuts.png" alt="Launcher shortcut page: the four entries and their order" width="260"></td></tr></table>

**Physical-device screenshots** of 0.2.2-r19 on `HLTE203T` (720×1440 framebuffer, scaled to 360×720). The home page shows the current readiness state and an "enabled / total" line; the middle page is **Module settings** (install wizard, diagnostics, feature ordering, launcher shortcuts, lab, glossary, plus language and toast duration further down the same list); the last page is **Module settings → Launcher shortcuts**, the order in which long-pressing the launcher icon lists up to four features. Captures show layout only — they do not establish panel grayscale or ghosting quality. English UI translation is no longer maintained: the app may still show Chinese text in places, as visible above.

<table><tr><th>E-Ink panel</th><th>Panel buttons</th></tr><tr><td><img src="docs/media/ui-panel.png" alt="E-Ink panel: brightness, current display mode, and a bottom row of four buttons (Wi-Fi / Bluetooth / auto refresh / cover to sleep)" width="300"></td><td><img src="docs/media/ui-panel-buttons.png" alt="Panel buttons page: master switch, four slots with Change and left/right arrows, and restore-default-order" width="230"></td></tr></table>

The two captures above are from **0.2.5-r23** on the same device. On the left is the **E-Ink panel** itself — the System UI overlay that rises from the bottom edge and covers the lower half, with brightness and the current display mode on top and a row of four slots at the bottom that InkControl can now take over (shown here as Wi-Fi, Bluetooth, auto refresh, cover to sleep); a tap toggles the action in place and the prompt appears at the **bottom** of the screen. The panel is translucent, so the launcher icons show through. On the right is the **panel buttons** page, reached directly from **System enhancements → Panel modification**: master switch, four "index · action" rows each with **Change** and **← / →**, **Restore default order**, and the in-page switches for "close the panel after a tap" and "match the pin-note size".

## Project status

The source targets **0.2.6-r26**, with per-mode algorithms, independent hysteresis, auto refresh (tap/key/reading counters plus periodic triggering), lock-screen notification counts (system clearable notifications by default, or WeChat unread), six custom lock-screen styles (Editorial, Swiss grid, Pixel, Editorial landscape, Editorial landscape reversed, Liquid glass), a lock-screen device-status strip (Wi-Fi / Bluetooth / sound mode / airplane, mutually exclusive with notifications), quick-settings tiles for third-party apps, a white navigation bar, a six-style phased page-turn animation (style, speed and band count are mandatory options without a toggle; the reader list ships with Legado pre-selected; mutually exclusive with the GPU display backend), system-settings snapshots replayed row by row after a factory reset, system-app removal and restore, four customizable buttons in the bottom row of the E-Ink refresh panel (a tap toggles the action and prompts use the default bottom position, a long press on Wi-Fi / Bluetooth / the InkControl toggles jumps to the matching settings page), up to four launcher long-press shortcuts, a bilingual UI (English translation is no longer maintained), a shared visual identity, and a versioned knowledge base. The repository ships a GitHub Actions workflow (`.github/workflows/build.yml`), but both Releases so far were built and signed locally with the same checks: `check_docs.py` and `check_repository.py` run before packaging (`tools/test.py` holds the unit tests), and each Release lists SHA256 checksums. An existing Release is never overwritten.

Physical-device status recorded on 2026-11-03 (`HLTE203T`): **app and backend module both 0.2.2-r19**, `sys.inkcontrol.ready=1`, no `error` / `disable_hwc`, `helper_loaded=true`; a settings snapshot captured 418 rows across the three `settings` tables; long-pressing the launcher icon lists four shortcuts (Cover to sleep / Custom lock screen / Auto refresh / Fonts) alongside the stock Share / Freeze / Uninstall row; the "add tile" list grows from 5 stock tiles to hundreds; the navigation bar dropped from 100% to 2.26% dark pixels with zero module log lines over 60 idle seconds. Every figure above comes from single-device, single-run checks; none of it establishes panel quality, power, temperature coverage, or long-term stability. The historical first GPU error remains unexplained; the firmware's Google split-resource icon overlay should stay off. See [evidence boundaries](docs/wiki/evidence.en.md).

On 2026-11-06 this device (`HLTE203T`) was upgraded to **0.2.3-r20**: app and backend module reinstalled together, full reboot, `sys.inkcontrol.ready=1`, no `error` / `disable_hwc`, and the installed APK is byte-identical to the local build. Panel-button prompts now appear at the bottom of the screen (they had been raised to the top so the panel would not cover them); see the panel captures in **A look inside** above.

## Developers and agents

Start with [AGENTS.md](AGENTS.md) and the [agent handbook](docs/wiki/agent-handbook.en.md). [llms.txt](llms.txt) provides a short entry point; the [knowledge index](docs/wiki/knowledge-index.json) maps topics to code and evidence.

The feature catalog is the single configuration definition. The UI calls application services. See [contributing](CONTRIBUTING.en.md), [architecture](docs/wiki/architecture.en.md), and [localization](docs/localization.en.md).

The repository is public, with Chinese and English documentation under [`docs/wiki`](docs/wiki/README.en.md). Original code and artwork follow [MIT](LICENSE). Vendor binaries, third-party assets, and external frameworks retain their own terms; see [NOTICE](NOTICE.en.md). Keys, passwords, plaintext firmware, personal backups, and raw device logs stay outside Git.

[Publication and Wiki maintenance](docs/publication.en.md)

## Risks and disclaimer

This project is experimental and remains a release candidate. **Use a spare device or a dedicated test device; it is not recommended for a primary phone used for communication, payments, or important data.** Passing cloud builds and automated tests does not certify stability on a physical device or compatibility with every device and firmware.

Root, Magisk modules, system-framework hooks, and display-backend changes may cause freezes, boot loops, failure to boot, data loss, or device damage. **Some failures may be unrecoverable. Disabling, uninstalling, or rolling back the module is not guaranteed to recover the device or its data.**

**Before installing, upgrading, or enabling features, back up photos, documents, chats, app data, and important configuration. Copy the backup to a computer or other independent storage and check that it is readable. Do not keep the only backup on the phone being tested.** Prepare recovery materials and a recovery method matching the device and firmware. If you do not know how to disable a module or restore the system, do not proceed yet.

The project is provided as is, without a promise of fault-free operation, data preservation, or recovery. Understand the risks and decide whether to use it; see the warranty disclaimer in [LICENSE](LICENSE). A backup reduces the risk of losing data but does not guarantee that the device can be repaired.
