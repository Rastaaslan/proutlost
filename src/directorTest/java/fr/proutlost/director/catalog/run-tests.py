#!/usr/bin/env python3
"""J02 only: qualified immutable API 1.0.1 JAR, one bounded javac/java process at a time, no Gradle."""
import hashlib
import json
import pathlib
import shutil
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[7]
JAR = pathlib.Path('/var/lib/codexbridge/worktrees/CB-202/build/director/API_1.0.1_CONTRACTS_c94f65e6e3618a96498588e9995818673955169e1ac914e74be57e5133e12b5b.jar')
EXPECTED = 'a670ff5ea985882a6c90f4fc30aca5d898e7af82661e154df1255e21047f2259'
OUT = ROOT / 'build/J02/classes'
REPORT = ROOT / 'reports/J02'
OUT.mkdir(parents=True, exist_ok=True)
REPORT.mkdir(parents=True, exist_ok=True)
assert hashlib.sha256(JAR.read_bytes()).hexdigest() == EXPECTED

def preflight():
    mem = {line.split(':')[0]: int(line.split()[1]) * 1024 for line in pathlib.Path('/proc/meminfo').read_text().splitlines()}
    io = pathlib.Path('/proc/pressure/io').read_text().splitlines()[1]
    available, free, psi = mem['MemAvailable'], shutil.disk_usage(ROOT).free, float(io.split('avg10=')[1].split()[0])
    assert available >= 3 * 1024**3 and free >= 8 * 1024**3 and psi < 15, (available, free, psi)
    return {'available_memory_bytes': available, 'disk_free_bytes': free, 'io_full_avg10': psi}

prefix = ['nice', '-n', '19', 'ionice', '-c', '3', 'taskset', '-c', '10,11']
sources = sorted(str(p.relative_to(ROOT)) for folder in ['src/director/java/fr/proutlost/director/catalog', 'src/directorTest/java/fr/proutlost/director/catalog'] for p in (ROOT / folder).glob('*.java'))
commands = [prefix + ['javac', '-J-Xmx384m', '-J-XX:ActiveProcessorCount=1', '--release', '21', '-Xlint:all', '-Werror', '-cp', str(JAR), '-d', str(OUT), *sources],
            prefix + ['java', '-Xmx256m', '-XX:ActiveProcessorCount=1', '-ea', '-cp', f'{OUT}:{JAR}:{ROOT / "src/director/resources"}', 'fr.proutlost.director.catalog.CatalogTests']]
for main in ['RuntimeCandidateTests', 'ScoringMetadataTests']:
    commands.append(prefix + ['java', '-Xmx256m', '-XX:ActiveProcessorCount=1', '-ea', '-cp', f'{OUT}:{JAR}:{ROOT / "src/director/resources"}', 'fr.proutlost.director.catalog.' + main])
results = []
for name, command in zip(['compile', 'catalog-tests', 'runtime-candidate-tests', 'scoring-metadata-tests'], commands):
    resources = preflight()
    run = subprocess.run(command, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    log = REPORT / (name + '.log')
    log.write_text(run.stdout)
    results.append({'name': name, 'command': command, 'preflight': resources, 'exit_code': run.returncode,
                    'result': 'PASS' if run.returncode == 0 else 'FAIL', 'log': str(log.relative_to(ROOT)),
                    'log_sha256': hashlib.sha256(log.read_bytes()).hexdigest()})
    print(name, run.returncode, run.stdout, flush=True)
    if run.returncode:
        break
(REPORT / 'TEST_RESULTS.json').write_text(json.dumps({'contracts_jar_sha256': EXPECTED, 'executions': results,
    'source_sha256': {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in [*(ROOT / 'src/director/java/fr/proutlost/director/catalog').glob('*.java'), *(ROOT / 'src/directorTest/java/fr/proutlost/director/catalog').glob('*.java'), *(ROOT / 'src/director/resources/schemas').glob('*.json'), *(ROOT / 'src/director/resources/data/proutlost_director/director').rglob('*.json'), *(ROOT / 'src/directorTest/resources/catalog').rglob('*.json')]},
    'not_run': ['Gradle', 'NeoForge dedicated server', 'GameTests', 'native clients', 'real physical capability qualification', 'G1 common architecture campaign', 'J03 scoring metadata evaluation']}, indent=2) + '\n')
sys.exit(results[-1]['exit_code'])
