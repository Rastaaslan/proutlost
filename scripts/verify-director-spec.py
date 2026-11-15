#!/usr/bin/env python3
"""Verify authentic inputs, then run the normative checker on an ignored writable copy."""
import argparse,hashlib,json,pathlib,shutil,subprocess,sys
p=argparse.ArgumentParser();p.add_argument('--spec',default='build/director-input/spec-1.0.0');a=p.parse_args()
root=pathlib.Path(__file__).resolve().parents[1];spec=(root/a.spec).resolve();out=root/'build/director';out.mkdir(parents=True,exist_ok=True)
manifest=json.loads((spec/'MANIFEST.json').read_text());assert len(manifest['files'])==39
files=sorted(p for p in spec.rglob('*') if p.is_file());assert len(files)==40
for entry in manifest['files']:
 relative=pathlib.PurePosixPath(entry['path']);assert not relative.is_absolute() and '..' not in relative.parts
 data=(spec/relative).read_bytes();assert len(data)==entry['bytes'] and hashlib.sha256(data).hexdigest()==entry['sha256'],relative
sources=json.loads((spec/'sources/SOURCE_SHA256.json').read_text())
for name,entry in sources.items():
 data=(spec/'sources'/name).read_bytes();assert len(data)==entry['bytes'] and hashlib.sha256(data).hexdigest()==entry['sha256'],name
copy=root/'build/director/spec-validation';copy.mkdir(parents=True,exist_ok=True)
for file in files:
 target=copy/file.relative_to(spec);target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(file,target)
result=subprocess.run([sys.executable,str(copy/'validation/validate_spec.py')],capture_output=True,text=True)
log='PASS: 40 authentic files, 39 MANIFEST entries, 2 source hashes\n'+result.stdout+result.stderr
(out/'schema.log').write_text(log);print(log,end='');sys.exit(result.returncode)
