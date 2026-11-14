"""显式指定设备的实机记录工具。只执行调用方提供的测试操作，不被构建/归档调用。"""
from pathlib import Path
import json
import os
import shlex
import shutil
import subprocess
import time

class Session:
    def __init__(self, serial, output):
        if not serial or serial.startswith('emulator-'):
            raise ValueError('必须显式指定本次授权的实体设备，禁止自动选择设备')
        self.serial = serial
        self.output = Path(output)
        self.output.mkdir(parents=True, exist_ok=True)
        # 环境变量 ADB > PATH 里的 adb > Windows 上 Android SDK 的默认位置
        found = os.environ.get('ADB') or shutil.which('adb')
        self.adb_path = Path(found) if found else Path(os.environ['LOCALAPPDATA'])/'Android/Sdk/platform-tools/adb.exe'
        product=self.adb('shell','getprop','ro.product.device').stdout.strip()
        if product != b'HLTE203T': raise ValueError('设备不是目标 HLTE203T')

    def adb(self, *args, timeout=40, check=True):
        result = subprocess.run([str(self.adb_path), '-s', self.serial, *map(str,args)], capture_output=True, timeout=timeout)
        if check and result.returncode:
            raise RuntimeError(result.stdout.decode('utf-8','replace')+result.stderr.decode('utf-8','replace'))
        return result

    def root(self, command, timeout=40):
        result = self.adb('shell','su','-c',shlex.quote(command), timeout=timeout)
        return result.stdout.decode('utf-8','replace').strip()

    def record(self, name, command, timeout=40):
        try: result = self.root(command,timeout)
        except Exception as error:
            with (self.output/(name+'.failed.txt')).open('x',encoding='utf-8') as out: out.write(str(error)+'\n')
            raise
        path = self.output/(name+'.txt')
        if path.exists(): raise FileExistsError(path)
        path.write_text(result+'\n',encoding='utf-8')
        return result

    def script(self, name, text, timeout=90):
        path=self.output/(name+'.sh')
        if path.exists(): raise FileExistsError(path)
        path.write_text(text.replace('\r\n','\n'),encoding='utf-8',newline='\n')
        remote='/data/local/tmp/inkcontrol-'+name+'.sh'
        self.adb('push',path,remote)
        return self.record(name,'sh '+shlex.quote(remote),timeout)

    def ui(self, name):
        import xml.etree.ElementTree as ET
        remote='/sdcard/inkcontrol-ui-%d.xml'%time.time_ns()
        dump=self.adb('shell','uiautomator','dump',remote,timeout=20)
        if b'UI hierchary dumped to:' not in dump.stdout: raise RuntimeError(dump.stdout.decode('utf-8','replace'))
        data=self.adb('exec-out','cat',remote).stdout
        self.adb('shell','rm',remote)
        (self.output/(name+'.xml')).write_bytes(data)
        tree=ET.fromstring(data)
        return '\n'.join(n.get('text','')+' '+n.get('bounds','')+' '+n.get('checked','') for n in tree.iter('node') if n.get('text') or n.get('class')=='android.widget.Switch')

    def tap_text(self, text, name):
        import re
        import xml.etree.ElementTree as ET
        self.ui(name)
        tree=ET.parse(self.output/(name+'.xml'))
        nodes=[n for n in tree.iter('node') if n.get('text')==text]
        if len(nodes)!=1: raise ValueError('页面中未找到唯一控件：'+text)
        a,b,c,d=map(int,re.findall(r'\d+',nodes[0].get('bounds')))
        self.adb('shell','input','tap',str((a+c)//2),str((b+d)//2))

    def probe(self, name, operation='state', **arguments):
        argv=['shell','am','instrument','-w','-r','-e','operation',operation]
        for key,value in arguments.items(): argv.extend(['-e',key,str(value)])
        argv.append('com.inkcontrol.test/.FeatureProbe')
        r=self.adb(*argv,timeout=80)
        raw=r.stdout+r.stderr
        (self.output/(name+'.instrument.txt')).write_bytes(raw)
        text=raw.decode('utf-8','replace')
        if 'INSTRUMENTATION_CODE: -1' not in text: raise RuntimeError(text)
        line=next(line for line in text.splitlines() if line.startswith('INSTRUMENTATION_RESULT: result='))
        result=json.loads(line.split('=',1)[1])
        (self.output/(name+'.json')).write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
        # Instrumentation 结束会收回目标应用进程；恢复正常入口后再观察外部组件。
        # 否则验收夹具本身会使 Provider 暂不可见，不能用那段空数据判定组件行为。
        self.adb('shell','am','start','-n','com.inkcontrol/.ui.MainActivity')
        time.sleep(1)
        return result

    def snapshot(self,name):
        text=self.record(name,'''getprop ro.product.device
getprop ro.build.version.sdk
getprop ro.build.version.incremental
getprop ro.build.display.id
cat /proc/sys/kernel/random/boot_id
getprop sys.boot_completed
pidof system_server com.android.systemui com.hmct.vision android.hardware.graphics.composer@2.1-service
sha256sum /vendor/lib64/hw/hwcomposer.ums512.so /vendor/lib64/hw/libeinkgpu.so
for d in /data/adb/modules/*; do echo "MODULE=$d"; cat "$d/module.prop"; ls "$d/disable" "$d/remove" 2>/dev/null; done
echo FRAMEWORK
/data/adb/lspd/cli modules ls --json
echo PROPERTIES
getprop | grep -E 'eink|inkcontrol'
echo MOUNTS
cat /proc/mounts | grep -E 'eink|inkcontrol|/vendor/lib64/hw|/product/overlay'
echo BATTERY
dumpsys battery
echo SPACE
df -h /data
echo SIZES
du -sk /data/adb/modules/* /data/adb/lspd/config
''')
        return text
