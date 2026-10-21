# Kavana apps

These are the native apps for Kavana, my focus app. The web version lives at https://stuffs.tiresia.xyz/kavana/ (code in franz-rosenzweig/kavana), and this repo is the phone side of it.

## Android (android/)

![Kavana for Android: the start screen, a block with the kitten in the ring, the cat over the home screen, a check-in on the cat, "How did it go?", the Café, Cats, and dark mode](docs/android.webp)

Kavana for Android is a REAL native app, written in Kotlin with Jetpack Compose, which means it looks and feels like an Android app and not like a web page in a frame. The one thing it borrows from the web is the engine: the web app's own `engine.js` runs inside the phone's JavaScript sandbox (the one that comes with Android System WebView). This way the kittens, the treats and the missions follow exactly the same rules on the phone and on the web, instead of two copies that slowly drift apart.

What it does:
- You say what you're working on, pick 5, 25, 50 or your own number of minutes, and pick "Ask me" or "Nudge me", the same as the web.
- A kitten grows in the ring while you focus and comes home to your Café at the end. Stop early, or leave Kavana in the middle, and it wanders off. Locking the phone is fine.
- **The cat over other apps.** If you leave Kavana in the middle of a block, the cat pops up over whatever app you went to and says "Boo! It's time to focus.", with "Back to work" and "Let me in". Check-ins that come while you're in another app show up the same way, with Yes and Drifted right on the cat. Android calls this "Display over other apps", and the app asks for it on the first run.
- Check-ins are scheduled on the phone itself, so nothing goes through my server. "Still on: …?" has Yes / Drifted right on the notification, and "How did it go?" has Done / Partly / Not really.
- The countdown sits on the lock screen and in the status bar while a block runs (on Samsung, in the Now Bar).
- Later, the Café (your history and stats), Cats, a daily goal, a daily reminder, breaks, park a thought, +5 / +10 at the end.

What it doesn't do yet: accounts and sync, friends and napping together, the shop, missions on screen, the room in the Café, sounds and breathing. Everything stays on the phone.

### Building it

It builds on my Mac over SSH, the same way as Daf and Binah (JDK 17 and `~/android-sdk` there):

```
cd ~/Documents/Claude/Projects/kavana-apps/android
./gradlew assembleDebug                 # Kavana dev, installs beside the real one
./release.sh                            # Kavana-<version>.apk, signed with Dvarim's key
adb install -r Kavana-<version>.apk
```

The engine files come from the web repo with `tools/sync-web.sh` (it records the commit in `assets/js/WEB_COMMIT`), and `node tools/test-bridge.js` runs them the way the phone does, with no Android needed.

The debug build can speed its clock up for testing: `adb shell am start -n xyz.tiresia.kavana.debug/xyz.tiresia.kavana.MainActivity --el minute 1000` makes a minute last one second (force-stop it first).

## iPad (PLAN-ipad.md)

The iPad app is only a plan for now. Apple doesn't let the free developer account use Screen Time, so it waits on the paid Developer Program. The plans for both are in `PLAN-android.md` and `PLAN-ipad.md`.
