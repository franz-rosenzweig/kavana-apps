# Chataltul apps

These are the native apps for Chataltul (חתלתול, Hebrew for kitten), my focus app. It used to be called Kavana, which is why the repos and the web address still say kavana. The web version lives at https://stuffs.tiresia.xyz/kavana/ (code in franz-rosenzweig/kavana), and this repo is the phone side of it.

## Android (android/)

![Chataltul for Android: the start screen, This Block, the menu, the sky during a block, the cat coming home to the café, the Café in 3D, and dark mode with a check-in](docs/android.webp)

**Install it:** download the APK from the newest release on the [Releases page](https://github.com/franz-rosenzweig/kavana-apps/releases/latest) on your phone and open it (Android 12 or newer). The first time, Android asks you to allow installs from that source, which is normal for apps that don't come from the Play Store. If you'd rather get updates by themselves, add `https://github.com/franz-rosenzweig/kavana-apps` to Obtainium.

Chataltul for Android is a REAL native app, written in Kotlin with Jetpack Compose, which means it looks and feels like an Android app and not like a web page in a frame. The one thing it borrows from the web is the engine: the web app's own `engine.js` runs inside the phone's JavaScript sandbox (the one that comes with Android System WebView). This way the kittens, the treats and the missions follow exactly the same rules on the phone and on the web, instead of two copies that slowly drift apart.

What it does:
- **It looks like the web now, and like Forest.** The Focus screen is one screen: the café wall in the background, a round window with the cat who's coming on its cushion, and a ring around the window that you drag for 5 to 120 minutes. Tap the big clock for This Block (tag, timer or stopwatch, how long, check-ins, a first step), then Start. There's no tab bar anymore: the menu at the top left has everything else.
- **During a block** the screen dives into the window and becomes a sky that goes from afternoon to sunset to stars as the block goes on (in dark mode it's a calm night sky the whole way, with a few stars from the start). **At the end** today's café comes up and your cat drops into it with its treats, and **Again** starts the same block in one tap. If your kitten wanders off while you watch, it tiptoes out of the ring.
- Pick how long and press Start, that's it. A title and a tag are there if you want them, like Forest's ("Add a title" under the tag), but you don't need either: a block without a title goes by its tag, or just "Focus". How long can be 5, 25, 50 or your own number of minutes, or a stopwatch that counts up (up to 3 hours; after 10 minutes your kitten can come home). "Ask me" or "Nudge me", the same as the web. Changed your mind? Cancel in the first 10 seconds and nothing counts.
- **Living cats.** The painted cats breathe, blink, flick an ear, swish a tail and tilt their head. Tap one and it hops; hold it and it purrs (the phone purrs too); it follows your finger. No new art: every picture has a rig (`assets/rig.json`, the same one the web uses) and is bent live with `Canvas.drawBitmapMesh`, with the motion numbers from `motion/warp.js`.
- A kitten grows in the ring while you focus, popping from napping to kitten to grown, and comes home to your Café at the end. Stop early, or leave Chataltul in the middle, and it wanders off. Locking the phone is fine. A pause lasts 5 minutes at most (or turn pausing off), and "Count extra time" keeps a block going past its end until you tap Done.
- **The cat over other apps.** If you leave Chataltul in the middle of a block, the cat pops up over whatever app you went to and says "Boo! You're focusing.", counting down the 15 seconds you have to come back before your kitten wanders off. "Back to work" takes you back. "Leave anyway" has to be held for two seconds, and then your kitten wanders off right away (the block keeps going without it). On purpose there's no easy way out: no close button and no pause on the cat. Check-ins that come while you're in another app show up the same way, with Yes and Drifted right on the cat. Android calls this "Display over other apps", and the app asks for it on the first run.
- **Cats, like Forest's collection.** Every cat in colour: the ones you've met are awake, the ones you haven't are asleep with how close you are, the ones in the shop show their price. Tap one to see it big and its three stages.
- **Missions and rewards.** Three missions a day and one a week, a starter challenge, a monthly focus challenge and 27 achievements, each with treats to collect.
- **The shop.** Things for your café, cats to adopt, café themes, sounds, shelter cats you sponsor (each stands for a real shelter cat), and Halloween and winter things in their week.
- **The Café, in 3D.** A floating room you can spin with your finger (the same one as on the web, drawn on the phone), with what you bought and the cats that came home walking, washing and napping in it; your focus by day, week, month or year compared with the one before, when in the day you focus, who came home, and every block, which you can edit (what it was, its tag, a note). "Someday" shows what the café becomes.
- A mindful minute: box breathing with your cat breathing along.
- **Real sounds to focus to.** 39 of them in seven groups (cats purring, rain and water, fire, outside, places, music and noise), the same ones the web plays, with one free in each group and the others in the shop. All but brown and white noise are real recordings (CC0, from Freesound; who recorded each one is in the web repo's [docs/sounds.md](https://github.com/franz-rosenzweig/kavana/blob/master/docs/sounds.md)). Like on the web, a recording downloads the first time you pick it (a megabyte or two) and stays on the phone, so after that it plays offline too. While it downloads, a version made on the phone fills in and the recording fades in when it's ready; the music just starts a moment later. Two can play at once. And a kitten that comes home says hello with a meow (not when the phone is on silent).
- Check-ins are scheduled on the phone itself, so nothing goes through my server. "Still on: …?" (or "Still focusing?" when the block has no title) has Yes / Drifted right on the notification, and "How did it go?" has Done / Partly / Not really, and a note.
- The countdown (or the stopwatch) sits on the lock screen and in the status bar while a block runs (on Samsung, in the Now Bar).
- Later, a daily goal, a daily reminder, breaks, park a thought, +5 / +10 at the end, your own nudge lines, tags, news.
- **Widgets, tiles and shortcuts.** Press and hold on your home screen, tap Widgets and look for Chataltul (or use Settings → Widgets → Add). **The Window** is your cat in a round window on the café wall, in three sizes (a big one, a square one and a slim strip) that show what's going on: ready to start, with Start, 15 and 50; while you focus, with the kitten growing in the ring, a live countdown, and Pause, Park a thought and Stop; a check-in, with Yes and Drifted right on it; the kitten come home, with Done, Partly and Not really; paused, with its own countdown; and wandered off. **Quick start** is a slim strip with 15, 25, 50 and ∞. **Today** is your goal as a ring, your streak with its paw prints, your missions, and a Collect button when a reward is waiting. **Later** is your list: tick things off, start a block with one, or park a thought. **Sound** shows what's playing, with its volume, and plays and pauses it. "Park a thought" opens a tiny dialog over whatever you're doing, not the whole app. There are three Quick Settings tiles (Chataltul, Park a thought and Focus sound), and when you press and hold the app's icon you get Start 25, Start 50, Park a thought and Open the café. The countdown on the lock screen has the kitten walking along its bar, with a dot for each check-in, and it never says what you called the block (the shade does, once you're unlocked). One switch in Settings, "Hide block titles", takes the titles off the widgets too.
- **Sign in, if you want, and it syncs with the web.** Settings → Sign In: type your email and Chataltul emails you a 6-digit code, and that's it, no password to remember (if you're new, the code makes your account). A name and a password work too. Your history on the phone comes along into the account, and from then on the phone and the web (and your other devices) have the same blocks, Later, cats and settings: a change goes up a moment after you make it, and what you did elsewhere comes in when you open Chataltul. The account page has your email, a password if you want one, signing out (the account's history leaves the phone, it's safe on the server) and deleting the account (your history stays on the phone).

What it doesn't do yet: the parts of the account that are about other people (friends, napping together, rooms with a code, rankings, the shelter counters). Without an account your history stays on the phone and nothing about you is sent anywhere; the one thing the app fetches is a sound's recording, the first time you pick it.

### Building it

It builds on my Mac over SSH, the same way as Daf and Binah (JDK 17 and `~/android-sdk` there):

```
cd ~/Documents/Claude/Projects/kavana-apps/android
./gradlew assembleDebug                 # Chataltul dev, installs beside the real one
./release.sh                            # Chataltul-<version>.apk, signed with Dvarim's key
adb install -r Chataltul-<version>.apk
```

The engine files and pictures come from the web repo with `tools/sync-web.sh` (it records the commit in `assets/js/WEB_COMMIT`; `KAVANA_REPO=<clone>` syncs from a clone on the Mac), and `node tools/test-bridge.js` runs them the way the phone does, with no Android needed.

## Living cats (motion/)

The motion study and its tools: `warp.js` (the web's living cats, a copy of what's in the web app), `rig.json` (every picture's eyes, head, neck, ears, tail and ground line), `tools/eyes.js` and `tools/rig.js` (find them in new pictures, with hand fixes for what the finder misses) and `tools/folds.js` (checks no motion folds a picture over).

The account check, `tools/check-account.py`, signs in and syncs on the emulator against a sandbox copy of the server on this computer (never the real one: it makes and deletes accounts, so it refuses any other address). It reads the emailed codes from the sandbox's outbox, plays a second device through the API, and walks signing in by email, syncing both ways, a password, signing out and back in, a password changed elsewhere, and deleting the account. How to start the sandbox is at the top of the script.

The widgets' check, `tools/check-widgets.py`, hosts every widget in every state in the debug build's Widget lab (a screen of the app's own, at real sizes, so it never goes near a home screen), runs a whole block from a widget, parks a thought from one, and looks at the countdown notification, the Quick Settings tiles and the shortcuts. `tools/widget-previews.py` makes the pictures the launcher's widget list shows, from the real widgets.

The device check, `tools/check.py`, drives a phone plugged into my Mac over SSH (or an emulator, with `KAVANA_ADB`). It needs to know which Mac: `KAVANA_MAC=user@host`, plus `KAVANA_MAC_KEY` if ssh needs a key, or the same two lines in `~/.config/kavana-apps/mac.env`, which stays out of the repo. It stops and says so if neither is there.

The debug build can speed its clock up for testing: `adb shell am start -n xyz.tiresia.kavana.debug/xyz.tiresia.kavana.MainActivity --el minute 1000` makes a minute last one second (force-stop it first).

## iPad (PLAN-ipad.md)

The iPad app is only a plan for now. Apple doesn't let the free developer account use Screen Time, so it waits on the paid Developer Program. The plans for both are in `PLAN-android.md` and `PLAN-ipad.md`.
