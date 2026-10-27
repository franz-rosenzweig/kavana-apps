import Foundation

public enum Span: String, CaseIterable { case day, week, month, year
    public var title: String { rawValue.capitalized }
}

/// A day, week, month or year, as the app window's Today shows it, and what the blocks in it add up to.
public struct Period: Equatable {
    public var span: Span
    public var from: Int
    public var to: Int
    public var start: Date { Date(timeIntervalSince1970: Double(from) / 1000) }
}

public enum Insights {
    /// The period that has `date` in it. Weeks start on Monday.
    public static func period(_ span: Span, containing date: Date, calendar cal: Calendar = .current) -> Period {
        var c = cal
        c.firstWeekday = 2
        let (a, b): (Date, Date)
        switch span {
        case .day:
            a = c.startOfDay(for: date); b = c.date(byAdding: .day, value: 1, to: a)!
        case .week:
            a = c.dateInterval(of: .weekOfYear, for: date)!.start; b = c.date(byAdding: .day, value: 7, to: a)!
        case .month:
            a = c.dateInterval(of: .month, for: date)!.start; b = c.date(byAdding: .month, value: 1, to: a)!
        case .year:
            a = c.dateInterval(of: .year, for: date)!.start; b = c.date(byAdding: .year, value: 1, to: a)!
        }
        return Period(span: span, from: Int(a.timeIntervalSince1970 * 1000), to: Int(b.timeIntervalSince1970 * 1000))
    }

    /// The period before this one (for "+6 h on September").
    public static func before(_ p: Period, calendar cal: Calendar = .current) -> Period {
        let d = cal.date(byAdding: .second, value: -1, to: p.start)!
        return period(p.span, containing: d, calendar: cal)
    }

    public static func blocks(_ list: [Hist], in p: Period) -> [Hist] { list.filter { $0.start >= p.from && $0.start < p.to } }

    public static func minutes(_ list: [Hist]) -> Int { list.reduce(0) { $0 + $1.actual } }

    /// Cats that came home, cats that wandered off.
    public static func home(_ list: [Hist]) -> Int { list.filter { $0.kitten == "home" }.count }
    public static func wandered(_ list: [Hist]) -> Int { list.filter { $0.kitten == "gone" }.count }

    /// Done, partly, not really.
    public static func outcomes(_ list: [Hist]) -> (done: Int, partly: Int, notReally: Int) {
        (list.filter { $0.outcome == "done" }.count, list.filter { $0.outcome == "partly" }.count, list.filter { $0.outcome == "notreally" }.count)
    }

    public static func drifts(_ list: [Hist]) -> Int { list.reduce(0) { $0 + $1.drifts } }

    /// Minutes in each slot of the period: hours of a day, days of a week or month, months of a year.
    public static func slots(_ list: [Hist], in p: Period, calendar cal: Calendar = .current) -> [Int] {
        let n: Int
        switch p.span {
        case .day: n = 24
        case .week: n = 7
        case .month: n = cal.range(of: .day, in: .month, for: p.start)!.count
        case .year: n = 12
        }
        var out = [Int](repeating: 0, count: n)
        for b in list {
            // minute by minute, so a block over the hour splits
            for i in 0..<max(0, b.actual) {
                let t = b.start + i * 60_000
                guard t >= p.from, t < p.to else { continue }
                let d = Date(timeIntervalSince1970: Double(t) / 1000)
                let k: Int
                switch p.span {
                case .day: k = cal.component(.hour, from: d)
                case .week: k = ((cal.component(.weekday, from: d) + 5) % 7)   // Monday is 0
                case .month: k = cal.component(.day, from: d) - 1
                case .year: k = cal.component(.month, from: d) - 1
                }
                if k >= 0, k < n { out[k] += 1 }
            }
        }
        return out
    }

    /// Minutes by tag, biggest first (no tag is "Untagged").
    public static func byTag(_ list: [Hist]) -> [(tag: String, minutes: Int)] {
        var m: [String: Int] = [:]
        for b in list { m[b.tag ?? "", default: 0] += b.actual }
        return m.map { (($0.key.isEmpty ? "Untagged" : $0.key), $0.value) }.sorted { $0.minutes != $1.minutes ? $0.minutes > $1.minutes : $0.tag < $1.tag }
    }

    /// The hour (0...23) with the most minutes, and all hours.
    public static func byHour(_ list: [Hist], calendar cal: Calendar = .current) -> [Int] {
        var h = [Int](repeating: 0, count: 24)
        for b in list {
            for i in 0..<max(0, b.actual) {
                let d = Date(timeIntervalSince1970: Double(b.start + i * 60_000) / 1000)
                h[cal.component(.hour, from: d)] += 1
            }
        }
        return h
    }

    /// Days in a row ending today (or yesterday, which still counts until today ends).
    public static func streak(_ list: [Hist], today: Date = Date(), calendar cal: Calendar = .current) -> Int {
        let days = Set(list.map { cal.startOfDay(for: Date(timeIntervalSince1970: Double($0.start) / 1000)) })
        var d = cal.startOfDay(for: today)
        if !days.contains(d) { d = cal.date(byAdding: .day, value: -1, to: d)! }
        var n = 0
        while days.contains(d) { n += 1; d = cal.date(byAdding: .day, value: -1, to: d)! }
        return n
    }

    /// "2 h 10 min", "40 min".
    public static func hours(_ minutes: Int) -> String { fmtMin(minutes) }

    /// "+6 h on September" style: the difference to the period before, or nil when that had nothing.
    public static func compare(_ now: Int, _ before: Int) -> String? {
        guard before > 0 else { return nil }
        let d = now - before
        return (d >= 0 ? "+" : "−") + fmtMin(abs(d))
    }
}
