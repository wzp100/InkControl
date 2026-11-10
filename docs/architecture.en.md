# Architecture

[简体中文](architecture.md)

InkControl targets the Hisense A5 Pro 经典版 (Classic Edition, monochrome) (HLTE203T), Android 10, firmware L1732.6.01.02. One `com.inkcontrol` APK contains a matching Magisk backend ZIP. All 73 enhancements default to off.

| Directory | Responsibility |
| --- | --- |
| `src/com/inkcontrol/ui` | Native black-and-white UI, feature guides, previews, language choice |
| `src/com/inkcontrol/i18n` | Display-only phrase translation, independent of Android |
| `src/com/inkcontrol/core` | Stable feature IDs, immutable configuration, dependency and restore rules |
| `src/com/inkcontrol/platform` | Storage, Provider, fixed Root bridge, framework routing, runtime acknowledgement |
| `src/com/inkcontrol/features` | Motion, lock-screen, desktop, and system enhancement handlers |
| `native` | GPU processing, algorithm selection, waveforms, telemetry |
| `backend` | Magisk lifecycle, controller, configuration validation and restoration ledger |
| `res`, `assets`, `overlay` | Localized metadata, theme assets, translations, optional icon overlay |
| `test`, `tools` | Host regressions, native probes, build and verification entry points |
| `.github`, `ci` | Cloud workflow, pinned dependencies, encrypted private inputs, public trust certificate |

The UI requests changes through application services. Configuration is saved before applying it, so an application failure preserves the user's selection and offers retry. A saved switch is not treated as proof of runtime activation: current-process acknowledgement, matching revisions, dependency readiness, and startup state determine status. Disabling a parent preserves child selections.

Clear and Reading allow only 16-level grayscale algorithms; Fast, Extreme, and video allow only black-and-white algorithms. Video has a separate policy, and hysteresis is independently stored and hidden for incompatible algorithms. Default selections preserve the previous behavior.

Language preferences are separate from feature configuration. Rendering translates descriptions and labels while persisted IDs and backend protocols remain unchanged. Root, installation, hardware integration, and physical-panel acceptance are separate from cloud builds.

The HWC dependencies are version-specific vendor-derived binaries. They cannot be recreated from this repository alone and are outside its MIT license. See [sources](sources.en.md), [cloud builds](cloud-build.en.md), and [licensing](../NOTICE.en.md).
