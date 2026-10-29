# Chataltul for Windows

The native Windows app for my focus app: a kitten in the notification area, a timer, check-ins, and a cat
that walks over the apps and sites that distract me. Like the Mac and Android apps it runs the web app's own
`engine.js`, here in [Jint](https://github.com/sebastienros/jint) inside the app, and draws everything
itself in WinUI 3, so the kittens, the treats and the check-ins follow the same rules on every device.
It is built by GitHub Actions only; nothing is built on my PCs. What comes after 0.4 is in
[PLAN-windows.md](../PLAN-windows.md).

It looks like Windows 11, not like the Mac: the popover is a flyout like the ones Windows' own tray icons open
(acrylic, the system's rounded corners), the cat's words come on a card like a Windows notification, Settings
has Mica and the tall title bar of Windows' own Settings, and every button, switch and box is Windows' own, in
the accent color the person chose. What is Chataltul's is what it shows: the paintings, the sky in the round
window, the kitten's ring.

## Version 0.1: the part that keeps me focused

- **The tray icon** is the kitten, and while a block runs, a ring around it that empties with the time. It
  has a state for ready, focusing, a check-in waiting (an orange dot), paused, on a break and came home, in
  colors for a dark or a light taskbar. The notification area has no room for the time beside the icon, so it
  is in the icon's tip: "Chataltul · 24:59 · Write the intro". A right-click has Open, Today and the café,
  Widgets, Settings and Quit.
- **The popover**, from a click on the icon, has what the Mac's has: the round window with the kitten growing
  in it, the clock, an optional title, Focus, Break and Long (with their lengths), and Start. While a block
  runs it has Pause (five minutes at most), park a thought and Stop (inside the first ten seconds it takes the
  block back; after that it asks "Sure?"). When the kitten comes home it asks how it went, with a note, and
  offers the break. "Open the café" opens the app window's café (0.2).
- **Check-ins are the cat peeking up in the corner of the screen**, with Yes and Drifted, on top of whatever
  I'm doing, without taking the keyboard from it, and a click anywhere but the card goes through to the app
  underneath. After Drifted the cat asks what pulled me away (phone, the web, messages, thoughts, tired, or
  other). Ctrl+Alt+Y and Ctrl+Alt+D answer from anywhere, and only while a check-in waits, so those keys are
  free the rest of the time. With the cat off, or if nobody answers in 90 seconds, it's a Windows
  notification with Yes and Drifted on it.
- **The speed bump.** While a block runs and an app on my list comes to the front, or a site on my list in
  Chrome, Edge, Brave, Vivaldi, Opera or Arc (Firefox and Zen are tried, not yet confirmed), the cat walks over it: the screen dims (all but the
  taskbar), "Boo! You're focusing.", 15 seconds to come back, and a big "Back to work" that takes me back to
  the window I was in. "Leave anyway" has to be held for two seconds, and then the kitten wanders off and the
  block goes on without it. No close, no pause, no snooze; Alt+F4 does nothing to it. If I do nothing, the 15
  seconds run out and the kitten wanders off the same way. Once it has gone, the cat doesn't come up again for
  that block.
- **Park a thought** with Ctrl+Alt+P, from any app: a field opens, and the thought goes to Later and onto the block.
- **Start a block from anywhere** with Ctrl+Alt+Space: it opens the popover with the cursor in the title, and
  pressing it again starts the block.
- **Away from the PC.** More than two minutes without a key or a click in the middle of a block (or the
  screen locked, the display off, or the PC asleep that long) asks "count them?" when I'm back. Being away is
  not drifting, so the kitten stays. "Take them off" waits for the web engine's `E.takeOff`
  (`mac/docs/engine-takeoff.md`), as on the Mac, so for now that button is hidden and the minutes count.
- **Settings**: General (open at sign-in, the cats, the cat in the corner), Blocks and check-ins (how often,
  ask or nudge, pausing, extra time, away), Blocklist (apps from the ones open, or any program; sites; the
  speed bump on or off; reading the browser), and Shortcuts (press the new keys). Ctrl+Plus, Ctrl+Minus,
  Ctrl+0 and Ctrl+wheel change the size of its text, and the size is kept.
- **What it asks for: nothing.** Windows has no permission for any of it. The first time it reads a browser
  during a block, the cat says so plainly, once, and Settings can turn it off. It only ever uses the site's
  name, and it stays on the PC.

## Version 0.2: the account, the app window and the widgets

- **Sign in by email code, and sync with the web**, the same API as the phone and the Mac (Settings → Account
  and sync): type an email, Chataltul emails a 6-digit code, and that's it (a name and a password work too, and
  a password can be set afterwards). This PC's blocks come along into the account, and from then on the web, the
  phone, the Mac and this PC have the same blocks, Later, cats and settings: a change goes up a moment after it's
  made, and what was done elsewhere comes in when Chataltul starts and every 30 seconds, merged by the engine
  exactly as the web merges it. Signing out takes the account's history off the PC (it's safe on the server);
  the account can be deleted from here too. The session is kept in Windows' Credential Manager, for this Windows
  account alone. Without an account nothing leaves the PC, and the widgets' places never do either way.
- **The app window** (from the popover's new button, the tray's right-click, or the jump list): Today, with
  Day, Week, Month and Year (what was focused and how it compares with the period before, cats that came home,
  drifts or wanderings off, days in a row, the café of that period, the timeline of the day and when I focus
  most, or each day's or month's bar and the time by tag), the **Café** in 3D (drawn natively from the web's
  paintings and laid out by the web's own table, with the light of the time of day: drag to turn it, click a
  cat for its name), **History** and **Later** (add, tick off, start a block with one). It has Mica, a tall
  title bar and the text-size keys, like Settings, and Blocklist, the shop and Settings at the bottom.
- **Widgets on the desktop**, like the phone's on its home screen (Settings → Widgets, or the tray's
  right-click): the Window (in three sizes: ready with Start, 15 and 50; focusing with the countdown, Pause,
  park a thought and Stop; a check-in with Yes and Drifted; came home with Done, Partly and Not really; paused;
  a break), Quick start (15, 25, 50, a stopwatch), Today (the goal as a ring, the streak with a paw for each day,
  the missions and Collect), Later, Pet the cat (a click hops, another purrs; asleep after ten at night, Boo if
  I've met Boo), A cat with a day (a clock), Streak, Daily goal, Missions, Constellation (a star for each block
  that came home this month), This week as weather and Your café. They sit on the desktop under every window,
  never take the keyboard, move by dragging, and a right-click has their size and Remove. "Hide block titles"
  keeps what a block is called off them.
- **The jump list**: a right-click on Chataltul's taskbar button or Start menu entry has Start 25, Start 50,
  Park a thought and Open the café.

Windows' own Widgets board (Win+W) only takes widgets from a signed, packaged app, so these widgets are
Chataltul's own on the desktop instead; the phone's lock-screen countdown, Quick Settings tiles and live wallpaper
have no place on Windows.

## Version 0.3: the full Blocklist, breaks on every screen, the sounds

- **The full Blocklist** (Settings → Blocklist): lists, each with a color, its apps and sites, and what happens
  when one is opened: the **speed bump**, **wait it out** (30 seconds with the cat before it opens, every time,
  though a visit that waited may stay a while) or **locked** (it doesn't open until the block ends, the block
  can't be stopped after its first ten seconds, and Chataltul refuses to quit meanwhile). A list can also be
  watched outside blocks: on weekdays between two times, or always, with an allowance of minutes a day; when the
  allowance is used up, it stops for the rest of the day.
- **The ready-made lists**: each list can switch on Adult, Social media, Dating, Gambling, Games, Video and music,
  News or Shopping, on top of the sites typed in. They are the Mac's public lists (UT1, StevenBlack, Hagezi),
  none of them bundled: switched on, a list is downloaded over https and looked at again about once a week, kept as
  the Mac's sorted 64-bit hashes (FNV-1a and its mixer, so the same list gives the same file), and a download that
  goes wrong never replaces the copy already here. **Always allow** keeps a site out of them, and **Add a list from
  a link** takes any https hosts file, list of domains or AdGuard list.
- **Breaks on every screen**: a soft full-screen break on each monitor, the night sky, the cat stretching, an idea
  from my own list, the time left, Postpone and Skip (or neither, with strict breaks). The cycle can be Pomodoro,
  52 / 17, Deep work or Flowtime (a stopwatch, with a break a fifth as long), and a break can start by itself when
  the kitten is home. **Eye breaks**, every 20 minutes of a block, are a 20-second card in the corner. They and the
  check-ins wait while a call is on (an app has the microphone) or something is full screen.
- **The sounds**: the web's 39, up to two at once, each with its volume, from the popover's sound button. A
  recording downloads from the web app the first time it's picked and stays in the app's folder; brown and white
  noise are made on the PC. They play while a block runs, fade in and out, and stop on a pause. A kitten that
  comes home meows. There's a Sound widget too.

## Version 0.4: the reports

- **Where each block went, app by app.** While a block runs, Chataltul notes which app is in front each second
  and, in a browser, the site's name (never the address or the page); not while the PC is idle or the block is
  paused. When a block comes home the popover shows where it went (the bar and the top three, with what's on a
  list in the caution color), and Today adds "Where your blocks went" for a day, a month or a year. It's kept in
  `timing.json` on this PC, it isn't in the doc the sync carries, and Settings → General turns it off and forgets
  it all.
- **The week in review** (Today on Week, or the popover's chart button): a few words about the week made on this
  PC (a good, uneven or quiet week, the hours and blocks, the best day, what took most of it, what usually pulled
  me away and when, and what might help), each day stacked by tag against the goal, the streak, how the blocks
  went and what pulled me away. **Ask about your week** answers a few kinds of question (best day, when I focus,
  how much, what pulled me away, tags, streak) from the numbers; Windows has no model on the PC to ask.
- **Streaks with a pause**: one missed day a week doesn't break the streak (weeks start on Monday); two in a row
  still end it. Today shows "5 days · in a row · 1 pause left". The web's own streak has no pause, so the two can
  differ.
- **Export** from Today: the blocks as a spreadsheet (CSV), where the time went as a spreadsheet, the review in
  words as Markdown.
- **At a block's start and end**, Settings → General can run a program, a shortcut, a script or a link, with
  what follows it as its arguments (quote a path with spaces): Windows doesn't let an app turn Do not disturb
  on, but a shortcut that does can be named here. A block already going when Chataltul starts doesn't run it
  again.

## Layout

```
windows/
  Chataltul.Core/        no window, plain net10.0, so the tests run anywhere:
                         EngineHost (the web's engine in Jint), Models (what the bridge hands back), SpeedBump
                         (+ HoldTracker), AwayTracker, PingTracker, Blocklist (the Mac's lists, modes and
                         ready-made lists in one file shape, and the browsers), WinSettings (windows.json, the
                         shortcuts, the widgets' places), TrayArt (the tray icon, drawn with no drawing library),
                         SyncClient (the account's API), Insights (the numbers in Today), Widgets (which there are,
                         and what they draw from the history), GuardPolicy (wait it out, locked, schedules,
                         allowances), DomainSet, Categories and ListStore (the ready-made lists), Breaks (cycles, eye
                         breaks), Sounds (the web's list), Timing (where each block went), Review (streaks with a
                         pause, the week in words, answers, export), Png, Art, Format
  Chataltul/             the app, WinUI 3 (Windows App SDK 1.8), unpackaged and self-contained:
                         Program (one at a time), App, Controller (puts it together), Store (the one model),
                         MessageWindow (the tray icon, the shortcuts, lock, sleep and sign-out), Watcher (the
                         front window, and a browser's address with UI Automation), AwayMonitor (+ Notifier),
                         Floating (the see-through, topmost windows), Popover, CatCorner, BumpWindow (+ the hold,
                         the park field), RoundWindow (the round window with the cat), MicaShell (a Windows 11
                         window: Mica, the tall title bar, the text-size keys), SettingsWindow, MainWindow (the app
                         window), CafeView (the café in 3D, in Win2D), DesktopWidgets (the widgets and their host),
                         SyncController (+ the session in Credential Manager), JumpList, BreakScreen, SoundPlayer, Busy (a
                         call or something full screen), Reports (where it went, the week in review, export),
                         Theme, SelfTest
  Tests/Program.cs       the core tests
  tools/                 convert-art.py (the paintings as PNG), make-icon.ps1 (Chataltul.ico from the Mac's icon)
  selftest.ps1           the self-test of the built app
  build.ps1              the workflow's steps, on a PC that has the SDK
  installer.iss          the Inno Setup installer, for one person, no administrator
```

## The engine

The engine's files are the Mac's, read from `mac/Chataltul/Resources/js` at build time: `lines.js`,
`cats.js`, `engine.js` and `news.js` copied from the web by `mac/tools/sync-web.sh` (which records the commit
in `WEB_COMMIT`, and `mac/tools/check-web.js` checks they are unedited), and the Mac's `bridge.js`, which is
Android's with the lines marked MAC. Windows adds no copy of its own, so all three apps run the same engine,
and `mac/tools/sync-web.sh` brings a new one to the Mac and Windows together. `cats.js` is loaded before
`engine.js`, because the engine writes a cat's name into a ping from it; the tests check the end ping says
Milo and not `pepper`.

Jint is a JavaScript interpreter written in C#, with nothing native to ship. One engine holds the doc between
calls, on the app's UI thread, and is asked only when something happens (a press, a ping coming due), never
each second. The core tests time an op on a doc with a year of blocks in it, and fail over two seconds. The doc is
`%LOCALAPPDATA%\Chataltul\doc.json`, written whole and moved into place; one that doesn't load is kept as
`doc.json.bad-<time>` and never written over. The Windows app's own settings are `windows.json` beside it
(the same shape as the Mac's `mac.json` where they share things), and `chataltul.log` is what went wrong
where nobody saw it.

The paintings are the Mac's WebP files made into PNG by `tools/convert-art.py` before each build (some
Windows have no WebP reader). Nothing in them is changed.

## How the cat gets over other apps

The cat's windows (`Floating.cs`) have no frame, stay on top of other windows, aren't in the taskbar or
Alt+Tab, and are see-through where they don't paint: a WinUI window whose backdrop is a clear brush of the
system compositor. The cat in the corner never becomes the active window (`WS_EX_NOACTIVATE`), so typing goes
on in the app I'm in, and its window's region is only the card and the cat, so a click anywhere else in its
corner goes through. The speed bump does take the front and the keyboard, on purpose.

The front window comes from `SetWinEventHook(EVENT_SYSTEM_FOREGROUND)`, and is looked at again once a second
while a block runs; its program is found by its file (`discord.exe`), and a Store app inside its
ApplicationFrameHost frame. A browser's address comes from UI Automation: the first text box of a Chromium
browser's window, Firefox's `urlbar-input`, read on a thread of its own and not while I'm typing in it.

## Build it

On GitHub: every push that touches `windows/`, the Mac's resources or the engine patch runs the Windows
workflow (`.github/workflows/windows.yml`), and the run keeps the installer, a portable zip, the self-test's
pictures of every screen and its log. A tag `windows-vX.Y.Z` also makes the release (never the repository's
Latest, which Obtainium reads for Android).

On a Windows PC with the .NET 10 SDK, Python with Pillow, and (for the installer) Inno Setup 6:

```powershell
windows\build.ps1            # build\Chataltul is the app; build\out the installer
```

The run's artifact is big (about 180 MB unpacked, as Moshe's): the Windows App SDK 1.8 package brings its AI and
ML parts along, which the app doesn't use. Trimming them is for later.

The installer is for one person and needs no administrator: it copies the app to
`%LOCALAPPDATA%\Programs\Chataltul`, and asks whether to start it at sign-in. The app isn't signed, so
Windows says the publisher is unknown the first time: More info › Run anyway. Windows 11 puts a new tray icon
among the hidden ones; the cat says so the first time, and where to drag it.

## Test it

```powershell
dotnet run --project windows\Tests     # the core tests, anywhere the SDK is (Linux too)
windows\selftest.ps1                   # the built app on a scratch folder, every window off the screen
```

The core tests run the engine headless in Jint: a block, its pings coming due (and the old ones dropped after
a sleep), the check-in answers with their reasons, the end and its treats, the cat names in the pings, the
away op, pausing, breaks, a dump and a load; then the engine change we ask the web for
(`mac/docs/engine-takeoff.patch`) applied to a copy, and the takeOff op with it. Then the rules with no
window: the speed bump, the hold, being away, the blocklist and browsers, the settings file and its
defaults, the doc file, the tray icon's states and pictures, the missions and rewards, Today's numbers for
each span, the widgets' rules, the account client's cookie and words, the words.

The self-test runs the real app with `--selftest`, wired up as it ships, on a scratch folder, with no tray
icon, no notification, and no reading of the real front window or any browser; the clock is moved on rather
than waited for. It walks the start shortcut, the speed bump on an app and a site (a plain press, a short
hold and a two-second hold of Leave anyway, the 15 seconds running out, Back to work), a stop in the first ten
seconds, a check-in in the corner with Drifted and its reason, being away, parking a thought, the end and its
outcome, the break offered and a break longer than the engine's; the app window on each page and span, every
widget in each size (and what their buttons do), and the jump list's tasks. Every screen is drawn to a PNG in
light and in dark. With -Sync it signs in against a sandbox copy of the web server on that machine (a wrong
code, the right one from the server's outbox, a second device through the API, syncing both ways, signing out
and back in); it refuses any server that isn't on the machine. The workflow adds `-OnScreen` (on its own desktop: real pixels show the windows are see-through and
the cat's corner lets clicks through; Leave anyway is held with the real mouse), `-Browser` (Edge and Chrome open
a page on that machine and their address is read; Firefox is tried and noted, since a fresh Firefox there never shows the page) and `-Launch` (the app as it ships starts,
sits in the tray and quits).

## What I could not check without a person

The tray icon's place among the hidden icons on Windows 11, and the popover's place next to it on a taskbar
at the top or the side; a real full-screen game with the cat over it; notifications with their buttons; and
how each browser's address box reads in another language or after an update. The code for each is there and
its rules are tested; Windows' side of them is not.
