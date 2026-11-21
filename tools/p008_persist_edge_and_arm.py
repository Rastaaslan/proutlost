"""Windows root controller: persist proven private QA boundary, then optionally arm next."""
import argparse,ast,datetime,hashlib,json,os,pathlib,subprocess
p=argparse.ArgumentParser();p.add_argument('completed',choices=['source','stage']);p.add_argument('next',choices=['stage','activation']);a=p.parse_args()
r=pathlib.Path(r'C:\WorldDirectorS2\fullstack\PLS2-FULLSTACK-P0-001');c=r/'checkpoints/P008-20261118T062924Z-root'
assert json.loads((r/'OWNER.json').read_text(encoding='utf-8-sig'))['session']=='P008-20261118T062924Z-root'
assert json.loads((r/'P008_INTEGRATOR.lock').read_text())['execution_id']=='P008-20261118T062924Z-root'
code="""import json,pathlib,sys
sys.path.insert(0,'/workspace/p008-tools-v4')
from run_linux_qa import require_owned
r=pathlib.Path('/workspace/P008-L2-20261118T062924Z-root-a02');p=require_owned(r)
side=r/'world/P008-paged-native-1dfce91d9c7ce856-cold-r2-BOUNDARY-01.properties'
props=dict(line.split('=',1) for line in side.read_text().splitlines() if line and not line.startswith('#'))
crash=sorted((r/'receipts').glob('abrupt-BOUNDARY-*.json'))[-1];cr=json.loads(crash.read_text())
assert cr['status']=='EXACT_OWNED_QA_JVM_ABRUPT_END_VERIFIED'
files=[str(side),str(crash),str(r/'P008_QA_PREPARATION.json'),str(r/'server-process.json'),p['args'],p['log'],cr['log']]
print(json.dumps(dict(props=props,crash=cr,process=p,files=files)))
""".replace('BOUNDARY',a.completed)
x=subprocess.run(['docker','exec','proutlost-p008-l2-20261118','python3','-c',code],capture_output=True)
assert x.returncode==0,x.stderr.decode();v=json.loads(x.stdout.decode());props=v['props']
assert props['pending_boundary']==a.completed and props['pending_preparation_tick']=='88593'
assert props['pending_generation']=='378bf5e45f5377afbc87c00fb92385ba22f33ef41694f75c1e65b7e51f9f7635'
assert ast.literal_eval(props['pending_source_pages'])==([0] if a.completed=='source' else list(range(18)))
assert ast.literal_eval(props['pending_metadata_pages'])==([] if a.completed=='source' else [0])
assert props['pending_activations']=='0' and props['pending_cancellations']=='0'
assert props['heads']=='1360' and props['other_source_heads']=='4' and props['pid']==str(v['process']['pid'])
d=c/('native-b05-edge-'+a.completed+'-proof');d.mkdir()
for f in dict.fromkeys(v['files']):subprocess.run(['docker','cp','proutlost-p008-l2-20261118:'+f,str(d)],check=True)
proof=dict(scope='DECLARED_SYNTHETIC_B05_NATIVE_BOUNDARY_NEW_JVM_OLD_ACTIVE_READERS',status='PASS',
 boundary=a.completed,readback=props,crash=v['crash'],process=v['process'],
 current_log_copy_scope='prefix at capture; old crashed JVM log is closed',
 files_sha256={f.name:hashlib.sha256(f.read_bytes()).hexdigest() for f in d.iterdir() if f.is_file()})
(d/'P008_L2_NATIVE_BOUNDARY_PROOF.json').write_text(json.dumps(proof,indent=2),encoding='utf8')
def atomic(path,value):
 t=path.with_suffix('.p008-root.tmp')
 with t.open('w',encoding='utf8',newline='\n') as stream:json.dump(value,stream,indent=2);stream.write('\n');stream.flush();os.fsync(stream.fileno())
 os.replace(t,path)
s=json.loads((r/'P008_EXECUTION_STATUS.json').read_text(encoding='utf-8-sig'));stamp=datetime.datetime.now(datetime.timezone.utc).isoformat()
s.update(updated_utc=stamp,last_activity_observed=stamp,last_verified_progress_utc=stamp,
 last_verified_progress='Native B05 '+a.completed+' interruption/recovery PASS: R1 1360+other4; exact R2 durable page sets, same clock88593',
 current_operation='Arm owned QA '+a.next+' abrupt interruption after persisted '+a.completed+' independent readback',
 next_action='After ARMED, actual R2 resume; fresh JVM old/new complete readback')
s['qualification']['B05_NATIVE_'+a.completed.upper()+'_EDGE']=dict(status='PASS',proof=str(d),pending_clock=props['pending_preparation_tick'],
 active_heads=1360,other_heads=4,source_pages=ast.literal_eval(props['pending_source_pages']),metadata_pages=ast.literal_eval(props['pending_metadata_pages']))
process=v['process'];s['processes']['qa'].update(status='NATIVE_'+a.completed.upper()+'_READBACK_PASS_NEXT_'+a.next.upper()+'_ARMING',
 pid=process['pid'],start_ticks=process['identity']['start_ticks'],identity=process['identity'],log=process['log'],args=process['args'],
 planned_operation='AUTHORIZED_SIGKILL_EXACT_OWNED_QA_PIDFD_AT_R2_'+a.next.upper(),ended_verified=False)
atomic(r/'P008_EXECUTION_STATUS.json',s)
owner=json.loads((r/'OWNER.json').read_text(encoding='utf-8-sig'));owner.update(utc=stamp,action=s['current_operation'],qa_actual_identity=process['identity'],qa_linux_pid=process['pid'],qa_jvm=s['processes']['qa']['status']);atomic(r/'OWNER.json',owner)
with (r/'P008_EXECUTION_JOURNAL.md').open('a',encoding='utf8') as stream:
 stream.write('\n### '+stamp+' — native '+a.completed+' recovery PASS\nExact pending page sets, no activation/cancel, retained clock88593. R1 all1360+other4 and actual first/far readers in newJVM. Proof '+str(d)+'. Next '+a.next+' exact owned pidfd interruption. No CDC-wide or canonical acceptance.\n')
print('BOUNDARY_PROOF_PERSISTED',d,flush=True)
raise SystemExit(subprocess.run(['docker','exec','proutlost-p008-l2-20261118','python3','/workspace/p008-tools-v4/kill_owned_qa_at_boundary.py',
 '--root','/workspace/P008-L2-20261118T062924Z-root-a02','--boundary',a.next,'--timeout-seconds','240']).returncode)
