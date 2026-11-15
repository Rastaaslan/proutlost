#!/usr/bin/env python3
"""J01 local validation only. Never invokes Gradle or starts Minecraft."""
import hashlib,json,pathlib,shutil,subprocess,sys,time
ROOT=pathlib.Path(__file__).resolve().parents[7]
# Resolve from the worktree invocation to avoid assumptions about package depth.
ROOT=pathlib.Path.cwd()
OUT=ROOT/'reports/J01'; BUILD=ROOT/'build/J01'; OUT.mkdir(parents=True,exist_ok=True); BUILD.mkdir(parents=True,exist_ok=True)
JAR='/var/lib/codexbridge/worktrees/CB-202/build/director/API_1.0.1_CONTRACTS_c94f65e6e3618a96498588e9995818673955169e1ac914e74be57e5133e12b5b.jar'
assert hashlib.sha256(pathlib.Path(JAR).read_bytes()).hexdigest()=='a670ff5ea985882a6c90f4fc30aca5d898e7af82661e154df1255e21047f2259'
PREFIX=['nice','-n','19','ionice','-c','3','taskset','-c','10,11']
executions=[]
def run(name,args):
    mem={l.split(':')[0]:int(l.split()[1]) for l in pathlib.Path('/proc/meminfo').read_text().splitlines()}
    io=pathlib.Path('/proc/pressure/io').read_text(); full=float(next(l for l in io.splitlines() if l.startswith('full ')).split()[1].split('=')[1]); free=shutil.disk_usage(ROOT).free
    assert mem['MemAvailable']*1024>=3*1024**3 and free>=8*1024**3 and full<15,'resource gate unavailable'
    command=PREFIX+args
    with (OUT/(name+'.log')).open('wb') as log: result=subprocess.run(command,cwd=ROOT,stdout=log,stderr=subprocess.STDOUT)
    executions.append(dict(name=name,command=command,exit_code=result.returncode,status='PASS' if result.returncode==0 else 'FAIL',log=str((OUT/(name+'.log')).relative_to(ROOT)),sha256=hashlib.sha256((OUT/(name+'.log')).read_bytes()).hexdigest(),resource_preflight=dict(available_ram_bytes=mem['MemAvailable']*1024,free_disk_bytes=free,io_full_avg10=full)))
    (OUT/'executions-iteration6.json').write_text(json.dumps(executions,indent=2)+'\n')
    print(name,result.returncode,flush=True)
    if result.returncode: print((OUT/(name+'.log')).read_text());sys.exit(result.returncode)
sources=sorted((ROOT/'src/director/java/fr/proutlost/director/memory').glob('*.java'))+sorted((ROOT/'src/directorTest/java/fr/proutlost/director/memory').glob('*.java'))+[ROOT/'src/director/java/fr/proutlost/director/adapter/neoforge/server/observation/ObservationBuffer.java',ROOT/'src/director/java/fr/proutlost/director/adapter/neoforge/server/observation/ProximityGroups.java',ROOT/'src/director/java/fr/proutlost/director/adapter/neoforge/server/observation/CampSampler.java',ROOT/'src/director/java/fr/proutlost/director/adapter/neoforge/server/observation/ObservationContinuity.java',ROOT/'src/director/java/fr/proutlost/director/adapter/neoforge/server/observation/ObservationPipeline.java',ROOT/'src/director/java/fr/proutlost/director/adapter/neoforge/server/observation/ObservationBudget.java',ROOT/'src/director/java/fr/proutlost/director/adapter/neoforge/server/observation/ObservationCadence.java']
(BUILD/'sources-iteration6.txt').write_text('\n'.join(str(p.relative_to(ROOT)) for p in sources)+'\n')
run('pure-compile-iteration6',['javac','-J-Xmx256m','--release','21','-cp',JAR,'-d','build/J01/classes','@build/J01/sources-iteration6.txt'])
for main in ['MemoryDomainTest','MemoryIntegrationTest']:
    if (ROOT/('src/directorTest/java/fr/proutlost/director/memory/'+main+'.java')).exists():
        run(main+'-iteration6',['java','-Xmx256m','-cp','build/J01/classes:'+JAR,'fr.proutlost.director.memory.'+main,'/var/lib/codexbridge/worktrees/CB-202/build/director-input/spec-1.0.0/DEFAULT_FOR_TESTING.json'])
if '--platform' in sys.argv:
    dependencies=json.loads(pathlib.Path('/var/lib/codexbridge/worktrees/CB-202/build/director/platform-dependencies.json').read_text())
    # Read-only pinned caches, all evidence-identified dependencies, no main.output.
    for d in dependencies:
        assert hashlib.sha256(pathlib.Path(d['path']).read_bytes()).hexdigest()==d['sha256'],d['path']
    cp=JAR+':build/J01/classes:'+':'.join(d['path'] for d in dependencies)
    run('platform-compile-iteration6',['javac','-J-Xmx384m','--release','21','-cp',cp,'-d','build/J01/platform-classes','src/director/java/fr/proutlost/director/adapter/neoforge/server/observation/NeoForgeObservation.java','src/directorTest/java/fr/proutlost/director/adapter/neoforge/server/observation/NativeObservationChecks.java'])
