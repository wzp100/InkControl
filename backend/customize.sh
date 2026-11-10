#!/system/bin/sh
MODDIR=$MODPATH
. "$MODDIR/lib/common.sh"
[ "$ARCH" = arm64 ] && [ "$API" = 29 ] && ic_supported || abort '墨控仅支持 HLTE203T / Android 10 / arm64'
(cd "$MODPATH" && sha256sum -c checksums.sha256 >/dev/null 2>&1) || abort '安装包完整性检查失败'
for hwc in "$MODPATH/payload/hwcomposer.default.so" "$MODPATH/payload/hwcomposer.idle10.so"; do
    grep -a -q '/vendor/lib64/hw/libeinkgpu.so' "$hwc" || abort 'HWC 输入缺少 libeinkgpu 依赖，拒绝安装'
done
[ -f "$MODPATH/payload/libeinkgpu.so" ] || abort '缺少 libeinkgpu.so，拒绝安装'
set_perm_recursive "$MODPATH" 0 0 0755 0644
for script in "$MODPATH"/*.sh "$MODPATH"/lib/*.sh "$MODPATH"/bin/*; do set_perm "$script" 0 0 0755; done
mkdir -p "$IC_STATE"
chmod 700 "$IC_STATE"
rm -f "$MODPATH/disable" "$MODPATH/remove" "$IC_STATE/error" "$IC_STATE/disable_hwc" "$IC_STATE/pending" "$IC_STATE/runtime.json"
[ -f "$IC_CONFIG" ] || cp "$MODPATH/lib/defaults.txt" "$IC_CONFIG"
ic_validate "$IC_CONFIG" || abort '配置无效，保留原文件；请先在墨控中检查'
ui_print '墨控后端已准备；全部增强默认关闭。'
ui_print '请在框架中启用墨控并勾选作用域，然后完整重启。'
