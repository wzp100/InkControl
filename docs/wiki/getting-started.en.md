# Installation and usage

[简体中文](getting-started.md) · [Wiki](README.en.md)

## Compatibility

Target: **Hisense A5 Pro 经典版 (Classic Edition), monochrome**. The backend checks `ro.product.device=HLTE203T`, API 29, `arm64-v8a`, firmware containing `L1732.6.01.02`, and exact stock-library hashes. A matching retail name does not bypass these gates. Relevant enhancements require Magisk and Vector or a compatible Xposed framework.

## Risks and disclaimer

This project is experimental and remains a release candidate. **Use a spare device or a dedicated test device; it is not recommended for a primary phone used for communication, payments, or important data.** Passing cloud builds and automated tests does not certify stability on a physical device or compatibility with every device and firmware.

Root, Magisk modules, system-framework hooks, and display-backend changes may cause freezes, boot loops, failure to boot, data loss, or device damage. **Some failures may be unrecoverable. Disabling, uninstalling, or rolling back the module is not guaranteed to recover the device or its data.**

**Before installing, upgrading, or enabling features, back up photos, documents, chats, app data, and important configuration. Copy the backup to a computer or other independent storage and check that it is readable. Do not keep the only backup on the phone being tested.** Prepare recovery materials and a recovery method matching the device and firmware. If you do not know how to disable a module or restore the system, do not proceed yet.

The project is provided as is, without a promise of fault-free operation, data preservation, or recovery. Understand the risks and decide whether to use it; see the warranty disclaimer in [LICENSE](../../LICENSE). A backup reduces the risk of losing data but does not guarantee that the device can be repaired.

## First installation

1. Download a signed APK from [Releases](https://github.com/wzp100/InkControl/releases); check its notes and SHA256SUMS. `-unsigned` files cannot be installed. Do not mix old artifacts.
2. Back up configuration, old APK, and modules. rc13 introduced a new release identity; uninstall the old signed app before migration. Do not re-sign packages merely to force installation.
3. Open **Install**, check compatibility and old-module conflicts, export this APK's matching Magisk ZIP, and install it.
4. Framework scopes: System Framework `android`, System UI `com.android.systemui`, stock launcher `com.hmct.vision`.
5. Restart fully, inspect installation and diagnostic acknowledgements, then enable individual features. All 74 enhancements initially remain off.

These steps require device-operation authorization; documentation work and CI do not execute them. Do not replace the GPU-fix candidate with the earlier backend embedded in an old rc10 APK.

## Everyday use

- Each display mode and video has separate algorithm selection. This chooses quantization; it does not switch an application's refresh mode. See [algorithms](algorithms.en.md).
- Disabling a parent stops child effects while preserving selections. Startup features require a restart. Saved intent is not activation proof.
- Language selection offers system, Simplified Chinese, and English without changing feature configuration or global system language.
- Without Root, guides, previews, language choice, and installer export work; this does not mean the GPU backend is active.
- Leave the unsupported Google split-resource icon overlay off. Read experimental-feature boundaries first.

Use the app and backend from the same Release. On failure, collect evidence before following [recovery](operations.en.md). Full clear refresh and local update are different operations.

Sources: `R01`–`R04` in the [evidence index](knowledge-index.json).
