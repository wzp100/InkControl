"""Static checks for the versioned bilingual Wiki and its agent entry points."""
import json
from pathlib import Path
import re
import subprocess
from urllib.parse import unquote, urlsplit
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def main():
    raw = subprocess.check_output(['git', 'ls-files', '--cached', '--others', '--exclude-standard', '-z'], cwd=ROOT)
    tracked = set(raw.decode('utf-8').strip('\0').split('\0'))
    index = json.loads((ROOT/'docs/wiki/knowledge-index.json').read_text(encoding='utf-8'))
    assert index['target']['name'] == 'Hisense A5 Pro 经典版'
    assert index['target']['screen'] == 'monochrome'
    manifest = ET.parse(ROOT/'AndroidManifest.xml').getroot()
    version = manifest.get('{http://schemas.android.com/apk/res/android}versionName')
    code = manifest.get('{http://schemas.android.com/apk/res/android}versionCode')
    assert index['source_version'] == version
    build = (ROOT/'tools/build.py').read_text(encoding='utf-8')
    assert "VERSION = '"+version+"'" in build and 'versionCode='+code+'\\n' in build
    assert "= '"+version+"' ]" in (ROOT/'backend/lib/common.sh').read_text(encoding='utf-8')
    assert (ROOT/'docs/releases'/(version+'.md')).is_file(), 'Missing release notes'
    expected = set()
    for topic in index['topics']:
        for language in ('zh', 'en'):
            assert topic[language] in tracked, 'Untracked Wiki topic: '+topic[language]
            expected.add(topic[language])
    actual = {name for name in tracked if name.startswith('docs/wiki/') and name.endswith('.md')}
    assert actual == expected, 'Wiki pages missing from the bilingual index'
    for name in index['code_entry_points']:
        assert name in tracked, 'Missing code entry: '+name
    source_ids = {row['id'] for row in index['sources']}
    assert len(source_ids) == len(index['sources'])
    for source in index['sources']:
        if source['availability'] == 'repository':
            assert source['path'] in tracked
        else:
            assert source['availability'] == 'workspace_only'
            assert re.fullmatch(r'[a-f0-9]{64}', source['sha256'])
            assert not Path(source['workspace_path']).is_absolute()
    selected = actual | {'README.md', 'README.en.md', 'AGENTS.md', 'llms.txt',
                         'docs/brand/README.md', 'docs/media/README.md'}
    errors = []
    for name in sorted(selected):
        text = (ROOT/name).read_text(encoding='utf-8')
        if re.search(r'A5\s*Pro\s*CC', text, re.I):
            errors.append(name+': wrong target name')
        # 版本号只在 docs/releases/<版本>.md 与 Release 页维护（2026-11-08 定）：
        # README 不再写当前版本，因此这里只校验产品名，版本一致性由上面
        # knowledge-index / AndroidManifest / build.py / module.prop / release 文件几处互相咬合。
        if name in ('README.md', 'README.en.md') and 'Hisense A5 Pro 经典版' not in text:
            errors.append(name+': target name missing')
        for rid in re.findall(r'\bR\d{2}\b', text):
            if rid not in source_ids: errors.append(name+': unknown source '+rid)
        # Remove fenced/inline code before parsing real Markdown and HTML references.
        visible = re.sub(r'```.*?```', '', text, flags=re.S)
        visible = re.sub(r'`[^`]+`', '', visible)
        links = re.findall(r'\]\(([^)]+)\)', visible)
        links += re.findall(r'(?:href|src)="([^"]+)"', visible)
        for target in links:
            target = target.strip().split(' "', 1)[0].strip('<>')
            url = urlsplit(target)
            if url.scheme or url.netloc or not url.path: continue
            dest = ((ROOT/name).parent/unquote(url.path)).resolve()
            try: relative = dest.relative_to(ROOT).as_posix()
            except ValueError:
                errors.append(name+': link leaves repository: '+target); continue
            if relative not in tracked and not any(p.startswith(relative.rstrip('/')+'/') for p in tracked):
                errors.append(name+': missing repository link '+target)
    for path in (ROOT/'docs/brand').glob('*.svg'):
        svg = ET.parse(path).getroot()
        assert not any(node.tag.endswith('script') for node in svg.iter()), 'Executable SVG'
    ET.parse(ROOT/'res/drawable/ic_launcher.xml')
    if errors: raise ValueError('\n'.join(errors))
    print('Wiki validated:', len(index['topics']), 'bilingual topics;', len(source_ids), 'scoped sources; links, naming, versions and SVG/XML checked')


if __name__ == '__main__': main()
