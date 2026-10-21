# Kavana for Android: the plan

*Written 2026-10-21. The iPad plan is next to it in PLAN-ipad.md, parked for now.*

When it comes to the cat, Android is the opposite of the iPad. Apple doesn't let any app draw over other apps, but Android DOES, with one permission called "Display over other apps". That means on Android the cat can really show up over whatever you're doing, which was the whole idea in the first place. There's also no Apple-style gatekeeping: no $99 a year, no entitlement to wait on, and no test builds that stop opening after 7 days. I can have it on my own phone the same day, and for anyone else it's a download link.

## How it's built

Same as the iPad plan and Daf's Android app: a native frame around the live Kavana web app, plus the native pieces a web page CAN'T do. This way it's the same Kavana, with the same cats, the same Café and the same words, and anything new on the web shows up in the app the same day. Claude builds it on my Mac over SSH, the way it built Daf's, and my S24+ is already plugged in there, so it gets tested on a real phone from day one.

## What's in the first version

- **The app itself.** Kavana, full screen, with the ring icon. It opens straight into the app, so there's no "Add to Home Screen" and no "Turn on reminders" step, and it keeps working offline like the web app.
- **Check-ins from the phone itself.** The pings are scheduled on the phone, so they don't depend on my server. "Still on: …?" gets Yes / Drifted right on the notification, and "How did it go?" gets Done / Partly / Not really (the web version can only fit two buttons).
- **The cat over the screen, two ways.**
  1. At each check-in, the cat peeks up from the bottom of the screen over whatever app is open: "Boo! Still on: Write the intro?" with Yes / Drifted right there. This one only needs "Display over other apps".
  2. If I open one of my distracting apps during a block (I pick them once), the cat pops up over it: "Boo! It's time to focus." with a big "Back to work" and a small "Let me in". This one also needs "Usage access", so Kavana can tell which app is open. It's a speed bump and not a jail, and just like on the web, the kitten still wanders off if I stay away.
- **The countdown on the lock screen and in the status bar,** as an ongoing notification with what I'm working on and the minutes left. On Samsung it should show in the Now Bar too. It also keeps Kavana alive during a block, which matters because Samsung is known for killing apps in the background.

The cat art already exists: the Boo cat and the painted cats from the web app. So unlike the iPad plan, there's no drawing round, just a little animation so the cat slides up from the edge.

Android makes me switch the two permissions on by hand in Settings, and Kavana walks me through it with a screen for each, in the same style as the rest of the app.

## Maybe: the Galaxy Z Flip 7

Only if we decide it's worth it.
- **Both screens:** the app works on both, and folding or unfolding the phone doesn't restart the block.
- **Half-folded on a table** ("Flex Mode"): the timer and the kitten sit on the top half and the buttons on the bottom, like a little desk clock. That's the Flip's best trick for a focus timer.
- **The cover screen:** Samsung doesn't let ordinary apps put their own widgets there, but the countdown should show in the Now Bar, which the Flip shows on the cover screen too. Samsung has been picky about which apps get in, so we'll only know once it's on a real Flip.
- **Testing:** without a Flip in hand, Claude can test fold, unfold and half-folded on Android's foldable emulator, and the real check is on the Flip itself.

## What only I can do

1. **Say go,** and say whether the Flip part is in.
2. **Say whose phones it's for.** My S24+ is plugged into the Mac. If the Flip 7 is someone else's, they install from a link and tap "Allow" a few times.
3. **OK the app ID** `xyz.tiresia.kavana`, the same one the iPad plan uses.
4. **Later, in 2027:** Google is going to require a registered developer even for apps installed from a link outside the Play Store. When that comes, I register an account. Installs from the Mac over a cable stay exempt either way.

## Timeline

- **Day 1:** the frame, check-ins from the phone and the countdown, on my S24+. The web app needs one small hook so it knows it's inside the app, which is the same hook the iPad app would use. Claude hands it to whichever session is working on the web app, so nothing collides.
- **Day 2:** the cat over the screen, both ways, on my S24+.
- **Day 3, only if the Flip is in:** the fold and half-fold layouts on the emulator, then on the Flip.
- **Then:** a download link on stuffs (kavana/android) for anyone else, and the app tells you when there's a new version.

Basically, Android gets the REAL cat over the screen for free, and it can be on my phone in a day or two. The code goes in a private repo, franz-rosenzweig/kavana-apps, with the Android app in android/ and room for the iPad one in ios/ if that ever happens.
