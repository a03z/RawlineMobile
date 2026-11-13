"""Every settings screen's pref(key) must name a preference of its own layout: a missing one crashes the screen on open."""
import re
import sys
from pathlib import Path

root = Path(__file__).resolve().parent.parent
src = root / 'app/src/main/java/io/nekohasekai/sagernet'
layouts = root / 'app/src/main/res/xml'

# Registry entries and object constants by name; a file's own constants win over same-named ones elsewhere.
shared = {}
for file in src.rglob('*.kt'):
    for name, value in re.findall(r'\bval\s+([A-Z_][A-Z0-9_]*)\s*=\s*(?:\w+\()?\s*"([^"]+)"', file.read_text(encoding='utf-8')):
        shared.setdefault(name, value)

problems = []
for file in (src / 'ui/settings').glob('*.kt'):
    text = file.read_text(encoding='utf-8')
    local = dict(re.findall(r'const\s+val\s+([A-Z_][A-Z0-9_]*)\s*=\s*"([^"]+)"', text))
    classes = list(re.finditer(r'\n(?:open |abstract )?class\s+(\w+)[^\n{]*?R\.xml\.(\w+)', text))
    for index, match in enumerate(classes):
        name, layout = match.group(1), match.group(2)
        end = classes[index + 1].start() if index + 1 < len(classes) else len(text)
        body = text[match.end():end]
        path = layouts / f'{layout}.xml'
        if not path.exists():
            continue
        keys = set(re.findall(r'app:key="([^"]+)"', path.read_text(encoding='utf-8')))
        for expression in re.findall(r'\bpref<[^>]*>\(([^()]+)\)|\bpref\(([^()]+)\)', body):
            expression = (expression[0] or expression[1]).strip()
            literal = re.fullmatch(r'"([^"]+)"', expression)
            if literal:
                key = literal.group(1)
            else:
                parts = expression.split('.')
                # A plain variable (a helper's own key parameter) cannot be checked here.
                constant = parts[-2] if len(parts) > 1 and parts[-1] == 'key' else parts[-1]
                key = local.get(constant, shared.get(constant)) if constant.isupper() else None
            if key is not None and key not in keys:
                problems.append(f'{name}: {expression} ({key}) is not in {layout}.xml')

print('\n'.join(problems) or 'Every settings key is in its layout')
sys.exit(1 if problems else 0)
