#!/usr/bin/env bash
# Run by the trusted service in this isolated worktree; never installs a mod.
set -euo pipefail
cd "$(dirname "$0")/.."
export GRADLE_USER_HOME="$PWD/.gradle/local-home"
variants=(repair_filter_unite detour_filter_distribute repair_amplify_stable_distribute detour_amplify_disturbed_unite)
# Preserve any previous campaign world; do not silently turn a first run into a restart.
for variant in "${variants[@]}"; do
  if [[ -e "build/quest-gradle-$variant" ]]; then
    echo "Existing test world: build/quest-gradle-$variant. Archive it before a fresh run." >&2
    exit 2
  fi
done
java -XshowSettings:properties -version 2>&1 | grep -Eq 'java.specification.version = 21$'
grep -qx 'minecraft_version=1.21.1' gradle.properties
grep -qx 'neo_version=21.1.248' gradle.properties
mkdir -p build/quest-trusted
report=$(mktemp -d "$PWD/build/quest-trusted/run-XXXXXXXX")
run_gradle() {
  local name=$1
  shift
  # pipefail preserves Gradle's failure even when tee succeeds.
  ./gradlew --no-daemon --offline "$@" 2>&1 | tee "$report/$name.log"
}
run_gradle build test build runGameTestServer
for variant in "${variants[@]}"; do
  run_gradle "$variant-first" runGameTestServer "-PquestCampaign=$variant"
  run_gradle "$variant-restart" runGameTestServer "-PquestCampaign=$variant" -PquestRestart=true
done
# Only report official artifacts after every command succeeds.
sha256sum build/libs/*.jar | tee "$report/artifacts.sha256"
echo "Gradle commands succeeded. Reports: $report. Graphical multiclient validation remains separate."
