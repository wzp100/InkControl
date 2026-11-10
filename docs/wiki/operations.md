# 诊断与恢复

[English](operations.en.md) · [Wiki](README.md)

## 先留证，再干预

先记录用户看到的现象、应用/场景、时间、模式与候选版本。获准读取设备后，明确选择设备序列号，保存本次 boot_id、HWC PID/starttime、模块/App 版本、配置修订号、模块哈希和诊断页。原始数据保存在本地实验目录，不上传 Git。

| 现象 | 首先核对 | 不应直接得出的结论 |
| --- | --- | --- |
| 视频慢 | 实际视频标记、算法、GPU/CPU 波次的连续增量、错误计数 | stock 慢就一定在 CPU |
| 开关开了没效果 | 依赖、HOT/REBOOT、本次启动、当前进程回执与修订号 | 文件保存等于生效 |
| 后端已加载仍卡 | 当前 helper、EGL/GL 首错、输入队列、遥测增量 | maps 有库等于 GPU 正在工作 |
| 锁屏歌词细线 | 输入、模式、误差扩散、固定歌词对照和实屏反馈 | 扫描字节相同等于观感问题已解决 |
| 重载系统服务后所有钩子失效 | 新 zygote/system_server/SystemUI PID 与框架注入 | App 仍能打开等于钩子正常 |

## GPU 日志和保护

当前持久取证目录为 `/data/adb/inkcontrol/gpu-diagnostics/<boot_id>/`，由 Magisk 启动服务开启；每个启动最多保留 GPU 日志 1MiB × 3、首故障 logcat 768KiB 等，当前及最近三次历史启动保留。每分钟检查采集进程，按 PID + starttime 去重。

首故障包含当时 runtime、进程身份和系统/主/崩溃日志，后续轮询不覆盖首个现场。旧 `/data/local/tmp/einkgpu.log` 只是早期临时日志，不是当前持久入口。

保护可设置 `/data/adb/inkcontrol/disable_hwc`、error 与 ready=0，阻止下次加载。运行中的 native 还可能锁定 CPU 回退。先复制日志与状态，解释故障类型，再决定恢复；不要先清停用标记或循环重启。日志采集失败不阻断保护。

队列竞态修复是在拿到锁后重查队列、最多重新选择四次；只对尚未发布的选择竞态重试，发布异常不重试。它不能解释所有历史 GPU 错误。GPU 波次与扫描增量只能证明对应工作发生，不能换算面板 FPS。

## 恢复顺序

1. 应用可操作时优先关闭相关增强，保留选择和状态证据；启动期行为需要完整重启后核验。
2. 使用对应候选的停用／卸载恢复入口和 before/written 账本，避免覆盖用户后改的设置。
3. 需要回滚时使用相同实验记录的 APK、模块、输入和备份，核对哈希。旧 rc10 APK 的内嵌后端可能覆盖 GPU 队列修复。
4. HWC/system_server 重载后必须核对当前 PID 注入；历史存在 Vector 重注入失败，完整重启曾恢复，自动自愈仍未完成。

不要盲目卸载顶层挂载、覆盖运行库、套用旧实验脚本的固定基线或把“清保护”当作修复。设备操作须在已授权任务内执行并记录恢复结果。

源码入口：`backend/service.sh`、`backend/lib/gpu_diagnostics.sh`、`native/service_queue_retry.hpp`、`platform/RuntimeStatus.java`。来源：`R03`、`R04`、`R09`。

## 面板开关提示「切换失败」

墨控被系统「强制停止」后，这台机器会禁止 SystemUI 拉起它的 ContentProvider，面板点「自动刷新」「遮挡息屏」等开关会先报目标状态、再报切换失败；日志关键字是 `unable to launch app ... for provider, it's restricted` 与 `Unknown authority com.inkcontrol.state`。这与 App 内开关是否同步无关——配置写在 App 私有目录，只能由墨控自己的进程落盘并调 root。

0.2.5-r23 起 `PanelHook` 在 provider 不可用时启动透明页 `PanelToggleActivity` 拉起墨控进程完成切换（显示约半秒即结束，焦点不离开当前界面），不需要手动打开 App。若仍出现失败提示，先到「诊断」页确认作用域与模块启用状态，再打开一次墨控后重试。
