import re, os, glob

root = r"E:\SDWMP3\app\src\main\java\com\sdw\music\player\ui"

skip_files = {'Color.kt', 'Theme.kt', 'Type.kt'}

replacements = [
    ('DarkBg', 'MaterialTheme.colorScheme.background'),
    ('DarkCard', 'MaterialTheme.colorScheme.surface'),
    ('DarkSurface', 'MaterialTheme.colorScheme.surfaceVariant'),
]

for fpath in glob.glob(root + "/**/*.kt", recursive=True):
    if os.path.basename(fpath) in skip_files:
        continue
    with open(fpath, 'r', encoding='utf-8-sig') as fh:
        lines = fh.readlines()
    changed = False
    new_lines = []
    for line in lines:
        stripped = line.lstrip()
        if stripped.startswith('import ') or stripped.startswith('package '):
            new_lines.append(line)
            continue
        for old, new in replacements:
            # use word-boundary regex but only on non-import lines
            line = re.sub(r'\b' + re.escape(old) + r'\b', new, line)
        if line != new_lines[-1] if new_lines else True:
            changed = True
        new_lines.append(line)
    if changed:
        with open(fpath, 'w', encoding='utf-8', newline='\n') as fh:
            fh.writelines(new_lines)
        print(f"  {os.path.relpath(fpath, root)}: updated")
print("== Done ==")
