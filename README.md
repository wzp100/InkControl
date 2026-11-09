<p align="center"><img src="docs/brand/banner.svg" alt="墨控 InkControl — Hisense A5 Pro 经典版" width="100%"></p>

<p align="center"><b>中文文档（主）</b> · <a href="docs/wiki/README.md"><b>知识库</b></a> · <a href="docs/wiki/agent-handbook.md">Agent 从这里开始</a> · <a href="README.en.md">English</a> · <a href="https://github.com/liuqianhe/InkControl/releases">下载安装包</a></p>

# 黑白之间，多一点掌控

**墨控 · InkControl** 是给 **Hisense A5 Pro 经典版**（`HLTE203T` / Android 10 / arm64）做的黑白墨水屏增强套件：一个 App 管理显示算法、动画、翻页动画、锁屏、原厂桌面与系统增强，配套一个 Magisk 后端模块。**全部增强默认关闭**，按需开启；界面同时展示期望配置、运行回执与异常状态。安装包与版本说明见 [Releases](https://github.com/liuqianhe/InkControl/releases)，App 与后端模块的版本号必须一致。

> 仅建议用于备用机或测试机，安装前先备份并阅读[风险提示与免责声明](#风险提示与免责声明)。

| 显示 | 动画 | 翻页动画 | 锁屏 | 原厂桌面 | 系统增强 | 字体 | 快捷面板 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 四种模式分别选算法，灰度／黑白分组，迟滞独立 | 场景效果与预览，复用显示链路 | 支持阅读软件，翻页显示「水波纹」动画 | 六套样式，天气／歌词／通知数／设备状态逐项启停/倒数日 | 主题、图标、壁纸分别选择；长按图标列出最多四个常用功能 | 控制中心磁贴、导航键漂白、字体替换、设置快照备份、系统应用卸载还原等 | 直接选择字体文件，进行替换系统字体 | 为系统`E-Ink`面板添加更多功能 |

## 入口

| 我想……                 | 读这里                                                                                                                  |
| ------------------------ | ----------------------------------------------------------------------------------------------------------------------- |
| 安装和开始使用           | [使用入门](docs/wiki/getting-started.md)                                                                                 |
| 理解四个模式、视频与迟滞 | [显示算法](docs/wiki/algorithms.md)                                                                                      |
| 排查卡顿、配置未生效     | [诊断与恢复](docs/wiki/operations.md)                                                                                    |
| 接手开发                 | [Agent 手册](docs/wiki/agent-handbook.md) → [研究与结论](docs/wiki/research.md)                                          |
| 读机制与流程文档         | [docs/ 索引](docs/README.md)                                                                                             |
| 找源码、证据与发布流程   | [架构](docs/wiki/architecture.md) · [证据索引](docs/wiki/evidence.md) · [构建与 Release](docs/wiki/build-and-release.md) |

## 使用方式

1. 从 [Releases](https://github.com/liuqianhe/InkControl/releases) 下载签名 APK，先看该版本说明与校验文件。
2. 在 App 的「安装」页核对环境，导出配套后端 ZIP，交给 Magisk 安装。
3. 在 LSPosed 类框架中启用墨控，勾选系统框架、系统界面、原厂桌面三个作用域。
4. 完整重启，在「诊断」页确认就绪，再逐项开启需要的功能。

仅适配 `HLTE203T` / Android 10 / `arm64-v8a`，固件 `L1732.6.14.01.B10`；依赖原厂桌面、主题与显示后端的内部实现，**其他机型、系统版本或改版 ROM 很可能不适配**。无 Root 也可浏览说明与预览。发行签名固定之后不能覆盖安装旧签名的 App；首次迁移需先备份配置并卸载旧版。[详细步骤与恢复](docs/wiki/getting-started.md)。

## 界面一览

<table><tr><th>功能首页</th><th>模块设置</th><th>桌面快捷方式</th></tr><tr><td><img src="docs/media/ui-home.png" alt="功能首页" width="260"></td><td><img src="docs/media/ui-settings.png" alt="模块设置" width="260"></td><td><img src="docs/media/ui-shortcuts.png" alt="桌面快捷方式" width="260"></td></tr></table>

<table><tr><th>E-Ink 快捷面板</th><th>快捷面板按钮</th></tr><tr><td><img src="docs/media/ui-panel.png" alt="E-Ink 快捷面板：亮度、当前显示模式，底部五格 WiFi、蓝牙、自动刷新、翻页动画、截屏便签" width="300"></td><td><img src="docs/media/ui-panel-buttons.png" alt="快捷面板按钮页：按钮顺序五行（各带拖动把手与更换）与两个页内开关" width="230"></td></tr></table>

<table><tr><th>翻页动画</th><th>目标阅读器</th></tr><tr><td><img src="docs/media/ui-pageanim.png" alt="翻页动画设置页：总开关，以及风格、速度、分块、间隔四个下拉" width="230"></td><td><img src="docs/media/ui-pageanim-readers.png" alt="目标阅读器选择弹窗：搜索应用并勾选" width="230"></td></tr></table>

截图来源、尺寸与 SHA256 见 [docs/media/README.md](docs/media/README.md)。英文翻译已停止维护，界面部分文字仍会显示中文。

## 开发者与 Agent

先读 [AGENTS.md](AGENTS.md) 和 [Agent 手册](docs/wiki/agent-handbook.md)；[llms.txt](llms.txt) 是精简入口，[知识索引](docs/wiki/knowledge-index.json) 是结构化导航。[参与开发](CONTRIBUTING.md) · [架构](docs/wiki/architecture.md) · [文档维护](docs/localization.md) · [公开前审查](docs/publication.md)。

功能目录是唯一配置定义，界面只调用应用服务。原创实现与标识采用 [MIT](LICENSE)；厂商二进制、第三方素材及外部框架适用各自权利，见 [NOTICE](NOTICE.md)。私钥、密码、明文固件、个人备份和原始设备日志不入 Git。

## 风险提示与免责声明

本项目仍处于实验与候选验证阶段，**建议仅用于备用机或专用测试机，不要用于承担通信、支付或重要数据存储的主力机**。构建与静态检查通过不等于完成实机稳定性验收，也不保证兼容其他设备或固件。

Root、Magisk 模块、系统框架钩子及显示后端改动可能造成死机、反复重启、**无法开机、数据丢失或设备损坏，部分故障可能无法恢复**；停用、卸载和回退机制不保证能救回设备或数据。

**安装、升级或启用功能前，先备份照片、文档、聊天记录、应用数据和重要配置，并把备份复制到电脑或其他独立存储、确认能读取，不要只留在手机上。** 提前准备与当前固件匹配的恢复方式；不清楚如何停用模块时请先不要尝试。项目按原样提供，不承诺无故障、无数据损失或可恢复；备份只能降低风险，不能保证设备可修复。无担保说明见 [LICENSE](LICENSE)。
