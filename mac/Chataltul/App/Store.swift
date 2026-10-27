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
    @Published var settings: MacSettings { didSet { if settings != oldValue { settingsStore.write(settings) } } }
    /// "You were away from the Mac": waiting for Count them or Take them off.
    @Published var awayPrompt: AwayReturn?
    /// A short line that says what just happened ("Miso wandered off while you were away").
    @Published var toast: String?

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
        Store.shared = self
    }

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
    var onBreak: Brk? { state.view.onBreak }
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

    func start(title: String, minutes: Int? = nil) {
        let set = state.view.settings
        var f: [String: Any] = [
            "intention": title, "minutes": minutes ?? settings.focusMinutes, "style": set.style, "mode": "timer",
            "tag": set.tag,
        ]
        f["cat"] = state.catsOn ? (state.companion as Any) : (NSNull() as Any)
        act("start", f)
    }

    func stop() {
        guard let b = active else { return }
        let n = nowMs()
        // Stopping inside the first ten seconds takes the block back, with no harm done.
        if n <= b.cancelUntil { act("cancel") } else { act("stop") }
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
    func startBreak(_ minutes: Int) { act("startBreak", ["minutes": minutes]) }
    func stopBreak() { act("stopBreak") }
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
