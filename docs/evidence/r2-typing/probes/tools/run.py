import os, sys, subprocess, json, re, shutil, glob
B = ['python3', '/home/urunsiyabend/.hermes/automation/siyollvm/bounded.py', '--']
JAR = os.path.expanduser('~/.cache/siyollvm-oracle/siyoc-02c2262/target/siyo-compiler-0.9.0.jar')
REPO = '/home/urunsiyabend/development/SiyoLLVM-native'
SC = os.path.join(os.environ['TMPDIR'], 'r2run'); os.makedirs(SC, exist_ok=True)
def bounded(cmd, cwd):
    env = dict(os.environ, LC_ALL='C'); env.pop('LANG', None)
    p = subprocess.run(B + cmd, cwd=cwd, env=env, capture_output=True, text=True, errors='replace')
    ev = re.findall(r'BOUNDED evidence=(\S+)', p.stdout + p.stderr)
    rc = None
    try: rc = json.load(open(os.path.join(ev[-1], 'receipt.json')))['returncode']
    except Exception: rc = 'NO-RECEIPT'
    strip = lambda s: '\n'.join(l for l in s.splitlines() if not l.startswith('BOUNDED '))
    return rc, strip(p.stdout), strip(p.stderr)
def first(s): return s.splitlines()[0] if s.strip() else ''
res = {}
for src in sys.argv[2:]:
    src = os.path.abspath(src)
    name = os.path.basename(os.path.dirname(src)) if os.path.basename(src) == 'main.siyo' else os.path.basename(src)[:-5]
    work = os.path.join(SC, name); shutil.rmtree(work, ignore_errors=True)
    if os.path.basename(src) == 'main.siyo': shutil.copytree(os.path.dirname(src), work)
    else: os.makedirs(work); shutil.copy(src, work)
    fn = os.path.basename(src)
    r = bounded(['java', '-jar', JAR, 'run', fn], work)
    e = bounded([REPO + '/bin/siyollvm', 'build', fn, '-o', 'prog'], work)
    n = bounded(['./prog'], work) if e[0] == 0 else None
    res[name] = dict(ref=dict(rc=r[0], out=r[1], err=first(r[2])), emit=dict(rc=e[0], out=e[1], err=first(e[2])),
                     nat=(dict(rc=n[0], out=n[1], err=first(n[2])) if n else None))
    print(name, json.dumps(res[name]), flush=True)
json.dump(res, open(sys.argv[1], 'w'), indent=1)
