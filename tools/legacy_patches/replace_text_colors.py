import re, os, glob

root = r"E:\SDWMP3\app\src\main\java\com\sdw\music\player\ui"

# Only replace in non-theme files — Theme.kt + Color.kt keep original definitions
skip_files = {'Color.kt', 'Theme.kt', 'Type.kt'}

replacements = [
    ('TextPrimary', 'MaterialTheme.colorScheme.onBackground'),
    ('TextSecondary', 'MaterialTheme.colorScheme.onSurfaceVariant'),
    ('TextTertiary', 'MaterialTheme.colorScheme.outlineVariant'),
    # Also swap Gold60 → MaterialTheme.colorScheme.secondaryContainer where used as non-text
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
        orig_line = line
        for old, new in replacements:
            line = re.sub(r'\b' + re.escape(old) + r'\b', new, line)
        if line != orig_line:
            changed = True
        new_lines.append(line)
    if changed:
        with open(fpath, 'w', encoding='utf-8', newline='\n') as fh:
            fh.writelines(new_lines)
        print(f"  {os.path.relpath(fpath, root)}: updated")
print("== Done ==")
