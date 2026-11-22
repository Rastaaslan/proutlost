from pathlib import Path
import subprocess, json, hashlib, zipfile, datetime, sys, shutil

sys.stdout.reconfigure(encoding='utf-8')
root=Path(sys.argv[1]).resolve()
src=root/'qa/R54'
stage=root/'deliveries/SEASON_R55_INTEGRATION/SHIPPED_SERVER'
work=root/'deliveries/SEASON_R55_INTEGRATION'
out=root/'deliveries/B09R55-CONTRA-DIRECTOR-20261122'
out.mkdir(exist_ok=True)
def sha(p):
 h=hashlib.sha256()
 with Path(p).open('rb')as f:
  for b in iter(lambda:f.read(4*1024*1024),b''):h.update(b)
 return h.hexdigest()
def rawsha(b):return hashlib.sha256(b).hexdigest()
def writejson(p,o):p.write_text(json.dumps(o,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
head=subprocess.check_output(['git','-C',str(src),'rev-parse','HEAD'],text=True).strip()
assert not subprocess.check_output(['git','-C',str(src),'status','--porcelain'],text=True).strip()
smoke=json.loads((work/'R55_SHIPPED_SET_SMOKE.json').read_text())
assert smoke['normal_exit_code']==0 and smoke['production_running'] and smoke['physical_grant_count']==1 and smoke['qa_addon_count']==0
assert (stage/'world/level.dat').is_file() and (stage/'world/import_receipt_r55.json').is_file()
baseline=root/'deliveries/B09R54-DELIVERY-20261122'
with zipfile.ZipFile(baseline/'PROUTLOST_S2_R54_CLIENT_CANDIDAT.zip')as z:manifest=json.loads(z.read('CANDIDATE_MANIFEST.json'))
mainsha='bd9507070a92e1734e8a99d39756291818a8d21c8bf00518751f4dd6109b955b'
sdksha='41ac0a7b2f783ce854a085507c6d30d2c2fa6beecb46947d5bfc7c2bf5be66e6'
assert sha(stage/'mods/proutlost-0.1.0.jar')==mainsha
assert sha(stage/'mods/proutlost-director-1.0.0.jar')==sdksha
assert len(list((stage/'mods').glob('*.jar')))==52
assert not any('TEMPORARY'in p.name or'season-source-survey'in p.name for p in(stage/'mods').glob('*.jar'))
pin=sha(stage/'world/proutlost/director-profile/manifest.json')
assert (stage/'DIRECTOR_MANIFEST_SHA256.txt').read_text().strip()==pin
assert json.loads((stage/'SEASON_INSTALLATION.json').read_text())['director_manifest_sha256']==pin
receipt=json.loads((stage/'world/import_receipt_r55.json').read_text())
assert receipt['status']=='IMPORTED_REAL_ISOLATED_RUNTIME'
assert receipt['catalogueSha256']==sha(stage/'world/director-physical-catalogs/contra_saison2/catalog.json')
proof=out/'PREUVES_COURTES';proof.mkdir(exist_ok=True)
for n in ['R54_WARM_TERRAIN_REFUSAL_PAUSE.json','R54_COLD_APPLY28_INVERSE.json','R54_COLD_NORMAL_CLOSE.json']:
 shutil.copy2(baseline/'PREUVES_COURTES'/n,proof/n)
for n in ['R55_BUILD_TARGETED_CHECK.json','R55_SHIPPED_SET_SMOKE.json','R55_IMPORT_NATIVE_CLOSED.json','R55_IMPORT_NATIVE.log']:
 shutil.copy2(work/n,proof/n)
shutil.copy2(stage/'world/import_receipt_r55.json',proof/'R55_ACTUAL_D0_RECEIPT.json')
shutil.copy2(src/'season/contra-initial-r55/CAMPAGNE_IMPLANTATION_MANQUANTE.json',out/'CAMPAGNE_IMPLANTATION_MANQUANTE.json')
shutil.copy2(src/'docs/OPERATIONAL_DELIVERY_R55.md',out/'INSTALLATION_R55.md')
source=out/'PROUTLOST_S2_R55_SOURCES.zip'
subprocess.run(['git','-C',str(src),'archive','--format=zip','--output',str(source),'HEAD'],check=True,timeout=60)
# World data is separate so all downloads remain below512MiB.
world=out/'PROUTLOST_S2_R55_MONDE_CONTRA.zip';worldpins={}
with zipfile.ZipFile(world,'w',zipfile.ZIP_DEFLATED,compresslevel=3)as z:
 for p in sorted((stage/'world').rglob('*')):
  if not p.is_file()or p.name=='session.lock':continue
  n=p.relative_to(stage).as_posix();z.write(p,n);worldpins[n]=sha(p)
 z.writestr('WORLD_CONTENTS_SHA256.json',json.dumps({'candidate':'R55','director_world_id':'74eb8e42-62b0-4375-9370-a2fee2fc89a0','files_sha256':worldpins},indent=2))
worldsha=sha(world)
print('WORLD_PACK_READY',world.stat().st_size,worldsha,flush=True)
manifest.update(label='R55_CONTRA_DIRECTOR_PRODUCTION_CAMPAIGN_SUPPORTS_PENDING',created_utc=datetime.datetime.now(datetime.timezone.utc).isoformat(),
 source_git_head=head,source_archive=source.name,source_sha256=sha(source),
 runtime_source_git_head=head,main_runtime_reused_from='9c59ec01befb681753c7eff8276fa62ffd71e85a',
 production_main_sha256=mainsha,production_director_sha256=sdksha,
 director_manifest_sha256=pin,world_archive=world.name,world_archive_sha256=worldsha,
 qualified_scope='R54_UNAFFECTED_RESULTS_REUSED;R55_OWNED_LANE_TARGETED_CHECK;AUTHENTIC_CONTRA_PROFILE_G0_D0_IMPORT;SHIPPED_SET_START_NORMAL_CLOSE',
 qualification_status='CONTRA_DIRECTOR_USABLE_QUESTSPEC_PHYSICAL_SUPPORTS_MISSING',
 season_operational_components=['Contra recovered world','Director approved production bundle','Authentic durable G0 and D0 imports','R54 WorldPrep and existing modules'],
 missing_operational_inputs=['QUESTSPEC canonical physical campaign supports, coordinates and content association'],
 native_component_qualified=True,native_current_qualified=False,native_qualified=False,human_qualified=False,fullstack_accepted=False,
 delivery_source_only_changed=False)
for key in ['mods','server_mods']:manifest[key]['proutlost-director-1.0.0.jar']=sdksha
qual={'candidate':manifest['label'],'source_head':head,'runtime_main_reused':True,'SDK_fix':'own metadata lane self-wait removed in two admission paths',
 'Contra_Director_operational':True,'complete_QUESTSPEC_campaign_operational':False,'physical_missing':'CAMPAGNE_IMPLANTATION_MANQUANTE.json',
 'FULLSTACK_ACCEPTED':False,'matrix_denominator':179,
 'not_run':['full suite','20-25-player load','8h soak','abrupt JVM campaign','human audio/render/G5','full canonical campaign walkthrough'],
 'targeted_checks':'PREUVES_COURTES','qa_addons_shipped':0}
rawmanifest=(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n').encode()
rawqual=(json.dumps(qual,ensure_ascii=False,indent=2)+'\n').encode()
writejson(out/'CANDIDATE_MANIFEST.json',manifest);writejson(out/'ETAT_LIVRAISON.json',qual)
doc=(src/'docs/OPERATIONAL_DELIVERY_R55.md').read_bytes()
results={}
for kind in ['CLIENT','SERVEUR']:
 target=out/f'PROUTLOST_S2_R55_{kind}.zip';pins={}
 with zipfile.ZipFile(target,'w',zipfile.ZIP_DEFLATED,compresslevel=3)as z:
  if kind=='CLIENT':
   with zipfile.ZipFile(baseline/'PROUTLOST_S2_R54_CLIENT_CANDIDAT.zip')as old:
    for item in old.infolist():
     if item.filename in ['CANDIDATE_MANIFEST.json','QUALIFICATION.json','INSTALLATION.md','PACK_CONTENTS_SHA256.json']:continue
     b=(stage/'mods/proutlost-director-1.0.0.jar').read_bytes()if item.filename=='mods/proutlost-director-1.0.0.jar'else old.read(item.filename)
     z.writestr(item,b);pins[item.filename]=rawsha(b)
  else:
   entries=['mods','libraries','config','defaultconfigs','tools']
   for name in entries:
    folder=stage/name
    if not folder.is_dir():continue
    for p in sorted(folder.rglob('*')):
     if p.is_file():n=p.relative_to(stage).as_posix();z.write(p,n);pins[n]=sha(p)
   for n in ['run.bat','Lancer-Saison.cmd','run.sh','user_jvm_args.txt','eula.txt','server.properties','SEASON_INSTALLATION.json','DIRECTOR_MANIFEST_SHA256.txt']:
    p=stage/n;z.write(p,n);pins[n]=sha(p)
   z.writestr('MONDE_CONTRA_SHA256.txt',worldsha+'\n');pins['MONDE_CONTRA_SHA256.txt']=rawsha((worldsha+'\n').encode())
  for n,b in [('CANDIDATE_MANIFEST.json',rawmanifest),('QUALIFICATION.json',rawqual),('INSTALLATION.md',doc),('CAMPAGNE_IMPLANTATION_MANQUANTE.json',(out/'CAMPAGNE_IMPLANTATION_MANQUANTE.json').read_bytes())]:
   z.writestr(n,b);pins[n]=rawsha(b)
  z.writestr('PACK_CONTENTS_SHA256.json',json.dumps({'candidate':manifest['label'],'files_sha256':pins},indent=2))
 with zipfile.ZipFile(target)as z:
  expected=manifest['mods'if kind=='CLIENT'else'server_mods']
  assert all(rawsha(z.read('mods/'+n))==v for n,v in expected.items())
  assert len([n for n in z.namelist()if n.startswith('mods/')and n.endswith('.jar')])==len(expected)
 results[kind]={'file':target.name,'bytes':target.stat().st_size,'sha256':sha(target)}
 print('MATCHED_PACK_READY',kind,json.dumps(results[kind]),flush=True)
results['MONDE']={'file':world.name,'bytes':world.stat().st_size,'sha256':worldsha}
results['SOURCES']={'file':source.name,'bytes':source.stat().st_size,'sha256':sha(source)}
writejson(out/'ASSEMBLY_R55.json',{'source_head':head,'packs':results,'FULLSTACK_ACCEPTED':False,'complete_campaign_available':False,'actual_import_receipt':receipt,'shipped_set_smoke':smoke})
docs=out/'PROUTLOST_S2_R55_INSTALLATION_ET_ETAT.zip'
with zipfile.ZipFile(docs,'w',zipfile.ZIP_DEFLATED)as z:
 for p in out.iterdir():
  if p.is_file()and p.suffix in ['.md','.json']:z.write(p,p.name)
 for p in proof.iterdir():z.write(p,'PREUVES_COURTES/'+p.name)
assets=[out/v['file']for v in results.values()]+[docs,out/'INSTALLATION_R55.md']
(out/'SHA256SUMS.txt').write_text(''.join(sha(p)+'  '+p.name+'\n'for p in assets),encoding='utf-8')
print('R55_ACTUAL_USABLE_CONTRA_DIRECTOR_PACKS_READY_CAMPAIGN_LIMIT_RECORDED',json.dumps(results),flush=True)
