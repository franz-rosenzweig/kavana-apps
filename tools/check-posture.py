#!/usr/bin/env python3
"""Sit up straight (Settings → Focus), on the emulator (a debug build, a minute lasting 2 seconds).

    KAVANA_ADB=<adb> python3 tools/check-posture.py <app-debug.apk> <out-dir>

The row in Settings, Off by default, set to Every 10 min. A block: at 10 minutes, with Chataltul on screen, the cat
pops up and says it, with no notification and nothing to answer (no check-in, the widgets and the watch don't take it
for one); it goes by itself. With the screen locked (which isn't leaving), the next one is a notification of its own
on the "Sit up straight" channel, with no buttons, and the kitten keeps growing. Then a block in dark mode, and one
with Cat mode off: a plain "Sit up straight." on the screen and no cat.

Emulator only, like the other checks that write: it clears the debug app's data.
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
    sys.exit('check-posture.py runs on the emulator only (KAVANA_ADB).')
os.makedirs(OUT, exist_ok=True)
PKG = P.PKG
ACT = f'{PKG}/xyz.tiresia.kavana.MainActivity'
SEC = 3  # seconds a minute lasts
SIT = 'Sit up straight.'
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


def sh(*a):
    return P.adb('shell', *a)


def doc():
    return json.loads(sh('run-as', PKG, 'cat', 'files/doc.json'))


def running():
    return next((b for b in doc()['blocks'] if not b.get('ended') and not b.get('cancelled')), None)


def widget_pending():
    try:
        return (json.loads(sh('run-as', PKG, 'cat', 'files/widget.json')).get('block') or {}).get('pending')
    except ValueError:
        return None


def records():
    """Chataltul's own notification records, one string each."""
    out = sh('dumpsys', 'notification', '--noredact')
    return [r for r in out.split('NotificationRecord(')[1:] if f'pkg={PKG} ' in r.split('\n', 1)[0]]


def posture_record():
    """Sit up straight's own notification (Notify.ID_POSTURE), not the group summary Android adds next to the countdown."""
    return next((r for r in records() if ' id=14 ' in r.split('\n', 1)[0] and "mId='posture'" in r), None)


def crashed():
    return 'FATAL EXCEPTION' in P.adb('logcat', '-d', '-b', 'crash')


def scroll_to(text, tries=8):
    w, h = (int(x) for x in re.findall(r'(\d+)x(\d+)', sh('wm', 'size'))[-1])
    for _ in range(tries):
        if P.find(text):
            return True
        sh('input', 'swipe', str(w // 2), str(h * 3 // 4), str(w // 2), str(h // 3), '300')
        time.sleep(0.7)
    return bool(P.find(text))


def row_value(label):
    """What a Settings row shows on its right (its own line on the screen)."""
    ns = P.nodes()
    y = next((y for t, _, y in ns if t == label), None)
    return [t for t, _, yy in ns if y is not None and abs(yy - y) < 40 and t != label]


def tap_switch(label):
    _, y = P.find(label, exact=True)
    w = int(re.findall(r'(\d+)x(\d+)', sh('wm', 'size'))[-1][0])
    sh('input', 'tap', str(w - 110), str(y))


def menu(name):
    P.tap('Menu', exact=True)
    time.sleep(1.0)
    P.tap(name, exact=True)
    time.sleep(1.0)


def start():
    """Starts a block; its start, in this machine's seconds (the emulator's clock is the host's)."""
    menu('Focus')
    P.tap('Start', exact=True)
    time.sleep(1)
    b = running()
    check('a block is running', b is not None)
    return b['start'] / 1000 if b else time.time()


def popup(wait):
    """Waits for "Sit up straight." and returns everything on the screen at that moment (one look)."""
    end = time.time() + wait
    while time.time() < end:
        ts = P.texts()
        if SIT in ts:
            return ts
        time.sleep(0.3)
    return []


def until(t0, minutes):
    left = t0 + minutes * SEC - time.time()
    if left > 0:
        time.sleep(left)


def file_it():
    """The end of a block: Done, and any cat met."""
    for _ in range(4):
        if P.find('Done', exact=True):
            P.tap('Done', exact=True)
            time.sleep(1.2)
        while P.find('Nice!', exact=True):
            P.tap('Nice!', exact=True)
            time.sleep(1)
        if running() is None and not P.find('Done', exact=True):
            return
        time.sleep(1.5)


print('install')
P.mac('adb uninstall ' + PKG + ' >/dev/null 2>&1 || true')
print(P.adb('install', '-t', APK, timeout=300).strip())
sh('pm', 'clear', PKG)
sh('pm', 'grant', PKG, 'android.permission.POST_NOTIFICATIONS')
sh('appops', 'set', PKG, 'SYSTEM_ALERT_WINDOW', 'allow')  # the cat over other apps is on: Sit up straight still never uses it
sh('cmd', 'uimode', 'night', 'no')
sh('settings', 'put', 'system', 'screen_off_timeout', '1800000')
sh('input', 'keyevent', 'KEYCODE_WAKEUP')
sh('wm', 'dismiss-keyguard')
P.adb('logcat', '-c')
if P.find("isn't responding"):  # a freshly booted emulator's System UI
    P.tap('Wait', exact=True)
    time.sleep(2)

try:
    sh('am', 'start', '-n', ACT, '--el', 'minute', str(SEC * 1000))
    time.sleep(2.5)
    if P.wait_for('Welcome to Chataltul', 10):
        P.tap('Continue'); time.sleep(1)
        P.tap('Continue'); time.sleep(1)
        P.tap('Start Focusing'); time.sleep(1.5)
    check('home: Add a title', P.wait_for('Add a title', 10))

    # ---------- the row ----------
    menu('Settings')
    check('Settings has Sit up straight, Off', scroll_to('Sit up straight') and row_value('Sit up straight') == ['Off'])
    P.tap('Check in every')
    P.tap('Only at the end', exact=True)  # no check-ins to get in the way
    time.sleep(0.8)
    P.tap('Sit up straight')
    check('its choices: Off and Every 10 to 60 min', all(P.find(f'Every {m} min', exact=True) for m in (10, 15, 20, 30, 45, 60)))
    step('settings-choices')
    P.tap('Every 10 min', exact=True)
    time.sleep(1)
    check('Every 10 min is kept in the doc', doc()['settings'].get('posture') == 10)
    check('and the row says so', row_value('Sit up straight') == ['Every 10 min'])
    step('settings-row')

    # ---------- a block, on screen ----------
    t0 = start()
    until(t0, 9)
    check('nothing at 9 minutes', SIT not in P.texts())
    ts = popup(4 * SEC)
    step('popup-light')
    check('at 10 minutes the cat says Sit up straight on the screen', bool(ts))
    check('in its own words', 'Miso is sitting up tall. Your turn.' in ts)
    check('and no notification while Chataltul is on screen', posture_record() is None)
    b = running()
    check('nothing waits for an answer: no check-in', b is not None and not any(c.get('answer') is None for c in b.get('checkins', [])))
    check('the widgets don\'t take it for a check-in', widget_pending() is None)
    until(t0, 10 + 10 / SEC)
    check('it goes by itself', 'Miso is sitting up tall. Your turn.' not in P.texts())

    # ---------- the screen locked: a notification of its own ----------
    sh('input', 'keyevent', 'KEYCODE_SLEEP')
    until(t0, 21)
    rec = posture_record()
    if rec is None:
        print('  records:', [r.split('\n', 1)[0][:200] for r in records()])
        print(P.adb('logcat', '-d', '-s', 'Kavana:*', 'AndroidRuntime:E')[-1500:])
    check('with the screen locked, the next one is a notification on the Sit up straight channel', rec is not None)
    check('with the cat\'s words', rec is not None and 'Miso is sitting up tall' in rec)
    check('and no buttons', rec is not None and 'actions={' not in rec)
    sh('input', 'keyevent', 'KEYCODE_WAKEUP')
    sh('wm', 'dismiss-keyguard')
    time.sleep(2)
    prefs = sh('run-as', PKG, 'cat', 'shared_prefs/kavana.xml')
    b = running()
    check('locking isn\'t leaving: the kitten is still growing, no leave cat', 'name="left"' not in prefs and b is not None and not b.get('gone'))
    until(t0, 25.5)
    file_it()

    # ---------- dark mode ----------
    sh('cmd', 'uimode', 'night', 'yes')
    time.sleep(3)
    t0 = start()
    until(t0, 9)
    ts = popup(4 * SEC)
    step('popup-dark')
    check('dark: the cat says it', 'Miso is sitting up tall. Your turn.' in ts)
    sh('cmd', 'uimode', 'night', 'no')
    time.sleep(2)

    # ---------- Cat mode off ----------
    menu('Settings')
    scroll_to('Cat mode')
    tap_switch('Cat mode')
    time.sleep(1)
    check('Cat mode is off', doc()['settings'].get('cat') is False)
    until(t0, 19)
    ts = popup(4 * SEC)
    step('no-cat')
    check('cat mode off: "Sit up straight." on the screen', bool(ts))
    check('with no cat line', ts and not any('sitting up tall' in x or 'Shoulders back' in x for x in ts))
    if ts:
        print('  on screen:', [x for x in ts if 'Sit' in x or 'tall' in x or 'Shoulders' in x])
    until(t0, 25.5)
    file_it()
    check('nothing crashed', not crashed())
finally:
    sh('cmd', 'uimode', 'night', 'no')
    print(P.adb('uninstall', PKG).strip())

print(f'\n{len(fails)} failed' if fails else '\nALL PASS')
sys.exit(1 if fails else 0)
