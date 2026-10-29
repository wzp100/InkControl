#!/system/bin/sh
# 此文件只读取固定格式配置，不source用户内容，不接受任意命令。
IC_STATE=${IC_STATE:-/data/adb/inkcontrol}
IC_MODULE=${IC_MODULE:-/data/adb/modules/inkcontrol_backend}
IC_VENDOR=${IC_VENDOR:-/vendor/lib64/hw}
IC_STOCK=d1ba01bf135151580c41dc7de13dd1c80876094c8022f58fe9ceecd0c9fb0988
IC_CONFIG=$IC_STATE/configuration.txt
IC_APPLIED=$IC_STATE/applied.txt
IC_BOOT=$IC_STATE/boot.txt
IC_LIB=${IC_LIB:-$MODDIR/lib}

ic_log() { mkdir -p "$IC_STATE"; printf '%s %s\n' "$(date '+%F %T')" "$*" >> "$IC_STATE/events.log"; }
ic_get() { [ -f "$IC_CONFIG" ] && awk -F= -v key="$1" '$1==key {print $2; exit}' "$IC_CONFIG"; }
ic_enabled() {
    [ "$(ic_get "$1")" = 1 ] || return 1
    dependency=$(awk -F'\t' -v key="$1" '$1==key {print $3}' "$IC_LIB/features.tsv")
    [ -z "$dependency" ] || [ "$(ic_get "$dependency")" = 1 ]
}
ic_validate() {
    awk -F= '
      function curve(choices, value,   n, parts, i) {
        if (index("," choices ",", ",@curve,")==0 || substr(value,1,6)!="curve:") return 0
        n=split(substr(value,7), parts, ",")
        if (n!=9) return 0
        for (i=1; i<=n; i++) if (parts[i]!~/^[0-9]+$/ || length(parts[i])>4 || parts[i]+0>2000) return 0
        return 1
      }
      function number(choices, value,   n) {
        if (index("," choices ",", ",@number,")==0 || value!~/^[0-9]+$/) return 0
        n=value+0
        if (length(value)>3 || n<1 || n>999) return 0
        return 1
      }
      function pkglist(list,   n, parts, j) {
        if (list=="") return 1
        n=split(list, parts, ",")
        if (n>200) return 0
        for (j=1; j<=n; j++) if (parts[j]!~/^[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)*$/ || length(parts[j])>255) return 0
        return 1
      }
      function apps(choices, value,   mode, semi, second, white, black) {
        if (index("," choices ",", ",@apps,")==0) return 0
        if (value=="") return 1
        semi=index(value, ";")
        if (semi==0) {
          second=index(value, ":")
          if (second>0) {
            mode=substr(value, 1, second-1)
            if (mode!="whitelist" && mode!="blacklist") return 0
            return pkglist(substr(value, second+1))
          }
          return (value=="whitelist" || value=="blacklist")
        }
        mode=substr(value, 1, semi-1)
        if (mode!="whitelist" && mode!="blacklist") return 0
        second=index(substr(value, semi+1), ";")
        if (second==0) return 0
        second=semi+second
        if (index(substr(value, second+1), ";")>0) return 0
        white=substr(value, semi+1, second-semi-1)
        black=substr(value, second+1)
        return pkglist(white) && pkglist(black)
      }
      function countdowns(choices, value,   n, parts, i, d, y, m, dd, dim, step) {        if (index("," choices ",", ",@countdowns,")==0) return 0
        if (value=="") return 1
        n=split(value, parts, ";")
        # 新格式 12 段「事项;日期;农历;显示」×3；旧格式 6 段「事项;日期」×3（农历=否、显示=是）
        if (n!=12 && n!=6) return 0
        step=(n==12)?4:2
        for (i=1; i<=n; i+=step) {
          if (length(parts[i])>48) return 0
          d=parts[i+1]
          if (d!="") {
            if (d !~ /^[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]$/) return 0
            y=substr(d,1,4)+0; m=substr(d,6,2)+0; dd=substr(d,9,2)+0
            if (m<1 || m>12 || dd<1) return 0
            if (step==4 && parts[i+2]=="1") {
              if (dd>30) return 0   # 农历月最大 30 天；大小月/闰月语义由 App 侧完整校验
            } else {
              dim=(m==4||m==6||m==9||m==11)?30:(m==2?((y%4==0&&(y%100!=0||y%400==0))?29:28):31)
              if (dd>dim) return 0
            }
          }
          if (step==4 && parts[i+2]!="0" && parts[i+2]!="1") return 0
          if (step==4 && parts[i+3]!="0" && parts[i+3]!="1") return 0
        }
        return 1
      }
      function fontpath(value,   n) {
        # 字体目录或字体名：绝对路径/文件名，不含等号、分号、控制字符与 shell 元字符
        if (value=="") return 1
        if (length(value)>200) return 0
        if (value ~ /[=;$`"|&<>*?!]/) return 0
        return 1
      }
      NR==FNR {split($0,s,"\t"); choices[s[1]]=s[2]; known[s[1]]=1; n++; next}
      {if (NF!=2 || seen[$1]++) bad=1}
      $1=="version" {if($2!="1") bad=1; next}
      $1=="revision" {if($2!~/^[0-9]+$/ || length($2)>15) bad=1; next}
      # 旧版残留键已删除：「字体2」（font2.*）并入「字体」；「Google 设置图标修复」（fix.google）、
      # 「隐藏锁屏通知」（lock.notifyhide）并入「锁屏通知」、「过渡动画总开关」（motion.master）、
      # 「全刷扫过」（motion.gc_clear）、「体感息屏唤醒」（wave.master）下线；
      # 「设备状态」四项逐项开关（lock.ext.wifi/bt/audio/airplane）现在是正式功能，已并入 known；
      # 旧配置里可能残留同名行，legacy 里继续放行只写了半行的老数据。
      # 旧配置的 lock.notify.value=ext 也照旧放行（ext 是纯字母，走 fontpath 兜底即通过）；
      # App 侧解析时会把它迁成 lock.ext=1 并在下次保存时重写。
      # 容忍旧配置里的残留键，升级不卡开机
      {key=$1; if(sub(/\.value$/,"",key)) {legacy=(key ~ /^(font2\.(dir|pick|saf)|fix\.google|lock\.notifyhide|wave\.master|lock\.ext\.(wifi|bt|audio|airplane))$/) ? 1 : 0; if((!known[key] && !legacy) || $2=="@curve" || $2=="@number" || $2=="@apps" || $2=="@countdowns" || $2=="@path" || $2=="@font" || $2=="@panel" || (index("," choices[key] ",", "," $2 ",")==0 && !curve(choices[key], $2) && !number(choices[key], $2) && !apps(choices[key], $2) && !countdowns(choices[key], $2) && !fontpath($2))) bad=1}
       else {legacy=(key ~ /^(font2\.(dir|pick|saf)|fix\.google|lock\.notifyhide|wave\.master|lock\.ext\.(wifi|bt|audio|airplane))$/) ? 1 : 0; if((!known[key] && !legacy) || ($2!="0" && $2!="1")) bad=1}}
      END {
        if(!seen["version"] || !seen["revision"]) bad=1
        for(key in known) {
          # 旧版配置没有分模式选项、自动刷新项、设备状态总开关与后来新增的开关；仅允许这些新增字段整对缺省，其他字段仍严格检查。
          # lock.ext 是「显示设备状态」总开关、lock.ext.wifi/bt/audio/airplane 是它的四项显示开关，
          # lock.ext.edit 是收着这四项的页面（@panel，值恒为空串）；
          # 旧配置里都没有，缺省按「关」（总开关）/「显示」（四项，App 侧 defaultOn）处理。
          # qs.extend（控制中心拓展）只在 SystemUI 里生效，旧配置里没有，缺省按「关」处理。
          optional=(key ~ /^display\.(alg\.(clear|reading|fast|extreme|video|stock)|hyst\.(fast|extreme|video))$/ || key ~ /^lock\.notifyhide$/ || key ~ /^lock\.ext(\.(wifi|bt|audio|airplane|edit))?$/ || key ~ /^font\.(dir|pick|saf)$/ || key ~ /^font2\.(dir|pick|saf)$/ || key ~ /^refresh\.(master|touch|key|reading|interval|debounce|delay|period|apps)$/ || key ~ /^qs\.extend$/)
          if(optional && !seen[key] && !seen[key ".value"]) continue
          if(!seen[key] || !seen[key ".value"]) bad=1
        }
        exit bad
      }
    ' "$IC_LIB/features.tsv" "$1"
}
ic_supported() {
    [ "$(getprop ro.product.device)" = HLTE203T ] && [ "$(getprop ro.build.version.sdk)" = 29 ] && [ "$(getprop ro.product.cpu.abi)" = arm64-v8a ] &&
        { getprop ro.build.display.id; getprop ro.build.version.incremental; } | grep -Fq 'L1732.6.01.02'
}
ic_ready() {
    ic_supported && [ -f "$IC_MODULE/module.prop" ] && [ ! -f "$IC_MODULE/disable" ] && [ ! -f "$IC_MODULE/remove" ] &&
        [ "$(sed -n 's/^version=//p' "$IC_MODULE/module.prop")" = '0.2.1-r18' ] && [ ! -f "$IC_STATE/error" ] || return 1
    ! ic_module_conflict >/dev/null
}
# 同样替换显示库或配置守护的 Magisk 模块会与墨控争用同一文件；启用时拒绝加载。
# 图标覆盖已下线，gms_settings_icon_fix 不再与本模块争用文件。
ic_module_conflict() {
    IC_MODULES=${IC_MODULES:-/data/adb/modules}
    for module in eink_display epd_cfg_guard a_eink_pipeline_opt a_eink_lock_dither a_eink_lock_motion a_eink_lock_motion2 a_eink_lock_motion3 a_eink_lock_motion4 a_eink_reading_verify a_eink_lock_crop; do
        [ -d "$IC_MODULES/$module" ] && [ ! -f "$IC_MODULES/$module/disable" ] && [ ! -f "$IC_MODULES/$module/remove" ] && { echo "$module"; return 0; }
    done
    return 1
}
ic_properties() {
    # 视频默认走 GPU 蓝噪声。stock 是原厂误差扩散，当前后端有 GPU 实现，但逐行依赖使其明显慢于蓝噪声；只在实验项明确选择时使用。
    sampling=0; cache=0; dither=stock; video=blue
    ic_enabled display.sampling && sampling=1
    ic_enabled experiment.cache && cache=1
    ic_enabled lock.dither && dither=nearest
    ic_enabled experiment.video && video=$(ic_get experiment.video.value)
    setprop debug.inkcontrol.sampling "$sampling" || return 1
    setprop debug.inkcontrol.cache "$cache" || return 1
    setprop debug.eink.lock.dither "$dither" || return 1
    setprop debug.eink.video.dither "$video" || return 1
    # default 保留既有路径：包括锁屏点量化和旧版视频算法选择。
    # 关闭选项或 GPU 后端时明确复位属性，避免停用后遗留之前的选择。
    # display.alg.stock 是「改用原厂显示算法」总开关：开启时五个模式一律不接管，逐模式的选择只保留在配置里。
    ic_stock=0
    if ic_enabled display.alg.stock; then ic_stock=1; fi
    # 原厂算法总开关下发给 native：=1 时 GPU 不接管任何像素量化（A2 二值与灰阶全走原厂 CPU 路径）。
    setprop debug.eink.alg.stock "$ic_stock" || return 1
    for ic_scope in clear reading fast extreme video; do
        ic_algorithm=default
        if [ "$ic_stock" = 0 ] && ic_enabled "display.alg.$ic_scope"; then
            ic_algorithm=$(ic_get "display.alg.$ic_scope.value")
        fi
        case "$ic_scope:$ic_algorithm" in
            clear:default|clear:stock|clear:nearest|clear:bayer16|reading:default|reading:stock|reading:nearest|reading:bayer16|fast:default|fast:stock|fast:bayer|fast:blue|fast:threshold|extreme:default|extreme:stock|extreme:bayer|extreme:blue|extreme:threshold|video:default|video:blue|video:bayer|video:stock|video:threshold) ;;
            *) return 1 ;;
        esac
        setprop "debug.eink.alg.$ic_scope" "$ic_algorithm" || return 1
    done
    for ic_scope in fast extreme video; do
        ic_hysteresis=default
        if [ "$ic_stock" = 0 ] && ic_enabled "display.hyst.$ic_scope"; then
            ic_hysteresis=$(ic_get "display.hyst.$ic_scope.value")
        fi
        case "$ic_hysteresis" in default|on|off) ;; *) return 1 ;; esac
        setprop "debug.eink.hyst.$ic_scope" "$ic_hysteresis" || return 1
    done
}
# 波形动画能力：system_server（LSPosed motion Hook）用 debug.eink.motion.cap / lockcap
# 判断能否向 HWC 请求波形动画（墨迹晕开/网点长大/扫描线/百叶窗/全刷扫过）。
# 能力列表来自 payload 里 libeinkgpu 的标记；没挂载显示后端时清空，请求会被忽略、等同瞬切。
ic_motion_caps() {
    ic_lib_file=$IC_MODULE/system/vendor/lib64/hw/libeinkgpu.so
    ic_motion=
    ic_lock=
    if [ -f "$ic_lib_file" ]; then
        ic_motion=$(grep -a -o 'EINK_MOTION_V2:[a-z,]*' "$ic_lib_file" 2>/dev/null | head -n 1 | sed 's/^[^:]*://')
        ic_lock=$(grep -a -o 'EINK_LOCK_MOTION_V1:[a-z,]*' "$ic_lib_file" 2>/dev/null | head -n 1 | sed 's/^[^:]*://')
        # 全刷扫过与锁屏过渡依赖不同的标记；锁屏标记缺失时退回主标记里的公共波形。
        [ -n "$ic_lock" ] || ic_lock=$(printf '%s' "$ic_motion" | sed 's/,gcsweep//; s/gcsweep,//; s/^gcsweep$//')
    fi
    setprop debug.eink.motion.cap "$ic_motion" || return 1
    setprop debug.eink.motion.lockcap "$ic_lock" || return 1
}
# ── 系统字体替换 ──────────────────────────────────────────────────────────
# 槽位链：/system/fonts/SysFont.ttf -> /data/fonts/HmctFont.ttf -> 当前字体文件。
# 换字体＝改写 HmctFont.ttf 的指向 + 写 persist.sys.fontfile/fontname，再让界面重建（见 ic_font_activate）。
# 写入前把槽位里原有的字体备份到 $IC_STATE/font-backup.ttf（只备份一次）。
IC_FONT_DIR=${IC_FONT_DIR:-/data/fonts}
ic_font_slot() {
    [ -L "$IC_FONT_DIR/HmctFont.ttf" ] || return 1
    readlink -f "$IC_FONT_DIR/HmctFont.ttf" 2>/dev/null
}
# 列出目录里的 TTF/OTF（含无扩展名的主题字体包），每行「文件名 \t 字节数 \t 绝对路径」。
# 走 root 而不是 App 的存储权限，TF 卡与隐藏目录同样可读。
ic_list_fonts() {
    dir=$1
    [ -n "$dir" ] || dir=/storage/emulated/0/.hmcttheme/com.hmct.theme
    [ -d "$dir" ] || { echo '目录不存在或无法读取' >&2; return 1; }
    for file in "$dir"/*; do
        [ -f "$file" ] || continue
        size=$(stat -c %s "$file" 2>/dev/null) || continue
        [ "$size" -ge 65536 ] || continue
        head -c 4 "$file" | xxd -p | grep -Eq '^(00010000|4f54544f|74727565|74746366)$' || continue
        printf '%s\t%s\t%s\n' "$(basename "$file")" "$size" "$file"
    done
}
IC_FONT_BACKUP=$IC_STATE/font-backup.ttf
# 原厂字体只在第一次替换前备份一次；后来的字体绝不能盖掉这份
ic_font_backup() {
    [ -s "$IC_FONT_BACKUP" ] && return 0
    cp -f "$1" "$IC_FONT_BACKUP" || return 1
    chmod 644 "$IC_FONT_BACKUP" 2>/dev/null
    ic_log "font: 备份原厂字体 $(stat -c %s "$IC_FONT_BACKUP") 字节"
}
# 原子替换字体槽位：先在同目录写临时文件、改好属主/权限/SELinux 标签，再 rename 覆盖。
# 直接 cp 覆盖正在被 mmap 的字体文件，会让渲染中的进程读到半截数据（SystemUI 崩溃、状态栏消失、黑屏），
# rename 走的是「换 inode」：旧进程继续用旧数据，新起的进程读新字体。
ic_font_write() {
    src=$1; dst=$2
    owner=$(stat -c '%u:%g' "$dst") || return 1
    mode=$(stat -c '%a' "$dst" 2>/dev/null) || mode=666
    ctx=$(ls -Z "$dst" 2>/dev/null | awk '{print $1}')
    tmp="$dst.inknew"
    rm -f "$tmp"
    cp -f "$src" "$tmp" || { rm -f "$tmp"; echo '写入临时文件失败（存储空间不足？）' >&2; return 1; }
    chown "$owner" "$tmp" 2>/dev/null
    chmod "$mode" "$tmp" 2>/dev/null
    case "$ctx" in *:*) chcon "$ctx" "$tmp" 2>/dev/null ;; esac
    sync
    mv -f "$tmp" "$dst" || { rm -f "$tmp"; echo '替换字体槽位失败' >&2; return 1; }
    return 0
}
# 微扰 font_scale 触发全局 Activity 重建：每个进程重跑 updateDefaultTypeface() 读新字体。
# 每次把 scale 改成「用户设定值 + 计数器*1.2e-7」再写回原值，既强制重建、又不改变用户实际缩放。
# 已实测（2026-10-25 18:54:15）launcher 在 font_scale 变化后重跑 updateDefaultTypeface。
ic_font_perturb_scale() {
    fs=$(settings get system font_scale 2>/dev/null)
    [ -n "$fs" ] || return 0
    case "$fs" in *.*) ;; *) fs="${fs}.0" ;; esac
    counter=$(cat "$IC_STATE/font_scale_counter" 2>/dev/null || echo 0)
    counter=$((counter + 1))
    new=$(awk -v f="$fs" -v c="$counter" 'BEGIN{printf "%.9f", int(f*100+0.5)/100 + c*0.00000012}')
    settings put system font_scale "$new" 2>/dev/null
    settings put system font_scale "$fs" 2>/dev/null
    echo "$counter" > "$IC_STATE/font_scale_counter"
}
# 让新字体立刻全局生效，不需要重启 zygote——这是海信主题商店「换字体不用重启」的真实做法
# （系统服务 com.android.fontOnline 的 setFont()；2026-10-25 抓日志 + 反编译 framework 核实）：
#   ① HmctFont.ttf 软链指向新字体（每个字体按内容 md5 前缀落到独立路径 inkcontrol2_<hash>.ttf）
#   ② persist.sys.fontfile / fontname / fontchange 三个属性指向它
#   ③ 微扰 font_scale 强制全局 Activity 重建（实测 launcher 重跑 updateDefaultTypeface），
#      桌面、锁屏、状态栏与正在运行的应用全部重读新字体；再发 FONT_CONFIG_CHANGED 广播、重启 SystemUI。
# 框架 Typeface.updateDefaultTypeface() 按 persist.sys.fontfile 的「路径字符串」判断是否重载：路径不变
# 就直接跳过，所以每个字体必须落到不同路径（md5 前缀），否则换了字体框架也不重新加载、看起来“没生效”。
# 字体是全局替换：状态栏、锁屏、桌面与所有应用一起换，不存在“桌面不变”的情况（旧结论是把“整段失效”
# 错当成“局部限制”）。
# 注意：fontname 必须是字体文件名去掉扩展名（系统的取值就是 "193" 这种）；默认字体特例是 sans-serif-medium。
ic_font_activate() {
    target=$1
    [ -f "$target" ] || { echo '字体文件不存在' >&2; return 1; }
    case "$target" in
        /system/*) name=sans-serif-medium ;;
        *) name=$(basename "$target"); name=${name%.*} ;;
    esac
    ln -sfn "$target" "$IC_FONT_DIR/HmctFont.ttf" || { echo '更新字体软链失败' >&2; return 1; }
    chown -h system:system "$IC_FONT_DIR/HmctFont.ttf" 2>/dev/null
    setprop persist.sys.fontfile "$target" || { echo '写入字体属性失败' >&2; return 1; }
    setprop persist.sys.fontname "$name" || { echo '写入字体名失败' >&2; return 1; }
    setprop persist.sys.fontchange 1
    ic_log "font: 激活 $target (name=$name)"
    # 让正在运行的界面也换字：微扰 font_scale 触发全局 Activity 重建（已实测 launcher 重跑 updateDefaultTypeface）
    ic_font_perturb_scale
    # 通知主题服务配置变化（海信 fontOnline 监听此广播重建字体相关资源）
    am broadcast -a hmct.intent.action.FONT_CONFIG_CHANGED >/dev/null 2>&1
    # 系统界面重生：状态栏、通知栏、锁屏立刻换字（它会被系统自动拉起）
    pids=$(pidof com.android.systemui 2>/dev/null)
    [ -n "$pids" ] && kill $pids 2>/dev/null
    return 0
}
# 回到原厂字体。这里**不靠备份内容**判断原厂：首次备份时槽位里装的未必是原厂字体
# （比如用户已经用主题商店换过字体，备份到的就是那个第三方字体）。
# 系统「默认字体」的取值是确定的：fonts_vision.xml 里 sans-serif 走 SysFont.ttf，
# 而 SysFont.ttf -> HmctFont.ttf，切回默认字体就是把软链指回 /system/fonts/Roboto-Medium.ttf。
# 所以直接指回去；备份文件仍保留，作为 /system 那份被改过时的兜底。
IC_FONT_STOCK=/system/fonts/Roboto-Medium.ttf
# 应用字体：字体文件按内容 md5 前缀落到独立路径（inkcontrol2_<hash>.ttf）——
# 框架 Typeface.updateDefaultTypeface() 按 persist.sys.fontfile 的「路径字符串」判重，
# 同一路径就不重载；独立路径保证每次换字体框架都真正重新加载，再由 ic_font_activate
# 全局激活（微扰 font_scale + 广播 + 重启 SystemUI），状态栏、锁屏、桌面与正在运行的
# 应用立刻一起换字，不用重启。
ic_apply_font() {
    src=$1
    [ -n "$src" ] && [ -f "$src" ] || { echo '找不到字体文件' >&2; return 1; }
    case "$src" in
        /storage/*|/sdcard/*|/data/local/tmp/*|/data/user/*|/data/data/*) ;;
        *) echo '字体路径不在允许范围' >&2; return 1 ;;
    esac
    printf '%s' "$src" | grep -q '[[:cntrl:]]' && { echo '字体路径无效' >&2; return 1; }
    # TTF(00010000)、OTF(OTTO)、'true'、TTC(ttcf) 四种合法字体头
    head -c 4 "$src" | xxd -p | grep -Eq '^(00010000|4f54544f|74727565|74746366)$' || {
        echo '不是有效的字体文件（需要 TTF 或 OTF）' >&2; return 1; }
    h=$(md5sum "$src" 2>/dev/null | cut -c1-8)
    dst=$IC_FONT_DIR/inkcontrol2_$h.ttf
    mkdir -p "$IC_STATE" || return 1
    if [ ! -f "$dst" ]; then
        # 新槽位从当前字体复制一份做基底，继承系统的属主、权限与 SELinux 标签
        slot=$(ic_font_slot 2>/dev/null); [ -f "$slot" ] || slot=$IC_FONT_STOCK
        cp -f "$slot" "$dst" 2>/dev/null || return 1
        chmod 666 "$dst" 2>/dev/null
        chcon u:object_r:fonts_app_data_file:s0 "$dst" 2>/dev/null
    fi
    ic_font_backup "$dst" || { echo '备份原厂字体失败' >&2; return 1; }
    ic_font_write "$src" "$dst" || return 1
    ic_font_activate "$dst" || return 1
    size=$(stat -c %s "$dst")
    ic_log "font: $src -> $dst ($size)"
    echo "已应用 $(basename "$src")（$((size / 1024 / 1024)) MB），全局界面（状态栏、锁屏、桌面与正在运行的应用）立即换字，不用重启。"
}
ic_restore_font() {
    if [ -f "$IC_FONT_STOCK" ]; then
        ic_font_activate "$IC_FONT_STOCK" || return 1
        ic_log "font: 恢复原厂字体 -> $IC_FONT_STOCK"
        echo "已恢复原厂字体（$(basename "$IC_FONT_STOCK")），界面已刷新。"
        return 0
    fi
    [ -s "$IC_FONT_BACKUP" ] || { echo '没有原厂字体备份' >&2; return 1; }
    tmp="$IC_STATE/font-restore.tmp"
    cp -f "$IC_FONT_BACKUP" "$tmp" || { echo '读取备份失败' >&2; return 1; }
    dst=$IC_FONT_DIR/inkcontrol2_restore.ttf
    if [ ! -f "$dst" ]; then
        slot=$(ic_font_slot 2>/dev/null); [ -f "$slot" ] || slot=$IC_FONT_STOCK
        cp -f "$slot" "$dst" 2>/dev/null || return 1
        chmod 666 "$dst" 2>/dev/null
        chcon u:object_r:fonts_app_data_file:s0 "$dst" 2>/dev/null
    fi
    ic_font_write "$tmp" "$dst" || { rm -f "$tmp"; return 1; }
    rm -f "$tmp"
    ic_font_activate "$dst" || return 1
    ic_log "font: 恢复原厂字体（来自备份） -> $dst"
    echo "已恢复原厂字体（$(( $(stat -c %s "$dst") / 1024 / 1024 )) MB），界面已刷新。"
}
# 自动刷新依赖随 App 提供的无障碍服务；只按刷新总开关增删该组件，关闭时不改写系统设置。
ic_accessibility() {
    ic_component=com.inkcontrol/.features.refresh.RefreshService
    ic_component_full=com.inkcontrol/com.inkcontrol.features.refresh.RefreshService
    ic_current=$(settings get secure enabled_accessibility_services 2>/dev/null)
    [ "$ic_current" = null ] && ic_current=""
    case ",$ic_current," in
        *",$ic_component,"*|*",$ic_component_full,"*) ic_present=1 ;;
        *) ic_present=0 ;;
    esac
    if ic_enabled refresh.master; then
        [ "$ic_present" = 1 ] && return 0
        if [ -n "$ic_current" ]; then ic_next="$ic_current:$ic_component"; else ic_next="$ic_component"; fi
        settings put secure enabled_accessibility_services "$ic_next" || return 1
        settings put secure accessibility_enabled 1 || return 1
    else
        [ "$ic_present" = 0 ] && return 0
        ic_next=$(printf '%s' "$ic_current" | tr ':' '\n' | grep -vxF "$ic_component" | grep -vxF "$ic_component_full" | paste -sd: -)
        if [ -n "$ic_next" ]; then
            settings put secure enabled_accessibility_services "$ic_next" || return 1
        else
            settings delete secure enabled_accessibility_services || return 1
        fi
    fi
    return 0
}
ic_prepare_restore() {
    STATE=$IC_STATE; CFG=/data/system/epd_mode_cfg.xml
    CS_MODULE=$IC_MODULE; CS_HOOK_DIR=/data/adb/post-fs-data.d; CS_SERVICE_DIR=/data/adb/service.d
    CONFIG_SOURCE=$IC_LIB/config_state.sh
    . "$CONFIG_SOURCE"
}
ic_apply_hot() {
    ic_properties || return 1
    ic_accessibility || return 1
    ic_prepare_restore
    cs_prepare_hooks || return 1
    if ic_enabled display.dynamic; then
        rm -f "$CS_DIR/stopped" "$CS_DIR/restore_requested"
        previous=$(awk -F= '$1=="display.dynamic" {print $2}' "$IC_APPLIED" 2>/dev/null)
        if [ "$previous" != 1 ] && [ -f "$CS_DIR/dynamic.tsv" ]; then
            phase=$(awk '{print $3}' "$CS_DIR/dynamic.tsv")
            case "$phase" in restored|kept)
                mkdir -p "$CS_DIR/history"
                cp "$CS_DIR/dynamic.tsv" "$CS_DIR/history/dynamic-$(date +%s)-$$.tsv" || return 1
                rm -f "$CS_DIR/dynamic.tsv" "$CS_DIR/install_dynamic" ;;
            esac
        fi
        cs_global_apply || return 1
    else cs_global_restore || return 1; fi
    cp "$IC_CONFIG" "$IC_APPLIED.tmp" && mv "$IC_APPLIED.tmp" "$IC_APPLIED" || return 1
    ic_ready && setprop sys.inkcontrol.ready 1
    return 0
}

# ── 系统应用卸载 / 还原 ───────────────────────────────────────────────
# 只对机主（user 0）卸载：分区里的原始 APK 保留，pm install-existing 即可原样收回，
# 还原后仍是系统应用身份（priv-app 的特权权限不会丢）。包名只允许字母数字点下划线，
# 避免把用户输入拼进 shell 命令。
ic_sysapp_valid() {
    case "$1" in
        ''|*[!A-Za-z0-9._]*) return 1 ;;
    esac
    [ ${#1} -le 200 ]
}
ic_sysapp_state() {
    ic_sysapp_valid "$1" || { echo '包名无效' >&2; return 2; }
    if pm list packages --user 0 2>/dev/null | grep -qx "package:$1"; then
        echo 'state=installed'
        pm path "$1" 2>/dev/null | sed -n 's/^package:/path=/p'
    elif pm list packages -u 2>/dev/null | grep -qx "package:$1"; then
        # 系统里还在、但没给机主装：正是 uninstall --user 0 的结果
        echo 'state=removed'
    else
        echo 'state=absent'
    fi
}
ic_sysapp_remove() {
    ic_sysapp_valid "$1" || { echo '包名无效' >&2; return 2; }
    if out=$(pm uninstall --user 0 "$1" 2>&1); then
        echo 'state=removed'
        echo "$out"
        return 0
    fi
    echo "$out" >&2
    return 1
}
ic_sysapp_restore() {
    ic_sysapp_valid "$1" || { echo '包名无效' >&2; return 2; }
    if out=$(pm install-existing --user 0 "$1" 2>&1); then
        echo 'state=installed'
        echo "$out"
        return 0
    fi
    echo "$out" >&2
    return 1
}
