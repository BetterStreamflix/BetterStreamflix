import glob, re, subprocess

def match_brace(lines, start_row, start_col):
    # crude: count braces on same+subsequent lines (no string awareness needed for our generated blocks)
    depth = 0
    for r in range(start_row, len(lines)):
        for c in range(len(lines[r])):
            if r == start_row and c < start_col:
                continue
            ch = lines[r][c]
            if ch == '{':
                depth += 1
            elif ch == '}':
                depth -= 1
                if depth == 0:
                    return r, c
    return None, None

changed = subprocess.check_output(['git', 'status', '--short'], text=True).splitlines()
files = [l[3:] for l in changed if l[3:].endswith('.kt')]

total = 0
for f in files:
    lines = open(f, encoding='utf-8').read().splitlines()
    out = []
    i = 0
    n = len(lines)
    made = 0
    while i < n:
        l = lines[i]
        # bare block: line is exactly  ws {   AND next line is deeper indented
        if re.match(r'^\s*\{\s*$', l):
            indent = len(l) - len(l.lstrip())
            er, ec = match_brace(lines, i, indent)
            if er is not None:
                # closing brace must be alone on its line too (or end the line at same indent)
                tail = lines[er][ec:]
                if tail.strip() in ('}', '},'):
                    body = lines[i + 1:er]
                    # dedent body by 4 if all non-empty lines are indented > indent
                    ok = all(not b.strip() or (len(b) - len(b.lstrip())) > indent for b in body)
                    if ok and body:
                        for b in body:
                            if not b.strip():
                                out.append(b)
                            else:
                                out.append(b[4:] if b.startswith(' ' * 4) else b)
                        if tail.strip() == '},':
                            # arg-position? shouldn't happen (checked separately) — keep block
                            out.append(lines[i])
                            out.extend(body)
                            out.append(lines[er])
                        i = er + 1
                        made += 1
                        continue
        out.append(l)
        i += 1
    if made:
        open(f, 'w', encoding='utf-8').write('\n'.join(out) + '\n')
        total += made
        print(f, made)
print('TOTAL', total)
