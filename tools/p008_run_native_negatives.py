"""Run the declared P008 negative fixtures through the real owned OP4 endpoint.

The receipt qualifies terminal refusals only. The subsequent cold observer must
independently verify unchanged durable source/heads and other-source retention.
"""
from pathlib import Path
import argparse, hashlib, json, sys, time
sys.path.insert(0, '/workspace/p008-tools-v4')
from run_linux_qa import atomic, native_entry, rcon, require_owned

p=argparse.ArgumentParser()
p.add_argument('--root', type=Path, required=True)
p.add_argument('--cohort', choices=['before_r2','after_r2'], required=True)
a=p.parse_args(); root=a.root.resolve()
assert root == Path('/workspace/P008-L2-20261118T062924Z-root-a02')
assert (root/'world/TEST_ONLY').read_text().strip() == 'PROUTLOST_NEUTRAL_QA_V1'
owned=require_owned(root)
cases=([
 ('p008_bad_missing_transfer','REFUSED'),
 ('p008_bad_duplicate_transfer','REFUSED'),
 ('p008_bad_corrupt_transfer','REFUSED'),
 ('p008_bad_oversize_transfer','REFUSED'),
 ('p008_bad_conflict_r1_transfer','REFUSED'),
 ('p008_qa_r1_replay_transfer','ALREADY_DURABLE'),
] if a.cohort=='before_r2' else [
 ('p008_bad_conflict_r2_transfer','REFUSED'),
 ('p008_bad_stale_r1_transfer','REFUSED'),
 ('p008_qa_r2_transfer','ALREADY_DURABLE'),
])
target=root/'receipts'/('native-negative-'+a.cohort+'-'+str(time.time_ns())+'.json')
proof=dict(scope='DECLARED_QA_NATIVE_TERMINAL_OUTCOMES_ONLY',cohort=a.cohort,
 server_identity=owned['identity'],status='RUNNING',cases=[],
 active_heads_other_source_verification='PENDING_INDEPENDENT_COLD_OBSERVER')
atomic(target,proof)
for name,expected in cases:
 assert require_owned(root)['identity']==owned['identity']
 directory=root/'world/director-location-imports'/name
 files={f.name:hashlib.sha256(f.read_bytes()).hexdigest() for f in sorted(directory.iterdir()) if f.is_file()}
 assert 'manifest.json' in files
 log=Path(owned['log']); offset=log.stat().st_size
 native_entry(root,'QAAlpha',name)
 deadline=time.monotonic()+45; terminal=''
 while time.monotonic()<deadline:
  terminal=rcon(root,'execute as QAAlpha at @s run proutlost director lab locations status')
  if 'LOCATION_IMPORT IDLE lane=false' in terminal and expected in terminal:break
  if 'UNCERTAIN' in terminal:raise RuntimeError('Uncertain J04 ACK: preserve state and diagnose')
  time.sleep(.2)
 else:raise RuntimeError('Expected terminal outcome missing: '+name+' '+terminal)
 assert require_owned(root)['identity']==owned['identity']
 with log.open('rb') as stream:
  stream.seek(offset); lines=stream.read(262144).decode(errors='replace')
 proof['cases'].append(dict(name=name,expected=expected,terminal=terminal,
  input_files_sha256=files,new_log_offset=offset,
  relevant_log=[line for line in lines.splitlines() if 'DIRECTOR_LOCATION_' in line]))
 atomic(target,proof)
 print('NATIVE_NEGATIVE_TERMINAL_PASS',name,terminal.strip(),flush=True)
proof['status']='TERMINAL_CASES_PASS_DURABLE_READBACK_PENDING'
proof['ended_epoch']=time.time();atomic(target,proof)
print('NATIVE_NEGATIVE_COHORT_RECEIPT',target,flush=True)
