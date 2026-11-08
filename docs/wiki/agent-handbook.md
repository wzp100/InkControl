# Agent 接手手册

[English](agent-handbook.en.md) · [Wiki](README.md)

## 开始前先读

1. [仓库规则](../../AGENTS.md)、[当前证据](evidence.md) 和 [知识索引](knowledge-index.json)。目标是 **Hisense A5 Pro 经典版黑白屏**。
2. 用 `git status --short` 确认已有修改，读 `AndroidManifest.xml` 和目标模块源码。源码版本不能当成已安装设备版本。
3. 如果有完整工作区，读根 README、目录规划和后续改进清单，再按索引读取任务所需原始报告。仅克隆仓库时，缺失的本地报告不能当作已验证。

## 权威来源

| 问题 | 先看什么 |
| --- | --- |
| 有哪些功能、选项、依赖和默认值 | `core/FeatureCatalog.java`、`DisplayAlgorithms.java` |
| 实际显示算法路由 | `native/service_algorithm_policy.hpp`、`service_gpu.cpp` |
| 配置是否真的生效 | 当前进程 PID + starttime、版本、修订号、boot_id 及运行回执 |
| 发布包来自哪份代码 | Release 中 `build-manifest.json`、SHA256SUMS、run ID 和 commit |
| 翻页动画与 GPU 后端能否同时启用 | [功能列表后端支持清单](../功能列表后端支持清单.md) 第五节与对应发布说明 |
| 某个实验是否通过 | 原始证据及最新有范围说明的报告；旧总结只做线索 |

代码路径以下均相对 `src/com/inkcontrol/`；完整文件映射见[架构](architecture.md)。

## 常用任务配方

**改算法：** 读[算法](algorithms.md)，保持灰度／黑白分组与旧默认；修改纯选择策略或生产 shader；补充能暴露边界差异的策略／GPU probe；推送云端编译。软件 GLES 通过后，目标 Mali 独立核与 HWC 集成仍按授权另验。

**新增功能：** FeatureCatalog 定义有限选项、依赖、作用进程、HOT／REBOOT；处理器消费不可变快照；停用走条件恢复；就绪后回报，失败不能冒充成功。不要手写另一份后端目录或新增独立配置真源。详见[开发示例](../development.md)。

**改 UI 文案：** 以中文源短语为准，只在显示边界翻译；ID、属性、路由与配置协议不随语言变化。**英文界面翻译已停止维护**，`assets/i18n/en.tsv` 现有条目保留、缺失只提示不阻断。改完跑 `python tools/check_i18n.py`，中文文档改动再跑 `check_docs.py`。

**用户说又卡了：** 先读[诊断](operations.md)，保留首故障与保护标记，判断 GPU 计数是否增长；不要先清日志、清停用标记或重启 HWC。`stock` 慢不等于 CPU 回退。

**发版本：** 同步 `AndroidManifest.xml`、`tools/build.py`、`backend/lib/common.sh`、知识索引、中英 README 与发布说明的 versionCode/版本串；已发布版本不覆盖，行为有变才递增。签名 Release 只在 Actions 里对 `main` 手动触发，推送 main 不再自动构建，PR 只跑回归检查。**提交信息可以详细，发布说明与 README 保持精简**；发布说明中文，但必须保留备用机、异地备份、无法开机／数据丢失／可能不可恢复的免责声明。

## 操作边界与交接

本地可编辑和做静态检查；所有 Java/native/APK 编译与构建测试在 GitHub 托管 Actions。设备改动需属于用户已授权的独立任务。备份、原厂库、波形与旧实验快照不覆盖。私钥、密码、设备标识、原始日志和明文 HWC 输入不入库。

交接至少记录：问题与触发条件、commit/run/版本、改动文件、检查结果、未覆盖范围、设备是否改变、证据位置、恢复方式。状态要区分“已保存”“已回执”“已上机”“用户观感通过”。

知识库**只维护中文**；英文页面保留但不再同步。索引、证据编号与目标名称仍要保持一致，`python tools/check_docs.py` 校验配对、链接、目标名称与版本一致性。
