# tools/ —— 真机排查工具速查

这个目录里主要是构建、测试与发布脚本。下面三个是**连着真机排查时才用**的小工具，
不参与 APK 构建，也不会被打包进去。

| 文件 | 用途 | 说明 |
| --- | --- | --- |
| `adb_target.py` | 设备选择器，下面两个脚本共用 | 见「设备选择」 |
| `unlock_device.py` | 锁屏 PIN 自动输入 | 见「怎么填 PIN」 |
| `eink_lock_capture.py` | 抓墨水屏锁屏的实际送屏位图 | [eink_lock_capture.md](eink_lock_capture.md) |

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
