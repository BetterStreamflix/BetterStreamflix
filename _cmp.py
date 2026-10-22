import re, io
fails = [l.split() for l in open('_fails.txt', encoding='utf-8')]
files = {loc: open(f'_old_{loc}.xml', encoding='utf-8').read() for loc, _ in fails}
out = io.open('_cmp_out.txt', 'w', encoding='utf-8')
for loc, key in fails:
    m = re.search(r'name="%s"[^>]*>(.*?)</string>' % key, files[loc], re.S)
    out.write(f'{loc} {key} | {m.group(1)[:100] if m else "NOT FOUND"}\n')
out.close()
