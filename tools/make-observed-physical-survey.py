#!/usr/bin/env python3
"""Create one explicit request inside an operator-owned, already staged QA copy.

No Minecraft start, chunk read/load/generation, terrain edit or Director import.
The native command must independently verify every cell against loaded state and
Anvil. This request is an intention, never a physical qualification receipt.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import uuid


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def no_links(path):
    for current in [path, *path.parents]:
        if current.is_symlink():
            raise ValueError("survey paths must not contain symbolic links")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--world", required=True, type=Path)
    parser.add_argument("--seed", required=True, type=int)
    parser.add_argument("--source-world", required=True)
    parser.add_argument("--source-build", required=True)
    parser.add_argument("--revision", required=True)
    parser.add_argument("--scope", required=True, choices=["PHYSICAL_GEOMETRY_ONLY", "SYNTHETIC_QA"])
    parser.add_argument("--survey-id", type=uuid.UUID, default=None)
    parser.add_argument("--dimension", default="minecraft:overworld")
    parser.add_argument("--bounds", required=True, nargs=6, type=int, metavar=("MIN_X", "MIN_Y", "MIN_Z", "MAX_X", "MAX_Y", "MAX_Z"))
    parser.add_argument("--native-module", help="Exact relative worldprep-v2/structure-module/BATCH/placement-N directory; omit for an explicitly observed support")
    parser.add_argument("--tags", nargs="*", default=[], help="Explicit subset of checked native model tags; never inferred")
    args = parser.parse_args()
    world = args.world.absolute()
    no_links(world)
    if not world.is_dir() or world.resolve() != world:
        raise ValueError("an existing canonical absolute staged world path is required")
    for value in [args.source_world, args.source_build, args.dimension, *args.tags]:
        if not re.fullmatch(r"[a-z0-9_.-]+:[a-z0-9/._-]+", value):
            raise ValueError("noncanonical resource ID")
    marker = world / "worldprep-candidate.json"
    no_links(marker)
    raw_marker = marker.read_bytes()
    if len(raw_marker) > 4096:
        raise ValueError("candidate marker bound")
    declared = json.loads(raw_marker)
    if declared.get("schemaVersion") != 1 or declared.get("candidateDirectory") != str(world) or declared.get("offlineBaseline") is not True or declared.get("physicalSurveyScope") != args.scope:
        raise ValueError("candidate marker does not explicitly authorize this survey scope")
    a, b, c, x, y, z = args.bounds
    if a > x or b > y or c > z or not 0 < (x-a+1)*(y-b+1)*(z-c+1) <= 32768:
        raise ValueError("invalid/beyond-consumer survey geometry")
    geometry = [{"dimension_id": args.dimension, "min": [a, b, c], "max": [x, y, z]}]
    survey = args.survey_id or uuid.uuid4()
    target = {"location_id": "proutlost:observed_support/" + str(survey), "source_ref": "proutlost:observed_existing_cells", "geometry": geometry,
              "tags": [], "origin": "OBSERVED_EXISTING_CELLS", "module_directory": None, "module_record_sha256": None}
    if args.native_module:
        if not re.fullmatch(r"worldprep-v2/structure-module/[a-zA-Z0-9_-]+/placement-[0-9]+", args.native_module):
            raise ValueError("exact native module directory required")
        directory = world / args.native_module
        no_links(directory)
        path = directory / "director-native-location-candidate.json"
        raw_record = path.read_bytes()
        if len(raw_record) > 2*1024*1024:
            raise ValueError("native record bound")
        envelope = json.loads(raw_record)
        if envelope.get("version") != 1 or sha(envelope["payload"].encode("utf-8")) != envelope.get("sha256"):
            raise ValueError("native record envelope digest differs")
        record = json.loads(envelope["payload"])
        if record.get("schema") != 1 or record.get("status") != "PLACED_VERIFIED_NOT_DIRECTOR_IMPORTED":
            raise ValueError("native location is not physically/disk verified")
        if args.dimension != record["dimension"] or a < record["minX"] or b < record["minY"] or c < record["minZ"] or x >= record["maxXExclusive"] or y >= record["maxYExclusive"] or z >= record["maxZExclusive"]:
            raise ValueError("support extends beyond the native source geometry")
        if not set(args.tags) <= set(record["tags"]):
            raise ValueError("tags were not retained by the native source")
        target.update(location_id="proutlost:observed_support/" + str(uuid.UUID(record["locationId"])) + "/" + sha(canonical(geometry)),
                      source_ref=record["structureRegistryId"], tags=sorted(set(args.tags)), origin="VERIFIED_NATIVE_STRUCTURE_MODULE",
                      module_directory=args.native_module, module_record_sha256=sha(raw_record))
    elif args.tags:
        raise ValueError("observed-only support cannot claim native tags")
    request = {"schema_version": 1, "survey_id": str(survey), "source_world": args.source_world, "source_build": args.source_build, "revision": args.revision,
               "expected_seed": str(args.seed), "candidate_marker_sha256": sha(raw_marker), "scope": args.scope, "targets": [target]}
    payload = canonical(request)
    if len(payload) > 32768:
        raise ValueError("native request byte bound")
    destination = world / "worldprep-surveys/requests" / ("survey-" + str(survey) + ".json")
    no_links(destination)
    destination.parent.mkdir(parents=True, exist_ok=True)
    with destination.open("xb") as output:
        output.write(payload)
    print(json.dumps({"request": str(destination), "sha256": sha(payload), "survey": str(survey), "state": "INTENT_ONLY_NOT_OBSERVED_OR_IMPORTED",
                      "command": "proutlost worldprep physical survey-export " + destination.name}, indent=2))


if __name__ == "__main__":
    main()
