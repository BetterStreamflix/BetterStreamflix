import re, io
Q = chr(34); AP = chr(39); BS = chr(92)
out = io.open('_scan2.txt','w',encoding='utf-8')
for loc in ['', '-de', '-es', '-fr', '-it', '-pl', '-ar']:
    s = open(f'app/src/main/res/values{loc}/strings.xml', encoding='utf-8').read()
    apos = []
    for m in re.finditer(r'<string\b([^>]*)>(.*?)</string>', s, re.S):
        attrs, body = m.group(1), m.group(2)
        inner = body.strip()
        quoted = inner.startswith(Q) and inner.endswith(Q)
        raw = body.replace(BS+AP, '')
        if not quoted and AP in raw and ('translatable=' + Q + 'false' + Q) not in attrs:
            name = re.search(r'name=' + Q + '([^' + Q + ']+)' + Q, attrs).group(1)
            apos.append(name)
    out.write((loc or '-en') + ': ' + str(len(apos)) + '\n')
    for a in apos: out.write('   ' + a + '\n')
out.close()
