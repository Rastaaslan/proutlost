"""Declared synthetic CSV metadata fixtures only; no Minecraft/CPU measurement."""
import csv,hashlib,json,tempfile,unittest
from decimal import Decimal
from pathlib import Path
import analyze_cohort_capture as m

class JoinTests(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory(prefix='P008-JOIN-PURE-');self.base=Path(self.tmp.name)
  self.plan_path=self.base/'native-plan.json'
  self.plan=dict(schema_version=1,run_id='synthetic-cohort-run',branch='DIRECTOR',mode='FULL_MC24',actual_players_target=24,human_operators=0,controller_id='synthetic-controller',automatic_selection=False,
    actors=[dict(player_id=str(__import__('uuid').UUID(int=n)),profile_name='P008_'+str(n),controller_id='synthetic-controller') for n in range(1,25)],survey_witnesses=[str(__import__('uuid').UUID(int=n)) for n in (1,2)],pins={key:'a'*64 for key in m.PIN_FIELDS})
  self.plan_path.write_text(json.dumps(self.plan))
  self.identity={k:('a'*64 if k.endswith('sha256') else 'DECLARED_SYNTHETIC') for k in m.REQUIRED_ID|m.DIRECTOR_ID}
  self.identity.update(authority_session='SessionId[value=00000000-0000-4000-8000-000000000003]',process_started_utc='2026-11-18T00:00:00Z',java_pid='100',boot_epoch='2',store_generation='1',world_id='00000000-0000-4000-8000-000000000001',store_id='00000000-0000-4000-8000-000000000002',branch='DIRECTOR',capture_format='P008_COHORT_V2',cohort_mode='FULL_MC24',actual_players_target='24',human_operators='0',controller_id='synthetic-controller',runtime_scope='REAL_CLOSED_QA_RUNTIME_ACTOR_FRAMES',plan_sha256=m.raw_sha(self.plan_path),allowed_uuid_sha256=m.load_plan(self.plan_path)['uuid_sha256'])
  self.v=self.make('vanilla',9,13);self.a=self.make('actor',10,14)
 def tearDown(self):self.tmp.cleanup()
 def make(self,role,first,last,cpu=True):
  root=self.base/(role+str(len(list(self.base.iterdir()))));root.mkdir();path=root/('ticks.csv' if role=='vanilla' else 'actor-frames.csv')
  rows=[]
  for n,tick in enumerate(range(first,last+1)):
   wall=1_000_000_000+tick*50_000_000
   row=[tick,wall,1_000_000,24,'','','',''] if role=='vanilla' else [tick,wall,tick,24,n+1,2_000_000,500_000,100,3,20,100,80,200,150 if cpu else -1,n,n,0,0,0,20,250,150 if cpu else -1,3_000_000,0,'RUNNING','NORMAL',7]
   rows.append(row)
  with path.open('w',newline='') as f:
   writer=csv.writer(f,lineterminator='\n');writer.writerow(m.VANILLA_HEADER if role=='vanilla' else m.ACTOR_HEADER);writer.writerows(rows)
  started=int(rows[0][1])-1;ended=int(rows[-1][1])+5
  start=dict(schema_version=2,provider_mode='TEST_ONLY',no_admission=True,no_world_writes=True,source='VANILLA_PREVIOUS_COMPLETED_TICK_NANOS_AT_POST' if role=='vanilla' else 'COMPLETED_PRODUCTION_RUNTIME_ACTOR_BUDGET_FRAME',identity=self.identity,started_monotonic_ns=started,duration_limit_seconds=30,source_sample_limit=650000,storage_limit_bytes=134217728,queue_capacity=4096)
  end=dict(schema_version=2,provider_mode='TEST_ONLY',identity=self.identity,end_observed=True,end_incomplete=False,observed_range_contiguous_and_drained=True,end_reason='EXPLICIT_STOP',failure='',source_samples=len(rows),queued_samples=len(rows),written_samples=len(rows),queue_dropped_samples=0,accepted_but_unwritten_samples=0,invalid_samples=0,duplicate_observations=0,source_gap_ticks=0,csv_bytes=path.stat().st_size,csv_sha256=hashlib.sha256(path.read_bytes()).hexdigest(),first_queued_completed_tick=first,last_queued_completed_tick=last,ended_monotonic_ns=ended,capture_wall_elapsed_ns=ended-started,last_observation_age_at_end_ns=5)
  if role=='actor':end.update(written_cpu_available_samples=len(rows) if cpu else 0,written_cpu_unavailable_samples=0 if cpu else len(rows),actor_cpu_coverage_complete=cpu)
  (root/'run-start.json').write_text(json.dumps(start));(root/'run-end.json').write_text(json.dumps(end));return root
 def edit(self,root,name,key,value):
  p=root/name;data=json.loads(p.read_text());data[key]=value;p.write_text(json.dumps(data))
 def rows(self,root,role,change):
  p=root/('ticks.csv' if role=='vanilla' else 'actor-frames.csv');rows=list(csv.reader(p.read_text().splitlines()));change(rows)
  with p.open('w',newline='') as f:writer=csv.writer(f,lineterminator='\n');writer.writerows(rows)
  self.edit(root,'run-end.json','csv_sha256',hashlib.sha256(p.read_bytes()).hexdigest());self.edit(root,'run-end.json','csv_bytes',p.stat().st_size)
 def join(self,seconds='0.1'):return m.analyze(self.v,self.a,self.plan_path,Decimal(seconds))
 def test_valid_boundaries_rate_and_raw_percentile(self):
  r=self.join();self.assertEqual(r['join']['shared_rows'],4);self.assertEqual(r['observed_completed_tick_rate_per_second_decimal'],'20.000000');self.assertEqual(r['vanilla_duration']['p95_ns'],1000000);self.assertTrue(r['technical_measurement_window_complete']);self.assertFalse(r['operator_approval_obtained'])
 def test_minimum_duration_not_extrapolated(self):
  self.assertFalse(self.join('900')['technical_measurement_window_complete'])
  with self.assertRaises(m.Refused):self.join('0')
 def test_unknown_cpu_not_zero(self):
  self.a=self.make('actor',10,14,False);r=self.join();self.assertFalse(r['actor_cpu_coverage_complete']);self.assertIsNone(r['director_actor_cpu_available_subset']['p95_ns'])
 def test_short_cpu_tail(self):
  self.a=self.make('actor',10,11)
  with self.assertRaises(m.Refused):self.join()
 def test_interior_gap_even_resealed(self):
  self.rows(self.a,'actor',lambda rows:rows.pop(3))
  with self.assertRaises(m.Refused):self.join()
 def test_process_identity_mismatch(self):
  for name in ['run-start.json','run-end.json']:
   p=self.a/name;d=json.loads(p.read_text());d['identity']['java_pid']='101';p.write_text(json.dumps(d))
  with self.assertRaises(m.Refused):self.join()
 def test_dropped(self):
  self.edit(self.a,'run-end.json','queue_dropped_samples',1)
  with self.assertRaises(m.Refused):self.join()
 def test_digest_corruption(self):
  with (self.v/'ticks.csv').open('a') as f:f.write('9\n')
  with self.assertRaises(m.Refused):self.join()
 def test_boolean_schema(self):
  self.edit(self.a,'run-start.json','schema_version',True)
  with self.assertRaises(m.Refused):self.join()
 def test_boolean_cpu_counter(self):
  self.a=self.make('actor',10,14,False);self.edit(self.a,'run-end.json','written_cpu_available_samples',False)
  with self.assertRaises(m.Refused):m.load(self.a,'actor')
 def test_end_clock_before_rows(self):
  self.edit(self.a,'run-end.json','ended_monotonic_ns',0)
  with self.assertRaises(m.Refused):self.join()
 def test_malformed_world_uuid(self):
  for root in [self.v,self.a]:
   for name in ['run-start.json','run-end.json']:
    p=root/name;d=json.loads(p.read_text());d['identity']['world_id']='DECLARED_NOT_UUID';p.write_text(json.dumps(d))
  with self.assertRaises(ValueError):self.join()
 def test_malformed_authority_session(self):
  for root in [self.v,self.a]:
   for name in ['run-start.json','run-end.json']:
    p=root/name;d=json.loads(p.read_text());d['identity']['authority_session']='foreign';p.write_text(json.dumps(d))
  with self.assertRaises(m.Refused):self.join()
 def test_negative_zero(self):
  with self.assertRaises(m.Refused):m.integer('-0')
 def test_vanilla_cpu_forbidden(self):
  self.rows(self.v,'vanilla',lambda rows:rows[1].__setitem__(4,'0'))
  with self.assertRaises(m.Refused):self.join()
 def test_cumulative_regression(self):
  self.rows(self.a,'actor',lambda rows:rows[-1].__setitem__(14,'0'))
  with self.assertRaises(m.Refused):self.join()



 def baseline(self,players=24):
  self.plan['branch']='BASELINE';self.plan_path.write_text(json.dumps(self.plan))
  self.identity={key:value for key,value in self.identity.items() if key not in m.DIRECTOR_ID}
  self.identity.update(branch='BASELINE',runtime_scope='PRODUCTION_RUNTIME_ABSENT_DIRECTOR_JAR_LOADED',plan_sha256=m.raw_sha(self.plan_path))
  self.v=self.make('vanilla',9,13)
  if players!=24:self.rows(self.v,'vanilla',lambda rows:[row.__setitem__(3,str(players)) for row in rows[1:]])
 def test_baseline_without_actor_has_no_invented_zero_cpu(self):
  self.baseline();r=m.analyze(self.v,None,self.plan_path,Decimal('0.1'))
  self.assertTrue(r['technical_measurement_window_complete']);self.assertIsNone(r['director_actor_cpu_available_subset']);self.assertIsNone(r['actor_cpu_coverage_complete'])
  self.assertEqual(r['actor_capture'],'NOT_APPLICABLE_RUNTIME_ABSENT')
 def test_baseline_actor_argument_refused(self):
  self.baseline()
  with self.assertRaises(m.Refused):m.analyze(self.v,self.a,self.plan_path,Decimal('0.1'))
 def test_baseline_zero_players_stays_zero_and_partial(self):
  self.baseline(0);r=m.analyze(self.v,None,self.plan_path,Decimal('0.1'))
  self.assertEqual(r['observed_player_list_counts']['maximum'],0);self.assertFalse(r['target_cohort_window_observed']);self.assertFalse(r['technical_measurement_window_complete'])
 def test_twenty_five_observed_rows_impossible_under_twenty_four_uuid_plan(self):
  self.rows(self.v,'vanilla',lambda rows:rows[1].__setitem__(3,'25'))
  with self.assertRaises(m.Refused):self.join()
 def test_twenty_six_observation_rejected(self):
  self.rows(self.v,'vanilla',lambda rows:rows[1].__setitem__(3,'26'))
  with self.assertRaises(m.Refused):self.join()
 def test_two_observed_players_never_inflated_to_target_twenty_four(self):
  self.rows(self.v,'vanilla',lambda rows:[row.__setitem__(3,'2') for row in rows[1:]])
  r=self.join();self.assertEqual(r['observed_player_list_counts']['maximum'],2);self.assertFalse(r['technical_measurement_window_complete'])
 def test_plan_raw_digest_mismatch_refused(self):
  self.plan_path.write_text(json.dumps(self.plan)+' ')
  with self.assertRaises(m.Refused):self.join()
 def test_capture_pin_mismatch_refused_even_if_streams_agree(self):
  for root in (self.v,self.a):
   for name in ('run-start.json','run-end.json'):
    p=root/name;data=json.loads(p.read_text());data['identity']['profile_sha256']='b'*64;p.write_text(json.dumps(data))
  with self.assertRaises(m.Refused):self.join()
 def test_planned_uuid_set_mismatch_refused(self):
  for name in ('run-start.json','run-end.json'):
   p=self.a/name;data=json.loads(p.read_text());data['identity']['allowed_uuid_sha256']='b'*64;p.write_text(json.dumps(data))
  with self.assertRaises(m.Refused):self.join()
 def test_director_without_actor_refused(self):
  with self.assertRaises(m.Refused):m.analyze(self.v,None,self.plan_path,Decimal('0.1'))
 def test_wrong_format_not_legacy_r1_misread(self):
  self.edit(self.v,'run-start.json','schema_version',1)
  with self.assertRaises(m.Refused):self.join()
 def test_duplicate_planned_profile_or_uuid_refused(self):
  for field in ('player_id','profile_name'):
   p=json.loads(json.dumps(self.plan));p['actors'][1][field]=p['actors'][0][field];self.plan_path.write_text(json.dumps(p))
   with self.assertRaises(m.Refused):m.load_plan(self.plan_path)
 def test_camelcase_wire_not_silently_accepted(self):
  p=dict(self.plan);p['schemaVersion']=p.pop('schema_version');self.plan_path.write_text(json.dumps(p))
  with self.assertRaises(m.Refused):m.load_plan(self.plan_path)

if __name__=='__main__':unittest.main()
