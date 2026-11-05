"""测试实际后端函数及默认配置；所有设备能力都用本地替身。"""
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
BASH = os.environ.get('INKCONTROL_TEST_BASH') or ('C:/Program Files/Git/bin/bash.exe' if os.name == 'nt' else shutil.which('bash'))
SCHEMA = Path(os.environ['INKCONTROL_TEST_SCHEMA'])

def posix(path):
    text = str(Path(path).resolve()).replace('\\','/')
    return '/'+text[0].lower()+text[2:] if len(text)>1 and text[1]==':' else text

class BackendTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='inkcontrol-test-')
        self.root = Path(self.tmp.name)
        self.config = self.root/'configuration.txt'
        self.config.write_bytes((SCHEMA/'defaults.txt').read_bytes())
        self.base = f'''set -e
IC_STATE={shlex.quote(posix(self.root))}
IC_MODULE="$IC_STATE/module"
IC_LIB={shlex.quote(posix(SCHEMA))}
. {shlex.quote(posix(ROOT/'backend/lib/common.sh'))}
'''
    def tearDown(self): self.tmp.cleanup()
    def shell(self, text, expected=0):
        result = subprocess.run([BASH,'--noprofile','--norc','-s'],input=self.base+text,capture_output=True,text=True,encoding='utf-8',timeout=60)
        self.assertEqual(result.returncode,expected,result.stdout+result.stderr)
        return result.stdout
    def test_all_defaults_disabled(self):
        self.shell('ic_validate "$IC_CONFIG"\n')
        # 四个设备状态显示开关是 defaultOn（配置里没有这个键就按显示处理），
        # 「截屏便签对齐尺寸」同样是 defaultOn（不缩的话原厂那一格比补上来的按钮大一圈），
        # 所以它们在默认配置里就写 1；其余开关仍必须全部是 0。
        default_on = {'lock.ext.wifi', 'lock.ext.bt', 'lock.ext.audio', 'lock.ext.airplane',
                      'qs.panel.pinsize'}
        for line in self.config.read_text().splitlines():
            key,value=line.split('=',1)
            if key not in ('version','revision') and not key.endswith('.value'):
                self.assertEqual(value, '1' if key in default_on else '0', key)
    def test_conflicting_module_blocks_only_while_enabled(self):
        modules=self.root/'modules'; (modules/'eink_display').mkdir(parents=True)
        prefix='IC_MODULES='+shlex.quote(posix(modules))+'\n'
        self.assertEqual(self.shell(prefix+'ic_module_conflict\n').strip(),'eink_display')
        (modules/'eink_display'/'disable').write_text('')
        self.shell(prefix+'if ic_module_conflict; then exit 90; fi\n')
        common=(ROOT/'backend/lib/common.sh').read_text(encoding='utf-8')
        self.assertNotIn('lspd',common); self.assertNotIn('manual_framework_checked',common)
    def test_parser_rejects_unknown_duplicate_injection_and_missing(self):
        original = self.config.read_text()
        for invalid in [original+'evil=1\n',original+'version=1\n',original.replace('revision=0','revision=-1'),original.replace('display.gpu.value=default','display.gpu.value=$(id)'),original.replace('display.gpu=0\n','')]:
            self.config.write_text(invalid,encoding='utf-8')
            self.shell('if ic_validate "$IC_CONFIG"; then exit 90; fi\n')
    def test_hold_curve_validation(self):
        original=self.config.read_text()
        good=original.replace('motion.hold.value=0','motion.hold.value=curve:420,400,360,320,280,230,210,175,150')
        self.config.write_text(good,encoding='utf-8'); self.shell('ic_validate "$IC_CONFIG"\n')
        for bad in ('curve:1,2,3','curve:1,2,3,4,5,6,7,8,2001','curve:1,2,3,4,5,6,7,8,x','@curve','curve:1,2,3,4,5,6,7,8,9,10'):
            self.config.write_text(original.replace('motion.hold.value=0','motion.hold.value='+bad),encoding='utf-8')
            self.shell('if ic_validate "$IC_CONFIG"; then exit 90; fi\n')
        self.config.write_text(original.replace('motion.steps.value=2','motion.steps.value=curve:1,2,3,4,5,6,7,8,9'),encoding='utf-8')
        self.shell('if ic_validate "$IC_CONFIG"; then exit 90; fi\n')
    def test_refresh_interval_accepts_custom_number(self):
        original=self.config.read_text()
        for good in ('3','1','42','999'):
            self.config.write_text(original.replace('refresh.interval.value=5','refresh.interval.value='+good),encoding='utf-8')
            self.shell('ic_validate "$IC_CONFIG"\n')
        for bad in ('0','1000','abc','-1','@number','@curve','3.5',' 3'):
            self.config.write_text(original.replace('refresh.interval.value=5','refresh.interval.value='+bad),encoding='utf-8')
            self.shell('if ic_validate "$IC_CONFIG"; then exit 90; fi\n')
        for key,value in (('refresh.delay','3'),('motion.hold','3')):
            self.config.write_text(original.replace(key+'.value=0',key+'.value='+value),encoding='utf-8')
            self.shell('if ic_validate "$IC_CONFIG"; then exit 90; fi\n')
        # refresh.period 选项含 @number：任意 1–999 合法，范围外/非数字仍拒（默认档随选项表变化，读当前值）
        period=[l.split('=',1)[1] for l in original.splitlines() if l.startswith('refresh.period.value=')][0]
        for good in ('3','1','60','300','600','1800','999'):
            self.config.write_text(original.replace('refresh.period.value='+period,'refresh.period.value='+good),encoding='utf-8')
            self.shell('ic_validate "$IC_CONFIG"\n')
        for bad in ('0','1000','abc','-1','@number','@curve','3.5',' 3'):
            self.config.write_text(original.replace('refresh.period.value='+period,'refresh.period.value='+bad),encoding='utf-8')
            self.shell('if ic_validate "$IC_CONFIG"; then exit 90; fi\n')
    def test_countdown_list_accepts_both_formats(self):
        original=self.config.read_text()
        def with_value(value):
            return original.replace('lock.countdowns.value=','lock.countdowns.value='+value)
        # 旧 6 段「事项;日期」×3
        for good in ('','考试;2026-11-27;;;;',';2026-11-27;;;;','a;2026-11-27;b;2026-12-20;c;2027-01-01'):
            self.config.write_text(with_value(good),encoding='utf-8'); self.shell('ic_validate "$IC_CONFIG"\n')
        # 新 12 段「事项;日期;农历;显示」×3（空组也带标志位）
        new_good=(';'.join(['考试','2026-11-27','0','1','','','0','1','春节','2027-01-01','1','1']),
                  ';'.join(['生日','2026-12-20','1','0','','','0','1','','','0','1']))
        for good in new_good:
            self.config.write_text(with_value(good),encoding='utf-8'); self.shell('ic_validate "$IC_CONFIG"\n')
        for bad in ('a;2026-11-27',                                     # 段数不对
                    ';'.join(['a','2026-11-27','2','1','','','','','','','','']),   # 农历标志非 0/1
                    ';'.join(['a','2026-11-27','0','2','','','','','','','','']),   # 显示标志非 0/1
                    ';'.join(['a','2026-11-31','1','1','','','','','','','','']),   # 农历月最大 30 天
                    ';'.join(['a','2026-02-30','0','1','','','','','','','','']),   # 公历 2 月无 30 日
                    ';'.join(['a','not-a-date','0','1','','','','','','','','']),
                    ';'.join(['a','2026-11-27','0','1','','','','','','','']),      # 11 段
                    '事项名特别长超过十二个字符;2026-11-27;0;1;;;;'):
            self.config.write_text(with_value(bad),encoding='utf-8')
            self.shell('if ic_validate "$IC_CONFIG"; then exit 90; fi\n')
    def test_refresh_debounce_accepts_custom_number_and_is_optional(self):
        original=self.config.read_text()
        for good in ('1000','500','2000','0','250','999'):
            self.config.write_text(original.replace('refresh.debounce.value=1000','refresh.debounce.value='+good),encoding='utf-8')
            self.shell('ic_validate "$IC_CONFIG"\n')
        for bad in ('abc','-1','@number','@curve','3.5','5000',' 3'):
            self.config.write_text(original.replace('refresh.debounce.value=1000','refresh.debounce.value='+bad),encoding='utf-8')
            self.shell('if ic_validate "$IC_CONFIG"; then exit 90; fi\n')
        stripped='\n'.join(line for line in original.splitlines() if not line.startswith('refresh.debounce'))
        self.config.write_text(stripped+'\n',encoding='utf-8')
        self.shell('ic_validate "$IC_CONFIG"\n')
    def test_refresh_app_filter(self):
        original=self.config.read_text()
        for good in ('','whitelist','blacklist','whitelist:com.tencent.mm','blacklist:com.a,android,com.b_c.d1',
                     'whitelist;;','whitelist;com.a;','blacklist;com.a,android;com.b_c.d1','whitelist;com.a;com.b'):
            self.config.write_text(original.replace('refresh.apps.value=','refresh.apps.value='+good),encoding='utf-8')
            self.shell('ic_validate "$IC_CONFIG"\n')
        for bad in ('com.a,',',com.a','com a','@apps','com.a/com.b','com..a','.com.a','com.a.','junk;$x','green:com.a','whitelist:com..a','whitelist:com.a,',
                    'whitelist;com.a','whitelist;com.a;com.b;com.c','green;com.a;com.b','whitelist;com..a;','whitelist;;com.a,','whitelist;com.a;com.b,'):
            self.config.write_text(original.replace('refresh.apps.value=','refresh.apps.value='+bad),encoding='utf-8')
            self.shell('if ic_validate "$IC_CONFIG"; then exit 90; fi\n')
        stripped='\n'.join(line for line in original.splitlines() if not line.startswith('refresh.apps'))
        self.config.write_text(stripped+'\n',encoding='utf-8')
        self.shell('ic_validate "$IC_CONFIG"\n')
    def test_service_waits_for_boot_before_hot_apply(self):
        service=(ROOT/'backend/service.sh').read_text(encoding='utf-8')
        self.assertLess(service.index('sys.boot_completed'),service.index('ic_apply_hot'))
    def test_child_stops_but_keeps_selection(self):
        self.config.write_text(self.config.read_text().replace('desktop.icons=0','desktop.icons=1'),encoding='utf-8')
        self.shell('if ic_enabled desktop.icons; then exit 90; fi\n')
        self.config.write_text(self.config.read_text().replace('desktop.theme=0','desktop.theme=1'),encoding='utf-8')
        self.shell('ic_enabled desktop.icons\n')
    def test_hot_properties_are_independent_and_off_by_default(self):
        output=self.shell('setprop() { echo "$1=$2"; }\nic_properties\n')
        expected={'debug.inkcontrol.sampling=0','debug.inkcontrol.cache=0','debug.eink.lock.dither=stock','debug.eink.video.dither=blue'}
        expected.update('debug.eink.alg.'+scope+'=default' for scope in ('clear','reading','fast','extreme','video'))
        expected.update('debug.eink.hyst.'+scope+'=default' for scope in ('fast','extreme','video'))
        self.assertEqual(set(output.splitlines()),expected)
        self.config.write_text(self.config.read_text().replace('display.gpu=0','display.gpu=1').replace('lock.dither=0','lock.dither=1'),encoding='utf-8')
        output=self.shell('setprop() { echo "$1=$2"; }\nic_properties\n')
        self.assertIn('debug.eink.lock.dither=nearest',output); self.assertIn('debug.inkcontrol.cache=0',output)
    def test_legacy_configuration_keeps_defaults_without_rewriting(self):
        original=self.config.read_text(encoding='utf-8')
        legacy='\n'.join(line for line in original.splitlines() if not line.startswith(('display.alg.','display.hyst.')))+'\n'
        self.config.write_text(legacy,encoding='utf-8')
        self.shell('ic_validate "$IC_CONFIG"\n')
        output=self.shell('setprop() { echo "$1=$2"; }\nic_properties\n')
        self.assertIn('debug.eink.alg.extreme=default',output)
        self.assertIn('debug.eink.hyst.video=default',output)
        self.assertEqual(self.config.read_text(encoding='utf-8'),legacy)
        # 只缺新增字段的一半仍然是损坏配置，不能以兼容旧版为由接受。
        for suffix in ('display.alg.clear=0\n','display.alg.clear.value=nearest\n'):
            self.config.write_text(legacy+suffix,encoding='utf-8')
            self.shell('if ic_validate "$IC_CONFIG"; then exit 90; fi\n')
    def test_mode_choices_reject_cross_family_and_invalid_hysteresis(self):
        original=self.config.read_text(encoding='utf-8')
        for key,value in (('display.alg.clear','blue'),('display.alg.reading','threshold'),('display.alg.fast','nearest'),('display.alg.extreme','bayer16'),('display.alg.video','nearest'),('display.hyst.video','12')):
            self.config.write_text(original.replace(key+'.value=default',key+'.value='+value),encoding='utf-8')
            self.shell('if ic_validate "$IC_CONFIG"; then exit 90; fi\n')
    def test_mode_choices_are_independent_and_parent_disable_resets_properties(self):
        text=self.config.read_text(encoding='utf-8').replace('display.gpu=0','display.gpu=1')
        choices={'display.alg.clear':'nearest','display.alg.reading':'bayer16','display.alg.fast':'blue','display.alg.extreme':'threshold','display.alg.video':'stock','display.hyst.fast':'on','display.hyst.extreme':'off','display.hyst.video':'off'}
        for key,value in choices.items():
            text=text.replace(key+'=0',key+'=1').replace(key+'.value=default',key+'.value='+value)
        self.config.write_text(text,encoding='utf-8')
        self.shell('ic_validate "$IC_CONFIG"\n')
        output=self.shell('setprop() { echo "$1=$2"; }\nic_properties\n')
        for key,value in choices.items(): self.assertIn('debug.eink.'+key.removeprefix('display.')+'='+value,output)
        self.config.write_text(text.replace('display.gpu=1','display.gpu=0'),encoding='utf-8')
        output=self.shell('setprop() { echo "$1=$2"; }\nic_properties\n')
        for key in choices: self.assertIn('debug.eink.'+key.removeprefix('display.')+'=default',output)
    def test_device_and_firmware_gate(self):
        fake='''getprop() { case "$1" in ro.product.device) echo HLTE203T;; ro.build.version.sdk) echo 29;; ro.product.cpu.abi) echo arm64-v8a;; ro.build.display.id) echo L1732.6.01.02;; esac; }
ic_supported
'''
        self.shell(fake)
        for changed in (fake.replace('echo 29','echo 30'),fake.replace('echo HLTE203T','echo OTHER'),fake.replace('echo L1732.6.01.02','echo L1732.6.16.00')): self.shell(changed,1)
    def test_no_arbitrary_root_or_automatic_phone_operations(self):
        root=(ROOT/'src/com/inkcontrol/platform/RootBridge.java').read_text(encoding='utf-8')
        # 白名单必须逐条列死：新增命令要同时改这里，避免悄悄放宽成「什么都能跑」
        self.assertIn('status|apply|applyfont|fontlist|restorefont|sysapp (state|remove|restore)'
                      '|sysbackup (list|save|restore|delete)")',root)
        for file in (ROOT/'src/com/inkcontrol/ui').glob('*.java'):
            text=file.read_text(encoding='utf-8'); self.assertNotIn('ProcessBuilder',text); self.assertNotIn('Settings.Global',text)
        for file in (ROOT/'src/com/inkcontrol/core').glob('*.java'):
            text=file.read_text(encoding='utf-8'); self.assertNotIn('import android.',text); self.assertNotIn('de.robv',text)
    def test_shell_syntax_utf8_and_lf(self):
        paths=list((ROOT/'backend').rglob('*.sh'))+[ROOT/'backend/bin/inkctl',ROOT/'backend/META-INF/com/google/android/update-binary']
        for path in paths:
            self.assertNotIn(b'\r',path.read_bytes()); path.read_text(encoding='utf-8')
            result=subprocess.run([BASH,'-n',posix(path)],capture_output=True,text=True)
            self.assertEqual(result.returncode,0,str(path)+result.stderr)
    def test_guard_corrupt_and_valid_inputs(self):
        valid="<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<config>\n<cfg pkg=\"com.a\" mode=\"515\" />\n</config>\n"
        for content,status in [(valid,0),('',2),(valid.replace('515','bad'),2),('\0'*100,2),(valid.replace('</config>\n',''),1)]:
            candidate=self.root/'epd.xml'; candidate.write_text(content,encoding='utf-8')
            self.shell(f'. {shlex.quote(posix(ROOT/"backend/lib/guard.sh"))}\ncheck {shlex.quote(posix(candidate))}\n',status)
    def test_dynamic_toggle_starts_new_ownership_cycle(self):
        source=shlex.quote(posix(ROOT/'backend/lib/config_state.sh'))
        self.shell(f'''settings() {{
  case "$1" in get) cat "$IC_STATE/global";; put) echo "$4" > "$IC_STATE/global";; delete) echo null > "$IC_STATE/global";; esac
}}
setprop() {{ :; }}
ic_ready() {{ return 1; }}
ic_prepare_restore() {{
 STATE=$IC_STATE; CS_DIR=$IC_STATE/config_state; CS_HOOK_DIR=$IC_STATE/boot; CS_SERVICE_DIR=$IC_STATE/service
 CONFIG_SOURCE={source}; . "$CONFIG_SOURCE"
}}
echo 1 > "$IC_STATE/global"
ic_apply_hot
sed 's/display.dynamic=0/display.dynamic=1/' "$IC_CONFIG" > "$IC_CONFIG.tmp"; mv "$IC_CONFIG.tmp" "$IC_CONFIG"
ic_apply_hot
[ "$(cat "$IC_STATE/global")" = 0 ]
sed 's/display.dynamic=1/display.dynamic=0/' "$IC_CONFIG" > "$IC_CONFIG.tmp"; mv "$IC_CONFIG.tmp" "$IC_CONFIG"
ic_apply_hot
[ "$(cat "$IC_STATE/global")" = 1 ]
sed 's/display.dynamic=0/display.dynamic=1/' "$IC_CONFIG" > "$IC_CONFIG.tmp"; mv "$IC_CONFIG.tmp" "$IC_CONFIG"
ic_apply_hot
[ "$(cat "$IC_STATE/global")" = 0 ]
[ "$(find "$CS_DIR/history" -type f | wc -l)" = 1 ]
echo 1 > "$IC_STATE/global"
ic_apply_hot
[ "$(cat "$IC_STATE/global")" = 1 ]
''')
