# 墨控仓库 / InkControl repository

- Target device: **Hisense A5 Pro 经典版 (Classic Edition), monochrome E-ink**. Use this name in current product documentation.
- Start with [Agent handbook](docs/wiki/agent-handbook.md), [evidence](docs/wiki/evidence.md), and [knowledge index](docs/wiki/knowledge-index.json). Keep both Wiki languages and source references current. Run `python tools/check_docs.py` after documentation edits.
- Signed builds on main publish a versioned Release only after all checks pass. Increment app/backend/build versions together for behavior or asset changes; never overwrite an existing published version.
- 面向用户的回复用中文；文件统一 UTF-8。PowerShell `Get-Content` 必须显式 `-Encoding utf8`，不得用 `-Raw`。
- All compilation and build validation runs in GitHub-hosted Actions. Do not compile APKs, native libraries or Java locally. Local text edits and static checks are allowed.
- 所有编译及构建验证走 GitHub 托管 Actions；本地只编辑和做静态检查。
- Keep feature IDs, backend properties, configuration syntax and default values independent of UI language. Run `python tools/check_i18n.py` after changing user-visible source phrases; maintain English translations in `assets/i18n/en.tsv`.
- Never commit private signing keys, unencrypted HWC inputs, device backups, raw logs, build outputs or local acceptance reports. CI dependencies in `ci/private-inputs.zip.enc` are encrypted; the password belongs in Actions secrets.
- Do not install, mount, flash, reboot or run device-changing tests as part of repository maintenance or CI. Real-device acceptance is a separate explicit task.
- 默认配置不因仓库整理、语言切换或云构建而改变；历史报告与发布版本不可覆盖。
- The user authorized public publication after the 2026-10-18 sensitive-data review. Maintain the native GitHub Wiki from docs/wiki; audit full Git history, Actions logs/artifacts and Release assets before expanding public content. Third-party binaries and artwork remain outside MIT; see NOTICE.md. Never put personal access tokens in workflows or Wiki pages.
- Keep bilingual risk notices in README, Wiki installation guidance and every Release description: recommend spare/test devices, require an off-device backup reminder, and disclose boot failure, data loss and potentially unrecoverable damage; never promise rollback can rescue the device.
- Release versions must match `X.Y.Z` or `X.Y.Z-rcN` (enforced by `tools/publish_release.py`); `-rcN` publishes as a prerelease.
- Ship only binaries that are built from this repository's source or are the encrypted vendor HWC inputs. Do not add closed-source third-party executables or libraries; page-turn animation exists only as the GPU fusion path in `native/service_fusion.hpp` and requires `display.gpu`.
- 只分发由本仓库源码构建的二进制与加密的厂商 HWC 输入；不得加入无源码的第三方可执行文件或库。翻页动画只有 GPU 融合实现，依赖 `display.gpu`。

## 排障要点 / Troubleshooting notes

- 自定义锁屏样式 5–8 会与原厂 `BottomBarController.getBottomBar()` 的底部图标条重叠。`BottomBarHook` 在 system_server 里按 `Settings.Global.eink_keyguard_style` 的 5–8 区间跳过原厂绘制；system_server 持有的是开机配置快照，改动该钩子后必须整机重启才能验证。
- `sys.inkcontrol.ready=0` 有两类独立成因：`ic_validate` 遇到 `lib/features.tsv` 未登记的 key，或 `post-fs-data.sh` 的 `checksums.sha256` 校验失败。两者都通过同批重装同版本 APK 与后端模块解决；`features.tsv` / `defaults.txt` / `checksums.sha256` 由构建生成，不入库。
- 翻页动画的触发范围只认 `pageanim.readers` 名单（为空即不触发）；同一应用内的滚动、弹窗与页内重绘也会播放。机制见 `docs/翻页动画融合实现.md`。
