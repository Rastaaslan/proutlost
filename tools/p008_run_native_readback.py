"""Drive only the existing read-only installed QA observer and real client demand."""
from pathlib import Path
import argparse, hashlib, json, re, sys, time
sys.path.insert(0,'/workspace/p008-tools-v4')
from run_linux_qa import atomic, native_entry, rcon, require_owned
p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True)
p.add_argument('--phase',choices=['first','cold'],required=True)
p.add_argument('--transfer',required=True);p.add_argument('--sha256',required=True)
p.add_argument('--run-id',default='');a=p.parse_args();root=a.root.resolve()
assert root==Path('/workspace/P008-L2-20261118T062924Z-root-a02')
assert re.fullmatch('[a-f0-9]{64}',a.sha256)
assert not a.run_id or a.phase=='cold' and re.fullmatch('[a-z0-9_-]{1,48}',a.run_id)
owned=require_owned(root);args=Path(owned['args']).read_text()
for k,v in [('Name',a.transfer),('Sha256',a.sha256)]:
 assert '-Dproutlost.director.nativePagedLocationImport'+k+'='+v in args.splitlines()
if a.run_id:assert '-Dproutlost.director.nativePagedLocationImportReadbackRunId='+a.run_id in args.splitlines()
suffix='-first' if a.phase=='first' else '-cold'+('-'+a.run_id if a.run_id else '')
target=root/'world'/('P008-paged-native-'+a.sha256[:16]+suffix+'.properties')
assert not target.exists(),'Preserve existing immutable observer receipt'
assert 'QAAlpha' in rcon(root,'list') and 'QABravo' in rcon(root,'list')
assert 'NOT_STARTED' in rcon(root,'proutlost_p008_qa observe status')
receipt=root/'receipts'/('readback-controller-'+str(time.time_ns())+'.json')
proof=dict(status='RUNNING_READONLY_OBSERVER',identity=owned['identity'],phase=a.phase,
 run_id=a.run_id,source_sha256=a.sha256,target=str(target),human_perception=False)
atomic(receipt,proof)
native_entry(root,'QAAlpha',a.transfer)
deadline=time.monotonic()+45
while time.monotonic()<deadline:
 status=rcon(root,'execute as QAAlpha at @s run proutlost director lab locations status')
 if 'LOCATION_IMPORT IDLE lane=false' in status and ('ALREADY_DURABLE' in status or ' DURABLE' in status):break
 if 'REFUSED' in status:raise RuntimeError('Actual replay refused: '+status)
 time.sleep(.2)
else:raise RuntimeError('Native replay did not become durably terminal')
proof['native_replay_status']=status;atomic(receipt,proof)
print(rcon(root,'tp QAAlpha 1000004 64 1000004'),flush=True)
print(rcon(root,'proutlost_p008_qa observe '+a.phase),flush=True)
deadline=time.monotonic()+180;far=False
while time.monotonic()<deadline:
 assert require_owned(root)['identity']==owned['identity']
 status=rcon(root,'proutlost_p008_qa observe status')
 if '_FAILED' in status:raise RuntimeError('Actual observer failed: '+status)
 if not far and 'phase=WAIT_FAR_REAL_CLIENT_DEMAND' in status:
  print(rcon(root,'tp QAAlpha 1080900 64 1000004'),flush=True);far=True
 if '_PASS cold=' in status:
  assert far and target.is_file()
  body=target.read_bytes();assert b'heads=1360' in body and b'other_source_heads=4' in body
  proof.update(status='NATIVE_OBSERVER_PASS',observer_status=status,sidecar_sha256=hashlib.sha256(body).hexdigest(),far_movement=True)
  atomic(receipt,proof);print(json.dumps(proof),flush=True);break
 time.sleep(.4)
else:raise RuntimeError('Read-only observer did not complete: '+status)
