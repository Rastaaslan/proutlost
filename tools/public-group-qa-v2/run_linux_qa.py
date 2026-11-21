#!/usr/bin/env python3
"""P008 owned QA launcher/control. Operates only its NEW prepared server root.

Subcommands start processes only when explicitly selected by the sole integrator.
Never opens another J04 store. RCON is private loopback, never published.
"""
from pathlib import Path
import argparse
import fcntl
import gzip
import hashlib
import json
import os
import re
import selectors
import signal
import socket
import struct
import subprocess
import sys
import threading
import time
import tomllib
import uuid


def load(path):
    return json.loads(Path(path).read_text())


def atomic(path, value):
    temporary = path.with_name(path.name + '.' + str(os.getpid()) + '.tmp')
    with temporary.open('x') as stream:
        stream.write(json.dumps(value, indent=2) + '\n')
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, path)


def identity(pid):
    p = Path('/proc') / str(pid)
    text = (p / 'stat').read_text()
    fields = text[text.rfind(')') + 2:].split()
    raw = (p / 'cmdline').read_bytes()
    if fields[0] == 'Z':
        raise RuntimeError('Owned process is a zombie, not an active JVM')
    return {'pid': pid, 'start_ticks': fields[19], 'cwd': os.readlink(p / 'cwd'),
        'executable': os.readlink(p / 'exe'), 'command_line_sha256': hashlib.sha256(raw).hexdigest()}


def require_owned(root):
    prior = load(root / 'server-process.json')
    actual = identity(prior['pid'])
    if actual != prior['identity'] or actual['cwd'] != str(root):
        raise RuntimeError('Exact owned QA process identity no longer matches; no command sent')
    return prior


def relay(root):
    # Only the game stream is proxied. Actual Minecraft and RCON bind loopback.
    listener = socket.socket()
    listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    listener.bind(('0.0.0.0', 25583))
    listener.listen(8)
    slots = threading.BoundedSemaphore(8)
    def connection(client):
        try:
            with client, socket.create_connection(('127.0.0.1', 25579), timeout=10) as server:
                client.setblocking(False)
                server.setblocking(False)
                with selectors.DefaultSelector() as select:
                    select.register(client, selectors.EVENT_READ, server)
                    select.register(server, selectors.EVENT_READ, client)
                    while True:
                        for key, _ in select.select(30):
                            data = key.fileobj.recv(65536)
                            if not data:
                                return
                            target = key.data
                            target.setblocking(True)
                            target.settimeout(15)
                            target.sendall(data)
                            target.setblocking(False)
        except (OSError, TimeoutError):
            pass
        finally:
            slots.release()
    while True:
        client, _ = listener.accept()
        if slots.acquire(blocking=False):
            threading.Thread(target=connection, args=(client,), daemon=True).start()
        else:
            client.close()


def rcon_local_binding(port):
    matches = []
    for name in ['/proc/net/tcp', '/proc/net/tcp6']:
        if not Path(name).exists():
            continue
        for line in Path(name).read_text().splitlines()[1:]:
            fields = line.split()
            address, value = fields[1].split(':')
            if int(value, 16) == port and fields[3] == '0A':
                matches.append(address)
    if not matches or any(value not in ['0100007F', '00000000000000000000000001000000'] for value in matches):
        raise RuntimeError('Actual RCON listener must be observed on loopback only')


def rcon(root, command):
    require_owned(root)
    secret = load(root / 'rcon-private.json')
    rcon_local_binding(secret['port'])
    def receive(stream):
        def exact(length):
            result = bytearray()
            while len(result) < length:
                part = stream.recv(length - len(result))
                if not part:
                    raise RuntimeError('RCON ended before complete response')
                result.extend(part)
            return bytes(result)
        length, = struct.unpack('<i', exact(4))
        if not 10 <= length <= 1048576:
            raise RuntimeError('Bounded RCON packet required')
        data = exact(length)
        request, kind = struct.unpack('<ii', data[:8])
        return request, kind, data[8:-2].decode('utf-8', errors='replace')
    def send(stream, request, kind, value):
        body = struct.pack('<ii', request, kind) + value.encode() + b'\0\0'
        stream.sendall(struct.pack('<i', len(body)) + body)
    with socket.create_connection(('127.0.0.1', secret['port']), timeout=10) as stream:
        stream.settimeout(10)
        send(stream, 812, 3, secret['password'])
        while True:
            request, kind, _ = receive(stream)
            if request == -1:
                raise RuntimeError('Private QA RCON authentication refused')
            if request == 812 and kind == 2:
                break
        send(stream, 813, 2, command)
        request, _, text = receive(stream)
        if request != 813:
            raise RuntimeError('RCON command correlation changed')
        return text


def native_entry(root, name, transfer):
    expected = next(value for value in load(root / 'whitelist.json') if value['name'] == name)
    op = next(value for value in load(root / 'ops.json') if value['uuid'] == expected['uuid'])
    if op['level'] != 4 or not re.fullmatch(r'[a-z0-9][a-z0-9_-]{0,54}_transfer', transfer):
        raise RuntimeError('Exact real OP4 QA identity and transfer name required')
    response = rcon(root, 'data get entity ' + name + ' UUID')
    match = re.search(r'\[I;\s*([^\]]+)\]', response)
    numbers = list(map(int, re.findall(r'-?\d+', match.group(1)))) if match else []
    if len(numbers) != 4:
        raise RuntimeError('Actual connected QA player UUID was not observed')
    observed = uuid.UUID(int=sum((number & 0xffffffff) << (96 - 32 * index) for index, number in enumerate(numbers)))
    if str(observed) != expected['uuid']:
        raise RuntimeError('Actual connected QA player UUID differs')
    command = 'execute as ' + name + ' at @s run proutlost director lab locations import ' + transfer
    reply = rcon(root, command)
    receipt = {'utc': time.time(), 'action': 'ACTUAL_NATIVE_IMPORT_COMMAND_SENT_NOT_YET_DURABLE',
        'command': command, 'connected_player': name, 'observed_uuid': str(observed), 'op_level': op['level'],
        'server_identity': require_owned(root)['identity'], 'actual_command_response': reply,
        'qualification': 'PENDING_ACTUAL_J04_AND_GAME_READER_EVIDENCE'}
    path = root / 'receipts' / ('native-entry-' + str(time.time_ns()) + '.json')
    with path.open('x') as stream:
        stream.write(json.dumps(receipt, indent=2) + '\n')
        stream.flush()
        os.fsync(stream.fileno())
    print(json.dumps(receipt, indent=2))


def observer_edge_flags(phase, readback_run_id='', pending_transfer='', pending_boundary=''):
    if readback_run_id and (phase != 'cold' or not re.fullmatch(r'[a-z0-9_-]{1,48}', readback_run_id)):
        raise RuntimeError('Bounded --readback-run-id is supported only in cold mode')
    if bool(pending_transfer) != bool(pending_boundary):
        raise RuntimeError('--pending-transfer and --pending-boundary must be supplied together')
    if pending_transfer and (phase != 'cold' or not readback_run_id
            or not re.fullmatch(r'[a-z0-9][a-z0-9_-]{0,54}_transfer', pending_transfer)
            or pending_boundary not in ['clock', 'source', 'stage']):
        raise RuntimeError('Pending-generation proof requires cold mode, unique readback id and an exact transfer/boundary pair')
    prefix = '-Dproutlost.director.nativePagedLocationImport'
    flags = []
    if readback_run_id:
        flags.append(prefix + 'ReadbackRunId=' + readback_run_id)
    if pending_transfer:
        flags.extend([prefix + 'PendingTransferName=' + pending_transfer, prefix + 'ExpectedPendingBoundary=' + pending_boundary])
    return flags


def launch(root, phase, source_sha, source, transfer, readback_run_id='', pending_transfer='', pending_boundary=''):
    edge_flags = observer_edge_flags(phase, readback_run_id, pending_transfer, pending_boundary)
    lease = (root / 'P008_QA_LAUNCH.lock').open('a+')
    try:
        fcntl.flock(lease, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        raise RuntimeError('This exact owned QA launcher still holds its effective lock; do not duplicate')
    meminfo = {line.split(':', 1)[0]: int(line.split()[1]) * 1024 for line in Path('/proc/meminfo').read_text().splitlines() if ':' in line and line.split()[1].isdigit()}
    if meminfo.get('MemAvailable', 0) < 3584 * 1024**2:
        raise RuntimeError('Less than 3.5 GiB available in the actual Linux VM before QA JVM launch')
    maximum, current = Path('/sys/fs/cgroup/memory.max'), Path('/sys/fs/cgroup/memory.current')
    if maximum.is_file() and current.is_file() and maximum.read_text().strip() != 'max':
        if int(maximum.read_text()) - int(current.read_text()) < 3584 * 1024**2:
            raise RuntimeError('Actual container limit lacks 3.5 GiB remaining headroom')
    previous = root / 'server-process.json'
    if previous.exists():
        try:
            old = load(previous)
            actual = identity(old['pid'])
        except (FileNotFoundError, ProcessLookupError):
            actual = None
        if actual is not None and actual == old['identity']:
            raise RuntimeError('This exact owned QA JVM is active; do not relaunch')
    raw = (root / 'world/TEST_ONLY').read_text().strip()
    if raw != 'PROUTLOST_NEUTRAL_QA_V1':
        raise RuntimeError('Exact owned TEST_ONLY world marker required')
    if (root / 'world/data/proutlost_quest_v1.dat').exists():
        raise RuntimeError('Campaign SavedData present; preserve this world and use a new QA root, never delete it')
    props = {}
    for line in (root / 'server.properties').read_text().splitlines():
        if '=' in line and not line.lstrip().startswith('#'):
            key, value = line.split('=', 1)
            props[key.strip()] = value.strip()
    if props.get('server-port') != '25579' or props.get('server-ip') != '127.0.0.1' or props.get('white-list') != 'true':
        raise RuntimeError('Unchanged neutral guard requires internal 127.0.0.1:25579 and whitelist=true')
    for configured in [root / 'defaultconfigs/proutlost-quest-server.toml', root / 'world/serverconfig/proutlost-quest-server.toml']:
        quest = tomllib.loads(configured.read_text())
        if quest.get('enabled') is not False or quest.get('testCommands') is not False:
            raise RuntimeError('Quest SERVER config must be false/false before this QA boot')
    if phase != 'discovery' and (not re.fullmatch('[a-f0-9]{64}', source_sha) or not source.startswith('qa:')):
        raise RuntimeError('Exact prepared source SHA/qa namespace required')
    if phase != 'discovery' and load(root / 'P008_QA_PREPARATION.json').get('discovery_only'):
        raise RuntimeError('Stage final exact TEST_ONLY binaries before native qualification')
    if phase == 'cold' and not (root / 'world' / ('P008-paged-native-' + source_sha[:16] + '-first.properties')).exists():
        raise RuntimeError('Original first-run native observer receipt required for cold mode')
    flags = ['-Xms512m', '-Xmx2560m', '-XX:ActiveProcessorCount=2', '-Djava.net.preferIPv4Stack=true', '-Djava.io.tmpdir=' + str(root / 'tmp'),
        '-Duser.home=' + str(root / 'home'), '-Dproutlost.director.nativeLab=true',
        '-Dproutlost.director.nativeGameTests=' + str(phase != 'discovery').lower(),
        '-Dneoforge.enableGameTest=' + str(phase != 'discovery').lower(), '-Dneoforge.enabledGameTestNamespaces=proutlost_director',
        '-Dproutlost.quest.neutralLab=true', '-Dproutlost.quest.neutralLabRoot=' + str(root / 'world')]
    if phase != 'discovery':
        alpha = next(value for value in load(root / 'ops.json') if value['name'] == 'QAAlpha')
        flags += ['-Dproutlost.director.p008InstalledQaObserver=true', '-Dproutlost.director.nativePagedLocationImportGameTest=true',
            '-Dproutlost.director.nativePagedLocationImportRecoveryOnly=' + str(phase == 'cold').lower(),
            '-Dproutlost.director.nativePagedLocationImportName=' + transfer,
            '-Dproutlost.director.nativePagedLocationImportSource=' + source,
            '-Dproutlost.director.nativePagedLocationImportSha256=' + source_sha,
            '-Dproutlost.director.nativePagedLocationImportOperator=' + alpha['uuid']]
        flags += edge_flags
    else:
        flags.append('-Dproutlost.director.nativePagedLocationImportGameTest=false')
    args = root / ('jvm-' + phase + '-' + str(time.time_ns()) + '.args')
    args.write_text('\n'.join(flags) + '\n')
    fifo = root / 'console'
    if not fifo.exists():
        os.mkfifo(fifo, 0o600)
    fd = os.open(fifo, os.O_RDWR)
    stamp = str(time.time_ns())
    log_path = root / ('server-' + phase + '-' + stamp + '.log')
    environment = dict(os.environ)
    for key in ['CLASSPATH', 'MOD_CLASSES', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS']:
        environment.pop(key, None)
    with log_path.open('ab') as output:
        process = subprocess.Popen(['java', '@' + str(args), '@libraries/net/neoforged/neoforge/21.1.248/unix_args.txt', 'nogui'],
            cwd=root, stdin=fd, stdout=output, stderr=subprocess.STDOUT, env=environment)
        proxy = None
        try:
            process_identity = identity(process.pid)
            proxy = subprocess.Popen([sys.executable, str(Path(__file__).resolve()), '--root', str(root), 'proxy'], cwd=root,
                stdout=output, stderr=subprocess.STDOUT, env=environment)
            receipt = {'pid': process.pid, 'identity': process_identity, 'proxy_identity': identity(proxy.pid),
                'phase': phase, 'log': str(log_path), 'args': str(args), 'status': 'ACTUAL_OWNED_QA_JVM_STARTED_NOT_YET_READY_OR_QUALIFIED'}
            atomic(root / 'server-process.json', receipt)
            print(json.dumps(receipt), flush=True)
            result = process.wait()
        finally:
            if proxy is not None and proxy.poll() is None:
                proxy.terminate()
                proxy.wait(timeout=5)
            os.close(fd)
            lease.close()
        atomic(root / ('server-exit-' + stamp + '.json'), {'pid': process.pid, 'phase': phase, 'exit': result, 'log': str(log_path)})
        return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, required=True)
    sub = parser.add_subparsers(dest='action', required=True)
    start = sub.add_parser('launch')
    start.add_argument('--phase', choices=['discovery', 'first', 'cold'], required=True)
    start.add_argument('--source-sha256', default='')
    start.add_argument('--source', default='qa:p008_l2_unique_run')
    start.add_argument('--transfer', default='p008_qa_transfer')
    start.add_argument('--readback-run-id', default='')
    start.add_argument('--pending-transfer', default='')
    start.add_argument('--pending-boundary', choices=['clock', 'source', 'stage'], default='')
    sub.add_parser('proxy')
    sub.add_parser('status')
    sub.add_parser('stop')
    send = sub.add_parser('console')
    send.add_argument('command')
    native = sub.add_parser('native-entry')
    native.add_argument('--name', choices=['QAAlpha', 'QABravo'], default='QAAlpha')
    native.add_argument('--transfer', default='p008_qa_transfer')
    args = parser.parse_args()
    root = args.root.resolve()
    marker = load(root / 'P008_QA_OWNER.json')
    if not root.name.startswith('P008-') or not marker['execution_id'].startswith('P008-'):
        raise RuntimeError('NEW P008 owned server root required')
    if args.action == 'launch':
        raise SystemExit(launch(root, args.phase, args.source_sha256, args.source, args.transfer,
            args.readback_run_id, args.pending_transfer, args.pending_boundary))
    if args.action == 'proxy':
        relay(root)
    elif args.action == 'status':
        print(json.dumps(require_owned(root), indent=2))
    elif args.action == 'stop':
        owned = require_owned(root)
        fd = os.open(root / 'console', os.O_WRONLY | os.O_NONBLOCK)
        try:
            os.write(fd, b'save-all flush\nstop\n')
        finally:
            os.close(fd)
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            try:
                actual = identity(owned['pid'])
            except (FileNotFoundError, ProcessLookupError):
                print('OWNED_QA_JVM_STOPPED_VERIFIED')
                return
            except RuntimeError as error:
                if 'zombie' in str(error):
                    time.sleep(.2)
                    continue
                raise
            if actual != owned['identity']:
                print('ORIGINAL_OWNED_QA_JVM_ENDED_DIFFERENT_PROCESS_NOT_TOUCHED')
                return
            time.sleep(.2)
        raise RuntimeError('Owned graceful stop not yet observed; preserve identity and diagnose, never broad-kill')
    elif args.action == 'native-entry':
        native_entry(root, args.name, args.transfer)
    else:
        if not args.command or len(args.command) > 4096 or any(value in args.command for value in ['\n', '\r', '\x00']):
            raise RuntimeError('Bounded single QA command required')
        print(rcon(root, args.command))


if __name__ == '__main__':
    main()
