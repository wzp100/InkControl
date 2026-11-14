# 墨水屏锁屏截图工具（eink_lock_capture.py）

一句话：**在电脑上截取海信 A5 Pro 墨水屏的锁屏画面**（横竖版式都支持），
产出实际送屏位图 + 可选的横版阅读图。

## 为什么需要这个工具

墨水屏锁屏是「息屏海报」：息屏后 SystemUI 才把锁屏画成位图送墨水屏，
此时锁屏已离开前台，`adb shell screencap` 只能截到亮屏后的界面，**截不到锁屏**。
墨控模块在 SystemUI 侧钩住原厂 `Util.poster(bitmap, mode)`，开启调试开关
`einklyric_debug_capture=1` 后会把**实际送屏的位图**原样写到
`/data/user_de/0/com.android.systemui/cache/einklock-posted.png`。
本工具就是「开开关 → 息屏触发 → 取出位图」的封装，所见即墨水屏上所得。

## 前置条件

1. 电脑有 adb（PATH 里，或用环境变量 `ADB` 指定）；
2. 设备已 root（Magisk）且 adb 获得过 su 授权（首次弹框点允许）；
3. 设备装有墨控模块、LSPosed 已启用，作用域含 `com.android.systemui`；
4. 锁屏用的是墨控自定义样式（原厂样式不走模块路径，取到的是原厂海报也可用）；
5. `--rotate` 需要本机有 JDK 11+（会自动探测 `C:\Paths\java_jdk\jdk17` 等）。

## 用法

```bash
# 最简：自动选唯一在线设备，存到当前目录 einklock-<时间戳>.png
python tools/eink_lock_capture.py

# 指定设备（无线/序列号均可）并输出横版阅读图
python tools/eink_lock_capture.py -s <IP>:5555 -o shot.png --rotate

# 息屏后等 20s 再取（海报出得慢时）
python tools/eink_lock_capture.py --wait 20

# 保留调试开关不清除（连续调试时）
python tools/eink_lock_capture.py --keep
```

参数：

| 参数 | 说明 |
| --- | --- |
| `-s / --serial` | adb 设备序列号或 `IP:端口`，默认唯一在线设备 |
| `-o / --output` | 输出 PNG 路径，默认 `./einklock-<时间戳>.png` |
| `--rotate` | 额外产出 `*-read.png`（逆时针旋转 90° 的横版阅读图） |
| `--wait` | 息屏后等待秒数，默认 12 |
| `--keep` | 不恢复 `einklyric_debug_capture=0` |

## 输出说明

- 原图恒为 **720×1440 竖图**（OEM 固定送屏尺寸，设备是否横屏都一样）；
- 横版锁屏样式（如「编辑排版-横」）的画面是旋转 90° 后铺满竖面板的，
  用 `--rotate` 得到的 `*-read.png` 才是正常阅读方向；
- 工具结束会**自动关闭**调试捕获开关，设备保持息屏——按一下电源键即可看到锁屏。

## 常见问题

- **拉取失败 / 提示确认模块 root**：设备没 root、su 未授权，或锁屏不是墨控自定义样式。
- **图是旧的**：海报在息屏后约 0.5s 才画出，工具默认等 12s；更慢可 `--wait 20`。
- **只想看不想存文件**：跑完后直接按电源键看设备屏幕即可，工具不改动锁屏本身。
- **Git Bash 路径问题**：本工具用参数列表调 adb，不受 MSYS 路径改写影响。
