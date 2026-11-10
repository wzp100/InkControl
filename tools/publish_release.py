"""Validate one cloud run, then publish an immutable GitHub Release."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
from urllib.error import HTTPError
from urllib.parse import quote
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = 'wzp100/InkControl'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def release_identity(version):
    if not re.fullmatch(r'\d+\.\d+\.\d+(?:-rc[1-9]\d*)?', version):
        raise ValueError('Unsupported release version')
    return 'v' + version, '-rc' in version


def validate_candidate(directory, context, certificate_sha):
    manifest = json.loads((directory/'build-manifest.json').read_text(encoding='utf-8'))
    version = manifest['version']
    release_identity(version)
    expected = {'InkControl-v'+version+'.apk', 'inkcontrol_backend-v'+version+'.zip'}
    if manifest.get('signing') != 'verified' or set(manifest['artifacts']) != expected:
        raise ValueError('Release requires the exact signed APK and backend pair')
    for key in ('GITHUB_REPOSITORY', 'GITHUB_SHA', 'GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT'):
        if not context.get(key) or manifest.get('cloud', {}).get(key) != context[key]:
            raise ValueError('Artifact does not belong to this source/run: '+key)
    if manifest['sources'].get('ci/trusted-certificate.der') != certificate_sha:
        raise ValueError('Untrusted release certificate')
    for name, sha in manifest['artifacts'].items():
        if digest(directory/name) != sha:
            raise ValueError('Artifact digest mismatch: '+name)
    sums = (directory/'SHA256SUMS').read_text(encoding='utf-8')
    wanted_sums = ''.join(sha+'  '+name+'\n' for name, sha in manifest['artifacts'].items())
    if sums != wanted_sums:
        raise ValueError('Build checksums do not match manifest')
    package = json.loads((directory/'package-verification.json').read_text(encoding='utf-8'))
    if not (package.get('version') == version and package.get('signing') == 'verified'
            and package.get('default_off') == 74 and package.get('module_checksums') is True
            and package.get('embedded_zip_identical') is True):
        raise ValueError('Package verification is incomplete')
    if (package.get('apk_sha256') != manifest['artifacts']['InkControl-v'+version+'.apk']
            or package.get('zip_sha256') != manifest['artifacts']['inkcontrol_backend-v'+version+'.zip']):
        raise ValueError('Package verification belongs to other artifacts')
    return manifest


def validate_evidence(inputs, run, attempt):
    ui = json.loads((inputs/('bilingual-ui-'+run+'-'+attempt)/'language-results.json').read_text(encoding='utf-8'))
    pages = {'english-system', 'chinese-explicit', 'english-explicit', 'english-after-restart'}
    checks = ui.get('checks', [])
    if len(checks) != 4 or {row.get('page') for row in checks} != pages or not all(row.get('passed') is True for row in checks):
        raise ValueError('Bilingual UI evidence is incomplete')
    regression = inputs/('regression-'+run+'-'+attempt)
    reports = list(regression.rglob('results.json'))
    if len(reports) != 1:
        raise ValueError('Expected exactly one regression report')
    results = json.loads(reports[0].read_text(encoding='utf-8'))
    required = {'CoreTest', 'GeometryTest', 'GuideTest', 'TextCatalogTest', 'CatalogExport',
                'com.inkcontrol.features.fix.PolicyTrackerTest',
                'com.inkcontrol.features.fix.HookIntegrationTest',
                'com.inkcontrol.platform.RuntimeStatusTest', 'unittest'}
    executed = {word for row in results for word in row.get('command', [])}
    if not results or any(row.get('exit') != 0 for row in results) or not required <= executed:
        raise ValueError('Regression evidence incomplete or failed')
    gpu = json.loads((regression/'native/gpu.txt').read_text(encoding='utf-8').splitlines()[-1])
    policy = (regression/'native/policy.txt').read_text(encoding='utf-8')
    queue = (regression/'native/queue.txt').read_text(encoding='utf-8')
    if gpu != {'scenarios': 59, 'mismatches': 0, 'panel_access': False} or '1785 checks passed' not in policy or '6 scenarios passed' not in queue:
        raise ValueError('Native/GPU evidence incomplete')
    return {'regression_commands': len(results), 'ui_pages': sorted(pages), 'software_gpu': gpu,
            'native_policy_checks': 1785, 'queue_scenarios': 6, 'device_acceptance': 'pending'}


def gh(*args):
    return subprocess.run(['gh', *map(str, args)], capture_output=True, text=True,
                          encoding='utf-8', check=True).stdout


def api_json(method, path, payload=None, asset=None):
    """Use returned release IDs; newly-created drafts need not have a visible tag."""
    host = 'uploads.github.com' if asset is not None else 'api.github.com'
    data = asset.read_bytes() if asset is not None else json.dumps(payload).encode('utf-8')
    headers = {'Authorization': 'Bearer '+os.environ['GH_TOKEN'],
               'Accept': 'application/vnd.github+json', 'X-GitHub-Api-Version': '2022-11-28',
               'User-Agent': 'InkControl-Release',
               'Content-Type': 'application/octet-stream' if asset is not None else 'application/json'}
    request = Request('https://'+host+'/'+path, data=data, headers=headers, method=method)
    try:
        with urlopen(request, timeout=120) as response:
            return json.load(response)
    except HTTPError as error:
        try: message = json.loads(error.read()).get('message', '')
        except (ValueError, UnicodeError): message = ''
        raise RuntimeError('GitHub REST operation failed: '+str(error.code)+' '+message[:300]) from None


def lookup_release(tag):
    p = subprocess.run(['gh', 'api', 'repos/'+REPOSITORY+'/releases/tags/'+tag],
                       capture_output=True, text=True, encoding='utf-8')
    if p.returncode == 0:
        return json.loads(p.stdout)
    if 'HTTP 404' in p.stderr:
        # Draft releases can exist before their tag is created. The tag endpoint
        # returns 404 for that state; the authenticated release list includes drafts.
        pages = json.loads(gh('api', '--paginate', '--slurp',
                              'repos/'+REPOSITORY+'/releases?per_page=100'))
        matches = [row for page in pages for row in page if row['tag_name'] == tag]
        if len(matches) > 1:
            raise ValueError('Multiple releases use the same tag')
        return matches[0] if matches else None
    raise RuntimeError('Release lookup failed; do not create or replace anything')


def verify_remote_assets(release, files, require_all):
    known = {path.name: path for path in files}
    assets = {asset['name']: asset for asset in release['assets']}
    if len(assets) != len(release['assets']) or not set(assets) <= set(known):
        raise ValueError('Unexpected or duplicate existing release assets')
    if require_all and set(assets) != set(known):
        raise ValueError('Published release has incomplete assets; refusing to overwrite')
    for name, asset in assets.items():
        sha = asset.get('digest')
        if sha:
            if sha != 'sha256:'+digest(known[name]):
                raise ValueError('Existing asset differs: '+name)
        else:
            with tempfile.TemporaryDirectory(prefix='inkcontrol-release-check-') as tmp:
                gh('release', 'download', release['tag_name'], '--repo', REPOSITORY,
                   '--pattern', name, '--dir', tmp)
                if digest(Path(tmp)/name) != digest(known[name]):
                    raise ValueError('Existing asset differs: '+name)
    return set(assets)


def publish(tag, prerelease, files, notes, sha):
    if os.environ.get('GITHUB_ACTIONS') != 'true' or os.environ.get('GITHUB_REF') != 'refs/heads/main':
        raise ValueError('Publishing is restricted to main in GitHub Actions')
    repo = json.loads(gh('api', 'repos/'+REPOSITORY))
    if repo.get('full_name') != REPOSITORY:
        raise ValueError('Unexpected release repository')
    release = lookup_release(tag)
    if release and not release['draft']:
        print('Existing published release retained: '+release['html_url'])
        return release['html_url']
    marker = '<!-- InkControl source: '+sha+' -->'
    if release:
        if release.get('target_commitish') != sha or marker not in release.get('body', ''):
            raise ValueError('Existing draft belongs to another commit; increment version')
    else:
        release = api_json('POST', 'repos/'+REPOSITORY+'/releases', {
            'tag_name': tag, 'target_commitish': sha, 'name': '墨控 InkControl '+tag,
            'body': notes.read_text(encoding='utf-8'), 'draft': True, 'prerelease': prerelease})
        if release.get('draft') is not True or release.get('tag_name') != tag:
            raise ValueError('Unexpected create-release response')
    present = verify_remote_assets(release, files, require_all=False)
    for path in files:
        if path.name in present: continue
        asset = api_json('POST', 'repos/'+REPOSITORY+'/releases/'+str(release['id'])+
                         '/assets?name='+quote(path.name, safe=''), asset=path)
        if asset.get('name') != path.name:
            raise ValueError('Unexpected uploaded asset name')
        release['assets'].append(asset)
    verify_remote_assets(release, files, require_all=True)
    published = api_json('PATCH', 'repos/'+REPOSITORY+'/releases/'+str(release['id']),
                         {'draft': False, 'prerelease': prerelease,
                          'make_latest': 'false' if prerelease else 'true'})
    if published.get('draft') is not False or published.get('tag_name') != tag:
        raise ValueError('Release publication was not confirmed')
    verify_remote_assets(published, files, require_all=True)
    return published['html_url']


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--input', required=True, type=Path)
    p.add_argument('--publish', action='store_true')
    args = p.parse_args()
    context = {key: os.environ.get(key, '') for key in ('GITHUB_REPOSITORY', 'GITHUB_SHA', 'GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT')}
    if context['GITHUB_REPOSITORY'] != REPOSITORY:
        raise ValueError('Unexpected repository')
    run, attempt = context['GITHUB_RUN_ID'], context['GITHUB_RUN_ATTEMPT']
    if not run.isdigit() or not attempt.isdigit() or not re.fullmatch(r'[a-f0-9]{40}', context['GITHUB_SHA']):
        raise ValueError('Invalid run identity')
    candidate = args.input/('InkControl-'+run+'-'+attempt+'-signed')
    manifest = validate_candidate(candidate, context, digest(ROOT/'ci/trusted-certificate.der'))
    evidence = validate_evidence(args.input, run, attempt)
    version = manifest['version']
    tag, prerelease = release_identity(version)
    # Published versions are immutable; documentation-only pushes can keep the version.
    if args.publish:
        if os.environ.get('GITHUB_ACTIONS') != 'true' or os.environ.get('GITHUB_REF') != 'refs/heads/main':
            raise ValueError('Publishing requires Actions/main')
        repo = json.loads(gh('api', 'repos/'+REPOSITORY))
        if repo.get('full_name') != REPOSITORY:
            raise ValueError('Unexpected release repository')
        existing = lookup_release(tag)
        if existing and not existing['draft']:
            print('Existing published release retained: '+existing['html_url'])
            return
    output = ROOT/'out'/('release-'+run+'-'+attempt)
    output.mkdir(parents=True, exist_ok=False)
    for name in (*manifest['artifacts'], 'build-manifest.json', 'package-verification.json'):
        shutil.copy2(candidate/name, output/name)
    notes = output/'RELEASE_NOTES.md'
    text = (ROOT/'docs/releases'/(version+'.md')).read_text(encoding='utf-8')
    text += '\n\nSource / 来源: `'+context['GITHUB_SHA']+'`\n\n'
    text += '[Cloud evidence / 云端证据](https://github.com/'+REPOSITORY+'/actions/runs/'+run+')\n\n'
    text += '<!-- InkControl source: '+context['GITHUB_SHA']+' -->\n'
    notes.write_text(text, encoding='utf-8', newline='\n')
    report = dict(evidence, version=version, signing='verified', cloud=context,
                  certificate_sha256=digest(ROOT/'ci/trusted-certificate.der'),
                  artifacts=manifest['artifacts'], prerelease=prerelease)
    (output/'release-verification.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    files = sorted(output.iterdir())
    sums = output/'SHA256SUMS'
    sums.write_text(''.join(digest(path)+'  '+path.name+'\n' for path in files), encoding='utf-8', newline='\n')
    files.append(sums)
    print(json.dumps({'tag': tag, 'prerelease': prerelease, 'assets': [path.name for path in files]}, ensure_ascii=False))
    if args.publish:
        url = publish(tag, prerelease, files, notes, context['GITHUB_SHA'])
        with Path(os.environ['GITHUB_STEP_SUMMARY']).open('a', encoding='utf-8') as f:
            f.write('\n[Release / 发布下载]('+url+')\n')
        print(url)


if __name__ == '__main__':
    main()
