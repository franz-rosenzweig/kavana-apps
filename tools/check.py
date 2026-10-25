#!/usr/bin/env python3
"""Kavana's device check: installs the debug build and walks whole blocks with a fast clock,
taking a screenshot at each step.

    python3 tools/check.py <app-debug.apk> <out-dir>           # the phone plugged into the Mac
    python3 tools/check.py mac:<path on the Mac> <out-dir>     # an APK built on the Mac
    KAVANA_ADB=/path/to/adb python3 tools/check.py ...         # an emulator on this machine

Part 1, the cat over other apps (a minute lasts 3 s, 6 s on a phone): the welcome and its café, the start screen,
a block with no title cancelled in its first seconds, a block with
the kitten in the ring and the countdown notification, leaving Kavana (the cat over the home
screen), a check-in answered on the cat while away, the kitten that wandered off, "How did it go?",
meeting Pepper, every tab (Cats with its missions and shop), the mindful minute, dark mode.
Part 2, without the cat and without a title (a minute lasts 2 s, 4 s on a phone): the countdown called
"Focus", "Miso is waiting" as a notification, a "Still focusing?" check-in notification with Yes and
Drifted, then (on the emulator) the end answered with its own Done button.

It clears only the DEBUG app's data (xyz.tiresia.kavana.debug), never the real app's, and puts
the phone's dark mode and the debug app's overlay permission back as they were.
"""
import json
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
import phone as P  # noqa: E402

APK, OUT = sys.argv[1], sys.argv[2]
os.makedirs(OUT, exist_ok=True)
ACT = f'{P.PKG}/xyz.tiresia.kavana.MainActivity'
# Seconds per test minute: a phone is driven through the Mac over SSH, which is slow.
SEC = 3 if P.LOCAL else 6
fails = []
n = 0


def step(name):
    global n
    n += 1
    path = os.path.join(OUT, f'{n:02d}-{name}.png')
    P.shot(path)
    print(f'  shot {os.path.basename(path)}')


def check(what, ok):
    print(('ok   ' if ok else 'FAIL ') + what)
    if not ok:
        fails.append(what)
    return ok


def launch(minute):
    P.adb('shell', 'am', 'force-stop', P.PKG)
    P.adb('shell', 'am', 'start', '-n', ACT, '--el', 'minute', str(minute))
    time.sleep(2.5)
    while P.find('Nice!', exact=True):  # a cat met (a new clock speed changes the minutes in history)
        P.tap('Nice!', exact=True)
        time.sleep(1)


def start_block(name=''):
    """Starts a block, with a title if there's a name (the title is optional, like Forest's)."""
    if not P.find('Add a title'):
        screen('Focus')
    time.sleep(0.5)
    if name:
        P.tap('Add a title')
        P.type_text(name)
        P.adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')  # the keyboard away
        time.sleep(0.6)
    P.tap('Start', exact=True)
    return running(name)


def screen(name):
    """A place in Kavana, through the menu (the drawer at the top left)."""
    P.tap('Menu', exact=True)
    time.sleep(1.0)
    P.tap(name, exact=True)
    time.sleep(0.8)


def notifications():
    """Kavana's own notification records only: on a real phone, nobody else's leave the phone."""
    return P.adb('shell', "dumpsys notification --noredact | awk '/NotificationRecord\\(/{on=/pkg=xyz\\.tiresia\\.kavana\\.debug /} on'")


def leave():
    """Leave Kavana for another app. On a real phone that's the installed release of Kavana itself
    (a plain screen with nothing private on it), never the home screen (its widgets are private),
    the notification shade, an app picked by category (on my phone "calculator" opened a
    Tasker dialog), or Android's Settings (it hides every app's overlays, the cat's too)."""
    if P.LOCAL:
        P.adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
    else:
        P.adb('shell', 'am', 'start', '-n', 'xyz.tiresia.kavana/.MainActivity')


def doc():
    return P.adb('shell', 'run-as', P.PKG, 'cat', 'files/doc.json')


def running(name=""):
    """The block is running, from the saved doc (Samsung's uiautomator can't read the timer screen:
    it redraws every second and never goes idle)."""
    end = time.time() + 10
    while time.time() < end:
        try:
            b = [x for x in json.loads(doc())['blocks'] if x.get('intention', '') == name and not x.get('ended') and not x.get('cancelled')]
            if b:
                return b[-1]
        except (ValueError, RuntimeError, KeyError):
            pass
        time.sleep(1)
    return None


print('install')
P.mac('adb uninstall ' + P.PKG + ' >/dev/null 2>&1 || true')  # another machine's debug key can't update it
if P.LOCAL:
    print(P.adb('install', '-t', APK, timeout=300).strip())
elif APK.startswith('mac:'):  # built on the Mac: install it from there
    print(P.mac('adb install -t ' + APK[4:], timeout=180).strip())
else:
    P.to_mac(APK, '/tmp/kavana-debug.apk')
    print(P.mac('adb install -t /tmp/kavana-debug.apk', timeout=180).strip())
P.adb('shell', 'pm', 'clear', P.PKG)
P.adb('shell', 'pm', 'grant', P.PKG, 'android.permission.POST_NOTIFICATIONS')
P.adb('shell', 'appops', 'set', P.PKG, 'SYSTEM_ALERT_WINDOW', 'allow')
night = P.adb('shell', 'cmd', 'uimode', 'night').strip()
P.adb('shell', 'cmd', 'uimode', 'night', 'no')
P.adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
if not P.LOCAL:
    P.adb('shell', 'cmd', 'statusbar', 'collapse')  # a shade left open would cover the app

try:
    print('\npart 1: the cat over other apps')
    launch(SEC * 1000)
    check('welcome shows', P.wait_for('Welcome to Kavana'))
    step('welcome')
    P.tap('Continue')
    check('a glimpse: the café fills up', P.wait_for('Your café fills up'))
    time.sleep(2)  # the things and the cats drop in
    step('welcome-cafe')
    P.tap('Continue')
    check('the cat page says All set (overlay already allowed)', P.wait_for('All set'))
    step('welcome-cat')
    P.tap('Start Focusing')
    check('the start screen: the café wall, the window, and a quiet "Add a title"', P.wait_for('Add a title'))
    step('home')

    # Changed your mind: Cancel in the first 10 seconds takes the block back, nothing counts.
    # This one has no title: Start is enough.
    check('a block without a title starts', bool(start_block()))
    P.tap('Cancel', exact=True)
    time.sleep(1.5)
    check('Cancel took it back', running() is None and '"cancelled"' in doc())

    b = start_block('Test block')
    t0 = time.time()
    check('the block runs', bool(b))
    time.sleep(1)
    step('run')
    check('Miso is the kitten growing in it', bool(b) and b.get('cat') == 'miso')
    end = time.time() + 6
    while time.time() < end and 'android.title=String (Test block)' not in notifications():
        time.sleep(1)
    dump = notifications()
    check('the countdown notification is posted', 'android.title=String (Test block)' in dump and 'chronometerCountDown=Boolean (true)' in dump)
    print('     promoted to a Live Update:', 'PROMOTED_ONGOING' in dump)

    # Leave Kavana: the cat comes up over the other app.
    leave()
    check('the cat over the home screen', P.wait_for("Boo! It's time to focus.", 6))
    step('leave-cat')
    # The first check-in is at minute 15 (45 s): it shows on the cat while still away.
    check('a check-in on the cat', P.wait_for('Still on: Test block', max(5, 15 * SEC + 25 - (time.time() - t0))))  # the alarm can be a few seconds late
    step('checkin-cat')
    if P.find('Yes', exact=True):
        P.tap('Yes', exact=True)
        time.sleep(1.5)
    # Back in Kavana, more than 15 s later: Miso wandered off.
    P.adb('shell', 'am', 'start', '-n', ACT)
    gone = None
    end = time.time() + 10
    while time.time() < end and not gone:
        b = running('Test block')
        gone = b.get('goneWhy') if b else None
        time.sleep(1)
    check('the kitten wandered off while away', gone == 'away')
    step('back-gone')
    check('the check-in answered on the cat counts', doc().count('"answer":"yes"') == 1)

    check('the end: How did it go?', P.wait_for('How did it go?', max(5, 25 * SEC + 6 - (time.time() - t0))))
    step('outcome')
    P.tap('Done', exact=True)
    check('meeting Pepper after the first block', P.wait_for('You met Pepper', 8))
    step('meet')
    while P.find('Nice!', exact=True):  # after 10 pm the first block meets Boo too
        P.tap('Nice!', exact=True)
        time.sleep(1)

    for name in ['Café', 'Cats', 'Later', 'Settings']:
        screen(name)
        time.sleep(1.2)
        step(name.lower().replace('é', 'e'))
        if name == 'Cats':  # the other two panes: missions and rewards, the shop
            P.tap('Missions')
            time.sleep(1)
            check('the achievements list (First Kitten) is there', P.scroll_to('First Kitten', tries=10))
            step('missions')
            screen('Shop')
            time.sleep(1)
            step('shop')

    screen('A mindful minute')
    check('the mindful minute', P.wait_for('Breathe in', 5))
    step('breathe')
    P.tap('Leave', exact=True)

    P.adb('shell', 'cmd', 'uimode', 'night', 'yes')
    time.sleep(2)
    screen('Focus')
    time.sleep(1)
    step('home-dark')
    screen('Café')
    time.sleep(1)
    step('cafe-dark')
    P.adb('shell', 'cmd', 'uimode', 'night', 'no')

    print('\npart 2: without the cat, notifications, and without a title')
    P.adb('shell', 'appops', 'set', P.PKG, 'SYSTEM_ALERT_WINDOW', 'deny')
    launch(SEC * 2000 // 3)
    check('the second block runs, with no title', bool(start_block()))
    time.sleep(2)
    check('the countdown goes by the tag, or Focus', 'android.title=String (Focus)' in notifications())
    t0 = time.time()
    leave()
    time.sleep(2)
    check('"Miso is waiting" as a notification', 'Miso is waiting' in notifications())
    # The check-in at minute 15 (30 s): a notification with Yes and Drifted (a banner as it comes).
    end = time.time() + 15 * SEC * 2 // 3 + 12
    while time.time() < end and 'Still focusing?' not in notifications():
        time.sleep(1)
    dump = notifications()
    i = dump.find('Still focusing?')
    check('the check-in notification, with Yes and Drifted', i >= 0 and '"Yes"' in dump[max(0, i - 4000):i + 2000] and '"Drifted"' in dump[max(0, i - 4000):i + 2000])
    # The end (50 s) takes its place: answer it from the shade with its own Done button. On a real
    # phone the shade holds other apps' notifications, so this part runs on the emulator only.
    if P.LOCAL:
        end = time.time() + 30
        while time.time() < end and 'Tap to log it.' not in notifications():
            time.sleep(1)
        P.adb('shell', 'cmd', 'statusbar', 'expand-notifications')
        time.sleep(1.5)
        for _ in range(2):
            if P.find('Done', exact=True):
                break
            e = P.find('Expand', exact=True)
            if e:
                P.adb('shell', 'input', 'tap', str(e[0]), str(e[1]))
                time.sleep(1.2)
        step('shade')
        if P.find('Done', exact=True):
            P.tap('Done', exact=True)
            time.sleep(2.5)
        P.adb('shell', 'cmd', 'statusbar', 'collapse')
        second = [b for b in json.loads(doc())['blocks'] if not b.get('intention') and not b.get('cancelled')]
        check('Done on the notification was recorded', bool(second) and second[-1].get('outcome') == 'done')
finally:
    mode = re.search(r'Night mode: (\w+)', night)
    P.adb('shell', 'cmd', 'uimode', 'night', mode.group(1) if mode else 'auto')
    P.adb('shell', 'appops', 'set', P.PKG, 'SYSTEM_ALERT_WINDOW', 'allow')
    P.adb('shell', 'am', 'force-stop', P.PKG)
    if not P.LOCAL:
        P.adb('shell', 'am', 'force-stop', 'xyz.tiresia.kavana')
        P.mac('adb uninstall ' + P.PKG + ' >/dev/null 2>&1 || true')  # only the real Kavana stays on the phone

print(f'\n{"all passed" if not fails else str(len(fails)) + " failed: " + "; ".join(fails)}')
sys.exit(1 if fails else 0)
