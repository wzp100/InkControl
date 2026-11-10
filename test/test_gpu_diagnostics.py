"""离线执行真实 POSIX shell/service 和 fake logcat；不连接设备，不依赖 Java。"""
from __future__ import annotations

import os
from pathlib import Path
import shlex
import shutil
import subprocess
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parents[1]
LIB = ROOT / "backend/lib/gpu_diagnostics.sh"
SHELL = os.environ.get("INKCONTROL_TEST_SH") or (
    "C:/Program Files/Git/usr/bin/sh.exe" if os.name == "nt" else shutil.which("sh"))
BOOT = "12345678-1234-1234-1234-123456789abc"


def posix(path):
    value = str(Path(path).resolve()).replace("\\", "/")
    return "/" + value[0].lower() + value[2:] if len(value) > 1 and value[1] == ":" else value


def write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8", newline="\n")


class GpuDiagnosticsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="inkcontrol-gpu-")
        self.root = Path(self.tmp.name)
        self.state = self.root / "state"
        self.module = self.root / "module"
        self.bin = self.root / "bin"
        self.boot = self.root / "boot-id"
        self.calls = self.root / "logcat-calls"
        write(self.boot, BOOT + "\n")
        write(self.state / "boot.txt", "display.gpu=1\nguard.config=0\n")
        write(self.state / "configuration.txt", "display.gpu=0\n")
        write(self.state / "runtime.json", '{"helper_loaded":true,"gpu_errors":3}\n')
        write(self.module / "lib/gpu_diagnostics.sh", LIB.read_text(encoding="utf-8"))
        write(self.module / "service.sh", (ROOT / "backend/service.sh").read_text(encoding="utf-8"))
        write(self.module / "lib/common.sh", 'IC_BOOT=$IC_STATE/boot.txt\nic_apply_hot() { :; }\n')
        write(self.module / "lib/guard.sh", "backup_now() { :; }\n")
        # 运行监测只在本次开机确实挂载了 libeinkgpu 时工作；连续回退达到 3 次才触发保护。
        write(self.module / "system/vendor/lib64/hw/libeinkgpu.so", "fake\n")
        self.executable(self.module / "bin/eink_status", """printf '%s\\n' '{"helper_loaded":true,"gpu_errors":3}'
""")
        self.executable(self.bin / "logcat", r"""printf '%s\n' "$*" >> "$FAKE_CALLS"
dump=0; file=
while [ "$#" -gt 0 ]; do
    case "$1" in -d) dump=1;; -f) shift; file=$1;; esac
    shift
done
if [ "$dump" = 1 ]; then
    [ ! -f "$IC_STATE/disable_hwc" ] || echo too-late > "$IC_STATE/order-error"
    [ "${FAKE_MODE:-ok}" != fail ] || exit 1
    if [ "${FAKE_MODE:-ok}" = hang ]; then
        trap '' TERM
        while :; do sleep 0.05; done
    fi
    if [ "${FAKE_MODE:-ok}" = large ]; then
        head -c 1000000 /dev/zero | tr '\000' X
    else
        printf 'main/system/crash: %s\n' "${FAKE_TOKEN:-first}"
    fi
    exit 0
fi
[ "${FAKE_MODE:-ok}" != fail ] || exit 1
printf 'EinkGPU/EGL/libEGL\n' >> "$file"
trap 'exit 0' TERM INT
while :; do sleep 0.05; done
""")
        self.base = f"""IC_STATE={shlex.quote(posix(self.state))}
IC_BOOT="$IC_STATE/boot.txt"
FAKE_BOOT_ID={shlex.quote(posix(self.boot))}
FAKE_CALLS={shlex.quote(posix(self.calls))}
PATH={shlex.quote(posix(self.bin))}:/usr/bin:/bin:$PATH
export IC_STATE IC_BOOT FAKE_CALLS PATH
cat() {{
    if [ "$1" = /proc/sys/kernel/random/boot_id ]; then command cat "$FAKE_BOOT_ID";
    else command cat "$@"; fi
}}
. {shlex.quote(posix(LIB))}
"""

    def executable(self, path, content):
        write(path, "#!/bin/sh\n" + content)
        path.chmod(0o755)

    def run_shell(self, code, expected=0, timeout=None, service=False):
        argv = [SHELL, "-c", self.base + code, posix(self.module / "service.sh")] if service else [SHELL, "-s"]
        result = subprocess.run(argv, input=None if service else self.base + code,
                                capture_output=True, text=True, encoding="utf-8",
                                timeout=timeout or (30 if os.name == "nt" else 12))
        self.assertEqual(result.returncode, expected, result.stdout + result.stderr)
        return result.stdout

    def tearDown(self):
        # 只清理本测试生成且 PID/starttime 同时匹配的后台替身。
        self.run_shell("""for owner in "$IC_STATE"/gpu-diagnostics/*/logger.lock/owner; do
    [ -f "$owner" ] || continue
    read -r p t < "$owner"
    if ic_gpu_owner_alive "$p" "$t"; then kill "$p"; fi
done
""")
        self.tmp.cleanup()

    def wait_calls(self, count=1):
        end = time.monotonic() + 5
        while time.monotonic() < end:
            if self.calls.exists():
                lines = self.calls.read_text(encoding="utf-8").splitlines()
                if len(lines) >= count:
                    return lines
            time.sleep(0.02)
        self.fail("fake logcat 未按时启动")

    def boot_dir(self, boot=BOOT):
        return self.state / "gpu-diagnostics" / boot

    def service(self, mode="ok", loops=1, extra=""):
        return self.run_shell(f"""FAKE_MODE={shlex.quote(mode)}; export FAKE_MODE
getprop() {{ echo 1; }}
pidof() {{ echo $$; }}
setprop() {{ printf '%s %s\\n' "$1" "$2" >> "$IC_STATE/properties"; }}
grep() {{
    # 加载检查读的是 composer 进程的 maps；替身进程里没有真库，这里视为已稳定加载。
    case "$*" in *libeinkgpu.so*/proc/*/maps*) return 0 ;; esac
    command grep "$@"
}}
sleep() {{
    # 只把运行监测循环的 60 秒周期计入轮次；加载检查阶段的短等待直接跳过。
    [ "$1" = 60 ] || return 0
    ticks=$((${{ticks:-0}} + 1))
    [ "$ticks" -lt {loops} ] || touch "${{0%/*}}/disable"
}}
{extra}
. "$0"
""", service=True)

    def test_repeated_and_concurrent_start_has_one_logger(self):
        script = self.base + "ic_gpu_diagnostics_start || :\n"
        procs = [subprocess.Popen([SHELL, "-s"], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                  stderr=subprocess.PIPE, text=True, encoding="utf-8") for _ in range(6)]
        for proc in procs:
            proc.stdin.write(script)
            proc.stdin.close()
        for proc in procs:
            proc.wait(timeout=12)
            self.assertEqual(proc.returncode, 0, proc.stderr.read())
            proc.stdout.close()
            proc.stderr.close()
        self.wait_calls()
        self.run_shell("ic_gpu_diagnostics_start\nic_gpu_diagnostics_start\n")
        self.assertEqual(len(self.wait_calls()), 1)
        owner = (self.boot_dir() / "logger.lock/owner").read_text().split()
        self.assertEqual(len(owner), 2)
        self.assertTrue(all(value.isdecimal() for value in owner))

    def test_rotation_filters_and_buffers_never_clear_logcat(self):
        self.run_shell("ic_gpu_diagnostics_start\n")
        args = shlex.split(self.wait_calls()[0])
        for flag, value in (("-r", "1024"), ("-n", "2"), ("-v", "threadtime")):
            self.assertEqual(args[args.index(flag) + 1], value)
        self.assertEqual([args[i + 1] for i, arg in enumerate(args) if arg == "-b"], ["main", "system", "crash"])
        for tag in ("EinkGPU:V", "EGL:V", "libEGL:V", "*:S"):
            self.assertIn(tag, args)
        self.assertNotIn("-c", args)
        self.assertEqual(args[args.index("-f") + 1], posix(self.boot_dir() / "gpu.log"))

    def test_boot_configuration_not_current_selection_controls_start(self):
        self.run_shell("ic_gpu_diagnostics_start\n")
        self.wait_calls()
        write(self.state / "boot.txt", "display.gpu=0\n")
        write(self.state / "configuration.txt", "display.gpu=1\n")
        self.run_shell("ic_gpu_diagnostics_start\n", expected=1)
        self.assertEqual(len(self.wait_calls()), 1)

    def test_disabled_service_creates_no_diagnostics(self):
        write(self.state / "boot.txt", "display.gpu=0\nguard.config=0\n")
        self.service()
        self.assertFalse((self.state / "gpu-diagnostics").exists())
        self.assertFalse((self.state / "disable_hwc").exists())

    def test_reboot_starts_new_logger_and_retains_old_evidence(self):
        self.service()
        self.wait_calls(2)
        before = (self.boot_dir() / "first-fault/runtime.json").read_bytes()
        new_boot = "abcdefab-1234-1234-1234-123456789abc"
        write(self.boot, new_boot + "\n")
        (self.module / "disable").unlink()
        # 模拟用户确认恢复后重新启用，并由下一次 post-fs-data 清除旧 error。
        (self.state / "error").unlink()
        (self.state / "disable_hwc").unlink()
        self.service()
        self.wait_calls(4)
        self.assertTrue((self.boot_dir(new_boot) / "gpu.log").is_file())
        self.assertEqual((self.boot_dir() / "first-fault/runtime.json").read_bytes(), before)

    def test_first_fault_not_overwritten_and_precedes_protection(self):
        self.service(loops=2)
        lines = self.wait_calls(2)
        fault = self.boot_dir() / "first-fault"
        before = {p.name: p.read_bytes() for p in fault.iterdir()}
        write(self.state / "runtime.json", "changed\n")
        self.run_shell("FAKE_TOKEN=second; export FAKE_TOKEN\nic_gpu_diagnostics_first_fault 99999\n")
        self.assertEqual({p.name: p.read_bytes() for p in fault.iterdir()}, before)
        self.assertIn(b'"gpu_errors":3', before["runtime.json"])
        self.assertIn(b"main/system/crash: first", before["logcat.txt"])
        self.assertRegex(before["identity.txt"].decode(), r"hwc_pid=\d+\nhwc_starttime=\d+")
        self.assertRegex(before["identity.txt"].decode(), r"service_pid=\d+\nservice_starttime=\d+")
        self.assertTrue((self.state / "disable_hwc").is_file())
        self.assertIn("sys.inkcontrol.ready 0", (self.state / "properties").read_text())
        self.assertFalse((self.state / "order-error").exists())
        dumps = [shlex.split(line) for line in lines if "-d " in line]
        self.assertEqual(len(dumps), 1)
        self.assertEqual([dumps[0][i + 1] for i, arg in enumerate(dumps[0]) if arg == "-b"], ["main", "system", "crash"])
        self.assertNotIn("-c", dumps[0])

    def test_logcat_failure_does_not_block_original_protection(self):
        start = time.monotonic()
        self.service(mode="fail")
        # Git/MSYS 在 Windows 创建每个 POSIX 工具进程的开销远高于 Linux。
        self.assertLess(time.monotonic() - start, 20 if os.name == "nt" else 5)
        self.assertTrue((self.state / "disable_hwc").is_file())
        self.assertTrue((self.state / "error").is_file())
        self.assertTrue((self.boot_dir() / "first-fault/attempted").exists())

    def test_hung_logcat_is_killed_before_protection(self):
        start = time.monotonic()
        self.service(mode="hang")
        self.assertLess(time.monotonic() - start, 25 if os.name == "nt" else 7)
        self.assertTrue((self.state / "disable_hwc").is_file())

    def test_unwritable_diagnostics_does_not_block_protection(self):
        write(self.state / "gpu-diagnostics", "occupied by regular file\n")
        self.service()
        self.assertTrue((self.state / "disable_hwc").is_file())
        self.assertFalse(self.calls.exists())

    def test_snapshot_sizes_are_bounded(self):
        write(self.state / "runtime.json", "R" * 100000)
        self.run_shell("FAKE_MODE=large; export FAKE_MODE\nic_gpu_diagnostics_first_fault $$\n")
        fault = self.boot_dir() / "first-fault"
        self.assertEqual((fault / "runtime.json").stat().st_size, 65536)
        self.assertEqual((fault / "logcat.txt").stat().st_size, 786432)

    def test_stale_owner_and_reused_pid_are_recovered_without_killing_pid(self):
        self.run_shell("""ic_gpu_prepare
mkdir "$gpu_dir/logger.lock"
printf '%s 0\n' "$$" > "$gpu_dir/logger.lock/owner"
ic_gpu_diagnostics_start
kill -0 $$
""")
        self.wait_calls()
        self.run_shell("""read -r p t < "$IC_STATE/gpu-diagnostics/""" + BOOT + """/logger.lock/owner"
kill "$p"
for i in 1 2 3 4 5 6 7 8 9 10; do
    ic_gpu_owner_alive "$p" "$t" || break
    sleep 0.1
done
ic_gpu_diagnostics_start
""")
        self.wait_calls(2)

    def test_fast_exit_and_missing_stat_keep_valid_retryable_owner(self):
        self.run_shell("""FAKE_MODE=fail; export FAKE_MODE
ic_gpu_starttime() { :; }
ic_gpu_diagnostics_start
sleep 0.1
ic_gpu_diagnostics_start
sleep 0.1
""")
        self.wait_calls(2)
        owner = (self.boot_dir() / "logger.lock/owner").read_text().split()
        self.assertEqual(owner[1], "0")
        self.assertEqual(len(owner), 2)

    def test_service_retries_failed_logger_on_next_poll(self):
        self.service(mode="fail", loops=2)
        calls = self.wait_calls(3)
        self.assertGreaterEqual(sum("-f " in line for line in calls), 2)

    def test_starttime_parses_comm_with_spaces_and_parentheses(self):
        proc = self.root / "proc"
        write(proc / "123/stat", "123 (a ) tricky name)) " + " ".join(["S"] + ["0"] * 18 + ["54321", "0"]) + "\n")
        out = self.run_shell(f"IC_GPU_PROC={shlex.quote(posix(proc))}\nic_gpu_starttime 123\n")
        self.assertEqual(out.strip(), "54321")

    def test_retention_keeps_current_and_three_newest_managed_boots(self):
        base = self.state / "gpu-diagnostics"
        ids = [f"00000000-0000-0000-0000-{i:012x}" for i in range(7)]
        for index, boot in enumerate(ids):
            directory = base / boot
            write(directory / "managed", "")
            write(directory / "evidence", boot)
            os.utime(directory, (1000 + index, 1000 + index))
        write(base / "foreign/evidence", "keep")
        unmanaged = "ffffffff-0000-0000-0000-000000000000"
        write(base / unmanaged / "evidence", "keep")
        self.run_shell("ic_gpu_diagnostics_start\n")
        self.wait_calls()
        kept = {p.name for p in base.iterdir() if (p / "managed").is_file()}
        self.assertEqual(kept, {BOOT, *ids[-3:]})
        self.assertEqual((base / "foreign/evidence").read_text(), "keep")
        self.assertEqual((base / unmanaged / "evidence").read_text(), "keep")

    def test_invalid_boot_ids_cannot_escape_or_trigger_pruning(self):
        outside = self.root / "outside"
        write(outside / "keep", "untouched")
        for invalid in ("", "../outside", "../../outside", "/tmp/escape", BOOT + "/../outside", BOOT + "\n" + BOOT, "*", "-rf"):
            with self.subTest(boot_id=invalid):
                write(self.boot, invalid + "\n")
                self.run_shell("ic_gpu_diagnostics_start\n", expected=1)
                self.assertFalse((self.state / "gpu-diagnostics").exists())
        self.assertEqual((outside / "keep").read_text(), "untouched")

    def symlink(self, target, path):
        try:
            path.symlink_to(target, target_is_directory=target.is_dir())
        except OSError as exc:
            self.skipTest(f"本机无符号链接权限，可在 Linux/WSL 运行此情景：{exc}")

    def test_root_symlink_is_refused(self):
        outside = self.root / "outside"
        write(outside / "keep", "untouched")
        self.symlink(outside, self.state / "gpu-diagnostics")
        self.run_shell("ic_gpu_diagnostics_start\n", expected=1)
        self.assertEqual({p.name for p in outside.iterdir()}, {"keep"})

    def test_boot_symlink_is_not_followed_or_pruned(self):
        outside = self.root / "outside"
        write(outside / "managed", "")
        write(outside / "keep", "untouched")
        base = self.state / "gpu-diagnostics"
        base.mkdir()
        self.symlink(outside, base / BOOT)
        self.run_shell("ic_gpu_diagnostics_start\n", expected=1)
        write(self.boot, "abcdefab-1234-1234-1234-123456789abc\n")
        self.run_shell("ic_gpu_diagnostics_start\n")
        self.assertEqual((outside / "keep").read_text(), "untouched")
        self.assertTrue((base / BOOT).is_symlink())

    def test_logfile_and_fault_symlinks_do_not_overwrite_external_data(self):
        outside = self.root / "outside"
        write(outside / "keep", "untouched")
        self.boot_dir().mkdir(parents=True)
        self.symlink(outside / "keep", self.boot_dir() / "gpu.log")
        self.symlink(outside, self.boot_dir() / "first-fault")
        self.run_shell("ic_gpu_diagnostics_start\n", expected=1)
        self.run_shell("ic_gpu_diagnostics_first_fault $$\n")
        self.assertEqual({p.name for p in outside.iterdir()}, {"keep"})
        self.assertEqual((outside / "keep").read_text(), "untouched")
        self.assertFalse(self.calls.exists())

    def test_shell_utf8_lf_syntax_and_start_before_monitoring(self):
        for path in (LIB, ROOT / "backend/service.sh"):
            data = path.read_bytes()
            data.decode("utf-8")
            self.assertNotIn(b"\r", data)
            self.assertFalse(data.startswith(b"\xef\xbb\xbf"))
            result = subprocess.run([SHELL, "-n", posix(path)], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
        service = (ROOT / "backend/service.sh").read_text(encoding="utf-8")
        self.assertLess(service.index("ic_gpu_diagnostics_start"), service.index("sys.boot_completed"))


if __name__ == "__main__":
    unittest.main()
