#!/usr/bin/env python3
"""无线 adb 安全重启：先唤醒、再发 reboot，用 boot_id/uptime 有界判定，卡住自动补唤醒重试。

为什么需要它
------------
海信 A5 Pro（HLTE203T）在**息屏 + 未充电**时，展锐平台的 `sprd_pm_thread`
会带着 `PowerManager.SuspendLockout` 这个活跃唤醒源在 suspend/resume 之间反复打转
（`dmesg` 里是 `PM: has wakeup events in progress`）。这时通过无线 adb 发下去的
`reboot` 会被排在电源管理状态机后面，设备仍在线、`sys.boot_completed` 仍是 1、
`/proc/uptime` 也没归零 —— 看起来像“重启完成了”，实际命令一直挂着，
直到有人按一次电源键产生真实唤醒事件才真正重启。无人值守的自动化会卡在等待里。

这个脚本做三件事：
1. 发 reboot **之前**先 `KEYCODE_WAKE` 唤醒屏幕，并尝试让屏幕保持唤醒；
2. 用 `boot_id` / `uptime` 判定是否真的重启过，绝不只看 `boot_completed`；
3. 超时判定为「卡住」时，自动补一次唤醒 + 重发 reboot；期间只要设备掉过线，
   就额外再等一段开机时间（无线 adb 常常比开机慢 30 秒以上）。
4. 两轮都没确认时**再做一次最终复核**：设备确实重启过、只是无线 adb 回来得晚，
   仍按成功返回，避免把「已完成」误报成「需要人工按键」。

用法
----
    python tools/reboot_safe.py --dry-run          # 只打印电源状态与探针，不重启
    python tools/reboot_safe.py                   # 唤醒 → 重启 → 等到开机完成
    python tools/reboot_safe.py --timeout 45      # 每轮等待秒数（默认 60）
    INK_DEVICE=192.168.2.25:5555 python tools/reboot_safe.py

退出码：0 = 已确认重启并开机（含「最终复核」的延迟确认）；
        2 = 两轮都没重启（需人工按一次电源键）；3 = 找不到设备。
"""

import argparse
import os
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import adb_target  # noqa: E402  设备选择规则见 adb_target.py

ADB = adb_target.ADB
BOOT_ID = "/proc/sys/kernel/random/boot_id"


def call(args, timeout=12):
    """调用 adb；超时/异常一律返回空串，绝不把自动化拖住。"""
    try:
        result = subprocess.run([ADB] + args, capture_output=True, timeout=timeout)
    except (subprocess.TimeoutExpired, OSError):
        return ""
    return result.stdout.decode("utf-8", "replace").strip()


def device_call(args, timeout=12):
    return call(["-s", SERIAL] + args, timeout)


def shell(*args, timeout=12):
    return device_call(["shell", *args], timeout)


def connect():
    if ":" in SERIAL:
        # adb server 会把重启前的 TCP 会话缓存成 offline；先断开再连，
        # 否则 "already connected" 会让我们一直读到旧会话的空响应。
        if state() != "device":
            call(["disconnect", SERIAL], timeout=8)
        call(["connect", SERIAL], timeout=25)


def boot_id():
    return shell("cat " + BOOT_ID)


def uptime():
    try:
        return float(shell("cat /proc/uptime").split()[0])
    except (ValueError, IndexError):
        return -1.0


def state():
    return device_call(["get-state"], timeout=6)


def diagnostics():
    power = shell("dumpsys power", timeout=20)
    wake = "?"
    for line in power.splitlines():
        if "mWakefulness=" in line:
            wake = line.strip()
            break
    return {
        "wakefulness": wake,
        "stay_on": shell("settings get global stay_on_while_plugged_in"),
        "screen_off_timeout": shell("settings get system screen_off_timeout"),
        "battery": " / ".join(x.strip() for x in shell("dumpsys battery").splitlines()
                              if "status:" in x or "level:" in x),
        "boot_id": boot_id(),
        "uptime": uptime(),
        "boot_completed": shell("getprop sys.boot_completed"),
    }


def wake():
    """唤醒屏幕；`svc power stayon` 只在充电时有效，失败不影响后续流程。"""
    shell("input keyevent 224", timeout=8)          # KEYCODE_WAKE
    shell("svc power stayon true", timeout=8)
    time.sleep(0.5)


def send_reboot():
    """`adb reboot` 本身可能挂起，必须给超时。"""
    call(["-s", SERIAL, "reboot"], timeout=8)


def _rebooted(old_boot, old_uptime):
    """boot_id 变化，或 uptime 明显回退，都算真的重启过。"""
    connect()
    bid = boot_id()
    up = uptime()
    if bid and bid != old_boot:
        return True
    return up > 0 and old_uptime > 0 and up < old_uptime - 5


def wait_reboot(old_boot, old_uptime, timeout, settle=35):
    """返回 'rebooted' / 'timeout'。掉线说明重启正在发生，额外再等一段开机时间。"""
    deadline = time.time() + timeout
    saw_offline = False
    while time.time() < deadline:
        connect()
        bid, up = boot_id(), uptime()
        if bid and bid != old_boot:
            return "rebooted"
        if up > 0 and old_uptime > 0 and up < old_uptime - 5:
            return "rebooted"
        if not bid or up <= 0:
            saw_offline = True
        time.sleep(3)
    # 实测：设备 13:29 就完成了启动，但无线 adb 到 13:31 才恢复；
    # 期间 boot_id() 一直返回空，不能因此判定失败并重发 reboot。
    if saw_offline:
        settle_deadline = time.time() + settle
        while time.time() < settle_deadline:
            if _rebooted(old_boot, old_uptime):
                return "rebooted"
            time.sleep(3)
    return "timeout"


def final_check(old_boot, timeout=25):
    """两轮都超时后的最终复核：设备其实已经重启并开机，只是无线 adb 回来得慢。"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        connect()
        bid = boot_id()
        if bid and bid != old_boot and shell("getprop sys.boot_completed") == "1":
            return True
        time.sleep(3)
    return False


def wait_boot(old_boot, timeout=150):
    deadline = time.time() + timeout
    while time.time() < deadline:
        connect()
        if state() == "device" and shell("getprop sys.boot_completed") == "1":
            bid = boot_id()
            if bid and bid != old_boot:
                return True
        time.sleep(3)
    return False


def main():
    global SERIAL
    try:
        # 让 stdout 与 stderr 的进度按真实顺序出现（无人值守时日志要能读）。
        sys.stdout.reconfigure(line_buffering=True)
    except AttributeError:
        pass
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dry-run", action="store_true", help="只打印诊断，不重启")
    parser.add_argument("--timeout", type=int, default=60, help="每轮等待秒数（默认 60）")
    args = parser.parse_args()

    SERIAL = os.environ.get("INK_DEVICE", "").strip() or adb_target.ensure()
    if not SERIAL:
        print("[!] 没有可用的海信设备", file=sys.stderr)
        return 3
    print("[*] 目标设备 %s" % SERIAL)
    info = diagnostics()
    for key, value in info.items():
        print("    %-18s %s" % (key, value))
    if args.dry_run:
        return 0

    old_boot, old_uptime = info["boot_id"], info["uptime"]
    for attempt in (1, 2):
        # 重发 reboot 之前先确认一次，避免「上一轮其实已重启、只是没检测到」导致二次重启。
        if attempt > 1 and final_check(old_boot, timeout=10):
            print("[+] 延迟确认：上一轮已重启，无需再发 reboot")
            print("[+] 开机完成：boot_id=%s uptime=%.1f" % (boot_id(), uptime()))
            return 0
        print("[*] 第 %d 轮：唤醒屏幕后发送 reboot" % attempt)
        wake()
        send_reboot()
        result = wait_reboot(old_boot, old_uptime, args.timeout)
        if result == "rebooted":
            print("[+] 已确认重启（boot_id 已变化）")
            if wait_boot(old_boot):
                print("[+] 开机完成：boot_id=%s uptime=%.1f" % (boot_id(), uptime()))
                return 0
            print("[!] 已重启，但等待 sys.boot_completed 超时，请手动确认", file=sys.stderr)
            return 2
        print("[!] 第 %d 轮未检测到重启：设备可能停在 suspend，补唤醒后重试" % attempt,
              file=sys.stderr)

    if final_check(old_boot):
        print("[+] 延迟确认：设备已完成重启（无线 adb 恢复较慢）")
        print("[+] 开机完成：boot_id=%s uptime=%.1f" % (boot_id(), uptime()))
        return 0

    print("[!] 两轮都未重启。设备很可能处于 SuspendLockout，需要人工按一次电源键唤醒；"
          "或改用有线 adb（USB adbd 会持有唤醒锁）。脚本已退出，不再等待。", file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main())
