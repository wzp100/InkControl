# Evidence and current status

[简体中文](evidence.md) · [Wiki](README.en.md) · [Source register](knowledge-index.json)

| Object | Recorded state | Scope |
| --- | --- | --- |
| Device at 2026-10-18 handoff | rc10 app + rc10-gpu-fix1 | Documentation/release work has not updated the phone |
| Queue-fix candidate | Full restart, alternating video, 12 window cycles, 3 sleep/wake cycles; 1024 waves / 12032 scans, zero CPU waves/errors at capture | Short scoped smoke test; historical first fault and stability remain open |
| rc11 algorithms | Core810, policy1785, 59 comparisons each on Mali/software GLES, six emulator algorithm pages | Selection, standalone computation, UI; no integrated app/backend installation |
| rc13 cloud candidate | Signing, packaging, regressions, software GPU, four bilingual pages passed | Cloud/emulator scope, no new signing migration on device |
| rc15 public candidate | All cloud jobs passed; 66 Python tests, policy1785/queue6/GPU59, four UI configurations; seven Release asset hashes verified | Signed public prerelease; device acceptance remains pending |
| rc16 auto-refresh candidate | Nine new auto-refresh options, all off by default; the accessibility service is reconciled only from the master switch; the app list filters counts by the foreground app | Cloud static-check scope; the refresh entry and device acceptance remain open |
| rc17 UI and lock-screen layout candidate | Home statistics folded into category titles, single-line status overview, install and diagnostics merged into Module settings, notice duration of 5/30/60 seconds or hidden; Editorial landscape and landscape-reversed lock styles (confirmed by the contributor on a lock poster) | Contributor's local build and device; cloud regression and panel/long-term acceptance were not run for that candidate |
| 0.3.0-rc1 public candidate | All cloud jobs passed: repository/translation/docs checks, Java and backend regressions, policy1785/queue6/GPU59, signing and package verification (88 enhancements default off), four bilingual UI configurations; seven Release assets | Signed public prerelease; merges features contributed by liuqianhe (PR #1) without the closed-source standalone page-turn library |
| 0.3.0-rc1 device smoke test, 2026-11-11 | Cloud-signed candidate installed after uninstalling the old-signature rc10 app; stock display library restored after the backend upgrade with `ready=1`; all three scopes loaded; Liquid glass and Editorial landscape lock posters with the device-status strip; auto refresh triggered through `EpdManagerService.forceClear`; proximity accessibility service added and removed with its switch; GPU display backend mounted with `gpu_errors=0` and `cpu_waves=0`; page-turn fusion gate and direction signals correct; normal reboot after restoring the previous everyday configuration; follow-up: settings snapshot save/list/delete (471 entries), system-app removal and restore, font apply and stock restore, and the five E-Ink panel buttons (display, in-place toggle, long-press jump) | One `HLTE203T` (system software `L1732.6.15.01.B10`), driven by automation; on-panel waveform appearance, physical proximity sleep, white navigation keys (no navigation bar on this unit), long-term stability and power remain unverified |
| Current source | Version in `AndroidManifest.xml`; Release tied to its commit/run | Consult that Release's evidence |

Public 0.3.0-rc1: [run 42916377816](https://github.com/wzp100/InkControl/actions/runs/42916377816), source `275dd9a70965bf09d34abcebfc9b89a4bb5fdcbf`, [Release](https://github.com/wzp100/InkControl/releases/tag/v0.3.0-rc1). The device smoke test used a signed build of the same source from the integration branch (run 42867869530), not the identical release file.

Public rc15: [run 39098632837](https://github.com/wzp100/InkControl/actions/runs/39098632837), source `3db347f5c36134d4344c1f3d215caa784f0e8c95`, [Release](https://github.com/wzp100/InkControl/releases/tag/v0.1.0-rc15). The rc13 reference below is in the private historical archive.

rc13 reference run: [39046131228](https://github.com/wzp100/InkControl-private-archive-20261018/actions/runs/39046131228), commit `c9e4d7d7ca75214ab6b7a204392ad7adcb16a479`. An earlier attempt timed out installing the emulator fixture after 60 seconds. Extending the limit to 180 seconds and adding diagnostic capture produced a successful repeat; failure evidence was retained.

Known gaps: missing original first-fault logs; the Google settings icon fix was retired in 0.3.0-rc1 (its split-resource overlay never took effect on this firmware); not yet accepted for 0.3.0-rc1: on-panel page-turn and sweep appearance, physical proximity sleep, white navigation keys (no navigation bar on the test unit), WiFi/Bluetooth panel buttons and drag ordering; occasional Vector reinjection failure after service reload; separate acceptance for integrated HWC, temperature, physical panel, lyrics, unplugged power, and long-term stability.

Releases attach `build-manifest.json`, `package-verification.json`, `release-verification.json`, SHA256SUMS, and bilingual notes. The same run retains original test/UI artifacts. Manifests bind commit, run, input, and source hashes; they do not certify physical-device acceptance.

Resolve `Rxx` through [knowledge-index.json](knowledge-index.json). Repository sources are available in a clone. Workspace-only sources require the original workspace; hashes identify the report version reviewed here. Do not invent repository links to absent logs or commit secrets/backups for convenience.

New experiments record input version, device state, parameters, raw evidence, conclusions, and restoration. Preserve failures and old reports; add new verification in new directories. Sources: `R02`, `R03`, `R04`, `R11`.


Repository publication and historical commit identities are described in the [publication record](../publication.en.md). Earlier private build SHAs were remapped when author emails were sanitized; current public Release manifests carry the exact public commit.
