#!/system/bin/sh
MODDIR=${0%/*}
. "$MODDIR/lib/common.sh"
mkdir -p "$IC_STATE"
chmod 700 "$IC_STATE"
setprop sys.inkcontrol.ready 0
(cd "$MODDIR" && sha256sum -c checksums.sha256 >/dev/null 2>&1) || { echo '模块文件校验失败' > "$IC_STATE/error"; touch "$MODDIR/skip_mount"; exit 1; }
ic_supported && ic_validate "$IC_CONFIG" || { echo '设备或配置校验失败' > "$IC_STATE/error"; touch "$MODDIR/skip_mount"; exit 1; }
if conflict=$(ic_module_conflict); then echo "冲突模块仍启用：$conflict" > "$IC_STATE/error"; touch "$MODDIR/skip_mount"; exit 1; fi
rm -f "$IC_STATE/error" "$MODDIR/skip_mount"
# 仅删除本模块固定的派生文件，不递归删除目录，不操作原厂分区。
# InkControlGoogleIcon.apk 是已下线的图标覆盖，保留清理以便旧安装升级后自动移除。
for file in system/vendor/lib64/hw/hwcomposer.ums512.so system/vendor/lib64/hw/libeinkgpu.so system/product/overlay/InkControlGoogleIcon.apk; do rm -f "$MODDIR/$file"; done
# 翻页动画由 GPU 显示后端的融合分支承担；没有独立的翻页 HWC。
if ic_enabled display.gpu; then
    current=$(sha256sum "$IC_VENDOR/hwcomposer.ums512.so" | cut -d' ' -f1)
    pending=$(cat "$IC_STATE/pending" 2>/dev/null || echo 0)
    if [ "$current" != "$IC_STOCK" ] || [ "$pending" -ge 2 ] || [ -f "$IC_STATE/disable_hwc" ]; then
        echo '原厂HWC不匹配或启动保护已触发；未挂载显示后端' > "$IC_STATE/error"
    else
        mkdir -p "$MODDIR/system/vendor/lib64/hw"
        variant=hwcomposer.default.so
        ic_enabled display.idle && variant=hwcomposer.idle10.so
        if ! grep -a -q '/vendor/lib64/hw/libeinkgpu.so' "$MODDIR/payload/$variant" || [ ! -f "$MODDIR/payload/libeinkgpu.so" ]; then
            echo 'HWC 输入缺少 libeinkgpu 依赖；未挂载显示后端' > "$IC_STATE/error"
        else
            cp "$MODDIR/payload/$variant" "$MODDIR/system/vendor/lib64/hw/hwcomposer.ums512.so"
            cp "$MODDIR/payload/libeinkgpu.so" "$MODDIR/system/vendor/lib64/hw/libeinkgpu.so"
            chmod 644 "$MODDIR/system/vendor/lib64/hw/"*.so
            chcon u:object_r:vendor_hal_file:s0 "$MODDIR/system/vendor/lib64/hw"
            chcon u:object_r:vendor_file:s0 "$MODDIR/system/vendor/lib64/hw/"*.so
            echo $((pending + 1)) > "$IC_STATE/pending"
        fi
    fi
else
    if [ "$(sha256sum "$IC_VENDOR/hwcomposer.ums512.so" | cut -d' ' -f1)" = "$IC_STOCK" ]; then
        echo 0 > "$IC_STATE/pending"
        rm -f "$IC_STATE/disable_hwc"
    fi
fi
# 波形动画能力必须与“这一开机实际挂载的库”一致，挂载判断之后再下发。
ic_motion_caps || { echo '波形动画能力下发失败' > "$IC_STATE/error"; exit 1; }
if ic_enabled guard.config; then . "$MODDIR/lib/guard.sh"; guard_boot; fi
ic_prepare_restore
cs_prepare_hooks || { echo '恢复入口创建失败' > "$IC_STATE/error"; exit 1; }
if ic_enabled display.apps; then
    rm -f "$CS_DIR/stopped" "$CS_DIR/restore_requested"
    if [ -f "$CS_DIR/apps.tsv" ]; then
        mkdir -p "$CS_DIR/history"
        cp "$CS_DIR/apps.tsv" "$CS_DIR/history/apps-$(date +%s)-$$.tsv" || exit 1
        awk '$4=="owned" || $4=="queued"' "$CS_DIR/apps.tsv" > "$CS_DIR/apps.next" && mv "$CS_DIR/apps.next" "$CS_DIR/apps.tsv"
    fi
    cs_queue_batch 515 521 && cs_apps_boot apply || { echo '每应用模式应用失败' > "$IC_STATE/error"; exit 1; }
else cs_apps_boot restore || { echo '每应用模式恢复失败' > "$IC_STATE/error"; exit 1; }; fi
cp "$IC_CONFIG" "$IC_BOOT"
cat /proc/sys/kernel/random/boot_id > "$IC_STATE/boot_id"
ic_log '启动配置已应用；加载和GPU执行状态仍需运行检查'
