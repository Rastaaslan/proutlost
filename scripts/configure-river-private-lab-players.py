"""Safely stage two authenticated Minecraft users in the isolated River QA lab.

No network ports, worlds, active servers, tokens or third-party containers touched.
Uses exactly one level-4 operator and one non-operator, never a shared account.
Run without --apply to validate inputs offline without writing anything.
"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import zipfile
import re
import subprocess
import sys
import uuid

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location(
    "director_river_private_lab", HERE / "prepare-river-private-lab.py")
lab = importlib.util.module_from_spec(spec)
spec.loader.exec_module(lab)
# The old 2ef5341 stage on b236971 is preserved for rollback but must never
# receive tomorrow's user identities. Only the native-qualified 43cccbd
# stage with the verified correction receipt can accept real accounts.
KIT = lab.KIT
STAGE = KIT / "stage-43cccbd"
RECEIPT = STAGE / "SERVER_PRIVATE_STAGE_RECEIPT.json"
CORRECTION = STAGE / "SERVER_LAUNCH_COMMAND_VERIFIED_CORRECTION.json"
PLAYER_RECEIPT = STAGE / "PLAYER_STAGE_RECEIPT.json"
PIN = "43cccbdc6385263b63c571ab1c8b01143c0fbce1"
PACK = lab.ROOT / "test-modpack" / "artifacts" / (
    "ProutlostS2-WorldDirector-43CCCBD-CLIENT_SERVER_TEST_ONLY-20261101.zip")
PACK_SHA = "da50ccfe0815da282bf627a8c8fca82948cd3194aa0c60b6ae922c770344bfef"
JAR = lab.ROOT / "test-modpack" / "artifacts" / (
    "proutlost-director-1.0.0-43cccbd-linux-QA.jar")
JAR_SHA = "2b42f302a8ae628b2978dd4fbae21af7920a6ac1f130455e0fcf9cb14ea7c4fd"
LINUX_JAR = "/workspace/revisions/43cccbd/build/libs/proutlost-director-1.0.0.jar"
TARGET = "/workspace/revisions/43cccbd/build/director-runs/server-riverqa-20261102-r43"
RECEIPT_SHA = "c8bf9dbb792979b9228ac8b34ac07ccc6908f55a692a497f66f68d2f3660f5bc"
CORRECTION_SHA = "374631f5a4e76318bb767395cd6b4e3b5241e6fe47ef049dace3bc87eb8ea614"
# The staged Windows ZIP used CRLF and a revision-specific MOTD.
# Keep the original receipt's pre-copy LF hash as historical evidence, not
# as proof of what was copied. Pin actual bytes on BOTH sides instead.
STAGED_PROPERTIES_SHA = "7a3bfeef50f30bf9abc36c017d7699bf80e4f404ad41c942000e77bba8087883"
CMD = ("cd /workspace/revisions/43cccbd && ./gradlew --offline --no-daemon "
       "--max-workers=1 --no-configuration-cache runDirectorServer "
       "-PdirectorLabServerId=riverqa-20261102-r43 -PdirectorNativeServerLab=true")
STATUS = lab.ROOT / "handoff" / "LATEST_STATUS_FIXE_20261101.json"
NAME = re.compile(r"[A-Za-z0-9_]{3,16}\Z")


# Development can advance after the qualified 43cccbd River kit is frozen.
# Admit only Director source trees, and compare their ORIGINAL pinned bytes to
# the immutable Linux QA mirror. Never load HEAD classes into this old server.
PINNED_SOURCE_PREFIXES = (
    "src/director/java/",
    "src/director/resources/",
    "src/directorTest/java/",
    "src/directorTest/resources/",
    "src/directorGameTest/java/",
    "src/directorGameTest/resources/",
    "src/directorPlatform/java/",
    "src/directorPlatform/gameTest/java/",
)
PINNED_BUILD_FILES = frozenset({"build.gradle"})


def pinned_source_path(relative):
    if not relative or "\\" in relative or any(
            part in ("", ".", "..") for part in relative.split("/")):
        return False
    return relative in PINNED_BUILD_FILES or any(
        relative.startswith(prefix) for prefix in PINNED_SOURCE_PREFIXES)


# Reviewed LF-only attributes: gradlew, J01/J02 schemas and normative fixture
# JSONs. Diff against 43cccbd contains ONLY added eol=lf rules; no code changes
# and no change to the pinned prebuilt server/JAR. Any later change fails closed.
APPROVED_LF_ATTR_BLOB = "cc30880a3737bab2fa6cf089ba33440a7eefd576"

# Frozen River QA accepts only this exact reviewed, non-runtime documentation
# Git blob. Do not permit arbitrary docs/ paths or altered documentation.
REVIEWED_DOC_BLOBS = {
    "docs/worlddirector-v1/J05_BLOCK_OWNERSHIP_RECOVERY_FENCE_20261102.md":
        "64fa1ec733b49eb5623b27da1d0833307e0a4e1a",
}


def require(ok, message):
    if not ok:
        raise RuntimeError(message)


def verify_reviewed_lf_attributes(changed_paths):
    """Only the exact reviewed build-attributes change may accompany frozen River QA."""
    if ".gitattributes" in changed_paths:
        observed = lab.process(["git", "-C", str(lab.REPO),
                                "rev-parse", "HEAD:.gitattributes"])
        require(observed == APPROVED_LF_ATTR_BLOB,
                "Unreviewed .gitattributes may change QA archive bytes")


def verify_reviewed_docs(changed_paths):
    """Allow only the reviewed exact non-runtime Git blob, never a docs subtree."""
    for path in sorted(set(changed_paths) & REVIEWED_DOC_BLOBS.keys()):
        observed = lab.process(["git", "-C", str(lab.REPO),
                                "rev-parse", "HEAD:" + path])
        require(observed == REVIEWED_DOC_BLOBS[path],
                "Unreviewed documentation change cannot bypass frozen QA")


def verify_frozen_linux_sources(changed_paths):
    """Accept later development only if the actual QA runtime still has PIN's bytes.

    Compare Git's immutable object to Linux bytes after CRLF normalization:
    the qualified mirror was exported from Windows with CRLF source files.
    Never compare against the newer, unqualified checkout or its JAR.
    """
    if not changed_paths:
        return
    require(all(pinned_source_path(relative) for relative in changed_paths),
            "Unreviewed code may not bypass the pinned QA stage")
    # Git's immutable tree is the authority for whether a path is genuinely
    # new. Never interpret a failed Git blob read as evidence of absence:
    # historic missing objects must fail the preflight rather than bypass it.
    pinned = set(subprocess.check_output(
        ["git", "-C", str(lab.REPO), "ls-tree", "-r", "--name-only", PIN],
        stderr=subprocess.PIPE).decode("utf-8").splitlines())
    require(bool(pinned), "Pinned Git tree unavailable")
    for relative in sorted(changed_paths):
        require(pinned_source_path(relative),
                "Unreviewed code may not bypass the pinned QA stage")
        mirror = "/workspace/revisions/43cccbd/" + relative
        if relative not in pinned:
            # Source added AFTER the QA pin must be absent from its mirror.
            present = subprocess.run(
                ["docker", "exec", lab.CONTAINER, "test", "-e", mirror],
                capture_output=True)
            require(present.returncode == 1,
                    "New game code presence/verification uncertain in pinned QA mirror: " + relative)
            continue
        reference = subprocess.check_output(
            ["git", "-C", str(lab.REPO), "show", PIN + ":" + relative],
            stderr=subprocess.PIPE)
        staged = subprocess.check_output(
            ["docker", "exec", lab.CONTAINER, "cat", mirror],
            stderr=subprocess.PIPE)
        require(bool(reference) and
                reference.replace(b"\r\n", b"\n") ==
                staged.replace(b"\r\n", b"\n"),
                "Pinned Linux source differs from qualified Git revision: " + relative)


def identity(name, raw_uuid):
    require(bool(NAME.fullmatch(name or "")),
            "Minecraft player name must have 3-16 letters, digits or underscores")
    try:
        uid = str(uuid.UUID(raw_uuid))
    except (ValueError, TypeError, AttributeError) as failure:
        raise ValueError("Invalid Minecraft account UUID") from failure
    require(uid != "00000000-0000-0000-0000-000000000000",
            "All-zero UUID is not an authenticated player")
    return {"uuid": uid, "name": name}


def role_files(operator_name, operator_uuid, river_name, river_uuid):
    operator = identity(operator_name, operator_uuid)
    river = identity(river_name, river_uuid)
    require(operator["uuid"] != river["uuid"], "Both users cannot share a Minecraft UUID")
    require(operator["name"].casefold() != river["name"].casefold(),
            "Both players cannot share a Minecraft name")
    allow = [operator, river]
    ops = [{**operator, "level": 4, "bypassesPlayerLimit": False}]
    return {
        "whitelist.json": (json.dumps(allow, separators=(",", ":")) + "\n").encode("ascii"),
        "ops.json": (json.dumps(ops, separators=(",", ":")) + "\n").encode("ascii"),
    }


def pack_mods():
    require(lab.sha_file(PACK) == PACK_SHA, "Pinned 43cccbd QA archive changed")
    with zipfile.ZipFile(PACK) as archive:
        require(archive.testzip() is None, "Qualified QA ZIP corrupted")
        data = json.loads(archive.read("PACK_TEST_STATUS.json"))
    require(data.get("director_source_commit") == PIN and
            data.get("director_jar_sha256") == JAR_SHA and
            data.get("active_mods") == 18,
            "Qualified pack identity mismatch")
    entries = data.get("source_addons")
    require(isinstance(entries, list) and len(entries) == 17,
            "Qualified Lot B count mismatch")
    hashes = {m["name"]: m["sha256"] for m in entries}
    require(len(hashes) == 17 and
            all(name.endswith(".jar") and "/" not in name and "\\\\" not in name
                and len(digest) == 64 for name, digest in hashes.items()),
            "Invalid source-mod manifest")
    return hashes


def preflight():
    require(RECEIPT.is_file() and lab.sha_file(RECEIPT) == RECEIPT_SHA,
            "43cccbd stage receipt absent/changed")
    require(CORRECTION.is_file() and lab.sha_file(CORRECTION) == CORRECTION_SHA,
            "43cccbd corrected launch receipt absent/changed")
    stage = json.loads(RECEIPT.read_text(encoding="utf-8"))
    corrected = json.loads(CORRECTION.read_text(encoding="utf-8"))
    require(stage.get("state") == "STAGED_PRIVATE_LINUX_SERVER_NOT_STARTED",
            "Private test server was not staged")
    require(stage.get("pinnedCodeCommit") == PIN and
            stage.get("sourcePackSha256") == PACK_SHA and
            stage.get("serverGameDirectory") == TARGET,
            "Staged QA revision mismatch: refuse stale rollback directory")
    require(stage.get("modCount") == 17 and
            stage.get("javaStarted") is False and
            stage.get("worldCreated") is False and
            stage.get("networkPrivateNotConfigured") is True and
            stage.get("onlineMode") is True and
            stage.get("enforceWhitelist") is True and
            stage.get("maxPlayers") == 2 and
            stage.get("gameplayEnabled") is False,
            "Unsafe or already activated test stage")
    require(corrected.get("original_sha256") == RECEIPT_SHA and
            corrected.get("code_revision") == PIN and
            corrected.get("corrected_pinned_launch_command") == CMD and
            corrected.get("serverStarted") is False and
            corrected.get("worldCreated") is False,
            "Stale or incorrect 43cccbd launch instruction")
    require(lab.sha_file(JAR) == JAR_SHA, "Qualified Director JAR changed")
    manifest = pack_mods()
    require(stage.get("modsSha256") == manifest, "Staging manifest does not match QA archive")

    status = json.loads(STATUS.read_text(encoding="utf-8-sig"))
    session = status.get("scheduled_technical_session", {})
    require(session.get("session_date_local") == "2026-11-02" and
            session.get("pinned_qa_commit") == PIN and
            session.get("full_qa_pack_sha256") == PACK_SHA and
            session.get("status") == "PREPARED_NOT_STARTED",
            "Session no longer pins qualified 43cccbd")
    require(status.get("release_status") == "TEST_ONLY_NON_DEPLOYABLE",
            "Never stage player access for released gameplay")

    require(not lab.process(["git", "-C", str(lab.REPO), "status", "--porcelain"]),
            "Another developer has uncommitted changes; refuse preflight")
    changes = set(lab.process(["git", "-C", str(lab.REPO), "diff",
                               "--name-only", PIN, "HEAD"]).splitlines())
    allowed = {
        ".gitignore",
        ".gitattributes",
        "scripts/prepare-river-private-lab.py",
        "scripts/test-prepare-river-private-lab.py",
        "scripts/configure-river-private-lab-players.py",
        "scripts/test-configure-river-private-lab-players.py",
    } | REVIEWED_DOC_BLOBS.keys()
    require(subprocess.run(
            ["git", "-C", str(lab.REPO), "merge-base", "--is-ancestor",
             PIN, "HEAD"], stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL).returncode == 0,
            "Qualified 43cccbd must remain an ancestor of active development")
    source_changes = changes - allowed
    require(all(pinned_source_path(path) for path in source_changes),
            "Unreviewed non-Director changes may not bypass frozen QA")
    verify_reviewed_lf_attributes(changes)
    verify_reviewed_docs(changes)
    lab.assert_safe_lab()
    verify_frozen_linux_sources(source_changes)
    require(lab.docker("sha256sum", LINUX_JAR).split()[0] == JAR_SHA,
            "Linux executable JAR differs from pinned 43cccbd")
    require(lab.linux_mod_hashes(TARGET + "/mods") == manifest,
            "Staged server mods no longer match qualified archive")
    properties = STAGE / "server.properties"
    require(properties.is_file() and lab.sha_file(properties) == STAGED_PROPERTIES_SHA,
            "Local staged server properties changed")
    require(lab.docker("sha256sum", TARGET + "/server.properties").split()[0] ==
            STAGED_PROPERTIES_SHA, "Linux staged server properties differ")
    expected = lab.SERVER_PROPERTIES.replace(
        "motd=Proutlost World Director S2 TEST_ONLY 2026-11-02 River",
        "motd=World Director 43cccbd TEST_ONLY River")
    # Exact safety parameters, including online-mode and whitelist, must
    # survive Windows CRLF->Linux reads. No unknown/duplicate keys allowed.
    require(properties.read_bytes().decode("utf-8").replace("\r\n", "\n") ==
            expected.replace("\r\n", "\n") and
            lab.docker("cat", TARGET + "/server.properties") ==
                    expected.replace("\r\n", "\n").strip(),
            "Unexpected private server properties content")
    require(lab.docker("cat", TARGET + "/eula.txt") == "eula=true",
            "Staged EULA changed")
    require(subprocess.run(["docker", "exec", lab.CONTAINER,
            "test", "-e", TARGET + "/world"]).returncode != 0,
            "World exists; cannot change live account roles")
    require(subprocess.run(["docker", "exec", lab.CONTAINER,
            "test", "-L", TARGET]).returncode != 0,
            "Symlinked stage target forbidden")
    require(lab.docker("readlink", "-f", TARGET) == TARGET,
            "QA path resolves outside authorized stage")
    processes = lab.docker("ps", "-eo", "comm")
    require(not any(line.strip() == "java" for line in processes.splitlines()),
            "Java already active in isolated lab")
    return stage

def add_only_if_same(name, content):
    require(name in ("ops.json", "whitelist.json"), "Unsafe player file name")
    dest = STAGE / name
    digest = hashlib.sha256(content).hexdigest()
    if dest.exists():
        require(dest.is_file() and lab.sha_file(dest) == digest,
                "Existing local player config differs: " + name)
    else:
        with dest.open("xb") as stream:
            stream.write(content)
    # Only these two names are permitted; never copy into arbitrary paths.
    require(name in ("ops.json", "whitelist.json"), "Unsafe player file name")
    target = TARGET + "/" + name
    exists = subprocess.run(["docker", "exec", lab.CONTAINER,
                             "test", "-e", target]).returncode == 0
    if exists:
        live_hash = lab.docker("sha256sum", target).split()[0]
        require(live_hash == digest, "Existing LAB player config differs: " + name)
    else:
        lab.process(["docker", "cp", str(dest), lab.CONTAINER + ":" + target])
        live_hash = lab.docker("sha256sum", target).split()[0]
        require(live_hash == digest, "LAB player config copy verification failed")
    return digest


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--operator-name")
    parser.add_argument("--operator-uuid")
    parser.add_argument("--river-name")
    parser.add_argument("--river-uuid")
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--preflight", action="store_true",
                        help="Audit only; never write or start Minecraft")
    args = parser.parse_args()
    values = (args.operator_name, args.operator_uuid, args.river_name, args.river_uuid)
    if args.preflight:
        require(not args.apply and not any(values),
                "Preflight cannot stage players or launch Minecraft")
        stage = preflight()
        print(json.dumps({"state": "PINNED_STAGE_VERIFIED_AWAITING_REAL_CLIENTS_AND_PRIVATE_ROUTE",
                          "commit": PIN, "serverGameDirectory": TARGET,
                          "playersConfigured": False, "serverStarted": False,
                          "privateMinecraftRouteVerified": False,
                          "publicPortPublished": False, "worldCreated": False,
                          "archiveVerified": True, "modCount": stage["modCount"]}))
        return
    if not all(values):
        require(not args.apply, "All four verified account fields required to stage")
        require(not any(values), "Provide all four fields or none")
        print(json.dumps({"state": "AWAITING_TWO_REAL_ACCOUNT_UUIDS",
            "serverStarted": False, "networkPublished": False,
            "instructions": "Supply two validated Minecraft usernames and UUIDs when known."}))
        return
    payloads = role_files(*values)
    require(not args.apply or not PLAYER_RECEIPT.exists(),
            "Existing player stage receipt must not be overwritten")
    if not args.apply:
        print(json.dumps({"state": "DRY_RUN_VALIDATED",
            "playerCount": 2, "operatorLevel": 4, "guestOperator": False,
            "serverStarted": False, "networkPublished": False}))
        return
    preflight()
    digests = {name: add_only_if_same(name, content)
               for name, content in payloads.items()}
    record = {"state": "PLAYER_ROLES_STAGED_SERVER_NOT_STARTED",
              "serverGameDirectory": TARGET,
              "pinnedCodeCommit": PIN,
              "whitelistSha256": digests["whitelist.json"],
              "opsSha256": digests["ops.json"],
              "players": 2, "level4Operators": 1,
              "serverStarted": False, "portPublished": False,
              "realClients": "NOT_RUN",
              "note": "Accounts are preconfigured but not authenticated/connected."}
    with PLAYER_RECEIPT.open("x", encoding="utf-8") as stream:
        stream.write(json.dumps(record, indent=2) + "\n")
    print(json.dumps(record))


if __name__ == "__main__":
    try:
        main()
    except Exception as failure:
        print("RIVER_PLAYER_STAGE_REFUSED " + repr(failure), file=sys.stderr)
        raise
