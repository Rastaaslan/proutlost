#!/usr/bin/env python3
"""Static specification QA only. Does not implement or run a Minecraft Director.

The local evaluator implements exactly the JSON Schema keywords used here.
It is not an external Draft 2020-12 conformance certification or a game test.
"""
from pathlib import Path
import copy
import hashlib
import json
import math
import re
import sys
import uuid

ROOT = Path(__file__).resolve().parents[1]
KEYWORDS = {'$schema', '$id', '$ref', '$defs', 'type', 'properties', 'required',
            'additionalProperties', 'items', 'minItems', 'uniqueItems', 'oneOf',
            'anyOf', 'allOf', 'if', 'then', 'enum', 'const', 'minimum', 'maximum',
            'exclusiveMinimum', 'minLength', 'minProperties', 'propertyNames',
            'format', 'pattern'}

class Invalid(ValueError):
    pass

def ensure(ok, message):
    if not ok:
        raise Invalid(message)

def no_duplicates(pairs):
    result = {}
    for key, value in pairs:
        ensure(key not in result, 'duplicate JSON key: ' + key)
        result[key] = value
    return result

def load(path):
    return json.loads(path.read_text(encoding='utf-8'), object_pairs_hook=no_duplicates,
                      parse_constant=lambda x: (_ for _ in ()).throw(Invalid('nonfinite JSON: ' + x)))

def deref(root, ref):
    ensure(ref.startswith('#/'), 'only local JSON pointers supported: ' + ref)
    node = root
    for key in ref[2:].split('/'):
        key = key.replace('~1', '/').replace('~0', '~')
        ensure(key in node, 'missing schema reference: ' + ref)
        node = node[key]
    return node

def check_schema(node, root):
    if isinstance(node, bool):
        return
    ensure(isinstance(node, dict), 'schema node must be object')
    ensure(not set(node).difference(KEYWORDS), 'unsupported schema keyword: ' + str(set(node).difference(KEYWORDS)))
    if '$ref' in node:
        deref(root, node['$ref'])
    if 'pattern' in node:
        re.compile(node['pattern'])
    if 'required' in node:
        ensure(isinstance(node['required'], list) and len(set(node['required'])) == len(node['required']), 'invalid required')
    if 'type' in node:
        types = node['type'] if isinstance(node['type'], list) else [node['type']]
        ensure(all(x in {'object', 'array', 'string', 'number', 'integer', 'boolean', 'null'} for x in types), 'invalid type')
    for container in ('properties', '$defs'):
        for child in node.get(container, {}).values():
            check_schema(child, root)
    for key in ('items', 'additionalProperties', 'propertyNames', 'if', 'then'):
        if key in node:
            check_schema(node[key], root)
    for key in ('oneOf', 'anyOf', 'allOf'):
        for child in node.get(key, []):
            check_schema(child, root)

def matches(value, node, root):
    try:
        validate(value, node, root)
        return True
    except Invalid:
        return False

def validate(value, node, root=None, at='$'):
    root = root if root is not None else node
    if node is True:
        return
    if node is False:
        raise Invalid(at + ': disallowed')
    if '$ref' in node:
        validate(value, deref(root, node['$ref']), root, at)
    if 'const' in node:
        ensure(value == node['const'] and type(value) == type(node['const']), at + ': const mismatch')
    if 'enum' in node:
        ensure(any(value == x and type(value) == type(x) for x in node['enum']), at + ': enum mismatch')
    if 'type' in node:
        options = node['type'] if isinstance(node['type'], list) else [node['type']]
        tests = {'object': isinstance(value, dict), 'array': isinstance(value, list),
                 'string': isinstance(value, str), 'number': type(value) in (int, float),
                 'integer': type(value) is int or (type(value) is float and math.isfinite(value) and value.is_integer()),
                 'boolean': type(value) is bool, 'null': value is None}
        ensure(any(tests[x] for x in options), at + ': type mismatch')
    if type(value) in (int, float):
        ensure(math.isfinite(value), at + ': nonfinite')
        if 'minimum' in node:
            ensure(value >= node['minimum'], at + ': minimum')
        if 'maximum' in node:
            ensure(value <= node['maximum'], at + ': maximum')
        if 'exclusiveMinimum' in node:
            ensure(value > node['exclusiveMinimum'], at + ': exclusiveMinimum')
    if isinstance(value, str):
        if 'minLength' in node:
            ensure(len(value) >= node['minLength'], at + ': minLength')
        if 'pattern' in node:
            ensure(re.search(node['pattern'], value) is not None, at + ': pattern')
        if node.get('format') == 'uuid':
            try:
                ensure(str(uuid.UUID(value)) == value, at + ': uuid canonical format')
            except ValueError:
                raise Invalid(at + ': UUID')
    if isinstance(value, dict):
        ensure(all(x in value for x in node.get('required', [])), at + ': missing required field')
        ensure(len(value) >= node.get('minProperties', 0), at + ': minProperties')
        props = node.get('properties', {})
        for key, item in value.items():
            if 'propertyNames' in node:
                validate(key, node['propertyNames'], root, at + ':propertyName')
            validate(item, props.get(key, node.get('additionalProperties', True)), root, at + '.' + key)
    if isinstance(value, list):
        ensure(len(value) >= node.get('minItems', 0), at + ': minItems')
        if node.get('uniqueItems'):
            ensure(len({json.dumps(x, sort_keys=True, ensure_ascii=False) for x in value}) == len(value), at + ': uniqueItems')
        for i, item in enumerate(value):
            if 'items' in node:
                validate(item, node['items'], root, at + '[' + str(i) + ']')
    if 'anyOf' in node:
        ensure(any(matches(value, x, root) for x in node['anyOf']), at + ': anyOf')
    if 'oneOf' in node:
        ensure(sum(matches(value, x, root) for x in node['oneOf']) == 1, at + ': oneOf')
    for child in node.get('allOf', []):
        validate(value, child, root, at)
    if 'if' in node and matches(value, node['if'], root) and 'then' in node:
        validate(value, node['then'], root, at)

def resolve(value, values, refs):
    if isinstance(value, str) and value.startswith('@test:'):
        key = value[len('@test:'):]
        ensure(key in values, 'unresolved calibration: ' + key)
        refs.add(key)
        return values[key]
    if isinstance(value, dict):
        return {k: resolve(v, values, refs) for k, v in value.items()}
    if isinstance(value, list):
        return [resolve(v, values, refs) for v in value]
    return value

def numeric_only_schema(node):
    if isinstance(node, dict):
        if isinstance(node.get('pattern'), str) and node['pattern'].startswith('^@test:'):
            return False
        return {k: numeric_only_schema(v) for k, v in node.items()}
    if isinstance(node, list):
        return [numeric_only_schema(v) for v in node]
    return node

def semantic_event(event):
    roles = event['target']['roles']
    for name, role in roles.items():
        ensure(role['min'] <= role['max'], 'role range ' + name)
    steps = event['steps']
    lookup = {x['id']: x for x in steps}
    ensure(len(lookup) == len(steps), 'duplicate step ID')
    visiting, done = set(), set()
    def visit(id):
        ensure(id in lookup, 'unknown predecessor')
        ensure(id not in visiting, 'step cycle')
        if id in done:
            return
        visiting.add(id)
        for pred in lookup[id]['after']:
            visit(pred)
        visiting.remove(id)
        done.add(id)
    for id in lookup:
        visit(id)
    def ancestors(id):
        result = set()
        for pred in lookup[id]['after']:
            result.add(pred)
            result.update(ancestors(pred))
        return result
    for step in steps:
        args = step['args']
        for role in ([args['role']] if 'role' in args else args.get('roles', [])):
            ensure(role in roles, 'unknown role ' + role)
        ensure(step['delay_ticks'] < event['timing']['max_duration_ticks'], 'step delay beyond duration')
        if step['primitive'] == 'proutlost:landslide':
            ensure(event['safety']['risk'] == 'CONTROLLED_DANGER', 'danger declared SAFE')
            ensure(event['safety']['warning_profile'] is not None, 'danger missing warning profile')
            warnings = [lookup[x] for x in ancestors(step['id']) if lookup[x]['primitive'] == 'proutlost:warning']
            ensure(bool(warnings), 'danger has no preceding WARN')
            ensure(any(step['delay_ticks'] >= x['args']['minimum_lead_ticks'] for x in warnings), 'insufficient warning delay')
            ensure(event['budget']['mutation_units'] >= args['max_blocks'], 'mutation underbudget')
        if step['primitive'] == 'proutlost:fauna_migrate':
            ensure(event['budget']['entity_slots'] >= args['cohort_size'], 'entity underbudget')
        if step['primitive'] == 'proutlost:trace' and args['persistent']:
            ensure(event['completion']['persistence_probability'] == 1, 'persistent primitive with probabilistic deletion')

def semantic_chain(chain, events):
    states = set(chain['states'])
    ensure(chain['entry_state'] in states, 'chain entry missing')
    ensure(set(chain['terminal_states']) <= states, 'chain terminal missing')
    ids = [x['id'] for x in chain['transitions']]
    ensure(len(set(ids)) == len(ids), 'duplicate transition')
    graph = {x: [] for x in states}
    for transition in chain['transitions']:
        ensure(transition['from'] in states and transition['to'] in states, 'unknown chain state')
        ensure(transition['from'] not in chain['terminal_states'], 'terminal emits transition')
        ensure(transition['event_id'] is None or transition['event_id'] in events, 'unknown chain event')
        ensure(transition['to'] not in chain['terminal_states'] or transition['event_id'] is None, 'terminal starts event')
        graph[transition['from']].append(transition['to'])
    seen = set()
    def explore(state, path):
        if state in path:
            ensure(chain['mode'] == 'DYNAMIC', 'cycle in short arc')
            return
        if state in seen:
            return
        seen.add(state)
        for next_state in graph[state]:
            explore(next_state, path | {state})
    explore(chain['entry_state'], set())
    ensure(seen == states, 'unreachable chain state')

def semantic_import(data):
    regions = {x['region_id']: x for x in data['regions']}
    locations = {x['location_id']: x for x in data['locations']}
    anchors = {x['anchor_id']: x for x in data['anchors']}
    ensure(len(regions) == len(data['regions']) and len(locations) == len(data['locations']) and len(anchors) == len(data['anchors']), 'duplicate import ID')
    for item in data['locations']:
        ensure(set(item['region_ids']) <= set(regions), 'unresolved import region')
    for item in data['anchors']:
        ensure(item['location_id'] in locations, 'unresolved anchor location')
        target = locations[item['location_id']]
        ensure(target['dimension'] == item['dimension'], 'anchor dimension mismatch')
        for axis in 'xyz':
            ensure(target['bounds']['min_' + axis] <= item['position'][axis] < target['bounds']['max_' + axis + '_exclusive'], 'anchor outside bounds')
    for item in list(regions.values()) + list(locations.values()):
        ensure(all(item['bounds']['min_' + a] < item['bounds']['max_' + a + '_exclusive'] for a in 'xyz'), 'invalid import bounds')
    body = {k: v for k, v in data.items() if k != 'export_digest'}
    digest = hashlib.sha256(json.dumps(body, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
    ensure(digest == data['export_digest'], 'import digest mismatch')

def main():
    profile = load(ROOT / 'DEFAULT_FOR_TESTING.json')
    ensure(profile['status'] == 'DEFAULT_FOR_TESTING' and profile['production_approved'] is False, 'profile status')
    values = profile['values']
    refs = set()
    schemas = {p.name: load(p) for p in sorted((ROOT / 'schemas').glob('*.schema.json'))}
    for value in schemas.values():
        check_schema(value, value)
    events = {}
    positives = []
    for folder, filename in [('events', 'event-definition.schema.json'), ('chains', 'chain-definition.schema.json'),
                             ('ai', 'ai-proposal.schema.json'), ('locations', 'location-import.schema.json'),
                             ('messages', 'director-message.schema.json')]:
        for path in sorted((ROOT / 'examples' / folder).glob('*.json')):
            data = load(path)
            validate(data, schemas[filename])
            resolved = resolve(data, values, refs)
            numeric = numeric_only_schema(schemas[filename])
            validate(resolved, numeric)
            if folder == 'events':
                semantic_event(resolved)
                ensure(data['id'] not in events, 'duplicate event ID')
                events[data['id']] = resolved
            if folder == 'ai':
                ensure(all(abs(x['delta_log_weight']) <= values['ai.max_abs_log_delta'] for x in data['adjustments']), 'AI score bound')
            if folder == 'locations':
                semantic_import(data)
            positives.append(str(path.relative_to(ROOT)))
    for path in (ROOT / 'examples/chains').glob('*.json'):
        semantic_chain(resolve(load(path), values, refs), events)
    families = set().union(*(set(x['families']) for x in events.values()))
    ensure(families >= {'AMBIENCE', 'FAUNA', 'MEMORY', 'PERCEPTION', 'SOCIAL', 'SOLO', 'HUMOUR', 'DANGER', 'DISASTER', 'CHAIN'}, 'family coverage')
    ensure({x['category'] for x in events.values()} == {'AMBIENT', 'HYBRID', 'NARRATIVE'}, 'category coverage')
    ensure({x['uniqueness']['scope'] for x in events.values()} == {'REPEATABLE', 'ONCE_PER_PLAYER', 'ONCE_PER_REGION', 'ONCE_PER_WORLD'}, 'uniqueness coverage')
    negatives = []
    def reject(name, fn):
        try:
            fn()
        except (Invalid, ValueError):
            negatives.append(name)
        else:
            raise Invalid('negative fixture accepted: ' + name)
    def bad_event(name, id, mutation):
        sample = copy.deepcopy(events['proutlost:' + id])
        mutation(sample)
        reject(name, lambda: (validate(sample, numeric_only_schema(schemas['event-definition.schema.json'])), semantic_event(sample)))
    bad_event('unknown_event_field', 'safe_micro', lambda x: x.update({'command': 'arbitrary'}))
    bad_event('unknown_primitive', 'safe_micro', lambda x: x['steps'][0].update({'primitive': 'minecraft:command'}))
    bad_event('missing_duration', 'safe_micro', lambda x: x['timing'].pop('max_duration_ticks'))
    bad_event('missing_role', 'safe_micro', lambda x: x['steps'][0]['args'].update({'role': 'absent'}))
    bad_event('cyclic_steps', 'safe_micro', lambda x: x['steps'][0].update({'after': ['sound']}))
    bad_event('fractional_tick_parameter', 'safe_micro', lambda x: x['steps'][0].update({'delay_ticks': 1.5}))
    bad_event('missing_warn_step', 'controlled_landslide', lambda x: x['steps'][1].update({'after': []}))
    bad_event('insufficient_warn_gap', 'controlled_landslide', lambda x: x['steps'][1].update({'delay_ticks': 0}))
    bad_event('danger_declared_safe', 'controlled_landslide', lambda x: x['safety'].update({'risk': 'SAFE'}))
    bad_event('entity_underbudget', 'animal_migration', lambda x: x['budget'].update({'entity_slots': 0}))
    bad_event('mutation_underbudget', 'controlled_landslide', lambda x: x['budget'].update({'mutation_units': 0}))
    bad_event('persistent_trace_not_retained', 'required_signal', lambda x: x['completion'].update({'persistence_probability': 0}))
    reject('unknown_parameter', lambda: resolve('@test:missing.key', values, set()))
    reject('duplicate_json_keys', lambda: json.loads('{"x":1,"x":2}', object_pairs_hook=no_duplicates))
    wrong_ai = load(ROOT / 'examples/ai/proposal.json')
    wrong_ai['command'] = 'arbitrary'
    reject('AI_extra_command', lambda: validate(wrong_ai, schemas['ai-proposal.schema.json']))
    wrong_req = load(ROOT / 'examples/messages/require.json')
    wrong_req['payload']['bypass_safety'] = True
    reject('REQUIRE_safety_bypass_field', lambda: validate(wrong_req, schemas['director-message.schema.json']))
    wrong_chain = resolve(load(ROOT / 'examples/chains/camp_short_arc.json'), values, refs)
    wrong_chain['transitions'][0]['event_id'] = 'proutlost:unknown'
    reject('chain_unknown_event', lambda: semantic_chain(wrong_chain, events))
    wrong_import = load(ROOT / 'examples/locations/import.json')
    wrong_import['anchors'][0]['location_id'] = '00000000-0000-0000-0000-000000000000'
    reject('import_unknown_location', lambda: semantic_import(wrong_import))
    broken_links = []
    for path in ROOT.glob('*.md'):
        content = path.read_text(encoding='utf-8')
        ensure(content.count('```') % 2 == 0, 'unclosed fenced block: ' + path.name)
        for target in re.findall(r'(?<!!)\[[^\]]+\]\(([^)]+)\)', content):
            if '://' in target or target.startswith('#'):
                continue
            destination = target.split('#')[0]
            if destination and not (path.parent / destination).exists():
                broken_links.append(path.name + ' -> ' + target)
    ensure(not broken_links, 'broken links: ' + str(broken_links))
    mandatory = ['TECH_SPEC', 'ARCHITECTURE', 'DATA_MODEL', 'EVENT_CONTRACT', 'PERSISTENCE_AND_RECOVERY',
                 'PERFORMANCE', 'AI_LAYER', 'ADMIN_V1', 'TEST_PLAN', 'IMPLEMENTATION_PLAN']
    for name in mandatory:
        ensure((ROOT / ('WORLD_DIRECTOR_' + name + '.md')).is_file(), 'missing mandatory deliverable: ' + name)
    contract_text = (ROOT / 'WORLD_DIRECTOR_EVENT_CONTRACT.md').read_text(encoding='utf-8')
    for path in (ROOT / 'examples/events').glob('*.json'):
        ensure(path.read_text(encoding='utf-8').strip() in contract_text, 'full event example absent from Markdown: ' + path.name)
    report = {
        'status': 'PASS_STATIC_SPEC_CHECKS', 'scope': 'design artifacts only',
        'schema_engine': 'local evaluator for exactly the used JSON Schema keywords; no external conformance certification',
        'schema_count': len(schemas), 'positive_fixture_count': len(positives),
        'positive_fixtures': positives, 'negative_case_count': len(negatives), 'negative_cases_rejected': negatives,
        'calibration_keys': len(values), 'referenced_calibration_keys_in_fixtures': len(refs),
        'mandatory_document_count': len(mandatory), 'full_event_examples': len(events),
        'event_families': sorted(families), 'broken_local_links': broken_links,
        'runtime_tests': 'NOT_RUN', 'Minecraft_or_NeoForge_build': 'NOT_RUN',
        'performance_measurements': 'NOT_RUN', 'active_server_changes': 'NONE',
        'remaining_runtime_gates': ['G0 binary baseline', 'G1/G2 Java implementation', 'G3 dedicated server and clients',
                                    'G4 physical/crash/soak tests', 'G5 product calibration and delivery'],
    }
    (ROOT / 'validation/STATIC_VALIDATION_REPORT.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({k: report[k] for k in ['status', 'schema_count', 'positive_fixture_count', 'negative_case_count',
                                             'calibration_keys', 'mandatory_document_count', 'runtime_tests']}))

if __name__ == '__main__':
    try:
        main()
    except (Invalid, KeyError, TypeError) as exc:
        print('STATIC_VALIDATION_FAILED: ' + str(exc), file=sys.stderr)
        raise SystemExit(1)
