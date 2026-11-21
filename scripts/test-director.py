#!/usr/bin/env python3
"""G0 isolated Java 21 qualification; no server, Gradle, cache writes or world access."""
import argparse, hashlib, json, os, pathlib, subprocess, sys, zipfile, shutil
p=argparse.ArgumentParser();p.add_argument('--spec',default='build/director-input/spec-1.0.0');p.add_argument('--platform',action='store_true');args=p.parse_args()
root=pathlib.Path(__file__).resolve().parents[1];os.chdir(root)
out=root/'build/director';out.mkdir(parents=True,exist_ok=True)
executions=[]
def run(name,cmd):
 result=subprocess.run(['nice','-n','19','ionice','-c','3',*cmd],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
 (out/(name+'.log')).write_text(result.stdout);executions.append({'name':name,'command':['nice','-n','19','ionice','-c','3',*cmd],'exit_code':result.returncode,'log_sha256':hashlib.sha256(result.stdout.encode()).hexdigest()});print(name+': '+('PASS' if result.returncode==0 else 'FAIL'));print(result.stdout,end='')
 if result.returncode:sys.exit(result.returncode)
version=subprocess.run(['javac','-version'],capture_output=True,text=True);assert version.stdout.startswith('javac 21.'),version
def pure(path):
 relative=str(path).split('/fr/proutlost/director/')[-1]
 return not relative.startswith(('adapter/','client/','admin/client/')) and not (relative.startswith('bootstrap/') and ('net.minecraft' in path.read_text() or 'net.neoforged' in path.read_text()))
production=sorted(root.glob('src/director/java/**/*.java'))
tests=sorted(root.glob('src/directorTest/java/**/*.java'))
sources=[p for p in production+tests if pure(p)]
classes=out/'classes'
if classes.exists():shutil.rmtree(classes)
classes.mkdir()
(out/'sources.txt').write_text('\n'.join(map(str,sources)))
run('compile',['javac','-J-Xmx1g','--release','21','-Xlint:all','-Werror','-d',str(classes),'@'+str(out/'sources.txt')])
run('contracts',['java','-Xmx512m','-ea','-cp',str(classes),'fr.proutlost.director.testkit.ContractTests',str(pathlib.Path(args.spec).resolve())])
# Actual packaging guard: only production class families, never testkit classes.
jarstage=out/'jar-input'
if jarstage.exists():shutil.rmtree(jarstage)
jarstage.mkdir()
for source in production:
 if not pure(source):continue
 rel=source.relative_to(root/'src/director/java').with_suffix('')
 for compiled_class in (classes/rel.parent).glob(rel.name+'*.class'):
  if compiled_class.stem!=rel.name and not compiled_class.stem.startswith(rel.name+'$'):continue
  dest=jarstage/compiled_class.relative_to(classes);dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(compiled_class,dest)
run('packaging',['jar','--create','--file',str(out/'director-contracts.jar'),'-C',str(jarstage),'.'])
with zipfile.ZipFile(out/'director-contracts.jar') as jar:
 assert not any('/testkit/' in name or '/worldprep/' in name for name in jar.namelist())
 assert 'fr/proutlost/director/bootstrap/DirectorBootstrap.class' in jar.namelist()
run('bootstrap' ,['java','-Xmx128m','-cp',str(classes),'fr.proutlost.director.bootstrap.DirectorBootstrap'])
if args.platform:
 cache=pathlib.Path('/var/lib/codexbridge/.gradle/caches')
 compiled=cache/'neoformruntime/intermediate_results/compiledWithNeoForge_058df8424bd3c42a012c1a689e4a8f56238bc111_output.jar'
 assert compiled.is_file(),'missing pinned compiled Minecraft/NeoForge artifact'
 with zipfile.ZipFile(compiled) as archive:
  manifest=archive.read('META-INF/MANIFEST.MF').decode()
  assert 'Implementation-Version: 21.1.248' in manifest and 'Specification-Version: 1.21.1' in manifest,'compiled platform version mismatch'
  assert 'version="21.1.248"' in archive.read('META-INF/neoforge.mods.toml').decode(),'NeoForge mod version mismatch'
 jars=sorted((cache/'modules-2/files-2.1').rglob('*.jar'));jars=[j for j in jars if not any(x in j.name for x in ['-sources','-javadoc','-userdev'])]
 assert any('/net.neoforged/neoforge/21.1.248/' in str(j) for j in jars),'pinned NeoForge absent'
 cp=os.pathsep.join(map(str,[classes,compiled,*jars]));probes=sorted(root.glob('src/directorPlatform/java/**/*.java'))
 assert probes,'missing pinned API probe'
 probes += [p for p in production+tests if not pure(p)]
 run('platform-compile',['javac','-J-Xmx1g','--release','21','-proc:none','-cp',cp,'-d',str(out/'platform-classes'),*map(str,probes)])
 (out/'platform-dependencies.json').write_text(json.dumps([{'path':str(j),'sha256':hashlib.sha256(j.read_bytes()).hexdigest()} for j in [compiled,*jars]],indent=2)+'\n')
(out/'executions.json').write_text(json.dumps({'checks':executions,'compiled_source_sha256':{str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sources+(probes if args.platform else [])}},indent=2)+'\n')
print('Native/client/GameTests: NOT_RUN')
