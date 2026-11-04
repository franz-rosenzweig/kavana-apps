#!/usr/bin/env python3
"""Picked apps for the leave cat (Settings → Apps during a block, "The cat comes for"), on the emulator (a debug build).

    KAVANA_ADB=<adb> python3 tools/check-leave.py <app-debug.apk> <out-dir> [walk|tablet]

walk: the screen and its picker, light and dark, then one block with a real clock: an allowed app brings no cat,
another app does, back to an allowed one within the grace takes it away, "Back to work" goes to the allowed app,
the home screen neither brings nor takes the cat, nor does locking and unlocking, Chataltul's own trip to a link
isn't leaving but going on from there is, "Only these apps", "Every app" and no Usage access are today's way,
and last, the kitten wanders off when the grace runs out in an app that counts.
tablet: the screen at a tablet's size (an emulator booted with KAV_SIZE=tablet), light and dark.

Emulator only, like the other checks that write: it clears the debug app's data and uses the emulator's own apps.
"""
import json
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
import phone as P  # noqa: E402

APK, OUT = sys.argv[1], sys.argv[2]
PHASE = sys.argv[3] if len(sys.argv) > 3 else 'walk'
if not P.LOCAL:
    sys.exit('check-leave.py runs on the emulator only (KAVANA_ADB).')
os.makedirs(OUT, exist_ok=True)
PKG = P.PKG
ACT = f'{PKG}/xyz.tiresia.kavana.MainActivity'
BOO = "Boo! You're focusing."
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


def launcher_apps():
    out = sh('cmd', 'package', 'query-activities', '--brief', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.LAUNCHER')
    return {line.strip().split('/')[0]: line.strip() for line in out.splitlines() if '/' in line}


def open_app(component):
    sh('am', 'start', '-n', component)
    time.sleep(2.5)


def chataltul(*extra):
    sh('am', 'start', '-n', ACT, *extra)
    time.sleep(2.5)


def top():
    out = sh('dumpsys', 'activity', 'activities')
    m = re.search(r'topResumedActivity=ActivityRecord\{\S+ \S+ ([\w.]+)/', out) or re.search(r'mResumedActivity: ActivityRecord\{\S+ \S+ ([\w.]+)/', out)
    return m.group(1) if m else None


def cat_up(wait=4.0):
    return P.wait_for(BOO, wait)


def no_cat(secs=4.0):
    end = time.time() + secs
    while time.time() < end:
        if P.find(BOO):
            return False
        time.sleep(0.8)
    return True


def prefs():
    return sh('run-as', PKG, 'cat', 'shared_prefs/kavana.xml')


def left_set():
    return 'name="left"' in prefs()


def doc():
    return json.loads(sh('run-as', PKG, 'cat', 'files/doc.json'))


def running():
    return next((b for b in doc()['blocks'] if not b.get('ended') and not b.get('cancelled')), None)


def crashed():
    return 'FATAL EXCEPTION' in P.adb('logcat', '-d', '-b', 'crash')


def scroll_to(text, tries=8):
    """Down the screen until `text` shows (phone.py's swipe is for a 1080 x 2400 phone)."""
    w, h = (int(x) for x in re.findall(r'(\d+)x(\d+)', sh('wm', 'size'))[-1])
    for _ in range(tries):
        if P.find(text):
            return True
        sh('input', 'swipe', str(w // 2), str(h * 3 // 4), str(w // 2), str(h // 3), '300')
        time.sleep(0.7)
    return bool(P.find(text))


def settings():
    P.tap('Menu', exact=True)
    time.sleep(1.0)
    P.tap('Settings', exact=True)
    time.sleep(1.0)


print('install')
P.mac('adb uninstall ' + PKG + ' >/dev/null 2>&1 || true')
print(P.adb('install', '-t', APK, timeout=300).strip())
sh('pm', 'clear', PKG)
sh('pm', 'grant', PKG, 'android.permission.POST_NOTIFICATIONS')
sh('appops', 'set', PKG, 'SYSTEM_ALERT_WINDOW', 'allow')
sh('appops', 'set', PKG, 'GET_USAGE_STATS', 'default')
sh('cmd', 'uimode', 'night', 'no')
sh('settings', 'put', 'system', 'screen_off_timeout', '1800000')
sh('input', 'keyevent', 'KEYCODE_WAKEUP')
sh('wm', 'dismiss-keyguard')
P.adb('logcat', '-c')

apps = launcher_apps()
# Two of the emulator's own apps: one for work (allowed), one that isn't. Not Settings: it hides every app's overlays.
WORK = next((apps[p] for p in ('com.google.android.contacts', 'com.android.contacts', 'com.google.android.documentsui', 'com.android.documentsui') if p in apps), None)
PLAY = next((apps[p] for p in ('com.google.android.deskclock', 'com.android.deskclock', 'com.google.android.calculator', 'com.android.calculator2', 'com.android.camera2') if p in apps), None)
if not WORK or not PLAY:
    sys.exit(f'no two apps to use: {sorted(apps)}')
WP, PP = WORK.split('/')[0], PLAY.split('/')[0]
print(f'work app {WP}, the other {PP}')

try:
    chataltul()
    if P.wait_for('Welcome to Chataltul', 10):
        P.tap('Continue'); time.sleep(1)
        P.tap('Continue'); time.sleep(1)
        P.tap('Start Focusing'); time.sleep(1.5)
    check('home: Add a title', P.wait_for('Add a title', 10))

    if PHASE == 'tablet':
        sh('appops', 'set', PKG, 'GET_USAGE_STATS', 'allow')
        chataltul('--es', 'leaveMode', 'except', '--es', 'leaveApps', WP)
        settings()
        scroll_to('Apps during a block')
        check('Settings says Except 1', P.wait_for('Except 1'))
        P.tap('Apps during a block')
        check('the screen at a tablet\'s size', P.wait_for('The cat comes for') and P.find('On', exact=True) is not None)
        time.sleep(2)
        step('tablet-light')
        sh('cmd', 'uimode', 'night', 'yes')
        time.sleep(3)
        step('tablet-dark')
        sh('cmd', 'uimode', 'night', 'no')
        raise SystemExit

    # ---------- the screen ----------
    settings()
    scroll_to('Apps during a block')
    check('Settings has "Apps during a block": Every app', P.find('Apps during a block') is not None and P.find('Every app', exact=True) is not None)
    P.tap('Apps during a block')
    check('it opens "The cat comes for" with three choices', P.wait_for('The cat comes for') and all(P.find(t, exact=True) for t in ('Every app', 'Every app except these', 'Only these apps')))
    check('with Every app, no list to pick from', not P.find('Usage access') and not P.find('Search'))
    step('every')
    P.tap('Every app except these', exact=True)
    time.sleep(1)
    check('Every app except these: Usage access is Off, with Allow', P.wait_for('Usage access') and P.find('Off', exact=True) is not None and P.find('Allow', exact=True) is not None)
    check('a search and the apps', P.find('Search') is not None)
    step('except-no-access')
    t = P.texts()
    check('the apps that never count aren\'t in the list (no Chataltul, no Phone)', 'Chataltul dev' not in t and 'Phone' not in t)
    sh('appops', 'set', PKG, 'GET_USAGE_STATS', 'allow')
    sh('input', 'keyevent', 'KEYCODE_HOME'); time.sleep(1.5)
    chataltul()  # back from "Android's settings": the switch is read again
    check('back with Usage access: On, no Allow', P.wait_for('Usage access') and P.find('On', exact=True) is not None and not P.find('Allow', exact=True))
    work_label = next((x for x, _, _ in P.nodes() if x in ('Contacts', 'Files')), None)
    play_label = next((x for x, _, _ in P.nodes() if x in ('Clock', 'Calculator', 'Camera')), None)
    P.tap('Search')
    P.type_text((work_label or 'Contacts')[:4])
    time.sleep(1)
    check('search finds the app', P.find(work_label or 'Contacts', exact=True) is not None)
    P.tap(work_label or 'Contacts', exact=True)
    time.sleep(0.8)
    P.adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')  # the keyboard away
    time.sleep(0.5)
    step('except-search-picked')
    P.adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')  # back to Settings
    time.sleep(1)
    check('Settings says Except 1', P.wait_for('Except 1'))
    P.tap('Apps during a block')
    time.sleep(1.5)
    check('the picked app is on top of the list', (lambda t: work_label in t and (play_label is None or t.index(work_label) < t.index(play_label)))(P.texts()))
    step('except-light')
    sh('cmd', 'uimode', 'night', 'yes'); time.sleep(3)
    step('except-dark')
    P.adb('shell', 'input', 'keyevent', 'KEYCODE_BACK'); time.sleep(1.5)
    step('settings-dark')  # the row, and Settings' own title, in dark mode
    P.tap('Apps during a block'); time.sleep(1.5)
    P.tap('Only these apps', exact=True); time.sleep(1)
    step('only-dark')
    sh('cmd', 'uimode', 'night', 'no'); time.sleep(2)
    P.tap('Every app except these', exact=True); time.sleep(0.5)
    P.adb('shell', 'input', 'keyevent', 'KEYCODE_BACK'); time.sleep(1)

    # ---------- a block, with a real clock ----------
    P.tap('Menu', exact=True); time.sleep(1); P.tap('Focus', exact=True); time.sleep(1)
    P.tap('Start', exact=True)
    time.sleep(1.5)
    b = running()
    check('a block runs, its kitten growing', b is not None and not b.get('gone'))

    print('\nEvery app except these (the work app picked)')
    open_app(WORK)
    check('the work app: no cat', no_cat(4) and not left_set())
    open_app(PLAY)
    check('another app: the cat comes', cat_up(5) and left_set())
    step('cat-over-other-app')
    open_app(WORK)
    check('back to the work app within the grace: the cat goes', no_cat(3) and not left_set())
    check('and the kitten is still growing', not (running() or {}).get('gone'))
    open_app(PLAY)
    check('the other app again: the cat', cat_up(5))
    P.tap('Back to work', exact=True)
    time.sleep(2.5)
    check('"Back to work" goes to the work app', top() == WP)
    check('and the cat goes', no_cat(3) and not left_set())
    sh('input', 'keyevent', 'KEYCODE_HOME'); time.sleep(2)
    check('the home screen: no cat', no_cat(4) and not left_set())
    open_app(PLAY)
    check('from the home screen to the other app: the cat', cat_up(5))
    sh('input', 'keyevent', 'KEYCODE_HOME'); time.sleep(2.5)
    check('the home screen doesn\'t take the cat away', P.find(BOO) is not None and left_set())
    open_app(WORK)
    check('the work app does', no_cat(3) and not left_set())

    print('\nlocked and unlocked in the work app')
    sh('input', 'keyevent', 'KEYCODE_SLEEP'); time.sleep(3)
    sh('input', 'keyevent', 'KEYCODE_WAKEUP'); time.sleep(1)
    sh('wm', 'dismiss-keyguard'); time.sleep(4)
    check('no cat after unlocking into the work app', no_cat(4) and not left_set() and top() == WP)

    print('\nChataltul\'s own trip (a link from Settings)')
    chataltul()
    settings()
    if scroll_to('Chataltul on the web'):
        P.tap('Chataltul on the web')
        time.sleep(4)
        browser = top()
        if browser and browser != PKG:
            check(f'the link\'s app ({browser}) isn\'t leaving', no_cat(4) and not left_set())
            open_app(PLAY)
            check('going on from there to another app is', cat_up(5))
            open_app(WORK)
            no_cat(2)
        else:
            check('the link opened an app', False)
    else:
        check('the link is in Settings', False)

    print('\nOnly these apps (the other app picked)')
    chataltul('--es', 'leaveMode', 'only', '--es', 'leaveApps', PP)
    open_app(WORK)
    check('an app that isn\'t picked: no cat', no_cat(4) and not left_set())
    open_app(PLAY)
    check('the picked app: the cat', cat_up(5))
    open_app(WORK)
    check('back to an unpicked app: the cat goes', no_cat(3) and not left_set())

    print('\nEvery app (today\'s way)')
    chataltul('--es', 'leaveMode', 'every')
    sh('input', 'keyevent', 'KEYCODE_HOME')
    check('the home screen brings the cat, right away', cat_up(3))
    chataltul()
    check('back in Chataltul: the cat goes', no_cat(2) and not left_set())

    print('\nno Usage access (a list, but today\'s way)')
    chataltul('--es', 'leaveMode', 'except', '--es', 'leaveApps', WP)
    sh('appops', 'set', PKG, 'GET_USAGE_STATS', 'deny')
    open_app(WORK)
    check('even the work app brings the cat', cat_up(3))
    chataltul()
    no_cat(2)
    sh('appops', 'set', PKG, 'GET_USAGE_STATS', 'allow')
    check('the kitten is still growing after all that', not (running() or {}).get('gone'))

    print('\nthe grace runs out in an app that counts')
    open_app(PLAY)
    check('the cat', cat_up(5))
    time.sleep(13)
    check('the kitten wandered off', P.wait_for('wandered off', 9))
    step('wandered-off')
    open_app(WORK)
    time.sleep(2)
    check('the doc learns it once you\'re in the work app', bool((running() or {}).get('gone')))
    check('no crash', not crashed())
    log = P.adb('logcat', '-d', '-s', 'ChataltulLeave:I')
    check('the debug log says what was in front', f'in front: {WP}, ALLOWED' in log and f'in front: {PP}, COUNTS' in log)
except SystemExit:
    pass
finally:
    sh('cmd', 'uimode', 'night', 'no')
    sh('input', 'keyevent', 'KEYCODE_HOME')

print('\nall passed' if not fails else f'\n{len(fails)} failed: {fails}')
sys.exit(1 if fails else 0)
