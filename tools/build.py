"""独立离线构建：核心测试 → native → Magisk ZIP → APK → 完整性验证。绝不操作手机。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
VERSION = '0.2.7-r27'
INPUTS = {
    'hwcomposer.stock.so': 'a95b9f92df5c4c37633fae5e15aa216359d314f58a214375a07a5a83e26e6502',
    'hwcomposer.motion.so': 'adbdf9aeb1868e1fb9bf01394a8f67942adb9b3c97a93d6ec2d168f83e60a675',
    # 翻页动画：第三方 HWC 派生库与两个原生小工具，逐字节校验后打进同一个后端模块。
    # 构建输入随仓库放在 inputs/（2026-11-09 起入库，权利归原权利人，见 NOTICE.md）；
    # 云端构建另有 ci/inputs.json 记录同一批文件的哈希。
    'hwcomposer.pageanim.so': '3e241e4bab0d9b79f9506543bbc68da069b1ae7954d25f45598d55e564eaf478',
    'a5pro-resident': '75c64d1f22da9f22a84f57665f231bfc97b29589366b5c193719159e4fc442ee',
    'a5pro-control-once': 'a79f49f05f984cf51e1b20126aa73aff72aab79841b7b410c13b96bc6e8bec01',
}
# 工具链是逐段手工调用（aapt2 / javac / d8 / zipalign / apksigner），每一步都会留下文件。
# 逐段产物全部落在 out/.work/<name>/（不再塞进产物目录），构建前后各清一次：
# 即使收尾清理失败，下一次构建开头也会整体清空，不会像旧版那样每个版本攒一份 13 MiB 的 work/。
OUT_ROOT = ROOT/'out'
SCRATCH = OUT_ROOT/'.work'
CACHE_BUDGET = 160   # 缓存上限 MiB；超出按「最久没命中」先删，0 表示不限制
KEEP_BUILDS = 3      # 默认只留最近 3 个产物目录

def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()

def run(*args, cwd=ROOT):
    env = dict(os.environ, JAVA_TOOL_OPTIONS='-Duser.language=en -Dfile.encoding=UTF-8', PYTHONUTF8='1', PYTHONIOENCODING='utf-8')
    # aapt2 在 Windows 上不接受含中文的绝对路径，项目路径使用相对路径。
    argv = [str(a.relative_to(cwd)) if isinstance(a, Path) and a.is_relative_to(cwd) else str(a) for a in args]
    subprocess.run(argv, check=True, cwd=cwd, env=env)

def size_of(path):
    if path.is_file(): return path.stat().st_size
    return sum(p.stat().st_size for p in path.rglob('*') if p.is_file())

def mib(value): return f'{value/1048576:.1f} MiB'

def clear_scratch():
    """构建前的兜底：中间文件根目录整体清空。清不掉就停下报错，绝不静默累积。"""
    if not SCRATCH.exists(): return
    try: shutil.rmtree(SCRATCH)
    except OSError as error:
        raise SystemExit('无法清理中间文件目录，请手动删除后重试：'+SCRATCH.as_posix()+'\n'+str(error))

def tidy(work, keep):
    """work/ 只服务构建过程；默认整目录清掉，排查时保留。失败不影响产物，下次构建开头会兜底。"""
    if keep: return
    try:
        shutil.rmtree(work)
        if SCRATCH.exists() and not any(SCRATCH.iterdir()): SCRATCH.rmdir()
    except OSError as error: print('中间文件清理失败（下次构建会自动清空）：'+work.as_posix()+' '+str(error))

def prune_builds(keep, protect=None):
    """产物目录按时间只留最近 keep 个；旧版遗留在产物目录里的 work/ 也一并清掉。"""
    if keep < 0 or not OUT_ROOT.is_dir(): return
    builds = sorted((p for p in OUT_ROOT.iterdir() if p.is_dir() and not p.name.startswith('.')),
                    key=lambda p: p.stat().st_mtime, reverse=True)
    doomed = [p for p in builds[keep:] if p.name != protect]   # 本次构建的目录永远保留
    freed = 0
    for path in doomed:
        size = size_of(path)
        try: shutil.rmtree(path); freed += size
        except OSError as error: print('旧产物清理失败：'+path.name+' '+str(error))
    if doomed: print('清理旧产物 '+str(len(doomed))+' 个，回收 '+mib(freed)+'：'+'、'.join(p.name for p in doomed))
    for path in builds:
        if path.name == protect: continue
        legacy = path/'work'
        if legacy.is_dir():
            size = size_of(legacy)
            try: shutil.rmtree(legacy); print('清掉旧版残留中间文件 '+path.name+'/work（'+mib(size)+'）')
            except OSError as error: print('残留清理失败：'+legacy.as_posix()+' '+str(error))

def prune_cache(budget):
    """缓存按命中时间淘汰：命中的条目刷新 mtime，最久没用的先删，总占用收在预算内。"""
    if budget <= 0 or not CACHE.is_dir(): return
    entries = [p for p in CACHE.iterdir() if p.is_dir()]
    entries.sort(key=lambda p: (p/'ok').stat().st_mtime if (p/'ok').exists() else 0)
    sizes = {p: size_of(p) for p in entries}
    total, limit, freed, removed = sum(sizes.values()), budget*1048576, 0, 0
    for entry in entries:
        if total <= limit: break
        try: shutil.rmtree(entry); total -= sizes[entry]; freed += sizes[entry]; removed += 1
        except OSError: pass
    if removed: print('构建缓存淘汰 '+str(removed)+' 项，回收 '+mib(freed)+'（现 '+mib(total)+'）')

# 内容寻址缓存：输入相同就复用上次的编译产物，跨构建共享；条目与 work/ 之间用硬链接，不额外占盘。
CACHE = OUT_ROOT/'.build-cache'
HITS = []

def _link(source, target):
    # copytree 的 copy_function 传入的是字符串路径，这里统一转回 Path。
    source, target = Path(source), Path(target)
    target.parent.mkdir(parents=True, exist_ok=True)
    try: os.link(source, target)
    except OSError: shutil.copy2(source, target)
    return str(target)

def _restore(source, target):
    if source.is_dir(): shutil.copytree(source, target, copy_function=_link)
    else: _link(source, target)

def tree_stamp(paths, salt=''):
    """把一组文件的内容与路径折成缓存键；源码/工具链/参数任一变化都会换键。"""
    digest = hashlib.sha256(salt.encode())
    for path in paths:
        items = [path] if path.is_file() else sorted(p for p in path.rglob('*') if p.is_file())
        for item in items: digest.update(item.relative_to(ROOT).as_posix().encode()+b'\0'+sha(item).encode())
    return digest.hexdigest()[:32]

def step(label, key, targets, build, enabled=True):
    """命中缓存就还原产物，否则执行并把结果写入缓存。targets 形如 {缓存名: 目标路径}。"""
    entry = CACHE/hashlib.sha256(key.encode()).hexdigest()[:32]
    if enabled and (entry/'ok').is_file() and all((entry/name).exists() for name in targets):
        for name, target in targets.items(): _restore(entry/name, target)
        HITS.append(label); return
    build()
    if not enabled: return
    entry.mkdir(parents=True, exist_ok=True)
    for name, target in targets.items():
        if not (entry/name).exists(): _restore(target, entry/name)
    (entry/'ok').touch()

def zip_tree(directory, target):
    with zipfile.ZipFile(target, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path in sorted(directory.rglob('*')):
            if not path.is_file(): continue
            name = path.relative_to(directory).as_posix()
            info = zipfile.ZipInfo(name, (2026, 10, 15, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = (0o100755 if name.endswith('.sh') or name in ('bin/inkctl', 'bin/eink_status', 'META-INF/com/google/android/update-binary') else 0o100644) << 16
            archive.writestr(info, path.read_bytes())

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--name', default='candidate-1')
    parser.add_argument('--sdk', default=os.environ.get('ANDROID_HOME'))
    parser.add_argument('--jdk', default=os.environ.get('JAVA_HOME'))
    parser.add_argument('--ndk', default=os.environ.get('ANDROID_NDK_HOME'))
    parser.add_argument('--inputs', type=Path, default=ROOT/'inputs')
    parser.add_argument('--keystore', type=Path, default=os.environ.get('INKCONTROL_KEYSTORE'))
    parser.add_argument('--key-alias', default=os.environ.get('INKCONTROL_KEY_ALIAS', 'inkcontrol'))
    parser.add_argument('--test-only', action='store_true')
    parser.add_argument('--unsigned', action='store_true', help='生成不可安装的待签名候选，不读取签名密码')
    parser.add_argument('--certificate', type=Path, help='待签名构建使用的现有 DER 公钥证书，不是私钥')
    parser.add_argument('--keep-intermediates', action='store_true', help='保留 out/.work/<name>/ 下的逐段中间文件，默认构建结束即删除')
    parser.add_argument('--no-cache', action='store_true', help='不读也不写 out/.build-cache，强制全部重新编译')
    parser.add_argument('--keep-builds', type=int, default=KEEP_BUILDS, help='构建后产物目录只留最近 N 个（默认 3；负值表示不清理）')
    parser.add_argument('--cache-budget', type=int, default=CACHE_BUDGET, help='构建缓存上限 MiB（默认 160；0 表示不限制）')
    parser.add_argument('--clean', action='store_true', help='只清理（旧产物目录、残留 work/、超限缓存），不构建')
    args = parser.parse_args()
    if Path(args.name).name != args.name or args.name in ('.', '..'): raise ValueError('invalid build name')
    if args.clean:
        prune_builds(args.keep_builds); prune_cache(args.cache_budget)
        total = size_of(OUT_ROOT) if OUT_ROOT.exists() else 0
        print('out/ 现占用 '+mib(total))
        return
    if not args.jdk: raise ValueError('请设置 JAVA_HOME 或 --jdk')
    jdk = Path(args.jdk)/'bin'
    exe = '.exe' if os.name == 'nt' else ''
    java, javac = jdk/('java'+exe), jdk/('javac'+exe)
    out = OUT_ROOT/args.name
    out.mkdir(parents=True, exist_ok=False)
    clear_scratch()
    work = SCRATCH/args.name; work.mkdir(parents=True)
    host = work/'host'

    def drop(*paths):
        """用完即弃的中间产物；--keep-intermediates 时全部保留供排查。"""
        if args.keep_intermediates: return
        for path in paths:
            try: shutil.rmtree(path) if path.is_dir() else path.unlink()
            except OSError: pass
    step('核心类编译', tree_stamp([ROOT/'src/com/inkcontrol/core', ROOT/'test/CoreTest.java', ROOT/'test/CatalogExport.java'], 'javac8|'+Path(args.jdk).name), {'host': host}, lambda: run(javac, '-encoding', 'UTF-8', '--release', '8', '-d', host, *sorted((ROOT/'src/com/inkcontrol/core').glob('*.java')), ROOT/'test/CoreTest.java', ROOT/'test/CatalogExport.java'), not args.no_cache)
    run(java, '-cp', host, 'CoreTest')
    module = work/'module'; shutil.copytree(ROOT/'backend', module)
    # 控制器以字面版本判断后端是否匹配，须与构建版本一致。
    assert "= '"+VERSION+"' ]" in (module/'lib/common.sh').read_text(encoding='utf-8'), 'common.sh 版本与构建版本不一致'
    run(java, '-cp', host, 'CatalogExport', module/'lib')
    if args.test_only: tidy(work, args.keep_intermediates); return
    if not args.sdk or not args.ndk: raise ValueError('需要 --sdk、--ndk')
    if args.unsigned:
        if not args.certificate or not args.certificate.is_file(): raise ValueError('--unsigned 需要 --certificate 指定现有签名的 DER 公钥证书')
    elif not args.keystore: raise ValueError('需要 --keystore（密码从 INKCONTROL_STORE_PASSWORD / INKCONTROL_KEY_PASSWORD 环境变量读取）')
    for name, expected in INPUTS.items():
        if sha(args.inputs/name) != expected: raise ValueError('输入版本不匹配：'+name)
    sdk, ndk = Path(args.sdk), Path(args.ndk)
    android = sdk/'platforms/android-33/android.jar'; bt = sdk/'build-tools/35.0.0'
    prebuilt = 'windows-x86_64' if os.name == 'nt' else 'linux-x86_64'
    clang = ndk/'toolchains/llvm/prebuilt'/prebuilt/'bin'/('clang++'+exe)
    payload = module/'payload'; payload.mkdir()
    native_key = tree_stamp([ROOT/'native'], 'clang29|'+ndk.name)
    step('GPU 服务编译', native_key, {'libeinkgpu.so': payload/'libeinkgpu.so'}, lambda: run(clang, '--target=aarch64-linux-android29', '-std=c++17', '-O2', '-fPIC', '-shared', '-static-libstdc++', '-Wall', '-Wextra', '-Werror', ROOT/'native/service_gpu.cpp', '-lEGL', '-lGLESv3', '-lnativewindow', '-llog', '-ldl', '-Wl,-soname,libeinkgpu.so', '-o', payload/'libeinkgpu.so'), not args.no_cache)
    step('状态观察器编译', native_key, {'eink_status': module/'bin/eink_status'}, lambda: run(clang, '--target=aarch64-linux-android29', '-std=c++17', '-O2', '-static-libstdc++', ROOT/'native/service_observer.cpp', '-o', module/'bin/eink_status'), not args.no_cache)
    motion = (args.inputs/'hwcomposer.motion.so').read_bytes()
    stock = (args.inputs/'hwcomposer.stock.so').read_bytes()
    default = bytearray(motion); default[0x92bcc:0x92bd4] = stock[0x92bcc:0x92bd4]
    (payload/'hwcomposer.default.so').write_bytes(default)
    (payload/'hwcomposer.idle10.so').write_bytes(motion)
    # 翻页动画的 HWC 与墨控的两份是同一个 /vendor 文件，不能同时挂载；
    # post-fs-data.sh 按 pageanim.master 在这三份里选一份。
    (payload/'hwcomposer.pageanim.so').write_bytes((args.inputs/'hwcomposer.pageanim.so').read_bytes())
    # resident 里三条硬编码路径原本指向独立模块目录，改成墨控自己的状态目录。
    # 新前缀更短，剩余字节补 0，保持 C 字符串长度与所有偏移不变。
    resident = bytearray((args.inputs/'a5pro-resident').read_bytes())
    old = b'/data/adb/modules/a5pro_epd_probe'
    new = b'/data/adb/inkcontrol/pageanim'
    hits = 0
    at = 0
    while True:
        at = resident.find(old, at)
        if at < 0: break
        end = resident.find(b'\x00', at)
        if end < 0: raise ValueError('resident 路径字符串没有结束符')
        replacement = (new + bytes(resident[at + len(old):end])).ljust(end - at, b'\x00')
        resident[at:end] = replacement
        hits += 1
        at = end + 1
    if hits != 3: raise ValueError('resident 路径数量异常：%d' % hits)
    (module/'bin/a5pro-resident').write_bytes(bytes(resident))
    (module/'bin/a5pro-control-once').write_bytes((args.inputs/'a5pro-control-once').read_bytes())
    (module/'module.prop').write_text('id=inkcontrol_backend\nname=墨控显示后端\nversion='+VERSION+'\nversionCode=27\nauthor=InkControl contributors\ndescription=墨控统一后端；增强默认关闭，通过App逐项开启。\n', encoding='utf-8', newline='\n')
    (module/'checksums.sha256').write_text(''.join(sha(p)+'  '+p.relative_to(module).as_posix()+'\n' for p in sorted(module.rglob('*')) if p.is_file() and p.name != 'customize.sh' and 'META-INF' not in p.parts), encoding='utf-8', newline='\n')
    assert b'\r' not in (module/'module.prop').read_bytes() + (module/'checksums.sha256').read_bytes()
    suffix = '-unsigned' if args.unsigned else ''
    backend = out/('inkcontrol_backend-v'+VERSION+suffix+'.zip'); zip_tree(module, backend)
    drop(module)   # 模块树已打进 zip，之后再无引用
    cert = out/'cert.der'
    if args.unsigned: shutil.copy2(args.certificate, cert)
    else: run(jdk/('keytool'+exe), '-exportcert', '-keystore', args.keystore, '-alias', args.key_alias, '-storepass:env', 'INKCONTROL_STORE_PASSWORD', '-file', cert)
    generated = work/'generated/com/inkcontrol/platform/BuildInfo.java'; generated.parent.mkdir(parents=True)
    generated.write_text('package com.inkcontrol.platform;\npublic final class BuildInfo {\n public static final String VERSION="'+VERSION+'", CERT_SHA256="'+sha(cert)+'", BACKEND_SHA256="'+sha(backend)+'";\n public static final boolean DEBUG=false;\n private BuildInfo() {}\n}\n', encoding='utf-8')
    android_salt = 'android-33|'+sha(android)
    stub_key = tree_stamp([ROOT/'stubs'], 'javac8|'+android_salt)
    def build_stubs():
        (work/'stubs').mkdir()
        run(javac, '-encoding', 'UTF-8', '--release', '8', '-cp', android, '-d', work/'stubs', *sorted((ROOT/'stubs').rglob('*.java')))
    step('桩类编译', stub_key, {'stubs': work/'stubs'}, build_stubs, not args.no_cache)
    classes = work/'classes.jar'
    def build_classes():
        (work/'classes').mkdir()
        run(javac, '-encoding', 'UTF-8', '--release', '8', '-cp', str(android)+os.pathsep+str(work/'stubs'), '-d', work/'classes', *sorted((ROOT/'src').rglob('*.java')), generated)
        with zipfile.ZipFile(classes, 'w') as archive:
            for entry in sorted((work/'classes').rglob('*.class')): archive.write(entry, entry.relative_to(work/'classes').as_posix())
    step('应用类编译', tree_stamp([ROOT/'src'], 'javac8|'+android_salt+'|'+stub_key+'|'+sha(generated)), {'classes': work/'classes', 'classes.jar': classes}, build_classes, not args.no_cache)
    def build_dex():
        (work/'dex').mkdir()
        run(java, '-cp', bt/'lib/d8.jar', 'com.android.tools.r8.D8', '--release', '--min-api', '29', '--lib', android, '--classpath', work/'stubs', '--output', work/'dex', classes)
    step('dex 转换', 'd8|'+android_salt+'|'+sha(classes), {'dex': work/'dex'}, build_dex, not args.no_cache)
    drop(work/'classes', classes)   # d8 之后 class 文件不再被引用（缓存里另有硬链接副本）
    step('应用资源编译', tree_stamp([ROOT/'res'], 'aapt2|'+bt.name), {'res.zip': work/'res.zip'}, lambda: run(bt/('aapt2'+exe), 'compile', '--dir', ROOT/'res', '-o', work/'res.zip'), not args.no_cache)
    run(bt/('aapt2'+exe), 'link', '-I', android, '--manifest', ROOT/'AndroidManifest.xml', '-o', work/'unsigned.apk', work/'res.zip')
    with zipfile.ZipFile(work/'unsigned.apk', 'a', zipfile.ZIP_DEFLATED) as archive:
        # Windows aapt2 的嵌套 assets 头使用反斜线，统一用 Python 写标准 POSIX 名称。
        entries = [(asset.relative_to(ROOT/'assets').as_posix(), asset) for asset in (ROOT/'assets').rglob('*') if asset.is_file()]
        entries.append(('inkcontrol_backend.zip', backend))
        for name, asset in sorted(entries): archive.write(asset, 'assets/'+name)
        for dex in sorted((work/'dex').glob('*.dex')): archive.write(dex, dex.name)
    # 统一 ZIP 头部与编码，避免 aapt2/Python 组合 ZIP 的 header mismatch。
    with zipfile.ZipFile(work/'unsigned.apk') as source, zipfile.ZipFile(work/'normalized.apk', 'w', zipfile.ZIP_DEFLATED) as target:
        for info in source.infolist(): target.writestr(info.filename, source.read(info.filename))
    drop(work/'unsigned.apk')   # 上一段产物已经用完，立刻释放，峰值少 2 MiB
    run(bt/('zipalign'+exe), '-f', '4', work/'normalized.apk', work/'aligned.apk')
    drop(work/'normalized.apk')
    apk = out/('InkControl-v'+VERSION+suffix+'.apk')
    if args.unsigned: shutil.copy2(work/'aligned.apk', apk)
    else:
        run(java, '-jar', bt/'lib/apksigner.jar', 'sign', '--v4-signing-enabled', 'false', '--ks', args.keystore, '--ks-key-alias', args.key_alias, '--ks-pass', 'env:INKCONTROL_STORE_PASSWORD', '--key-pass', 'env:INKCONTROL_KEY_PASSWORD', '--out', apk, work/'aligned.apk')
        run(java, '-jar', bt/'lib/apksigner.jar', 'verify', '--verbose', '--print-certs', apk)
    with zipfile.ZipFile(apk) as archive:
        assert archive.testzip() is None
        assert hashlib.sha256(archive.read('assets/inkcontrol_backend.zip')).hexdigest() == sha(backend)
        assert archive.read('assets/xposed_init').decode().strip() == 'com.inkcontrol.platform.HookDispatcher'
        assert 'classes.dex' in archive.namelist()
    with zipfile.ZipFile(backend) as archive: assert archive.testzip() is None
    manifest = {'version': VERSION, 'signing': 'unsigned' if args.unsigned else 'verified', 'verification': 'offline-only; device acceptance pending', 'artifacts': {p.name: sha(p) for p in (apk, backend)}, 'inputs': {name: sha(args.inputs/name) for name in INPUTS}, 'sources': {p.relative_to(ROOT).as_posix(): sha(p) for directory in ('src', 'native', 'backend', 'assets', 'res', 'overlay', 'stubs', 'tools', 'test') for p in sorted((ROOT/directory).rglob('*')) if p.is_file() and '__pycache__' not in str(p) and 'config_monitor_' not in str(p)}}
    manifest['sources']['AndroidManifest.xml'] = sha(ROOT/'AndroidManifest.xml')
    for directory in ('.github', 'ci'):
        for path in sorted((ROOT/directory).rglob('*')):
            if path.is_file(): manifest['sources'][path.relative_to(ROOT).as_posix()] = sha(path)
    (out/'build-manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    (out/'SHA256SUMS').write_text(''.join(sha(p)+'  '+p.name+'\n' for p in (apk, backend)), encoding='utf-8')
    tidy(work, args.keep_intermediates)
    prune_builds(args.keep_builds, protect=args.name)
    prune_cache(args.cache_budget)
    print('缓存复用 '+str(len(HITS))+' 项'+(('：'+'、'.join(HITS)) if HITS else '（本次全部重新编译）'))
    print('构建输出 '+out.relative_to(ROOT).as_posix()+'（'+('含 out/.work 中间文件' if args.keep_intermediates else '仅可交付产物')+'）：')
    for path in sorted(p for p in out.iterdir() if p.is_file()):
        print('  ' + path.name + '  ' + f'{path.stat().st_size/1048576:.2f} MiB')
    print('out/ 现占用 '+mib(size_of(OUT_ROOT)))

if __name__ == '__main__': main()
