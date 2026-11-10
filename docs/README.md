# docs/ 文档索引

这个目录放**可以直接照着做**的机制说明与流程文档；过程性的分析、对照实验与回滚记录留在仓库外的工作区 `doc/`，不随仓库发布。
新增文档时：机制类放本目录根下并在下表补一行；流程类放本目录根下；对外讲解放 `wiki/`。

## 机制参考（读它就够动手）

| 文档 | 讲什么 | 什么时候看 |
| --- | --- | --- |
| [E-Ink快捷面板.md](E-Ink快捷面板.md) | E-Ink 刷新面板怎么唤起、按钮怎么替换（最多五格，第 5 格默认「无」）、原厂「截屏便签」怎么复用；动作表、长按跳转、`toggle` 与 App 状态同步链路、被 force-stop 时的兜底 | 改面板按钮、加面板动作、排查面板开关不生效 |
| [刷新格言外部调用.md](刷新格言外部调用.md) | 不打开墨控、从 shell / 自动化工具刷新锁屏格言的两条命令（广播与透明页）、链路与前置条件 | 写脚本、做自动化、排查外部触发无反应 |
| [翻页动画融合实现.md](翻页动画融合实现.md) | 分块揭示怎么落进 GPU 波形分带：接入点、门控条件、条带相位与帧数预算、六种展开形状、方向判定（手势与音量键）、目标阅读器闸门、与全刷扫过的互斥、属性与排查口径 | 改翻页动画、融合、分带揭示或全刷扫过 |
| [全刷画面回退修复.md](全刷画面回退修复.md) | 全刷后画面退回上一页：三缓冲轮转假定「每波都有新帧」的根因，按 SF 出帧计数决定「消费新帧 / 复用屏上帧」的修复、验证口径与回退开关 | 排查全刷闪回上一页、动 GPU 取帧链路 |
| [功能列表后端支持清单.md](功能列表后端支持清单.md) | 功能列表里每一项依赖哪层后端（Magisk / system_server / SystemUI / 显示库）、哪些做法已验证不可行及代价 | 判断某项能否在不重启的前提下生效 |
| [字体替换机制.md](字体替换机制.md) | 字体槽位路径、`HmctFont.ttf` 软链、`inkctl applyfont` 与生效链路 | 改字体替换 |
| [系统设置备份清单.md](系统设置备份清单.md) | 快照里包含哪些设置项、哪些会被跳过 | 改备份范围或排查恢复差异 |

## 构建与发布

| 文档 | 讲什么 |
| --- | --- |
| [build.md](build.md) / [cloud-build.md](cloud-build.md) | 本地构建步骤、签名与产物；GitHub Actions 云端构建的开关与限制 |
| [publication.md](publication.md) | 公开前的审查清单（密钥、日志、个人数据、第三方权利） |
| [verification.md](verification.md) | 验证口径：静态检查、上机冒烟、重启验证的分级 |
| [sources.md](sources.md) / [source-provenance.json](source-provenance.json) | 第三方来源与派生文件的权利归属 |
| [development.md](development.md) / [architecture.md](architecture.md) | 分层（core → ui → platform / features + native + backend）与改动入口 |
| [localization.md](localization.md) | 中英文案维护约定 |
| [media/README.md](media/README.md) | 界面截图清单、尺寸与 SHA256 |

## 知识库

[wiki/README.md](wiki/README.md) 是面向使用者的说明（入门、显示算法、诊断恢复、研究结论），
[wiki/agent-handbook.md](wiki/agent-handbook.md) 是接手开发的入口，
[wiki/knowledge-index.json](wiki/knowledge-index.json) 是结构化索引。
