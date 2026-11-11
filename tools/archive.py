"""不可覆盖的候选归档；源码采用白名单，不复制私钥、固件输入和设备数据。"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import zipfile

ROOT = Path(__file__).resolve().parents[1]
DIRECTORIES = ('src', 'native', 'backend', 'assets', 'res', 'stubs', 'tools', 'test', 'docs', '.github', 'ci')
FILES = ('AndroidManifest.xml', 'README.md', 'README.en.md', 'NOTICE.md', 'NOTICE.en.md', 'LICENSE', '.gitignore', '.gitattributes', 'AGENTS.md', 'CONTRIBUTING.md', 'CONTRIBUTING.en.md', 'llms.txt')

def digest(path): return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build', required=True, type=Path)
    parser.add_argument('--tests', required=True, type=Path)
    parser.add_argument('--verification', required=True, type=Path)
    reports = parser.add_mutually_exclusive_group(required=True)
    reports.add_argument('--emulator-report', type=Path)
    reports.add_argument('--device-report', type=Path)
    parser.add_argument('--destination', required=True, type=Path)
    args = parser.parse_args()
    manifest = json.loads((args.build/'build-manifest.json').read_text(encoding='utf-8'))
    if manifest.get('signing') == 'unsigned':
        raise ValueError('待签名候选不能走安装发布归档；请先沿用原密钥重新构建并验证签名')
    tests = json.loads(args.tests.read_text(encoding='utf-8'))
    if not tests or any(entry['exit'] != 0 for entry in tests): raise ValueError('测试未全部通过')
    required = {'CoreTest', 'GeometryTest', 'com.inkcontrol.features.fix.PolicyTrackerTest',
                'com.inkcontrol.features.fix.HookIntegrationTest', 'com.inkcontrol.platform.RuntimeStatusTest', 'GuideTest', 'TextCatalogTest', 'CatalogExport'}
    executed = {word for entry in tests for word in entry['command']}
    if not required <= executed or 'unittest' not in tests[-1]['command']: raise ValueError('测试未完整执行')
    report_path = args.device_report or args.emulator_report
    report = json.loads(report_path.read_text(encoding='utf-8'))
    if report.get('apk_sha256') != manifest['artifacts']['InkControl-v'+manifest['version']+'.apk']: raise ValueError('设备验证记录不属于当前 APK')
    if report.get('zip_sha256') != manifest['artifacts']['inkcontrol_backend-v'+manifest['version']+'.zip']: raise ValueError('设备验证记录的 ZIP 不匹配')
    if args.device_report and (report.get('device') != 'HLTE203T' or report.get('final_state') != 'all-off-stock-backend'): raise ValueError('缺少目标设备与最终恢复状态')
    # 任何运行源码与构建快照不同，均拒绝把旧 APK 配上新源码。
    for name, expected in manifest['sources'].items():
        if (name == 'AndroidManifest.xml' or name.startswith(('src/', 'native/', 'backend/', 'assets/', 'res/', 'stubs/'))) and digest(ROOT/name) != expected: raise ValueError('构建后源码变动，请重建：'+name)
    for name, expected in manifest['artifacts'].items():
        if digest(args.build/name) != expected: raise ValueError('产物校验失败：'+name)
        with zipfile.ZipFile(args.build/name) as archive:
            if archive.testzip() is not None: raise ValueError('ZIP CRC错误：'+name)
    if not args.verification.is_file(): raise ValueError('缺少实际验证记录')
    args.destination.mkdir(parents=True, exist_ok=False)
    for name in manifest['artifacts']: shutil.copy2(args.build/name, args.destination/name)
    shutil.copy2(args.build/'build-manifest.json', args.destination/'build-manifest.json')
    def public_value(value):
        if isinstance(value, str):
            return value.replace(str(Path.home()), '${USER_HOME}').replace(Path.home().as_posix(), '${USER_HOME}')
        if isinstance(value, list): return [public_value(item) for item in value]
        if isinstance(value, dict): return {key: public_value(item) for key, item in value.items()}
        return value
    (args.destination/'offline-tests.json').write_text(json.dumps(public_value(tests), ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    shutil.copy2(report_path, args.destination/('device-acceptance.json' if args.device_report else 'emulator-smoke.json'))
    shutil.copy2(args.build/'package-verification.json', args.destination/'package-verification.json')
    shutil.copy2(args.verification, args.destination/'verification.md')
    shutil.copy2(ROOT/'docs/sources.md', args.destination/'sources.md')
    public = args.destination/('InkControl-source-v'+manifest['version']+'.zip')
    paths = [ROOT/name for name in FILES]
    paths += [p for name in DIRECTORIES for p in sorted((ROOT/name).rglob('*')) if p.is_file() and '__pycache__' not in p.parts and not any(part.startswith('config_monitor_') for part in p.parts) and not (name == 'docs' and (p.name.startswith('20') or 'evidence' in p.parts or 'local-history' in p.parts)) and p.suffix != '.enc']
    source_hashes = {}
    with zipfile.ZipFile(public, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path in paths:
            relative = path.relative_to(ROOT).as_posix()
            archive.write(path, 'InkControl/'+relative); source_hashes[relative] = digest(path)
    (args.destination/'source-manifest.json').write_text(json.dumps(source_hashes, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    scope = '已执行本候选的 HLTE203T 实机检查；通过范围、失败项及未覆盖项目见 verification.md 与 device-acceptance.json。' if args.device_report else '已通过电脑端回归和本地模拟器基本页面／无Root／ZIP导出验证；目标 HLTE203T 的整合版完整启动、Hook、媒体与面板验收仍待做。详见 verification.md。'
    if args.device_report and report.get('known_failures'):
        scope += '\n\n已知未通过：' + '；'.join(report['known_failures']) + '。相关增强保持关闭，本候选没有全功能通过结论。'
    (args.destination/'README.md').write_text('# 墨控 v'+manifest['version']+' 本地候选\n\n安装 `InkControl-v'+manifest['version']+'.apk`，从 App 导出 Magisk ZIP并按安装页操作。增强全部默认关闭。\n\n'+scope+'\n\n源码见 InkControl-source ZIP，私有 HWC 输入与签名材料不包含在内。GitHub 尚未公开发布。\n', encoding='utf-8')
    artifacts = sorted(p for p in args.destination.iterdir() if p.is_file())
    (args.destination/'SHA256SUMS').write_text(''.join(digest(p)+'  '+p.name+'\n' for p in artifacts), encoding='utf-8')
    print(args.destination)

if __name__ == '__main__': main()
