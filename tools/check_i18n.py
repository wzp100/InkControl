"""Validate UTF-8 display translations, placeholders and coverage without compiling locally."""
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
SOURCES = ('ui/MainActivity.java', 'ui/Guide.java', 'ui/Charts.java', 'ui/MotionDemo.java',
           'core/FeatureCatalog.java', 'core/DisplayAlgorithms.java', 'core/FeatureService.java',
           'core/Configuration.java', 'core/Integrity.java', 'core/FeatureDefinition.java')

def main():
    def decode(text): return text.replace('\\n', '\n').replace('\\t', '\t').replace('\\\\', '\\')
    pairs = {}
    for row in (ROOT/'assets/i18n/en.tsv').read_text(encoding='utf-8').splitlines():
        if not row or row.startswith('#'): continue
        key, value = map(decode, row.split('\t'))
        if key in pairs or not key or not value or re.search('[\u3400-\u9fff]', value):
            raise ValueError('Invalid, untranslated or duplicate catalog entry: '+key)
        if re.findall(r'%[-.0-9]*[sdf]', key) != re.findall(r'%[-.0-9]*[sdf]', value):
            raise ValueError('Format placeholders differ: '+key)
        pairs[key] = value
    missing = []
    paths = [ROOT/'src/com/inkcontrol'/p for p in SOURCES]
    paths.extend((ROOT/'src/com/inkcontrol/platform').glob('*.java'))
    for path in paths:
        source = path.read_text(encoding='utf-8')
        # 先把字符字面量抹平（'a'、'\''、'"'）：里面的引号不是字符串边界，
        # 不处理会让后面的字符串匹配从字符字面量处错位，把代码当成未翻译文案。
        source = re.sub(r"'(?:\\.|[^'\\\n])'", "''", source)
        for match in re.finditer(r'"(?:[^"\\]|\\.)*"', source):
            text = json.loads(match.group())
            if re.search('[\u3400-\u9fff]', text) and text not in pairs and text not in ('中 / EN', '语言 / Language'):
                missing.append((path.relative_to(ROOT).as_posix(), text))
    if missing: raise ValueError('Missing translations: '+repr(missing))
    print('UTF-8 bilingual catalog validated:', len(pairs), 'phrases')

if __name__ == '__main__': main()
