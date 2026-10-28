#!/usr/bin/env python3
"""The Wear OS app's check, on a Wear OS emulator (a debug build): the tile in each state, a tap on it, and the complication.

    KAVANA_ADB=<adb> python3 tools/check-wear.py <wear-debug.apk> <out-dir>

The emulator has no phone, so the snapshots are made up (the debug build's DEBUG_SNAP); what a tap sends to the phone shows in the log.
The phone and the watch talking is only seen on a real pair.
"""
import json
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
import phone as P  # noqa: E402

APK, OUT = sys.argv[1], sys.argv[2]
if not P.LOCAL:
    sys.exit('check-wear.py runs on the emulator only (KAVANA_ADB).')
os.makedirs(OUT, exist_ok=True)
PKG = 'xyz.tiresia.kavana.debug'
TILE = f'{PKG}/xyz.tiresia.kavana.wear.FocusTile'
fails = []
n = 0


def check(what, ok):
    print(('ok   ' if ok else 'FAIL ') + what)
    if not ok:
        fails.append(what)
    return ok


def step(name):
    global n
    n += 1
    P.shot(os.path.join(OUT, f'{n:02d}-{name}.png'))
    print(f'  shot {n:02d}-{name}.png')


def snap(**k):
    """A made-up snapshot, as the phone would send it."""
    s = {'catName': 'Miso', 'catsOn': True, 'todayMin': 80, 'goalMin': 120, 'streak': 5, 'sent': int(time.time() * 1000)}
    s.update(k)
    P.adb('shell', f"am broadcast -f 32 -a xyz.tiresia.kavana.wear.DEBUG_SNAP -p {PKG} --es json '{json.dumps(s)}'")  # -f 32: a fresh install too
    time.sleep(4)


def texts():
    for _ in range(4):
        t = P.texts()
        if t:
            return t
        time.sleep(1)
    return []


def has(t, *words):
    return all(any(w in x for x in t) for w in words)


print('install')
if f'package:{PKG}' in P.adb('shell', 'pm', 'list', 'packages', PKG):
    P.adb('uninstall', PKG)
print(P.adb('install', '-t', APK, timeout=300).strip())
P.adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')

now = int(time.time() * 1000)
snap(mode='READY')
out = P.adb('shell', 'am', 'broadcast', '-a', 'com.google.android.wearable.app.DEBUG_SURFACE', '--es', 'operation', 'add-tile', '--ecn', 'component', TILE)
check('the tile can be added to the watch', 'Index=' in out)


def lab(**k):
    """The tile for a made-up snapshot, in the tile lab (the watch's own tile renderer, on a screen of the app's)."""
    s = {'catName': 'Miso', 'catsOn': True, 'todayMin': 80, 'goalMin': 120, 'streak': 5, 'sent': int(time.time() * 1000)}
    s.update(k)
    P.adb('shell', 'am', 'force-stop', PKG)
    P.adb('shell', f"am start -n {PKG}/xyz.tiresia.kavana.wear.TileLab --es json '{json.dumps(s)}'")
    time.sleep(5)
    return texts()


t = lab(mode='READY')
check('ready: the cat is waiting, Start 25, and 15, 50, ∞', has(t, 'Miso is waiting', 'Start 25', '15', '50', '∞'))
step('tile-ready')
P.adb('logcat', '-c')
P.tap('Start 25', exact=True)
time.sleep(4)
check('a tap on Start 25 goes to the phone (here, none is paired)', 'tap start25' in P.adb('logcat', '-d', '-s', 'ChataltulWatch:I'))

t = lab(mode='FOCUSING', title='Draft the newsletter', start=now - 7 * 60_000, end=now + 18 * 60_000 + 30_000, total=25 * 60_000)
check('focusing: the minutes left, the title, Pause', any(re.match(r'1[89] min left$', x) for x in t) and has(t, 'Draft the newsletter', 'Pause'))
step('tile-focusing')

t = lab(mode='EXTRA', title='Draft the newsletter', start=now - 28 * 60_000, end=now - 3 * 60_000, total=25 * 60_000, over=now - 3 * 60_000)
check('extra time: Done instead of Pause', has(t, 'extra time', 'Done') and not has(t, 'Pause'))
step('tile-extra')
P.adb('logcat', '-c')
P.tap('Done', exact=True)
time.sleep(4)
check('Done in extra time asks the phone to stop the block', 'tap stop' in P.adb('logcat', '-d', '-s', 'ChataltulWatch:I'))
t = lab(mode='FOCUSING', stopwatch=True, start=now - 4 * 60_000, end=now + 176 * 60_000, total=180 * 60_000)
check('a stopwatch under 10 minutes: Pause, no Done (the kitten would wander off)', has(t, 'focusing', 'Pause') and not has(t, 'Done'))
t = lab(mode='FOCUSING', stopwatch=True, start=now - 12 * 60_000, end=now + 168 * 60_000, total=180 * 60_000)
check('a stopwatch past 10 minutes: Done', has(t, 'focusing', 'Done') and not has(t, 'Pause'))

t = lab(mode='CHECKIN', title='Draft the newsletter', start=now - 12 * 60_000, end=now + 13 * 60_000, total=25 * 60_000, pending=1)
check('a check-in: Yes and Drifted', has(t, 'BOO! CHECK-IN', 'Still on: Draft the newsletter?', 'Yes', 'Drifted'))
step('tile-checkin')

t = lab(mode='PAUSED', start=now - 12 * 60_000, end=now + 13 * 60_000, total=25 * 60_000, paused=now - 20_000, pauseEnds=now + 4 * 60_000)
check('paused: Resume', has(t, 'PAUSED', 'Resume'))
step('tile-paused')

t = lab(mode='HOME', minutes=25, treats=25)
check('came home: the treats and Done', has(t, 'MISO CAME HOME', '+25 treats', 'Done'))
step('tile-home')

t = lab(mode='READY', catsOn=False)
check('cats off: no cat name', has(t, 'Ready', 'Start 25') and not has(t, 'Miso'))

P.adb('shell', 'am', 'force-stop', PKG)
P.adb('shell', 'am', 'start', '-n', f'{PKG}/xyz.tiresia.kavana.wear.WatchActivity')
time.sleep(4)
check('the app itself shows the same (the last snapshot)', has(texts(), 'Miso is waiting', 'Start 25', '15', '50', '∞'))
step('app')


def clab(**k):
    """The complication for a made-up snapshot, in the complication lab (drawn as a watch face draws it, and in words)."""
    s = {'catName': 'Miso', 'catsOn': True, 'todayMin': 80, 'goalMin': 120, 'streak': 5, 'sent': int(time.time() * 1000)}
    s.update(k)
    P.adb('shell', 'am', 'force-stop', PKG)
    P.adb('shell', f"am start -n {PKG}/xyz.tiresia.kavana.wear.ComplicationLab --es json '{json.dumps(s)}'")
    time.sleep(4)
    return texts()


svc = P.adb('shell', 'pm', 'query-services', '--components', '-a', 'android.support.wearable.complications.ACTION_COMPLICATION_UPDATE_REQUEST')
check('watch faces can find the complication', 'FocusComplication' in svc)
t = clab(mode='READY')
check('the complication, between blocks: today against the goal', has(t, 'ranged_value: 1h20 today (80 of 120)', 'short_text: 1h20 today'))
step('complication-today')
now = int(time.time() * 1000)
t = clab(mode='FOCUSING', title='Draft the newsletter', start=now - 7 * 60_000, end=now + 18 * 60_000 + 30_000, total=25 * 60_000)
check('during a block: the minutes left, counting down, and never the title',
      any(re.match(r'ranged_value: 1[89]\s?m(in)? left \((2[6-9]|3\d) of 100\)$', x) for x in t)
      and any(re.match(r'short_text: 1[89]\s?m(in)? left$', x) for x in t) and not has(t, 'newsletter'))
step('complication-focusing')

print(f'\n{"all passed" if not fails else str(len(fails)) + " failed: " + "; ".join(fails)}')
sys.exit(1 if fails else 0)
