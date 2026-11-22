
from pathlib import Path
import subprocess,json,hashlib,zipfile,datetime,shutil,sys
sys.stdout.reconfigure(encoding="utf-8")
root=Path(sys.argv[1]).resolve()
src=root/"qa/R54"
inputs=root/"checkpoints/P008-20261118T062924Z-root/P008-RELAY-20261120-7e3766414017/native-inputs"
out=root/"deliveries/B09R54-DELIVERY-20261122"
out.mkdir(exist_ok=False)
h=lambda b:hashlib.sha256(b).hexdigest()
hf=lambda p:h(Path(p).read_bytes())
old=root/"deliveries/B09R54-A01-unqualified-20261122"
previous=root/"deliveries/B09R54-candidate-INCOMPLETE-20261122"
m=json.loads((previous/"P008_B09R54_MATCHED_CANDIDATE_MANIFEST.json").read_text(encoding="utf-8"))
head=subprocess.check_output(["git","-C",str(src),"rev-parse","HEAD"],text=True).strip()
runtime_head="9c59ec01befb681753c7eff8276fa62ffd71e85a"
assert not subprocess.check_output(["git","-C",str(src),"status","--porcelain"],text=True).strip()
runtime_delta=subprocess.check_output(["git","-C",str(src),"diff","--name-only",runtime_head,"HEAD","--","src","build.gradle","settings.gradle","gradle.properties","gradle"],text=True)
assert not runtime_delta.strip(),"Runtime source changed: compile required"
source=out/"PROUTLOST_S2_R54_SOURCES.zip"
subprocess.run(["git","-C",str(src),"archive","--format=zip","--output",str(source),"HEAD"],check=True,timeout=45)
source_files=len(subprocess.check_output(["git","-C",str(src),"ls-files"],text=True).splitlines())
proof=out/"PREUVES_COURTES"
proof.mkdir()
container="proutlost-p008-production-qa-20261120"
proofs=[
("/workspace/P008-WP-R54-BOUND-TERRAIN-WARM-A01/evidence/native-bound-terrain-WARM-a02-resume/RESULT.json","R54_WARM_TERRAIN_REFUSAL_PAUSE.json"),
("/workspace/P008-WP-R54-BOUND-TERRAIN-COLD-A01/evidence/native-bound-terrain-COLD-delivery-a01/RESULT.json","R54_COLD_APPLY28_INVERSE.json"),
("/workspace/P008-WP-R54-BOUND-TERRAIN-COLD-A01/supervision/CLOSED.json","R54_COLD_NORMAL_CLOSE.json")]
for p,n in proofs:subprocess.run(["docker","cp",container+":"+p,str(proof/n)],check=True,capture_output=True,timeout=30)
cold=json.loads((proof/"R54_COLD_APPLY28_INVERSE.json").read_text())
close=json.loads((proof/"R54_COLD_NORMAL_CLOSE.json").read_text())
assert cold["status"]=="TRUE_COLD_FORMAT4_SAME_PLAN_FRESH_CENSUS_ACTUAL28_AND_EXACT_INVERSE_PASS" and close["normal_exit0_pidfd_ended"] and close["failure"] is None
for n in ["R54_MATCHED_TARGETED_BUILD_RESULT_A01.json","R54_MATCHED_TARGETED_BUILD_RESULT_A02.json"]:
 shutil.copy2(inputs/n,proof/n)
m.update(created_utc=datetime.datetime.now(datetime.timezone.utc).isoformat(),label="B09R54_DELIVERY_MATCHED_BINARIES_AUTHORED_SEASON_INPUTS_REQUIRED",
 source_archive=source.name,source_files=source_files,source_sha256=hf(source),source_git_head=head,
 runtime_source_git_head=runtime_head,delivery_source_only_changed=True,qualification_status="BLOCKED_AUTHORED_SEASON_INPUTS",
 qualified_scope="R54_NATIVE_WARM_STALE_REFUSAL_SOFTPAUSE_AND_TRUE_COLD_APPLY28_EXACT_INVERSE_WITH_NORMAL_CLOSE; FULL_SEASON_NOT_INSTALLED",
 native_component_qualified=True,native_current_qualified=False,native_qualified=False,human_qualified=False,fullstack_accepted=False,
 missing_operational_inputs=["author-chosen complete Contra world","authentic catalog.json + adapters.json and versioned authored physical needs","approved Director operator bundle"])
m["scoped_evidence"]={n:hf(proof/n)for _,n in proofs}
m["runtime_compile_reused"]={"runtime_source_git_head":runtime_head,"runtime_source_delta":[],"build_receipt_sha256":hf(inputs/"R54_MATCHED_TARGETED_BUILD_RESULT_A02.json"),"no_recompile_needed_for_launch_scripts_and_docs":True}
manifest=(json.dumps(m,ensure_ascii=False,indent=2,sort_keys=True)+"\n").encode()
(out/"CANDIDATE_MANIFEST.json").write_bytes(manifest)
doc=(src/"docs/OPERATIONAL_DELIVERY_R54.md").read_bytes()
(out/"INSTALLATION.md").write_bytes(doc)
matrix=json.loads((root/"PROUTLOST_S2_CDC_MATRIX.json").read_text(encoding="utf-8-sig"))
snapshot={"schema_version":1,"source_matrix_sha256":hf(root/"PROUTLOST_S2_CDC_MATRIX.json"),"canonical_denominator":179,
"counts":matrix["current_qualification_counts"],"requirements":[{"id":r["id"],"status":r["status"]}for r in matrix["requirements"]],
"scope":"Preserved reception statuses; no automatic downgrade to missing-development and no whole-requirement acceptance promotion.","FULLSTACK_ACCEPTED":False}
assert len(snapshot["requirements"])==179
(out/"MATRICE_179_STATUTS.json").write_text(json.dumps(snapshot,indent=2),encoding="utf-8")
qual={"candidate":m["label"],"status":"BLOCKED_AUTHORED_SEASON_INPUTS","source_git_head":head,"runtime_source_git_head":runtime_head,
"native_terrain_fix_scoped":cold["status"],"normal_close":True,"counts":snapshot["counts"],
"not_run":["whole suite","20-25 player representative load","8h soak","new abrupt JVM campaign","human audio/render/G5","final cold cycle after R54 inverse"],
"season_operational":False,"FULLSTACK_ACCEPTED":False}
qual_raw=(json.dumps(qual,indent=2)+"\n").encode()
(out/"ETAT_LIVRAISON.json").write_bytes(qual_raw)
review=(src/"src/director/resources/data/proutlost_director/director/profiles/default_for_testing.json").read_bytes()
quest=(src/"src/main/java/fr/proutlost/quest/runtime/QuestConfig.java").read_text(encoding="utf-8")
quest_toml=b"""enabled=true\ntestCommands=false\nminimumContributors=2\ncirculationSeconds=300\nactivePopulationRatio=0.6\ndiscussionSeconds=300\nballotSeconds=180\namplificationPerturbationProbability=0.5\nvoteParticipation=0.6\ncampActivitySeconds=60\ncampVisits=2\nsignalFireCount=3\nsceneIntervalSeconds=10\ntransmissionIntervalSeconds=10\nstabilizationSeconds=10\nworkContributors=2\nwitnessAbsenceSeconds=86400\n"""
wp_toml=b"""[worldPrep]\nenabled=false\nphysicalPasses=false\nstructuresOnly=false\nparallelDiscovery=false\nparallelEcologyPlanning=false\nparallelEcologyWorkers=4\nmultiTilePrefetch=false\nmultiTileWorkers=4\nloadExistingChunks=false\nmode="TEST"\ncellsPerTick=256\npauseAboveTickMillis=100\n"""
notice="""# Decisions auteur encore necessaires

Les fichiers techniques d'installation et de chargement sont prets. Aucun JSON n'est a ecrire manuellement.

1. Carte de reference : retenir le candidat Contra du 5-6 novembre sur dam-server, ou indiquer l'autre dossier final deja valide. Le candidat retrouve est ferme et conserve, mais aucun statut de carte finale n'est invente.
2. Profil et besoins physiques : identifier les decisions deja validees concernant la calibration et l'association des evenements prevus aux supports physiques Contra. A defaut, il faut une decision auteur sur une proposition reviewable avant d'emettre l'approbation. Les valeurs de depart sont fournies telles quelles dans profil-a-valider/default_for_testing.json ; elles restent non approuvees.

Origine physique connue : /home/damien/contra-s2-candidate-cow-20261105 (monde COW lower+upper, pas upper seul). L'export G0 v3 et ses qualifications conservent leur provenance. Les vrais catalog.json/adapters.json de production ne sont pas recuperes ; ils doivent etre produits depuis les vraies entrees, pas depuis des tags G0 convertis en capacites.

Ces decisions ne sont pas remplacees par une campagne de tests. Receptions humaines et charge restent des qualifications distinctes.
"""
(out/"DECISIONS_AUTEUR.md").write_text(notice,encoding="utf-8")
replace={"CANDIDATE_MANIFEST.json":manifest,"QUALIFICATION.json":qual_raw,"INSTALLATION.md":doc}
main=(inputs/"R54_A02_OFFICIAL_MAIN.jar").read_bytes()
sdk=(inputs/"R54_A02_OFFICIAL_SDK.jar").read_bytes()
assert h(main)==m["production_main_sha256"] and h(sdk)==m["production_director_sha256"]
replace.update({"mods/proutlost-0.1.0.jar":main,"mods/proutlost-director-1.0.0.jar":sdk})
packs={}
for kind in ["CLIENT","SERVEUR"]:
 prior=old/("PROUTLOST_S2_B09R54_"+kind+"_CANDIDAT_INCOMPLET.zip")
 dst=out/("PROUTLOST_S2_R54_"+kind+"_CANDIDAT.zip")
 extras={}
 if kind=="SERVEUR":
  rootcmd=b'@echo off\r\ncd /d "%~dp0"\r\npowershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\\Lancer-Saison.ps1" %*\r\nif errorlevel 1 pause\r\n'
  extras={"tools/Installer-Saison.ps1":(src/"scripts/delivery/Installer-Saison.ps1").read_bytes(),
   "tools/Lancer-Saison.ps1":(src/"scripts/delivery/Lancer-Saison.ps1").read_bytes(),
   "run.bat":rootcmd,"Lancer-Saison.cmd":rootcmd,"run.sh":(src/"scripts/delivery/run-season.sh").read_bytes(),
   "season-templates/proutlost-quest-server.toml":quest_toml,"season-templates/proutlost-server.toml":wp_toml,
   "profil-a-valider/default_for_testing.json":review,"DECISIONS_AUTEUR.md":notice.encode(),
   "server.properties":b"server-ip=127.0.0.1\nserver-port=25579\nonline-mode=true\nenable-rcon=false\nlevel-name=world\nmax-players=25\n"}
 desired={**replace,**extras};fh={}
 with zipfile.ZipFile(prior)as iz,zipfile.ZipFile(dst,"x",compression=zipfile.ZIP_DEFLATED,compresslevel=3)as oz:
  ix=json.loads(iz.read("PACK_CONTENTS_SHA256.json"))
  for info in iz.infolist():
   n=info.filename
   if n=="PACK_CONTENTS_SHA256.json":continue
   raw=iz.read(n)
   if n in ix["files_sha256"]:assert h(raw)==ix["files_sha256"][n]
   raw=desired.pop(n,raw);oz.writestr(info,raw);fh[n]=h(raw)
  for n,raw in desired.items():
   info=zipfile.ZipInfo(n)
   info.compress_type=zipfile.ZIP_DEFLATED
   if n.endswith(".sh"):info.external_attr=0o100755<<16
   oz.writestr(info,raw);fh[n]=h(raw)
  ix.update(candidate=m["label"],files_sha256=fh,candidate_manifest_sha256=h(manifest))
  oz.writestr("PACK_CONTENTS_SHA256.json",json.dumps(ix,indent=2))
 with zipfile.ZipFile(dst)as z:
  assert z.testzip()is None
  pins=json.loads(z.read("PACK_CONTENTS_SHA256.json"))["files_sha256"]
  assert all(h(z.read(n))==pin for n,pin in pins.items())
  modpins=m["mods"] if kind=="CLIENT"else m["server_mods"]
  assert all(h(z.read("mods/"+n))==pin for n,pin in modpins.items())
  assert len([n for n in z.namelist()if n.startswith("mods/")and n.endswith(".jar")])==(53 if kind=="CLIENT"else 52)
  assert not any("TEST_ONLY"in n or"p008-"in n for n in z.namelist()if n.startswith("mods/"))
 packs[kind]={"file":dst.name,"bytes":dst.stat().st_size,"sha256":hf(dst),"integrity_verified":True}
 print("MATCHED_PACK_READY",kind,json.dumps(packs[kind]),flush=True)
report={"utc":datetime.datetime.now(datetime.timezone.utc).isoformat(),"source_head":head,"runtime_source_head":runtime_head,
"source_zip_sha256":hf(source),"source_files":source_files,"manifest_sha256":h(manifest),"packs":packs,
"runtime_unchanged_since_reused_build":True,"zero_QA_addons":True,"season_operational":False,"blockers":m["missing_operational_inputs"],"FULLSTACK_ACCEPTED":False}
(out/"ASSEMBLY_VERIFIED.json").write_text(json.dumps(report,indent=2),encoding="utf-8")
docs=out/"PROUTLOST_S2_R54_INSTALLATION_ET_ETAT.zip"
with zipfile.ZipFile(docs,"x",compression=zipfile.ZIP_DEFLATED,compresslevel=3)as z:
 for p in out.iterdir():
  if p.is_file()and p.suffix in [".md",".json"]:z.write(p,p.name)
 for p in proof.iterdir():z.write(p,"PREUVES_COURTES/"+p.name)
delivery_files=[out/("PROUTLOST_S2_R54_"+k+"_CANDIDAT.zip")for k in ["CLIENT","SERVEUR"]]+[source,docs,out/"INSTALLATION.md"]
(out/"SHA256SUMS.txt").write_text("".join(hf(p)+"  "+p.name+"\n"for p in delivery_files),encoding="utf-8")
print("DELIVERY_CONCRETE_READY_AUTHOR_INPUTS_REQUIRED",json.dumps(report),flush=True)
