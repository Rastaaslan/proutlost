#!/usr/bin/env python3
"""Reconnect only the two already-running P008 real clients through their GUI.

No JVM launch, Minecraft protocol, account entry, player creation or server
mutation. Before each click: full process/window/namespace guards and a fresh
reference-matched screenshot. Every screenshot and receipt is CREATE_NEW.
"""
from pathlib import Path
import argparse, fcntl, hashlib, json, os, re, subprocess, time
from PIL import Image, ImageGrab

DISPLAY = ':91'
PROFILE_ROOT = Path('/workspace/linux-clients-b05')
PINS = {
    'QAAlpha': dict(pid=1147, start_ticks='49889894', wid=2097162, x=0,
        cmdline_sha256='1c6bf2023e6d2c3c25a841dcd43cb464bc933119179aa9279f39e4cb691584f3'),
    'QABravo': dict(pid=1338, start_ticks='49898963', wid=4194314, x=640,
        cmdline_sha256='35c22582820c1149f80f71e5fc7791a9144715ebbe6a224708b5d2052fb8eeff'),
}
XVFB = dict(pid=1090, start_ticks='49817582', exe='/usr/bin/Xvfb', cwd='/workspace/display-91',
    cmdline_sha256='8ceb13ea57a969a289e4a081e2b1b688f7f8f164e7ab866cf2b74f9c791dd547')
REFERENCES = {
    'disconnected': 'B05-edge-disconnected-before-reconnect.png',
    'disconnected_alt': 'P008-reconnect-r2-source-preflight/001-both-clients-before.png',
    'serverlist': 'B05-edge-serverlist.png',
    'direct': 'B05-edge-directconnection.png',
}
BACK_Y = {'disconnected': 209, 'disconnected_alt': 220}


def need(value, reason):
    if not value:
        raise RuntimeError(reason)


def command(*args):
    return subprocess.check_output(args, env={**os.environ, 'DISPLAY': DISPLAY}, text=True, timeout=8).strip()


def proc(pid):
    root = Path('/proc') / str(pid)
    raw = (root / 'stat').read_text()
    fields = raw[raw.rfind(')') + 2:].split()
    need(fields[0] != 'Z', 'Process is a zombie')
    return dict(pid=pid, start_ticks=fields[19], exe=os.readlink(root / 'exe'), cwd=os.readlink(root / 'cwd'),
        cmdline_sha256=hashlib.sha256((root / 'cmdline').read_bytes()).hexdigest(),
        namespaces={name: os.readlink(root / 'ns' / name) for name in ['pid', 'mnt', 'net', 'uts']})


def guard(name):
    pin = PINS[name]
    for expected in [XVFB, {**pin, 'exe': '/opt/java/openjdk/bin/java', 'cwd': str(PROFILE_ROOT / name)}]:
        actual = proc(expected['pid'])
        need(all(actual[key] == expected[key] for key in ['pid', 'start_ticks', 'exe', 'cwd', 'cmdline_sha256']), 'Exact owned process identity changed')
        need(all(value == os.readlink(Path('/proc/self/ns') / key) for key, value in actual['namespaces'].items()), 'Client/controller namespaces differ')
    receipt = json.loads((PROFILE_ROOT / name / 'P008_LINUX_CLIENT_PROCESS.json').read_text())
    need(receipt['name'] == name and receipt['pid'] == pin['pid'] and str(receipt['start_ticks']) == pin['start_ticks']
        and receipt['cmdline_sha256'] == pin['cmdline_sha256'] and receipt['endpoint'] == '127.0.0.1:25579'
        and receipt['game_root'] == str(PROFILE_ROOT / name) and receipt['environment']['DISPLAY'] == DISPLAY,
        'Original real-client launch receipt differs')
    need(int(command('xdotool', 'getwindowpid', str(pin['wid']))) == pin['pid'], 'Window does not belong to exact client PID')
    geometry = dict(line.split('=', 1) for line in command('xdotool', 'getwindowgeometry', '--shell', str(pin['wid'])).splitlines() if '=' in line)
    need(all(int(geometry[key]) == value for key, value in dict(X=pin['x'], Y=0, WIDTH=640, HEIGHT=360).items()), 'Window moved/resized; refuse stale coordinates')
    title = command('xdotool', 'getwindowname', str(pin['wid']))
    need('Minecraft' in title and '1.21.1' in title, 'Unexpected native window title')
    return dict(name=name, window=pin['wid'], process=proc(pin['pid']), title=title, geometry=geometry)


def foreground(image):
    return {(x, y) for y in range(image.height) for x in range(image.width)
        if min(image.getpixel((x, y))) >= 180 and max(image.getpixel((x, y))) - min(image.getpixel((x, y))) <= 70}


def heading_template(image, state):
    # References use the observed LEFT640x360 client, translated to either WID.
    low, high = (75, 185) if state in BACK_Y else (8, 60)
    rows = []
    for y in range(low, high):
        count = sum(1 for x in range(100, 540) if min(image.getpixel((x, y))) >= 180
            and max(image.getpixel((x, y))) - min(image.getpixel((x, y))) <= 70)
        if count >= 4:
            rows.append(y)
    groups = []
    for y in rows:
        if not groups or y - groups[-1][-1] > 2:
            groups.append([])
        groups[-1].append(y)
    groups = [g for g in groups if 4 <= g[-1] - g[0] + 1 <= 22]
    need(groups, 'Reference has no bounded bright heading: ' + state)
    group = groups[0]
    box = (100, max(low, group[0] - 1), 540, min(high, group[-1] + 2))
    mask = foreground(image.crop(box))
    need(len(mask) >= 24, 'Reference heading has too few text pixels: ' + state)
    return box, mask


def back_template(image, center_y):
    # Inner label area only: exclude button edges/highlights. For the alternate
    # observed layout this region is BELOW Open Report Directory at y197.
    low, high = center_y - 10, center_y + 10
    mask = foreground(image.crop((240, low, 400, high)))
    counts = {}
    for x, y in mask:
        counts[y] = counts.get(y, 0) + 1
    rows = sorted(y for y, count in counts.items() if count >= 4)
    groups = []
    for y in rows:
        if not groups or y - groups[-1][-1] > 2:
            groups.append([])
        groups[-1].append(y)
    groups = [g for g in groups if 4 <= g[-1] - g[0] + 1 <= 12]
    need(len(groups) == 1, 'Exactly one observed Back to Server List label required')
    box = (240, low + groups[0][0] - 1, 400, low + groups[0][-1] + 2)
    text = foreground(image.crop(box))
    need(len(text) >= 24, 'Observed Back label has too few glyph pixels')
    return box, text


def score(image, template):
    box, expected = template
    actual = foreground(image.crop(box))
    overlap = len(expected & actual)
    return min(overlap / len(expected), overlap / max(1, len(actual)))


def address_matches(image, template):
    box, expected = template
    actual = foreground(image.crop(box))
    difference, common = expected ^ actual, expected & actual
    if not difference:
        return True
    # Permit only the single blinking caret column AFTER all common text.
    # A general .91 threshold would overlook a changed endpoint character.
    columns = {x for x, _ in difference}
    return bool(common) and len(columns) == 1 and 7 <= len(difference) <= 12 \
        and min(columns) > max(x for x, _ in common)


class Reconnect:
    def __init__(self, output, references):
        self.output, self.index, self.evidence, self.actions = output, 0, [], []
        self.templates, self.reference_hashes, self.back_labels = {}, {}, {}
        for state, filename in REFERENCES.items():
            path = references / filename
            need(path.is_file() and not path.is_symlink() and path.stat().st_size <= 8 * 1024**2, 'Bounded original reference screenshot required')
            self.reference_hashes[state] = hashlib.sha256(path.read_bytes()).hexdigest()
            with Image.open(path) as loaded:
                need(loaded.size == (1280, 720), 'Reference display dimensions changed')
                image = loaded.convert('RGB')
            self.templates[state] = heading_template(image, state)
            if state in BACK_Y:
                self.back_labels[state] = back_template(image, BACK_Y[state])
            if state == 'direct':
                self.address = ((220, 116, 420, 136), foreground(image.crop((220, 116, 420, 136))))
                need(len(self.address[1]) >= 24, 'Reference address field is not visible')

    def capture(self, label, image=None):
        image = image if image is not None else ImageGrab.grab(xdisplay=DISPLAY).convert('RGB')
        need(image.size == (1280, 720), 'Xvfb framebuffer dimensions changed')
        self.index += 1
        target = self.output / f'{self.index:03d}-{label}.png'
        with target.open('xb') as stream:
            image.save(stream, format='PNG')
        entry = dict(screenshot=str(target), sha256=hashlib.sha256(target.read_bytes()).hexdigest(), epoch=time.time())
        self.evidence.append(entry)
        print(json.dumps(entry), flush=True)
        return image

    def matches(self, image, name):
        x = PINS[name]['x']
        cropped = image.crop((x, 0, x + 640, 360))
        return {state: score(cropped, template) for state, template in self.templates.items()}

    def require_state(self, image, name, expected):
        scores = self.matches(image, name)
        matched = [state for state, value in scores.items() if value >= .91]
        allowed = set(BACK_Y) if expected == 'disconnected' else {expected}
        need(len(matched) == 1 and matched[0] in allowed, 'Unexpected/ambiguous GUI state for ' + name + ': ' + json.dumps(scores))
        selected = matched[0]
        if selected in BACK_Y:
            x = PINS[name]['x']
            box, label = self.back_labels[selected]
            current = image.crop((x, 0, x + 640, 360))
            need(foreground(current.crop(box)) == label, 'Exact observed Back to Server List glyphs differ; no click')
        if expected == 'direct':
            x = PINS[name]['x']
            need(address_matches(image.crop((x, 0, x + 640, 360)), self.address), 'Prefilled address differs from the observed127.0.0.1:25579 reference')
        return selected

    def click(self, name, expected, y=None):
        guard(name)
        before = self.capture(name + '-' + expected + '-before')
        selected = self.require_state(before, name, expected)
        if expected == 'disconnected':
            y = BACK_Y[selected]
        need(y in [209, 220, 315, 208], 'Unrecognized action coordinate')
        self.actions.append(dict(name=name, expected=expected, matched_layout=selected, x=PINS[name]['x'] + 320, y=y, status='VALIDATED_ACTION_PENDING'))
        guard(name)
        command('xdotool', 'windowfocus', '--sync', str(PINS[name]['wid']))
        command('xdotool', 'mousemove', '--sync', str(PINS[name]['x'] + 320), str(y), 'click', '1')
        self.actions[-1]['status'] = 'CLICK_SENT'
        time.sleep(.2)
        self.capture(name + '-' + expected + '-after-click')

    def wait_state(self, name, expected):
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            guard(name)
            image = ImageGrab.grab(xdisplay=DISPLAY).convert('RGB')
            try:
                self.require_state(image, name, expected)
            except RuntimeError:
                time.sleep(.25)
                continue
            self.capture(name + '-' + expected + '-ready', image)
            return
        self.capture(name + '-unexpected-transition')
        raise RuntimeError('Expected GUI transition did not arrive: ' + name + '/' + expected)

    def reconnect(self, name):
        self.click(name, 'disconnected')
        self.wait_state(name, 'serverlist')
        self.click(name, 'serverlist', 315)
        self.wait_state(name, 'direct')
        log = PROFILE_ROOT / name / 'logs/latest.log'
        need(log.is_file() and not log.is_symlink(), 'Actual client log required')
        before_log = log.stat()
        self.click(name, 'direct', 208)
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            current = guard(name)
            after_log = log.stat()
            need((before_log.st_dev, before_log.st_ino) == (after_log.st_dev, after_log.st_ino)
                and before_log.st_size <= after_log.st_size <= before_log.st_size + 1024**2, 'Client log rotated/truncated or exceeded bounded reconnect interval')
            with log.open('rb') as stream:
                stream.seek(before_log.st_size)
                lines = stream.read(1024**2 + 1).decode('utf-8', errors='replace')
            image = ImageGrab.grab(xdisplay=DISPLAY).convert('RGB')
            matches = self.matches(image, name)
            need(max(matches[state] for state in BACK_Y) < .91, 'Real client returned to either observed Connection Lost layout; no retry')
            if re.search(r'Connecting to 127[.]0[.]0[.]1, 25579\b', lines) and 'Multiplayer' in current['title'] and max(matches.values()) < .91:
                self.capture(name + '-native-multiplayer-title-observed', image)
                return dict(status='REAL_CLIENT_GUI_MULTIPLAYER_TITLE_OBSERVED', identity=current,
                    client_connect_log_interval=[before_log.st_size, after_log.st_size], endpoint='127.0.0.1:25579',
                    server_join_and_negotiated_session='ROOT_MUST_VERIFY_ACTUAL_SERVER_RECEIPTS')
            time.sleep(.25)
        self.capture(name + '-join-not-confirmed')
        raise RuntimeError('Real client GUI/log join not confirmed; no retry or new launch')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run-id', required=True)
    parser.add_argument('--references', type=Path, default=Path('/workspace/screenshots'))
    parser.add_argument('--execute', action='store_true')
    args = parser.parse_args()
    need(re.fullmatch(r'[a-z0-9_-]{1,48}', args.run_id), 'Bounded unique run id required')
    parent = Path('/workspace/screenshots')
    need(parent.is_dir() and not parent.is_symlink() and args.references.resolve() == parent, 'Original owned screenshot directory required')
    lease = (parent / 'P008_GUI_RECONNECT.lock').open('a+')
    fcntl.flock(lease, fcntl.LOCK_EX | fcntl.LOCK_NB)
    output = parent / ('P008-reconnect-' + args.run_id)
    output.mkdir()
    evidence = None
    result = dict(scope='EXISTING_REAL_CLIENT_GUI_ONLY_NO_NEW_PROCESS_NO_PROTOCOL_CLIENT', run_id=args.run_id, execute=args.execute, status='STARTED')
    try:
        evidence = Reconnect(output, args.references)
        result['reference_sha256'] = evidence.reference_hashes
        result['back_button_y_by_exact_layout'] = BACK_Y
        result['initial_clients'] = [guard(name) for name in PINS]
        initial = evidence.capture('both-clients-before')
        for name in PINS:
            evidence.require_state(initial, name, 'disconnected')
        result['clients'] = [evidence.reconnect(name) for name in PINS] if args.execute else []
        result['status'] = 'GUI_RECONNECT_OBSERVED_SERVER_RECEIPTS_PENDING' if args.execute else 'PREFLIGHT_AND_REFERENCE_MATCH_PASS_NO_INPUT'
    except Exception as failure:
        result.update(status='REFUSED_OR_STOPPED_NO_RETRY', error=str(failure))
        if evidence is not None:
            try:
                evidence.capture('failure-final-frame')
            except Exception as capture_failure:
                result['final_capture_error'] = str(capture_failure)
    finally:
        result['screenshots'] = evidence.evidence if evidence is not None else []
        result['gui_actions'] = evidence.actions if evidence is not None else []
        with (output / 'receipt.json').open('x') as stream:
            json.dump(result, stream, indent=2)
            stream.write('\n')
        print(json.dumps(result), flush=True)
    return 0 if result['status'] in ['GUI_RECONNECT_OBSERVED_SERVER_RECEIPTS_PENDING', 'PREFLIGHT_AND_REFERENCE_MATCH_PASS_NO_INPUT'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
