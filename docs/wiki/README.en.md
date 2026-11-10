<p><img src="../brand/mark.svg" width="56" alt="InkControl"></p>

# InkControl knowledge base

[简体中文](README.md) · [Project](../../README.en.md) · [Machine-readable index](knowledge-index.json)

For the **Hisense A5 Pro 经典版 (Classic Edition), monochrome screen**. This versioned, offline-readable Wiki explains usage, code, experimental findings, and open questions. Original experiments and device backups remain in the local workspace.

> **Use a spare/test device and back up important data off the phone before proceeding. Boot failure, data loss, or unrecoverable damage is possible; rollback is not a recovery guarantee. Read the [full risks and disclaimer](getting-started.en.md#risks-and-disclaimer).**

| Chapter | Contents |
| --- | --- |
| [Getting started](getting-started.en.md) | Installation, scopes, defaults, upgrades, languages |
| [Agent handbook](agent-handbook.en.md) | Reading order, authority, task recipes, handoff |
| [Device and display](device-and-display.en.md) | Compatibility, pixels to scans, modes versus waveforms |
| [Algorithms](algorithms.en.md) | Supported choices, video activation, hysteresis |
| [Architecture](architecture.en.md) | Responsibilities, configuration, IPC, acknowledgements, restoration |
| [Operations](operations.en.md) | GPU fallback, first-fault evidence, framework and mode diagnosis |
| [Research](research.en.md) | Completed work, measurements, unsuccessful approaches, limitations |
| [Build and release](build-and-release.en.md) | Cloud compilation, signing, automatic releases, versioning |
| [Evidence and status](evidence.en.md) | Timeline, validation scope, known issues, sources |
| [Glossary](glossary.en.md) | HWC, waveform, scan, hysteresis, revision |

Agent reading order: **handbook → evidence → architecture → task chapter → relevant code**. An old summary alone is insufficient for deployment decisions.

## Native GitHub Wiki

Read the [native GitHub Wiki](https://github.com/wzp100/InkControl/wiki/Home.en), with Chinese and English navigation. The source of truth stays in `docs/wiki` for review and offline agent access. Export with `python tools/export_wiki.py --destination out/native-wiki-<unique-id>`; commit the 22 pages plus sidebar/footer to the separate `InkControl.wiki.git` repository. Never edit both copies independently. The exporter creates files only; publication requires a separate Git push.

Implementation, standalone-kernel validation, device smoke tests, reboot checks, panel evaluation, and long-term tests are distinct evidence levels. `Rxx` references resolve through the [source index](knowledge-index.json), which records repository sources and hashes for workspace-only reports.

The recorded device runs the 0.3.0-rc1 app and backend since the 2026-11-11 smoke test (previously rc10 app + rc10-gpu-fix1). New signing starts at rc13. Read `AndroidManifest.xml` for the current source version and Release attachments for exact build evidence. Documentation is not authorization to install, mount, flash, or reboot a device.
