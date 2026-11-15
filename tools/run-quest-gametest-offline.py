#!/usr/bin/env python3
"""Run the pinned real NeoForge GameTest server from cached libraries in a fresh disposable world. Not a Gradle build."""
from pathlib import Path
import json,os,subprocess,zipfile,time,re,sys
root=Path(__file__).resolve().parents[1];cache=root/'.gradle/local-home/caches';base=cache/'modules-2/files-2.1'
config=json.loads(next((base/'net.neoforged/neoforge/21.1.248').glob('*/*moddev-config.json')).read_text())
def resolve(c):
 g,a,v,*classifier=c.split(':');hits=list((base/g/a/v).glob('*/*.jar'))
 name=a+'-'+v+('-'+classifier[0] if classifier else '')+'.jar'
 return next(p for p in hits if p.name==name)
modules=[resolve(c) for c in config['modules']]
libs={str(resolve(c)) for c in config['libraries']}
manifest=json.loads((cache/'neoformruntime/artifacts/minecraft_1.21.1_version_manifest.json').read_text())
for entry in manifest['libraries']:
 try:libs.add(str(resolve(entry['name'])))
 except (StopIteration,ValueError):pass
api=cache/'neoformruntime/intermediate_results/compiledWithNeoForge_058df8424bd3c42a012c1a689e4a8f56238bc111_output.jar'
resources=cache/'neoformruntime/intermediate_results/stripClient_4e978522ff53800e5a6b00c341a4861aa856f7c9_resourcesOutput.jar'
libs.update([str(api),str(resources)])
output=root/'build/quest-game-test';output.mkdir(parents=True,exist_ok=True)
work=output/('run-'+str(time.time_ns()));work.mkdir()
(output/'last-run.txt').write_text(str(work)+'\n');(work/'tmp').mkdir(exist_ok=True)
# Full diagnostic mod archive carries the metadata and compiled classes from javac.
mods=work/'mods';mods.mkdir(exist_ok=True)
artifact=root/'build/quest-offline/proutlost-0.1.0-CB213-verification-only.jar'
import shutil
shutil.copyfile(artifact,mods/artifact.name)
classpath=work/'legacy-classpath.txt';classpath.write_text('\n'.join(sorted(libs)))
args=['java','-Xmx1G','-Djava.io.tmpdir='+str(work/'tmp')]
if '--campaign' in sys.argv:
 args+=['-Dproutlost.quest.campaignGameTest=true','-Dproutlost.quest.campaignVariant='+os.environ.get('QUEST_TEST_VARIANT','repair_filter_unite')]
for arg in config['runs']['gameTestServer']['jvmArgs']:args.append(arg.replace('{modules}',os.pathsep.join(map(str,modules))))
config['runs']['gameTestServer']['props']['ignoreList'] += ',compiledWithNeoForge_,stripClient_'
for k,v in config['runs']['gameTestServer']['props'].items():args.append('-D'+k+'='+v.replace('{minecraft_classpath_file}',str(classpath)))
args += ['-Dneoforge.enabledGameTestNamespaces='+('cb213campaign' if '--campaign' in sys.argv else 'proutlost,minecraft'),'-cp',os.pathsep.join(sorted(libs)),config['runs']['gameTestServer']['main']]+config['runs']['gameTestServer']['args']
(work/'launch.json').write_text(json.dumps(args,indent=2))
def run_server(command, phase):
 process=subprocess.Popen(command,cwd=work,env=os.environ.copy())
 try:
  process.wait(timeout=60)
 except subprocess.TimeoutExpired:
  # JVM's diagnostic signal avoids the attach API's unavailable local socket.
  import signal
  process.send_signal(signal.SIGQUIT)
  try: process.wait(timeout=120)
  except subprocess.TimeoutExpired:
   process.terminate()
   try: process.wait(timeout=15)
   except subprocess.TimeoutExpired: process.kill();process.wait()
 result=process
 log=work/'logs/latest.log'
 content=log.read_text() if log.exists() else ''
 passed=re.search(r'All \d+ required tests passed',content) is not None
 (work/(phase+'.log')).write_text(content)
 print(phase, 'Minecraft process exit:',result.returncode,'; verified success marker:',passed,'; log:',log,flush=True)
 return result.returncode==0 and passed
if not run_server(args,'initial'): raise SystemExit(1)
if '--restart' in sys.argv:
 restart=args.copy();restart.insert(1,'-Dproutlost.quest.restartVerification=true')
 (work/'restart-launch.json').write_text(json.dumps(restart,indent=2))
 if not run_server(restart,'restart'): raise SystemExit(1)
raise SystemExit(0)
