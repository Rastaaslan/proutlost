#!/usr/bin/env python3
"""Refresh candidate digests and exports without writing Git objects/refs. No PASS assertion."""
from pathlib import Path
import hashlib,json,subprocess,tarfile,sys
root=Path(__file__).resolve().parents[1]
def sha(data):return hashlib.sha256(data).hexdigest()
def write(name,data):(root/'docs/worlddirector-v1'/name).write_text(json.dumps(data,indent=2,ensure_ascii=False)+'\n')
runs=json.loads((root/'build/director/executions.json').read_text())
assert all(c['exit_code']==0 for c in runs['checks'])
assert {c['name'] for c in runs['checks']}=={'compile','contracts','bootstrap','packaging','platform-compile'}
current={str(p.relative_to(root)) for folder in ('src/director/java','src/directorTest/java','src/directorPlatform/java') for p in (root/folder).rglob('*.java') if p.is_file()}
recorded=set(runs['compiled_source_sha256'])
if current!=recorded:
 raise SystemExit('tests stale: Java inventory differs; added='+str(sorted(current-recorded))+'; removed='+str(sorted(recorded-current)))
for path,digest in runs['compiled_source_sha256'].items():
 if sha((root/path).read_bytes())!=digest:raise SystemExit('tests stale: '+path)
if '--verify-only' in sys.argv:
 print('PASS: complete compiled Java inventory and hashes match');sys.exit(0)
assert 'PASS_STATIC_SPEC_CHECKS' in (root/'build/director/schema.log').read_text()
base=subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip()
spec=root/'build/director-input/spec-1.0.0'
entries=[]
for p in sorted(spec.rglob('*')):
 if not p.is_file():continue
 b=p.read_bytes();entries.append({'path':str(p.relative_to(spec)),'bytes':len(b),'sha256':sha(b),'git_blob_sha1':hashlib.sha1(b'blob '+str(len(b)).encode()+b'\0'+b).hexdigest()})
write('NORMATIVE_INPUTS.json',{'spec_version':'1.0.0','official_zip_sha256':'ad09d2b65f234107e8db2e45778b7a78800a2d6806e32fa7570cb4adc7041252','verified_repack_sha256':'1b00564384c295733beff865707f0da9267b4b0b8a8c1f98bd63fb1da9ef07f2','local_path':str(spec.relative_to(root)),'git_objects_written':False,'operator_action':'Work copies the 40 exact files from CB-202 for subsequent jobs. Blob IDs are identity checks, not a claim of published objects.','files':entries})
paths=sorted([p for folder in ['src/director','src/directorTest','src/directorPlatform','scripts'] for p in (root/folder).rglob('*') if p.is_file()]+[root/'build.gradle',root/'docs/worlddirector-v1/OWNERSHIP.json',root/'docs/worlddirector-v1/NORMATIVE_INPUTS.json'])
source=[{'path':str(p.relative_to(root)),'sha256':sha(p.read_bytes()),'bytes':p.stat().st_size}for p in paths]
digest=sha(''.join(e['path']+'\0'+e['sha256']+'\n' for e in source).encode())
write('API_MANIFEST_1.0.0.json',{'spec_version':'1.0.0','api_version':'1.0.0','qualification':'CANDIDATE_NOT_FROZEN','base_commit':base,'source_commit':None,'source_digest_algorithm':'SHA256 of UTF8 sorted path + NUL + file SHA256 + LF','source_digest':digest,'platform':{'java':21,'minecraft':'1.21.1','neoforge':'21.1.248','gradle':'8.14.4'},'files':source})
logs=[]
for p in sorted((root/'build/director').glob('*.log')):logs.append({'path':str(p.relative_to(root)),'sha256':sha(p.read_bytes()),'bytes':p.stat().st_size})
write('TEST_EVIDENCE.json',{'source_digest':digest,'compile':'PASS_JAVA21_DIRECT','pinned_api_compile':'PASS','contracts':'PASS','schema':'PASS','bootstrap':'PASS','gradle_local':'FAIL_ENVIRONMENT_BEFORE_PROJECT_CONFIGURATION','gradle_operator_prior_snapshot':'PASS_REPORTED_BY_WORK: dcb035f5..., 2124 assertions, directorBootstrap/directorJar and compileDirectorPlatformJava; not final-source qualification','gradle_final_snapshot':'NOT_RUN_PENDING_WORK_HANDOFF','gradle_final_task_graph':'NOT_RUN: local --dry-run fails before project configuration; Work must verify isolated graph','packaging_guard':'PASS','native':'NOT_RUN','client':'NOT_RUN','gametests':'NOT_RUN','logs':logs,'qualification':'NOT_FROZEN: final snapshot supplied to Work for operator rerun, local commit and freeze decision; no sandbox G0_PASS'})
export=root/'build/director/G0_SOURCE_EXPORT.tar.gz'
with tarfile.open(export,'w:gz') as tar:
 for p in sorted(set(paths+list((root/'docs/worlddirector-v1').glob('*')))):
  if p.is_file():tar.add(p,arcname=str(p.relative_to(root)),recursive=False)
print('source_digest='+digest);print('source_export_sha256='+sha(export.read_bytes()));print('base_commit='+base);print('source_files='+str(len(source)))
