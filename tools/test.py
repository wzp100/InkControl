"""电脑端回归；所有 shell 系统接口都用临时目录替身，不连接设备。"""
import argparse
from datetime import datetime
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jdk', default=os.environ.get('JAVA_HOME'))
    parser.add_argument('--fast', action='store_true',
                        help='只跑 Java 侧检查与目录导出，跳过 unittest discover（Windows 上约 5 分钟）')
    args = parser.parse_args()
    if not args.jdk: raise ValueError('请设置 JAVA_HOME 或 --jdk')
    out = ROOT/'out'/('tests-'+datetime.now().strftime('%Y%m%d-%H%M%S'))
    out.mkdir(parents=True, exist_ok=False)
    host = out/'host'; host.mkdir(); schema = out/'schema'; schema.mkdir()
    env = dict(os.environ, JAVA_TOOL_OPTIONS='-Duser.language=en -Dfile.encoding=UTF-8', INKCONTROL_TEST_SCHEMA=str(schema), PYTHONUTF8='1', PYTHONIOENCODING='utf-8')
    records = []
    def run(*args):
        argv = [str(a.relative_to(ROOT)) if isinstance(a, Path) and a.is_relative_to(ROOT) else str(a) for a in args]
        result = subprocess.run(argv, cwd=ROOT, env=env, capture_output=True, text=True, encoding='utf-8', errors='replace')
        records.append({'command': argv, 'exit': result.returncode, 'stdout': result.stdout, 'stderr': result.stderr})
        (out/'results.json').write_text(json.dumps(records, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
        print(result.stdout, end=''); print(result.stderr, end='', file=sys.stderr)
        if result.returncode: raise RuntimeError('测试失败，见 '+str(out/'results.json'))
    exe = '.exe' if os.name == 'nt' else ''
    javac, java = Path(args.jdk)/'bin'/('javac'+exe), Path(args.jdk)/'bin'/('java'+exe)
    run(javac, '-encoding', 'UTF-8', '--release', '8', '-d', host,
        *sorted((ROOT/'src/com/inkcontrol/core').glob('*.java')),
        *sorted((ROOT/'src/com/inkcontrol/i18n').glob('*.java')),
        *[ROOT/('src/com/inkcontrol/features/'+p) for p in ('motion/Config.java','motion/Geometry.java','fix/DisplayPermissionPolicy.java','fix/VideoSessionTracker.java')],
        ROOT/'src/com/inkcontrol/features/fix/Hook.java', ROOT/'src/com/inkcontrol/platform/RuntimeStatus.java', ROOT/'src/com/inkcontrol/ui/Guide.java', *sorted((ROOT/'test/runtime').rglob('*.java')),
        *[p for p in sorted((ROOT/'test').rglob('*.java')) if 'runtime' not in p.parts and 'android' not in p.parts])
    for test in ('CoreTest','GeometryTest','com.inkcontrol.features.fix.PolicyTrackerTest','com.inkcontrol.features.fix.HookIntegrationTest','com.inkcontrol.platform.RuntimeStatusTest','GuideTest','TextCatalogTest'): run(java, '-cp', host, test)
    run(java, '-cp', host, 'CatalogExport', schema)
    # 慢的是 Python 的 test_gpu_diagnostics：每例都起 6 个 bash 替身进程并等 12–30s 超时，
    # Windows 上整套近 5 分钟。日常改前端/文案用 --fast 只跑 Java 侧，发版前再跑全量。
    if not args.fast:
        run(sys.executable, '-m', 'unittest', 'discover', '-s', 'test', '-p', 'test_*.py', '-v')
    print(('快速检查' if args.fast else '全部电脑端测试') + '通过：'+str(out/'results.json'))

if __name__ == '__main__': main()
