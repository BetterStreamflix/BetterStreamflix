import re, io
out = io.open('_scan_out.txt','w',encoding='utf-8')
for loc in ['','-de','-es','-fr','-it','-pl','-ar']:
    path = f'app/src/main/res/values{loc}/strings.xml'
    s = open(path, encoding='utf-8').read()
    apos = fmt = 0
    for m in re.finditer(r'<string\b([^>]*)>(.*?)</string>', s, re.S):
        attrs, body = m.group(1), m.group(2)
        if 'translatable="false"' in attrs: continue
        inner = body.strip()
        quoted = inner.startswith('"') and inner.endswith('"')
        # raw apostrophe check
        if not quoted:
            stripped = re.sub(r"\'", '', body)
            if "'" in stripped:
                ln = s.count('\n', 0, m.start()) + 1
                out.write(f'{loc or "-en"} APOS {ln} {m.group(0)[:80]}\n'); apos += 1
        # non-positional multi-substitution
        if 'formatted="false"' not in attrs:
            subs = re.findall(r'%(?![0-9]+\$)[sdxfgt]', body)
            if len(subs) >= 2:
                ln = s.count('\n', 0, m.start()) + 1
                name = re.search(r'name="([^"]+)"', attrs)
                out.write(f'{loc or "-en"} FMT {ln} {name.group(1) if name else "?"} | {body[:70]}\n'); fmt += 1
    out.write(f'== {loc or "-en"}: {apos} apostrophe, {fmt} format ==\n')
out.close()
print(open('_scan_out.txt',encoding='utf-8').read()[-600:])
