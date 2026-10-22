import subprocess, sys

files = subprocess.check_output(['git', 'status', '--short'], text=True).splitlines()
files = [l[3:] for l in files if l[3:].endswith('.kt') and not l.startswith(' D')]

bad = []
for f in files:
    src = open(f, encoding='utf-8').read()
    depth = {'{': 0, '(': 0, '[': 0}
    pairs = {'}': '{', ')': '(', ']': '['}
    in_str = None
    in_triple = False
    i = 0
    n = len(src)
    ok = True
    while i < n:
        c = src[i]
        if in_triple:
            if src.startswith('"""', i):
                in_triple = False
                i += 3
                continue
            i += 1
            continue
        if in_str:
            if c == '\\':
                i += 2
                continue
            if c == in_str:
                in_str = None
            i += 1
            continue
        if src.startswith('"""', i):
            in_triple = True
            i += 3
            continue
        if c in '"\'':
            in_str = c
        elif c == '/' and i + 1 < n and src[i + 1] == '/':
            while i < n and src[i] != '\n':
                i += 1
            continue
        elif c == '/' and i + 1 < n and src[i + 1] == '*':
            j = src.find('*/', i + 2)
            i = n if j < 0 else j + 2
            continue
        elif c in '{([':
            depth[c] += 1
        elif c in '})]':
            depth[pairs[c]] -= 1
            if depth[pairs[c]] < 0:
                bad.append((f, f'unbalanced {c} at {i}'))
                ok = False
                break
        i += 1
    if ok and any(v != 0 for v in depth.values()):
        bad.append((f, f'residual depth {depth}'))

for f, msg in bad:
    print('BAD', f, msg)
print('checked', len(files), 'bad', len(bad))
sys.exit(1 if bad else 0)
