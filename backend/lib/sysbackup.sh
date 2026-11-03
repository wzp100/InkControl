#!/system/bin/sh
# 系统设置快照：只搬 settings 的三张表（system/secure/global），恢复时逐条 settings put 回放。
#
# 为什么不覆盖文件、也不碰 /data/data：整包盖回去会让「设置」启动即闪退 ——
# prefs 里存的是「上次停在哪个页面 + 配套的键名」，换一套系统后 findPreference 拿不到对象，
# TopLevelSettings.onCreate 里直接空指针；再加上属主与 SELinux 上下文不对，读都读不出来。
# 逐条回放的好处是能实时看到哪一条失败、失败不连累其它条，而且不用重启。

IC_BACKUP="$IC_STATE/backup"

# 回放时跳过的键：
#   *_formatted / 更新时间戳这类是临时状态，写回去只会让界面短暂错乱；
#   enabled_accessibility_services 由后端按开关维护（见 service.sh），这里写进去会和它打架；
#   快捷方式的目标服务同理，恢复成不存在的组件会让「无障碍」页显示异常。
ic_backup_skip() {
    case "$1" in
        next_alarm_formatted|has_update_package|notification_path|smsring_path) return 0 ;;
        enabled_accessibility_services|accessibility_shortcut_target_service) return 0 ;;
        *"_formatted") return 0 ;;
        *) return 1 ;;
    esac
}

# 快照编号只接受日期时间格式，杜绝 `../` 这类拼出来的路径。
ic_backup_valid() {
    case "$1" in
        ''|.*|*[!0-9A-Za-z_-]*) return 1 ;;
        *) return 0 ;;
    esac
}

ic_backup_count() {
    ic_c=0
    for ic_t in system secure global; do
        [ -f "$IC_BACKUP/$1/$ic_t.txt" ] && ic_c=$((ic_c + $(wc -l < "$IC_BACKUP/$1/$ic_t.txt")))
    done
    echo "$ic_c"
}

# 已有快照：每行「编号<TAB>创建时间<TAB>条数<TAB>占用KB」，供界面直接排版。
ic_backup_list() {
    [ -d "$IC_BACKUP" ] && for ic_d in "$IC_BACKUP"/*; do
        [ -d "$ic_d" ] || continue
        ic_id=${ic_d##*/}
        ic_backup_valid "$ic_id" || continue
        ic_created=$(sed -n 's/^created=//p' "$ic_d/manifest.txt" 2>/dev/null)
        ic_size=$(du -sk "$ic_d" 2>/dev/null | awk '{print $1}')
        printf '%s\t%s\t%s\t%s\n' "$ic_id" "${ic_created:-0}" "$(ic_backup_count "$ic_id")" "${ic_size:-0}"
    done
}

ic_backup_save() {
    ic_id=$(date +%Y%m%d-%H%M%S)
    ic_dir="$IC_BACKUP/$ic_id"
    # 同一秒内连点两次不会互相覆盖：补一个后缀
    [ -d "$ic_dir" ] && ic_id="$ic_id-$$" && ic_dir="$IC_BACKUP/$ic_id"
    mkdir -p "$ic_dir" || { echo '无法创建快照目录' >&2; return 1; }
    for ic_t in system secure global; do
        # 必须经过管道：settings 自己不写 stdout，是把 fd 交给 system_server 代写，
        # 而 system_server 对 /data/adb（adb_data_file）没有 write 权限，直接重定向会拿到
        # “Failed transaction” 和一个空文件。过一层 cat，写盘的就变回 root shell。
        settings list "$ic_t" </dev/null 2>/dev/null | cat > "$ic_dir/$ic_t.txt"
    done
    ic_n=$(ic_backup_count "$ic_id")
    if [ "$ic_n" -eq 0 ]; then
        rm -rf "$ic_dir"
        echo '没有读到任何设置项' >&2
        return 1
    fi
    cat "$ic_dir"/*.txt > "$ic_dir/.digest" 2>/dev/null
    ic_sha=$(sha256sum "$ic_dir/.digest" 2>/dev/null | cut -d' ' -f1)
    rm -f "$ic_dir/.digest"
    {
        echo "id=$ic_id"
        echo "created=$(date +%s)"
        echo "firmware=$(getprop ro.build.display.id)"
        echo "fingerprint=$(getprop ro.build.fingerprint)"
        echo "count=$ic_n"
        echo "sha256=$ic_sha"
    } > "$ic_dir/manifest.txt"
    echo "id=$ic_id"
    echo "count=$ic_n"
}

ic_backup_restore() {
    ic_id=$1
    ic_backup_valid "$ic_id" || { echo '快照编号无效' >&2; return 2; }
    ic_dir="$IC_BACKUP/$ic_id"
    [ -d "$ic_dir" ] || { echo "快照不存在：$ic_id" >&2; return 1; }
    # 先给当前状态拍一张：恢复错了还能按这一张退回去
    ic_before=$(ic_backup_save 2>/dev/null | sed -n 's/^id=//p')
    echo "safety=$ic_before"
    ic_w=0; ic_s=0; ic_f=0
    for ic_t in system secure global; do
        [ -f "$ic_dir/$ic_t.txt" ] || continue
        while IFS= read -r ic_line; do
            [ -n "$ic_line" ] || continue
            case "$ic_line" in *=*) ;; *) continue ;; esac
            ic_k=${ic_line%%=*}
            ic_v=${ic_line#*=}
            if ic_backup_skip "$ic_k"; then ic_s=$((ic_s + 1)); continue; fi
            # stdin 必须断开：cmd 客户端会把 stdin/stdout 的 fd 一并交给 system_server，
            # 而这个 fd 指着 /data/adb 下的文件（system_server 连 use 都不行）→ 整笔 transaction 失败。
            if settings put "$ic_t" "$ic_k" "$ic_v" </dev/null >/dev/null 2>&1; then
                ic_w=$((ic_w + 1))
            else
                ic_f=$((ic_f + 1))
            fi
        done < "$ic_dir/$ic_t.txt"
    done
    ic_log "sysbackup 恢复 $ic_id：写入 $ic_w，跳过 $ic_s，失败 $ic_f"
    echo "written=$ic_w"
    echo "skipped=$ic_s"
    echo "failed=$ic_f"
}

ic_backup_delete() {
    ic_id=$1
    ic_backup_valid "$ic_id" || { echo '快照编号无效' >&2; return 2; }
    [ -d "$IC_BACKUP/$ic_id" ] || { echo "快照不存在：$ic_id" >&2; return 1; }
    rm -rf "$IC_BACKUP/$ic_id"
    echo "deleted=$ic_id"
}
