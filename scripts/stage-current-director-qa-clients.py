"""Stage CURRENT (not yet native qualified) QA client files on FIXE only.

No launcher interaction, account files, network, Minecraft world, server or
public port. The previous native-qualified pack is immutable and read only.
"""
import argparse
from pathlib import Path, PurePosixPath
import hashlib
import json
import os
import shutil
import subprocess
import sys
import zipfile
from datetime import datetime, timezone

ROOT=Path(r"C:\WorldDirectorS2")
REPO=ROOT/"work"/"main"
STATUS=ROOT/"handoff"/"LATEST_STATUS_FIXE_20261101.json"
ARCHIVE=ROOT/"test-modpack"/"artifacts"/"ProutlostS2-WorldDirector-43CCCBD-CLIENT_SERVER_TEST_ONLY-20261101.zip"
JAR=REPO/"build"/"libs"/"proutlost-director-1.0.0.jar"
PROFILES=ROOT/"test-modpack"/"qa-clients"

def require(value,why):
    if not value: raise RuntimeError(why)

def no_link(path):
    require(not path.is_symlink(), "Refuse symlink: "+str(path))
    if path.exists():
        require(not (getattr(path.stat(),"st_file_attributes",0) & 0x400),
                "Refuse Windows reparse point: "+str(path))

def sha(path):
    digest=hashlib.sha256()
    with path.open("rb") as f:
        for part in iter(lambda:f.read(1024*1024),b""):digest.update(part)
    return digest.hexdigest()

def safe_member(name):
    path=PurePosixPath(name)
    require(not path.is_absolute() and len(path.parts)>2 and
            all(p not in ("",".","..") and ":" not in p and "\\" not in p for p in path.parts),
            "Archive member path unsafe: "+name)
    return path

def inspect():
    info=json.loads(STATUS.read_text(encoding="utf-8-sig"))
    git_head=subprocess.check_output(["git","-C",str(REPO),"rev-parse","HEAD"],text=True).strip()
    require(git_head==info["git_head"],"Handoff not pinned to actual HEAD")
    # A QA tool-only commit must not cause an old JAR to be mislabeled as
    # newly compiled game code. Pin the actual Windows-tested source.
    head=info["latest_development"]["commit"]
    changed=set(subprocess.check_output(
        ["git","-C",str(REPO),"diff","--name-only",head,git_head],
        text=True).splitlines())
    require(changed.issubset({
        "scripts/stage-current-director-qa-clients.py",
        "scripts/test-stage-current-director-qa-clients.py"}),
        "Runtime source changed since Windows JAR proof")
    require(info["release_status"]=="TEST_ONLY_NON_DEPLOYABLE","Unexpected release status")
    require(info["native_linux_container"]["current_commit_native"].startswith("NOT_RUN"),
            "This staging is PRE_NATIVE only, inspect current state first")
    require(sha(ARCHIVE)==info["test_modpack_packaging"]["sha256"],
            "Qualified Lot B source archive changed")
    require(sha(JAR)==info["windows_java"]["jar_sha256"],
            "Compiled Windows JAR does not match latest proven artifact")
    status=subprocess.check_output(["git","-C",str(REPO),"status","--porcelain"],text=True).splitlines()
    known={"scripts/configure-river-private-lab-players.py",
           "scripts/test-configure-river-private-lab-players.py"}
    require({line[3:].strip() for line in status}.issubset(known | {
        "scripts/stage-current-director-qa-clients.py",
        "scripts/test-stage-current-director-qa-clients.py"}),
        "Unreviewed worktree change: refuse staging")
    for path in [ROOT,PROFILES,ARCHIVE,JAR]:
        no_link(path)
    with zipfile.ZipFile(ARCHIVE) as z:
        manifest=json.loads(z.read("PACK_TEST_STATUS.json"))
        require(manifest.get("director_source_commit")=="43cccbdc6385263b63c571ab1c8b01143c0fbce1",
                "Unexpected source pack revision")
        mods=manifest["source_addons"]
        require(len(mods)==17 and manifest["active_mods"]==18,"Lot B addon count mismatch")
        addons={m["name"]:m["sha256"] for m in mods}
        require(len(addons)==17 and all(n.endswith(".jar") and "/" not in n and "\\" not in n
            and len(h)==64 for n,h in addons.items()),"Invalid source mod map")
        entries={}
        for item in z.infolist():
            n=item.filename
            if not n.startswith("client-local-profile/") or n.endswith("/"):continue
            key=safe_member(n)
            require(n not in entries,"Duplicate archive member")
            # No hidden bytes, OS metadata, account secrets, launch settings.
            require(key.parts[1] in ("mods","config"),"Unapproved profile content")
            require(item.file_size<=128*1024*1024 and item.compress_size<=128*1024*1024,
                    "Archive entry too large")
            require(((item.external_attr>>16)&0o170000)!=0o120000,
                    "Archive symlink forbidden")
            entries[n]=item
        jar_paths={"client-local-profile/mods/"+n for n in addons}
        found_mods={n for n in entries if n.startswith("client-local-profile/mods/")}
        require(found_mods==jar_paths|{"client-local-profile/mods/proutlost-director-1.0.0.jar"},
                "Not exactly 18 source mods")
        config_paths=[n for n in entries if n.startswith("client-local-profile/config/")]
        require(config_paths,"No sanitized config files")
    return head, addons, entries, info

def stage():
    head,addons,entries,info=inspect()
    prefix=head[:7].upper()
    targets=[PROFILES/(prefix+"-Client-"+side) for side in ("A","B")]
    for target in targets:
        for p in [target,target.parent]:
            no_link(p)
        require(not target.exists(),"Refuse overwrite existing QA profile: "+str(target))
    for target in targets:
        target.mkdir(parents=False,exist_ok=False)
        files={}
        with zipfile.ZipFile(ARCHIVE) as z:
            for name in sorted(entries):
                relative=PurePosixPath(name).relative_to("client-local-profile")
                if relative.as_posix()=="mods/proutlost-director-1.0.0.jar":
                    continue
                destination=target.joinpath(*relative.parts)
                destination.parent.mkdir(parents=True,exist_ok=True)
                no_link(destination)
                digest=hashlib.sha256()
                with z.open(name) as s,destination.open("xb") as d:
                    for b in iter(lambda:s.read(1024*1024),b""):
                        d.write(b);digest.update(b)
                got=digest.hexdigest()
                if relative.parts[0]=="mods":
                    require(got==addons[relative.name],"Lot B mod source hash mismatch: "+relative.name)
                require(sha(destination)==got,"Copy verification failed")
                files[relative.as_posix()]=got
        dest=target/"mods"/"proutlost-director-1.0.0.jar"
        shutil.copyfile(JAR,dest)
        digest=sha(dest)
        require(digest==info["windows_java"]["jar_sha256"],"Director JAR copy changed")
        files["mods/proutlost-director-1.0.0.jar"]=digest
        require(len([p for p in files if p.startswith("mods/")])==18,"Incorrect mod count")
        receipt={"kind":"ISOLATED_CLIENT_FILES_PRE_NATIVE_UNQUALIFIED",
          "head":head,"source_pack_commit":"43cccbd","source_pack_sha256":sha(ARCHIVE),
          "windows_director_jar_sha256":digest,"minecraft":"1.21.1",
          "neoforge":"21.1.248","java":"21","files_sha256":files,
          "status":"TEST_ONLY_NON_DEPLOYABLE","native_current":"NOT_RUN",
          "real_client_launch":"NOT_RUN","f8_visual":"NOT_RUN",
          "approved":False,"gameplay_enabled":False,
          "created_utc":datetime.now(timezone.utc).isoformat(),
          "instructions":"Copy into NEW isolated Minecraft launcher profile only after current-native qualification; do not overwrite MapDev, do not use any real world."}
        output=target/"QA_STATUS.json"
        output.write_text(json.dumps(receipt,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
        print("STAGED",str(target),"files",len(files),"receipt_sha256",sha(output))
    return targets

def main():
    parser=argparse.ArgumentParser()
    group=parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--check",action="store_true")
    group.add_argument("--stage",action="store_true")
    options=parser.parse_args()
    if options.check:
        head,mods,entries,info=inspect()
        print("PASS_CURRENT_CLIENT_QA_SOURCE_CHECK",head,"source_mods",len(mods),"archive_entries",len(entries),
              "native","NOT_RUN","client","NOT_RUN")
    else: stage()

if __name__=="__main__":
    try: main()
    except Exception as e:
        print("QA_STAGE_REFUSED",type(e).__name__,str(e),file=sys.stderr)
        sys.exit(2)
