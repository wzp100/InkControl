"""Check the actual Git candidate file list without printing secret values."""
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
FORBIDDEN = {'out','results','signing','private-inputs','.ci-secrets','__pycache__'}
EXTENSIONS = {'.jks','.keystore','.p12','.pfx','.pem','.key','.apk','.so','.img','.log','.logcat','.hprof'}
# inputs/ 是允许入库的构建输入（原厂与社区派生的 HWC / 常驻二进制，权利归原权利人）：
# 这些文件按名称与大小校验，但不套用「.so/.apk 一律拒绝」的产物规则。
INPUTS = 'inputs'

def main():
    raw = subprocess.check_output(['git','ls-files','--cached','--others','--exclude-standard','-z'],cwd=ROOT)
    paths = sorted(set(raw.decode('utf-8').strip('\0').split('\0')))
    errors = []
    for name in paths:
        if not name: continue
        path = Path(name)
        in_inputs = bool(path.parts) and path.parts[0] == INPUTS
        if not in_inputs and (FORBIDDEN.intersection(path.parts) or path.suffix.lower() in EXTENSIONS
                              or path.name.startswith('.env')):
            errors.append(name+': private input or generated file')
            continue
        full = ROOT/path
        # 已删掉但还没提交的文件（重构中间态）不在工作区里，跳过；提交后自然从清单里消失。
        if not full.exists(): continue
        if full.is_symlink(): errors.append(name+': symbolic link not supported'); continue
        if full.stat().st_size > 10*1024*1024: errors.append(name+': unexpected large file')
        if in_inputs: continue
        if path.suffix.lower() in {'.java','.py','.sh','.md','.json','.xml','.yml','.yaml','.tsv','.txt'}:
            text = full.read_text(encoding='utf-8')
            if re.search(r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,}',text):
                errors.append(name+': possible credential material')
            if re.search(r'[A-Za-z]:[\\/]+Users[\\/]+[0-9A-Za-z_-]+|\b20\d{12}\b',text):
                errors.append(name+': personal path or device identifier')
    if errors: raise ValueError('Repository candidate rejected:\n'+'\n'.join(errors))
    print('Repository candidate checked:',len(paths),'files; no private keys, outputs or device identifiers')

if __name__ == '__main__': main()
