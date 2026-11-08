#!/system/bin/sh
MODDIR=${0%/*}
. "$MODDIR/lib/common.sh"
. "$MODDIR/lib/gpu_diagnostics.sh"
# 开机等待、加载检查及运行监测之前开始留存；取证失败不能阻断原有保护。
ic_gpu_diagnostics_start 2>/dev/null || :
[ -f "$IC_STATE/error" ] && exit 1
# 热应用要调用 settings 等系统服务；late_start 时它们可能还没起来，先等开机完成（最多 3 分钟）。
for attempt in $(seq 1 90); do [ "$(getprop sys.boot_completed)" = 1 ] && break; sleep 2; done
if [ "$(getprop sys.boot_completed)" != 1 ]; then
    echo '系统未在 3 分钟内完成启动；已停用显示后端并重启恢复' > "$IC_STATE/error"
    touch "$IC_STATE/disable_hwc" "$MODDIR/disable"
    sync
    reboot
    exit 1
fi
ic_apply_hot || { echo '启动期配置应用失败' > "$IC_STATE/error"; exit 1; }
# 翻页动画常驻端：读 settings.conf、监听触摸/按键、把翻页事件写进 HWC 注入段。
# 只在本次开机确实挂了翻页动画 HWC 时拉起；GPU 后端融合模式下这份注入段不存在，
# 拉起只会空转并污染日志。一个实例由它自己的 resident.lock 保证。
if [ "$(awk -F= '$1=="pageanim.master" {print $2}' "$IC_BOOT")" = 1 ] && [ ! -f "$MODDIR/system/vendor/lib64/hw/libeinkgpu.so" ] && [ -f "$MODDIR/payload/hwcomposer.pageanim.so" ]; then
    "$MODDIR/bin/a5pro-resident" </dev/null >>"$IC_STATE/pageanim/resident.log" 2>&1 &
fi
if [ -f "$MODDIR/system/vendor/lib64/hw/libeinkgpu.so" ]; then
    for attempt in $(seq 1 90); do
        pid=$(pidof android.hardware.graphics.composer@2.1-service | awk '{print $1}')
        if [ -n "$pid" ] && [ "$pid" = "$last" ]; then stable=$((stable + 1)); else last=$pid; stable=0; fi
        if [ "$stable" -ge 20 ]; then
            if grep -q libeinkgpu.so "/proc/$pid/maps"; then echo 0 > "$IC_STATE/pending"; break; fi
        fi
        sleep 2
    done
    if [ "$stable" -lt 20 ] || ! grep -q libeinkgpu.so "/proc/$pid/maps"; then
        ic_gpu_diagnostics_first_fault "$pid" 2>/dev/null || :
        echo '显示服务未稳定加载；已停用，完整重启返回原厂' > "$IC_STATE/error"
        touch "$IC_STATE/disable_hwc" "$MODDIR/disable"
    fi
fi
. "$MODDIR/lib/guard.sh"
next_backup=0
while [ ! -f "$MODDIR/disable" ] && [ ! -f "$MODDIR/remove" ]; do
    # 复用 Magisk 启动服务；没有额外 Android 常驻服务。始终读取本次启动配置。
    if [ "$(awk -F= '$1=="display.gpu" {print $2}' "$IC_BOOT")" = 1 ] && [ -f "$MODDIR/system/vendor/lib64/hw/libeinkgpu.so" ]; then
        ic_gpu_diagnostics_start 2>/dev/null || :
        pid=$(pidof android.hardware.graphics.composer@2.1-service | awk '{print $1}')
        if [ -n "$pid" ]; then
            "$MODDIR/bin/eink_status" status "$pid" > "$IC_STATE/runtime.tmp" 2>/dev/null && mv "$IC_STATE/runtime.tmp" "$IC_STATE/runtime.json"
            errors=$(sed -n 's/.*"gpu_errors":\([0-9][0-9]*\).*/\1/p' "$IC_STATE/runtime.json")
            if ! grep -q '"helper_loaded":true' "$IC_STATE/runtime.json" || [ "${errors:-0}" -ge 3 ]; then
                ic_gpu_diagnostics_first_fault "$pid" 2>/dev/null || :
                echo '运行监测发现显示后端异常；已禁止下一次加载，完整重启回到原厂' > "$IC_STATE/error"
                touch "$IC_STATE/disable_hwc"
                setprop sys.inkcontrol.ready 0
            fi
        fi
    fi
    # 无障碍服务自愈：清理工具、系统或手动操作把「已启用服务」改掉后，下个周期补回来。
    # ic_accessibility 幂等——状态已经对时只读一次设置，不写盘；开关本身关着则不会擅自加。
    ic_accessibility 2>/dev/null || :
    now=$(date +%s)
    if [ "$(awk -F= '$1=="guard.config" {print $2}' "$IC_BOOT")" = 1 ] && [ "$now" -ge "$next_backup" ]; then backup_now; next_backup=$((now + 21600)); fi
    sleep 60
done
