#!/bin/sh
set -eu
cd "$(dirname "$0")"
case "$(java -version 2>&1)" in *'version "21.'*|*'version "21"'*) ;; *) echo 'Java 21 requis.' >&2; exit 1;; esac
test -f world/level.dat || { echo 'Monde Contra absent : lancement refuse.' >&2; exit 1; }
test -f SEASON_INSTALLATION.json || { echo 'Installation de saison absente.' >&2; exit 1; }
test -f DIRECTOR_MANIFEST_SHA256.txt || exit 1
pin="$(cat DIRECTOR_MANIFEST_SHA256.txt)"
test "${#pin}" -eq 64 || exit 1
actual="$(sha256sum world/proutlost/director-profile/manifest.json)"
test "${actual%% *}" = "$pin" || { echo 'Manifeste modifie : actualiser ses empreintes avant lancement.' >&2; exit 1; }
if grep -Eq 'nativeLab|productionRuntimeQa|cohortQa|proutlost\.qa\.' user_jvm_args.txt; then
 echo 'Arguments de laboratoire refuses pour cette saison.' >&2; exit 1
fi
exec java "-Dproutlost.director.productionManifestSha256=$pin" -Dproutlost.director.locationImport=contra-saison2 @user_jvm_args.txt @libraries/net/neoforged/neoforge/21.1.248/unix_args.txt nogui
