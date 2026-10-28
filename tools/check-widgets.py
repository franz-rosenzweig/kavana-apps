#!/usr/bin/env python3
"""The widgets' check, on the emulator (a debug build): every widget in every state, a whole block run from a widget,
Park a thought, the countdown notification, the Quick Settings tiles and the launcher shortcuts.

    KAVANA_ADB=<adb> python3 tools/check-widgets.py <app-debug.apk> <out-dir> [states|dark|live|play|playdark|pet|cafe|friends|tablet]

With no phase it does all three in one go; an emulator that renders for long enough grows to several GB, so
the phases can each run on a freshly booted one.

It hosts the widgets in the debug build's Widget lab (a screen of the app's own, at real sizes), so it never
touches a home screen or a notification shade. Emulator only, like the other checks that write.
"""
import json
import os
import re
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
import phone as P  # noqa: E402

APK, OUT = sys.argv[1], sys.argv[2]
PHASE = sys.argv[3] if len(sys.argv) > 3 else 'all'  # states, dark or live: a phase per emulator boot keeps its memory down
if not P.LOCAL:
    sys.exit('check-widgets.py runs on the emulator only (KAVANA_ADB).')
os.makedirs(OUT, exist_ok=True)
PKG = P.PKG
LAB = f'{PKG}/xyz.tiresia.kavana.widget.WidgetLab'
fails = []
n = 0


def on(phase):
    return PHASE in ('all', phase)


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


def scroll_down():
    """A swipe from low on the screen to high, whatever the emulator's size (a small screen keeps its memory use down)."""
    w, h = (int(v) for v in P.adb('shell', 'wm', 'size').split()[-1].split('x'))
    P.adb('shell', 'input', 'swipe', str(w // 2), str(int(h * .79)), str(w // 2), str(int(h * .21)), '400')


def lab(set_, *extra):
    P.adb('shell', 'am', 'force-stop', PKG)
    P.adb('shell', 'am', 'start', '-n', LAB, '--es', 'set', set_, *extra)
    top = []
    for _ in range(8):  # until the lab shows its labels ("classic  (340×150)"); a fresh install is asked about hosting widgets first
        time.sleep(3)
        if P.find('Create widget and allow access?'):
            P.tap('Always allow')
            P.tap('Create', exact=True)
            continue
        top = texts()
        if extra or any('×' in x for x in top):
            break
    time.sleep(2)  # the widgets draw a moment after their labels
    top = texts() or top
    # the page is longer than the screen: what's below the fold
    scroll_down()
    time.sleep(1.5)
    return top + texts()


def has(t, *words):
    return all(any(w in x for x in t) for w in words)


def labp(set_, *extra):
    """Every part (four widgets) of a lab set, with no scrolling: the texts of all of them."""
    out = []
    for part in range(10):
        P.adb('shell', 'am', 'force-stop', PKG)
        P.adb('shell', 'am', 'start', '-n', LAB, '--es', 'set', set_, '--ei', 'part', str(part), *extra)
        t = []
        for _ in range(6):
            time.sleep(3)
            if P.find('Create widget and allow access?'):
                P.tap('Always allow')
                P.tap('Create', exact=True)
                continue
            t = texts()
            if any('×' in x for x in t):
                break
        if not any('×' in x for x in t):
            break
        time.sleep(1.5)
        out += texts() or t
    return out


def host_bounds():
    """The place on the screen of the widget the lab hosts (the first AppWidgetHostView): (left, top, right, bottom)."""
    import re
    import xml.etree.ElementTree as ET
    for _ in range(5):
        xml = P.adb('exec-out', 'uiautomator', 'dump', '--windows', '/dev/tty')
        try:
            root = ET.fromstring(xml[xml.find('<?xml'):xml.rfind('>') + 1])
        except ET.ParseError:
            time.sleep(1)
            continue
        for n in root.iter('node'):
            if 'AppWidgetHostView' in (n.get('class') or ''):
                return tuple(int(x) for x in re.findall(r'\d+', n.get('bounds')))
        time.sleep(1)
    return None


def look(path, box):
    """A screenshot of the box (and a picture of it to compare with another)."""
    from PIL import Image
    P.shot(path)
    return Image.open(path).convert('RGB').crop(box)


def differ(a, b):
    """How much of a picture is different from another, in percent."""
    from PIL import ImageChops
    d = ImageChops.difference(a, b).convert('L')
    return sum(1 for v in d.getdata() if v > 24) * 100.0 / (a.width * a.height)


def doc():
    return json.loads(P.adb('shell', 'run-as', PKG, 'cat', 'files/doc.json'))


print('install')
P.mac('adb uninstall ' + PKG + ' >/dev/null 2>&1 || true')
print(P.adb('install', '-t', APK, timeout=300).strip())
P.adb('shell', 'pm', 'grant', PKG, 'android.permission.POST_NOTIFICATIONS')
P.adb('shell', 'cmd', 'uimode', 'night', 'no')

try:
    if on('states'):
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

    if on('dark'):
        P.adb('shell', 'cmd', 'uimode', 'night', 'yes')
        time.sleep(2)
        t = lab('window')
        check('dark mode draws the same widgets', has(t, 'Miso is waiting', 'FOCUSING', 'Resume'))
        step('window-dark')
        P.adb('shell', 'cmd', 'uimode', 'night', 'no')

    if on('live'):
        print('\na whole block, from a widget (the real engine, a minute lasting three seconds: a pause is 15 s, and a screen read takes a few)')
        P.adb('shell', 'pm', 'clear', PKG)
        P.adb('shell', 'pm', 'grant', PKG, 'android.permission.POST_NOTIFICATIONS')
        P.adb('shell', 'am', 'start', '-n', f'{PKG}/xyz.tiresia.kavana.MainActivity', '--el', 'minute', '3000')
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
        try:  # the pause ends by itself after 15 s: tap straight away rather than read the screen twice
            P.tap('Resume', exact=True, wait=12)
            tapped = True
        except RuntimeError:
            tapped = False
        check('and its Resume button', tapped)
        check('Resume: FOCUSING again', P.wait_for('FOCUSING', 15))
        check('the countdown is a foreground service started by a tap on a widget',
              'isForeground=true' in P.adb('shell', 'dumpsys', 'activity', 'services', PKG))
        note = P.adb('shell', 'dumpsys', 'notification', '--noredact')
        mine = note[note.find(f'pkg={PKG}'):][:6000]
        check('the countdown notification is a Live Update with its stages, a check-in point, the kitten and a house',
              all(k in mine for k in ['ProgressStyle', 'android.progressSegments', 'android.progressPoints', 'android.progressTrackerIcon', 'android.progressEndIcon', 'android.requestPromotedOngoing']))
        check('and its lock-screen version has no title', 'android.title=String (Focusing)' in mine)
        check('with Pause and Park a thought on it', 'Park a thought' in mine and 'Pause' in mine)
        check('a check-in comes up on the widget', P.wait_for('BOO! CHECK-IN', 90))
        step('live-checkin')
        P.tap('Yes', exact=True)
        check('Yes: back to FOCUSING', P.wait_for('FOCUSING', 8))
        check('the kitten comes home, and the widget asks how it went', P.wait_for('CAME HOME', 120))
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

    if on('play'):
        print('\nthe playful widgets, in every state (a made-up snapshot each)')
        month = time.strftime('%B')
        t = labp('stats')
        check('Streak: the number and the words', has(t, '5', '12', 'days in a row'))
        check('Goal: the ring, what is left, reached, no goal', has(t, '1h 20', 'of 2 h', '40 min to go', '0m', '2 h to go', 'Goal reached', 'No goal set'))
        check('Missions: counts, the next one, Collect, all done, none', has(t, '2 of 3 missions', 'Bring 3 kittens home: 2 of 3', '+20', '3 of 3 missions', 'All done for today', 'No missions yet'))
        t = labp('pet')
        check('Pet the cat: the cat speaks, and the day so far', has(t, 'Mrrp?', 'Mrrp!', 'Prrrr…', 'TODAY', '1 h 20', '3 cats came home', 'Start 25'))
        check('Pet the cat, late: asleep (Boo only if he has been met)', has(t, 'Shh.', 'Shh. Boo is asleep.'))
        check('Pet the cat, a block running: the live clock instead of Start', has(t, 'FOCUSING'))
        check('Pet the cat, as a strip: Boo! Ready when you are.', has(t, 'Boo! Ready when you are.'))
        t = labp('clock')
        check('A cat with a day: the greeting by the hour', has(t, 'GOOD MORNING', 'GOOD AFTERNOON', 'GOOD EVENING', 'GOOD NIGHT'))
        check('and Boo is out late, with the time', has(t, 'BOO IS OUT', '8:15', '10:42', '11:10'))
        check('and what the cat is up to', has(t, 'Miso is growing. Stay with it.', 'Miso is asleep on the cushion. Shh.', 'Miso came home.'))
        t = labp('sky')
        check('Constellation: the month, the stars, the streak, the brightest day', has(t, month.upper()) and any(re.match(r'\d+ blocks?, \d+ stars?$', x) for x in t) and has(t, 'Brightest:', '5 days in a row'))
        check('Constellation: nothing yet; the last two weeks', has(t, 'No stars yet', 'No streak yet', '5 days', 'stars lit'))
        check('This week, as weather: the title, the best day, Today', has(t, 'This week, as weather', 'best:', 'Today', 'no blocks yet'))
        t = labp('friends')
        check("Friends' table: who is doing what, and how long they have left", has(t, 'Dana', '14 min left', 'Sam', 'on a break', 'Alex', 'not focusing', 'Kim', "isn't sharing", 'Refresh'))
        check("Friends' table: no friends, signed out, not sharing", has(t, 'No friends yet.', 'Sign in to see your friends here.', 'Open Chataltul', "You're not sharing your focus, so your friends' stays hidden."))
        step('play')

    if on('playdark'):
        print('\nthe playful widgets in dark mode')
        P.adb('shell', 'cmd', 'uimode', 'night', 'yes')
        time.sleep(2)
        t = labp('stats')
        check('dark: streak, goal and missions draw', has(t, 'days in a row', 'of 2 h', '2 of 3 missions'))
        t = labp('pet')
        check('dark: Pet the cat draws', has(t, 'Mrrp?', 'Start 25'))
        t = labp('clock')
        check('dark: the clock draws', has(t, 'GOOD MORNING', 'BOO IS OUT'))
        t = labp('sky')
        check('dark: the sky widgets draw', has(t, 'This week, as weather', 'Brightest:'))
        P.adb('shell', 'cmd', 'uimode', 'night', 'no')

    if on('pet'):
        print('\npetting the cat, on the widget (the real thing, not a made-up snapshot)')
        P.adb('shell', 'pm', 'clear', PKG)
        P.adb('shell', 'pm', 'grant', PKG, 'android.permission.POST_NOTIFICATIONS')
        P.adb('shell', 'am', 'start', '-n', f'{PKG}/xyz.tiresia.kavana.MainActivity')
        time.sleep(3)
        P.adb('shell', 'am', 'force-stop', PKG)
        lab('x', '--ez', 'live', 'true', '--es', 'provider', 'pet', '--ei', 'w', '170', '--ei', 'h', '170')
        P.adb('shell', 'am', 'force-stop', PKG)
        P.adb('shell', 'am', 'start', '-n', LAB, '--ez', 'live', 'true', '--es', 'provider', 'pet', '--ei', 'w', '170', '--ei', 'h', '170')
        time.sleep(6)
        box = host_bounds()
        check('the Pet the cat widget is on the lab screen', box is not None)
        if box:
            cx, cy = (box[0] + box[2]) // 2, (box[1] + box[3]) // 2

            def vibes(tag):
                d = P.adb('shell', 'dumpsys', 'vibrator_manager')
                open(os.path.join(OUT, f'vibrator-{tag}.txt'), 'w').write(d)
                return len(re.findall(r'(?i)waveform|Step=\d+ms', d)), len(re.findall(r'(?i)predefined|prebaked', d))  # (a purr shows as its pulses, Step=26ms…)

            def appears(path, away_from, also_not=None, secs=6):
                """Screenshots of the widget until it looks different from `away_from` (and from `also_not`): that picture, or None."""
                end = time.time() + secs
                while time.time() < end:
                    im = look(path, box)
                    if differ(away_from, im) > 1.5 and (also_not is None or differ(also_not, im) > 1.5):
                        return im
                return None

            idle = look(os.path.join(OUT, 'pet-0-idle.png'), box)
            w0, p0 = vibes('0-before')
            P.adb('shell', 'input', 'tap', str(cx), str(cy))
            hop = appears(os.path.join(OUT, 'pet-1-hop.png'), idle)
            check('a tap: she hops (the picture changes: a heart, a shadow)', hop is not None)
            time.sleep(6)
            check('and she settles again by herself', differ(idle, look(os.path.join(OUT, 'pet-1b-settled.png'), box)) < 1.0)
            w1, p1 = vibes('1-after-hop')
            P.adb('shell', f'input tap {cx} {cy} & sleep 0.3; input tap {cx} {cy}; wait')  # (a tap is a slow process to start: the two are about a third of a second apart)
            purr = appears(os.path.join(OUT, 'pet-2-purr.png'), idle, hop)
            check('two quick taps: she purrs (a picture that is neither sitting nor hopping)', purr is not None)
            time.sleep(9)
            check('and she settles again', differ(idle, look(os.path.join(OUT, 'pet-3-settled.png'), box)) < 1.0)
            w2, p2 = vibes('2-after-purr')
            print('  vibrations (purr pulses, clicks): before', (w0, p0), 'after the hop', (w1, p1), 'after the purr', (w2, p2))
            check('the phone buzzed for the hop (a click)', p1 > p0)
            check('and purred for the purr (a long waveform)', w2 > w1)

    if on('cafe'):
        print('\nthe café, from real blocks (a minute lasting three seconds)')
        P.adb('shell', 'pm', 'clear', PKG)
        P.adb('shell', 'pm', 'grant', PKG, 'android.permission.POST_NOTIFICATIONS')
        P.adb('shell', 'am', 'start', '-n', f'{PKG}/xyz.tiresia.kavana.MainActivity', '--el', 'minute', '3000')
        time.sleep(3)
        P.adb('shell', 'am', 'force-stop', PKG)
        lab('x', '--ez', 'live', 'true', '--ei', 'w', '340', '--ei', 'h', '150')
        P.adb('shell', 'am', 'force-stop', PKG)
        P.adb('shell', 'am', 'start', '-n', LAB, '--ez', 'live', 'true', '--ei', 'w', '340', '--ei', 'h', '150')
        time.sleep(5)
        for i in range(2):
            check(f'block {i + 1}: ready', P.wait_for('Miso is waiting', 10))
            P.tap('15', exact=True)
            check(f'block {i + 1}: the kitten comes home', P.wait_for('CAME HOME', 90))
            P.tap('Done', exact=True)
            time.sleep(1)
        time.sleep(4)
        d = doc()
        check('two blocks came home in the history (answered, and not wandered off)', sum(1 for b in d['blocks'] if b.get('outcome') and not b.get('gone')) >= 2)
        labp('cafe', '--ei', 'per', '2')  # a café widget on the screen: its pictures are drawn from the history
        time.sleep(8)
        ls = P.adb('shell', 'run-as', PKG, 'ls', '-l', 'files')
        print(ls)
        check('the café pictures are drawn (day and night)', 'cafe-day.webp' in ls and 'cafe-night.webp' in ls)
        sizes = {m.group(2): int(m.group(1)) for m in re.finditer(r'\s(\d+)\s+\S+\s+\S+\s+(cafe-\w+\.webp)', ls)}
        check('and they have something in them', all(v > 20_000 for v in sizes.values()) and len(sizes) == 2)
        month = time.strftime('%B')
        t = labp('cafe', '--ei', 'per', '2')
        check('the café widget by day: today, with its numbers', has(t, 'Your café today', 'came home', 'focused', 'treats earned', 'Open', '3 cats home today', 'No cats home yet'))
        step('cafe')
        P.adb('shell', 'cmd', 'uimode', 'night', 'yes')
        time.sleep(2)
        t = labp('cafe', '--ei', 'per', '2')
        check('and in dark mode: the month, at night', has(t, f'38 cats in {month}', 'wandered off', 'treats', '38 this month'))
        step('cafe-dark')
        P.adb('shell', 'cmd', 'uimode', 'night', 'no')

    if on('friends'):
        print('\nthe friends\' table, against a stand-in for the server\'s friends list (never the real one)')
        import http.server
        import threading
        now_ms = int(time.time() * 1000)
        reply = {'code': 'ABCDEFGH', 'sharing': True, 'friends': [
            {'name': 'dana', 'display': 'Dana', 'status': 'focusing', 'end': now_ms + 14 * 60_000, 'left': 14 * 60_000, 'id': 'b1', 'nap': 'b1'},
            {'name': 'sam', 'display': 'Sam', 'status': 'break', 'end': now_ms + 4 * 60_000},
            {'name': 'alex', 'display': 'Alex', 'status': 'idle'}]}
        seen = {'cookie': None, 'n': 0}

        class H(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                seen['n'] += 1
                seen['cookie'] = self.headers.get('Cookie')
                body = json.dumps(reply).encode() if self.path.endswith('api/friends') else b'{}'
                self.send_response(200 if self.path.endswith('api/friends') or True else 404)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            do_POST = do_GET

            def log_message(self, *a):
                pass

        srv = http.server.ThreadingHTTPServer(('127.0.0.1', 8494), H)
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        try:
            P.adb('shell', 'pm', 'clear', PKG)
            P.adb('shell', 'pm', 'grant', PKG, 'android.permission.POST_NOTIFICATIONS')
            P.adb('shell', 'am', 'start', '-n', f'{PKG}/xyz.tiresia.kavana.MainActivity', '--es', 'server', 'http://10.0.2.2:8494/')
            time.sleep(3)
            P.adb('shell', 'am', 'force-stop', PKG)
            lab('x', '--ez', 'live', 'true', '--es', 'provider', 'friends')
            P.adb('shell', 'am', 'force-stop', PKG)
            P.adb('shell', 'am', 'start', '-n', LAB, '--ez', 'live', 'true', '--es', 'provider', 'friends', '--ei', 'w', '340', '--ei', 'h', '150')
            time.sleep(6)
            check('signed out: it says so, and asks to open the app', P.wait_for('Sign in to see your friends here.', 8) and P.find('Open Chataltul') is not None)
            step('friends-signed-out')
            # an account on the phone (the sandbox's own sign-in is check-account's job): its name and a session cookie, in the app's preferences
            P.adb('shell', 'am', 'force-stop', PKG)
            import base64
            prefs = P.adb('shell', 'run-as', PKG, 'cat', 'shared_prefs/kavana.xml')
            prefs = prefs.replace('</map>', '<string name="account">{"name":"tester","display":"Tester"}</string><string name="cookie">testcookie</string></map>')
            P.adb('shell', f"run-as {PKG} sh -c 'echo {base64.b64encode(prefs.encode()).decode()} | base64 -d > shared_prefs/kavana.xml'")
            P.adb('shell', 'am', 'start', '-n', LAB, '--ez', 'live', 'true', '--es', 'provider', 'friends', '--ei', 'w', '340', '--ei', 'h', '150')
            time.sleep(6)
            if P.find('Refresh'):
                P.tap('Refresh', exact=True)
            check('signed in: the friends, from the list the server gives', P.wait_for('Dana', 15) and P.wait_for('Sam', 3) and P.wait_for('Alex', 3))
            t = texts()
            check('with how long they have left (counting down), a break, and not focusing', any(re.match(r'1[34]:\d\d left$', x) for x in t) and has(t, 'on a break', 'not focusing'))
            check('and the session cookie went with the request', seen['cookie'] is not None and 'testcookie' in seen['cookie'])
            step('friends')
            reply['sharing'] = False
            P.tap('Refresh', exact=True)
            check('not sharing: it says why, and hides them', P.wait_for("You're not sharing your focus, so your friends' stays hidden.", 15))
            reply['sharing'] = True
            reply['friends'] = [{'name': 'kim', 'display': 'Kim', 'status': 'hidden'}]
            P.tap('Refresh', exact=True)
            check("a friend who shares nothing: \"isn't sharing\"", P.wait_for("isn't sharing", 15))
            reply['friends'] = []
            P.tap('Refresh', exact=True)
            check('no friends yet: it says where they are added', P.wait_for('No friends yet.', 15))
            step('friends-none')
        finally:
            srv.shutdown()

    if on('tablet'):
        print('\na tablet\'s shapes (on a tablet-sized emulator: KAV_SIZE=tablet)')
        t = labp('tablet', '--ei', 'per', '2')
        check('the big Window, focusing: the clock, the title, the check-in, Pause', has(t, 'Draft the newsletter', 'Check-in in 6 min', 'Pause'))
        check('the big Window in its other states', has(t, 'Miso is waiting', 'Tap to start 25 minutes', 'BOO! CHECK-IN', 'Still on: Draft the newsletter?', 'MISO CAME HOME', 'Nice, 50 minutes', 'Not really', 'PAUSED', 'Resume', 'MISO WANDERED OFF', 'Open Chataltul'))
        check('the café, 6 x 4', has(t, 'Your café today', 'came home', 'treats earned'))
        check('the wide constellation: the words on the left', any(re.match(r'\d+ blocks?,\s*\d+ stars?$', x) for x in t) and has(t, '5 days in a row'))
        check('the short wide goal, streak and missions', has(t, 'Today', 'of your 2 h goal', '40 min to go', 'days in a row', '2 of 3', 'missions', 'Collect +20'))
        check('Pet the cat with its bubble, and the friends by day and at night', has(t, 'Mrrp?', 'Dana', '14 min left', 'Refresh'))
        step('tablet')

finally:
    P.adb('shell', 'cmd', 'uimode', 'night', 'no')
    P.adb('shell', 'am', 'force-stop', PKG)

print(f'\n{"all passed" if not fails else str(len(fails)) + " failed: " + "; ".join(fails)}')
sys.exit(1 if fails else 0)
