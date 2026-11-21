#!/usr/bin/env python3
"""Explicit abrupt QA JVM test, guarded by full launch identity and TEST_ONLY.

This controller is outside production JARs. It follows the actual owned log,
kills only that exact JVM at a named preparation/activation boundary, and writes
a receipt. The receipt proves an interruption, not data recovery or acceptance.
"""
from pathlib import Path
import argparse, json, os, select, signal, time
from run_linux_qa import atomic, identity, load, require_owned

BOUNDARIES = {
    'clock': 'DIRECTOR_LOCATION_PREPARATION_CLOCK_DURABLE tick=',
    'source': 'DIRECTOR_LOCATION_SOURCE_PAGE_DURABLE index=0 ',
    'stage': 'DIRECTOR_LOCATION_METADATA_PAGE_DURABLE index=0 ',
    'activation': 'DIRECTOR_LOCATION_GENERATION_ACTIVATED cut=',
}

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--root', type=Path, required=True)
    p.add_argument('--boundary', choices=BOUNDARIES, required=True)
    p.add_argument('--timeout-seconds', type=int, default=240)
    a = p.parse_args()
    root = a.root.resolve()
    owner = load(root / 'P008_QA_OWNER.json')
    if not root.name.startswith('P008-') or owner['execution_id'] != 'P008-20261118T062924Z-root':
        raise RuntimeError('This exact P008 isolated QA assignment is required')
    if (root / 'world/TEST_ONLY').read_text().strip() != 'PROUTLOST_NEUTRAL_QA_V1':
        raise RuntimeError('Exact TEST_ONLY marker required')
    if not 5 <= a.timeout_seconds <= 600:
        raise RuntimeError('Bounded monitor timeout required')
    prior = require_owned(root)
    if not hasattr(os, 'pidfd_open') or not hasattr(signal, 'pidfd_send_signal'):
        raise RuntimeError('Linux pidfd support required; no PID-only kill fallback')
    # Pin the kernel process before arming; a later PID reuse cannot redirect
    # the signal. The short-lived controller owns this descriptor until exit.
    process_fd = os.pidfd_open(prior['pid'], 0)
    if require_owned(root)['identity'] != prior['identity']:
        raise RuntimeError('Owned JVM changed while binding pidfd; no signal sent')
    args = Path(prior['args']).read_text()
    if '-Dproutlost.director.nativeLab=true' not in args or '-Dproutlost.quest.neutralLab=true' not in args:
        raise RuntimeError('Actual JVM arguments do not identify the assigned QA mode')
    log = Path(prior['log'])
    if not log.is_relative_to(root) or log.is_symlink():
        raise RuntimeError('Owned actual log path required')
    receipt = root / 'receipts' / ('abrupt-' + a.boundary + '-' + str(time.time_ns()) + '.json')
    state = dict(boundary=a.boundary, trigger=BOUNDARIES[a.boundary], server_identity=prior['identity'],
        status='ARMED_NO_KILL_YET', qualification='RECOVERY_NOT_RUN', log=str(log), started_epoch=time.time(), signal_binding='LINUX_PIDFD')
    with log.open('r', encoding='utf-8', errors='replace') as stream:
        stream.seek(0, os.SEEK_END)
        state['start_log_offset'] = stream.tell()
        atomic(receipt, state)
        print(json.dumps(dict(status=state['status'], receipt=str(receipt))), flush=True)
        deadline = time.monotonic() + a.timeout_seconds
        while time.monotonic() < deadline:
            line = stream.readline(1048577)
            if len(line) > 1048576:
                raise RuntimeError('Log line exceeds monitor bound')
            if not line:
                require_owned(root)
                time.sleep(.01)
                continue
            if BOUNDARIES[a.boundary] not in line:
                continue
            if require_owned(root)['identity'] != prior['identity']:
                raise RuntimeError('Owned JVM changed; no signal sent')
            state.update(trigger_line=line.strip(), trigger_log_offset=stream.tell(), signal='SIGKILL',
                signal_epoch=time.time(), status='EXACT_OWNED_QA_SIGNAL_PENDING')
            atomic(receipt, state)
            signal.pidfd_send_signal(process_fd, signal.SIGKILL, None, 0)
            ended = select.poll()
            ended.register(process_fd, select.POLLIN)
            if ended.poll(5000):
                state.update(status='EXACT_OWNED_QA_JVM_ABRUPT_END_VERIFIED', ended_epoch=time.time(), process_exit_binding='KERNEL_PIDFD_READY')
                atomic(receipt, state)
                print(json.dumps(state), flush=True)
                return
            raise RuntimeError('Signal sent; abrupt exit not yet observed; inspect exact identity, never signal another PID')
    state.update(status='TIMEOUT_NO_SIGNAL_SENT', ended_epoch=time.time())
    atomic(receipt, state)
    raise RuntimeError('No selected boundary observed; no JVM killed')

if __name__ == '__main__':
    main()
