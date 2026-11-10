#!/usr/bin/env python3
"""海信 A5 Pro（HLTE203T）锁屏 PIN 自动输入。

仓库里不保存任何 PIN：密码只由使用者在运行时通过 --pin 或环境变量
INK_UNLOCK_PIN 提供，脚本也不会把输入的数字回显到日志。

为什么需要它
------------
这台机器的锁屏是 AOSP 标准 KeyguardPINView（4 行 x 3 列 NumPadKey 按钮），
不是输入框，所以 `input text` 和 `input keyevent` 数字键都进不去；
uiautomator 在这台机器上静默失败，锁屏截图又是全白（墨水屏由 EPD 直接驱动），
因此唯一可行的是「按真实坐标 input tap」。

坐标来源
--------
2026-10-25 用 getevent -lt /dev/input/event2 录到一次 6 位数字 PIN 的六个
真实触点（坐标只与按键位置有关，与密码内容无关），再用「同键两次点击」互相校验，反推整个网格：

    列中心 x = 182 / 360 / 538   （列宽约 178px，numpad 宽 534 居中）
    行中心 y = 920 / 1047 / 1174 / 1301

前两行来自实测（1/2/4/5/6 中的 2、4、5、6），后两行是等间距线性外推，
未经真机点击验证；只用前两行数字键时坐标是实测安全的。

用法
----
    python tools/unlock_device.py state     # 打印锁屏/解锁状态
    python tools/unlock_device.py grid      # 打印坐标网格
    python tools/unlock_device.py unlock    # 息屏唤醒并输入 PIN
    python tools/unlock_device.py unlock --pin "$INK_UNLOCK_PIN" --wait 1.5
"""

import argparse
import os
import re
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import adb_target  # noqa: E402  设备选择规则见 adb_target.py

ADB = adb_target.ADB
# 空 = 自动选择（有线海信 > 无线 192.168.2.25 > 任意无线海信）
SERIAL = os.environ.get("INK_DEVICE", "").strip()
# 不内置任何真实 PIN；由 --pin 或环境变量 INK_UNLOCK_PIN 提供。
DEFAULT_PIN = os.environ.get("INK_UNLOCK_PIN", "").strip()

# 列中心 / 行中心（像素，屏幕 720x1440，density 320）
COL_X = {"1": 182, "4": 182, "7": 182, "EMERGENCY": 182,
         "2": 360, "5": 360, "8": 360, "0": 360,
         "3": 538, "6": 538, "9": 538, "BACKSPACE": 538}
ROW_Y = (920, 1047, 1174, 1301)

GRID = {
    "1": (COL_X["1"], ROW_Y[0]), "2": (COL_X["2"], ROW_Y[0]), "3": (COL_X["3"], ROW_Y[0]),
    "4": (COL_X["4"], ROW_Y[1]), "5": (COL_X["5"], ROW_Y[1]), "6": (COL_X["6"], ROW_Y[1]),
    "7": (COL_X["7"], ROW_Y[2]), "8": (COL_X["8"], ROW_Y[2]), "9": (COL_X["9"], ROW_Y[2]),
    "0": (COL_X["0"], ROW_Y[3]),
}


def adb(*args, timeout=30):
    if not SERIAL:
        return ""
    cmd = [ADB, "-s", SERIAL, *args]
    try:
        out = subprocess.run(cmd, capture_output=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        return ""
    return out.stdout.decode("utf-8", "replace")


def connect():
    """选目标（有线海信优先，其次无线 192.168.2.25）并确认可用。"""
    global SERIAL
    if not SERIAL:
        SERIAL = adb_target.ensure()
    if not SERIAL:
        print("[!] 没有可用的海信设备（USB 与无线都不可达）")
        return False
    print("[*] 目标设备 %s" % SERIAL)
    return True


def state():
    """返回 'unlocked' / 'locked' / 'asleep'，判断依据是焦点窗口与电源状态。"""
    power = adb("shell", "dumpsys power")
    if "mWakefulness=Asleep" in power or "state=OFF" in power:
        return "asleep"
    window = adb("shell", "dumpsys window")
    focus = ""
    for line in window.splitlines():
        if "mCurrentFocus=" in line:
            focus = line
            break
    if "StatusBar" in focus or "NotificationShade" in focus:
        return "locked"
    if "Launcher" in focus or "Application Not Responding" in focus:
        return "unlocked"
    # 焦点在别的应用上也算已解锁（解锁后打开了某个 app）
    if "Window{" in focus and "StatusBar" not in focus:
        return "unlocked"
    return "locked"


def wake():
    """点亮屏幕。熄屏时先按电源键；已经是亮屏则不动，避免把自己按熄。"""
    if state() == "asleep":
        adb("shell", "input keyevent 224")   # KEYCODE_WAKEUP
        time.sleep(1.2)


def tap(x, y, pause=0.45):
    adb("shell", f"input tap {x} {y}")
    time.sleep(pause)


def unlock(pin=DEFAULT_PIN, wait=0.6, force_wake=True):
    if not pin or not pin.isdigit():
        print("[!] 未提供 PIN：设环境变量 INK_UNLOCK_PIN，或传 --pin（纯数字）")
        return False
    current = state()
    if current == "unlocked":
        print("[=] 已经解锁，不需要输入")
        return True

    if force_wake and current == "asleep":
        print("[*] 屏幕熄灭，先唤醒")
        wake()
        time.sleep(wait)

    missing = [d for d in pin if d not in GRID]
    if missing:
        print(f"[!] 不支持的按键：{missing}")
        return False

    print(f"[*] 输入 {len(pin)} 位 PIN")
    for i, digit in enumerate(pin, 1):
        x, y = GRID[digit]
        print(f"    {i}/{len(pin)}  * -> ({x}, {y})")
        tap(x, y, pause=wait)

    time.sleep(1.5)
    after = state()
    if after == "unlocked":
        print("[+] 解锁成功")
        return True

    print(f"[!] 仍未解锁（当前状态 {after}）")
    print("    可能原因：唤醒后停在滑动锁屏而不是密码键盘；或 PIN 有误。")
    return False


def print_grid():
    print("海信 A5 Pro 锁屏 PIN 键盘坐标（720x1440）")
    print("        x=182      x=360      x=538")
    label = [["1", "2", "3"], ["4", "5", "6"], ["7", "8", "9"], ["Emergency", "0", "Del"]]
    for row, y in zip(label, ROW_Y):
        cells = "  ".join(f"{cell:>11}" for cell in row)
        print(f"y={y}  {cells}")
    print()
    for digit in "0123456789":
        x, y = GRID[digit]
        print(f"  {digit}: ({x}, {y})")


def main():
    parser = argparse.ArgumentParser(description="海信 A5 Pro 锁屏 PIN 自动输入")
    parser.add_argument("action", choices=["state", "grid", "unlock"], nargs="?", default="state")
    parser.add_argument("--pin", default=DEFAULT_PIN, help="锁屏 PIN（纯数字）；未提供时读环境变量 INK_UNLOCK_PIN")
    parser.add_argument("--wait", type=float, default=0.6, help="每次点击后的等待秒数")
    args = parser.parse_args()

    if args.action == "grid":
        print_grid()
        return 0

    connect()

    if not SERIAL:
        return 2

    if args.action == "state":
        print(state())
        return 0

    return 0 if unlock(args.pin, args.wait) else 1


if __name__ == "__main__":
    sys.exit(main())
