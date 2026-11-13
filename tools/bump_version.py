"""Set the project version everywhere it is written, in one step.

    python tools/bump_version.py 0.3.0-rc3          # versionCode = current + 1
    python tools/bump_version.py 0.3.0 --code 31    # explicit versionCode

Updates AndroidManifest.xml, tools/build.py, backend/lib/common.sh, the knowledge index and the
"current candidate" line of both build-and-release Wiki pages, then creates a bilingual release-notes
skeleton (with the mandatory risk notice copied from the previous notes) if none exists.
Run `python tools/check_docs.py` afterwards; it verifies that every place agrees.
"""
import argparse
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def replace(path, pattern, replacement):
    """Replace exactly one match so a silent partial update cannot happen."""
    file = ROOT/path
    text = file.read_text(encoding='utf-8')
    updated, count = re.subn(pattern, replacement, text)
    if count != 1:
        raise SystemExit('%s: expected one match for %r, found %d' % (path, pattern, count))
    file.write_bytes(updated.encode('utf-8'))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('version', help='X.Y.Z or X.Y.Z-rcN')
    parser.add_argument('--code', type=int, help='versionCode; defaults to the current one plus 1')
    args = parser.parse_args()
    if not re.fullmatch(r'\d+\.\d+\.\d+(?:-rc[1-9]\d*)?', args.version):
        raise SystemExit('Version must match X.Y.Z or X.Y.Z-rcN (tools/publish_release.py rejects anything else)')
    manifest = (ROOT/'AndroidManifest.xml').read_text(encoding='utf-8')
    old = re.search(r'android:versionName="([^"]+)"', manifest).group(1)
    old_code = int(re.search(r'android:versionCode="(\d+)"', manifest).group(1))
    code = args.code if args.code is not None else old_code + 1
    if code <= old_code and args.version != old:
        raise SystemExit('versionCode must increase (current %d)' % old_code)
    version = args.version
    replace('AndroidManifest.xml', r'android:versionCode="\d+" android:versionName="[^"]+"',
            'android:versionCode="%d" android:versionName="%s"' % (code, version))
    replace('tools/build.py', r"VERSION = '[^']+'", "VERSION = '%s'" % version)
    replace('tools/build.py', r'versionCode=\d+\\n', 'versionCode=%d\\\\n' % code)
    replace('backend/lib/common.sh', r"= '[^']+' \] && \[ ! -f \"\$IC_STATE/error\" \]",
            "= '%s' ] && [ ! -f \"$IC_STATE/error\" ]" % version)
    replace('docs/wiki/knowledge-index.json', r'"source_version": "[^"]+"', '"source_version": "%s"' % version)
    replace('docs/wiki/build-and-release.md', r'当前候选 `[^`]+`', '当前候选 `%s`' % version)
    replace('docs/wiki/build-and-release.en.md', r'Current candidate: `[^`]+`', 'Current candidate: `%s`' % version)

    notes = ROOT/'docs/releases'/(version+'.md')
    if not notes.exists():
        previous = (ROOT/'docs/releases'/(old+'.md')).read_text(encoding='utf-8')
        zh_risk = previous[previous.index('## 风险提示与免责声明'):previous.index('\n---\n')].rstrip()
        en_risk = previous[previous.index('## Risks and disclaimer'):].rstrip()
        kind_zh = '候选预发布' if '-rc' in version else '正式版本'
        kind_en = 'Candidate prerelease' if '-rc' in version else 'Stable release'
        notes.write_bytes(('''# 墨控 InkControl %(v)s

面向 **Hisense A5 Pro 经典版黑白屏**的%(kz)s（versionCode %(c)d）。**全部增强默认关闭**。安装前请先阅读下方风险提示。

## 更新内容

- TODO

## 使用注意

- APK 与后端模块必须同版本、同批安装，升级后完整重启一次。
- 仅在 `HLTE203T`（Android 10 / arm64）上验证；其他机型与固件不保证可用或可恢复。

%(zr)s

---

%(ke)s (versionCode %(c)d) for the **Hisense A5 Pro 经典版 (Classic Edition), monochrome**. **All enhancements default off.** Read the risk notice below before installing.

## Changes

- TODO

## Notes

- Install the APK and backend module from the same version together and restart fully after upgrading.
- Verified only on `HLTE203T` (Android 10 / arm64); other devices and firmware are not guaranteed to work or be recoverable.

%(er)s
''' % {'v': version, 'c': code, 'kz': kind_zh, 'ke': kind_en, 'zr': zh_risk, 'er': en_risk}).encode('utf-8'))
        print('Created', notes.relative_to(ROOT).as_posix(), '- replace the TODO lines before committing')
    print('Version %s (versionCode %d) -> %s (versionCode %d)' % (old, old_code, version, code))
    print('Next: python tools/check_docs.py')


if __name__ == '__main__':
    main()
