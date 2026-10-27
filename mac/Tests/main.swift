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

// ---------------------------------------------------------------- stage 2: lists, modes, schedules
var utc = Calendar(identifier: .gregorian)
utc.timeZone = TimeZone(identifier: "UTC")!
func day(_ y: Int, _ m: Int, _ d: Int, _ h: Int = 10, _ min: Int = 0) -> Date { utc.date(from: DateComponents(year: y, month: m, day: d, hour: h, minute: min))! }
func ms(_ d: Date) -> Int { Int(d.timeIntervalSince1970 * 1000) }

section("lists and the first version's shape")
let oldShape = try JSONDecoder().decode(Blocklist.self, from: Data("{\"apps\":[{\"bundleID\":\"a.b\",\"name\":\"AB\"}],\"sites\":[\"x.com\"]}".utf8))
equal("the first version's two lists become one", oldShape.lists.count, 1)
equal("with its apps and sites", [oldShape.apps.count, oldShape.sites.count], [1, 1])
equal("called Distractions", oldShape.lists[0].name, "Distractions")
var multi = Blocklist(lists: [
    GuardList(id: "d", name: "Distractions", apps: [ListedApp(bundleID: "com.apple.MobileSMS", name: "Messages")], sites: ["youtube.com"], mode: .speedBump),
    GuardList(id: "n", name: "News", sites: ["news.ycombinator.com", "bbc.co.uk"], mode: .waitItOut, when: GuardWhen(.weekdays), allowanceMin: 15),
    GuardList(id: "m", name: "Messages", apps: [ListedApp(bundleID: "net.whatsapp.WhatsApp", name: "WhatsApp")], mode: .locked, when: GuardWhen(.always)),
])
equal("a site is found in the list that has it", multi.hit(url: "https://www.bbc.co.uk/news", browser: "Safari")?.list.id, "n")
equal("an app is found in its list", multi.hit(bundleID: "net.whatsapp.WhatsApp")?.list.id, "m")
equal("a subdomain is covered", multi.hit(url: "https://m.youtube.com/", browser: "Chrome")?.list.id, "d")
equal("something not listed isn't", multi.hit(url: "https://example.org/", browser: "Chrome") == nil, true)
equal("adding to a chosen list", { _ = multi.add(site: "lobste.rs", to: "n"); return multi.lists[1].sites.last ?? "" }(), "lobste.rs")
let roundTrip = try JSONDecoder().decode(Blocklist.self, from: JSONEncoder().encode(multi))
equal("lists survive a round trip", roundTrip, multi)

section("what a list asks for, and when")
let mon10 = day(2026, 10, 26, 10), mon18 = day(2026, 10, 26, 18), sat10 = day(2026, 10, 31, 10)
let blockEnd = ms(day(2026, 10, 26, 10, 25))
let dist = multi.lists[0], news = multi.lists[1], chat = multi.lists[2]
equal("a block: the list's own mode, until the block ends", GuardPolicy.enforcement(for: dist, now: mon10, blockEnd: blockEnd, usedMs: 0, calendar: utc)?.mode, .speedBump)
equal("a block: and until when", GuardPolicy.enforcement(for: dist, now: mon10, blockEnd: blockEnd, usedMs: 0, calendar: utc)?.until, blockEnd)
equal("no block, 'during blocks' only: nothing", GuardPolicy.enforcement(for: dist, now: mon10, blockEnd: nil, usedMs: 0, calendar: utc) == nil, true)
equal("a weekday inside 9 to 5, with an allowance left: free", GuardPolicy.enforcement(for: news, now: mon10, blockEnd: nil, usedMs: 6 * 60_000, calendar: utc) == nil, true)
let used = GuardPolicy.enforcement(for: news, now: mon10, blockEnd: nil, usedMs: 15 * 60_000, calendar: utc)
equal("allowance used up: it asks for the wait", used?.mode, .waitItOut)
equal("and says why", used?.reason, .allowanceUsed)
equal("and until 5 PM", used?.until, ms(day(2026, 10, 26, 17)))
equal("after 5 PM: nothing", GuardPolicy.enforcement(for: news, now: mon18, blockEnd: nil, usedMs: 99 * 60_000, calendar: utc) == nil, true)
equal("on Saturday: nothing", GuardPolicy.enforcement(for: news, now: sat10, blockEnd: nil, usedMs: 99 * 60_000, calendar: utc) == nil, true)
equal("always: locked outside a block too", GuardPolicy.enforcement(for: chat, now: sat10, blockEnd: nil, usedMs: 0, calendar: utc)?.mode, .locked)
equal("the allowance counts only outside blocks, inside the window", [
    GuardPolicy.countsUsage(for: news, now: mon10, blockEnd: nil, usedMs: 0, calendar: utc),
    GuardPolicy.countsUsage(for: news, now: mon10, blockEnd: blockEnd, usedMs: 0, calendar: utc),
    GuardPolicy.countsUsage(for: news, now: mon18, blockEnd: nil, usedMs: 0, calendar: utc),
    GuardPolicy.countsUsage(for: news, now: mon10, blockEnd: nil, usedMs: 15 * 60_000, calendar: utc),
], [true, false, false, false])
equal("a locked list locks the block", GuardPolicy.locksTheBlock(multi), true)
equal("no locked list, no lock", GuardPolicy.locksTheBlock(Blocklist.starter), false)

section("a day's usage")
var usage = DayUsage(day: DayUsage.key(day(2026, 10, 26), calendar: utc))
usage.add("n", ms: 6 * 60_000, at: day(2026, 10, 26, 11))
equal("it adds up", usage.used("n", at: day(2026, 10, 26, 12)), 6 * 60_000)
equal("and starts again on a new day", usage.used("n", at: day(2026, 10, 27, 9)), 0)

section("wait it out")
let mHit = GuardHit(list: chat, target: .app(ListedApp(bundleID: "net.whatsapp.WhatsApp", name: "WhatsApp")))
let waitEnf = Enforcement(mode: .waitItOut, reason: .block, listName: "News", until: blockEnd)
var other = OtherGuard()
var oa = other.front(hit: nil, enforcement: nil, bundleID: "com.apple.TextEdit", cat: "miso", catName: "Miso", now: t)
equal("nothing listed: nothing", oa, [])
oa = other.front(hit: mHit, enforcement: waitEnf, bundleID: "net.whatsapp.WhatsApp", cat: "miso", catName: "Miso", now: t)
guard case .show(let wcard)? = oa.first else { print("FAIL wait card"); exit(1) }
equal("a listed app brings the wait", wcard.kind, .waitItOut)
equal("30 seconds long", wcard.waitMs, 30_000)
equal("it counts down", wcard.line(now: t + 7_000), "WhatsApp opens in 23 seconds. Most urges pass by then.")
equal("not over at 29 seconds", other.tick(now: t + 29_000), [])
equal("over at 30: the card goes", other.tick(now: t + 30_000), [.hide])
equal("and the visit may stay", other.front(hit: mHit, enforcement: waitEnf, bundleID: "net.whatsapp.WhatsApp", cat: "miso", catName: "Miso", now: t + 31_000), [])
_ = other.front(hit: nil, enforcement: nil, bundleID: "com.apple.TextEdit", cat: "miso", catName: "Miso", now: t + 40_000)
equal("leaving for 2 seconds and coming back is the same visit", other.front(hit: mHit, enforcement: waitEnf, bundleID: "net.whatsapp.WhatsApp", cat: "miso", catName: "Miso", now: t + 42_000), [])
_ = other.front(hit: nil, enforcement: nil, bundleID: "com.apple.TextEdit", cat: "miso", catName: "Miso", now: t + 50_000)
var nextVisit = other.front(hit: mHit, enforcement: waitEnf, bundleID: "net.whatsapp.WhatsApp", cat: "miso", catName: "Miso", now: t + 60_000)
equal("leaving for 10 seconds, it waits again, every time", nextVisit.count == 1 && { if case .show = nextVisit[0] { return true } else { return false } }(), true)
oa = other.front(hit: nil, enforcement: nil, bundleID: "com.apple.TextEdit", cat: "miso", catName: "Miso", now: t + 65_000)
equal("leaving during the wait takes the card away", oa, [.hide])
equal("Back to work with no card does nothing", other.backToWork(), [])
other = OtherGuard()
_ = other.front(hit: nil, enforcement: nil, bundleID: "com.apple.TextEdit", cat: "miso", catName: "Miso", now: t)
_ = other.front(hit: mHit, enforcement: waitEnf, bundleID: "net.whatsapp.WhatsApp", cat: "miso", catName: "Miso", now: t + 1000)
equal("back to work goes to where they were", other.backToWork(), [.hide, .activate("com.apple.TextEdit")])
equal("and the same app in front doesn't bring it straight back", other.front(hit: mHit, enforcement: waitEnf, bundleID: "net.whatsapp.WhatsApp", cat: "miso", catName: "Miso", now: t + 2000), [])

section("locked, and used up")
let lockEnf = Enforcement(mode: .locked, reason: .block, listName: "Messages", until: blockEnd)
other = OtherGuard()
oa = other.front(hit: mHit, enforcement: lockEnf, bundleID: "net.whatsapp.WhatsApp", cat: "miso", catName: "Miso", now: t)
guard case .show(let lcard)? = oa.first else { print("FAIL lock card"); exit(1) }
equal("a locked app brings the lock", lcard.kind, .locked)
equal("it says until when", lcard.title, "Locked until \(clockTime(blockEnd)).")
equal("and it never opens by itself", other.tick(now: t + 10 * MIN), [])
let usedEnf = Enforcement(mode: .waitItOut, reason: .allowanceUsed, listName: "News", until: ms(day(2026, 10, 26, 17)))
other = OtherGuard()
oa = other.front(hit: GuardHit(list: news, target: .site(domain: "bbc.co.uk", browser: "Safari")), enforcement: usedEnf, bundleID: "com.apple.Safari", cat: "miso", catName: "Miso", now: t)
if case .show(let ucard)? = oa.first { equal("used up says so", ucard.kind, .usedUp) } else { check("used up card", false) }
equal("a speed bump isn't this guard's", other.front(hit: GuardHit(list: dist, target: .site(domain: "youtube.com", browser: "Chrome")), enforcement: Enforcement(mode: .speedBump, reason: .block, listName: "Distractions", until: blockEnd), bundleID: "com.google.Chrome", cat: "miso", catName: "Miso", now: t + 1000), [.hide])

// ---------------------------------------------------------------- breaks
section("breaks")
equal("Pomodoro is 25 and 5", [CyclePreset.pomodoro.minutes!.focus, CyclePreset.pomodoro.minutes!.rest], [25, 5])
equal("52 / 17", [CyclePreset.fiftyTwo.minutes!.focus, CyclePreset.fiftyTwo.minutes!.rest], [52, 17])
equal("Deep work is 90 and 20", [CyclePreset.deep.minutes!.focus, CyclePreset.deep.minutes!.rest], [90, 20])
equal("Flowtime is a stopwatch", CyclePreset.flow.stopwatch && CyclePreset.flow.minutes == nil, true)
equal("a flow break is a fifth of the stretch, 5 to 25", [CyclePreset.flowBreak(focusedMinutes: 20), CyclePreset.flowBreak(focusedMinutes: 70), CyclePreset.flowBreak(focusedMinutes: 180)], [5, 14, 25])
let bs = BreakSettings()
equal("the ideas are the mockup's", bs.ideas.prefix(3).map { $0 }, ["Stand up and stretch", "Get some water", "Look out of a window"])
equal("an idea and others to turn to", [bs.ideaFor(0).main, bs.ideaFor(0).others.first ?? ""], ["Stand up and stretch", "Get some water"])
equal("ideas wrap round", bs.ideaFor(6).main, "Stand up and stretch")
equal("no ideas still says something", try JSONDecoder().decode(BreakSettings.self, from: Data("{\"ideas\":[]}".utf8)).ideaFor(0).main, "Take a breath.")

section("eye breaks")
var eye = EyeBreakTimer(everyMin: 20, seconds: 20)
equal("none before a block", eye.check(now: t, running: true, busy: false), false)
eye.blockStarted(at: t)
equal("none in the first 19 minutes", eye.check(now: t + 19 * MIN, running: true, busy: false), false)
equal("it waits while a call is on", eye.check(now: t + 20 * MIN, running: true, busy: true), false)
equal("and comes when the call is over", eye.check(now: t + 22 * MIN, running: true, busy: false), true)
equal("once", eye.check(now: t + 22 * MIN + 1000, running: true, busy: false), false)
eye.finish(at: t + 22 * MIN + 20_000)
equal("the next is a full 20 minutes later", eye.nextAt, t + 22 * MIN + 20_000 + 20 * MIN)
equal("a pause is no time on the block", eye.check(now: t + 50 * MIN, running: false, busy: false), false)
equal("away for 30 seconds counts as one", eye.awayFor(ms: 30_000, now: t + 60 * MIN), true)
equal("away for 5 seconds doesn't", eye.awayFor(ms: 5_000, now: t + 61 * MIN), false)
equal("so the next one moved", eye.nextAt, t + 80 * MIN)
eye.blockEnded()
equal("no block, no eye breaks", eye.nextAt == nil && !eye.check(now: t + 200 * MIN, running: true, busy: false), true)

// ---------------------------------------------------------------- insights
section("insights")
func hist(_ date: Date, _ min: Int, tag: String? = nil, kitten: String = "home", outcome: String? = "done", drifts: Int = 0) -> Hist {
    var o = ["id": "\(ms(date))", "start": ms(date), "actual": min, "minutes": min, "kitten": kitten, "drifts": drifts] as [String: Any]
    if let tag { o["tag"] = tag }
    if let outcome { o["outcome"] = outcome }
    return try! JSONDecoder().decode(Hist.self, from: JSONSerialization.data(withJSONObject: o))
}
let hs = [hist(day(2026, 10, 26, 9, 30), 50, tag: "Writing"), hist(day(2026, 10, 26, 10, 30), 40, tag: "Reading"), hist(day(2026, 10, 26, 11, 40), 9, tag: "Study", kitten: "gone", outcome: nil, drifts: 1),
          hist(day(2026, 10, 25, 14), 30, tag: "Writing"), hist(day(2026, 10, 24, 14), 30), hist(day(2026, 9, 30, 14), 60, tag: "Work")]
let today = Insights.period(.day, containing: day(2026, 10, 26, 12), calendar: utc)
let todayBlocks = Insights.blocks(hs, in: today)
equal("today's blocks", todayBlocks.count, 3)
equal("today's minutes", Insights.minutes(todayBlocks), 99)
equal("cats home, cats gone", [Insights.home(todayBlocks), Insights.wandered(todayBlocks)], [2, 1])
equal("outcomes", [Insights.outcomes(todayBlocks).done, Insights.outcomes(todayBlocks).partly], [2, 0])
equal("drifts", Insights.drifts(todayBlocks), 1)
let week = Insights.period(.week, containing: day(2026, 10, 28), calendar: utc)
equal("the week starts on Monday", utc.component(.weekday, from: week.start), 2)
equal("a week of seven days", (week.to - week.from) / 86_400_000, 7)
equal("this week's minutes", Insights.minutes(Insights.blocks(hs, in: week)), 99)
equal("by day, Monday first", Insights.slots(Insights.blocks(hs, in: week), in: week, calendar: utc).prefix(2).map { $0 }, [99, 0])
let oct = Insights.period(.month, containing: day(2026, 10, 15), calendar: utc)
equal("October has 31 slots", Insights.slots([], in: oct, calendar: utc).count, 31)
equal("October's minutes", Insights.minutes(Insights.blocks(hs, in: oct)), 159)
let sept = Insights.before(oct, calendar: utc)
equal("the month before is September", utc.component(.month, from: sept.start), 9)
equal("compared with it", Insights.compare(159, Insights.minutes(Insights.blocks(hs, in: sept))), "+1 h 39 min")
equal("by hour, today", Insights.slots(todayBlocks, in: today, calendar: utc)[9] + Insights.slots(todayBlocks, in: today, calendar: utc)[10], 50 - 0 + 30 + 0 + 0 == 80 ? 80 : 80)
equal("by tag, biggest first", Insights.byTag(todayBlocks).map { $0.tag }, ["Writing", "Reading", "Study"])
equal("the busiest hour is 10", Insights.byHour(todayBlocks, calendar: utc).enumerated().max { $0.element < $1.element }?.offset, 10)
equal("days in a row", Insights.streak(hs, today: day(2026, 10, 26, 12), calendar: utc), 3)
equal("yesterday still counts until today ends", Insights.streak(Array(hs.dropFirst(3)), today: day(2026, 10, 26, 8), calendar: utc), 2)
equal("a year has twelve slots", Insights.slots([], in: Insights.period(.year, containing: day(2026, 5, 5), calendar: utc), calendar: utc).count, 12)

section("settings: breaks and the cycle")
let ms2 = MacSettings()
equal("full-screen breaks are on, strict is off", [ms2.breaks.fullScreen, ms2.breaks.strict], [true, false])
equal("eye breaks every 20 minutes for 20 seconds", [ms2.breaks.eyeEveryMin, ms2.breaks.eyeSeconds], [20, 20])
equal("breaks are offered, not started", ms2.breaks.start, .offer)
let bsRound = try JSONDecoder().decode(MacSettings.self, from: JSONEncoder().encode(ms2))
equal("break settings survive a round trip", bsRound, ms2)

// ---------------------------------------------------------------- stage 3: where it went, streaks, the week in words, export
section("where it went")
var log = TimingLog()
for _ in 0..<41 { log.add(block: "b1", start: t, bundleID: "com.apple.TextEdit", name: "Writer", ms: 60_000) }
for _ in 0..<4 { log.add(block: "b1", start: t, bundleID: "com.apple.Safari", name: "Safari", ms: 60_000, site: ("youtube.com", "Safari")) }
for _ in 0..<2 { log.add(block: "b1", start: t, bundleID: "com.apple.Safari", name: "Safari", ms: 60_000, site: ("library.example", "Safari")) }
for _ in 0..<3 { log.add(block: "b1", start: t, bundleID: "com.apple.mail", name: "Mail", ms: 60_000) }
equal("a block's time adds up by app", log.blocks["b1"]?.totalMs, 50 * 60_000)
equal("and by site", log.blocks["b1"]?.sites["youtube.com"]?.ms, 4 * 60_000)
let wrows = Went.rows(log.timings(for: ["b1", "nope"]), blocklist: Blocklist(apps: [ListedApp(bundleID: "com.apple.mail", name: "Mail")], sites: ["youtube.com"]))
equal("the biggest is first", wrows.first?.name, "Writer")
equal("the shares add up to the whole", Int((wrows.reduce(0) { $0 + $1.share } * 100).rounded()), 100)
equal("a browser's line names its sites", wrows.first { $0.name == "Safari" }?.label, "Safari: youtube.com, library.example")
equal("what is on a list is marked", wrows.filter { $0.distraction }.map { $0.name }.sorted(), ["Mail", "Safari"])
equal("a block with no record has no rows", Went.rows([]).count, 0)
var many = TimingLog()
for (i, ms) in [50, 40, 30, 20, 10, 5, 3, 2].enumerated() { many.add(block: "m", start: t, bundleID: "a\(i)", name: "App \(i)", ms: ms * 60_000) }
let folded = Went.rows(many.timings(for: ["m"]), limit: 4)
equal("more than the limit folds the rest into one line", folded.count, 4)
equal("called Other", folded.last?.name, "Other")
log.prune(before: t + 1)
equal("old blocks are pruned", log.isEmpty, true)
let tStore = TimingStore(folder: FileManager.default.temporaryDirectory.appendingPathComponent("ct-\(UUID().uuidString)"))
try? FileManager.default.createDirectory(at: tStore.file.deletingLastPathComponent(), withIntermediateDirectories: true)
var tl = TimingLog(); tl.add(block: "z", start: t, bundleID: "x", name: "X", ms: 5000)
tStore.write(tl)
equal("it survives the file", tStore.read(), tl)
tStore.erase()
equal("and can be erased", tStore.read().isEmpty, true)

section("streaks with a pause")
func dayHist(_ y: Int, _ m: Int, _ d: Int) -> Hist { hist(day(y, m, d, 10), 30) }
// Mon 26 Oct 2026 is a Monday
let fri = day(2026, 10, 30, 12), sun = day(2026, 11, 1, 12)
var st = Streaks.withPause([dayHist(2026, 10, 26), dayHist(2026, 10, 27), dayHist(2026, 10, 28), dayHist(2026, 10, 29), dayHist(2026, 10, 30)], today: fri, calendar: utc)
equal("Monday to Friday is five in a row", st.days, 5)
equal("with the week's pause still there", st.pausesLeft, 1)
st = Streaks.withPause([dayHist(2026, 10, 26), dayHist(2026, 10, 27), dayHist(2026, 10, 28), dayHist(2026, 10, 29), dayHist(2026, 10, 30), dayHist(2026, 11, 1)], today: sun, calendar: utc)
equal("a missed Saturday is carried over", st.days, 6)
equal("by the week's pause", [st.pausedDays.count, st.pausesLeft], [1, 0])
equal("which was Saturday", utc.component(.weekday, from: st.pausedDays[0]), 7)
st = Streaks.withPause([dayHist(2026, 10, 26), dayHist(2026, 10, 28), dayHist(2026, 10, 30)], today: fri, calendar: utc)
equal("only one pause a week: the second miss ends it", st.days, 2)
st = Streaks.withPause([dayHist(2026, 10, 26), dayHist(2026, 10, 27), dayHist(2026, 10, 30)], today: fri, calendar: utc)
equal("two missed days in a row end it", st.days, 1)
st = Streaks.withPause([dayHist(2026, 10, 28), dayHist(2026, 10, 29)], today: day(2026, 10, 30, 8), calendar: utc)
equal("today isn't over: yesterday still counts, no pause used", [st.days, st.pausesLeft], [2, 1])
st = Streaks.withPause([dayHist(2026, 10, 22), dayHist(2026, 10, 23), dayHist(2026, 10, 25), dayHist(2026, 10, 26)], today: day(2026, 10, 26, 12), calendar: utc)
equal("a pause in last week is that week's, and this week's is still there", [st.days, st.pausedDays.count, st.pausesLeft], [4, 1, 1])
equal("no blocks, no streak", Streaks.withPause([], today: fri, calendar: utc).days, 0)

section("the week in words")
let wkPeriod = Insights.period(.week, containing: day(2026, 10, 28), calendar: utc)
func wh(_ d: Date, _ min: Int, tag: String?, drifts: Int = 0, whys: [String] = [], outcome: String? = "done") -> Hist {
    var o = ["id": "\(ms(d))", "start": ms(d), "actual": min, "minutes": min, "kitten": "home", "drifts": drifts, "whys": whys] as [String: Any]
    if let tag { o["tag"] = tag }
    if let outcome { o["outcome"] = outcome }
    return try! JSONDecoder().decode(Hist.self, from: JSONSerialization.data(withJSONObject: o))
}
let weekBlocks = [wh(day(2026, 10, 26, 10), 120, tag: "Writing"), wh(day(2026, 10, 27, 10), 150, tag: "Writing"), wh(day(2026, 10, 27, 16), 40, tag: "Work", drifts: 1, whys: ["web"]),
                  wh(day(2026, 10, 28, 16), 60, tag: "Reading", drifts: 2, whys: ["web", "phone"]), wh(day(2026, 10, 29, 15), 60, tag: "Writing", drifts: 1, whys: ["web"], outcome: "partly"), wh(day(2026, 10, 30, 11), 90, tag: "Writing")]
let wf = Review.facts(blocks: weekBlocks, period: wkPeriod, goalMin: 120, streak: StreakInfo(days: 5, pausedDays: [], pausesLeft: 1), calendar: utc)
equal("the week's minutes", wf.minutes, 520)
equal("best on Tuesday", wf.bestDay?.name, "Tuesday")
equal("Writing took most", wf.topTag, "Writing")
equal("the web pulled most", wf.reasons.first?.id, "web")
equal("and 3 times", wf.reasons.first?.times, 3)
equal("the drifts were late in the day", wf.lateDrifts, true)
equal("days that met the goal", wf.daysMet, 2)
let said = Review.summary(wf)
check("the summary starts with a verdict and the numbers", said.hasPrefix("An uneven week: 8 h 40 min over 6 blocks, best on Tuesday."))
check("and names the tag", said.contains("Writing took most of it."))
check("and what pulled away, and when", said.contains("usually the web, mostly in blocks that began after 3 PM"))
check("and suggests the speed bump", said.contains("might want the speed bump on"))
equal("a week with no blocks says so", Review.summary(WeekFacts()).hasPrefix("No blocks yet"), true)
equal("a phone drift is out of reach", Review.summary(Review.facts(blocks: [wh(day(2026, 10, 26, 10), 60, tag: nil, drifts: 1, whys: ["phone"])], period: wkPeriod, goalMin: 0, streak: wf.streak, calendar: utc)).contains("out of Chataltul's reach"), true)
equal("best day question", Review.answer("which day was my best day?", wf), "Your best day was Tuesday, with 3 h 10 min.")
equal("total question", Review.answer("How much did I do?", wf), "8 h 40 min over 6 blocks.")
check("distraction question", Review.answer("what distracted me", wf).hasPrefix("What pulled you away: the web 3 times"))
check("tag question", Review.answer("what did I work on most", wf).contains("Writing"))
check("streak question", Review.answer("what's my streak", wf).contains("5 days in a row"))
check("a question it can't answer says what it can", Review.answer("why is the sky blue", wf).contains("I can answer"))
check("facts for a model", Review.factsText(wf, title: "This week").contains("Best day: Tuesday"))
equal("reasons have words", [Reason.label("web"), Reason.label("hungry")], ["The web", "Hunger"])

section("export")
equal("a plain field is left alone", Export.field("hello"), "hello")
equal("a comma gets quotes", Export.field("a, b"), "\"a, b\"")
equal("a quote is doubled", Export.field("say \"hi\""), "\"say \"\"hi\"\"\"")
let csvText = Export.blocksCSV(weekBlocks, catName: { $0 == "miso" ? "Miso" : $0 }, calendar: utc)
equal("a header and a row for each block", csvText.split(separator: "\n").count, 7)
check("the header names the columns", csvText.hasPrefix("start,minutes,title,tag,how it went,drifts,what pulled away,cat,the kitten,note\n"))
check("a row has the time, the length and the tag", csvText.contains("2026-10-27T10:00:00Z,150,Writing,Writing,done,0,,Miso,came home,"))
check("the reasons are in words", csvText.contains("The web; Phone"))
equal("where it went as CSV", Export.wentCSV([WentRow(name: "Writer", ms: 41 * 60_000, share: 0.82, sites: [], distraction: false)]), "app,minutes,share\nWriter,41,82%\n")
let md = Export.reviewMarkdown(title: "This week", range: "26 to 1 November", summary: said, f: wf, went: [WentRow(name: "Writer", ms: 41 * 60_000, share: 0.82, sites: [], distraction: false)])
check("the review as Markdown", md.hasPrefix("# This week\n") && md.contains("## What pulled you away") && md.contains("Kept only on this Mac"))

section("settings: timing")
equal("remembering is on, and the shortcuts are empty", [MacSettings().recordTiming, MacSettings().shortcutStart.isEmpty, MacSettings().shortcutEnd.isEmpty], [true, true, true])

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
