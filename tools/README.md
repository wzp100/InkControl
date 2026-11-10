# tools/ —— 真机排查工具速查

这个目录里主要是构建、测试与发布脚本。下面几个是**连着真机排查时才用**的小工具，
不参与 APK 构建，也不会被打包进去。

| 文件 | 用途 | 说明 |
| --- | --- | --- |
| `adb_target.py` | 设备选择器，下面几个脚本共用 | 见「设备选择」 |
| `unlock_device.py` | 锁屏 PIN 自动输入 | 见「怎么填 PIN」 |
| `eink_lock_capture.py` | 抓墨水屏锁屏的实际送屏位图 | [eink_lock_capture.md](eink_lock_capture.md) |
| `reboot_safe.py` | 无线 adb 安全重启（自动唤醒 + 有界判定） | 见「无线 adb 重启为什么会卡住」 |

## 前置条件

- 电脑有 adb（PATH 里，或脚本顶部列出的 `C:\Paths\adbtools-platform-tools-latest-windows\adb.exe` 等候选路径）。
- 设备已 root（Magisk），adb 拿到过 su 授权（首次会在手机上弹框）。
- 无线调试在设备息屏时会掉线，排查前先确认在线；`svc power stayon true` 可以避免长测试中途熄屏。
- 锁屏截图额外要求墨控模块已注入 SystemUI（LSPosed 作用域含 `com.android.systemui`）。

## 设备选择

`adb_target.py` 按优先顺序挑一台设备：环境变量 `INK_DEVICE` > 有线海信 > 无线 `192.168.2.25:5555` > 任意无线海信；`offline` 的历史连接一律排除，避免命令打到别的手机上。

```bash
python tools/adb_target.py                                   # 打印所有目标与最终选择
INK_DEVICE=192.168.2.25:5555 python tools/unlock_device.py state
```

## 怎么填 PIN（仓库里没有任何真实密码）

`unlock_device.py` **不内置默认 PIN**。请用自己在系统「设置 → 安全 → 屏幕锁定」里设置的**纯数字** PIN，在运行时提供，两种方式：

1. 环境变量（推荐，密码不进命令历史）：

   ```bash
   export INK_UNLOCK_PIN='123456'          # 123456 只是示例，换成自己的纯数字 PIN
   python tools/unlock_device.py unlock --wait 1.5
   ```

   Windows PowerShell：

   ```powershell
   $env:INK_UNLOCK_PIN = '123456'         # 同样换成自己的 PIN
   python tools/unlock_device.py unlock --wait 1.5
   ```

2. 命令行参数（会出现在 shell 历史里，公共机器不建议）：

   ```bash
   python tools/unlock_device.py unlock --pin "$INK_UNLOCK_PIN" --wait 1.5
   ```

要求与行为：

- 只能是数字 `0`–`9`，长度不限（原厂 PIN 通常 4–16 位）；脚本按每一位在锁屏数字键盘上的坐标点按。
- 不提供 PIN 时直接报错退出，不会猜、不会去读某个文件；控制台只打印位数与坐标，**不回显输入了哪些数字**。
- 换 PIN 只要改环境变量或参数，脚本和坐标表都不用动——坐标表记的是按键位置，与密码内容无关。
- 这台机的锁屏是 `NumPadKey` 按钮矩阵，`input text` / `input keyevent` 数字键都无效，uiautomator 也静默失败，只有按真实坐标点按可行。

```bash
python tools/unlock_device.py state    # 只看锁屏/解锁状态，不需要 PIN
python tools/unlock_device.py grid     # 打印数字键盘坐标网格，不需要 PIN
```

## 锁屏截图

`adb shell screencap` 截不到墨水屏的息屏海报，所以走墨控在 SystemUI 里钩住的送屏位图：
开调试开关 → 息屏触发 → 从 SystemUI 缓存取回 PNG；`--rotate` 会额外产出横版阅读图。
完整用法见 [eink_lock_capture.md](eink_lock_capture.md)。

## 无线 adb 重启为什么会卡住

现象：息屏状态下通过无线 adb 发 `adb reboot`，设备**仍在线**、`sys.boot_completed` 仍是 1，
`/proc/uptime` 也没归零，看起来像重启完成了，实际命令一直挂着；按一次电源键后才真正重启。
无人值守的自动化会卡在等待里。

原因（在 HLTE203T 上实测）：设备未充电时 `stay_on_while_plugged_in=7` 不生效，
屏幕超时后息屏，展锐平台的 `sprd_pm_thread` 带着活跃唤醒源
`PowerManager.SuspendLockout` 在 suspend/resume 之间反复打转：

```
PM: has wakeup events in progress:
HSINFO: active wakeup source: PowerManager.SuspendLockout
HSINFO: active wakeup source: PowerManagerService.Display
```

此时 `sys.powerctl=reboot` 的处理排在电源管理状态机后面，直到电源键产生一个真实唤醒事件
才继续。无线 adb 的 WiFi 省电也会让这条链路更容易挂住。

**别用 `sys.boot_completed` 判断重启**，它在卡住时同样是 1。要同时看
`/proc/sys/kernel/random/boot_id` 变化（或 `/proc/uptime` 变小）。
`reboot_safe.py` 就是按这个判定写的：

```bash
python tools/reboot_safe.py --dry-run       # 只看电源状态与探针，不重启
python tools/reboot_safe.py                 # 唤醒 → reboot → 等到开机完成
python tools/reboot_safe.py --timeout 45    # 每轮等待秒数（默认 60）
```

流程：先 `KEYCODE_WAKE` 唤醒屏幕（并尝试 `svc power stayon true`）→ 发 `adb reboot`
（命令本身也带超时，不会把脚本拖死）→ 每 3 秒用 `boot_id` / `uptime` 判定；
超时判为「卡住」就补一次 `KEYCODE_WAKE` 并重发，第二轮仍失败则**明确报错退出**，
绝不无限等待。退出码 0 = 已确认重启并开机，2 = 需要人工按电源键或改用有线 adb。

判定上还有两个容易踩的坑，脚本都已处理：

- **设备掉线 ≠ 没重启。** 开机后无线 adb（靠 `START_ADBD` 恢复 5555 端口）常常比
  `boot_completed` 晚 30 秒以上，期间 `boot_id()` 返回空。只要这一轮里出现过掉线，
  就额外再等一段开机时间；重发 reboot 之前也会先复核一次，避免重复重启。
- **两轮超时后做最终复核。** 曾有实测：脚本报「两轮都未重启」，设备其实早已
  重启完毕（boot_id 已变、uptime 归零），只是无线 adb 回来得晚。现在这种情况
  按成功返回（日志里标「延迟确认」），不会再把已完成误报成需要人工按键。

实测（息屏 + 未充电）：**第一轮仍会卡住，第二轮补唤醒重发后成功**，所以单次 `KEYCODE_WAKE`
并不能保证一次成功，两轮重试是必需的；整个过程不需要人按电源键。

其它规避办法：接 USB 用有线 adb（adbd 会持有唤醒锁）；或让设备保持充电 +
`svc power stayon true`，使屏幕不进入息屏 suspend。
