import re
Q = chr(34); AP = chr(39); BS = chr(92)
elem_re = re.compile(r'<(string|item)\b([^>]*)>(.*?)</\1>', re.S)
pos_re = re.compile(r'%[0-9]+\$')
sub_re = re.compile(r'%(?!%|[0-9]+\$)([sdxfgtebhaoc])', re.I)

def escape_apos(body):
    out = []
    for i, c in enumerate(body):
        if c == AP and (i == 0 or body[i-1] != BS):
            out.append(BS)
        out.append(c)
    return ''.join(out)

def fix_body(attrs, body):
    quoted = body.strip().startswith(Q) and body.strip().endswith(Q)
    out = body if quoted else escape_apos(body)
    if 'formatted=' + Q + 'false' + Q not in attrs and not pos_re.search(out):
        subs = sub_re.findall(out)
        if len(subs) >= 2:
            n = [0]
            def repl(m):
                n[0] += 1
                return '%' + str(n[0]) + '$' + m.group(1)
            out = sub_re.sub(repl, out)
    return out

for loc in ['', '-de', '-es', '-fr', '-it', '-pl', '-ar']:
    p = f'app/src/main/res/values{loc}/strings.xml'
    s = open(p, encoding='utf-8').read()
    def repl_elem(m):
        return '<' + m.group(1) + m.group(2) + '>' + fix_body(m.group(2), m.group(3)) + '</' + m.group(1) + '>'
    ns = elem_re.sub(repl_elem, s)
    if ns != s:
        open(p, 'w', encoding='utf-8', newline='').write(ns)
        print('FIXED', p)
    else:
        print('same ', p)
