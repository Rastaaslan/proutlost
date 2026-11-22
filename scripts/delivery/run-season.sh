#!/bin/sh
set -eu
cd "$(dirname "$0")"
java_version="$(java -version 2>&1)"
case "$java_version" in *'version "21.'*|*'version "21"'*) ;; *) echo "Java 21 x64 requis." >&2; exit 1;; esac
pin="$(python3 - <<'PY'
from pathlib import Path
import json,hashlib
r=Path(".")
if not (r/"SEASON_INSTALLATION.json").is_file():
 raise SystemExit("Saison non installee : Contra et le profil Director approuve sont requis. Voir INSTALLATION.md.")
if not (r/"world/level.dat").is_file():raise SystemExit("Monde Contra absent.")
p=r/"world/proutlost/director-profile/manifest.json"
if not p.is_file():raise SystemExit("Manifeste Director absent.")
m=json.loads(p.read_text(encoding="utf-8-sig"))
if m.get("status")!="APPROVED" or m.get("production_approved") is not True:
 raise SystemExit("Profil auteur non approuve.")
h=hashlib.sha256(p.read_bytes()).hexdigest()
if json.loads((r/"SEASON_INSTALLATION.json").read_text(encoding="utf-8-sig"))["director_manifest_sha256"]!=h:
 raise SystemExit("Le profil a change depuis son installation.")
args=(r/"user_jvm_args.txt").read_text()
if any(x in args for x in ["nativeLab","productionRuntimeQa","cohortQa","proutlost.qa."]):
 raise SystemExit("Arguments QA incompatibles avec la saison.")
print(h)
PY
)"
exec java "-Dproutlost.director.productionManifestSha256=$pin" @user_jvm_args.txt @libraries/net/neoforged/neoforge/21.1.248/unix_args.txt nogui
