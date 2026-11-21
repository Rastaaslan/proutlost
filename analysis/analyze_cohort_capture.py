#!/usr/bin/env python3
"""Read-only exact-tick join of the separate TEST_ONLY cohort observer streams.
No Minecraft/CPU measurement occurs here. No averaging of periodic Minecraft summaries.
"""
from __future__ import annotations
import argparse
from array import array
import csv
from decimal import Decimal, InvalidOperation, localcontext
import hashlib
import json
from pathlib import Path
import re
import uuid
from datetime import datetime
from typing import Any

MAX_BYTES=128*1024*1024
MAX_ROWS=650000
VANILLA_HEADER=['completed_tick','wall_monotonic_ns','tick_duration_ns','players','director_actor_cpu_ns','director_worker_cpu_ns','critical_cpu_ns','decision_cpu_ns']
ACTOR_HEADER=['frame_native_tick','wall_monotonic_ns','observed_post_tick','player_count_at_observation','frame_ordinal','global_budget_ns','critical_budget_ns','operation_limit','operations','mandatory_wall_ns','critical_wall_ns','ordinary_wall_ns','actor_wall_ns','actor_cpu_ns','critical_units_cumulative','ordinary_units_cumulative','unit_overruns_cumulative','critical_overruns_cumulative','emergency_critical_units_cumulative','window_samples','actor_wall_p95_ns','actor_cpu_p95_ns','p95_goal_ns','p95_goal_exceeded','phase_at_observation','health_at_observation','world_revision_at_observation']
REQUIRED_ID={'world_root','main_jar_sha256','director_jar_sha256','observer_jar_sha256','qualified_sources_sha256','candidate_sha256','profile_sha256','action_plan_sha256','plan_sha256','allowed_uuid_sha256','cohort_mode','branch','actual_players_target','human_operators','controller_id','java_pid','process_started_utc','runtime_scope','player_scope','join_scope','capture_format','run_id'}
DIRECTOR_ID={'world_id','store_id','store_generation','boot_epoch','authority_session','fixture_sha256','geometry_sha256'}
PIN_FIELDS={'candidate_sha256','profile_sha256','action_plan_sha256','sources_sha256','director_jar_sha256'}

class Refused(ValueError):
    pass

def need(value: bool, reason: str)->None:
    if not value:
        raise Refused(reason)

def integer(value: str, *, minimum: int=0, maximum: int=(1<<63)-1)->int:
    need(isinstance(value,str) and re.fullmatch(r'-?(0|[1-9][0-9]*)',value) is not None,'noncanonical integer')
    number=int(value)
    need(str(number)==value and minimum<=number<=maximum,'integer out of range')
    return number

def meta_int(value: Any,minimum: int=0,maximum: int=(1<<63)-1)->int:
    need(type(value) is int and minimum<=value<=maximum,'exact bounded metadata integer required')
    return value

def regular(root: Path,name: str,maximum: int)->Path:
    path=root/name
    need(path.is_file() and not path.is_symlink() and path.resolve().parent==root,'regular bounded artifact required: '+name)
    need(path.stat().st_size<=maximum,'artifact byte bound: '+name)
    return path

def raw_sha(path: Path)->str:
    sha=hashlib.sha256()
    with path.open('rb') as file:
        for block in iter(lambda:file.read(65536),b''):
            sha.update(block)
    return sha.hexdigest()

def metadata(path: Path)->dict[str,Any]:
    def unique(pairs):
        result={}
        for key,value in pairs:
            need(key not in result,'duplicate JSON key')
            result[key]=value
        return result
    result=json.loads(path.read_text(encoding='utf-8'),object_pairs_hook=unique,parse_constant=lambda _: (_ for _ in ()).throw(Refused('nonfinite JSON')))
    need(isinstance(result,dict),'metadata object required')
    return result

def load(root_value: Path,role: str)->dict[str,Any]:
    need(root_value.is_dir() and not root_value.is_symlink(),'unlinked capture directory required')
    root=root_value.resolve()
    start=metadata(regular(root,'run-start.json',65536));end=metadata(regular(root,'run-end.json',65536))
    need(meta_int(start.get('schema_version'))==2 and meta_int(end.get('schema_version'))==2,'capture schema')
    need(start.get('provider_mode')=='TEST_ONLY' and end.get('provider_mode')=='TEST_ONLY','declared TEST_ONLY capture required')
    need(start.get('no_admission') is True and start.get('no_world_writes') is True,'observer scope differs')
    started=meta_int(start.get('started_monotonic_ns'))
    ended=meta_int(end.get('ended_monotonic_ns'))
    need(ended>=started,'capture end precedes start')
    duration_limit=meta_int(start.get('duration_limit_seconds'),1,32400)
    sample_limit=meta_int(start.get('source_sample_limit'),1,MAX_ROWS)
    meta_int(start.get('storage_limit_bytes'),131072,MAX_BYTES);meta_int(start.get('queue_capacity'),1,4096)
    need(meta_int(end.get('capture_wall_elapsed_ns'))==ended-started,'capture elapsed clock accounting')
    expected_source='VANILLA_PREVIOUS_COMPLETED_TICK_NANOS_AT_POST' if role=='vanilla' else 'COMPLETED_PRODUCTION_RUNTIME_ACTOR_BUDGET_FRAME'
    need(start.get('source')==expected_source,'source role mismatch')
    identity=start.get('identity')
    need(isinstance(identity,dict) and REQUIRED_ID<=identity.keys() and identity==end.get('identity'),'start/end identity mismatch or incomplete')
    need(len(identity)<=32 and all(isinstance(k,str) and isinstance(v,str) and len(v)<=1024 for k,v in identity.items()),'bounded string identity required')
    for key in REQUIRED_ID:
        if key.endswith('sha256'):
            need(re.fullmatch(r'[0-9a-f]{64}',identity[key]) is not None and identity[key]!='0'*64,'nonzero identity SHA256')
    integer(identity['java_pid'],minimum=1)
    need(identity['branch'] in {'BASELINE','DIRECTOR'} and identity['capture_format']=='P008_COHORT_V2','closed cohort branch/format required')
    need(identity['cohort_mode']=='FULL_MC24' and integer(identity['actual_players_target'],maximum=25)==24 and integer(identity['human_operators'])==0,'closed native FULL_MC24 plan required')
    if identity['branch']=='DIRECTOR':
        need(DIRECTOR_ID<=identity.keys() and identity['runtime_scope']=='REAL_CLOSED_QA_RUNTIME_ACTOR_FRAMES','Director authority identity absent')
        integer(identity['boot_epoch'],minimum=1);integer(identity['store_generation'],minimum=1)
        for key in ['world_id','store_id']:
            need(str(uuid.UUID(identity[key]))==identity[key],'canonical world/store UUID required')
        authority=re.fullmatch(r'SessionId\[value=([0-9a-f-]{36})\]',identity['authority_session'])
        need(authority is not None and str(uuid.UUID(authority.group(1)))==authority.group(1),'canonical authority session identity required')
        for key in ['fixture_sha256','geometry_sha256']:
            need(re.fullmatch(r'[0-9a-f]{64}',identity[key]) is not None,'Director SHA256 identity required')
    else:
        need(role=='vanilla' and not DIRECTOR_ID.intersection(identity),'BASELINE cannot claim actor/J04 identity')
        need(identity['runtime_scope']=='PRODUCTION_RUNTIME_ABSENT_DIRECTOR_JAR_LOADED','baseline scope differs')
    need(identity['process_started_utc'].endswith('Z'),'UTC process start required')
    datetime.fromisoformat(identity['process_started_utc'][:-1]+'+00:00')
    need(end.get('end_observed') is True and end.get('end_incomplete') is False and end.get('observed_range_contiguous_and_drained') is True,'interrupted or incomplete capture')
    need(end.get('end_reason') in {'EXPLICIT_STOP','SERVER_STOP','DURATION_LIMIT','SAMPLE_LIMIT'},'capture ended on failure/storage limit')
    need(end.get('failure')=='','capture source/IO failure')
    for key in ['queue_dropped_samples','accepted_but_unwritten_samples','invalid_samples','duplicate_observations','source_gap_ticks']:
        need(type(end.get(key)) is int and end[key]==0,'capture loss: '+key)
    name='ticks.csv' if role=='vanilla' else 'actor-frames.csv'
    path=regular(root,name,MAX_BYTES);stamp=path.stat();expected_sha=end.get('csv_sha256')
    need(isinstance(expected_sha,str) and re.fullmatch(r'[0-9a-f]{64}',expected_sha) is not None and raw_sha(path)==expected_sha,'CSV digest mismatch')
    expected=VANILLA_HEADER if role=='vanilla' else ACTOR_HEADER
    data={key:array('q') for key in (['wall','duration','players'] if role=='vanilla' else ['wall','actor_wall','actor_cpu','critical_wall','mandatory_wall','ordinary_wall','players'])}
    count=0;first=None;last=None;last_wall=-1;last_observation=-1;last_ordinal=None;last_cumulative=None;budgets=None;available_cpu=0;phases={};health={};revision_unavailable=0
    with path.open('r',encoding='utf-8',newline='') as file:
        header=file.readline(2049)
        need(header.endswith('\n') and len(header)<=2048,'bounded CSV header')
        need(next(csv.reader([header]))==expected,'exact CSV columns/schema required')
        while True:
            line=file.readline(1025)
            if not line:
                break
            need(len(line)<=1024 and line.endswith('\n'),'bounded complete CSV row required')
            fields=next(csv.reader([line]))
            need(len(fields)==len(expected),'CSV row width')
            tick=integer(fields[0],maximum=(1<<32)-1);wall=integer(fields[1])
            need(last is None or tick==last+1,'missing/regressed/duplicate native tick')
            need(wall>last_wall,'monotone observation clock required')
            if first is None:
                first=tick
            last=tick;last_wall=wall;data['wall'].append(wall)
            if role=='vanilla':
                data['duration'].append(integer(fields[2],minimum=1));data['players'].append(integer(fields[3],maximum=25))
                need(all(v=='' for v in fields[4:]),'vanilla CPU fields must stay unmeasured')
            else:
                observation=integer(fields[2],maximum=(1<<32)-1);data['players'].append(integer(fields[3],maximum=25))
                need(observation>=tick and observation>=last_observation,'frame/observation relation')
                last_observation=observation
                ordinal=integer(fields[4],minimum=1)
                need(last_ordinal is None or ordinal==last_ordinal+1,'actor frame ordinal discontinuity')
                last_ordinal=ordinal
                this_budget=(integer(fields[5],minimum=1),integer(fields[6],minimum=1),integer(fields[7],minimum=1),integer(fields[22],minimum=1))
                need(this_budget[1]<=this_budget[0] and (budgets is None or budgets==this_budget),'budget/source profile changed inside window')
                budgets=this_budget
                need(integer(fields[8])<=this_budget[2],'observation operation cap')
                for index,key in [(9,'mandatory_wall'),(10,'critical_wall'),(11,'ordinary_wall'),(12,'actor_wall')]:
                    data[key].append(integer(fields[index]))
                cpu=integer(fields[13],minimum=-1);data['actor_cpu'].append(cpu);available_cpu+=int(cpu>=0)
                cumulative=tuple(integer(fields[i]) for i in range(14,19))
                need(last_cumulative is None or all(a>=b for a,b in zip(cumulative,last_cumulative)),'cumulative counters regressed')
                last_cumulative=cumulative
                integer(fields[19],minimum=1);integer(fields[20]);integer(fields[21],minimum=-1);integer(fields[23],maximum=1)
                for value,counts in [(fields[24],phases),(fields[25],health)]:
                    need(re.fullmatch(r'[A-Z_]{1,32}',value) is not None,'observed state label')
                    counts[value]=counts.get(value,0)+1
                revision=integer(fields[26],minimum=-1);revision_unavailable+=int(revision<0)
            count+=1
            need(count<=MAX_ROWS,'row count bound')
    need(count>1 and first is not None and last is not None,'at least two real rows needed')
    need(count<=sample_limit and data['wall'][0]>=started and last_wall<=ended and last_wall-started<duration_limit*1000000000,'source rows outside bounded capture clocks')
    need(meta_int(end.get('last_observation_age_at_end_ns'))==ended-last_wall,'last observation clock accounting')
    need(type(end.get('csv_bytes')) is int and end['csv_bytes']==stamp.st_size,'CSV byte accounting')
    need(path.stat().st_size==stamp.st_size and path.stat().st_mtime_ns==stamp.st_mtime_ns and raw_sha(path)==expected_sha,'artifact changed while analyzing')
    for key in ['source_samples','queued_samples','written_samples']:
        need(type(end.get(key)) is int and end[key]==count,'exact CSV row accounting: '+key)
    need(meta_int(end.get('first_queued_completed_tick'))==first and meta_int(end.get('last_queued_completed_tick'))==last,'CSV/end tick range differs')
    if role=='actor':
        need(meta_int(end.get('written_cpu_available_samples'))==available_cpu and meta_int(end.get('written_cpu_unavailable_samples'))==count-available_cpu,'CPU availability accounting')
        need(end.get('actor_cpu_coverage_complete') is (available_cpu==count),'CPU coverage claim differs')
    return dict(root=str(root),identity=identity,sha256=expected_sha,count=count,first=first,last=last,data=data,budgets=budgets,phases=phases,health=health,revision_unavailable=revision_unavailable)

def quantile(values: list[int],percent: int)->int:
    need(bool(values),'empty metric window')
    ordered=sorted(values)
    return ordered[(percent*len(ordered)+99)//100-1]

def stats(values: list[int])->dict[str,Any]:
    if not values:
        return {'available_samples':0,'p95_ns':None,'p99_ns':None,'max_ns':None}
    return {'available_samples':len(values),'p95_ns':quantile(values,95),'p99_ns':quantile(values,99),'max_ns':max(values)}

def load_plan(path: Path)->dict[str,Any]:
    need(path.is_file() and not path.is_symlink() and path.stat().st_size<=65536,'bounded unlinked native cohort plan required')
    stamp=path.stat();before_sha=raw_sha(path);p=metadata(path)
    need(path.stat().st_size==stamp.st_size and path.stat().st_mtime_ns==stamp.st_mtime_ns and raw_sha(path)==before_sha,'plan changed during analysis')
    need(set(p)=={'schema_version','run_id','branch','mode','actual_players_target','human_operators','controller_id','actors','survey_witnesses','pins','automatic_selection'},'closed native cohort plan fields required')
    need(meta_int(p['schema_version'])==1 and p['branch'] in {'BASELINE','DIRECTOR'} and p['mode']=='FULL_MC24','native cohort plan schema/branch/mode')
    need(meta_int(p['actual_players_target'])==24 and meta_int(p['human_operators'])==0,'24 explicit real-client goals and zero-human plan required')
    bounded=lambda value:isinstance(value,str) and re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,63}',value) is not None
    need(bounded(p['run_id']) and bounded(p['controller_id']),'bounded plan/controller identity')
    need(type(p['automatic_selection']) is bool and (p['branch']!='BASELINE' or not p['automatic_selection']),'baseline automatic selection forbidden')
    need(isinstance(p['pins'],dict) and set(p['pins'])==PIN_FIELDS,'exact native plan pins required')
    for value in p['pins'].values():
        need(isinstance(value,str) and re.fullmatch(r'[0-9a-f]{64}',value) is not None and value!='0'*64,'nonzero plan SHA256')
    need(isinstance(p['actors'],list) and len(p['actors'])==24,'24 planned real profiles required')
    ids=set();names=set()
    for actor in p['actors']:
        need(isinstance(actor,dict) and set(actor)=={'player_id','profile_name','controller_id'},'closed native actor declaration required')
        need(isinstance(actor['player_id'],str) and str(uuid.UUID(actor['player_id']))==actor['player_id'],'canonical planned UUID')
        need(isinstance(actor['profile_name'],str) and re.fullmatch(r'[A-Za-z0-9_]{1,16}',actor['profile_name']) is not None,'planned Minecraft profile name')
        need(actor['player_id'] not in ids and actor['profile_name'].lower() not in names,'duplicate planned UUID/name')
        need(actor['controller_id']==p['controller_id'],'one pinned cohort controller required')
        ids.add(actor['player_id']);names.add(actor['profile_name'].lower())
    need(isinstance(p['survey_witnesses'],list) and len(p['survey_witnesses'])==2 and len(set(p['survey_witnesses']))==2 and set(p['survey_witnesses'])<=ids,'two planned survey witnesses from cohort required')
    return {'value':p,'sha256':before_sha,'uuid_sha256':hashlib.sha256(('\n'.join(sorted(ids))+'\n').encode()).hexdigest()}


def bind_plan(capture: dict[str,Any],plan: dict[str,Any])->None:
    i=capture['identity'];p=plan['value']
    need(i['plan_sha256']==plan['sha256'] and i['allowed_uuid_sha256']==plan['uuid_sha256'],'capture differs from exact raw plan or planned UUID set')
    need(i['branch']==p['branch'] and i['cohort_mode']==p['mode'] and i['controller_id']==p['controller_id'],'capture branch/controller differs from plan')
    for target,pin in [('candidate_sha256','candidate_sha256'),('profile_sha256','profile_sha256'),('action_plan_sha256','action_plan_sha256'),('qualified_sources_sha256','sources_sha256'),('director_jar_sha256','director_jar_sha256')]:
        need(i[target]==p['pins'][pin],'capture pin differs from plan: '+pin)
    need(all(0<=count<=p['actual_players_target'] for count in capture['data']['players']),'observed real count exceeds planned UUID whitelist')


def analyze(vanilla_root: Path,actor_root: Path|None,plan_path: Path,minimum_seconds: Decimal)->dict[str,Any]:
    need(minimum_seconds.is_finite() and 0<minimum_seconds<=32400,'positive bounded minimum seconds required')
    plan=load_plan(plan_path);v=load(vanilla_root,'vanilla');bind_plan(v,plan)
    branch=plan['value']['branch'];a=None
    if branch=='BASELINE':
        need(actor_root is None,'BASELINE requires no actor capture; absent runtime is not zero actor CPU')
        first,last,count=v['first'],v['last'],v['count'];vi=0;ai=0
    else:
        need(actor_root is not None,'DIRECTOR requires actor capture')
        a=load(actor_root,'actor');bind_plan(a,plan)
        need(v['identity']==a['identity'],'streams belong to different process/world/session/pins/plan')
        need(abs(v['first']-a['first'])<=1 and abs(v['last']-a['last'])<=1,'CPU and vanilla bounds differ beyond one native tick')
        first=max(v['first'],a['first']);last=min(v['last'],a['last']);need(last>first,'shared native window too short')
        count=last-first+1;vi=first-v['first'];ai=first-a['first']
    durations=list(v['data']['duration'][vi:vi+count]);counts=list(v['data']['players'][vi:vi+count])
    elapsed=v['data']['wall'][vi+count-1]-v['data']['wall'][vi];need(elapsed>0,'shared monotone elapsed window')
    duration_met=Decimal(elapsed)>=minimum_seconds*Decimal(1000000000)
    cohort_met=all(n==plan['value']['actual_players_target'] for n in counts)
    with localcontext() as context:
        context.prec=34
        rate=str((Decimal(count-1)*Decimal(1000000000)/Decimal(elapsed)).quantize(Decimal('0.000001')))
    result={'schema':2,'scope':'TEST_ONLY_COHORT_EXISTING_OBSERVATIONS_NO_NEW_MEASUREMENT','branch':branch,'identity':v['identity'],'vanilla_csv_sha256':v['sha256'],'exact_plan_sha256':plan['sha256'],
        'join':{'first_native_tick':first,'last_native_tick':last,'shared_rows':count,'vanilla_boundary_rows_excluded':v['count']-count,'boundary_tolerance_ticks':0 if a is None else 1,'internal_missing_ticks':0,'shared_observation_elapsed_ns':elapsed,'minimum_seconds_required':str(minimum_seconds),'minimum_window_met':duration_met},
        'observed_completed_tick_rate_per_second_decimal':rate,'tick_rate_scope':'NATIVE_TICK_DELTA_OVER_POST_MONOTONE_ELAPSED_NOT_1_OVER_AVERAGE_MSPT',
        'vanilla_duration':stats(durations),'observed_player_list_counts':{'minimum':min(counts),'maximum':max(counts),'target':24,'all_joined_vanilla_observations_at_target':cohort_met,'zero_player_observations':sum(n==0 for n in counts)},
        'cohort_count_scope':'ACTUAL_WHITELISTED_PLAYER_LIST_AT_CURRENT_POST_NOT_PREVIOUS_TICK_MEMBERSHIP_OR_ACTIVITY',
        'plan_pins_scope':'EXACT_CAPTURE_TO_RAW_PLAN_IDENTITY_MATCH_NOT_NEW_CANDIDATE_FILE_OR_PROFILE_EXECUTION_VERIFICATION',
        'runtime_scope':v['identity']['runtime_scope'],'operator_approval_obtained':False,'fullstack_accepted':False,
        'not_claimed':['capabilities','WARN/ACK receipts','human perception','activity representativeness','worker CPU','critical CPU','whole-server CPU','heap/GC/network metrics','IMPACT/cleanup/ownership outcome','canonical reception','FULLSTACK_ACCEPTED']}
    cpu_complete=True
    if a is None:
        result.update(actor_capture='NOT_APPLICABLE_RUNTIME_ABSENT',director_actor_cpu_available_subset=None,director_actor_wall=None,actor_cpu_coverage_complete=None)
    else:
        actor_wall=list(a['data']['actor_wall'][ai:ai+count]);actor_cpu=list(a['data']['actor_cpu'][ai:ai+count]);critical=list(a['data']['critical_wall'][ai:ai+count]);acounts=list(a['data']['players'][ai:ai+count]);cpu=[n for n in actor_cpu if n>=0]
        cpu_complete=len(cpu)==count;cohort_met=cohort_met and all(n==24 for n in acounts)
        result.update(actor_csv_sha256=a['sha256'],director_actor_wall=stats(actor_wall),director_actor_cpu_available_subset=stats(cpu),director_critical_wall=stats(critical),actor_cpu_coverage_complete=cpu_complete,actor_cpu_unavailable_shared_rows=count-len(cpu),global_budget_ns=a['budgets'][0],critical_budget_ns=a['budgets'][1],p95_goal_ns=a['budgets'][3],actor_frames_over_global_wall_budget=sum(n>a['budgets'][0] for n in actor_wall),actor_frames_over_critical_wall_budget=sum(n>a['budgets'][1] for n in critical),actor_wall_p95_goal_met=quantile(actor_wall,95)<=a['budgets'][3],actor_phase_at_observation_counts=a['phases'],health_at_observation_counts=a['health'])
        result['join']['actor_boundary_rows_excluded']=a['count']-count
        result['observed_actor_player_list_counts']={'minimum':min(acounts),'maximum':max(acounts),'all_joined_actor_observations_at_target':all(n==24 for n in acounts)}
    result['target_cohort_window_observed']=cohort_met
    result['technical_measurement_window_complete']=duration_met and cpu_complete and cohort_met
    result['status']='OBSERVED_SCOPE_WINDOW_COMPLETE_NOT_ACCEPTANCE' if result['technical_measurement_window_complete'] else 'MEASUREMENT_PARTIAL_SCOPE_ONLY'
    return result


def main()->int:
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('vanilla_directory',type=Path);parser.add_argument('--actor-directory',type=Path)
    parser.add_argument('--plan',required=True,type=Path);parser.add_argument('--minimum-seconds',required=True)
    args=parser.parse_args()
    try: result=analyze(args.vanilla_directory,args.actor_directory,args.plan,Decimal(args.minimum_seconds))
    except (ValueError,OSError,UnicodeError,csv.Error,InvalidOperation,RecursionError,TypeError,KeyError) as error:
        print(json.dumps({'schema':2,'status':'REFUSED_CAPTURE_INTEGRITY_OR_SCOPE','reason':str(error),'fullstack_accepted':False},indent=2));return 2
    print(json.dumps(result,indent=2));return 0 if result['technical_measurement_window_complete'] else 2

if __name__=='__main__': raise SystemExit(main())
