import json, re, sys
d = json.load(open(sys.argv[1]))
def ref(r):
    if r is None: return 'n/a'
    if r['rc'] == 0: return 'runs: `' + r['out'].replace('\n', ' / ')[:40] + '`'
    e = r['err']
    m = re.match(r'java.lang.ClassCastException: class (\S+) cannot be cast to class (\S+)', e)
    if m: return f'CCE {m.group(1).split(".")[-1]}→{m.group(2).split(".")[-1]}'
    if 'VerifyError' in e: return 'VerifyError'
    if e.startswith('java.lang.NullPointerException'):
        return 'NPE `' + e.split(': ', 1)[1].replace('java.lang.', '').replace('java.util.', '') + '`'
    if '.siyo(' in e: return 'bind error `' + e.split(': ', 1)[1] + '`'
    return '`' + e.replace('codeanalysis.', '').replace('java.lang.', '') + '`'
def llvm(v):
    e = v['emit']
    if e['rc'] != 0:
        return 'rejects `' + re.sub(r'^.*?\(\d+, \d+\): (\[llvm\] )?', '', e['err']).replace(' is not supported by SiyoLLVM', '').replace(' are not supported by SiyoLLVM', '') + '`'
    n = v['nat']; r = v['ref']
    if n['rc'] == r['rc'] and n['out'] == r['out'] and n['err'] == r['err']: return 'accepts, matches'
    return 'accepts, differs (`' + n['err'][:60] + '`)'
groups = {}
for k, v in d.items():
    base, _, var = k.rpartition('__')
    if not base: base, var = k, 'ok'
    groups.setdefault(base, {})[var] = v
print('| probe | reference: correct | wrong type | null | SiyoLLVM today (correct / wrong / null) |')
print('|---|---|---|---|---|')
for base in sorted(groups):
    g = groups[base]
    cells = [ref(g[x]['ref']) if x in g else '—' for x in ('ok', 'wrong', 'null')]
    ll = [llvm(g[x]) for x in ('ok', 'wrong', 'null') if x in g]
    ll = ll[0] if len(set(ll)) == 1 else ' / '.join(ll)
    print(f'| `{base}` | ' + ' | '.join(cells) + f' | {ll} |')
