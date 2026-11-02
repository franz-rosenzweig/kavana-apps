import Foundation

/// What the desktop widgets show. The app writes it to widget/now.json in its folder whenever something changes, and the
/// widget extension, which is sandboxed and may only read that folder, draws from it. It holds numbers and what the person
/// typed (a block's title, the thoughts in Later), never a web address or the name of a site or an app.
public struct WidgetSnapshot: Codable, Equatable {
    public enum Phase: String, Codable { case ready, focus, paused, checkin, onBreak = "break", done }

    /// The block that is running, paused, or waiting for its outcome.
    public struct Run: Codable, Equatable {
        public var title: String
        public var start: Int
        public var end: Int
        public var minutes: Int
        public var stopwatch: Bool
        /// When it was paused, while it is, and when the pause ends by itself.
        public var pausedAt: Int?
        public var resumes: Int?
        /// growing, home or gone.
        public var kitten: String

        public init(title: String, start: Int, end: Int, minutes: Int, stopwatch: Bool = false, pausedAt: Int? = nil, resumes: Int? = nil, kitten: String = "growing") {
            self.title = title; self.start = start; self.end = end; self.minutes = minutes; self.stopwatch = stopwatch
            self.pausedAt = pausedAt; self.resumes = resumes; self.kitten = kitten
        }
    }

    /// A block of today, for the large Today widget.
    public struct Done: Codable, Equatable {
        public var title: String
        public var minutes: Int
        public var home: Bool
        public init(title: String, minutes: Int, home: Bool) { self.title = title; self.minutes = minutes; self.home = home }
    }

    public var written = 0
    /// The day the day's numbers are for (yyyy-MM-dd, this Mac's time zone): after midnight they read as nothing yet.
    public var day = ""
    public var phase = Phase.ready
    public var run: Run?
    public var breakEnd: Int?
    public var catName = "Miso"
    /// The cat's paintings as file names in widget/art: nap, kitten, grown and gone.
    public var art: [String: String] = [:]
    public var catsOn = true
    /// The minutes Start starts, and the Quick start widget's.
    public var focusMinutes = 25
    public var quick = [25, 50, 90]
    public var todayMin = 0
    public var blocksToday = 0
    public var perDay = 6
    public var catsHome = 0
    public var goalMin = 0
    public var streak = 0
    public var pausesLeft = 0
    /// The last seven days, oldest first: whether a block ran to its end on each, and the day's letter.
    public var week: [Bool] = []
    public var weekLetters: [String] = []
    public var today: [Done] = []
    public var later: [String] = []
    public var laterCount = 0
    /// The café's still picture, light and dark, as file names in widget/.
    public var cafeLight: String?
    public var cafeDark: String?

    public init() {}

    public static let file = "now.json"

    public func encoded() -> Data {
        let e = JSONEncoder()
        e.outputFormatting = [.sortedKeys]
        return (try? e.encode(self)) ?? Data()
    }
    public static func decode(_ data: Data) -> WidgetSnapshot? { try? JSONDecoder().decode(WidgetSnapshot.self, from: data) }
}

// ---------------------------------------------------------------- what changes by itself

extension WidgetSnapshot {
    public enum Stage: String { case nap, kitten, grown, gone }

    public static func dayKey(_ ms: Int, calendar: Calendar = .current) -> String {
        let c = calendar.dateComponents([.year, .month, .day], from: Date(timeIntervalSince1970: Double(ms) / 1000))
        return String(format: "%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0)
    }

    /// Whether the day's numbers are still today's at this time.
    public func isToday(at t: Int, calendar: Calendar = .current) -> Bool { day == WidgetSnapshot.dayKey(t, calendar: calendar) }

    /// The day's numbers at a time: after midnight, until the app writes again, nothing yet today.
    public func dayNumbers(at t: Int, calendar: Calendar = .current) -> (minutes: Int, blocks: Int, home: Int, list: [Done]) {
        isToday(at: t, calendar: calendar) ? (todayMin, blocksToday, catsHome, today) : (0, 0, 0, [])
    }

    /// The share of the block gone by at a time, 0 to 1, held while it is paused. A stopwatch fills over its minutes, or an hour.
    public func progress(at t: Int) -> Double {
        guard let r = run else { return 0 }
        if r.kitten == "home" { return 1 }
        let at = min(t, r.pausedAt ?? t)
        if r.stopwatch { return max(0, min(1, Double(at - r.start) / Double(max(1, r.minutes > 0 ? r.minutes : 60) * 60_000))) }
        return max(0, min(1, Double(at - r.start) / Double(max(1, r.end - r.start))))
    }

    /// Which painting the cat is in at a time. It grows with the block (web/app.js renderSky), and a block under 25 minutes
    /// brings home a kitten (App/Art.swift says the same for the popover).
    public func stage(at t: Int) -> Stage {
        guard let r = run else { return .nap }
        if r.kitten == "gone" { return .gone }
        let small = r.stopwatch ? (min(t, r.pausedAt ?? t) - r.start) < 25 * 60_000 : r.minutes < 25
        if r.kitten == "home" { return small ? .kitten : .grown }
        let p = progress(at: t)
        let s: Stage = p < 0.25 ? .nap : p < 0.75 ? .kitten : .grown
        return s == .grown && small ? .kitten : s
    }

    /// The painting for a stage, as a file name in widget/art.
    public func artName(_ s: Stage) -> String? { art[s.rawValue] }

    /// A running block whose end went by long ago with nothing new from the app (it isn't running): the widget says to open it.
    public func isStale(at t: Int) -> Bool {
        guard let r = run, phase == .focus || phase == .checkin, !r.stopwatch else { return false }
        return t > r.end + 2 * 60_000
    }

    /// The moments the Focus widget changes by itself, from now: each minute of a running block (the ring and the cat
    /// growing) and its end. The clock's seconds need none: the widget counts them down itself.
    public func moments(from now: Int, limit: Int = 180) -> [Int] {
        var out = [now]
        guard phase == .focus || phase == .checkin, let r = run, r.pausedAt == nil, r.kitten == "growing" else { return out }
        let stop = r.stopwatch ? now + 60 * 60_000 : r.end
        var t = now - ((now - r.start) % 60_000 + 60_000) % 60_000 + 60_000
        while t < stop, out.count < limit - 1 { out.append(t); t += 60_000 }
        if !r.stopwatch, r.end > now, out.last != r.end { out.append(r.end) }
        return out
    }

    /// When to read now.json again: a little after the block, the pause or the break ends, else at the next midnight
    /// (the day's numbers start again). Never sooner than a minute from now.
    public func refresh(after now: Int, calendar: Calendar = .current) -> Int {
        let d = Date(timeIntervalSince1970: Double(now) / 1000)
        let next = calendar.date(byAdding: .day, value: 1, to: calendar.startOfDay(for: d)) ?? d.addingTimeInterval(86_400)
        var t = Int(next.timeIntervalSince1970 * 1000)
        if let r = run {
            if phase == .focus || phase == .checkin { t = min(t, r.stopwatch ? now + 3600_000 : r.end + 10_000) }
            if phase == .paused, let e = r.resumes { t = min(t, e + 10_000) }
        }
        if phase == .onBreak, let e = breakEnd { t = min(t, e + 10_000) }
        return max(now + 60_000, t)
    }

    /// The links the widgets open: the app takes them (AppDelegate.open).
    public static let scheme = "chataltul"
    public static func link(_ path: String) -> URL { URL(string: "\(scheme)://\(path)")! }
    public static func startLink(_ minutes: Int) -> URL { link("start?minutes=\(minutes)") }
}
