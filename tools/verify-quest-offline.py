#!/usr/bin/env python3
"""Fallback verification with real cached pinned APIs; does NOT replace a Gradle build or Minecraft test."""
import os, pathlib, subprocess, sys, shutil, zipfile, hashlib
root = pathlib.Path(__file__).resolve().parents[1]
os.chdir(root)
cache = pathlib.Path(os.environ.get('QUEST_GRADLE_CACHE', root / '.gradle/local-home/caches'))
output = root / 'build/quest-offline'
output.mkdir(parents=True, exist_ok=True)
api = cache / 'neoformruntime/intermediate_results/compiledWithNeoForge_058df8424bd3c42a012c1a689e4a8f56238bc111_output.jar'
# This hash comes from the existing NeoForm cache manifest for NeoForge 21.1.248.
manifest = api.with_name(api.name.replace('_output.jar', '.txt'))
if not api.exists() or '21.1.248' not in manifest.read_text():
    sys.exit('Pinned NeoForge 21.1.248 compiled API cache missing; run Gradle in an authorized environment.')
jars = sorted((cache / 'modules-2/files-2.1').rglob('*.jar'))
cp = os.pathsep.join(map(str, [api] + jars))
main = output / 'main'; tests = output / 'test'
for directory in [main, tests]:
    if directory.exists(): shutil.rmtree(directory)
    directory.mkdir()
def run(args):
    print('RUN', args[0], args[1] if args[0]=='javac' else args[-1], flush=True)
    result = subprocess.run(args)
    if result.returncode: sys.exit(result.returncode)
sources = sorted((root / 'src/main/java').rglob('*.java'))
args = output / 'main.args'
args.write_text('\n'.join(['--release', '21', '-encoding', 'UTF-8', '-proc:none', '-classpath', cp, '-d', str(main)] + list(map(str, sources))))
run(['javac', '@' + str(args)])
classpath = os.pathsep.join([str(main), str(root/'src/main/resources'), cp])
sources = sorted((root / 'src/test/java').rglob('*.java'))
launcher = output / 'RunTests.java'
launcher.write_text('''import org.junit.platform.launcher.core.*;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectPackage;
public class RunTests {
 public static void main(String[] args) throws Exception {
  var records = new java.util.ArrayList<String>();
  var listener = new SummaryGeneratingListener();
  var launcher = LauncherFactory.create(); launcher.registerTestExecutionListeners(listener);
  launcher.registerTestExecutionListeners(new org.junit.platform.launcher.TestExecutionListener() {
   public void executionFinished(org.junit.platform.launcher.TestIdentifier id, org.junit.platform.engine.TestExecutionResult result) {
    if(id.isTest()) records.add(result.getStatus()+String.valueOf((char)9)+id.getUniqueId());
   }
  });
  launcher.execute(LauncherDiscoveryRequestBuilder.request().selectors(selectPackage("fr.proutlost")).build());
  java.nio.file.Files.write(java.nio.file.Path.of("build/quest-offline/tests.tsv"), records);
  listener.getSummary().printTo(new java.io.PrintWriter(System.out,true));
  listener.getSummary().printFailuresTo(new java.io.PrintWriter(System.out,true));
  if(listener.getSummary().getTestsFoundCount()==0 || listener.getSummary().getTotalFailureCount()>0) System.exit(1);
 }
}''')
args = output / 'test.args'
args.write_text('\n'.join(['--release','21','-encoding','UTF-8','-proc:none','-classpath',classpath,'-d',str(tests)] + list(map(str,sources+[launcher]))))
run(['javac','@'+str(args)])
temp = output / 'tmp'
temp.mkdir(exist_ok=True)
run(['java','-Djava.io.tmpdir='+str(temp),'-cp',os.pathsep.join([str(tests),classpath]),'RunTests'])

# Diagnostic archive only: deliberately named differently from the release Gradle artifact.
properties = {}
for line in (root/'gradle.properties').read_text().splitlines():
    if '=' in line and not line.startswith('#'):
        key, value = line.split('=',1); properties[key] = value
metadata = (root/'src/main/templates/META-INF/neoforge.mods.toml').read_text()
for key, value in properties.items(): metadata = metadata.replace('${'+key+'}',value)
if '${' in metadata: sys.exit('Unexpanded mod metadata')
artifact = output/'proutlost-0.1.0-CB213-verification-only.jar'
with zipfile.ZipFile(artifact,'w',zipfile.ZIP_DEFLATED) as jar:
    jar.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\nImplementation-Version: CB213-verification-only\n\n')
    jar.writestr('META-INF/neoforge.mods.toml',metadata)
    for tree in [main, root/'src/main/resources', root/'src/generated/resources']:
        if tree.exists():
            for item in sorted(tree.rglob('*')):
                if item.is_file() and '.cache' not in item.parts: jar.write(item,item.relative_to(tree).as_posix())
(output/'artifact.sha256').write_text(hashlib.sha256(artifact.read_bytes()).hexdigest()+'  '+artifact.name+'\n')
print('Diagnostic artifact:',artifact)
