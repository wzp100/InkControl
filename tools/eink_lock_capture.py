#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""墨水屏锁屏截图工具（墨控 InkControl 专用）。

原理：海信 A5 Pro 的墨水屏锁屏是「息屏海报」——息屏后 SystemUI 才把锁屏 View
画成位图送墨水屏，此时锁屏已离开前台，`adb shell screencap` 只能截到亮屏后的
界面，截不到锁屏。墨控模块在 SystemUI 侧钩住了原厂 Util.poster(bitmap, mode)，
开启调试开关 `einklyric_debug_capture=1` 后，模块会把实际送屏的位图原样写到
SystemUI 缓存（设备保护存储 /data/user_de/0/com.android.systemui/cache/
einklock-posted.png）。本工具就是「开开关 → 息屏触发 → 取出该位图」的封装。

送屏位图恒为 720x1440 竖图（OEM 固定，设备是否横屏都一样）。横版锁屏样式
（如「编辑排版-横」）的图需旋转 90° 阅读：`--rotate` 会额外产出一张
*-read.png（逆时针旋转 90° 后的横版阅读图）。

用法：
    python eink_lock_capture.py                      # 自动选设备，存到 ./einklock-<时间戳>.png
    python eink_lock_capture.py -s <IP>:5555 # 指定设备（无线/序列号均可）
    python eink_lock_capture.py -o out.png --rotate  # 指定输出并旋转出阅读图
    python eink_lock_capture.py --wait 15            # 息屏后多等一会儿（默认 12s）

前置条件：
    1. adb 可用（PATH 里，或用环境变量 ADB 指定）；
    2. 设备已 root（Magisk），adb 有 su 授权（首次会在手机上弹框）；
    3. 设备装有墨控模块且 LSPosed 已启用（钩子挂在 SystemUI 进程）。

注意：
    - 工具运行结束后设备保持息屏——按一下电源键即可看到锁屏海报；
    - 默认会自动关闭调试捕获开关（einklyric_debug_capture=0），加 --keep 可保留；
    - 旋转功能需要本机有 JDK（11+，单文件源码直跑），找不到 JDK 时只出原图。
"""
import argparse
import shutil
import subprocess
import sys
import os
import tempfile
import time
from datetime import datetime
from pathlib import Path

# adb 与 java 都只从环境变量和 PATH 里找，脚本里不写本机路径。
ADB_CANDIDATES = [c for c in (os.environ.get("ADB"), "adb") if c]
JDK_CANDIDATES = [c for c in (
    os.path.join(os.environ["JAVA_HOME"], "bin", "java") if os.environ.get("JAVA_HOME") else None,
    "java") if c]
REMOTE_RAW = "/data/user_de/0/com.android.systemui/cache/einklock-posted.png"
# su 看不到 /storage/emulated/0（FUSE 隔离），中转用 /data/local/tmp
REMOTE_STAGE = "/data/local/tmp/einklock-capture.png"

# 逆时针旋转 90° 的单文件 Java 程序（JDK 11+ 可直接 `java Rot.java in out` 运行）
ROT_JAVA = r"""
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

public class Rot {
    public static void main(String[] args) throws Exception {
        BufferedImage in = ImageIO.read(new File(args[0]));
        int w = in.getWidth(), h = in.getHeight();
        BufferedImage out = new BufferedImage(h, w, BufferedImage.TYPE_INT_RGB);
        // 逆时针旋转 90°：out(x,y) = in(w-1-y, x)
        for (int y = 0; y < w; y++) {
            for (int x = 0; x < h; x++) {
                out.setRGB(x, y, in.getRGB(w - 1 - y, x));
            }
        }
        ImageIO.write(out, "png", new File(args[1]));
        System.out.println("rotated " + w + "x" + h + " -> " + h + "x" + w);
    }
}
"""


def find_exe(candidates):
    for c in candidates:
        if Path(c).is_absolute():
            if Path(c).exists():
                return c
        else:
            found = shutil.which(c)
            if found:
                return found
    return None


def run(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, text=True, **kw)


def pick_device(adb, serial):
    if serial:
        return serial
    r = run([adb, "devices"])
    online = []
    for line in r.stdout.splitlines()[1:]:
        parts = line.split()
        if len(parts) == 2 and parts[1] == "device":
            online.append(parts[0])
    if not online:
        sys.exit("没有在线设备。请连接设备（USB 或无线 adb connect）后重试。")
    if len(online) > 1:
        sys.exit("有多台设备，请用 -s 指定：\n  " + "\n  ".join(online))
    return online[0]


def sh(adb, serial, *args):
    r = run([adb, "-s", serial, "shell", *args])
    if r.returncode != 0:
        sys.exit("adb shell 命令失败: %s\n%s" % (" ".join(args), r.stderr))
    return r.stdout.strip()


def su_sh(adb, serial, script):
    # adb shell 会把参数用空格拼回一条命令交给设备的 sh，`su -c` 后面的脚本必须自带引号，
    # 否则分号之后的部分会退回普通 shell 用户执行（读不到 SystemUI 的 cache，Permission denied）。
    return sh(adb, serial, "su -c '%s'" % script.replace("'", "'\\''"))


def rotate_with_jdk(png_path):
    """用 JDK 把竖图旋转成横版阅读图，返回新图路径；无 JDK 返回 None。"""
    java = find_exe(JDK_CANDIDATES)
    if not java:
        return None
    out = png_path.with_name(png_path.stem + "-read.png")
    with tempfile.TemporaryDirectory() as td:
        rot = Path(td) / "Rot.java"
        rot.write_text(ROT_JAVA, encoding="utf-8")
        r = run([java, str(rot), str(png_path), str(out)])
        if r.returncode != 0:
            print("旋转失败（JDK 单文件运行报错）：%s" % r.stderr.strip()[:300])
            return None
    return out if out.exists() else None


def main():
    ap = argparse.ArgumentParser(
        description="墨水屏锁屏截图（通过墨控模块的送屏位图捕获，screencap 无法截到息屏锁屏）")
    ap.add_argument("-s", "--serial", help="adb 设备序列号或 IP:端口，默认唯一在线设备")
    ap.add_argument("-o", "--output", help="输出 PNG 路径，默认 ./einklock-<时间戳>.png")
    ap.add_argument("--rotate", action="store_true",
                    help="额外产出旋转 90° 后的横版阅读图（*-read.png）")
    ap.add_argument("--wait", type=float, default=12.0,
                    help="息屏后等待秒数，等锁屏位图画出（默认 12）")
    ap.add_argument("--keep", action="store_true",
                    help="保留调试捕获开关不清除（默认恢复为 0）")
    args = ap.parse_args()

    adb = find_exe(ADB_CANDIDATES)
    if not adb:
        sys.exit("找不到 adb。请安装 platform-tools 或把 adb 加入 PATH。")
    serial = pick_device(adb, args.serial)

    out = Path(args.output) if args.output else Path(
        "einklock-%s.png" % datetime.now().strftime("%Y%m%d-%H%M%S"))
    out = out.resolve()

    print("设备 %s：开启调试捕获开关…" % serial)
    sh(adb, serial, "settings", "put", "global", "einklyric_debug_capture", "1")

    print("亮屏 → 息屏，触发锁屏海报（等待 %.0fs）…" % args.wait)
    sh(adb, serial, "input", "keyevent", "224")   # WAKEUP
    time.sleep(2)
    sh(adb, serial, "input", "keyevent", "26")    # SLEEP
    time.sleep(args.wait)

    print("取出送屏位图…")
    # 用 cat 重定向而不是 cp：cp 会照抄源文件 600 的权限，adb（shell 用户）就拉不下来；
    # 重定向按 umask 生成 644。chmod 在 /data/local/tmp 的某些域下还会报 Operation not permitted。
    su_sh(adb, serial,
          "rm -f %s; cat %s > %s" % (REMOTE_STAGE, REMOTE_RAW, REMOTE_STAGE))
    r = run([adb, "-s", serial, "pull", REMOTE_STAGE, str(out)])
    if r.returncode != 0 or not out.exists():
        sys.exit("拉取失败：%s\n请确认：模块已启用、设备已 root、锁屏样式为墨控自定义样式。" % r.stderr)
    su_sh(adb, serial, "rm -f %s" % REMOTE_STAGE)
    print("已保存：%s（%dx%d 竖图）" % (out, 720, 1440))

    if args.rotate:
        read = rotate_with_jdk(out)
        if read:
            print("阅读图：%s（横版方向）" % read)
        else:
            print("未找到本机 JDK，已跳过旋转。可稍后手动旋转查看。")

    if not args.keep:
        sh(adb, serial, "settings", "put", "global", "einklyric_debug_capture", "0")
        print("调试捕获开关已恢复为 0")
    print("完成。按电源键即可在设备上看到该锁屏。")


if __name__ == "__main__":
    main()
