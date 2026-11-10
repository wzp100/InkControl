"""Cloud-emulator smoke test. Signs a temporary UI fixture, never a distributable app."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build',type=Path,required=True)
    args = parser.parse_args()
    if os.environ.get('GITHUB_ACTIONS') != 'true': raise ValueError('Cloud emulator only')
    adb = Path(os.environ['ANDROID_HOME'])/'platform-tools/adb'
    serial = 'emulator-5554'
    def command(*argv, timeout=30):
        return subprocess.run(list(map(str,argv)),capture_output=True,check=True,timeout=timeout).stdout
    def device(*argv, timeout=30): return command(adb,'-s',serial,*argv,timeout=timeout)
    for attempt in range(120):
        emulator_log = ROOT/'out/ui/emulator.log'
        if emulator_log.exists() and 'FATAL' in emulator_log.read_text(encoding='utf-8',errors='replace'):
            raise RuntimeError('Cloud emulator failed; see emulator.log')
        try:
            if device('shell','getprop','sys.boot_completed',timeout=5).strip()==b'1': break
        except (subprocess.SubprocessError, OSError): pass
        time.sleep(2)
    else: raise RuntimeError('Cloud emulator boot timed out')
    if device('shell','getprop','ro.kernel.qemu').strip()!=b'1': raise ValueError('Not an emulator')
    work = Path(os.environ['RUNNER_TEMP'])/'ui-fixture';work.mkdir()
    key = work/'ui.jks'
    command(Path(os.environ['JAVA_HOME'])/'bin/keytool','-genkeypair','-keystore',key,'-alias','ui-only','-keyalg','RSA','-keysize','2048','-validity','2','-storepass','android','-keypass','android','-dname','CN=Ephemeral UI Test Only')
    apk = next(args.build.glob('InkControl-*.apk'))
    fixture = work/'ui-fixture.apk'
    bt=Path(os.environ['ANDROID_HOME'])/'build-tools/35.0.0'
    command(Path(os.environ['JAVA_HOME'])/'bin/java','-jar',bt/'lib/apksigner.jar','sign','--ks',key,'--ks-key-alias','ui-only','--ks-pass','pass:android','--key-pass','pass:android','--out',fixture,apk)
    evidence=ROOT/'out/ui';evidence.mkdir(exist_ok=True,parents=True)
    results=[]
    def dump():
        device('shell','uiautomator','dump','/sdcard/inkcontrol-ci.xml',timeout=25)
        return device('exec-out','cat','/sdcard/inkcontrol-ci.xml')
    size=list(map(int,re.findall(r'(\d+)x(\d+)',device('shell','wm','size').decode('utf-8'))[-1]))
    def swipe(up):
        x=size[0]//2;top,bottom=size[1]//4,size[1]*3//4
        device('shell','input','swipe',str(x),str(bottom if up else top),str(x),str(top if up else bottom),'300')
        time.sleep(1)
    def lookup(text):
        # Headings can repeat a control's label; prefer the node that actually accepts the tap.
        matches=[n for n in ET.fromstring(dump()).iter('node') if n.get('text')==text or n.get('content-desc')==text]
        matches.sort(key=lambda n:n.get('clickable')!='true')
        return matches[0] if matches else None
    def find(text):
        # Module settings is longer than a small emulator screen; scroll down until the control appears.
        for attempt in range(8):
            node=lookup(text)
            if node is not None: return node
            swipe(True)
        raise AssertionError('Missing UI control: '+text)
    def tap(text):
        node=find(text)
        left,top,right,bottom=map(int,re.findall(r'\d+',node.get('bounds')))
        device('shell','input','tap',str((left+right)//2),str((top+bottom)//2))
        time.sleep(1)
    def module(tab,marker):
        # Install, diagnostics and the language switch live on the module-settings tab.
        for attempt in range(4): swipe(False)
        if lookup(marker) is None: tap(tab)
        for attempt in range(4): swipe(False)
    def capture(name,expected):
        raw=dump();tree=ET.fromstring(raw)
        texts={n.get('text') for n in tree.iter('node')}
        for text in expected: assert text in texts,(name,text)
        if 'InkControl' in expected:
            density_text=device('shell','wm','density').decode('utf-8')
            density=int(re.findall(r'density:\s*(\d+)',density_text)[-1])/160.0
            brand=next(n for n in tree.iter('node') if n.get('text')=='InkControl')
            left,top,right,bottom=map(int,re.findall(r'\d+',brand.get('bounds')))
            assert bottom-top <= 45*density, 'English brand wrapped on narrow screen'
        (evidence/(name+'.xml')).write_bytes(raw)
        (evidence/(name+'.png')).write_bytes(device('exec-out','screencap','-p'))
        results.append({'page':name,'expected':expected,'passed':True})
    try:
        # Cold hosted emulators can finish booting while PackageManager is still busy.
        device('install',fixture,timeout=180)
        device('shell','input','keyevent','KEYCODE_WAKEUP')
        device('shell','wm','dismiss-keyguard')
        device('shell','am','start','-W','-n','com.inkcontrol/.ui.MainActivity')
        time.sleep(2)
        module('Module settings','Install')
        capture('english-system',['InkControl','Features','Install','Diagnostics'])
        tap('Language / Language');tap('简体中文')
        module('模块设置','安装')
        capture('chinese-explicit',['墨控','功能','安装','诊断'])
        tap('语言 / Language');tap('English')
        module('Module settings','Install')
        capture('english-explicit',['InkControl','Features','Install','Diagnostics'])
        # Recreate/process restart must preserve the user's language choice.
        device('shell','am','force-stop','com.inkcontrol')
        device('shell','am','start','-W','-n','com.inkcontrol/.ui.MainActivity')
        time.sleep(1)
        module('Module settings','Install')
        capture('english-after-restart',['InkControl','Features','Install','Diagnostics'])
    except Exception:
        # Preserve the original failure even if a busy emulator cannot provide diagnostics.
        try:
            (evidence/'failure-logcat.txt').write_bytes(device('logcat','-d','-t','400',timeout=15))
        except (subprocess.SubprocessError, OSError): pass
        raise
    finally:
        device('uninstall','com.inkcontrol')
        (evidence/'language-results.json').write_text(json.dumps({'fixture':'temporary emulator-only certificate; never distributed','checks':results},ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('Cloud language smoke tests passed:',len(results),'configurations')

if __name__=='__main__':main()
