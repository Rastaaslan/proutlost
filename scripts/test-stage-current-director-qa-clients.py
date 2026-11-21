"""Offline verification of two CURRENT QA profiles; no game or server launch."""
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import hashlib
s=Path(__file__).with_name("stage-current-director-qa-clients.py")
spec=importlib.util.spec_from_file_location("director_current_qa",s)
module=importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
head,mods,entries,status=module.inspect()
count=0
for side in ("A","B"):
    p=module.PROFILES/(head[:7].upper()+"-Client-"+side)
    receipt=p/"QA_STATUS.json"
    data=json.loads(receipt.read_text(encoding="utf-8"))
    assert data["head"]==head and data["status"]=="TEST_ONLY_NON_DEPLOYABLE"
    assert data["native_current"]=="NOT_RUN" and data["real_client_launch"]=="NOT_RUN"
    assert not data["approved"] and not data["gameplay_enabled"]
    assert data["minecraft"]=="1.21.1" and data["neoforge"]=="21.1.248" and data["java"]=="21"
    actual={str(q.relative_to(p)).replace("\\","/") for q in p.rglob("*") if q.is_file()}
    assert actual==set(data["files_sha256"])|{"QA_STATUS.json"},actual-set(data["files_sha256"])
    for path,h in data["files_sha256"].items():
        value=p.joinpath(*path.split("/"))
        module.no_link(value)
        assert module.sha(value)==h,path
        if path.startswith("mods/") and path.endswith(".jar"):
            expected=(mods.get(value.name) if value.name in mods else status["windows_java"]["jar_sha256"])
            assert h==expected,path
        count+=1
assert count==58,count
# Staging is create-only: must refuse any attempt to mutate the populated target.
res=subprocess.run([sys.executable,str(s),"--stage"],capture_output=True,text=True)
assert res.returncode==2 and "Refuse overwrite" in res.stderr,res.stderr
print("PASS_CURRENT_QA_PROFILE_VERIFICATION",count,
      "files in two distinct profiles, create-only refusal PASS; actual launches NOT_RUN")
