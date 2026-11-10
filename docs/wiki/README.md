<p><img src="../brand/mark.svg" width="56" alt="墨控"></p>

# 墨控知识库

[English](README.en.md) · [项目首页](../../README.md) · [结构化索引](knowledge-index.json)

面向 **Hisense A5 Pro 经典版黑白屏**。这里保存可持续维护的项目知识：怎么使用、代码在哪里、实验得到了什么、哪些结论仍待验证。Wiki 随源码提交，可离线阅读；原始实验与设备备份保留在本地工作区。

> **建议仅在备用机／测试机上使用，操作前将重要数据备份到手机之外。可能无法开机、丢失数据或发生无法恢复的故障，回退不保证救回。请先读[完整风险提示与免责声明](getting-started.md#风险提示与免责声明)。**

## 按目的阅读

| 章节 | 你会找到什么 |
| --- | --- |
| [使用入门](getting-started.md) | 安装、作用域、默认配置、升级与语言 |
| [Agent 手册](agent-handbook.md) | 接手顺序、权威来源、任务配方与交接要求 |
| [设备与显示链路](device-and-display.md) | 适配条件、像素到扫描、模式与波形的区别 |
| [显示算法](algorithms.md) | 每种模式支持什么，视频何时触发，迟滞如何工作 |
| [源码架构](architecture.md) | 模块职责、配置、IPC、回执、条件恢复 |
| [诊断与恢复](operations.md) | GPU 回退、首故障日志、框架与模式异常排查 |
| [研究与结论](research.md) | 已完成工作、性能测量、失败方案与研究边界 |
| [构建与 Release](build-and-release.md) | 云编译、签名、自动发布、版本与附件 |
| [证据与当前状态](evidence.md) | 版本时间线、通过范围、已知问题和证据来源 |
| [术语表](glossary.md) | HWC、波形、扫描、迟滞、修订号等术语 |

首次接手的 agent：**手册 → 证据 → 架构 → 任务对应章节 → 对应源码**。不要只读旧总结后直接部署。

## GitHub 原生 Wiki

在线阅读入口为 [GitHub 原生 Wiki](https://github.com/wzp100/InkControl/wiki)，提供中文与英文导航。`docs/wiki` 保留同一内容的维护源，便于代码审查和 agent 离线阅读。使用 `python tools/export_wiki.py --destination out/native-wiki-<唯一编号>` 导出 22 篇正文及侧边栏、页脚，再提交到独立的 `InkControl.wiki.git`。两处不要分别修改；导出命令只生成文件，发布需要另行 Git 推送。

## 阅读约定

- “源码支持”指代码和配置中存在实现；“独立核通过”只覆盖对应计算；“上机冒烟”“重启验证”“面板评价”“长稳”分别记录。
- 结论旁的 `Rxx` 是 [来源索引](knowledge-index.json) 中的稳定编号。索引标明仓库内来源和仅本地可读的研究报告，并记录后者 SHA256。
- 已记录设备自 2026-11-11 冒烟测试起为 0.3.0-rc1 App 与后端（此前为 rc10 App + rc10-gpu-fix1）。新签名从 rc13 开始；当前源码版本见 `AndroidManifest.xml`，每次发布的确切验证见 Release 附件。
- 文档不授予设备操作权限；维护文档或云构建不包含手机安装、挂载、刷写和重启。
