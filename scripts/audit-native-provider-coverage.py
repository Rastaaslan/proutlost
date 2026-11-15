"""Read-only J02 candidate vs actual J05 native *declarations* audit.

This is NOT a production provider registry, runtime binding, or game qualification.
A declared Java primitive is not necessarily instantiated, WAL-authorized, or tested.
"""
import hashlib
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
RESOURCE = ROOT / "src/director/resources"
MANIFEST = RESOURCE / "data/proutlost_director/director/candidate-manifest.json"
BINDINGS = ROOT / "src/director/java/fr/proutlost/director/adapter/neoforge/server/effects"


def main():
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    if manifest.get("status") != "CANDIDATE" or manifest.get("production_approved") is not False:
        raise ValueError("candidate status unexpectedly changed")
    events = {}
    capabilities = set()
    sha_evidence = {}
    for entry in manifest["definitions"]:
        path = RESOURCE / entry["resource"]
        raw = path.read_bytes()
        raw_hash = hashlib.sha256(raw).hexdigest()
        lf_hash = hashlib.sha256(raw.replace(b"\r\n", b"\n")).hexdigest()
        expected = entry["sha256"]
        if expected not in (raw_hash, lf_hash):
            raise ValueError("candidate manifest SHA mismatch: " + entry["resource"])
        sha_evidence[entry["definition_id"]] = {
            "expected": expected,
            "raw_matches": raw_hash == expected,
            "lf_matches": lf_hash == expected,
        }
        data = json.loads(raw.decode("utf-8"))
        if data.get("id") != entry["definition_id"]:
            raise ValueError("candidate identity substitution")
        if "/events/" in entry["resource"]:
            events[data["id"]] = sorted({step["primitive"] for step in data["steps"]})
            capabilities.update(data["safety"]["required_capabilities"])
    if len(events) != 9 or len(manifest["definitions"]) != 11:
        raise ValueError("incomplete nine events / two chains")
    needed = set().union(*(set(v) for v in events.values()))
    declared = {}
    cap_declarations = {}
    for path in sorted(BINDINGS.glob("*Binding.java")):
        source = path.read_text(encoding="utf-8")
        primitive_section = re.search(
            r"public Set<PrimitiveId> supportedPrimitives\(\)\s*\{\s*return Set\.of\((.*?)\);",
            source, re.S,
        )
        capability_section = re.search(
            r"public Set<CapabilityId> capabilities\(\)\s*\{\s*return Set\.of\((.*?)\);",
            source, re.S,
        )
        if not primitive_section or not capability_section:
            raise ValueError("native binding declaration not introspectable: " + path.name)
        primitives = set(re.findall(r'new PrimitiveId\("([^"]+)"\)', primitive_section.group(1)))
        binding_caps = set(re.findall(r'new CapabilityId\("([^"]+)"\)', capability_section.group(1)))
        constants = dict(re.findall(
            r'static final CapabilityId\s+(\w+)\s*=\s*new CapabilityId\("([^"]+)"\)', source))
        for token in capability_section.group(1).split(","):
            token = token.strip()
            if re.fullmatch(r"[A-Z_][A-Z_0-9]*", token):
                if token not in constants:
                    raise ValueError("unresolved capability constant: " + token)
                binding_caps.add(constants[token])
        # Names are static source declarations ONLY; this code never constructs a Binding.
        declared[path.stem] = sorted(primitives)
        cap_declarations[path.stem] = sorted(binding_caps)
    native_ids = set().union(*(set(v) for v in declared.values()))
    lifecycle = (ROOT / "src/directorPlatform/java/fr/proutlost/director/bootstrap/DirectorServerLifecycle.java").read_text(encoding="utf-8")
    if "DirectorBootstrap.compose(ProviderMode.PRODUCTION,List.of())" not in lifecycle:
        raise ValueError("production lifecycle changed; cannot assert zero installed bindings")
    report = {
        "kind": "SOURCE_DECLARATIONS_ONLY_NOT_RUNTIME_PROOF",
        "manifest_sha256_raw": hashlib.sha256(MANIFEST.read_bytes()).hexdigest(),
        "candidate_definition_shas": sha_evidence,
        "candidate_events": len(events),
        "candidate_chains": len(manifest["definitions"]) - len(events),
        "candidate_required_primitives": sorted(needed),
        "candidate_required_capabilities": sorted(capabilities),
        "native_binding_declarations": declared,
        "native_capability_declarations": cap_declarations,
        "matching_declarations": sorted(needed & native_ids),
        "missing_native_declarations": sorted(needed - native_ids),
        "events_affected_by_missing_native": {
            event: sorted(set(primitives) - native_ids)
            for event, primitives in sorted(events.items()) if set(primitives) - native_ids
        },
        "actually_installed_production_provider_count": 0,
        "actually_installed_source": "DirectorServerLifecycle.started composes PRODUCTION with empty roots",
        "runtime_qualification": "NOT_RUN",
        "admission": "DISABLED",
    }
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as failure:
        print("FAIL_CLOSED_PROVIDER_AUDIT: " + str(failure), file=sys.stderr)
        sys.exit(2)
