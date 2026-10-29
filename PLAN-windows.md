# Chataltul for Windows: the plan

*Written 2026-10-27. Android and the iPad have theirs beside it (PLAN-android.md, PLAN-ipad.md).*

Windows is the easy one for the cat. Like Android and unlike the iPad, any app may put a window over every
other one, and Windows asks for no permission to see which app is in front or to read a browser's address.
So the Mac's whole design comes across: the kitten in the notification area instead of the menu bar, the cat
peeking up in the corner, the speed bump over the screen. It is C# and WinUI 3, with the web's `engine.js`
inside the app (in Jint), the same way the Mac runs it in JavaScriptCore. It grows the way the Mac did, a version at a time. Every build is made by GitHub Actions, on GitHub's Windows machines, and kept with the run;
releases are tagged `windows-vX.Y.Z`, like the Mac's `mac-vX.Y.Z`.

## 0.1: the part that keeps me focused (tried by hand, 28 October)

The tray icon with the kitten and its ring, the popover (Focus, Break, Long, Start; Pause, park, Stop; how
did it go), check-ins as the cat in the corner with Yes and Drifted and the reasons after Drifted, the
speed bump over apps and over sites in Chrome, Edge, Brave, Vivaldi, Opera and Arc (Firefox and Zen tried), park a
thought and start a block from anywhere, away from the PC, Windows notifications as the second chance,
Settings, open at sign-in, an installer for one person. What it does is in [windows/README.md](windows/README.md).

## 0.2: the account, the app window and the widgets (green 29 October)

Asked for after trying 0.1 (28 October): the account and its sync with the web (the Android and Mac API, sign in
by email code, the session in Credential Manager); the app window with Today (day, week, month, year), the café
in 3D (the Mac's CafeScene, drawn with Win2D), History and Later; widgets on the desktop, the phone's set as
Windows cards (the Window, Quick start, Today, Later, Pet the cat, a clock, Streak, Goal, Missions,
Constellation, the week as weather, the café), since Windows' Widgets board only takes a signed, packaged app;
and the taskbar's jump list (Start 25, Start 50, Park a thought, Open the café). What it does is in
[windows/README.md](windows/README.md).

## 0.3: the full Blocklist, breaks on every screen, the sounds (windows/, now)

The Mac's 0.2, on Windows:
- **The full Blocklist**: lists with a color each, and for each one the speed bump, **wait it out** (30
  seconds with the cat before it opens) or **locked** (it doesn't open until the block ends, the block can't
  be stopped after its first ten seconds, and Chataltul won't quit meanwhile); watched outside blocks too, on
  weekdays between two times or always, with an allowance of minutes a day. The settings file already has the
  Mac's shape for all of it, so nothing has to move. In Settings → Blocklist (the app window links to it).
- **The ready-made lists** (Adult, Social media, Dating, Gambling, Games, Video and music, News, Shopping), from
  the same public lists as the Mac (UT1, StevenBlack, Hagezi), downloaded over https when switched on and
  looked at again weekly, kept as the Mac's sorted 64-bit hashes (FNV-1a and its mixer, unchanged, so the same
  list gives the same file), **Always allow**, and **Add a list from a link**. A port of `Categories.swift`,
  `ListStore.swift` and `DomainSet.swift`, with the Mac's tests (a fake web in place of its small server).
- **Breaks on every screen**: a soft full-screen break on each monitor, with the cat stretching, an idea, the
  time left, Postpone and Skip (or neither, with strict breaks); the cycles (Pomodoro, 52 / 17, Deep work,
  Flowtime); eye breaks every 20 minutes as a card in the corner. They and the check-ins wait while a call is
  on (an app has the microphone) or something is full screen. Each break window simply covers its screen, above
  everything, with no presenter that Windows could change, so it has one state.
- **The sounds**: the web's 39, two at once, each recording downloaded the first time and kept, and the meow
  when a kitten comes home (Windows' media player; the list is the Mac's `sounds.json`, made from the web's
  `sounds.js`), and the Sound widget.

## 0.4: the reports

- **Where each block went**, app by app (and a site's name in a browser, never the address), noted each
  second while a block runs and the PC isn't idle; the top three when a block comes home, and "Where your
  blocks went" in Today. Kept in `timing.json` on this PC only, with a switch to turn it off and a button to
  forget it.
- **The week in review**: a few words about the week made on this PC, each day stacked by tag against the
  goal, how the blocks went, what pulled me away and when; and "Ask about your week", answered from the
  numbers (Windows has no model on the PC to ask, so the Mac's fallback answers are the answers).
- **Streaks with a pause** (one missed day a week), and **export**: the blocks and where the time went as
  CSV, the review as Markdown.
- **Focus Assist**: Windows' Do not disturb on while a block runs, if it can be set from an app; else the
  two boxes for programs to run at a block's start and end, as the Mac has for Shortcuts.

## Later, for any of them

The engine off the UI thread, if a real account's history makes a press slow (the core tests time its parts on a
year of blocks: the engine's own work is about a fifth of an op; the bridge's state around it is the rest).
Friends, the friends' table widget and napping together (the account's parts about other people, as on the
phone and the Mac); signing the app so Windows knows the publisher, which would also let it be a packaged app
with widgets on Windows' own Widgets board (the Microsoft Store signs for free, after an ID check); an ARM64
build; updates from the releases.
## What only I can do

1. Say go for each push and each release (the repo is public: the Actions minutes cost nothing).
2. Run the installer once on my own PC and tell Claude what Windows said: the tray icon among the hidden ones,
   the first notification, the cat over a real full-screen app.
3. **For the web repo**, the same two engine changes the Mac asks for (`mac/docs/engine-takeoff.md`):
   `E.takeOff`, so "Take them off" can show, and breaks of 17, 20 and 25 minutes in `E.BREAKS`. Windows
   picks both up from the Mac's synced copy with no change of its own.
