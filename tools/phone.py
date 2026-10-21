#!/usr/bin/env python3
"""Drives the Android phone plugged into the Mac (adb over SSH) for Kavana's device checks.

    python3 tools/phone.py shot out.png          # a screenshot
    python3 tools/phone.py tap "Start Focus"     # tap the first thing showing that text
    python3 tools/phone.py texts                 # what's on the screen (uiautomator)

As a module: import phone; phone.adb('shell', 'input', 'keyevent', 'HOME'); phone.tap('Yes').
Compose screens show up in uiautomator through their accessibility tree, so text taps work.
"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

MAC = ['ssh', '-o', 'BatchMode=yes', '-i', '~/.ssh/mac-key', 'user@your-mac']
PATH = 'export PATH=$HOME/android-sdk/platform-tools:$PATH; '
PKG = 'xyz.tiresia.kavana.debug'
# KAVANA_ADB=/path/to/adb: a device on this machine (the emulator) instead of the phone on the Mac.
LOCAL = os.environ.get('KAVANA_ADB')


def mac(cmd, binary=False, timeout=120):
    if LOCAL:
        cmd = re.sub(r'^adb ', LOCAL + ' -s ' + os.environ.get('KAVANA_SERIAL', 'emulator-5554') + ' ', cmd)
        r = subprocess.run(['bash', '-c', cmd], capture_output=True, timeout=timeout)
    else:
        r = subprocess.run(MAC + [PATH + cmd], capture_output=True, timeout=timeout)
    if r.returncode != 0 and not binary:
        raise RuntimeError(f'{cmd}: {r.stderr.decode(errors="replace")[-400:]}')
    return r.stdout if binary else r.stdout.decode(errors='replace')


def adb(*args, binary=False, timeout=120):
    q = ' '.join("'" + a.replace("'", "'\\''") + "'" if re.search(r'[^\w@%+=:,./-]', a) else a for a in args)
    return mac('adb ' + q, binary=binary, timeout=timeout)


def shot(path):
    png = adb('exec-out', 'screencap', '-p', binary=True)
    with open(path, 'wb') as f:
        f.write(png)
    return path


def nodes():
    xml = adb('exec-out', 'uiautomator', 'dump', '--windows', '/dev/tty')
    xml = xml[xml.find('<?xml'):xml.rfind('>') + 1]
    out = []
    for n in ET.fromstring(xml).iter('node'):
        t = n.get('text') or n.get('content-desc') or ''
        b = [int(x) for x in re.findall(r'\d+', n.get('bounds', ''))]
        if t and len(b) == 4:
            out.append((t, (b[0] + b[2]) // 2, (b[1] + b[3]) // 2))
    return out


def texts():
    return [t for t, _, _ in nodes()]


def find(text, exact=False, last=False):
    hits = [(x, y) for t, x, y in nodes() if ((t == text) if exact else (text in t))]
    if not hits:
        return None
    return max(hits, key=lambda p: p[1]) if last else hits[0]


def tab(name):
    """A tab in the bottom bar (the lowest thing on the screen with that label)."""
    return tap(name, exact=True, last=True)


def scroll_to(text, tries=6):
    for _ in range(tries):
        if find(text):
            return True
        adb('shell', 'input', 'swipe', '540', '1700', '540', '700', '300')
        time.sleep(0.6)
    return bool(find(text))


def tap(text, exact=False, wait=6.0, last=False):
    end = time.time() + wait
    while True:
        p = find(text, exact, last)
        if p:
            adb('shell', 'input', 'tap', str(p[0]), str(p[1]))
            return p
        if time.time() > end:
            raise RuntimeError(f'not on screen: {text!r}; showing {texts()[:30]}')
        time.sleep(0.5)


def wait_for(text, wait=20.0):
    end = time.time() + wait
    while time.time() < end:
        if find(text):
            return True
        time.sleep(0.7)
    return False


def type_text(s):
    adb('shell', 'input', 'text', s.replace(' ', '%s'))


if __name__ == '__main__':
    cmd = sys.argv[1]
    if cmd == 'shot':
        print(shot(sys.argv[2]))
    elif cmd == 'tap':
        print(tap(sys.argv[2]))
    elif cmd == 'texts':
        print('\n'.join(texts()))
    else:
        print(adb(*sys.argv[1:]))
