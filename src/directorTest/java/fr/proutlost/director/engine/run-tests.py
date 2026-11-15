#!/usr/bin/env python3
"""Reproducible isolated J03 validation; never invokes Gradle or a platform runtime."""
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys

root = Path(__file__).resolve().parents[7]
report = root / 'reports/J03'
build = root / 'build/J03'
report.mkdir(parents=True, exist_ok=True)
build.mkdir(parents=True, exist_ok=True)
jar = Path('/var/lib/codexbridge/worktrees/CB-202/build/director/API_1.0.1_CONTRACTS_c94f65e6e3618a96498588e9995818673955169e1ac914e74be57e5133e12b5b.jar')
profile = Path('/var/lib/codexbridge/worktrees/CB-202/build/director-input/spec-1.0.0/DEFAULT_FOR_TESTING.json')
assert hashlib.sha256(jar.read_bytes()).hexdigest() == 'a670ff5ea985882a6c90f4fc30aca5d898e7af82661e154df1255e21047f2259'
assert hashlib.sha256(profile.read_bytes()).hexdigest() == 'a0ba94dfec141788b6f3768a585fec5208d9270e10fc0202139b4dae228cd81d'
manifest = json.loads((root / 'docs/worlddirector-v1/api-1.0.1/API_MANIFEST_1.0.1.json').read_text())
for item in manifest['files']:
    data = (root / item['path']).read_bytes()
    assert len(data) == item['bytes'] and hashlib.sha256(data).hexdigest() == item['sha256'], item['path']

sources = sorted((root / 'src/director/java/fr/proutlost/director/engine').glob('*.java'))
tests = sorted(Path(__file__).parent.glob('*.java'))
classes = build / 'classes'
classes.mkdir(exist_ok=True)
limits = ['nice', '-n', '19', 'ionice', '-c', '3', 'taskset', '-c', '10,11']
commands = [
    limits + ['javac', '-J-Xmx256m', '-J-XX:ActiveProcessorCount=1', '-J-XX:+UseSerialGC', '--release', '21', '-Xlint:all', '-Werror', '-cp', str(jar), '-d', str(classes)] + [str(p) for p in sources + tests],
    limits + ['java', '-Xmx256m', '-XX:ActiveProcessorCount=1', '-XX:+UseSerialGC', '-cp', str(classes) + ':' + str(jar), 'fr.proutlost.director.engine.EngineAlgorithmsTest', str(profile)]
]
evidence = {'api_manifest_files_verified': len(manifest['files']), 'executions': [], 'sources': {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources + tests}}
for label, command in zip(['compile', 'tests'], commands):
    memory = int(re.search(r'MemAvailable:\s+(\d+)', Path('/proc/meminfo').read_text())[1]) * 1024
    free = shutil.disk_usage(root).free
    psi = float(re.search(r'full avg10=(\S+)', Path('/proc/pressure/io').read_text())[1])
    assert memory >= 3 * 1024**3 and free >= 8 * 1024**3 and psi < 15, (memory, free, psi)
    result = subprocess.run(command, cwd=root, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    log = report / (label + '.log')
    log.write_text(result.stdout)
    evidence['executions'].append({'label': label, 'command': command, 'exit_code': result.returncode, 'status': 'PASS' if result.returncode == 0 else 'FAIL', 'resources': {'mem_available_bytes': memory, 'disk_free_bytes': free, 'io_full_avg10': psi}, 'log': str(log.relative_to(root)), 'log_sha256': hashlib.sha256(log.read_bytes()).hexdigest()})
    (report / 'TEST_RESULTS.json').write_text(json.dumps(evidence, indent=2) + '\n')
    print(label, 'exit', result.returncode)
    print(result.stdout)
    if result.returncode:
        sys.exit(result.returncode)
