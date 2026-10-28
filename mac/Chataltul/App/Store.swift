import AppKit
import Combine
import SwiftUI

/// The app's one model: the engine's state, the doc on disk, this Mac's settings, and the clock that moves
/// time on. Everything runs on the main thread (the engine answers in a few milliseconds), so a change
/// and what follows it can't cross.
final class Store: ObservableObject {
    static var shared: Store!

    let folder: URL
    let host: EngineHost
    let docs: DocStore
    let settingsStore: MacSettingsStore
    let catalog: SoundCatalog

    @Published private(set) var state: UiState
    @Published var settings: MacSettings {
        didSet {
            guard settings != oldValue else { return }
            settingsStore.write(settings)
            if settings.customLists != oldValue.customLists { categories.custom = settings.customLists }
        }
    }
    /// The ready-made lists of sites, downloaded to this Mac (lists/).
    let categories: CategoryStore
    /// Goes up whenever a ready-made list changes, so the windows that show them redraw.
    @Published private(set) var listsRevision = 0
    /// "You were away from the Mac": waiting for Count them or Take them off.
    @Published var awayPrompt: AwayReturn?
    /// A break longer than the engine allows (5, 10 or 15 minutes) runs on past the engine's own: the Mac keeps its end.
    @Published private(set) var macBreakEnd: Int?
    private var macBreakStart = 0
    /// A break that was postponed comes back at this time, with this many minutes.
    @Published var postponed: (at: Int, minutes: Int)?

    /// What each list's allowance has seen today (usage.json).
    @Published private(set) var usage = DayUsage()
    private let usageStore: UsageStore
    /// Where each block went, app by app (timing.json): kept only on this Mac.
    private(set) var timing = TimingLog()
    private let timingStore: TimingStore
    private var timingDirty = false
    private var tracker = PingTracker()
    /// Pings that came due, for whoever shows them (the cat, a notification).
    var onDue: ([Ping]) -> Void = { _ in }
    /// Everything that follows a change: the sounds, the shortcuts, the notifications.
    var afterChange: [(UiState) -> Void] = []
    /// Everything that looks at the clock, once a second.
    var onSecond: [(Int) -> Void] = []
    /// The sync, which hears about each change that should go up.
    var changed: () -> Void = {}
    private var timer: Timer?
    private var lastHealthyDump = ""

    /// The folder the doc and settings live in: Application Support/<bundle id>, or CHATALTUL_FOLDER (tests).
    static func dataFolder() -> URL {
        if let f = ProcessInfo.processInfo.environment["CHATALTUL_FOLDER"], !f.isEmpty { return URL(fileURLWithPath: f) }
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent(Bundle.main.bundleIdentifier ?? "xyz.tiresia.chataltul")
    }

    init() throws {
        folder = Store.dataFolder()
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let res = Bundle.main.resourceURL!
        host = try EngineHost(js: res.appendingPathComponent("js"))
        catalog = try SoundCatalog.load(res.appendingPathComponent("sounds.json"))
        docs = DocStore(folder: folder)
        settingsStore = MacSettingsStore(folder: folder)
        settings = settingsStore.read()
        usageStore = UsageStore(folder: folder)
        usage = usageStore.read()
        timingStore = TimingStore(folder: folder)
        timing = timingStore.read()
        timing.prune(before: nowMs() - 400 * 86_400_000)
        categories = CategoryStore(folder: folder.appendingPathComponent("lists"), network: ProcessInfo.processInfo.environment["CHATALTUL_NO_LISTS"] == nil)
        // The doc: loaded, or if it doesn't parse, kept aside (never written over) and started anew.
        do { try host.load(docs.read()) }
        catch {
            NSLog("doc.json doesn't load, kept aside: \(error)")
            docs.setAside()
            try host.load(nil)
        }
        let out = try host.run("tick")
        state = out.state
        Art.learn(out.state.cats)
        if out.changed { persist() }
        _ = tracker.update(out.state.pings, at: out.state.now)
        categories.custom = settings.customLists
        categories.onChange = { [weak self] in self?.listsRevision += 1 }
        Store.shared = self
    }

    /// The blocks that started in a span of time, for the window's Today and History (the state carries only the newest 400).
    /// A read: it changes nothing and tells nobody.
    func blocks(from: Int, to: Int) -> [Hist] {
        guard let out = try? host.run("period", ["from": from, "to": to], now: nowMs()), let r = out.result,
              let data = try? JSONEncoder().encode(r), let list = try? JSONDecoder().decode([Hist].self, from: data) else { return [] }
        return list
    }

    // ---------- where it went ----------

    func addTiming(block id: String, start: Int, bundleID: String, name: String, ms: Int, site: (domain: String, browser: String)?) {
        timing.add(block: id, start: start, bundleID: bundleID, name: name, ms: ms, site: site)
        timingDirty = true
    }
    func saveTiming() { if timingDirty { timingStore.write(timing); timingDirty = false } }
    func clearTiming() { timing.clear(); timingStore.erase(); timingDirty = false; objectWillChange.send() }

    /// The lines of "where it went" for these blocks.
    func wentRows(for blockIDs: [String]) -> [WentRow] { Went.rows(timing.timings(for: blockIDs), blocklist: settings.blocklist, matcher: categories) }

    // ---------- the ready-made lists ----------

    /// Fetches the ready-made lists that are switched on and not here yet, or last looked at over a week ago.
    func refreshLists(force: Bool = false) { categories.refresh(settings.blocklist.categoryIDs, force: force) }

    /// Switches a ready-made list on or off in a list, and fetches it when it is switched on.
    func setCategory(_ id: String, on: Bool, in listID: String) {
        guard let i = settings.blocklist.lists.firstIndex(where: { $0.id == listID }) else { return }
        var l = settings.blocklist.lists[i]
        l.categories.removeAll { $0 == id }
        if on { l.categories.append(id) }
        settings.blocklist.lists[i] = l
        if on { categories.refresh([id]) }
    }

    /// A list from its link, switched on in this list. False if it isn't an https address.
    @discardableResult
    func addCustomList(name: String, link: String, to listID: String) -> Bool {
        guard let c = Catalog.custom(name: name, link: link) else { return false }
        settings.customLists.append(c)
        categories.custom = settings.customLists
        setCategory(c.id, on: true, in: listID)
        return true
    }

    /// Takes away a list added by link, and the copy of it on this Mac.
    func removeCustomList(_ id: String) {
        let sources = settings.customLists.first { $0.id == id }?.sources.map { $0.id } ?? []
        for i in settings.blocklist.lists.indices { settings.blocklist.lists[i].categories.removeAll { $0 == id } }
        settings.customLists.removeAll { $0.id == id }
        categories.remove(sources: sources)
    }

    /// Days in a row, with the week's pause.
    var streak: StreakInfo { Streaks.withPause(state.history) }

    // ---------- the lists' allowances ----------

    func usedMs(_ listID: String, at date: Date = Date()) -> Int { var u = usage; return u.used(listID, at: date) }
    func addUsage(_ listID: String, ms: Int, at date: Date = Date()) { usage.add(listID, ms: ms, at: date) }
    func saveUsage() { usageStore.write(usage) }

    // ---------- one change at a time ----------

    @discardableResult
    func act(_ op: String, _ fields: [String: Any] = [:], now: Int = nowMs()) -> Out? {
        do {
            let out = try host.run(op, fields, now: now)
            finish(out, op: op)
            return out
        } catch {
            NSLog("engine: \(op): \(error)")
            return nil
        }
    }

    /// For the sync, which hands the engine an op already written as JSON.
    @discardableResult
    func act(json: String, op: String) -> Out? {
        do {
            let out = try host.run(json: json)
            finish(out, op: op)
            return out
        } catch {
            NSLog("engine: \(op): \(error)")
            return nil
        }
    }

    static let syncOps: Set<String> = ["merge", "empty", "squeeze", "size"]

    private func finish(_ out: Out, op: String) {
        if out.changed {
            persist()
            if !Store.syncOps.contains(op) { changed() }
        }
        Art.learn(out.state.cats)
        state = out.state
        let due = tracker.update(out.state.pings, at: out.state.now)
        if !due.isEmpty { onDue(due) }
        for f in afterChange { f(out.state) }
    }

    private func persist() {
        do { try docs.write(try host.dump()) } catch { NSLog("the doc didn't save: \(error)") }
    }

    /// The doc as the app keeps it, for the sync.
    func docJSON() -> String { (try? host.dump()) ?? "{}" }

    // ---------- the clock ----------

    func startClock() {
        timer?.invalidate()
        let t = Timer(timeInterval: 1, repeats: true) { [weak self] _ in self?.second() }
        t.tolerance = 0.2
        RunLoop.main.add(t, forMode: .common)
        timer = t
    }

    private func second() {
        let now = nowMs()
        if let w = nextWake, now >= w { act("tick", now: now) }
        for f in onSecond { f(now) }
    }

    /// When the engine next has something to do by itself: a ping, or a pause ending.
    var nextWake: Int? {
        [tracker.next, state.view.active?.pauseEnds].compactMap { $0 }.min()
    }

    // ---------- what the screens ask ----------

    var active: Block? { state.view.active }
    var awaiting: Block? { state.view.awaiting }
    /// The break that is on, with the Mac's longer end if it asked for one.
    var onBreak: Brk? {
        let n = nowMs()
        if var b = state.view.onBreak {
            if let e = macBreakEnd, e > b.end { b.end = e; b.minutes = max(b.minutes, (e - b.start) / 60_000) }
            return b
        }
        if let e = macBreakEnd, e > n { return Brk(minutes: (e - macBreakStart) / 60_000, start: macBreakStart, end: e) }
        return nil
    }
    var running: Bool { active != nil && active?.paused == nil }

    func cat(_ id: String?) -> CatInfo { state.cat(id) }
    /// The cat of the block on screen, else the one waiting to be invited.
    var shownCat: CatInfo { cat(active?.cat ?? awaiting?.cat ?? state.companion) }

    private func today(_ ms: Int) -> Bool { Calendar.current.isDateInToday(Date(timeIntervalSince1970: Double(ms) / 1000)) }
    /// Blocks that ran to their end today, for "2 of 6 blocks".
    var blocksToday: Int { state.history.filter { today($0.start) && !$0.stopped }.count }
    var catsHomeToday: Int { state.history.filter { today($0.start) && !$0.stopped && $0.kitten == "home" }.count }

    /// What the engine's own `view.settings` says (check-in interval, style, cat mode).
    var engineSettings: Settings { state.view.settings }

    var mixLabel: String { catalog.label(settings.mix, shop: state.owned) }
    var playableMix: [MixEntry] { catalog.playable(settings.mix, shop: state.owned) }

    // ---------- the changes ----------

    func start(title: String, minutes: Int? = nil, stopwatch: Bool = false) {
        let set = state.view.settings
        var f: [String: Any] = [
            "intention": title, "minutes": minutes ?? settings.focusMinutes, "style": set.style, "mode": stopwatch ? "stopwatch" : "timer",
            "tag": set.tag,
        ]
        f["cat"] = state.catsOn ? (state.companion as Any) : (NSNull() as Any)
        act("start", f)
    }

    /// When a locked list lets go: the end of a running block, if any list is locked. A block like that can't be
    /// stopped once its first ten seconds are over, because stopping it would be the way round the lock.
    var lockedUntil: Int? {
        guard let b = active, GuardPolicy.locksTheBlock(settings.blocklist), nowMs() > b.cancelUntil else { return nil }
        return b.end
    }

    @discardableResult
    func stop() -> Bool {
        guard let b = active else { return false }
        let n = nowMs()
        // Stopping inside the first ten seconds takes the block back, with no harm done.
        if n <= b.cancelUntil { act("cancel"); return true }
        if lockedUntil != nil { return false }
        act("stop")
        return true
    }

    func pause() { act("pause") }
    func resume() { act("resume") }
    func answer(_ answer: String, why: String? = nil) {
        guard let b = active, let n = b.pending else { return }
        var f: [String: Any] = ["id": b.id, "n": n, "answer": answer]
        if let why { f["why"] = why }
        act("answer", f)
    }
    func outcome(_ id: String, _ o: String?) { act("outcome", ["id": id, "outcome": o as Any? ?? NSNull()]) }
    func note(_ id: String, _ text: String) { act("edit", ["id": id, "patch": ["note": text]]) }
    func park(_ text: String) -> Bool {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return false }
        // On a running block, the thought is also kept on the block; otherwise it only waits in Later.
        return act(active != nil ? "park" : "addLater", ["text": t])?.error == nil
    }
    /// Starts a break. The engine only knows breaks of 5, 10 and 15 minutes; a longer one starts the longest of those and
    /// the Mac keeps its own end (docs/engine-takeoff.md asks the web engine for more lengths).
    func startBreak(_ minutes: Int) {
        let engine = [5, 10, 15].filter { $0 <= minutes }.max() ?? 5
        let n = nowMs()
        postponed = nil
        if act("startBreak", ["minutes": engine], now: n)?.error == nil, minutes > engine {
            macBreakStart = n; macBreakEnd = n + minutes * 60_000
        } else { macBreakEnd = nil }
    }
    func stopBreak() { macBreakEnd = nil; act("stopBreak") }

    /// The Mac's own end of a long break has come.
    func macBreakOver() -> Bool {
        guard let e = macBreakEnd, nowMs() >= e else { return false }
        macBreakEnd = nil
        act("stopBreak")
        return true
    }

    // ---------- the cycle ----------

    var preset: CyclePreset { settings.cycle.preset }

    /// Chooses a cycle: its focus and break lengths become the popover's.
    func apply(_ p: CyclePreset) {
        settings.cycle.preset = p
        if let m = p.minutes { settings.focusMinutes = m.focus; settings.cycle.shortBreak = m.rest }
    }

    /// The break to offer after this block: a long one after every few, a fifth of the stretch in Flowtime.
    func breakAfter(_ b: Block?) -> Int {
        if preset == .flow { return CyclePreset.flowBreak(focusedMinutes: b.map { max(1, ($0.ended ?? nowMs()) - $0.start) / 60_000 } ?? 25) }
        return settings.cycle.isLong(afterBlocks: blocksToday) ? settings.cycle.longBreak : settings.cycle.shortBreak
    }
    func setEngine(_ patch: [String: Any]) { act("settings", ["patch": patch]) }

    /// The kitten wanders off because the person left (the speed bump, or being away).
    @discardableResult
    func kittenLeaves(at: Int, grace: Int) -> Bool {
        let out = act("away", ["at": at, "grace": grace], now: max(nowMs(), at + grace))
        return out?.result != nil && out?.result != JSONValue.null
    }

    // ---------- away ----------

    func countAway() { awayPrompt = nil }
    func takeAwayOff() {
        guard let a = awayPrompt else { return }
        awayPrompt = nil
        act("takeOff", ["from": a.from, "to": a.to])
    }
}
