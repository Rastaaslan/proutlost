#!/usr/bin/env python3
"""Root-operated NEW PUBLIC_GROUP native QA orchestration; never a live deploy.

Uses the existing installed B07 main/Director harness, zero real clients and
the existing RCON/identity helpers. No J04/NBT injection or GameTest bypass.
"""
from pathlib import Path
import argparse, fcntl, json, os, re, secrets, shutil, subprocess, sys, time, tomllib, uuid, zipfile
from prepare_linux_qa import sha, write_new, copy_tree
from run_linux_qa import atomic, identity, load, require_owned, rcon

EXECUTION = 'P008-20261118T062924Z-root'
MAIN = 'proutlost-0.1.0.jar'
DIRECTOR = 'proutlost-director-1.0.0.jar'
MARKER = 'PROUTLOST_PUBLIC_GROUP_QA_V1'
CELL = '0 60 0'


def need(value, why):
    if not value:
        raise RuntimeError(why)


def lock(path, existing=False):
    if existing:
        need(path.is_file() and not path.is_symlink(), 'Existing effective launcher lock required')
    handle = path.open('r' if existing else 'a+')
    fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
    return handle


def no_listener():
    for source in [Path('/proc/net/tcp'), Path('/proc/net/tcp6')]:
        if source.exists():
            for row in source.read_text().splitlines()[1:]:
                fields = row.split()
                need(not (fields[3] == '0A' and int(fields[1].split(':')[1], 16) in [25579, 25584]), 'QA game/RCON port remains occupied; no new JVM')


def stopped(root):
    need(load(root / 'P008_QA_OWNER.json')['execution_id'] == EXECUTION, 'Original QA root owner differs')
    prior = root / 'server-process.json'
    if prior.exists():
        receipt = load(prior)
        try:
            actual = identity(receipt['pid'])
        except (FileNotFoundError, ProcessLookupError):
            actual = None
        except RuntimeError as failure:
            need('zombie' in str(failure), 'Process identity unresolved')
            actual = None
        need(actual != receipt['identity'], 'Exact previous QA JVM remains active')
    return lock(root / 'P008_QA_LAUNCH.lock', existing=True)


def root_path(path):
    need(path.is_absolute() and path.parent == Path('/workspace') and not path.is_symlink()
        and re.fullmatch(r'P008-[A-Za-z0-9_-]{1,120}', path.name), 'Distinct /workspace/P008-* root required')
    return path


def prepare(root, args):
    need(not root.exists(), 'NEW root required; preserve all existing worlds and receipts')
    previous = args.previous_root.resolve()
    previous_lease = stopped(previous)
    port_lease = lock(Path('/workspace/P008_QA_PORT_25579.lock'))
    no_listener()
    need(args.template.is_dir() and args.libraries.is_dir(), 'Existing runtime/config inputs required')
    libraries = args.libraries.resolve()
    need(str(libraries).startswith('/referenceqa/') and os.statvfs(libraries).f_flag & os.ST_RDONLY, 'Libraries must remain on the existing readonly reference mount')
    need(shutil.disk_usage('/workspace').free >= 2 * 1024**3, 'NEW QA requires2GiB free')
    for pin in [args.main_sha256, args.director_sha256, args.source_sha256]:
        need(re.fullmatch(r'[a-f0-9]{64}', pin), 'Explicit root-verified B07 hashes required')
    need(sha(args.main_jar) == args.main_sha256 and sha(args.director_jar) == args.director_sha256, 'Frozen B07 binary SHA mismatch')
    dependencies = load(Path(__file__).with_name('dependencies-server-50.json'))['dependencies']
    need(len(dependencies) == 50, 'Observed50server-dependency cohort required')
    for name, pin in dependencies.items():
        source = args.dependency_directory / name
        need(source.is_file() and not source.is_symlink() and sha(source) == pin, 'Original dependency differs: ' + name)
    root.mkdir()
    owner = dict(ticket='PLS2-FULLSTACK-P0-008', execution_id=EXECUTION, kind='NEW_PUBLIC_GROUP_ZERO_CLIENT_TEST_ONLY', previous_root_read_only=str(previous))
    write_new(root / 'P008_QA_OWNER.json', json.dumps(owner, indent=2) + '\n')
    for name in ['mods', 'official-inputs-B07-INCOMPLETE', 'world/serverconfig', 'home', 'tmp', 'receipts', 'copied-config-inputs']:
        (root / name).mkdir(parents=True)
    (root / 'libraries').symlink_to(libraries, target_is_directory=True)
    for name in ['config', 'defaultconfigs']:
        copy_tree(args.template / name, root / name)
    (root / 'defaultconfigs').mkdir(exist_ok=True)
    for filename in ['defaultconfigs/proutlost-quest-server.toml', 'world/serverconfig/proutlost-quest-server.toml']:
        target = root / filename
        if target.exists():
            shutil.copy2(target, root / 'copied-config-inputs' / filename.replace('/', '-'))
            target.unlink()  # Only a copied config in this NEW root, preserved above.
        write_new(target, 'enabled=false\ntestCommands=false\n')
    for name, source, pin, required in [(MAIN, args.main_jar, args.main_sha256, 'fr/proutlost/quest/qa/PublicGroupQaRuntime.class'),
            (DIRECTOR, args.director_jar, args.director_sha256, 'fr/proutlost/director/bootstrap/DirectorPublicGroupQaFence.class')]:
        frozen = root / 'official-inputs-B07-INCOMPLETE' / name
        shutil.copy2(source, frozen)
        need(sha(frozen) == pin, 'Frozen binary changed while copying')
        with zipfile.ZipFile(frozen) as package:
            need(package.testzip() is None and required in package.namelist(), 'B07 native harness entry or valid archive missing')
        shutil.copy2(frozen, root / 'mods' / name)
    for name in dependencies:
        shutil.copy2(args.dependency_directory / name, root / 'mods' / name)
    eula = (args.template / 'eula.txt').read_text()
    need('eula=true' in eula, 'Existing accepted EULA required')
    write_new(root / 'eula.txt', eula)
    write_new(root / 'world/PUBLIC_GROUP_TEST_ONLY', MARKER + '\n')
    write_new(root / 'whitelist.json', '[]\n')
    write_new(root / 'ops.json', '[]\n')
    secret = secrets.token_hex(32)
    write_new(root / 'rcon-private.json', json.dumps(dict(host='127.0.0.1', port=25584, password=secret)) + '\n', 0o600)
    layers = dict(biome='minecraft:plains', layers=[dict(block='minecraft:bedrock', height=1), dict(block='minecraft:dirt', height=123), dict(block='minecraft:grass_block', height=1)])
    properties = {'server-ip': '127.0.0.1', 'server-port': 25579, 'level-name': 'world', 'level-type': 'minecraft:flat',
        'generator-settings': json.dumps(layers, separators=(',', ':')), 'generate-structures': 'false', 'level-seed': 180112026,
        'online-mode': 'false', 'enforce-secure-profile': 'false', 'white-list': 'true', 'enforce-whitelist': 'true',
        'max-players': 1, 'view-distance': 3, 'simulation-distance': 3, 'difficulty': 'peaceful', 'gamemode': 'creative',
        'spawn-protection': 0, 'enable-rcon': 'true', 'rcon.port': 25584, 'rcon.password': secret,
        'broadcast-rcon-to-ops': 'false', 'enable-query': 'false', 'motd': 'P008 PUBLIC_GROUP B07 INCOMPLETE TEST_ONLY'}
    write_new(root / 'server.properties', ''.join(f'{k}={v}\n' for k, v in properties.items()), 0o600)
    inventory = {p.name: sha(p) for p in sorted((root / 'mods').glob('*.jar'))}
    need(len(inventory) == 52, 'Exact52server-mod-file cohort required')
    plan = dict(owner=owner, status='PREPARED_NOT_STARTED_NOT_QUALIFIED', verdict='B07_COMPILED_INCOMPLETE_ARCHITECTURE_GATE_FAILED',
        server_root=str(root), previous_root=str(previous), source_sha256=args.source_sha256,
        main_jar_sha256=args.main_sha256, director_jar_sha256=args.director_sha256, mods=inventory, seed=180112026,
        native_clients=0, whitelist=[], physical_cell=CELL, physical_setup='IF_ALREADY_LOADED_PIN_THEN_GRASS_TO_STONE_BEFORE_PRODUCE_ONLY',
        libraries_readonly=str(libraries), world_copied=False, old_data_or_UUID_copied=False,
        secret_files_not_delivery=['rcon-private.json', 'server.properties'])
    write_new(root / 'P008_PUBLIC_GROUP_PREPARATION.json', json.dumps(plan, indent=2) + '\n')
    write_new(root / 'B07_INCOMPLETE_NOTICE.txt', 'B07 compiled candidate only; canonical architecture gate failed. No fullstack acceptance, deployment or human reception.\n')
    print(json.dumps({k: v for k, v in plan.items() if k != 'mods'}, indent=2))
    port_lease.close(); previous_lease.close()


def guard(root):
    plan = load(root / 'P008_PUBLIC_GROUP_PREPARATION.json')
    need(plan['owner']['execution_id'] == EXECUTION and plan['server_root'] == str(root), 'Exact new public GROUP owner/root required')
    need((root / 'world/PUBLIC_GROUP_TEST_ONLY').read_text().strip() == MARKER and not (root / 'world/TEST_ONLY').exists(), 'Distinct public marker required; neutral marker forbidden')
    for name in ['proutlost_quest_v1.dat', 'proutlost_quest_neutral_qa_v1.dat']:
        need(not (root / 'world/data' / name).exists(), 'Canonical/neutral SavedData present; preserve and stop')
    need(load(root / 'whitelist.json') == [] and load(root / 'ops.json') == [], 'Zero-client closed whitelist and console-only QA required')
    for filename in ['defaultconfigs/proutlost-quest-server.toml', 'world/serverconfig/proutlost-quest-server.toml']:
        config = tomllib.loads((root / filename).read_text())
        need(config.get('enabled') is False and config.get('testCommands') is False, 'Quest SERVER config must remainfalse/false')
    inventory = {p.name: sha(p) for p in (root / 'mods').glob('*.jar')}
    need(inventory == plan['mods'] and len(inventory) == 52, 'Frozen B07/50server-dependency inventory changed')
    properties = dict(line.split('=', 1) for line in (root / 'server.properties').read_text().splitlines() if '=' in line and not line.startswith('#'))
    need(properties.get('server-ip') == '127.0.0.1' and properties.get('server-port') == '25579'
        and properties.get('white-list') == 'true' and properties.get('rcon.port') == '25584', 'Exact loopback QA listeners/config required')
    return plan


def launch_flags(root, plan):
    return ['-Xms512m', '-Xmx2560m', '-XX:ActiveProcessorCount=2', '-Djava.net.preferIPv4Stack=true',
        '-Djava.io.tmpdir=' + str(root / 'tmp'), '-Duser.home=' + str(root / 'home'),
        '-Dproutlost.director.nativeLab=true', '-Dproutlost.director.nativeGameTests=false', '-Dneoforge.enableGameTest=false',
        '-Dproutlost.quest.neutralLab=false', '-Dproutlost.quest.realGroupClientsQa=false',
        '-Dproutlost.director.p008InstalledQaObserver=false', '-Dproutlost.quest.publicGroupQa=true',
        '-Dproutlost.quest.publicGroupQaRoot=' + str(root / 'world'),
        '-Dproutlost.quest.publicGroupQaSourcesSha256=' + plan['source_sha256'],
        '-Dproutlost.quest.publicGroupQaMainJar=' + str(root / 'mods' / MAIN),
        '-Dproutlost.quest.publicGroupQaDirectorJar=' + str(root / 'mods' / DIRECTOR)]


def launch(root, phase):
    plan = guard(root)
    previous_lease = stopped(Path(plan['previous_root']))
    port_lease = lock(Path('/workspace/P008_QA_PORT_25579.lock'))
    lease = lock(root / 'P008_QA_LAUNCH.lock')
    if (root / 'server-process.json').exists():
        old = require_stopped_public(root)
        need(old.get('exit') == 0, 'Prior public GROUP JVM did not close normally; diagnose before reopening')
    no_listener()
    memory = dict((line.split(':')[0], int(line.split()[1]) * 1024) for line in Path('/proc/meminfo').read_text().splitlines() if len(line.split()) >= 2 and line.split()[1].isdigit())
    need(memory.get('MemAvailable', 0) >= 3584 * 1024**2, 'Actual VM needs3.5GiB available')
    maximum = Path('/sys/fs/cgroup/memory.max').read_text().strip()
    need(maximum != 'max' and int(maximum) - int(Path('/sys/fs/cgroup/memory.current').read_text()) >= 3584 * 1024**2, 'Bounded QA container needs3.5GiB headroom')
    produced = root / 'world/public-group-qa/produce.json'
    if phase == 'produce':
        need(not (root / 'world/public-group-qa').exists() and not (root / 'world/data/proutlost_quest_public_group_qa_v1.dat').exists(), 'Preserve existing/failed public GROUP run; no produce reset')
    else:
        need(produced.is_file() and (root / 'world/data/proutlost_quest_public_group_qa_v1.dat').is_file(), 'Paired original produce/SavedData required')
    flags = launch_flags(root, plan)
    stamp = str(time.time_ns()); args = root / ('jvm-public-group-' + phase + '-' + stamp + '.args')
    write_new(args, '\n'.join(flags) + '\n')
    fifo = root / 'console'
    if not fifo.exists(): os.mkfifo(fifo, 0o600)
    fd = os.open(fifo, os.O_RDWR)
    environment = dict(os.environ)
    for name in ['CLASSPATH', 'MOD_CLASSES', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS']:
        environment.pop(name, None)
    logfile = root / ('server-public-group-' + phase + '-' + stamp + '.log')
    with logfile.open('xb') as output:
        process = subprocess.Popen(['java', '@' + str(args), '@libraries/net/neoforged/neoforge/21.1.248/unix_args.txt', 'nogui'],
            cwd=root, stdin=fd, stdout=output, stderr=subprocess.STDOUT, env=environment)
        receipt = dict(pid=process.pid, identity=identity(process.pid), phase=phase, log=str(logfile), args=str(args),
            exit_receipt=str(root / ('server-exit-' + stamp + '.json')), status='STARTED_NOT_YET_READY_OR_QUALIFIED', proxy_started=False)
        atomic(root / 'server-process.json', receipt); print(json.dumps(receipt), flush=True)
        result = process.wait()
    atomic(Path(receipt['exit_receipt']), dict(pid=process.pid, identity=receipt['identity'], phase=phase, exit=result, log=str(logfile)))
    os.close(fd); lease.close(); port_lease.close(); previous_lease.close()
    return result


def require_stopped_public(root):
    old = load(root / 'server-process.json')
    try: actual = identity(old['pid'])
    except (FileNotFoundError, ProcessLookupError): actual = None
    need(actual != old['identity'], 'Public GROUP JVM still active')
    need(Path(old['exit_receipt']).is_file(), 'Owned launcher exit receipt not yet observed')
    return load(old['exit_receipt'])


def native_run(root, phase):
    plan = guard(root); process = require_owned(root)
    target = root / 'receipts' / ('public-group-' + phase + '-' + str(time.time_ns()) + '.json')
    operation = dict(phase=phase, status='NATIVE_COMMANDS_NOT_YET_SENT', commands=[])
    write_new(target, json.dumps(operation, indent=2) + '\n')
    try:
        _native_run(root, phase, operation, target, plan, process)
    except Exception as failure:
        operation.update(status='NATIVE_OPERATION_FAILED_PRESERVE_WORLD_AND_PROCESS', error=str(failure))
        atomic(target, operation)
        raise


def _native_run(root, phase, operation, target, plan, process):
    need(process['phase'] == phase, 'Command phase differs from this new JVM launch phase')
    response = rcon(root, 'list')
    need(re.search(r'There are 0 of a max of \d+ players online', response), 'Actual zero-client server required')
    operation.update(server_identity=process['identity'], scope='DECLARED_SYNTHETIC_GROUP_ACTORS_NATIVE_SINGLE_CELL_ZERO_CLIENTS')
    atomic(target, operation)
    def execute(text):
        operation['commands'].append(dict(command=text, response=None, status='BEFORE_SEND'))
        atomic(target, operation)
        reply = rcon(root, text)
        operation['commands'][-1].update(response=reply, status='RESPONSE_RECEIVED')
        atomic(target, operation)
        return reply
    def physical(block):
        nonce = 'P008_PUBLIC_GROUP_' + uuid.uuid4().hex
        need(nonce in execute('execute if loaded ' + CELL + ' if block ' + CELL + ' minecraft:' + block + ' run say ' + nonce), 'Actual loaded physical block differs: ' + block)
    before_reopen = set((root / 'world/public-group-qa').glob('reopen-*.json')) if phase == 'reopen' else set()
    if phase == 'produce':
        need(not (root / 'world/public-group-qa').exists(), 'Existing produce attempt must be preserved')
        physical('grass_block')
        execute('execute if loaded ' + CELL + ' run forceload add 0 0')
        execute('execute if loaded ' + CELL + ' if block ' + CELL + ' minecraft:grass_block run setblock ' + CELL + ' minecraft:stone')
        physical('stone')
        execute('save-all flush')
        execute('proutlost public_group_qa produce ' + CELL)
    else:
        physical('moss_block')
        execute('proutlost public_group_qa reopen')
    deadline = time.monotonic() + 240
    while time.monotonic() < deadline:
        status = rcon(root, 'proutlost public_group_qa status')
        operation['last_native_status'] = status
        atomic(target, operation)
        print(json.dumps(dict(phase=phase, native_status=status)), flush=True)
        need('failure=NONE' in status, 'Actual public GROUP harness failed or did not start')
        if 'done=true' in status: break
        time.sleep(1)
    else: raise RuntimeError('Public GROUP native completion timeout; preserve running process and logs')
    physical('moss_block')
    receipts = [root / 'world/public-group-qa/produce.json'] if phase == 'produce' else list(set((root / 'world/public-group-qa').glob('reopen-*.json')) - before_reopen)
    need(len(receipts) == 1 and receipts[0].is_file() and receipts[0].stat().st_size <= 65536, 'One NEW native receipt required')
    receipt = load(receipts[0])
    for key, expected in dict(world_path=str(root / 'world'), wal_path=str(root / 'world/public-group-qa/wal'),
            source_sha256=plan['source_sha256'], main_jar_sha256=plan['main_jar_sha256'], director_jar_sha256=plan['director_jar_sha256'],
            native_clients=0, synthetic_group_actors=3, physical_post='minecraft:moss_block').items():
        need(receipt[key] == expected, 'Actual native receipt identity differs: ' + key)
    need(receipt['process'].startswith(str(process['pid']) + '@'), 'Native receipt belongs to another JVM')
    need((root / 'world/data/proutlost_quest_public_group_qa_v1.dat').is_file(), 'Actual Quest SavedData missing')
    if phase == 'reopen':
        original = load(root / 'world/public-group-qa/produce.json')
        for key in ['world_id', 'quest_core_sha256', 'ownership_record_sha256', 'fixture_sha256', 'fact_id', 'event_id', 'ownership_id']:
            need(receipt[key] == original[key], 'Cold original state/identity differs: ' + key)
        need(receipt['wal_boot_epoch'] > original['wal_boot_epoch'] and receipt['process'] != original['process']
            and receipt['wal_authority'] != original['wal_authority'] and receipt['producing_process'] == original['process'], 'Genuine new-JVM private J04 boot/session required')
    operation.update(status='NATIVE_' + phase.upper() + '_RECEIPT_AND_PHYSICAL_POST_VERIFIED', native_receipt=str(receipts[0]), native_receipt_sha256=sha(receipts[0]),
        quest_saved_data_sha256=sha(root / 'world/data/proutlost_quest_public_group_qa_v1.dat'))
    atomic(target, operation); print(json.dumps(operation), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument('--root', type=Path, required=True)
    sub = parser.add_subparsers(dest='action', required=True)
    setup = sub.add_parser('prepare')
    for name in ['previous-root', 'template', 'libraries', 'dependency-directory', 'main-jar', 'director-jar']:
        setup.add_argument('--' + name, type=Path, required=True)
    for name in ['main-sha256', 'director-sha256', 'source-sha256']:
        setup.add_argument('--' + name, required=True)
    start = sub.add_parser('launch'); start.add_argument('--phase', choices=['produce', 'reopen'], required=True)
    for name in ['produce', 'reopen', 'status', 'stop']: sub.add_parser(name)
    args = parser.parse_args(); root = root_path(args.root)
    if args.action == 'prepare': prepare(root, args); return 0
    if args.action == 'launch': return launch(root, args.phase)
    if args.action in ['produce', 'reopen']: native_run(root, args.action); return 0
    guard(root); require_owned(root)
    if args.action == 'status': print(rcon(root, 'proutlost public_group_qa status')); return 0
    print(rcon(root, 'save-all flush'), flush=True)
    descriptor = os.open(root / 'console', os.O_WRONLY | os.O_NONBLOCK)
    try: os.write(descriptor, b'stop\n')
    finally: os.close(descriptor)
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        try:
            exit_receipt = require_stopped_public(root)
            need(exit_receipt['exit'] == 0, 'Owned JVM ended abnormally; preserve diagnostics')
            print(json.dumps(exit_receipt), flush=True); return 0
        except (FileNotFoundError, RuntimeError): time.sleep(.2)
    raise RuntimeError('Owned JVM stop not yet observed; do not kill or relaunch automatically')


if __name__ == '__main__':
    raise SystemExit(main())
