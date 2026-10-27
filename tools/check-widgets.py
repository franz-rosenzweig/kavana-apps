#!/usr/bin/env python3
"""The widgets' check, on the emulator (a debug build): every widget in every state, a whole block run from a widget,
Park a thought, the countdown notification, the Quick Settings tiles and the launcher shortcuts.

    KAVANA_ADB=<adb> python3 tools/check-widgets.py <app-debug.apk> <out-dir>

It hosts the widgets in the debug build's Widget lab (a screen of the app's own, at real sizes), so it never
touches a home screen or a notification shade. Emulator only, like the other checks that write.
"""
import json
import os
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
import phone as P  # noqa: E402

APK, OUT = sys.argv[1], sys.argv[2]
if not P.LOCAL:
    sys.exit('check-widgets.py runs on the emulator only (KAVANA_ADB).')
os.makedirs(OUT, exist_ok=True)
PKG = P.PKG
LAB = f'{PKG}/xyz.tiresia.kavana.widget.WidgetLab'
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


def texts(retries=4):
    for _ in range(retries):
        t = P.texts()
        if t:
            return t
        time.sleep(1)
    return []


def lab(set_, *extra):
    P.adb('shell', 'am', 'force-stop', PKG)
    P.adb('shell', 'am', 'start', '-n', LAB, '--es', 'set', set_, *extra)
    time.sleep(5)
    if P.find('Create widget and allow access?'):  # once: Android asks before an app hosts widgets
        P.tap('Always allow')
        P.tap('Create', exact=True)
        time.sleep(5)
    top = texts()
    # the page is longer than the screen: what's below the fold
    P.adb('shell', 'input', 'swipe', '540', '1900', '540', '500', '400')
    time.sleep(1.5)
    return top + texts()


def has(t, *words):
    return all(any(w in x for x in t) for w in words)


def doc():
    return json.loads(P.adb('shell', 'run-as', PKG, 'cat', 'files/doc.json'))


print('install')
P.mac('adb uninstall ' + PKG + ' >/dev/null 2>&1 || true')
print(P.adb('install', '-t', APK, timeout=300).strip())
P.adb('shell', 'pm', 'grant', PKG, 'android.permission.POST_NOTIFICATIONS')
P.adb('shell', 'cmd', 'uimode', 'night', 'no')

try:
    print('\nevery widget, every state (a made-up snapshot each)')
    lab('window')  # (the allow dialog, if it comes)
    t = lab('window')
    check('Window, ready', has(t, 'Miso is waiting', 'Tap to start 25 minutes', 'Start', '15', '50'))
    check('Window, focusing: a live clock, the title, Pause', has(t, 'FOCUSING', 'Draft the newsletter', 'Pause', 'Park a thought'))
    check('Window, check-in: Yes and Drifted on the widget', has(t, 'BOO! CHECK-IN', 'Still on: Draft the newsletter?', 'Yes', 'Drifted'))
    check('Window, came home: how it went, on the widget', has(t, 'MISO CAME HOME', 'Nice, 50 minutes', '+50 treats', 'Done', 'Partly', 'Not really'))
    check('Window, paused: a countdown and Resume', has(t, 'PAUSED', 'Starts again by itself', 'Resume'))
    check('Window, wandered off', has(t, 'MISO WANDERED OFF', 'Your block keeps going.', 'Open Chataltul'))
    step('window')
    t = lab('window-more')
    check('Window, the rest: extra time, stopped early, a break, no title', has(t, 'EXTRA TIME', 'STOPPED EARLY', 'BREAK', 'Miso is growing'))
    t = lab('small')
    check('2×2: Start 25, Resume, +50', has(t, 'Start 25', 'Resume', '+50', 'Yes', 'Drifted'))
    step('small')
    t = lab('strip')
    check('4×1: the strip in every state', has(t, 'Miso is waiting', 'BOO! CHECK-IN', 'Miso came home', 'Paused', 'Miso wandered off'))
    t = lab('quick')
    check('Quick start: 15, 25, 50, ∞, and the running strip', has(t, '15', '25', '50', '∞', 'Pause', 'Miso came home'))
    step('quick')
    t = lab('today')
    check('Today: the goal, the streak, the missions, Collect', has(t, '1h 20', 'of 2 h', '5 days', 'in a row', '2 of 3', 'missions done', 'Collect +20'))
    check('Today, nothing yet: 0m and no Collect', has(t, '0m', '0 days', '0 of 3'))
    step('today')
    t = lab('later')
    check('Later: the list and Park a thought', has(t, 'Later', '4 things', 'Park a thought', 'Book the dentist', 'Look up that podcast episode'))
    check('Later, empty', has(t, 'nothing yet', 'Nothing parked'))
    step('later')
    t = lab('sound')
    check('Sound: the mix, play and pause, and picking one', has(t, 'Rain', 'Purring', 'Pause the sound', 'Play the sound', 'Pick a sound'))
    step('sound')
    t = lab('milo')
    check('Milo, with his own name', has(t, 'Milo is waiting', 'MILO CAME HOME'))
    t = lab('themes')
    check('the five café themes draw', has(t, 'classic', 'mint', 'sakura', 'library', 'night'))
    step('themes')
    P.adb('shell', 'cmd', 'uimode', 'night', 'yes')
    time.sleep(2)
    t = lab('window')
    check('dark mode draws the same widgets', has(t, 'Miso is waiting', 'FOCUSING', 'Resume'))
    step('window-dark')
    P.adb('shell', 'cmd', 'uimode', 'night', 'no')

    print('\na whole block, from a widget (the real engine, a minute lasting a second)')
    P.adb('shell', 'pm', 'clear', PKG)
    P.adb('shell', 'pm', 'grant', PKG, 'android.permission.POST_NOTIFICATIONS')
    P.adb('shell', 'am', 'start', '-n', f'{PKG}/xyz.tiresia.kavana.MainActivity', '--el', 'minute', '1000')
    time.sleep(3)
    P.adb('shell', 'am', 'force-stop', PKG)
    t = lab('live', '--ez', 'live', 'true')  # (the --es set is ignored)
    P.adb('shell', 'am', 'force-stop', PKG)
    P.adb('shell', 'am', 'start', '-n', LAB, '--ez', 'live', 'true', '--ei', 'w', '340', '--ei', 'h', '150')
    time.sleep(5)
    check('the live widget is ready', P.wait_for('Miso is waiting', 8))
    P.tap('Start', exact=True)
    check('Start: the widget says FOCUSING, and a block is running', P.wait_for('FOCUSING', 8) and any(not b.get('ended') for b in doc()['blocks']))
    P.tap('Pause', exact=True)
    check('Pause: PAUSED', P.wait_for('PAUSED', 15))
    check('and its Resume button', P.wait_for('Resume', 20))
    P.tap('Resume', exact=True, wait=20)
    check('Resume: FOCUSING again', P.wait_for('FOCUSING', 15))
    check('the countdown is a foreground service started by a tap on a widget',
          'isForeground=true' in P.adb('shell', 'dumpsys', 'activity', 'services', PKG))
    note = P.adb('shell', 'dumpsys', 'notification', '--noredact')
    mine = note[note.find(f'pkg={PKG}'):][:6000]
    check('the countdown notification is a Live Update with its stages, a check-in point, the kitten and a house',
          all(k in mine for k in ['ProgressStyle', 'android.progressSegments', 'android.progressPoints', 'android.progressTrackerIcon', 'android.progressEndIcon', 'android.requestPromotedOngoing']))
    check('and its lock-screen version has no title', 'android.title=String (Focusing)' in mine)
    check('with Pause and Park a thought on it', 'Park a thought' in mine and 'Pause' in mine)
    check('a check-in comes up on the widget', P.wait_for('BOO! CHECK-IN', 30))
    step('live-checkin')
    P.tap('Yes', exact=True)
    check('Yes: back to FOCUSING', P.wait_for('FOCUSING', 8))
    check('the kitten comes home, and the widget asks how it went', P.wait_for('CAME HOME', 40))
    step('live-home')
    P.tap('Done', exact=True)
    check('Done: ready again', P.wait_for('Miso is waiting', 8))
    check('and the block is recorded as Done', any(b.get('outcome') == 'done' for b in doc()['blocks']))

    print('\npark a thought, from a widget')
    P.tap('Start', exact=True)
    check('another block', P.wait_for('FOCUSING', 8))
    P.tap('Park a thought', exact=True)
    check('a small dialog, not the app', P.wait_for('A thought to park', 6) and not P.find('Welcome'))
    P.adb('shell', 'input', 'text', 'Reply%sto%sDana')
    time.sleep(0.6)
    P.tap('Park', exact=True)
    time.sleep(2)
    d = doc()
    check('it went on the Later list', any(x['text'] == 'Reply to Dana' for x in d.get('later', [])))
    check('and the block it was parked in', any('Reply to Dana' in json.dumps(b.get('parked', [])) for b in d['blocks']))
    step('after-park')

    print('\nQuick Settings tiles and launcher shortcuts')
    tiles = P.adb('shell', 'pm', 'query-services', '--components', '-a', 'android.service.quicksettings.action.QS_TILE')
    check('the three tiles are there', all(x in tiles for x in ['FocusTileService', 'ParkTileService', 'SoundTileService']))
    sc = P.adb('shell', 'dumpsys', 'shortcut')
    check('the four launcher shortcuts are published', all(f'id={i}' in sc for i in ['start25', 'start50', 'park', 'cafe']))
finally:
    P.adb('shell', 'cmd', 'uimode', 'night', 'no')
    P.adb('shell', 'am', 'force-stop', PKG)

print(f'\n{"all passed" if not fails else str(len(fails)) + " failed: " + "; ".join(fails)}')
sys.exit(1 if fails else 0)
