# 源码来源与依赖清单

[English](sources.en.md)

整理日期：2026-10-15。原项目、原始实验结果和输入均保留，未做覆盖或迁移删除。新项目中的 `src/native/backend/overlay/assets` 是整合后权威实现，正常构建不访问旧项目或外部会话目录。

| 实现 | 输入来源 | 整合处理 |
| --- | --- | --- |
| 动画 | 工作区 `过渡动画模块` 最新源码与 10-15 锁／唤醒动画 | 改包名、移除旧页面和 Settings JSON 配置源，接入统一目录，保留几何实现 |
| 锁屏与歌词 | `锁屏歌词模块` 最新源码 | 合并框架入口，刷新、两处歌词、天气和样式独立门控；样式接管采用条件恢复 |
| 桌面 | `原厂桌面主题模块` 最新源码及素材 | 保留三主题，图标／字体／壁纸／组件独立开关，统一 Provider |
| 系统修复 | `墨水屏修复模块` | 各修复独立开关，证书绑定当前构建；视频原因列表增加停用恢复 |
| GPU native | `调度与功耗分析/out/lock-motion-v6/source_snapshot` | 复制准确源码基线，uniform 缓存与取图队列增加独立属性门控；不引入 v17 |
| HWC 派生输入 | `过渡动画模块/out/backend-v0.2-rc3/module/system/vendor/lib64/hw/hwcomposer.ums512.so` | 私有构建输入（加密于 `ci/private-inputs.zip.enc`），严格哈希校验；默认空闲代码恢复为原厂8字节 |
| 原厂 HWC | `墨水屏刷新分析/原版库/hwcomposer.ums512.so.lib64` | 私有构建输入（加密，不以明文入库）；权利归原权利人，见 `NOTICE.md` |
| 条件恢复／守护 | `墨水屏显示套件/module/config_state.sh`、既有 guard 实现 | 独立墨控状态目录和恢复脚本名称，添加重复启停周期管理 |
| 回归测试 | 过渡动画、EinkFix 与显示套件测试 | 调整包名和路径，重新执行；原厂服务由电脑端替身模拟 |

原始文件 SHA256 记录在 `source-provenance.json`；该文件记录迁入前来源，不要求与修改后的同名目标相等。构建清单记录当前权威源码哈希，交付归档另有完整 `source-manifest.json`。迁移中去重掉的目标作为历史记录保留，天气图标当前只保留 `shared/WeatherIcon.java`。

## 外部工具与协议

- OpenJDK/JBR 21.0.8：本次构建编译器；不进入 APK。
- Android Platform 33、Build Tools 35.0.0、NDK r29：固定构建输入；设备 API 29、arm64。正式源码不附 SDK/NDK。
- Xposed legacy API 最低 82：仅本地最小 stubs，运行时由 LSPosed／Vector 提供。未打包框架实现。
- Magisk ZIP 入口来自本工作区显示套件的最小安装入口，转交设备现有 `util_functions.sh`，最低版本检查为 20.4；本轮未在目标 Magisk 中实装。
- Vector CLI：采用已有实际输出的 `modules ls --json` 格式，归一化 PACKAGE/STATUS，不依赖终端表格排版。只有固定模块包名可被 enable/disable。实际 CLI 启停与作用域仍待实机验收。
- Lyric Getter：保留 `cn.lyric.getter.api.data.LyricData` 的既有 Parcel 兼容声明。没有引入在线库或未固定的远程依赖。

## 许可与公开边界

原创部分按 MIT 整理，见根 `LICENSE`。厂商库及 Google 图形不在原创许可范围内，见 `NOTICE.md`。厂商派生的构建输入只以加密形式存放；签名私钥、未加密 HWC 输入、个人备份、固件镜像和设备日志不进入公开源码。正式签名从 rc13 开始固定；素材与厂商库的原有权利不因仓库公开而改变。以 GitHub Release 附件判断实际发布状态。
