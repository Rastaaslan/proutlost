"""FIXE-only: prepare (never start) a private two-player QA server on Linux/ext4.

No user worlds, account secrets, or host/Docker port publications are modified.
Usage: py -3 scripts/prepare-river-private-lab.py --prepare | --check
"""
import argparse
import hashlib
import json
import re
from pathlib import Path
import subprocess
import sys
import zipfile

ROOT = Path(r"C:\WorldDirectorS2")
REPO = ROOT / "work" / "main"
STATUS_FILE = ROOT / "handoff" / "LATEST_STATUS_FIXE_20261101.json"
KIT = ROOT / "test-modpack" / "qa-sessions" / "2026-11-02-River"
PACK = ROOT / "test-modpack" / "artifacts" / (
    "ProutlostS2-WorldDirector-B236971-CLIENT_SERVER_TEST_ONLY-20261101T132923Z.zip")
CONTAINER = "worlddirector-s2-linux-lab-20261101"
PIN = "b236971bc348ef6cbc9523abab74a67cbc2c4c03"
SOURCE_MODS = "/workspace/labs/proutlost-s2-modpack-20261101-qa/mods"
LAB_ID = "riverqa-20261102"
TARGET = f"/workspace/revisions/b236971/build/director-runs/server-{LAB_ID}"
EXPECTED_PACK_SHA = "32319f8197f0688d8bd5d6f8c4fe83085bd1a5f805e1aea88eb60fa79f55a1f5"
SOURCE_ARCHIVE = ROOT / "migration" / "WORLD_DIRECTOR_S2_b236971_QA_SOURCE.tar.gz"
EXPECTED_SOURCE_SHA = "b7e872e00eca6001314118c4aa1ffbc7d20e780a19fce243fcecb9c3d26a9f10"
DIRECTOR_JAR = ROOT / "test-modpack" / "artifacts" / (
    "proutlost-director-1.0.0-b236971-linux-PRE_NATIVE_QA.jar")
EXPECTED_JAR_SHA = "0fce98328538444e69b984f12899259dfa12e1dcfe252d4bdc293c30497c5829"
LINUX_JAR = "/workspace/revisions/b236971/build/libs/proutlost-director-1.0.0.jar"
SERVER_PROPERTIES = (
    "# PRIVATE QA — only in Docker ext4; no public ports; real operator whitelist later.\n"
    "online-mode=true\n"
    "white-list=true\n"
    "enforce-whitelist=true\n"
    "max-players=2\n"
    "server-port=25565\n"
    "server-ip=\n"
    "enable-rcon=false\n"
    "enable-query=false\n"
    "enable-command-block=false\n"
    "broadcast-console-to-ops=false\n"
    "broadcast-rcon-to-ops=false\n"
    "view-distance=5\n"
    "simulation-distance=4\n"
    "sync-chunk-writes=true\n"
    "motd=Proutlost World Director S2 TEST_ONLY 2026-11-02 River\n"
    "level-name=world\n"
)

def process(args):
    r = subprocess.run(args, text=True, encoding="utf-8", errors="replace",
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if r.returncode:
        raise RuntimeError("Command failed: "+repr(args)+"\n"+r.stderr[-1800:])
    return r.stdout.strip()

def sha_file(file):
    hash_ = hashlib.sha256()
    with file.open("rb") as stream:
        for part in iter(lambda: stream.read(1024 * 1024), b""):
            hash_.update(part)
    return hash_.hexdigest()

def docker(*args):
    return process(["docker", "exec", CONTAINER, *args])

def require(condition, message):
    # Python -O removes assert statements: safety gates must remain active.
    if not condition:
        raise RuntimeError(message)

def require_current_session_pin():
    """Legacy b236971 staging cannot be used when tomorrow's QA is 43cccbd.

    Historical archives remain untouched; never silently create another older
    server even if its own ZIP/hash is internally consistent.
    """
    require(STATUS_FILE.is_file(), "Current FIXE QA status is unavailable")
    status = json.loads(STATUS_FILE.read_text(encoding="utf-8-sig"))
    session = status.get("scheduled_technical_session", {})
    require(session.get("session_date_local") == "2026-11-02" and
            session.get("pinned_qa_commit") == PIN,
            "Obsolete b236971 QA stager: active River session uses a different revision")

def assert_safe_lab():
    instances = json.loads(process(["docker", "inspect", CONTAINER]))
    require(len(instances) == 1, "Expected exactly one Docker lab")
    instance = instances[0]
    require(instance["State"]["Running"], "LAB stopped")
    host = instance["HostConfig"]
    require(not host["PortBindings"], "Lab exposed host ports")
    require(not any(instance["NetworkSettings"].get("Ports", {}).values()),
            "Ports published")
    require(0 < host["Memory"] <= 10 * 1024**3, "RAM limit not enforced")
    nanos = int(host.get("NanoCpus") or 0)
    quota, period = int(host.get("CpuQuota") or 0), int(host.get("CpuPeriod") or 0)
    require((0 < nanos <= 6_000_000_000) or (
        0 < period and 0 < quota <= 6 * period), "6 CPU limit not enforced")
    require(instance["Name"] == "/" + CONTAINER, "Wrong Docker lab identity")
    require(host.get("NetworkMode") not in ("host", "none")
            and not str(host.get("NetworkMode", "")).startswith("container:"),
            "Unexpected Docker networking mode")
    require(not host.get("Privileged"), "Privileged container not allowed")
    mounts = instance.get("Mounts", [])
    require(len(mounts) == 1 and mounts[0].get("Type") == "volume"
            and mounts[0].get("Name") == CONTAINER
            and mounts[0].get("Destination") == "/workspace",
            "Lab has unexpected host bind mounts or volumes")
    return {"memoryLimitBytes": host["Memory"], "nanoCpus": nanos,
            "cpuQuota": quota, "cpuPeriod": period, "publishedPorts": 0}

def expected_mods():
    require(sha_file(PACK) == EXPECTED_PACK_SHA, "QA archive hash changed")
    with zipfile.ZipFile(PACK) as archive:
        require(archive.testzip() is None, "QA archive ZIP is corrupted")
        info = json.loads(archive.read("PACK_TEST_STATUS.json"))
        require(info["director_source_commit"] == PIN, "QA pack commit mismatch")
        addons = info["source_addons"]
        require(len(addons) == 17, "Expected exactly 17 pinned Lot B mods")
        return {entry["name"]: entry["sha256"] for entry in addons}

def linux_mod_hashes(folder):
    values = docker("sh", "-c", "cd "+folder+" && sha256sum *.jar")
    pairs = [line.strip().split(None, 1) for line in values.splitlines()]
    result = {name.strip("*"): sha for sha, name in pairs}
    require(len(result) == len(pairs), "Duplicate jar entries")
    return result

def pinned_server_command():
    require(re.fullmatch(r"/workspace/revisions/[a-f0-9]{7,40}/build/libs/proutlost-director-1\.0\.0\.jar",
                         LINUX_JAR) is not None, "Unsafe pinned Linux JAR path")
    require(re.fullmatch(r"[a-z0-9-]{1,32}", LAB_ID) is not None,
            "Unsafe QA laboratory identifier")
    revision_root = LINUX_JAR.split("/build/libs/")[0]
    return (f"cd {revision_root} && ./gradlew --offline --no-daemon "
            "--max-workers=1 --no-configuration-cache runDirectorServer "
            f"-PdirectorLabServerId={LAB_ID} -PdirectorNativeServerLab=true")


def main():
    arg = argparse.ArgumentParser()
    arg.add_argument("--prepare", action="store_true")
    arg.add_argument("--check", action="store_true")
    flags = arg.parse_args()
    require(flags.prepare != flags.check, "Select exactly one mode")
    require_current_session_pin()  # Before ANY Docker calls or file mutations.
    head = process(["git", "-C", str(REPO), "rev-parse", "HEAD"])
    # The QA pack's code must be in the checkout's history. Adding
    # documentation/scripts is allowed; independent code changes are NOT.
    if head != PIN:
        changed = process(["git", "-C", str(REPO), "diff",
                           "--name-only", PIN, head]).splitlines()
        expected_tools = {
            ".gitignore",
            "scripts/prepare-river-private-lab.py",
            "scripts/test-prepare-river-private-lab.py",
            "scripts/configure-river-private-lab-players.py",
            "scripts/test-configure-river-private-lab-players.py",
        }
        require(bool(changed) and set(changed).issubset(expected_tools),
                "QA game code has advanced; pin or requalify instead")
    require(not process(["git", "-C", str(REPO), "status", "--porcelain"]),
            "Independent uncommitted development present; refuse QA staging/check")
    limits = assert_safe_lab()
    require(sha_file(SOURCE_ARCHIVE) == EXPECTED_SOURCE_SHA,
            "Pinned Director source snapshot mismatch")
    require(sha_file(DIRECTOR_JAR) == EXPECTED_JAR_SHA,
            "Pinned Director JAR mismatch")
    mirror_sha = docker("sha256sum", LINUX_JAR).split()[0]
    require(mirror_sha == EXPECTED_JAR_SHA,
            "Linux compiled Director JAR differs from pinned QA")
    expected = expected_mods()
    require(linux_mod_hashes(SOURCE_MODS) == expected, "Pinned Lot B mismatch")
    exists = subprocess.run(["docker", "exec", CONTAINER, "test", "-e", TARGET]).returncode == 0
    if flags.prepare:
        require(not exists, "Refuse to overwrite existing QA server")
        docker("mkdir", "-p", TARGET + "/mods")
        docker("sh", "-c", "cp "+SOURCE_MODS+"/*.jar "+TARGET+"/mods/")
        (KIT / "server.properties").write_text(SERVER_PROPERTIES, encoding="utf-8")
        (KIT / "eula.txt").write_text("eula=true\n", encoding="ascii")
        for name in ("server.properties", "eula.txt"):
            process(["docker", "cp", str(KIT / name),
                     CONTAINER+":"+TARGET+"/"+name])
    require(linux_mod_hashes(TARGET + "/mods") == expected,
            "Staged Lot B mods changed")
    require(docker("cat", TARGET + "/server.properties") ==
            SERVER_PROPERTIES.strip(), "Staged server properties mismatch")
    require(docker("cat", TARGET + "/eula.txt") == "eula=true",
            "Staged EULA changed")
    require(subprocess.run(["docker", "exec", CONTAINER, "test", "-e",
                            TARGET+"/world"]).returncode != 0,
            "World already exists; cannot reset it")
    receipt = {
        "state":"STAGED_PRIVATE_LINUX_SERVER_NOT_STARTED",
        "date":"2026-11-02", "pinnedCodeCommit": PIN,
        "sourcePackSha256": EXPECTED_PACK_SHA,
        "serverGameDirectory": TARGET, "modCount":len(expected),
        "modsSha256":expected,"serverPropertiesSha256":hashlib.sha256(
            SERVER_PROPERTIES.encode()).hexdigest(),
        "enforceWhitelist":True,"onlineMode":True,"maxPlayers":2,
        "gameplayEnabled":False,"realClients":"NOT_RUN","F8":"NOT_RUN",
        "javaStarted":False,"worldCreated":False,"networkPrivateNotConfigured":True,
        "limits":limits,
        "nextCommandInsideContainer": pinned_server_command(),
        "reminder":"Start only once user/OBS resource check and private auth networking are confirmed."
    }
    print(json.dumps(receipt,ensure_ascii=False,indent=2))
    if flags.prepare:
        output=KIT/"SERVER_PRIVATE_STAGE_RECEIPT.json"
        require(not output.exists(), "Refuse overwriting prior evidence")
        output.write_text(json.dumps(receipt,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")

if __name__ == "__main__":
    try:
        main()
    except Exception as failure:
        print("RIVER_QA_PREPARE_REFUSED "+repr(failure),file=sys.stderr)
        raise
