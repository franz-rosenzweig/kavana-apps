import Foundation

// The core self-test: the engine in JavaScriptCore, the pings, the away op, the cat names, and the rules of
// the speed bump, the hold, the away prompt and the blocklist, all with no screen.
//   swiftc Chataltul/Core/*.swift Tests/main.swift -o build/core-tests && build/core-tests Chataltul/Resources
// Prints PASS/FAIL lines and "SELFTEST passed" at the end; the exit code is 0 only if everything passed.

var passed = 0
var failed = 0

func check(_ name: String, _ cond: @autoclosure () -> Bool) {
    if cond() { passed += 1; print("PASS \(name)") } else { failed += 1; print("FAIL \(name)") }
}
func equal<T: Equatable>(_ name: String, _ a: T, _ b: T) {
    if a == b { passed += 1; print("PASS \(name)") } else { failed += 1; print("FAIL \(name): got \(a), wanted \(b)") }
}
func section(_ s: String) { print("--- \(s)") }

let args = CommandLine.arguments
guard args.count > 1 else { print("usage: core-tests <Resources folder>"); exit(2) }
let resources = URL(fileURLWithPath: args[1])
let MIN = 60_000

// ---------------------------------------------------------------- the engine and its pings
section("engine")
let host: EngineHost
do { host = try EngineHost(js: resources.appendingPathComponent("js")) } catch { print("FAIL the engine didn't load: \(error)"); exit(1) }

var t = Int(Calendar.current.date(from: DateComponents(year: 2026, month: 10, day: 26, hour: 10))!.timeIntervalSince1970 * 1000)
try host.load(nil)
var r = try host.run("tick", now: t)
equal("a new doc has no running block", r.state.view.active == nil, true)
equal("Miso is the companion", r.state.companion, "Miso".lowercased())
equal("the cats come with their names", r.state.cat("pepper").name, "Milo")

r = try host.run("start", ["intention": "Draft the newsletter", "minutes": 25, "style": "ask"], now: t)
let id1 = r.result?["id"]?.string ?? ""
check("start gives a block id", !id1.isEmpty)
let a1 = r.state.view.active
equal("the kitten is growing", a1?.kitten, "growing")
equal("the pings are a check-in and the end", r.state.pings.map { $0.kind }, ["checkin", "end"])
equal("the check-in comes at 15 minutes", r.state.pings.first?.at, t + 15 * MIN)
check("the check-in names the title", r.state.pings.first?.title.contains("Still on: Draft the newsletter?") == true)

section("pings come due")
var tracker = PingTracker()
equal("the first look has nothing due", tracker.update(r.state.pings, at: t).count, 0)
r = try host.run("tick", now: t + 10 * MIN)
equal("nothing is due before its time", tracker.update(r.state.pings, at: t + 10 * MIN).count, 0)
r = try host.run("tick", now: t + 15 * MIN + 1000)
let due = tracker.update(r.state.pings, at: t + 15 * MIN + 1000)
equal("the check-in is due at 15 minutes", due.map { $0.kind }, ["checkin"])
equal("and the engine has it pending", r.state.view.active?.pending, 1)
equal("it isn't told twice", tracker.update(r.state.pings, at: t + 15 * MIN + 2000).count, 0)

section("check-in answers")
r = try host.run("answer", ["id": id1, "n": 1, "answer": "drifted", "why": "phone"], now: t + 15 * MIN + 5000)
equal("Drifted counts", r.result?["drifts"]?.int, 1)
equal("the check-in is answered", r.state.view.active?.pending, nil)

section("the end")
r = try host.run("tick", now: t + 25 * MIN + 1000)
let endDue = tracker.update(r.state.pings, at: t + 25 * MIN + 1000)
equal("the end ping is due", endDue.map { $0.kind }, ["end"])
equal("the kitten came home", r.state.view.awaiting?.kitten, "home")
equal("with 25 treats", r.state.view.awaiting?.treats, 25)
r = try host.run("outcome", ["id": id1, "outcome": "done"], now: t + 26 * MIN)
equal("Done files it", r.state.history.first?.outcome, "done")
equal("a break is offered", r.state.view.breakOffer?.minutes, 5)
check("a day's stats count it", r.state.stats.today == 25)

section("a sleeping Mac: old pings are dropped, the end is kept")
var sleepy = PingTracker()
let old = [Ping(at: t, kind: "checkin", block: "b", n: 1), Ping(at: t + 5 * MIN, kind: "checkin", block: "b", n: 2), Ping(at: t + 30 * MIN, kind: "end", block: "b")]
_ = sleepy.update(old, at: t - 1000)
let woke = sleepy.update([], at: t + 45 * MIN)
equal("only the end is told after a long sleep", woke.map { $0.kind }, ["end"])

section("cat names in pings (the end ping said the cat's id once)")
for (style, settings) in [("extra time", ["overtime": true]), ("timer", [:])] as [(String, [String: Any])] {
    try host.load(nil)
    var n = Int(t)
    _ = try host.run("tick", now: n)
    _ = try host.run("settings", ["patch": settings.merging(["companion": "pepper"]) { $1 }], now: n)
    // pepper has to be met first: finishing a block meets it
    _ = try host.run("start", ["intention": "First", "minutes": 5], now: n)
    n += 6 * MIN
    r = try host.run("tick", now: n)
    if let aw = r.state.view.awaiting { _ = try host.run("outcome", ["id": aw.id, "outcome": "done"], now: n + 1000) }
    n += 10 * MIN
    r = try host.run("settings", ["patch": settings.merging(["companion": "pepper"]) { $1 }], now: n)
    r = try host.run("start", ["intention": "Read", "minutes": 25], now: n)
    equal("the block's cat is Milo's id (\(style))", r.state.view.active?.cat, "pepper")
    let end = r.state.pings.first { $0.kind == "end" }
    if style == "extra time" {
        check("the end ping says Milo, not pepper", (end?.title.contains("Milo") ?? false) && !(end?.title.contains("pepper") ?? true))
    } else {
        check("the end ping has no cat id in it", !(end?.title.contains("pepper") ?? true) && !(end?.body.contains("pepper") ?? true))
    }
}
// the stopwatch's end ping also names the cat
try host.load(nil)
var sn = t
_ = try host.run("tick", now: sn)
_ = try host.run("start", ["intention": "First", "minutes": 5], now: sn); sn += 6 * MIN
r = try host.run("tick", now: sn)
if let aw = r.state.view.awaiting { _ = try host.run("outcome", ["id": aw.id, "outcome": "done"], now: sn + 1000) }
sn += 10 * MIN
_ = try host.run("settings", ["patch": ["companion": "pepper"]], now: sn)
r = try host.run("start", ["mode": "stopwatch", "tag": "Study"], now: sn)
let swEnd = r.state.pings.first { $0.kind == "end" }
check("the stopwatch end ping says Milo", swEnd?.title.contains("Milo") == true && swEnd?.title.contains("pepper") == false)

section("away: the kitten wanders off, the block goes on")
try host.load(nil)
t += 86_400_000
_ = try host.run("tick", now: t)
r = try host.run("start", ["intention": "Away test", "minutes": 25], now: t)
r = try host.run("away", ["at": t + 5 * MIN, "grace": 15_000], now: t + 5 * MIN + 16_000)
equal("the kitten is gone", r.state.view.active?.kitten, "gone")
equal("because of leaving", r.state.view.active?.goneWhy, "away")
equal("the block keeps going", r.state.view.active != nil, true)
equal("away returns the cat's id", r.result?["cat"]?.string, "miso")
r = try host.run("away", ["at": t + 6 * MIN, "grace": 0], now: t + 6 * MIN)
equal("a second away does nothing more", r.result == nil || r.result == .null, true)
r = try host.run("tick", now: t + 25 * MIN + 1000)
equal("a kitten that left does not come home", r.state.view.awaiting?.kitten, "gone")
equal("and earns no treats", r.state.view.awaiting?.treats, 0)

section("away with no grace is right away (Leave anyway)")
try host.load(nil)
t += 86_400_000
_ = try host.run("tick", now: t)
_ = try host.run("start", ["minutes": 25], now: t)
r = try host.run("away", ["at": t + 3 * MIN, "grace": 0], now: t + 3 * MIN)
equal("held Leave anyway sends the kitten off at once", r.state.view.active?.kitten, "gone")

section("the engine can't take minutes off yet (E.takeOff is the engine change we need)")
r = try host.run("takeOff", ["from": t, "to": t + 3 * MIN], now: t + 4 * MIN)
equal("takeOff says so when the engine lacks it", r.error, "takeOff")
equal("the state says so too", r.state.canTakeOff, false)

section("the doc survives a dump and a load")
let dump = try host.dump()
try host.load(dump)
r = try host.run("tick", now: t + 5 * MIN)
check("the running block is still there", r.state.view.active != nil)
r = try host.run("nope", now: t + 5 * MIN)
equal("an unknown op is an error, not a crash", r.error, "op")

section("park a thought")
r = try host.run("park", ["text": "Reply to that email"], now: t + 6 * MIN)
equal("it waits on the block", r.state.view.active?.parked, ["Reply to that email"])
equal("and in Later", r.state.view.later.first?.text, "Reply to that email")

// ---------------------------------------------------------------- the sound list
section("sounds")
let catalog = try SoundCatalog.load(resources.appendingPathComponent("sounds.json"))
check("there are sounds", catalog.kinds.count > 30)
equal("seven groups", catalog.groups.count, 7)
for g in catalog.groups { check("a free sound in \(g.name)", catalog.kinds(in: g.id).contains { $0.free }) }
let freshState = try { () -> UiState in try host.load(nil); return try host.run("tick", now: t).state }()
for k in catalog.kinds where !k.free { check("\(k.name) has a price in the shop", (freshState.prices["sound-\(k.id)"] ?? 0) > 0) }
for k in catalog.kinds { if let l = k.like { check("\(k.id) is like a sound that exists or is made on the Mac", catalog.kind(l) != nil || ["fire", "birds", "cafe", "night", "wind", "rain", "waves", "purr"].contains(l)) } }
for k in catalog.kinds where k.id != "brown" && k.id != "white" { check("\(k.id) has a recording", k.file != nil) }
equal("three meows", catalog.meows.count, 3)
equal("a third sound takes the oldest one's place", SoundCatalog.toggle("fire", in: [MixEntry(id: "rain"), MixEntry(id: "purr")]).map { $0.id }, ["purr", "fire"])
equal("playing needs owning", catalog.playable([MixEntry(id: "rain"), MixEntry(id: "fire")], shop: []).map { $0.id }, ["rain"])
equal("a bought one plays", catalog.playable([MixEntry(id: "fire")], shop: ["sound-fire"]).map { $0.id }, ["fire"])

// ---------------------------------------------------------------- the blocklist
section("blocklist")
var list = Blocklist(apps: [ListedApp(bundleID: "com.apple.MobileSMS", name: "Messages")], sites: ["youtube.com", "news.ycombinator.com"])
equal("a listed domain matches", list.site(forURL: "https://youtube.com/watch?v=1"), "youtube.com")
equal("www is ignored", list.site(forURL: "https://www.youtube.com/"), "youtube.com")
equal("subdomains are covered", list.site(forURL: "https://m.youtube.com/feed"), "youtube.com")
equal("another site isn't", list.site(forURL: "https://notyoutube.com/"), nil)
equal("a look-alike suffix isn't", list.site(forURL: "https://evilyoutube.com/"), nil)
equal("a path that mentions it isn't", list.site(forURL: "https://example.com/youtube.com"), nil)
equal("a host with a port", list.site(forURL: "http://news.ycombinator.com:80/item?id=1"), "news.ycombinator.com")
equal("typed with no scheme", Blocklist.normalizeDomain("Reddit.com/r/cats"), "reddit.com")
equal("typed with www", Blocklist.normalizeDomain("https://www.x.com/home"), "x.com")
equal("not a domain", Blocklist.normalizeDomain("cats"), nil)
equal("an app by bundle id", list.app("com.apple.MobileSMS")?.name, "Messages")
check("adding a site twice adds it once", list.add(site: "reddit.com") && !list.add(site: "www.reddit.com"))
equal("the starter list has sites", Blocklist.starter.sites.contains("youtube.com"), true)
equal("Firefox can't be read", ReadableBrowser.with(bundleID: "org.mozilla.firefox") == nil, true)
equal("Safari can", ReadableBrowser.with(bundleID: "com.apple.Safari")?.name, "Safari")

// ---------------------------------------------------------------- the speed bump
section("speed bump")
let msg = BumpBlock(id: "b1", paused: false, kitten: "growing", cat: "pepper", catName: "Milo", title: "Draft the newsletter", end: t + 25 * MIN)
let youtube = Target.site(domain: "youtube.com", browser: "Chrome")
let messages = Target.app(ListedApp(bundleID: "com.apple.MobileSMS", name: "Messages"))
var bump = SpeedBump()
var acts = bump.front(target: nil, bundleID: "com.apple.TextEdit", block: msg, now: t)
equal("nothing on the list: nothing happens", acts, [])
equal("where they were is remembered", bump.previous, "com.apple.TextEdit")
acts = bump.front(target: youtube, bundleID: "com.google.Chrome", block: nil, now: t)
equal("no block running: no card", acts, [])
acts = bump.front(target: youtube, bundleID: "com.google.Chrome", block: BumpBlock(id: "b1", paused: true), now: t)
equal("a paused block: no card", acts, [])
acts = bump.front(target: youtube, bundleID: "com.google.Chrome", block: BumpBlock(id: "b1", kitten: "gone"), now: t)
equal("a kitten that already left: no card", acts, [])

acts = bump.front(target: youtube, bundleID: "com.google.Chrome", block: msg, now: t)
guard case .show(let card)? = acts.first else { print("FAIL the card didn't come up"); exit(1) }
equal("a listed site brings the card", acts.count, 1)
equal("the card uses the cat's own name", card.catName, "Milo")
equal("the line", card.line(now: t, graceMs: 15_000), "Come back within 15 seconds, or Milo wanders off.")
equal("and it counts down", card.line(now: t + 4_200, graceMs: 15_000), "Come back within 11 seconds, or Milo wanders off.")
equal("the last second is singular", card.line(now: t + 14_500, graceMs: 15_000), "Come back within 1 second, or Milo wanders off.")
equal("the ring starts full", card.fraction(now: t, graceMs: 15_000), 1.0)
equal("and ends empty", card.fraction(now: t + 15_000, graceMs: 15_000), 0.0)
equal("still the same card on the next look", bump.front(target: youtube, bundleID: "com.google.Chrome", block: msg, now: t + 1000), [])
equal("nothing at 14 seconds", bump.tick(block: msg, now: t + 14_999), [])
acts = bump.front(target: messages, bundleID: "com.apple.MobileSMS", block: msg, now: t + 5000)
equal("another listed target keeps the same card and countdown", acts.count == 1 && { if case .update(let c) = acts[0] { return c.shownAt == t } else { return false } }(), true)
acts = bump.tick(block: msg, now: t + 15_000)
equal("at 15 seconds the kitten wanders off", acts, [.hide, .away(at: t, grace: 15_000), .wandered(catName: "Milo")])
equal("and the card is gone", bump.card == nil, true)
equal("it doesn't come up again for that block", bump.front(target: youtube, bundleID: "com.google.Chrome", block: msg, now: t + 20_000), [])
_ = bump.front(target: nil, bundleID: "com.apple.TextEdit", block: msg, now: t + 21_000)
let again = bump.front(target: youtube, bundleID: "com.google.Chrome", block: msg, now: t + 22_000)
equal("not even after leaving and coming back", again, [])
let next = BumpBlock(id: "b2", kitten: "growing", cat: "miso", catName: "Miso", title: "Next", end: t + 90 * MIN)
check("a new block gets its card again", { if case .show? = bump.front(target: youtube, bundleID: "com.google.Chrome", block: next, now: t + 30_000).first { return true } else { return false } }())

section("speed bump: back to work")
bump = SpeedBump()
_ = bump.front(target: nil, bundleID: "com.apple.TextEdit", block: msg, now: t)
_ = bump.front(target: messages, bundleID: "com.apple.MobileSMS", block: msg, now: t + 1000)
acts = bump.backToWork()
equal("Back to work goes to the app they were in", acts, [.hide, .activate("com.apple.TextEdit")])
equal("it doesn't bump again while that target stays in front", bump.front(target: messages, bundleID: "com.apple.MobileSMS", block: msg, now: t + 2000), [])
_ = bump.front(target: nil, bundleID: "com.apple.TextEdit", block: msg, now: t + 3000)
check("it does after they left it and came back", { if case .show? = bump.front(target: messages, bundleID: "com.apple.MobileSMS", block: msg, now: t + 4000).first { return true } else { return false } }())
acts = bump.front(target: nil, bundleID: "com.apple.TextEdit", block: msg, now: t + 5000)
equal("leaving the listed app takes the card away", acts, [.hide])
acts = bump.backToWork()
equal("Back to work with no card does nothing", acts, [])

section("speed bump: leave anyway")
bump = SpeedBump()
_ = bump.front(target: youtube, bundleID: "com.google.Chrome", block: msg, now: t)
acts = bump.leaveHeld(now: t + 7000)
equal("held Leave anyway: the kitten goes right away", acts, [.hide, .away(at: t + 7000, grace: 0), .wandered(catName: "Milo")])
equal("and it stays quiet for the block", bump.front(target: youtube, bundleID: "com.google.Chrome", block: msg, now: t + 9000), [])
bump = SpeedBump()
_ = bump.front(target: youtube, bundleID: "com.google.Chrome", block: msg, now: t)
acts = bump.tick(block: BumpBlock(id: "b1", paused: true), now: t + 3000)
equal("a pause takes the card away without costing the kitten", acts, [.hide])
bump = SpeedBump()
_ = bump.front(target: youtube, bundleID: "com.google.Chrome", block: msg, now: t)
equal("the block ending takes the card away", bump.tick(block: nil, now: t + 3000), [.hide])

section("the hold")
var hold = HoldTracker()
hold.down(now: t)
equal("half way", hold.progress(now: t + 1000), 0.5)
equal("not yet at 1.9 seconds", hold.check(now: t + 1900), false)
equal("held for 2 seconds", hold.check(now: t + 2000), true)
equal("it counts once", hold.check(now: t + 2100), false)
hold = HoldTracker()
hold.down(now: t); hold.up(now: t + 150)
equal("a plain tap only says Hold to leave", hold.hint, true)
equal("and does not leave", hold.check(now: t + 5000), false)
hold = HoldTracker()
hold.down(now: t); hold.up(now: t + 1500)
equal("letting go early does not leave", hold.check(now: t + 2500), false)
equal("and shows no hint (it was a real hold)", hold.hint, false)
hold.down(now: t + 3000)
equal("it can be started again", hold.check(now: t + 5000), true)

// ---------------------------------------------------------------- away from the Mac
section("away from the Mac")
var away = AwayTracker()
equal("busy: nothing", away.sample(idleMs: 5_000, now: t, running: true), nil)
equal("idle 60 s: not away yet", away.sample(idleMs: 60_000, now: t + 55_000, running: true), nil)
equal("idle 130 s: away, not asked yet", away.sample(idleMs: 130_000, now: t + 125_000, running: true), nil)
check("away", away.isAway)
let back = away.sample(idleMs: 2_000, now: t + 12 * MIN, running: true)
equal("back after 12 minutes: asked", back?.minutes, 12)
equal("it began when the last key was pressed", back?.from, t + 125_000 - 130_000)
equal("not asked twice", away.sample(idleMs: 1_000, now: t + 12 * MIN + 2000, running: true), nil)
away = AwayTracker()
_ = away.sample(idleMs: 125_000, now: t + 125_000, running: true)
equal("away with no block running: not asked", away.sample(idleMs: 1_000, now: t + 6 * MIN, running: false), nil)
away = AwayTracker()
_ = away.sample(idleMs: 100_000, now: t + 100_000, running: true)
equal("under two minutes: never asks", away.sample(idleMs: 1_000, now: t + 110_000, running: true), nil)
away = AwayTracker()
away.offlineNow(now: t, idleMs: 3_000)
equal("asleep: not asked", away.sample(idleMs: 4 * MIN, now: t + 4 * MIN, running: true), nil)
away.onlineNow()
let slept = away.sample(idleMs: 2_000, now: t + 30 * MIN, running: true)
equal("woke after half an hour: asked", slept?.minutes, 30)
away = AwayTracker()
away.offlineNow(now: t, idleMs: 0)
away.onlineNow()
equal("a screen lock of 30 seconds: not asked", away.sample(idleMs: 1_000, now: t + 30_000, running: true), nil)
away = AwayTracker(thresholdMs: 5 * MIN)
_ = away.sample(idleMs: 4 * MIN, now: t + 4 * MIN, running: true)
let tolerated = away.sample(idleMs: 1_000, now: t + 6 * MIN, running: true)
equal("a longer limit in Settings is respected: 4 minutes idle is not away", tolerated, nil)
_ = away.sample(idleMs: 6 * MIN, now: t + 12 * MIN, running: true)
equal("and 6 minutes is", away.sample(idleMs: 1_000, now: t + 20 * MIN, running: true)?.minutes, 14)

// ---------------------------------------------------------------- the settings
section("settings")
var ms = MacSettings()
check("the Mac settings start with the starter blocklist", ms.blocklist == Blocklist.starter)
equal("the shortcuts", [ms.startKey.label, ms.parkKey.label, ms.yesKey.label, ms.driftedKey.label], ["⌃⌥Space", "⌃⌥P", "⌃⌥Y", "⌃⌥D"])
ms.blocklist.add(site: "example.org")
let enc = try JSONEncoder().encode(ms)
let dec = try JSONDecoder().decode(MacSettings.self, from: enc)
equal("they survive a round trip", dec, ms)
equal("old files without new keys still load", try JSONDecoder().decode(MacSettings.self, from: Data("{}".utf8)), MacSettings())
equal("the server defaults to the web app's own", MacSettings().serverURL, "https://stuffs.tiresia.xyz/kavana/")
equal("the cycle: a long break after 4", [Cycle().isLong(afterBlocks: 3), Cycle().isLong(afterBlocks: 4), Cycle().isLong(afterBlocks: 8)], [false, true, true])

// ---------------------------------------------------------------- with the engine change we asked for
if args.count > 2 {
    section("with E.takeOff in the engine (docs/engine-takeoff.patch)")
    let patched = try EngineHost(js: URL(fileURLWithPath: args[2]))
    try patched.load(nil)
    let t0 = Int(Calendar.current.date(from: DateComponents(year: 2026, month: 10, day: 27, hour: 10))!.timeIntervalSince1970 * 1000)
    var pr = try patched.run("tick", now: t0)
    equal("the engine says it can take minutes off", pr.state.canTakeOff, true)
    pr = try patched.run("start", ["intention": "Away", "minutes": 25, "style": "ask"], now: t0)
    let end0 = pr.state.view.active?.end ?? 0
    pr = try patched.run("takeOff", ["from": t0 + 3 * MIN, "to": t0 + 9 * MIN], now: t0 + 10 * MIN)
    equal("takeOff has no error", pr.error, nil)
    equal("the end moved later by 6 minutes", pr.state.view.active?.end, end0 + 6 * MIN)
    equal("the end ping moved with it", pr.state.pings.first { $0.kind == "end" }?.at, end0 + 6 * MIN)
    equal("the kitten is still growing", pr.state.view.active?.kitten, "growing")
    equal("a gap in the future is refused", try patched.run("takeOff", ["from": t0 + 8 * MIN, "to": t0 + 30 * MIN], now: t0 + 11 * MIN).error, "takeOff")
}

print("")
print("\(passed) passed, \(failed) failed")
if failed == 0 { print("SELFTEST passed"); exit(0) } else { print("SELFTEST FAILED"); exit(1) }
