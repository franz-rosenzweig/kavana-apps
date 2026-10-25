#!/usr/bin/env python3
"""Kavana's account check: signing in and syncing, on the emulator, against a SANDBOX server.

    KAVANA_ADB=<adb> python3 tools/check-account.py <app-debug.apk> <out-dir> <sandbox url> <sandbox state dir>

Start the sandbox from the kavana repo with its own state folder, and mail going to a file:

    KAVANA_STATE=/tmp/kavana-sandbox KAVANA_PORT=8493 KAVANA_MAIL=outbox node server.js

The sandbox url is how this computer reaches it (http://127.0.0.1:8493/); the emulator reaches the
same server at 10.0.2.2. This test makes and deletes accounts, so it refuses anything that isn't
this computer, and it runs on the emulator only.

It walks: a block on the phone before there's an account; signing in by email (the code read from
the sandbox's outbox), which makes the account and takes the block along; a second device (this
script, through the API) seeing it, adding to Later, and the phone getting that when it opens; a
password set on the account page; signing out (the history leaves the phone); signing in again with
the email and the password (it comes back); a password changed on the other device signing the
phone out; and deleting the account with its password (the history stays on the phone).

The sandbox sends at most 6 codes an hour to one address on this computer and this walk uses 3, so
restart the sandbox before a third run within the hour.
"""
import http.cookiejar
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

sys.path.insert(0, os.path.dirname(__file__))
import phone as P  # noqa: E402

APK, OUT, URL, STATE = sys.argv[1:5]
URL = URL.rstrip('/') + '/'
host = urllib.parse.urlparse(URL).hostname
if host not in ('127.0.0.1', 'localhost') or not P.LOCAL:
    sys.exit('check-account.py makes and deletes accounts: only against a sandbox on this computer, with the emulator (KAVANA_ADB).')
PHONE_URL = URL.replace(host, '10.0.2.2')
os.makedirs(OUT, exist_ok=True)
ACT = f'{P.PKG}/xyz.tiresia.kavana.MainActivity'
EMAIL = f'cat{int(time.time()) % 100000}@example.com'
NAME = EMAIL.split('@')[0]
PW1, PW2 = 'purring kettle 1', 'purring kettle 2'
fails = []
n = 0


def step(name):
    global n
    n += 1
    P.shot(os.path.join(OUT, f'{n:02d}-{name}.png'))
    print(f'  shot {n:02d}-{name}.png')


def check(what, ok):
    print(('ok   ' if ok else 'FAIL ') + what)
    if not ok:
        fails.append(what)
    return ok


def code_for(email, after):
    """The newest code mailed to that address since `after`, from the sandbox's outbox."""
    end = time.time() + 10
    while time.time() < end:
        try:
            for line in reversed(open(os.path.join(STATE, 'outbox.jsonl')).read().splitlines()):
                m = json.loads(line)
                if email in json.dumps(m.get('to', '')) and (m.get('at') or time.time() * 1000) >= after * 1000:
                    c = re.search(r'\b(\d{3} ?\d{3})\b', m.get('text', '') or json.dumps(m))
                    if c:
                        return c.group(1).replace(' ', '')
        except (OSError, ValueError):
            pass
        time.sleep(0.5)
    return None


class Web:
    """The other device: the API, with its own cookie."""
    def __init__(self):
        self.jar = http.cookiejar.CookieJar()
        self.op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar))

    def api(self, method, path, body=None):
        req = urllib.request.Request(URL + path, method=method, data=None if method == 'GET' else json.dumps(body or {}).encode(),
                                     headers={'Content-Type': 'application/json'})
        try:
            with self.op.open(req, timeout=15) as r:
                return r.status, (json.loads(r.read() or b'null') if r.status != 204 else None)
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read() or b'null')


def engine(doc, js):
    """Changes a doc with the engine, as the web app would: `js` gets E, doc and now."""
    code = ("const fs=require('fs'),vm=require('vm');const ctx=vm.createContext({console,crypto:require('crypto').webcrypto});"
            "for(const f of ['lines.js','engine.js'])vm.runInContext(fs.readFileSync(process.argv[1]+'/'+f,'utf8'),ctx);"
            "const E=vm.runInContext('Kavana',ctx);const doc=E.normalize(JSON.parse(fs.readFileSync(0,'utf8')));const now=Date.now();"
            + js + ";process.stdout.write(JSON.stringify(doc));")
    d = os.path.join(os.path.dirname(__file__), '../android/app/src/main/assets/js')
    return json.loads(subprocess.run(['node', '-e', code, d], input=json.dumps(doc), capture_output=True, text=True, check=True).stdout)


def doc():
    return P.adb('shell', 'run-as', P.PKG, 'cat', 'files/doc.json')


def prefs():
    return P.adb('shell', 'run-as', P.PKG, 'cat', 'shared_prefs/kavana.xml')


def launch():
    P.adb('shell', 'am', 'force-stop', P.PKG)
    P.adb('shell', 'am', 'start', '-n', ACT, '--es', 'server', PHONE_URL, '--el', 'minute', '1000')
    time.sleep(3)
    while P.find('Nice!', exact=True):
        P.tap('Nice!', exact=True)
        time.sleep(1)


def screen(name):
    P.tap('Menu', exact=True)
    time.sleep(1.0)
    P.tap(name, exact=True)
    time.sleep(0.8)


def wait(fn, secs=12):
    end = time.time() + secs
    while time.time() < end:
        if fn():
            return True
        time.sleep(1)
    return False


def type_into(hint, text):
    P.tap(hint)
    time.sleep(0.5)
    P.type_text(text)
    time.sleep(0.4)


def dialog_type(text):
    time.sleep(1.0)  # the dialog's field takes the focus by itself
    P.type_text(text)
    time.sleep(0.4)


print('install')
P.mac('adb uninstall ' + P.PKG + ' >/dev/null 2>&1 || true')
print(P.adb('install', '-t', APK, timeout=300).strip())
P.adb('shell', 'pm', 'clear', P.PKG)
P.adb('shell', 'pm', 'grant', P.PKG, 'android.permission.POST_NOTIFICATIONS')
P.adb('shell', 'appops', 'set', P.PKG, 'SYSTEM_ALERT_WINDOW', 'allow')
P.adb('shell', 'cmd', 'uimode', 'night', 'no')
web = Web()

try:
    launch()
    P.tap('Continue'); P.wait_for('Your café fills up', 8); time.sleep(1); P.tap('Continue'); P.wait_for('All set', 8); P.tap('Start Focusing')
    check('the start screen', P.wait_for('Add a title', 8))

    # A block before there's an account: it should come along.
    type_into('Add a title', 'Before the account')
    P.adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
    time.sleep(0.6)
    P.tap('Start', exact=True)
    check('the block ends', P.wait_for('How did it go?', 40))
    P.tap('Done', exact=True)
    time.sleep(2)
    while P.find('Nice!', exact=True):
        P.tap('Nice!', exact=True); time.sleep(1)

    print('\nsign in by email')
    screen('Settings')
    check('Settings offers Sign In', P.wait_for('Back up your history and sync it with the web', 6))
    step('settings-signed-out')
    P.tap('Sign In', exact=True)
    check('the sign-in page asks for an email', P.wait_for('Email Me a Code', 6))
    step('sign-in-email')
    type_into('you@example.com', EMAIL)
    t0 = time.time() - 1
    P.tap('Email Me a Code', exact=True)
    check('the code page', P.wait_for('We sent a 6-digit code', 10))
    code = code_for(EMAIL, t0)
    check('a code went to the outbox', bool(code))
    time.sleep(0.8)  # the code field takes the focus by itself
    P.type_text(code or '000000')
    step('sign-in-code')
    P.tap('Sign In', exact=True, last=True)
    check('signed in: Settings shows the account', P.wait_for('Backed up · syncs with your other devices', 12))
    check('the session cookie is kept', 'name="cookie"' in prefs())
    step('settings-signed-in')

    # The other device signs in with an emailed code too, and sees the phone's block.
    t0 = time.time() - 1
    web.api('POST', 'api/email/code', {'email': EMAIL})
    st, me = web.api('POST', 'api/email/signin', {'email': EMAIL, 'code': code_for(EMAIL, t0)})
    check('the other device signs in to the same account (not a new one)', st == 200 and me.get('created') is False and me.get('name') == NAME)
    ok = wait(lambda: 'Before the account' in json.dumps(web.api('GET', 'api/sync?rev=-1')[1]))
    check("the phone's block from before the account is on the server", ok)

    # The other device adds to Later; the phone gets it when it comes back to the front.
    st, r = web.api('GET', 'api/sync?rev=-1')
    mine = engine(r['doc'], "E.addLater(doc, 'From the other device', now)")
    st, r2 = web.api('POST', 'api/sync', {'rev': r['rev'], 'doc': mine})
    check('the other device syncs a change', st == 200)
    P.adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
    time.sleep(1)
    P.adb('shell', 'am', 'start', '-n', ACT)
    check('the phone pulls it when it opens', wait(lambda: 'From the other device' in doc(), 15))
    screen('Later')
    check('Later shows it', P.wait_for('From the other device', 6))
    step('later-synced')

    # A change on the phone goes up by itself.
    screen('Focus')
    type_into('Add a title', 'After the account')
    P.adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
    time.sleep(0.6)
    P.tap('Start', exact=True)
    time.sleep(2)
    check('the running block reaches the server', wait(lambda: 'After the account' in json.dumps(web.api('GET', 'api/sync?rev=-1')[1]), 15))
    P.tap('Cancel', exact=True)
    time.sleep(2)

    print('\nthe account page')
    screen('Settings')
    P.tap('Backed up · syncs with your other devices')
    check('the account page: email, password (none yet), username', P.wait_for(EMAIL, 6) and P.find('Set one', exact=True) and P.find(NAME, exact=True))
    step('account')
    P.tap('Password', exact=True)
    check('a password can be set without the old one (signed in by email just now)', P.wait_for('Set a Password', 6))
    dialog_type(PW1)
    P.tap('Set Password', exact=True)
    check('it says Password set', P.wait_for('Password set.', 6) or P.wait_for('Change', 4))
    st, _ = Web().api('POST', 'api/login', {'name': NAME, 'password': PW1})
    check('the password works on the server', st == 200)

    print('\nsign out, and in with the email and the password')
    P.tap('Sign Out', exact=True)
    P.wait_for('Sign Out?', 6)
    step('sign-out')
    P.tap('Sign Out', exact=True, last=True)
    check('signed out: back on Settings with Sign In', P.wait_for('Back up your history and sync it with the web', 10))
    check("the account's history left the phone", wait(lambda: 'Before the account' not in doc(), 6))
    P.tap('Sign In', exact=True)
    P.wait_for('Email Me a Code', 6)
    P.tap('Use a name and password instead')
    check('the name and password form', P.wait_for('Name or email', 6))
    type_into('Name or email', EMAIL)
    P.tap('Required')
    time.sleep(0.4)
    P.type_text(PW1)
    step('sign-in-password')
    P.tap('Sign In', exact=True, last=True)
    check('signed in with the email and the password', P.wait_for('Backed up · syncs with your other devices', 12))
    check('the history came back', wait(lambda: 'Before the account' in doc() and 'From the other device' in doc(), 10))

    print('\nsigned out from another device')
    st, _ = web.api('POST', 'api/account/password', {'password': PW2, 'current': PW1})
    check('the other device changes the password', st == 200)
    P.adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
    time.sleep(1)
    P.adb('shell', 'am', 'start', '-n', ACT)
    check('the phone notices it was signed out', wait(lambda: 'name="account"' not in prefs(), 12))
    check('and Settings offers Sign In again', P.wait_for('Back up your history and sync it with the web', 8))

    print('\ndelete the account')
    P.tap('Sign In', exact=True)
    P.wait_for('Email Me a Code', 6)
    if P.find('Use email instead'):
        P.tap('Use email instead')
        time.sleep(0.6)
    type_into('you@example.com', EMAIL)
    t0 = time.time() - 1
    P.tap('Email Me a Code', exact=True)
    P.wait_for('We sent a 6-digit code', 10)
    time.sleep(0.8)
    P.type_text(code_for(EMAIL, t0) or '000000')
    P.tap('Sign In', exact=True, last=True)
    check('signed in by email again', P.wait_for('Backed up · syncs with your other devices', 12))
    P.tap('Backed up · syncs with your other devices')
    P.wait_for('Delete My Account', 6)
    P.tap('Delete My Account', exact=True)
    check('deleting asks for the password', P.wait_for('Type your password to go ahead', 6))
    dialog_type(PW2)
    step('delete')
    P.adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')  # the keyboard away: its backspace key is called "Delete" too
    time.sleep(0.8)
    P.tap('Delete', exact=True)
    check('deleted: Settings offers Sign In', P.wait_for('Back up your history and sync it with the web', 10))
    check('the history stays on the phone', 'Before the account' in doc())
    st, _ = Web().api('POST', 'api/login', {'name': NAME, 'password': PW2})
    check('the account is gone from the server', st == 401)
finally:
    P.adb('shell', 'am', 'force-stop', P.PKG)

print(f'\n{"all passed" if not fails else str(len(fails)) + " failed: " + "; ".join(fails)}')
sys.exit(1 if fails else 0)
