"""Apply the reference-derived Java layout with clang-format 23.1.1.

Braces, final modifiers and appropriate functional style remain source-review
conventions; this formatter does not attempt semantic rewrites.
"""
import argparse
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[2]
IMPORT = re.compile(r'^import (static )?([\w.*]+);[ \t]*$', re.MULTILINE)


def imports(source):
    matches = list(IMPORT.finditer(source))
    if not matches:
        return source
    span = source[matches[0].start():matches[-1].end()]
    if IMPORT.sub('', span).strip():
        raise ValueError('Import block contains comments or unsupported syntax; review manually')
    groups = [[], [], []]
    for match in matches:
        static, name = match.groups()
        group = 2 if static else 1 if name.startswith(('java.', 'javax.')) else 0
        groups[group].append('import ' + ('static ' if static else '') + name + ';')
    replacement = '\n\n'.join('\n'.join(sorted(group)) for group in groups if group)
    return source[:matches[0].start()] + replacement + source[matches[-1].end():]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    version = subprocess.check_output(['clang-format', '--version'], text=True)
    if 'version 23.1.1' not in version:
        raise SystemExit('Use clang-format 23.1.1 for reproducible Java layout')
    changed = []
    for path in sorted(ROOT.rglob('*.java')):
        if 'target' in path.relative_to(ROOT).parts or '.git' in path.parts:
            continue
        original = path.read_text()
        result = subprocess.check_output(['clang-format', '--style=file', '--assume-filename=' + str(path)],
                                         input=imports(original), text=True)
        result = imports(result)
        if result != original:
            changed.append(str(path.relative_to(ROOT)))
            if not args.check:
                path.write_text(result)
    if changed:
        print(('Needs formatting: ' if args.check else 'Formatted: ') + ', '.join(changed))
    return 1 if args.check and changed else 0


if __name__ == '__main__':
    raise SystemExit(main())
