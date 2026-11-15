#!/usr/bin/env python3
"""J04-only runner: no Gradle, downloads, world, or common G0 campaign."""
import hashlib, json, pathlib, shutil, subprocess, time
root = pathlib.Path.cwd()
report = root / 'reports/J04'
report.mkdir(parents=True, exist_ok=True)
jar = pathlib.Path('/var/lib/codexbridge/worktrees/CB-202/build/director/API_1.0.1_CONTRACTS_c94f65e6e3618a96498588e9995818673955169e1ac914e74be57e5133e12b5b.jar')
assert hashlib.sha256(jar.read_bytes()).hexdigest() == 'a670ff5ea985882a6c90f4fc30aca5d898e7af82661e154df1255e21047f2259'
classes = root / 'build/J04/classes'
classes.mkdir(parents=True, exist_ok=True)
prefix = ['nice', '-n', '19', 'ionice', '-c', '3', 'taskset', '-c', '10,11']
sources = sorted(root.glob('src/director/java/fr/proutlost/director/persistence/*.java')) + sorted(root.glob('src/directorTest/java/fr/proutlost/director/persistence/*.java'))
commands = [prefix + ['javac', '-J-Xmx256m', '-J-XX:ActiveProcessorCount=1', '--release', '21', '-cp', str(jar), '-d', str(classes)] + [str(p) for p in sources], prefix + ['java', '-Xms32m', '-Xmx256m', '-XX:ActiveProcessorCount=1', '-cp', str(classes)+':'+str(jar), 'fr.proutlost.director.persistence.StoreTests', str(root / ('build/J04/run-'+str(time.time_ns())))]]
results=[]
for index, command in enumerate(commands):
    mem=int(next(x.split()[1] for x in pathlib.Path('/proc/meminfo').read_text().splitlines() if x.startswith('MemAvailable:')))*1024
    psi=float(next(x for x in pathlib.Path('/proc/pressure/io').read_text().splitlines() if x.startswith('full ')).split()[1].split('=')[1])
    free=shutil.disk_usage(root).free
    assert mem >= 3*1024**3 and free >= 8*1024**3 and psi < 15, (mem,free,psi)
    log=report / ('compile.log' if index==0 else 'tests.log')
    with log.open('w') as out:
        result=subprocess.run(command, stdout=out, stderr=subprocess.STDOUT, timeout=180)
    evidence={'command':command,'exit_code':result.returncode,'log':str(log.relative_to(root)),'sha256':hashlib.sha256(log.read_bytes()).hexdigest(),'resources':{'available_bytes':mem,'disk_free_bytes':free,'io_full_avg10':psi}}
    results.append(evidence)
    (report/'TEST_RESULTS.json').write_text(json.dumps({'commands':results},indent=2)+'\n')
    print(log.read_text(),end='')
    if result.returncode: raise SystemExit(result.returncode)
