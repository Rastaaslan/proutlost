from pathlib import Path
import subprocess,json,hashlib,zipfile,datetime,sys,shutil
sys.stdout.reconfigure(encoding='utf-8')
root=Path(sys.argv[1]).resolve();src=root/'qa/R54';work=root/'deliveries/SEASON_R56_CAMPAIGN';stage=work/'SHIPPED_SERVER';out=root/'deliveries/B09R56-CAMPAGNE-CONTRA-20261123';out.mkdir(exist_ok=True)
def sha(p):
 h=hashlib.sha256()
 with Path(p).open('rb')as f:
  for b in iter(lambda:f.read(4*1024*1024),b''):h.update(b)
 return h.hexdigest()
def rawsha(b):return hashlib.sha256(b).hexdigest()
def write(p,o):p.write_text(json.dumps(o,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
head=subprocess.check_output(['git','-C',str(src),'rev-parse','HEAD'],text=True).strip()
assert not subprocess.check_output(['git','-C',str(src),'status','--porcelain'],text=True).strip()
smoke=json.loads((work/'R56_SHIPPED_SET_SMOKE.json').read_text());check=json.loads((work/'R56_SHORT_CAMPAIGN_CHECK.json').read_text())
assert smoke['normal_exit_code']==0 and smoke['production_running'] and smoke['physical_grant_count']==1 and smoke['qa_addon_count']==0 and smoke['campaign_sites']==17
assert check['success']and check['synthetic_quest_facts']==0 and not check['calibration_overridden']
assert len(list((stage/'mods').glob('*.jar')))==52
assert not any('TEMPORARY'in p.name or 'campaign-shortcheck'in p.name for p in(stage/'mods').glob('*.jar'))
pins=json.loads((work/'R56_UNAFFECTED_PINS.json').read_text());mainsha=sha(stage/'mods/proutlost-0.1.0.jar');sdksha=sha(stage/'mods/proutlost-director-1.0.0.jar')
assert mainsha==pins['main_sha256'] and sdksha==pins['SDK_reused_sha256']
pin=sha(stage/'world/proutlost/director-profile/manifest.json');assert (stage/'DIRECTOR_MANIFEST_SHA256.txt').read_text().strip()==pin
receipt=json.loads((stage/'world/import_receipt_r56.json').read_text());assert receipt['status']=='IMPORTED_REAL_ISOLATED_RUNTIME'and receipt['catalogueSha256']==sha(stage/'world/director-physical-catalogs/contra_saison2/catalog.json')
physical=json.loads((stage/'world/proutlost/campaign/manifest.json').read_text());assert len(physical['sites'])==17 and len(physical['supports'])==48
assert not any('r56_alice'in p.name or'r56_bob'in p.name for p in(stage/'world/playerdata').glob('*'))
base=root/'deliveries/B09R55-CONTRA-DIRECTOR-20261122'
with zipfile.ZipFile(base/'PROUTLOST_S2_R55_CLIENT.zip')as z:manifest=json.loads(z.read('CANDIDATE_MANIFEST.json'))
source=out/'PROUTLOST_S2_R56_SOURCES.zip';subprocess.run(['git','-C',str(src),'archive','--format=zip','--output',str(source),'HEAD'],check=True,timeout=60)
world=out/'PROUTLOST_S2_R56_MONDE_CONTRA.zip';worldpins={}
with zipfile.ZipFile(world,'w',zipfile.ZIP_DEFLATED,compresslevel=3)as z:
 for p in sorted((stage/'world').rglob('*')):
  if not p.is_file()or p.name=='session.lock':continue
  n=p.relative_to(stage).as_posix();z.write(p,n);worldpins[n]=sha(p)
 z.writestr('WORLD_CONTENTS_SHA256.json',json.dumps({'candidate':'R56','director_world_id':'74eb8e42-62b0-4375-9370-a2fee2fc89a0','files_sha256':worldpins},indent=2))
worldsha=sha(world);print('WORLD_PACK_READY',world.stat().st_size,worldsha,flush=True)
# Only the isolated verification copy contains these fixture UUIDs; shipped data never did.
import uuid
for name in ['r56_alice','r56_bob']:
 import hashlib as _h
 d=bytearray(_h.md5(('r56-short:'+name).encode()).digest());d[6]=(d[6]&15)|48;d[8]=(d[8]&63)|128
 assert not(stage/'world/playerdata'/f'{uuid.UUID(bytes=bytes(d))}.dat').exists()
manifest.update(label='R56_CONTRA_CAMPAIGN_IMPLANTED',created_utc=datetime.datetime.now(datetime.timezone.utc).isoformat(),source_git_head=head,source_archive=source.name,source_sha256=sha(source),runtime_source_git_head=head,production_main_sha256=mainsha,production_director_sha256=sdksha,director_manifest_sha256=pin,world_archive=world.name,world_archive_sha256=worldsha,qualified_scope='R54_R55_UNAFFECTED_RESULTS_REUSED;R56_ACTUAL_PHYSICAL_SUPPORTS;REAL_G0_D0_IMPORT;SHIPPED_SET_START_NORMAL_CLOSE;SHORT_P0_P1_P2_NATIVE_PROGRESSION',qualification_status='OPERATIONAL_CAMPAIGN_IMPLANTATION_SHORT_CHECK_PASSED_FULL_WALKTHROUGH_NOT_RUN',season_operational_components=['Contra recovered world','Approved Director production bundle','28 authentic G0/D0 locations','17 campaign bindings and 48 physical supports','Existing quest campaign and modules'],missing_operational_inputs=[],native_component_qualified=True,native_current_qualified=False,native_qualified=False,human_qualified=False,fullstack_accepted=False,delivery_source_only_changed=False)
manifest.pop('main_runtime_reused_from',None)
for key in ['mods','server_mods']:manifest[key]['proutlost-0.1.0.jar']=mainsha;manifest[key]['proutlost-director-1.0.0.jar']=sdksha
qual={'candidate':manifest['label'],'source_head':head,'baseline_preserved':'R55','Contra_Director_operational':True,'campaign_physical_implementation_available':True,'full_campaign_walkthrough_completed':False,'FULLSTACK_ACCEPTED':False,'matrix_denominator':179,'actual_prepared_sites':17,'actual_supports':48,'D0_entries':28,'legacy_large_emprises':'Functional access/anchor windows only; full physical volumes preserved','remaining_limits':['Existing ORIGINAL_TEXT_PLACEHOLDERS retained','Human readability/audio/render acceptance pending','Full campaign branches not newly qualified'],'not_run':['full suite','20-25-player load','8h soak','abrupt JVM campaign','human audio/render/G5','full campaign walkthrough'],'targeted_checks':'PREUVES_COURTES','qa_addons_shipped':0}
write(out/'CANDIDATE_MANIFEST.json',manifest);write(out/'ETAT_LIVRAISON.json',qual)
rawmanifest=(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n').encode();rawqual=(json.dumps(qual,ensure_ascii=False,indent=2)+'\n').encode();doc=(src/'docs/OPERATIONAL_DELIVERY_R56.md').read_bytes();(out/'INSTALLATION_R56.md').write_bytes(doc)
implementation=(src/'season/contra-initial-r56/CAMPAGNE_IMPLANTATION_REALISEE.json').read_bytes();(out/'CAMPAGNE_IMPLANTATION_REALISEE.json').write_bytes(implementation)
results={}
for kind in ['CLIENT','SERVEUR']:
 target=out/f'PROUTLOST_S2_R56_{kind}.zip';packpins={}
 with zipfile.ZipFile(target,'w',zipfile.ZIP_DEFLATED,compresslevel=3)as z:
  if kind=='CLIENT':
   with zipfile.ZipFile(base/'PROUTLOST_S2_R55_CLIENT.zip')as old:
    for item in old.infolist():
     if item.filename in ['CANDIDATE_MANIFEST.json','QUALIFICATION.json','INSTALLATION.md','PACK_CONTENTS_SHA256.json','CAMPAGNE_IMPLANTATION_MANQUANTE.json']:continue
     b=(stage/'mods/proutlost-0.1.0.jar').read_bytes()if item.filename=='mods/proutlost-0.1.0.jar'else old.read(item.filename);z.writestr(item,b);packpins[item.filename]=rawsha(b)
  else:
   for name in ['mods','libraries','config','defaultconfigs','tools']:
    for p in sorted((stage/name).rglob('*')):
     if p.is_file():n=p.relative_to(stage).as_posix();z.write(p,n);packpins[n]=sha(p)
   for n in ['run.bat','Lancer-Saison.cmd','run.sh','user_jvm_args.txt','eula.txt','server.properties','SEASON_INSTALLATION.json','DIRECTOR_MANIFEST_SHA256.txt']:
    z.write(stage/n,n);packpins[n]=sha(stage/n)
   z.writestr('MONDE_CONTRA_SHA256.txt',worldsha+'\n');packpins['MONDE_CONTRA_SHA256.txt']=rawsha((worldsha+'\n').encode())
  for n,b in [('CANDIDATE_MANIFEST.json',rawmanifest),('QUALIFICATION.json',rawqual),('INSTALLATION.md',doc),('CAMPAGNE_IMPLANTATION_REALISEE.json',implementation)]:z.writestr(n,b);packpins[n]=rawsha(b)
  z.writestr('PACK_CONTENTS_SHA256.json',json.dumps({'candidate':manifest['label'],'files_sha256':packpins},indent=2))
 with zipfile.ZipFile(target)as z:
  expected=manifest['mods'if kind=='CLIENT'else'server_mods'];assert all(rawsha(z.read('mods/'+n))==v for n,v in expected.items());assert len([n for n in z.namelist()if n.startswith('mods/')and n.endswith('.jar')])==len(expected)
 results[kind]={'file':target.name,'bytes':target.stat().st_size,'sha256':sha(target)};print('MATCHED_PACK_READY',kind,json.dumps(results[kind]),flush=True)
results['MONDE']={'file':world.name,'bytes':world.stat().st_size,'sha256':worldsha};results['SOURCES']={'file':source.name,'bytes':source.stat().st_size,'sha256':sha(source)}
write(out/'ASSEMBLY_R56.json',{'source_head':head,'packs':results,'FULLSTACK_ACCEPTED':False,'campaign_physical_available':True,'actual_import_receipt':receipt,'shipped_set_smoke':smoke,'short_check':check})
proof=out/'PREUVES_COURTES';proof.mkdir(exist_ok=True)
for n in ['R56_UNAFFECTED_PINS.json','R56_SHIPPED_SET_SMOKE.json','R56_SHORT_CAMPAIGN_CHECK.json','R56_AUTHORING_NATIVE.log','R56_SHIPPED_SET_NATIVE.log','R56_SHORT_CHECK_NATIVE.log']:
 shutil.copy2(work/n,proof/n)
for n in ['R54_WARM_TERRAIN_REFUSAL_PAUSE.json','R54_COLD_APPLY28_INVERSE.json','R54_COLD_NORMAL_CLOSE.json']:shutil.copy2(root/'deliveries/B09R54-DELIVERY-20261122/PREUVES_COURTES'/n,proof/n)
shutil.copy2(stage/'world/import_receipt_r56.json',proof/'R56_ACTUAL_D0_RECEIPT.json')
docs=out/'PROUTLOST_S2_R56_INSTALLATION_ET_ETAT.zip'
with zipfile.ZipFile(docs,'w',zipfile.ZIP_DEFLATED)as z:
 for p in out.iterdir():
  if p.is_file()and p.suffix in ['.md','.json']:z.write(p,p.name)
 for p in proof.iterdir():z.write(p,'PREUVES_COURTES/'+p.name)
assets=[out/v['file']for v in results.values()]+[docs,out/'INSTALLATION_R56.md'];(out/'SHA256SUMS_R56.txt').write_text(''.join(sha(p)+'  '+p.name+'\n'for p in assets),encoding='utf-8')
assert all(p.stat().st_size<512_000_000 for p in assets)
print('R56_OPERATIONAL_DELIVERY_PACKS_READY',json.dumps(results),flush=True)
