# Agent handbook

[简体中文](agent-handbook.md) · [Wiki](README.en.md)

## Read first

1. Read [AGENTS.md](../../AGENTS.md), [evidence](evidence.en.md), and the [knowledge index](knowledge-index.json). The target is **Hisense A5 Pro 经典版 (Classic Edition), monochrome**.
2. Inspect `git status --short`, `AndroidManifest.xml`, and relevant code. Source version is not installed-device version.
3. With the full workspace, read its root README, directory plan, task list, and required original reports. A missing workspace-only report in a clone is not verification.

| Question | Authority |
| --- | --- |
| Features, options, dependencies, defaults | `core/FeatureCatalog.java`, `DisplayAlgorithms.java` |
| Runtime algorithm routing | `native/service_algorithm_policy.hpp`, `service_gpu.cpp` |
| Actual activation | Current PID + starttime, version, revision, boot_id, runtime acknowledgement |
| Artifact provenance | Release `build-manifest.json`, SHA256SUMS, run ID, commit |
| Experimental success | Raw evidence and latest scoped report; older summaries are pointers |
| Page-turn animation and the GPU backend | Section 5 of the [backend support list](../功能列表后端支持清单.md) (Chinese) and the matching release notes |

Java paths above are relative to `src/com/inkcontrol/`. See the [code map](architecture.en.md).

## Task recipes

**Algorithms:** read [algorithm policy](algorithms.en.md); preserve grayscale/binary separation and defaults. Change the shared selection policy or production shaders, add meaningful boundary probes, and build in Actions. Software GLES success does not replace Mali or HWC acceptance.

**Features:** define finite options, dependencies, process role, and HOT/REBOOT in FeatureCatalog. Consume immutable snapshots in handlers, conditionally restore on disable, acknowledge only after readiness, and report failures. Do not introduce another configuration source or handwritten backend catalog. See the [development guide](../development.en.md).

**UI text:** keep Chinese source phrases and `assets/i18n/en.tsv` in sync. Translate at display boundaries; persisted IDs, routing, properties, and protocols stay stable. Run `python tools/check_i18n.py` (missing translations fail), `check_docs.py` after documentation edits, and cloud UI/text tests.

**Slow refresh:** follow [operations](operations.en.md). Preserve first-fault evidence and protection markers before intervention. Inspect counter growth. Do not begin by clearing logs, removing markers, or restarting HWC. Slow `stock` processing is not proof of CPU fallback.

**Releases:** run `python tools/bump_version.py <version>`, which synchronizes the version string and versionCode in `AndroidManifest.xml`, `tools/build.py`, `backend/lib/common.sh`, the knowledge index and the Wiki, and creates a bilingual release-notes skeleton. Pushing main releases automatically only after signed packaging, regressions and bilingual UI checks pass; PRs run regressions only. Versions must match `X.Y.Z` or `X.Y.Z-rcN`. Existing versions remain intact; increment the version for behavioral changes. Commit messages may be detailed while release notes and READMEs stay concise; release notes are bilingual and must keep the spare-device, off-device backup and boot-failure / data-loss / unrecoverable-damage notices.

## Boundaries and handoff

Local work is editing and static inspection. All Java/native/APK compilation and build tests run on GitHub-hosted Actions. Device changes require a separately authorized task. Preserve original libraries, waveforms, backups, and experiment snapshots. Never commit signing secrets, device identifiers, raw logs, or plaintext HWC inputs.

Record the trigger, commit/run/version, changed files, checks, limitations, device changes, evidence location, and recovery. Distinguish saved intent, runtime acknowledgement, device validation, and user panel feedback.

Maintain both languages, source IDs, and the index. `python tools/check_docs.py` checks pairs, links, target naming, and version consistency.
