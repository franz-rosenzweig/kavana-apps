# Kavana for iPad: the plan

*Written 2026-10-18.*

It started with a cat. I wanted a cat that shows up over the iPad screen and says "Boo! It's time to focus." An iPad doesn't let ANY app draw over other apps, so it can't literally walk across the screen. What Apple does allow is Screen Time blocking: during a focus block, the apps you picked (YouTube, games, whatever pulls you away) are locked, and opening one shows a screen we design ourselves. That's where the cat lives. You tap YouTube in the middle of a block, and instead of YouTube you get the cat, "Boo! It's time to focus.", what you said you're working on, and how many minutes are left, with one button: "Back to work". Basically, the cat shows up exactly when you drift, which is better than a cat that shows up at random.

## How it's built

There were two ways to go. One is rewriting Kavana from scratch in Apple's own language (SwiftUI). That would feel the most native, but every screen would exist twice, and everything new I add to the web app (friends, sync, whatever comes next) would have to be built twice. The other is what we did for Daf, Moshe and Dvarim: a native frame around the Kavana web app, plus the native pieces a web page CAN'T do. I'm going with the frame. This way it's the same app, with the same name, the same teal and cream and the same words, and anything new on the web shows up on the iPad the same day without a new build. Only the native pieces need building.

## What's in the first version

- **The app itself.** Kavana, full screen, with the same ring icon. It opens the live web app and keeps working offline, the same as the web app does.
- **Check-ins from the iPad itself.** The pings ("Still on: …?", the nudges, "How did it go?", "Break's over") are scheduled on the iPad, so they don't depend on my server and there's no "Turn on reminders" dance, just one yes to notifications. They also get real Yes / Drifted buttons right on the notification, which iPad web pings never show.
- **The cat.** You pick the apps once, with Apple's own picker. They lock when a block starts and unlock when it ends, when you pause, or when you tap Stop early, which means it's a speed bump and not a jail.
- **Lock Screen countdown** (Apple calls it a "Live Activity"): the intention and the minutes left, right on the Lock Screen.

Later, only if it actually gets used: a Home Screen widget (today's minutes and the goal bar), Shortcuts, and a Focus filter. No separate iPhone or Mac version, though the iPad app will run on an iPhone anyway.

## What only I can do

1. **Join the Apple Developer Program** ($99 a year, as an individual, which is the quick one), with the Apple ID that's already in Xcode. This isn't optional: Apple doesn't let the free Personal Team use Screen Time AT ALL, so even testing the cat needs it. It's also the only way onto my friend's iPad, through TestFlight (each build lasts 90 days, and Claude can upload new ones).
2. **Ask Apple for the Family Controls entitlement.** It's a form on the developer site, and it only opens once I'm enrolled. The cat works on MY test iPad right away, but sending it to anyone else needs Apple's OK, which can take a few days or a few weeks. The request has to list the app and its small helper pieces, and Claude will give me the exact IDs.
3. **Pick the bundle ID.** Claude suggests `xyz.tiresia.kavana`, to match Daf's `xyz.tiresia.daf`. It can't change after the first TestFlight upload.
4. **Pick the test iPad and plug it into the Mac once.** No iPad or iPhone has ever been connected to the Mac, so Xcode has never seen one. It needs iPadOS 17 or later (for the Lock Screen countdown) and "Developer Mode" turned on, which shows up in Settings → Privacy & Security after the first plug-in. On the free account, a test install stops opening after 7 days and needs a reinstall.
5. **Later, for TestFlight:** make an App Store Connect API key, so Claude can upload builds from the Mac without me.

## The cat

Same rules as the Daf icon: flat, one clean silhouette, no gradients and no layers, calm and natural, and no curly spiral tail. Teal on cream, like the rest of Kavana. The block screen allows one picture, a title, a line of text and the buttons, and no animation, so the cat has to land in one image. My first idea is a cat peeking up over the bottom edge, just ears, eyes and two paws on the edge, like it just popped up to say boo. Claude draws a few concepts and shows them as a contact sheet, big and small, light and dark, and I pick before anything gets refined. The app icon stays the ring, so the iPad app and the web app read as the same thing.

## Timeline

- **Days 1-2, on the free account:** the Xcode project, the frame, check-ins from the iPad and the Lock Screen countdown, running on my test iPad. The web app needs one small hook so it knows it's inside the iPad app, and Claude hands that to the session that's rebuilding the web app, so nothing collides.
- **Same days:** round 1 of the cat drawings.
- **The day I'm enrolled:** the cat on my test iPad, and the entitlement request goes to Apple that day.
- **While Apple decides:** a first TestFlight build WITHOUT the cat goes to my friend, so they get the check-ins and the countdown right away.
- **When Apple says yes:** a build with the cat goes out, and that's v1.

Basically, it's a couple of days of building, and the rest is waiting on Apple.
