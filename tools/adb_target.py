#!/usr/bin/env python3
"""海信 A5 Pro 的 adb 目标选择器（tools/ 下所有脚本共用）。

为什么需要它
------------
本机经常同时挂着多个 adb 目标：USB 上可能是别的手机，海信自己可能
有线无线都在线，无线还可能挂过历史连接。不加筛选就会出现
`more than one device/emulator`，或者更糟——命令打到了别的设备上。

选择规则（按优先级，第一条命中即用）
-----------------------------------
1. 环境变量 `INK_DEVICE` 显式指定（原样返回，不做校验）；
2. **有线**上状态为 `device`、且型号是 HLTE203T / 海信 的目标；
3. **无线** `192.168.2.25:5555`（海信静态 IP）且状态为 `device`；
4. 任意状态下为 `device`、型号是 HLTE203T 的无线目标。

即：**同时有线无线在线时优先走有线**；有线上挂的是别的设备时，
自动退回无线；多条无线里固定用 192.168.2.25。

用法
----
    from adb_target import ADB, resolve, ensure
    serial = resolve()          # 只选，不连接
    serial = ensure()           # 选不到无线时主动 adb connect 再选
"""

import os
import re
import subprocess

ADB = os.environ.get("ADB", r"C:\Paths\adbtools-platform-tools-latest-windows\adb.exe")

# 海信的静态 IP（无线调试固定用这个）
WIRELESS = "192.168.2.25:5555"
# 设备型号白名单：HLTE203T = A5 Pro 经典版
MODELS = ("HLTE203T", "A5Pro", "HLTE203")
ENV_OVERRIDE = "INK_DEVICE"


def _run(args, timeout=20):
    try:
        p = subprocess.run([ADB] + args, capture_output=True, timeout=timeout)
    except (subprocess.TimeoutExpired, OSError):
        return ""
    return p.stdout.decode("utf-8", "replace")


def list_devices():
    """返回 [{'serial','state','usb','model'}]。"""
    out = _run(["devices", "-l"])
    items = []
    for line in out.splitlines():
        line = line.strip()
        if not line or line.startswith("List of devices") or line.startswith("*"):
            continue
        parts = line.split()
        if len(parts) < 2:
            continue
        serial, state = parts[0], parts[1]
        model = ""
        m = re.search(r"model:(\S+)", line)
        if m:
            model = m.group(1)
        items.append({
            "serial": serial,
            "state": state,
            "usb": "usb:" in line,
            "model": model,
        })
    return items


def _is_hisense(dev):
    return any(k.upper() in (dev["model"] or "").upper() for k in MODELS)


def resolve():
    """按优先级挑一个 serial；挑不到返回空串。"""
    override = os.environ.get(ENV_OVERRIDE, "").strip()
    if override:
        return override

    devices = [d for d in list_devices() if d["state"] == "device"]
    if not devices:
        return ""

    # 2. 有线 + 海信
    for d in devices:
        if d["usb"] and _is_hisense(d):
            return d["serial"]
    # 3. 无线固定 IP
    for d in devices:
        if d["serial"] == WIRELESS:
            return d["serial"]
    # 4. 任意无线海信
    for d in devices:
        if not d["usb"] and _is_hisense(d):
            return d["serial"]
    return ""


def ensure(connect_retries=3):
    """挑目标；如果没有任何可用目标，先试 adb connect 静态 IP 再挑。"""
    serial = resolve()
    if serial:
        return serial
    for _ in range(connect_retries):
        _run(["connect", WIRELESS], timeout=25)
        serial = resolve()
        if serial:
            return serial
    return ""


def describe():
    """调试用：打印所有目标与最终选择。"""
    rows = list_devices()
    lines = ["serial=%-24s state=%-9s usb=%-5s model=%s" %
             (d["serial"], d["state"], d["usb"], d["model"] or "?") for d in rows]
    chosen = resolve()
    return "\n".join(lines + ["-> 选中: %s" % (chosen or "(无)")])


if __name__ == "__main__":
    print(describe())
