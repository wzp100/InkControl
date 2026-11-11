# Installation and usage

[简体中文](getting-started.md) · [Wiki](README.en.md)

## Compatibility

Target: **Hisense A5 Pro 经典版 (Classic Edition), monochrome**. The backend checks `ro.product.device=HLTE203T`, API 29, `arm64-v8a`, firmware containing `L1732.6.01.02`, and exact stock-library hashes. Verified system software versions (`ro.software.version`) are `L1732.6.14.01.B10` and `L1732.6.15.01.B10`. A matching retail name does not bypass these gates. Relevant enhancements require Magisk and Vector or a compatible Xposed framework.

## Risks and disclaimer

This project is experimental and remains a release candidate. **Use a spare device or a dedicated test device; it is not recommended for a primary phone used for communication, payments, or important data.** Passing cloud builds and automated tests does not certify stability on a physical device or compatibility with every device and firmware.

Root, Magisk modules, system-framework hooks, and display-backend changes may cause freezes, boot loops, failure to boot, data loss, or device damage. **Some failures may be unrecoverable. Disabling, uninstalling, or rolling back the module is not guaranteed to recover the device or its data.**

**Before installing, upgrading, or enabling features, back up photos, documents, chats, app data, and important configuration. Copy the backup to a computer or other independent storage and check that it is readable. Do not keep the only backup on the phone being tested.** Prepare recovery materials and a recovery method matching the device and firmware. If you do not know how to disable a module or restore the system, do not proceed yet.

The project is provided as is, without a promise of fault-free operation, data preservation, or recovery. Understand the risks and decide whether to use it; see the warranty disclaimer in [LICENSE](../../LICENSE). A backup reduces the risk of losing data but does not guarantee that the device can be repaired.

## First installation

1. Download a signed APK from [Releases](https://github.com/wzp100/InkControl/releases); check its notes and SHA256SUMS. `-unsigned` files cannot be installed. Do not mix old artifacts.
2. Back up configuration, old APK, and modules. rc13 introduced a new release identity; uninstall the old signed app before migration. Do not re-sign packages merely to force installation.
3. Open **Install**, check compatibility and old-module conflicts, export this APK's matching Magisk ZIP, and install it.
4. Framework scopes: System Framework, System UI `com.android.systemui`, stock launcher `com.hmct.vision`. Tick System Framework in the manager UI; from the Vector command line its name is `system` (`android` alone does not inject the system framework, and Diagnostics shows 2 / 3). Open InkControl once and grant Root before restarting: this device blocks other processes from starting the app's configuration Provider until the app has been opened.
5. Restart fully, inspect installation and diagnostic acknowledgements, then enable individual features. All 88 enhancements initially remain off.

These steps require device-operation authorization; documentation work and CI do not execute them. Do not replace the GPU-fix candidate with the earlier backend embedded in an old rc10 APK.

## Everyday use

- Each display mode and video has separate algorithm selection. This chooses quantization; it does not switch an application's refresh mode. See [algorithms](algorithms.en.md).
- Disabling a parent stops child effects while preserving selections. Startup features require a restart. Saved intent is not activation proof.
- Language selection on **Module settings** offers system, Simplified Chinese, and English without changing feature configuration or global system language.
- Without Root, guides, previews, language choice, and installer export work; this does not mean the GPU backend is active.
- Page-turn animation requires the GPU display backend and triggers only for apps on the target-reader list; auto refresh and Proximity each enable an accessibility service while on. Read experimental-feature boundaries first.

Use the app and backend from the same Release. On failure, collect evidence before following [recovery](operations.en.md). Full clear refresh and local update are different operations.

Sources: `R01`–`R04` in the [evidence index](knowledge-index.json).
