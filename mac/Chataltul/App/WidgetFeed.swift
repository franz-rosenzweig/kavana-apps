import AppKit
import SwiftUI
import WidgetKit

/// Keeps the desktop widgets fed. What they show goes to widget/now.json in the app's folder, the only place the sandboxed
/// widget may read, with the cat's paintings and a still picture of today's café beside it; then WidgetKit is told.
final class WidgetFeed {
    let folder: URL
    private let store: Store
    private var lastJSON = Data()
    private var cafeSig = ""
    private var queued = false
    private var day = ""
    /// Off in tests: nothing is asked of WidgetKit.
    var tellWidgetKit = true

    init(store: Store) {
        self.store = store
        folder = store.folder.appendingPathComponent("widget", isDirectory: true)
        try? FileManager.default.createDirectory(at: folder.appendingPathComponent("art", isDirectory: true), withIntermediateDirectories: true)
    }

    /// Something changed: written on the next turn of the run loop, so a burst of changes writes once.
    func changed() {
        guard !queued else { return }
        queued = true
        DispatchQueue.main.async { [weak self] in self?.queued = false; self?.write() }
    }

    /// Once a second from the app: a new day means new numbers, and the café's light follows the time of day.
    func second(_ now: Int) {
        let d = WidgetSnapshot.dayKey(now)
        if d != day || now % 600_000 < 1000 { changed() }
    }

    // ---------------------------------------------------------------- what the widgets show

    func snapshot(now: Int = nowMs()) -> WidgetSnapshot {
        let st = store.state
        var s = WidgetSnapshot()
        s.written = now
        s.day = WidgetSnapshot.dayKey(now)
        s.catsOn = st.catsOn
        let block = store.active ?? store.awaiting
        if let b = store.onBreak, b.end > now {
            s.phase = .onBreak; s.breakEnd = b.end
        } else if let b = store.active {
            s.phase = b.paused != nil ? .paused : b.pending != nil ? .checkin : .focus
        } else if store.awaiting != nil {
            s.phase = .done
        }
        if let b = block {
            s.run = WidgetSnapshot.Run(title: b.title, start: b.start, end: b.ended ?? b.end, minutes: b.minutes, stopwatch: b.stopwatch,
                                       pausedAt: b.paused, resumes: b.pauseEnds, kitten: b.kitten)
        }
        let cat = store.cat(block?.cat ?? st.companion)
        s.catName = cat.name
        s.art = ["nap": Art.nap(cat.id), "kitten": Art.kitten(cat.id), "grown": Art.portrait(cat.id), "gone": Art.gone]
            .mapValues { ($0 as NSString).lastPathComponent }
        s.focusMinutes = store.settings.focusMinutes
        s.quick = [25, 50, 90]
        s.todayMin = st.stats.today
        s.blocksToday = store.blocksToday
        s.perDay = store.settings.cycle.perDay
        s.catsHome = store.catsHomeToday
        s.goalMin = st.view.settings.goal
        let streak = store.streak
        s.streak = streak.days; s.pausesLeft = streak.pausesLeft
        let cal = Calendar.current
        let today = cal.startOfDay(for: Date(timeIntervalSince1970: Double(now) / 1000))
        let letter = DateFormatter(); letter.dateFormat = "EEEEE"
        for k in (0..<7).reversed() {
            let d = cal.date(byAdding: .day, value: -k, to: today) ?? today
            s.week.append(st.history.contains { !$0.stopped && cal.isDate(Date(timeIntervalSince1970: Double($0.start) / 1000), inSameDayAs: d) })
            s.weekLetters.append(letter.string(from: d))
        }
        let from = Int(today.timeIntervalSince1970 * 1000)
        s.today = store.blocks(from: from, to: now + 1).sorted { $0.start > $1.start }.prefix(8)
            .map { WidgetSnapshot.Done(title: $0.title, minutes: $0.actual, home: $0.kitten == "home") }
        s.later = st.view.later.prefix(8).map { $0.text }
        s.laterCount = st.view.later.count
        return s
    }

    /// Writes now.json, the paintings it names and the café's picture, and tells WidgetKit when anything changed.
    @discardableResult
    func write(now: Int = nowMs()) -> Bool {
        var s = snapshot(now: now)
        day = s.day
        for name in s.art.values { copyArt(name) }
        let pictures = cafePictures(now: now)
        s.cafeLight = pictures.light; s.cafeDark = pictures.dark
        var same = s; same.written = 0
        let sig = same.encoded()
        guard sig != lastJSON || pictures.new else { return false }
        lastJSON = sig
        do { try s.encoded().write(to: folder.appendingPathComponent(WidgetSnapshot.file), options: .atomic) } catch { NSLog("widget: now.json didn't save: \(error)"); return false }
        if tellWidgetKit { WidgetCenter.shared.reloadAllTimelines() }
        return true
    }

    /// A painting of the app's, copied once into widget/art (the widget can't read inside the app).
    private func copyArt(_ name: String) {
        let to = folder.appendingPathComponent("art/\(name)")
        guard !FileManager.default.fileExists(atPath: to.path), let res = Bundle.main.resourceURL else { return }
        for dir in ["cats", "cafe"] {
            let from = res.appendingPathComponent("\(dir)/\(name)")
            if FileManager.default.fileExists(atPath: from.path) { try? FileManager.default.copyItem(at: from, to: to); return }
        }
    }

    // ---------------------------------------------------------------- the café's picture

    static let cafeSize = CGSize(width: 640, height: 420)

    /// Today's café, drawn again only when it looks different: other cats home, other things, another time of day.
    private func cafePictures(now: Int) -> (light: String?, dark: String?, new: Bool) {
        let from = Int(Calendar.current.startOfDay(for: Date(timeIntervalSince1970: Double(now) / 1000)).timeIntervalSince1970 * 1000)
        let blocks = store.blocks(from: from, to: now + 1)
        let seed = from / 1000
        let light = CafeData.make(store: store, blocks: blocks, span: .day, dark: false, seed: seed)
        let sig = "\(light.theme)|\(light.light)|\(light.things)|\(light.cats.map { $0.key + $0.src })|\(seed)"
        let names = ("cafe-light.png", "cafe-dark.png")
        let have = FileManager.default.fileExists(atPath: folder.appendingPathComponent(names.0).path)
        if sig == cafeSig, have { return (names.0, names.1, false) }
        var dark = light; dark.dim = true
        guard let l = WidgetFeed.render(light), let d = WidgetFeed.render(dark) else { return (have ? names.0 : nil, have ? names.1 : nil, false) }
        try? l.write(to: folder.appendingPathComponent(names.0), options: .atomic)
        try? d.write(to: folder.appendingPathComponent(names.1), options: .atomic)
        cafeSig = sig
        return (names.0, names.1, true)
    }

    /// The café drawn once to a PNG, the way the window draws it, standing still.
    static func render(_ data: CafeData, size: CGSize = cafeSize) -> Data? {
        // the app's code all runs on the main thread, where drawing has to be
        MainActor.assumeIsolated {
            let scene = CafeScene()
            scene.show(data)
            scene.step(date: Date(), size: size)
            let view = Canvas { ctx, sz in scene.draw(ctx, size: sz) }.frame(width: size.width, height: size.height)
            let r = ImageRenderer(content: view)
            r.scale = 2
            guard let cg = r.cgImage else { return nil }
            return NSBitmapImageRep(cgImage: cg).representation(using: .png, properties: [:])
        }
    }
}
