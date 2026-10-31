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
                         AwayTracker, PingTracker, Blocklist (lists, modes, schedules), Categories (the ready-made lists),
                         ListStore (their download), DomainSet (the hashes and the list reader), GuardPolicy (what a list asks
                         for now, wait it out, locked, allowances), Breaks (cycles, ideas, eye breaks), Insights (the
                         numbers in Today), Timing (where each block went), Review (streaks with a pause, the
                         week in words, answers, export), MacSettings, Sounds, SyncClient, DocStore, WidgetSnapshot
                         (what the widgets show, and what changes in them by itself)
    App/                 AppKit and SwiftUI: AppDelegate (puts it together), Store (the one model), the menu
                         bar item, the popover, the cat's windows, Watcher (front app and browser address),
                         Hotkeys, Notifier, SoundPlayer, SyncController, Settings, the app window (AppWindow), the
                         café (CafeScene, a native port of web/cafe3d.js), the full Blocklist and Breaks views, the
                         full-screen break (BreakScreen), Busy (is a call or full-screen video on?), the week in review
                         (WeekReview), Exporter, OnDevice (the model on the Mac, if there is one), ShortcutRunner and
                         WidgetFeed (writes what the widgets show)
    Resources/js/        engine.js lines.js cats.js news.js: the web's, copied as they are; bridge.js: the
                         Android app's, with a few lines marked MAC
    Resources/           cats/ cafe/ audio/(meows) rig.json sounds.json AppIcon.icns
  Widgets/               the desktop widgets, an app extension inside the app: Widgets.swift (the six widgets and
                         their timelines) and WidgetViews.swift (their faces, which the self-test draws too)
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

## The ready-made website lists

Nothing is bundled. `Categories.swift` is the catalog: each category (Adult, Social media, Dating, Gambling, Games, Video and music, News, Shopping) is made of one or two public lists, each with its address, licence and credit.

| Category | Lists |
| --- | --- |
| Adult | Hagezi NSFW (GPL-3.0), StevenBlack porn-only (MIT) |
| Social media | UT1 social_networks (CC BY-SA 4.0), StevenBlack social-only (MIT) |
| Dating | UT1 dating |
| Gambling | UT1 gambling, StevenBlack gambling-only |
| Games, Video and music, News, Shopping | UT1 games, audio-video, press, shopping |

UT1 is the Université Toulouse Capitole blacklist, `https://dsi.ut-capitole.fr/blacklists/`. Its archives are read with `/usr/bin/tar -xzO` to memory, so nothing in an archive is written anywhere.

`ListStore.swift` downloads a list when it is switched on (and about weekly after that, checking again every six hours while the app runs), asks with the `ETag` and `Last-Modified` it was given so an unchanged list costs a `304`, refuses more than 64 MB, and only takes https (plain http only to this Mac, for the tests). The answer is read as a hosts file, a plain list of domains or AdGuard rules; only whole domains count (`||example.com^`, not paths, wildcards or `@@` exceptions), a leading `www.` is dropped, and local names and addresses are skipped. What is kept is `lists/<list>.chd`: a sorted array of 64-bit hashes (FNV-1a and a mixer, which must not change), so a lookup is a binary search for the host and each parent of it, never a single name like `com`, and the names themselves aren't on disk. A list that comes back as a web page, or with fewer domains than a floor set per list, is refused and the copy already here stays. A failed list is tried again after an hour.

A list's typed sites are checked first, then its ready-made lists unless the host is under one of its **Always allow** sites. One lookup across all eight categories takes a few microseconds; all of them together are about 2.5 MB on disk.

## The desktop widgets

`Widgets/` is a WidgetKit extension, `ChataltulWidgets.appex` in the app's `Contents/PlugIns`, built by `build.sh` next to the app with swiftc and signed before it. Two things about it are easy to get wrong:

- **An extension starts in `NSExtensionMain`.** Xcode links extensions with `-e _NSExtensionMain`. Without it, the bundle's own `main` runs and WidgetKit crashes it on launch. `build.sh` links it that way, and `release.sh` checks it.
- **macOS only runs a new widget when the gallery opens.** Until someone opens Edit Widgets, chronod logs "purging instead because we shouldn't cache it … isApple? false" for every widget that isn't Apple's, and never starts it. That isn't a refusal. A widget signed with my own identity, not Apple's, shows in the gallery and runs. I checked that on 30 Oct 2026 with a throwaway app, on macOS 27.0.1.

An extension has to be sandboxed, so it can't read the app's data. Instead the app writes `widget/now.json` in its own folder (`WidgetFeed`), with the cat's four paintings (`widget/art/`) and a still picture of today's café (`cafe-light.png`, `cafe-dark.png`, drawn by `CafeScene` standing still) beside it. The extension's entitlements let it read that folder and nothing else. It's a temporary exception, a path from the home folder, so there is no App Group and no team id is needed. The app never writes into the extension's container, which would have macOS ask the person about "data from other apps". `now.json` has numbers, the block's title, the cat's name and the thoughts in Later, never a site, a browser or an app. The self-test puts a site in the time log and checks it isn't there.

The app writes `now.json` after every change the engine makes, when its settings change, at midnight and every ten minutes (for the café's light), only when something in it is different, and then asks WidgetKit to reload. A widget's timeline needs nothing from the app while a block runs: `Text(timerInterval:)` counts the clock down by itself, and the Focus widget has an entry each minute for the ring and the cat growing. After midnight the day's numbers read as nothing yet, even before the app has written.

A click on a widget is a `chataltul://` link: `start?minutes=25` (starts a block if nothing is on, else opens the popover), `open`, `park`, `window/today`, `window/later` or `window/cafe`. Only the app's own bundle id takes these links: `build.sh` removes them from any copy built under another id, so a test copy can't catch them.

macOS takes note of every app it comes across, wherever it was built. A copy left with the real bundle id could then have its widgets in the gallery beside the installed app's. So `release.sh` deletes its unzipped copy once the zip is made, and `selftest.sh` makes macOS forget its test copies after each run.

## Where things live on the Mac

The doc is `~/Library/Application Support/<bundle id>/doc.json`, written whole and swapped in. If it doesn't load, it is kept as `doc.json.bad-<time>` and never written over. This Mac's own settings (the blocklist, the shortcuts, the sound mix, the day's cycle) are `mac.json` beside it, the downloaded ready-made lists are in `lists/`, the recordings are in `audio/`, and what the widgets show is in `widget/`. The session for the web account is in the Keychain.

## The self-test

`./selftest.sh` runs the core tests and the web checks. `ui` builds a copy of the app as `xyz.tiresia.chataltul.selftest` with `SelfTest/` in place of `Main.swift`, runs it in a folder of its own, and draws every screen in light and dark to `build/selftest/shots`. Then it wires up the real app (menu bar item, popover, cat, speed bump, shortcuts, away) with every window placed far off the screen, and calls what the buttons call: Leave anyway, the 15 seconds running out, Back to work, a check-in answered, an away return. No window shows, nothing is in the menu bar, no notification is asked for, and the real front app and any browser are never read (so no permission prompt can land on anyone's screen). A crash writes to the log and leaves quietly.

`ui` also draws each widget in each size, light and dark, from the `now.json` the real `WidgetFeed` writes: ready, a block on, a check-in, paused, a break and a cat that came home. The live part clicks the widgets' links (Start 50, Start while a block is on, Park a thought, Later, a link it can't read). `launch` checks that the shipped app has the widgets inside, signed and sandboxed, and that it writes their file when it starts.

`core` also starts a small web server on this Mac (`python3 -m http.server`) with lists of every kind, and some that aren't (a web page, a nearly empty list, a 404, one too big, a tar.gz), and downloads them with the real code, including a second ask that gets a `304`. `ui` draws the Blocklist with the ready-made lists in each state and has the cat's card come up over a site only a ready-made list names; the self-test copies never go to the web.

`sync` starts a sandbox copy of the web server on a free port of this Mac (`KAVANA_MAIL=outbox`, so the sign-in codes are written to a file the test reads), plays a second Mac through the API, and walks signing in with a wrong and a right code, syncing both ways, a recording being downloaded and played (muted), signing out and back in. It refuses any server that isn't on this Mac. It needs a clone of the web repo: `KAVANA_REPO`, or `kavana-web` or `kavana` beside this repo; without one it says SKIP.

## What I could not check without a person

The permission prompts (Notifications, and Automation for each browser), a real full-screen app with the cat over it, the two-second hold with a real mouse, and the widgets on a real desktop: how they look when the desktop isn't in front (macOS greys them), and a click on one going to the app. The code for each is there and its rules are tested; the system's side of them is not.

## Releasing

`mac/release.sh X.Y.Z` builds the app from a commit in a clean checkout, signs it with the shared identity the other apps use (in its own keychain; the script's settings are in `~/.config/chataltul-release.env`, outside the repository), checks it (the signature and who signed it, the version, the id, the hardened runtime, that no home folder's path, address or data is inside, the widgets' signature, sandbox and entry point, and that it starts), zips it (the unzipped copy goes, see the widgets above) and writes the cask and the release notes into `mac/build/release/`. That is all it does by default. `--publish` makes the GitHub release (tagged `mac-vX.Y.Z`, so it isn't taken for one of the Android app's) and puts the cask in the tap, and only works from `origin/main`. The version is X.Y.Z and the build number is 10000·X + 100·Y + Z; nothing is committed for them. The signature is my own, not Apple's, so the cask removes the quarantine flag the way the other casks do.
