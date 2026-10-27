# Chataltul for Mac

The native Mac app for my focus app. The user-facing description and the screenshots are in the [main README](../README.md#mac-mac). This is how it is put together.

## Layout

```
mac/
  project.yml            XcodeGen spec (the Xcode project is generated, not kept)
  build.sh               builds Chataltul.app with swiftc, reading project.yml
  selftest.sh            core | ui | sync | launch | all
  Chataltul/
    Core/                no AppKit, so the self-test runs it without a screen:
                         EngineHost (the web's engine in JavaScriptCore), Models, SpeedBump (+ HoldTracker),
                         AwayTracker, PingTracker, Blocklist (lists, modes, schedules), GuardPolicy (what a list asks
                         for now, wait it out, locked, allowances), Breaks (cycles, ideas, eye breaks), Insights (the
                         numbers in Today), MacSettings, Sounds, SyncClient, DocStore
    App/                 AppKit and SwiftUI: AppDelegate (puts it together), Store (the one model), the menu
                         bar item, the popover, the cat's windows, Watcher (front app and browser address),
                         Hotkeys, Notifier, SoundPlayer, SyncController, Settings, the app window (AppWindow), the
                         café (CafeScene, a native port of web/cafe3d.js), the full Blocklist and Breaks views, the
                         full-screen break (BreakScreen) and Busy (is a call or full-screen video on?)
    Resources/js/        engine.js lines.js cats.js news.js: the web's, copied as they are; bridge.js: the
                         Android app's, with a few lines marked MAC
    Resources/           cats/ cafe/ audio/(meows) rig.json sounds.json AppIcon.icns
  Tests/main.swift       the core tests
  SelfTest/              the window and live tests, and the sync test (built in place of Main.swift)
  tools/                 sync-web.sh, check-web.js, test-takeoff.js, make-icon.sh
  docs/engine-takeoff.*  the one change I need in the web engine
```

## The engine

`sync-web.sh` copies `engine.js`, `lines.js`, `cats.js`, `news.js`, the paintings, `rig.json` and the meows from a commit of the web repo, and makes `sounds.json` out of `KINDS` in `sounds.js`. Nothing there is edited by hand: `tools/check-web.js` fails if the engine files differ from the recorded commit, or if the sound list differs from the web's.

```
KAVANA_REPO=~/src/kavana mac/tools/sync-web.sh            # the clone's HEAD
KAVANA_REPO=~/src/kavana mac/tools/sync-web.sh <commit>
```

`cats.js` is loaded before `engine.js`, because the engine writes a cat's name into a ping from it. The self-test checks that the end ping says Milo and not `pepper`.

`bridge.js` is a copy of the Android app's `bridge.js`: the same ops and the same state. The Mac adds an op, `takeOff`, and `state.caps.takeOff` (see below).

### One change I need in the web engine

"Take them off" on the away prompt moves a running block's end later by the minutes I was away. `E.extend` only adds time after a block has ended, and there is nothing that moves a running block's end, so the end ping would still fire at the old time. [engine-takeoff.md](docs/engine-takeoff.md) has the change (it works like a pause found out afterwards) as a patch. `tools/test-takeoff.js` and `selftest.sh core` try it on a copy. The app shows the button only when the engine has `E.takeOff`.

## Where things live on the Mac

The doc is `~/Library/Application Support/<bundle id>/doc.json`, written whole and swapped in. If it doesn't load, it is kept as `doc.json.bad-<time>` and never written over. This Mac's own settings (the blocklist, the shortcuts, the sound mix, the day's cycle) are `mac.json` beside it, and the recordings are in `audio/`. The session for the web account is in the Keychain.

## The self-test

`./selftest.sh` runs the core tests and the web checks. `ui` builds a copy of the app as `xyz.tiresia.chataltul.selftest` with `SelfTest/` in place of `Main.swift`, runs it in a folder of its own, and draws every screen in light and dark to `build/selftest/shots`. Then it wires up the real app (menu bar item, popover, cat, speed bump, shortcuts, away) with every window placed far off the screen, and calls what the buttons call: Leave anyway, the 15 seconds running out, Back to work, a check-in answered, an away return. No window shows, nothing is in the menu bar, no notification is asked for, and the real front app and any browser are never read (so no permission prompt can land on anyone's screen). A crash writes to the log and leaves quietly.

`sync` starts a sandbox copy of the web server on a free port of this Mac (`KAVANA_MAIL=outbox`, so the sign-in codes are written to a file the test reads), plays a second Mac through the API, and walks signing in with a wrong and a right code, syncing both ways, a recording being downloaded and played (muted), signing out and back in. It refuses any server that isn't on this Mac. It needs a clone of the web repo: `KAVANA_REPO`, or `kavana-web` or `kavana` beside this repo; without one it says SKIP.

## What I could not check without a person

The permission prompts (Notifications, and Automation for each browser), a real full-screen app with the cat over it, and the two-second hold with a real mouse. The code for each is there and its rules are tested; the system's side of them is not.
