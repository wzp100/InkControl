# 墨控仓库 / InkControl repository

- Target device: **Hisense A5 Pro 经典版 (Classic Edition), monochrome E-ink**. Use this name in current product documentation.
- Documentation is maintained in **Chinese only** (since 2026-10-23): the English `README.en.md` and other English docs are **no longer updated or maintained**; `README.md` (Chinese) is the primary document. Keep the Chinese Wiki and source references current. Run `python tools/check_docs.py` after Chinese documentation edits.
- Signed builds publish a versioned Release only after all checks pass, and only when triggered manually (`workflow_dispatch` on `main`, since 2026-10-24): pushing to `main` no longer starts a cloud build; pull requests still run the regression checks only. Increment app/backend/build versions together for behavior or asset changes; never overwrite an existing published version.
- 签名构建在所有检查通过后发布版本化 Release，且自 2026-10-24 起仅限**手动触发**（在 Actions 页对 `main` 分支 Run workflow）；推送 `main` 不再自动构建，PR 仍只跑回归检查。行为或素材变更时同步递增 app/后端/构建版本号，不得覆盖已发布版本。
- 面向用户的回复用中文；文件统一 UTF-8。PowerShell `Get-Content` 必须显式 `-Encoding utf8`，不得用 `-Raw`。
- All compilation and build validation runs in GitHub-hosted Actions. Do not compile APKs, native libraries or Java locally. Local text edits and static checks are allowed.
- 所有编译及构建验证走 GitHub 托管 Actions；本地只编辑和做静态检查。
- Keep feature IDs, backend properties, configuration syntax and default values independent of UI language. Run `python tools/check_i18n.py` after changing user-visible source phrases. English UI translations are **no longer maintained** (since 2026-10-24): `check_i18n.py` reports missing entries as notes instead of failing the build; existing `assets/i18n/en.tsv` entries stay for the in-app English UI.
- 功能 ID、后端属性、配置语法与默认值不得依赖界面语言。修改用户可见源码文案后运行 `python tools/check_i18n.py`。**英文界面翻译自 2026-10-24 起停止维护**：`check_i18n.py` 对缺失翻译只提示不阻断；`assets/i18n/en.tsv` 已有条目保留（App 内英文界面仍可用）。
- Never commit private signing keys, unencrypted HWC inputs, device backups, raw logs, build outputs or local acceptance reports. CI dependencies in `ci/private-inputs.zip.enc` are encrypted; the password belongs in Actions secrets.
- Do not install, mount, flash, reboot or run device-changing tests as part of repository maintenance or CI. Real-device acceptance is a separate explicit task.
- 默认配置不因仓库整理、语言切换或云构建而改变；历史报告与发布版本不可覆盖。
- The user authorized public publication after the 2026-10-18 sensitive-data review. Maintain the native GitHub Wiki from docs/wiki; audit full Git history, Actions logs/artifacts and Release assets before expanding public content. Third-party binaries and artwork remain outside MIT; see NOTICE.md. Never put personal access tokens in workflows or Wiki pages.
- Keep bilingual risk notices in README and Wiki installation guidance; since rc16 Release descriptions/notes (`docs/releases/*.md`) are Chinese-only. Every Release description must still recommend spare/test devices, require an off-device backup reminder, and disclose boot failure, data loss and potentially unrecoverable damage; never promise rollback can rescue the device. **Commit messages may be detailed; Release notes and README stay concise.**
- Keep commit messages and public docs free of host-specific paths, credentials, device identifiers, PINs, raw logs and local acceptance data. Real-device findings belong in a separate explicit task, not in repository rules.

## 翻页动画与 HWC

- 「翻页动画」是社区作品（作者 nuku），经同意整合进墨控；第三方原生二进制与 HWC 派生库**不进入公开仓库**，云端构建输入需单独维护，权利范围见 `NOTICE.md`。README 与 Release 说明都要写明署名。
- `pageanim.master` 与 `display.gpu` **互斥**：同一个 `hwcomposer.ums512.so` 只能挂一份，同时开启时翻页动画优先、`libeinkgpu.so` 不挂载。文档必须说明「不能同时启用」且切换要完整重启；`pageanim.master` 是 REBOOT 项。详见 `docs/大功能后端支持清单.md` 与 `docs/releases/0.2.6-r26.md`。
- 后端模块按开机时的开关选挂 HWC，别在两个开关都打开时假设两套效果叠加；App 里显示「已开启」只代表配置，不代表库已挂载。

## 锁屏与排障要点

- 自定义锁屏样式 5–8 会与原厂 `BottomBarController.getBottomBar()` 的底部图标条重叠。`BottomBarHook` 在 **system_server** 里按 `Settings.Global.eink_keyguard_style` 的 **5–8** 区间直接 `setResult` 跳过原厂绘制；**不要改用 `LockStyle.custom()` / `RuntimeConfig.enabled("lock.style")`**，system_server 持有的是开机配置快照，App 里后来开启不会刷新它。
- 改 `BottomBarHook` 后必须**整机重启**：重启 SystemUI 不会重载 system_server 的模块与快照。
- `sys.inkcontrol.ready=0` 有两类独立成因：① `ic_validate` 遇到 `lib/features.tsv` 未登记的 key（新增功能没同步导出）；② `post-fs-data.sh` 的 `checksums.sha256` 校验失败（手工改过模块文件却没重算清单）。两者都应通过**干净重建并同批重装同版本后端模块**解决，让 `features.tsv` / `defaults.txt` / `checksums.sha256` 由构建一次生成。
- 真机验收用 `inkctl status` 确认 `ready=1` 与当前应挂载的 HWC 哈希；不要拿旧后端模块搭配新 APK 测试。
