#!/usr/bin/env python3
"""Build NEW QA derivative v2 only; never install or mutate a server/world.

The exact official B05 Director entry bodies remain identical. The prior QA
test overlay is retained, replacing only the observer and its nested classes.
The separate addon must contain no Director package. Outputs are CREATE_NEW.
"""
from pathlib import Path
import argparse
import hashlib
import json
import zipfile

PREFIX = 'fr/proutlost/director/adapter/neoforge/server/DirectorNativePagedLocationImportGameTests'


def sha(path):
    result = hashlib.sha256()
    with path.open('rb') as data:
        for chunk in iter(lambda: data.read(65536), b''):
            result.update(chunk)
    return result.hexdigest()


def entries(path):
    with zipfile.ZipFile(path) as archive:
        if archive.testzip() is not None:
            raise RuntimeError('JAR CRC check failed: ' + str(path))
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise RuntimeError('Duplicate JAR entries: ' + str(path))
        return {name: archive.read(name) for name in names if not name.endswith('/')}


def observer(name):
    return name == PREFIX + '.class' or (name.startswith(PREFIX + '$') and name.endswith('.class'))


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--official-director', type=Path, required=True)
    p.add_argument('--official-sha256', required=True)
    p.add_argument('--prior-qa-director', type=Path, required=True)
    p.add_argument('--prior-qa-sha256', required=True)
    p.add_argument('--compiled-classes', type=Path, required=True)
    p.add_argument('--addon-jar', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    a = p.parse_args()
    if not a.output.name.endswith('-TEST_ONLY.jar'):
        raise RuntimeError('Explicit TEST_ONLY derivative output name required')
    if a.output.exists() or a.output.with_suffix('.provenance.json').exists():
        raise RuntimeError('Preserve existing derivative/provenance; choose a new output')
    if sha(a.official_director) != a.official_sha256 or sha(a.prior_qa_director) != a.prior_qa_sha256:
        raise RuntimeError('Exact installed B05 official/previous QA SHA required')
    official, prior, addon = entries(a.official_director), entries(a.prior_qa_director), entries(a.addon_jar)
    for name, body in official.items():
        if prior.get(name) != body:
            raise RuntimeError('Prior QA derivative changed an official entry: ' + name)
    if any(name.startswith('fr/proutlost/director/') for name in addon):
        raise RuntimeError('Split Director package forbidden in standalone addon')
    if any(name.startswith('fr/proutlost/p008qa/') for name in prior):
        raise RuntimeError('Bootstrap package must reside only in the separate addon')
    replacements = {}
    for path in sorted(a.compiled_classes.rglob('*.class')):
        name = path.relative_to(a.compiled_classes).as_posix()
        if observer(name):
            if name in official:
                raise RuntimeError('Observer unexpectedly belongs to official production JAR')
            replacements[name] = path.read_bytes()
    if PREFIX + '.class' not in replacements or not any('$InstalledRun.class' in name for name in replacements):
        raise RuntimeError('Compiled observer entry point and installed state required')
    packed = {name: body for name, body in prior.items() if not observer(name)}
    packed.update(replacements)
    if any(name.startswith('fr/proutlost/p008qa/') for name in packed):
        raise RuntimeError('Bootstrap package would be duplicated across modules')
    with zipfile.ZipFile(a.output, 'x', compression=zipfile.ZIP_DEFLATED) as archive:
        for name, body in sorted(packed.items()):
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            archive.writestr(info, body)
    result = entries(a.output)
    for name, body in official.items():
        if result.get(name) != body:
            raise RuntimeError('New QA derivative changed an official entry: ' + name)
    permitted = set(replacements) | {name for name in prior if observer(name)}
    changed = {name for name in set(prior) | set(result) if prior.get(name) != result.get(name)}
    if not changed <= permitted:
        raise RuntimeError('Change escaped the single TEST_ONLY observer family')
    receipt = dict(scope='TEST_ONLY_OBSERVER_OVERLAY_NOT_PRODUCTION_OR_CANONICAL_RECEPTION',
        official_director=str(a.official_director), official_sha256=a.official_sha256,
        prior_qa_director=str(a.prior_qa_director), prior_qa_sha256=a.prior_qa_sha256,
        qa_derivative=str(a.output), qa_derivative_sha256=sha(a.output),
        addon_jar=str(a.addon_jar), addon_sha256=sha(a.addon_jar),
        official_entry_count=len(official), official_entry_body_parity=True,
        official_class_count=sum(name.endswith('.class') for name in official),
        replaced_observer_entries={name: hashlib.sha256(body).hexdigest() for name, body in sorted(replacements.items())},
        changed_entries=sorted(changed), bootstrap_only_in_separate_addon=True,
        actual_runtime_qualification='NOT_RUN')
    with a.output.with_suffix('.provenance.json').open('x') as target:
        json.dump(receipt, target, indent=2)
        target.write('\n')
    print(json.dumps(receipt, indent=2))


if __name__ == '__main__':
    main()
