# Code map and architecture

[简体中文](architecture.md) · [Wiki](README.en.md) · [Architecture overview](../architecture.en.md)

| Repository path | Responsibility |
| --- | --- |
| `src/com/inkcontrol/core` | Catalog, configuration, dependencies, restoration; Android-free |
| `src/com/inkcontrol/ui` | `MainActivity` keeps lifecycle, navigation, the page shell, state and the save flow; pages live in their own classes (`*Pages`, `FeaturePage`, `FeatureRows`, `OptionViews`, `PreviewViews`, editors) with shared widgets in `Widgets`; application-service calls only |
| `src/com/inkcontrol/platform` | Android composition, AtomicFile, Provider, RootBridge, Hook routing, acknowledgements |
| `src/com/inkcontrol/features` | Motion, lock, desktop, fix, refresh (auto refresh), wave (proximity), qsext (control center and E-Ink panel), and navbar handlers |
| `src/com/inkcontrol/features/lock` | `LockCanvas` keeps canvas state and text/shape primitives; each lock style lives in `LockEditorial`, `LockEditorialLand`, `LockSwiss`, `LockPixel`, `LockGlass`, and the device-status strip in `LockExtBar` |
| `src/com/inkcontrol/shared` | Actual shared weather/media logic |
| `src/com/inkcontrol/i18n` | Pure Java display translation |
| `native/service_gpu.cpp` | GPU service and production shaders |
| `native/service_algorithm_policy.hpp` | Pure algorithm-selection policy |
| `native/service_queue_retry.hpp` | Stable input-queue selection and bounded retry |
| `native/service_fusion.hpp` | Banded waveform phase for page-turn animation (GPU fusion) |
| `backend` | Magisk lifecycle, fixed controller, protection, conditional restoration; validation rules in `lib/validate.awk` |
| `test`, `tools`, `ci`, `.github/workflows` | Tests, cloud build/publish tools, encrypted inputs, trust certificate |

The single user configuration source is device-protected app `files/configuration.txt`, committed through AtomicFile. Backend `/data/adb/inkcontrol/configuration.txt` is generated boot input; `applied.txt` and `boot.txt` are acknowledgements, not another user-editable source.

FeatureCatalog drives UI, validation, tests, and generated `features.tsv/defaults.txt/retired.txt`: mandatory keys, default-on entries and retired keys are all exported from it, so the backend and package verification keep no hand-written lists. IDs, finite values, dependencies, process roles, HOT/REBOOT, and experimental flags are defined together. New optional algorithm fields preserve legacy defaults and revisions.

Changes pass validation, save desired intent, apply, and await acknowledgement. Application failure retains intent and offers retry. Parent disable preserves child selections.

Hook acknowledgement matches PID, `/proc/PID/stat` starttime, version, and revision. Startup behavior also matches boot_id and boot snapshot. GPU confirmation requires the current helper, telemetry, and zero errors. Stale process success is not reused.

The Provider runs in its own `:state` process, restricts callers by UID and role, and exposes no arbitrary configuration writes, file access, or Root execution. Its only write entry is `toggle`, accepted from System UI or InkControl itself and limited to five whitelisted switches (auto refresh, lock-screen theme, sleep on cover, page-turn animation, full-refresh sweep). The exported transparent `PanelToggleActivity` is its fallback when the Provider cannot be started, so other installed apps can also flip those five switches, but cannot write any other setting or value. Permission enhancement binds the build certificate. RootBridge calls only fixed `inkctl`, with values through stdin and whitelist parsing.

Restoration uses before/written ledgers and restores only values still owned by InkControl. Field-level XML merges preserve later user edits. Magisk disable/uninstall handles recovery. Display-backend monitoring reuses the Magisk startup service. Auto refresh and Proximity each have an accessibility service (`:refresh` and `:motion` processes) that the backend adds to the enabled list only while its switch is on and removes afterwards.

The app declares network permission solely for the manual update check on the About page (it reads the GitHub releases feed; nothing is checked, downloaded or installed automatically) and storage read permission for choosing font files.

Sources: `R01`, `R04`. [source-provenance.json](../source-provenance.json) records imported originals, not current modified-file hashes. Current source provenance belongs to the build manifest.
