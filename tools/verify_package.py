"""离线复核构建产物和内置后端，不连接设备。"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import zipfile


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--build',required=True,type=Path);a=p.parse_args()
    manifest=json.loads((a.build/'build-manifest.json').read_text(encoding='utf-8'))
    version=manifest['version'];suffix='-unsigned' if manifest.get('signing')=='unsigned' else ''
    apk=a.build/('InkControl-v'+version+suffix+'.apk');backend=a.build/('inkcontrol_backend-v'+version+suffix+'.zip')
    for name,expected in manifest['artifacts'].items():
        assert hashlib.sha256((a.build/name).read_bytes()).hexdigest()==expected,name
    with zipfile.ZipFile(apk) as app,zipfile.ZipFile(backend) as module:
        assert app.testzip() is None and module.testzip() is None
        assert app.read('assets/inkcontrol_backend.zip')==backend.read_bytes()
        assert app.read('assets/xposed_init').decode().strip()=='com.inkcontrol.platform.HookDispatcher'
        assert 'id=inkcontrol_backend\n' in module.read('module.prop').decode()
        for line in module.read('checksums.sha256').decode().splitlines():
            expected,name=line.split('  ',1)
            assert hashlib.sha256(module.read(name)).hexdigest()==expected,name
        config=module.read('lib/defaults.txt').decode()
        flags=[line for line in config.splitlines() if '=' in line and not line.split('=')[0].endswith('.value') and line.split('=')[0] not in ('version','revision')]
        schema=module.read('lib/features.tsv').decode().splitlines()
        # 默认开启的只有这几项：必选参数（无开关）与总开关默认关闭时不产生效果的子项。
        # 其余增强必须全部默认关闭；这张表变化即默认配置变化，必须显式评审。
        default_on={'lock.ext.wifi','lock.ext.bt','lock.ext.audio','lock.ext.airplane','qs.panel.pinsize',
                    'pageanim.style','pageanim.spacing','pageanim.bands','pageanim.vkey'}
        assert len(flags)==len(schema) and all(line.endswith(('=0','=1')) for line in flags)
        assert {line.split('=')[0] for line in flags if line.endswith('=1')}==default_on
        assert {line.split('=')[0] for line in flags}=={line.split('\t')[0] for line in schema}
        assert 'GmsSettingsIconFix.apk' not in {n.filename for n in module.infolist()}, '图标覆盖已下线，不应再打包'
        result={'version':version,'signing':manifest.get('signing','legacy-build'),'apk_sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'zip_sha256':hashlib.sha256(backend.read_bytes()).hexdigest(),
                'default_off':sum(line.endswith('=0') for line in flags),'module_checksums':True,'embedded_zip_identical':True}
    (a.build/'package-verification.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8');print(json.dumps(result,ensure_ascii=False))


if __name__=='__main__':main()
