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
- Never commit private signing keys, device backups, raw logs, build outputs or local acceptance reports. Vendor-derived HWC binaries live in `inputs/` and **are versioned** (since 2026-11-09); everything else in `ci/private-inputs.zip.enc` stays encrypted with the password in Actions secrets.
- 禁止提交签名私钥、设备备份、原始日志、构建产物与本机验收报告。厂商派生的 HWC 二进制放在 `inputs/` 并**允许入库**（2026-11-09 起）；`ci/private-inputs.zip.enc` 里的其余私有输入仍保持加密，密码只放 Actions secrets。
- Do not install, mount, flash, reboot or run device-changing tests as part of repository maintenance or CI. Real-device acceptance is a separate explicit task.
- 默认配置不因仓库整理、语言切换或云构建而改变；历史报告与发布版本不可覆盖。
- The user authorized public publication after the 2026-10-18 sensitive-data review. Maintain the native GitHub Wiki from docs/wiki; audit full Git history, Actions logs/artifacts and Release assets before expanding public content. Third-party binaries and artwork remain outside MIT; see NOTICE.md. Never put personal access tokens in workflows or Wiki pages.
- Keep bilingual risk notices in README and Wiki installation guidance; since rc16 Release descriptions/notes (`docs/releases/*.md`) are Chinese-only. Every Release description must still recommend spare/test devices, require an off-device backup reminder, and disclose boot failure, data loss and potentially unrecoverable damage; never promise rollback can rescue the device. **Commit messages may be detailed; Release notes and README stay concise.**
- Keep commit messages and public docs free of host-specific paths, credentials, device identifiers, PINs, raw logs and local acceptance data. Real-device findings belong in a separate explicit task, not in repository rules.

## 仓库目录

| 目录 | 作用 | 入库 |
| --- | --- | --- |
| `src/` | Java 源码：`core`（功能目录、配置解析、运行时状态）、`ui`（App 界面、开关与拖动排序控件）、`platform/`（Provider 与配置读写）、`features/`（各功能在 system_server / SystemUI / 桌面的钩子） | 是 |
| `res/`、`assets/` | Android 资源（布局、图标）与随包资源（Guide 文案、i18n、内置素材） | 是 |
| `native/` | `libeinkgpu.so` 的 C++ 源码：取帧与转置、像素量化、波形层与条带相位（`service_fusion.hpp`）、锁屏/唤醒动画、原厂桥接 | 是（编译产物不入库） |
| `backend/` | Magisk 模块骨架：`post-fs-data.sh`（开机选挂 HWC）、`service.sh`（常驻/守护）、`lib/common.sh`（`ic_*` 函数与 `ic_validate`）、`bin/inkctl`、`features.tsv` / `defaults.txt`（与 App 的 `FeatureCatalog` 一一对应） | 是 |
| `stubs/` | 编译用的最小 Xposed / 系统 API 声明，不含任何框架实现 | 是 |
| `test/` | 回归：`CoreTest`、`GuideTest`、`CatalogExport`、`GeometryTest` 等（由 `tools/test.py` 驱动）与后端 shell / python 测试 | 是 |
| `tools/` | 构建与静态检查（`build.py`、`check_docs.py`、`check_repository.py`、`check_i18n.py`）以及设备脚本（`adb_target.py`、`reboot_safe.py`、`unlock_device.py`） | 是 |
| `docs/` | 机制文档与索引、`wiki/`（面向使用者 + `knowledge-index.json`）、`releases/`（每版发布说明）、`media/`（界面截图与 SHA256 清单） | 是 |
| `inputs/` | **构建输入**：原厂与社区派生的 HWC（`hwcomposer.stock/motion/pageanim.so`）与常驻二进制（`a5pro-resident`、`a5pro-control-once`）。允许入库，但权利归原权利人（见 `NOTICE.md`），不要在文档里把它们算作 MIT 原创 | 是 |
| `ci/` | 云端构建的输入清单、加密依赖包（`private-inputs.zip.enc`）与固定签名证书 | 是 |
| `out/`、`results/` | 构建产物与验收输出 | 否（`.gitignore`） |
| `signing/`、`private-inputs/`、`.ci-secrets/` | 本地签名材料与私密输入 | **否，禁止入库** |
| `temp/` | 本机临时脚本与设备备份 | 否（`.gitignore`） |

顶层文件：`AndroidManifest.xml`（versionName / versionCode 的事实来源之一）、`tools/build.py` 的 `VERSION`、`backend/module.prop` 三处版本号必须一致；`llms.txt` 是 Agent 入口；`NOTICE.md` / `LICENSE` 定义权利边界。

## 翻页动画与 HWC

- 自 0.2.6-r26 起 `pageanim.master` 与 `display.gpu` **可以同时启用**：分块揭示已重写到 `libeinkgpu` 的 GPU 波形时序（`native/service_fusion.hpp`），两个开关同开不用重启，HWC 始终是墨控补丁库。**不要再写「二选一」「不能同时启用」**。
- 触发范围只认「目标阅读器」名单（`pageanim.readers`，名单为空＝不触发）；融合没有手势识别，同一应用内的滚动、弹窗、页内重绘也会播。`pageanim.touch` / `pageanim.keys` / `pageanim.debounce` 只在独立库模式（未开 GPU 后端）生效；音量键的方向由 `pageanim.vkey` 管，融合模式也生效。
- 后端模块仍按开机时的开关选挂 HWC（GPU 优先）；App 里显示「已开启」只代表配置，不代表库已挂载。
- 机制与实测见 `docs/翻页动画融合实现.md`；工作区方案文档为 `doc/GPU显示后端与翻页动画-兼容共存方案.md`。

## 文档与发布口径

- **README 不存本版信息**：不写「本版要点／更新内容」，也不写当前版本号（每次发版都要改，早晚不一致）。版本信息只放 Release 页与 `docs/releases/<版本>.md`，事实来源是 `module.prop` 与 `build.py` 的 `VERSION`。
- `docs/releases/*.md` 按固定模板、面向所有人写，**精简**：备份提醒 → 版本号 → 更新内容（新增 / 变更 / 修复）→ 使用注意 → 致谢 → 完整更新日志（compare 链接）→ 风险提示与免责声明（原文照抄，不得删减）。
- 界面截图：放进 `docs/media/`，宽 472（`ui-settings` / `ui-shortcuts` 为 360），文件名沿用 `ui-*.png`；换图时同步改 `docs/media/README.md` 的版本归属、描述与 SHA256，以及 README 里对应 `<img>` 的 alt。**用户自己提供的截图不要擅自重拍替换**。
- 改完中文文档跑 `python tools/check_docs.py`；英文文档（`README.en.md`、`docs/wiki/*.en.md`、`assets/i18n/en.tsv`）停止维护，不要为版本推进或文案调整去改。

## 锁屏与排障要点

- 自定义锁屏样式 5–8 会与原厂 `BottomBarController.getBottomBar()` 的底部图标条重叠。`BottomBarHook` 在 **system_server** 里按 `Settings.Global.eink_keyguard_style` 的 **5–8** 区间直接 `setResult` 跳过原厂绘制；**不要改用 `LockStyle.custom()` / `RuntimeConfig.enabled("lock.style")`**，system_server 持有的是开机配置快照，App 里后来开启不会刷新它。
- 改 `BottomBarHook` 后必须**整机重启**：重启 SystemUI 不会重载 system_server 的模块与快照。
- `sys.inkcontrol.ready=0` 有两类独立成因：① `ic_validate` 遇到 `lib/features.tsv` 未登记的 key（新增功能没同步导出）；② `post-fs-data.sh` 的 `checksums.sha256` 校验失败（手工改过模块文件却没重算清单）。两者都应通过**干净重建并同批重装同版本后端模块**解决，让 `features.tsv` / `defaults.txt` / `checksums.sha256` 由构建一次生成。
- 真机验收用 `inkctl status` 确认 `ready=1` 与当前应挂载的 HWC 哈希；不要拿旧后端模块搭配新 APK 测试。
