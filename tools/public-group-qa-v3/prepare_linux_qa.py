#!/usr/bin/env python3
"""Prepare NEW P008 QA server files only. No JVM/container/process is started.

Run by the sole integrator inside a new labelled container with the previous
mission volume mounted read-only at /referenceqa, and a NEW writable /workspace.
"""
from pathlib import Path
import argparse
import hashlib
import json
import os
import secrets
import shutil
import tomllib
import uuid
import zipfile


def sha(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def offline(name):
    return str(uuid.UUID(bytes=hashlib.md5(('OfflinePlayer:' + name).encode()).digest(), version=3))


def write_new(path, text, mode=0o644):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, mode)
    with os.fdopen(fd, 'w', encoding='utf-8', newline='\n') as stream:
        stream.write(text)
        stream.flush()
        os.fsync(stream.fileno())


def copy_tree(source, target):
    if not source.is_dir():
        return
    for path in source.rglob('*'):
        if path.is_symlink():
            raise RuntimeError('Symlink configuration input refused: ' + str(path))
    shutil.copytree(source, target)


def derivative(official, classes, resources, target):
    added = {}
    with zipfile.ZipFile(official) as source, zipfile.ZipFile(target, 'x', zipfile.ZIP_DEFLATED) as out:
        names = source.namelist()
        if len(names) != len(set(names)):
            raise RuntimeError('Duplicate production JAR entry')
        for item in source.infolist():
            out.writestr(item, source.read(item.filename))
        known = set(names)
        for root in [classes, *resources]:
            if not root.is_dir():
                if root == classes or root == resources[0]:
                    raise RuntimeError('Compiled TEST_ONLY classes/resources absent: ' + str(root))
                continue  # Optional platform test resources may have no source files.
            for path in sorted(root.rglob('*')):
                if not path.is_file():
                    continue
                name = path.relative_to(root).as_posix()
                if name in known:
                    continue  # Never replace a production entry/class body.
                data = path.read_bytes()
                out.writestr(name, data)
                added[name] = hashlib.sha256(data).hexdigest()
                known.add(name)
    required = 'fr/proutlost/director/adapter/neoforge/server/DirectorNativePagedLocationImportGameTests.class'
    if required not in added:
        raise RuntimeError('P008 observer class was not added to TEST_ONLY derivative')
    with zipfile.ZipFile(official) as source, zipfile.ZipFile(target) as out:
        for name in source.namelist():
            if source.read(name) != out.read(name):
                raise RuntimeError('QA derivative altered a production body: ' + name)
    return added


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--execution-id', required=True)
    p.add_argument('--template', type=Path, default=Path('/referenceqa/B18R1-regional-production-qa'))
    p.add_argument('--libraries', type=Path, required=True)
    p.add_argument('--build-root', type=Path, required=True)
    p.add_argument('--main-jar', type=Path, required=True)
    p.add_argument('--main-sha256', required=True)
    p.add_argument('--director-jar', type=Path, required=True)
    p.add_argument('--director-sha256', required=True)
    p.add_argument('--target', type=Path, required=True)
    p.add_argument('--discovery-only', action='store_true', help='Use frozen official JAR only; stage TEST_ONLY overlay after final build')
    a = p.parse_args()
    if not a.execution_id.startswith('P008-') or any(c not in 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-' for c in a.execution_id):
        raise RuntimeError('Explicit P008 execution identity required')
    target = a.target.absolute()
    if target.parent != Path('/workspace') or not target.name.startswith('P008-') or target.exists():
        raise RuntimeError('NEW /workspace/P008-* server root required; preserve existing roots')
    if not a.template.is_dir() or not a.libraries.is_dir():
        raise RuntimeError('Known installed runtime input missing; inspect exact read-only path')
    if sha(a.main_jar) != a.main_sha256 or sha(a.director_jar) != a.director_sha256:
        raise RuntimeError('Exact integrated production candidate SHA differs')
    if shutil.disk_usage('/workspace').free < 2 * 1024**3:
        raise RuntimeError('Less than 2 GiB free for NEW QA files')
    target.mkdir()
    frozen = target / 'official-inputs'
    frozen.mkdir()
    for original, expected, name in [(a.main_jar, a.main_sha256, 'proutlost-0.1.0.jar'),
            (a.director_jar, a.director_sha256, 'proutlost-director-1.0.0.jar')]:
        shutil.copy2(original, frozen / name)
        if sha(frozen / name) != expected:
            raise RuntimeError('Candidate changed during copy; frozen bytes do not match explicit input SHA')
        with zipfile.ZipFile(frozen / name) as package:
            if package.testzip() is not None:
                raise RuntimeError('Frozen official candidate ZIP CRC failure')
    a.main_jar, a.director_jar = frozen / 'proutlost-0.1.0.jar', frozen / 'proutlost-director-1.0.0.jar'
    write_new(target / 'P008_QA_OWNER.json', json.dumps({'ticket': 'PLS2-FULLSTACK-P0-008', 'execution_id': a.execution_id,
        'kind': 'NEW_SYNTHETIC_QA_SERVER_NO_RETAINED_WORLD_COPIED', 'template_read_only': str(a.template)}, indent=2) + '\n')
    (target / 'libraries').symlink_to(a.libraries.resolve(), target_is_directory=True)
    for name in ['config', 'defaultconfigs']:
        copy_tree(a.template / name, target / name)
    mods = target / 'mods'
    mods.mkdir()
    template_jars = list((a.template / 'mods').glob('*.jar'))
    if len(template_jars) < 40:
        raise RuntimeError('Fullpack template is unexpectedly incomplete')
    for jar in template_jars:
        if jar.name in ['proutlost-0.1.0.jar', 'proutlost-director-1.0.0.jar'] or 'P008-TEST_ONLY' in jar.name:
            continue
        shutil.copy2(jar, mods / jar.name)
    shutil.copy2(a.main_jar, mods / 'proutlost-0.1.0.jar')
    test_jar = mods / ('proutlost-director-1.0.0.jar' if a.discovery_only else 'proutlost-director-1.0.0-P008-TEST_ONLY.jar')
    if a.discovery_only:
        shutil.copy2(a.director_jar, test_jar)
        added = {}
    else:
        added = derivative(a.director_jar, a.build_root / 'build/classes/java/directorGameTest',
            [a.build_root / 'src/directorGameTest/resources', a.build_root / 'src/directorPlatform/gameTest/resources'], test_jar)
    (target / 'world').mkdir()
    write_new(target / 'world/TEST_ONLY', 'PROUTLOST_NEUTRAL_QA_V1\n')
    # QuestConfig is ModConfig.Type.SERVER and is loaded from this NEW world's
    # serverconfig directory. Its two top-level defaults are false, but old QA
    # defaultconfigs can explicitly enable both. Preserve those copied bytes
    # and install the exact neutral guard values before Minecraft can boot.
    # No SavedData, UUID, level.dat, campaign or other world file is copied.
    copied_configs = target / 'copied-template-config-inputs'
    copied_configs.mkdir()
    quest_sources = {}
    server_quest = a.template / 'world/serverconfig/proutlost-quest-server.toml'
    if server_quest.is_file():
        if server_quest.is_symlink():
            raise RuntimeError('Symlink Quest SERVER config input refused')
        raw = server_quest.read_bytes()
        (copied_configs / 'template-world-serverconfig-proutlost-quest-server.toml').write_bytes(raw)
        quest_sources['template_world_serverconfig_sha256'] = hashlib.sha256(raw).hexdigest()
    defaults = target / 'defaultconfigs'
    defaults.mkdir(exist_ok=True)
    copied_default = defaults / 'proutlost-quest-server.toml'
    if copied_default.is_file():
        raw = copied_default.read_bytes()
        (copied_configs / 'copied-defaultconfigs-proutlost-quest-server.toml').write_bytes(raw)
        quest_sources['copied_defaultconfigs_sha256'] = hashlib.sha256(raw).hexdigest()
    neutral_text = 'enabled=false\ntestCommands=false\n'
    staged = defaults / 'proutlost-quest-server.toml.P008-new.tmp'
    write_new(staged, neutral_text)
    os.replace(staged, copied_default)
    (target / 'world/serverconfig').mkdir()
    write_new(target / 'world/serverconfig/proutlost-quest-server.toml', neutral_text)
    for configured in [copied_default, target / 'world/serverconfig/proutlost-quest-server.toml']:
        actual = tomllib.loads(configured.read_text())
        if actual.get('enabled') is not False or actual.get('testCommands') is not False:
            raise RuntimeError('Exact Quest SERVER disabled neutral values required')
    for name in ['home', 'tmp', 'receipts']:
        (target / name).mkdir()
    eula = (a.template / 'eula.txt').read_text(encoding='utf-8')
    if 'eula=true' not in eula:
        raise RuntimeError('Existing accepted EULA not available')
    write_new(target / 'eula.txt', eula)
    secret = secrets.token_hex(32)
    write_new(target / 'rcon-private.json', json.dumps({'host': '127.0.0.1', 'port': 25584, 'password': secret}) + '\n', 0o600)
    layers = {'biome': 'minecraft:plains', 'layers': [{'block': 'minecraft:bedrock', 'height': 1},
        {'block': 'minecraft:dirt', 'height': 123}, {'block': 'minecraft:grass_block', 'height': 1}]}
    props = {'server-ip': '127.0.0.1', 'server-port': 25579, 'level-name': 'world', 'level-type': 'minecraft:flat',
        'generator-settings': json.dumps(layers, separators=(',', ':')), 'generate-structures': 'false',
        'level-seed': 180112026, 'online-mode': 'false', 'enforce-secure-profile': 'false', 'white-list': 'true',
        'enforce-whitelist': 'true', 'max-players': 4, 'view-distance': 4, 'simulation-distance': 4,
        'difficulty': 'peaceful', 'gamemode': 'creative', 'force-gamemode': 'true', 'spawn-protection': 0,
        'enable-rcon': 'true', 'rcon.port': 25584, 'rcon.password': secret, 'broadcast-rcon-to-ops': 'false',
        'enable-query': 'false', 'motd': 'P008 DECLARED SYNTHETIC TEST_ONLY QA', 'allow-flight': 'true'}
    write_new(target / 'server.properties', ''.join(f'{k}={v}\n' for k, v in props.items()), 0o600)
    identities = [{'uuid': offline(name), 'name': name} for name in ['QAAlpha', 'QABravo']]
    write_new(target / 'whitelist.json', json.dumps(identities, indent=2) + '\n')
    write_new(target / 'ops.json', json.dumps([{**identity, 'level': 4, 'bypassesPlayerLimit': True} for identity in identities], indent=2) + '\n')
    plan = {'execution_id': a.execution_id, 'server_root': str(target), 'actual_game_bind': '127.0.0.1:25579',
        'published_game_proxy': '127.0.0.1:25583', 'rcon_bind_expected': '127.0.0.1:25584; NOT PUBLISHED',
        'libraries_read_only': str(a.libraries.resolve()), 'build_root_read_only': str(a.build_root),
        'production_main_sha256': a.main_sha256, 'production_director_sha256': a.director_sha256,
        'test_only_director_jar': test_jar.name, 'test_only_director_sha256': sha(test_jar), 'added_test_entries': added,
        'mods': {jar.name: sha(jar) for jar in sorted(mods.glob('*.jar'))}, 'offline_qa_identities': identities,
        'qa_secret_files_excluded_from_delivery': ['rcon-private.json', 'server.properties'], 'world_copied': False,
        'quest_server_config': {'registration': 'ModConfig.Type.SERVER: proutlost-quest-server.toml',
            'spec_defaults': {'enabled': False, 'testCommands': False}, 'effective_preboot_values': {'enabled': False, 'testCommands': False},
            'configured_paths': ['defaultconfigs/proutlost-quest-server.toml', 'world/serverconfig/proutlost-quest-server.toml'],
            'preserved_copied_config_inputs': quest_sources, 'campaign_state_copied': False},
        'discovery_only': a.discovery_only, 'jvm_started': False, 'qualification': 'NOT_RUN'}
    write_new(target / 'P008_QA_PREPARATION.json', json.dumps(plan, indent=2) + '\n')
    print(json.dumps({k: v for k, v in plan.items() if k not in ['mods', 'added_test_entries']}, indent=2))


if __name__ == '__main__':
    main()
