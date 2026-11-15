#!/usr/bin/env python3
"""Read-only input, ownership and J02 import checks; does not run the common G0/G1 campaign."""
import hashlib
import json
import pathlib
import re
import subprocess
import tarfile

root = pathlib.Path(__file__).resolve().parents[7]
spec = pathlib.Path('/var/lib/codexbridge/worktrees/CB-202/build/director-input/spec-1.0.0')
g0 = pathlib.Path('/var/lib/codexbridge/worktrees/CB-202/build/director')
checks = []
def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def check(name, ok):
    assert ok, name
    checks.append({'name': name, 'result': 'PASS'})

check('spec archive SHA256', sha(spec.parent / 'spec.tar.xz') == '1b00564384c295733beff865707f0da9267b4b0b8a8c1f98bd63fb1da9ef07f2')
archive = g0 / 'G0_FROZEN_SOURCE_e769c9748318deffa7f81ad5352f4453e26e961ddb76abdbb969cbc1bcfbcbb2.tar.gz'
check('G0 frozen archive SHA256', sha(archive) == '1272394344ec57f3b3a2c351f1ba57b686443c0271e60ea4aeccaaeb17eaa199')
jar = g0 / 'G0_FROZEN_CONTRACTS_e769c9748318deffa7f81ad5352f4453e26e961ddb76abdbb969cbc1bcfbcbb2.jar'
check('G0 contracts JAR SHA256', sha(jar) == '7f023597d51a2993ef95d64480c04a2f78c77b602dc660c0866377ada531f499')
manifest = json.loads((spec / 'MANIFEST.json').read_text())
check('MANIFEST39 count', len(manifest['files']) == 39)
for e in manifest['files']:
    p = spec / e['path']; check('MANIFEST39:' + e['path'], p.stat().st_size == e['bytes'] and sha(p) == e['sha256'])
for name, e in json.loads((spec / 'sources/SOURCE_SHA256.json').read_text()).items():
    p = spec / 'sources' / name; check('SOURCE_SHA256:' + name, p.stat().st_size == e['bytes'] and sha(p) == e['sha256'])
overlay = json.loads((root / 'reports/J02/API_BASELINE_ACK_1.0.1.json').read_text())
overlay_files = {e['path']: e for e in overlay['files']}
for e in overlay_files.values():
    check('API1.0.1 overlay:' + e['path'], sha(root / e['path']) == e['sha256'] and (root / e['path']).stat().st_size == e['bytes'])
check('API1.0.1 JAR', sha(g0 / 'API_1.0.1_CONTRACTS_c94f65e6e3618a96498588e9995818673955169e1ac914e74be57e5133e12b5b.jar') == overlay['jar_sha256'])
with tarfile.open(g0 / 'API_1.0.1_DELTA_c94f65e6e3618a96498588e9995818673955169e1ac914e74be57e5133e12b5b.tar.gz') as delta:
    for member in delta.getmembers(): check('API1.0.1 exact archive:' + member.name, (root / member.name).read_bytes() == delta.extractfile(member).read())
api = json.loads((root / 'docs/worlddirector-v1/API_MANIFEST_1.0.0.json').read_text())
for e in api['files']:
    expected = overlay_files.get(e['path'], e)
    p = root / e['path']; check('API_MANIFEST with authorized overlay:' + e['path'], p.stat().st_size == expected['bytes'] and sha(p) == expected['sha256'])
digest = hashlib.sha256(''.join(e['path'] + '\0' + e['sha256'] + '\n' for e in sorted(api['files'], key=lambda e: e['path'])).encode()).hexdigest()
check('G0 source digest', digest == api['source_digest'] == 'e769c9748318deffa7f81ad5352f4453e26e961ddb76abdbb969cbc1bcfbcbb2')
with tarfile.open(archive) as tar:
    imported = []
    for entry in tar.getmembers():
        check('G0 preserved or exact authorized overlay:' + entry.name, sha(root / entry.name) == overlay_files[entry.name]['sha256'] if entry.name in overlay_files else (root / entry.name).read_bytes() == tar.extractfile(entry).read())
        imported.append(entry.name)
for kind in ['events', 'chains']:
    for p in (root / 'src/directorTest/resources/catalog' / kind).glob('*.json'):
        check('authentic fixture:' + p.name, p.read_bytes() == (spec / 'examples' / kind / p.name).read_bytes())
for name in ['event-definition.schema.json', 'chain-definition.schema.json']:
    p = root / 'src/director/resources/schemas' / name
    check('authentic schema:' + p.name, p.read_bytes() == (spec / 'schemas' / p.name).read_bytes())
runtime_root = root / 'src/director/resources/data/proutlost_director/director'
runtime_manifest = json.loads((runtime_root / 'candidate-manifest.json').read_text())
check('runtime candidate not production approved', runtime_manifest['status'] == 'CANDIDATE' and runtime_manifest['production_approved'] is False)
check('runtime testing profile byte exact', (runtime_root / 'profiles/default_for_testing.json').read_bytes() == (spec / 'DEFAULT_FOR_TESTING.json').read_bytes())
check('runtime candidate definition count', len(runtime_manifest['definitions']) == 11)
for entry in runtime_manifest['definitions']:
    p = root / 'src/director/resources' / entry['resource']
    check('runtime manifest hash:' + entry['definition_id'], sha(p) == entry['sha256'])
    kind = 'events' if '/events/' in entry['resource'] else 'chains'
    expected = json.loads((spec / 'examples' / kind / p.name).read_text())
    expected['id'] = 'proutlost_director:candidate/' + p.stem
    if kind == 'chains':
        for transition in expected['transitions']:
            if transition['event_id'] is not None:
                transition['event_id'] = transition['event_id'].replace('proutlost:', 'proutlost_director:candidate/', 1)
    elif expected['eligibility']['type'] == 'requirement_pending':
        expected['eligibility']['selector'] = expected['eligibility']['selector'].replace('proutlost:', 'proutlost_director:candidate/', 1)
    check('runtime preserves normative recipes and calibration:' + p.name, json.loads(p.read_text()) == expected)
for p in (root / 'src/director/java/fr/proutlost/director/catalog').glob('*.java'):
    source = p.read_text()
    check('J02 pure imports:' + p.name, not any(x in source for x in ['net.minecraft', 'net.neoforged', 'fr.proutlost.worldprep', '.testkit']))
    for target in re.findall(r'import\s+(?:static\s+)?fr\.proutlost\.director\.([\w.]+)', source):
        check('J02 API-only dependency:' + p.name + ':' + target, target.startswith(('api.', 'catalog.')))
results = json.loads((root / 'reports/J02/TEST_RESULTS.json').read_text())
for name, digest in results['source_sha256'].items(): check('tested source unchanged:' + name, sha(root / name) == digest)
for execution in results['executions']: check('test log digest:' + execution['name'], sha(root / execution['log']) == execution['log_sha256'])
head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
check('managed HEAD remains BASE_COMMIT', head == '9d895ecf335f2cc7770f7f89b328c48e5e3173d3')
report = {'result': 'PASS', 'head': head, 'g0_integration_commit_supplied_by_operator': 'bcdbb0dc51886bac63d378b494d7a444664d8f72', 'checks': checks, 'imported_baseline_files': imported}
(root / 'reports/J02/INPUT_AND_INTEGRITY.json').write_text(json.dumps(report, indent=2) + '\n')
print('PASS input/integrity checks=' + str(len(checks)) + '; API_MANIFEST files=' + str(len(api['files'])) + '; imported baseline files=' + str(len(imported)))
