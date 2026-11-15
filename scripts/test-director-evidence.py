#!/usr/bin/env python3
"""Regression tests for evidence binding, using a disposable copy of actual run inputs."""
from pathlib import Path
import json
import shutil
import subprocess
import sys
import tempfile

root=Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix='evidence-regression-',dir=root/'build/director') as tmp:
 fixture=Path(tmp)
 runs=json.loads((root/'build/director/executions.json').read_text())
 paths=['scripts/director-evidence.py','build/director/executions.json',*runs['compiled_source_sha256']]
 for path in paths:
  dest=fixture/path;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(root/path,dest)
 docs=fixture/'docs/worlddirector-v1';docs.mkdir(parents=True)
 sentinel=docs/'TEST_EVIDENCE.json';sentinel.write_text('unchanged\n')
 def invoke(valid,verify=True):
  result=subprocess.run([sys.executable,str(fixture/'scripts/director-evidence.py'),*(['--verify-only'] if verify else [])],capture_output=True,text=True)
  assert (result.returncode==0)==valid,result.stdout+result.stderr
  if not valid:assert 'tests stale:' in result.stderr,result.stderr
  assert sentinel.read_text()=='unchanged\n','evidence changed before validation'
 invoke(True)
 for source_root in ('src/director/java','src/directorTest/java','src/directorPlatform/java'):
  added=fixture/source_root/'UncompiledAddition.java';added.parent.mkdir(parents=True,exist_ok=True)
  added.write_text('final class UncompiledAddition {}\n')
  invoke(False,verify=False)  # Exercise the actual evidence-generation entry point.
  added.unlink()
  invoke(True)
 existing=fixture/next(iter(runs['compiled_source_sha256']))
 original=existing.read_bytes();existing.write_bytes(original+b'\n// changed after compilation\n')
 invoke(False,verify=False)
 existing.unlink();invoke(False,verify=False)
 existing.write_bytes(original);invoke(True)
print('PASS: added Java in all 3 source roots, changed/deleted sources reject evidence before writes; exact tested inventory accepted')
