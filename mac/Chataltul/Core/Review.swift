import Foundation

// The week in review: streaks with a pause, a few words about the week made on this Mac, answers to a few
// questions about it, and the files that can be exported. No network, no model needed.

public enum Reason {
    public static let ids = ["phone", "messages", "email", "web", "tired", "hungry", "thoughts", "people", "other"]
    public static func label(_ id: String) -> String {
        ["phone": "Phone", "messages": "Messages", "email": "Email", "web": "The web", "thoughts": "Thoughts", "people": "People", "tired": "Tired", "hungry": "Hunger", "other": "Other"][id] ?? id.capitalized
    }
    /// The few shown on the cat's card, quick to tap.
    public static let quick = ["phone", "web", "messages", "thoughts", "tired"]
}

public struct StreakInfo: Equatable {
    /// Days in a row ending today (or yesterday, which still counts until today ends).
    public var days: Int
    /// The days a pause carried the streak over.
    public var pausedDays: [Date]
    /// Pauses left this week: one a week.
    public var pausesLeft: Int
}

public enum Streaks {
    /// Days in a row, where one missed day a week is carried over by the week's pause (weeks start on Monday). The
    /// paused day doesn't add to the count, and a pause only bridges a single day: two missed days in a row end it.
    public static func withPause(_ list: [Hist], today: Date = Date(), calendar: Calendar = .current) -> StreakInfo {
        var cal = calendar
        cal.firstWeekday = 2
        let focused = Set(list.filter { $0.actual > 0 }.map { cal.startOfDay(for: Date(timeIntervalSince1970: Double($0.start) / 1000)) })
        func before(_ d: Date) -> Date { cal.date(byAdding: .day, value: -1, to: d)! }
        func week(_ d: Date) -> Date { cal.dateInterval(of: .weekOfYear, for: d)!.start }
        var d = cal.startOfDay(for: today)
        if !focused.contains(d) { d = before(d) }   // today isn't over
        var used = Set<Date>()
        var paused: [Date] = []
        var n = 0
        while true {
            if focused.contains(d) { n += 1; d = before(d); continue }
            let w = week(d)
            if !used.contains(w), focused.contains(before(d)) { used.insert(w); paused.append(d); d = before(d); continue }
            break
        }
        return StreakInfo(days: n, pausedDays: paused, pausesLeft: used.contains(week(today)) ? 0 : 1)
    }
}

/// What a period came to, for the few words about it.
public struct WeekFacts: Equatable {
    public var minutes = 0
    public var blocks = 0
    public var bestDay: (name: String, minutes: Int)?
    public var topTag: String?
    public var reasons: [(id: String, times: Int)] = []
    /// Most of the blocks with drifts began after 3 PM.
    public var lateDrifts = false
    public var goalMinutes = 0
    public var daysMet = 0
    public var bestHour: Int?
    public var streak = StreakInfo(days: 0, pausedDays: [], pausesLeft: 1)
    public var done = 0, partly = 0, notReally = 0
    public var home = 0, wandered = 0

    public static func == (a: WeekFacts, b: WeekFacts) -> Bool { a.minutes == b.minutes && a.blocks == b.blocks && a.topTag == b.topTag && a.daysMet == b.daysMet }
}

public enum Review {
    public static let days = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]

    public static func facts(blocks: [Hist], period: Period, goalMin: Int, streak: StreakInfo, calendar: Calendar = .current) -> WeekFacts {
        var f = WeekFacts()
        f.minutes = Insights.minutes(blocks); f.blocks = blocks.count
        let slots = Insights.slots(blocks, in: period, calendar: calendar)
        if period.span == .week, let i = slots.enumerated().max(by: { $0.element < $1.element }), i.element > 0 { f.bestDay = (days[i.offset], i.element) }
        f.topTag = Insights.byTag(blocks).first(where: { $0.tag != "Untagged" })?.tag
        var counts: [String: Int] = [:]
        for b in blocks { for w in b.whys { counts[w, default: 0] += 1 } }
        f.reasons = counts.sorted { $0.value != $1.value ? $0.value > $1.value : $0.key < $1.key }.map { ($0.key, $0.value) }
        let drifted = blocks.filter { $0.drifts > 0 }
        let late = drifted.filter { calendar.component(.hour, from: Date(timeIntervalSince1970: Double($0.start) / 1000)) >= 15 }
        f.lateDrifts = drifted.count >= 2 && late.count * 10 >= drifted.count * 6
        f.goalMinutes = goalMin
        if goalMin > 0, period.span == .week { f.daysMet = slots.filter { $0 >= goalMin }.count }
        let h = Insights.byHour(blocks, calendar: calendar)
        if let b = h.enumerated().max(by: { $0.element < $1.element }), b.element > 0 { f.bestHour = b.offset }
        f.streak = streak
        let o = Insights.outcomes(blocks); f.done = o.done; f.partly = o.partly; f.notReally = o.notReally
        f.home = Insights.home(blocks); f.wandered = Insights.wandered(blocks)
        return f
    }

    /// "A good week: 11 h 20 min over 14 blocks, best on Tuesday. Writing took most of it. When you drifted it was
    /// usually the web, mostly in blocks that began after 3 PM, so the afternoon blocks might want the speed bump on."
    public static func summary(_ f: WeekFacts) -> String {
        if f.blocks == 0 { return "No blocks yet this week. Start one, and what it came to shows up here." }
        let verdict: String
        if f.goalMinutes > 0 { verdict = f.daysMet >= 4 ? "A good week" : f.daysMet >= 2 ? "An uneven week" : "A quiet week" }
        else { verdict = f.minutes >= 600 ? "A good week" : f.minutes >= 240 ? "A steady week" : "A quiet week" }
        var s = "\(verdict): \(Insights.hours(f.minutes)) over \(f.blocks) block\(f.blocks == 1 ? "" : "s")"
        if let d = f.bestDay { s += ", best on \(d.name)" }
        s += "."
        if let t = f.topTag { s += " \(t) took most of it." }
        if let r = f.reasons.first {
            let what = r.id == "web" ? "the web" : r.id == "phone" ? "the phone" : Reason.label(r.id).lowercased()
            s += " When you drifted it was usually \(what)"
            if f.lateDrifts { s += ", mostly in blocks that began after 3 PM" }
            if r.id == "web" { s += ", so \(f.lateDrifts ? "the afternoon blocks" : "your blocks") might want the speed bump on" }
            else if r.id == "phone" { s += ", which is out of Chataltul's reach, so maybe put it in another room" }
            s += "."
        }
        return s
    }

    /// A few questions answered from the numbers, for when no model is there to ask. Nil: it doesn't know.
    public static func answer(_ question: String, _ f: WeekFacts) -> String {
        let q = question.lowercased()
        func has(_ words: String...) -> Bool { words.contains { q.contains($0) } }
        if f.blocks == 0 { return "There's nothing to go on yet: no blocks in this period." }
        if has("best day", "most productive", "which day", "busiest") {
            if let d = f.bestDay { return "Your best day was \(d.name), with \(Insights.hours(d.minutes))." }
            return "There's no best day to name in this period."
        }
        if has("when", "what time", "hour") {
            if let h = f.bestHour { return "You focus most around \(hourName(h))." }
        }
        if has("how much", "how long", "total", "hours", "time did i") { return "\(Insights.hours(f.minutes)) over \(f.blocks) block\(f.blocks == 1 ? "" : "s")." }
        if has("distract", "drift", "pulled", "away from") {
            if f.reasons.isEmpty { return "No drifts with a reason were noted in this period." }
            return "What pulled you away: " + f.reasons.prefix(3).map { "\(Reason.label($0.id).lowercased()) \($0.times) time\($0.times == 1 ? "" : "s")" }.joined(separator: ", ") + "."
        }
        if has("tag", "most of", "writing", "reading", "work", "study") {
            if let t = f.topTag { return "\(t) took most of it." }
        }
        if has("streak", "in a row") {
            return "\(f.streak.days) day\(f.streak.days == 1 ? "" : "s") in a row" + (f.streak.pausesLeft > 0 ? ", with a pause left this week." : ", and this week's pause is used.")
        }
        if has("cat", "came home", "wander") { return "\(f.home) cat\(f.home == 1 ? "" : "s") came home and \(f.wandered) wandered off." }
        if has("how did", "go", "outcome", "done") { return "\(f.done) done, \(f.partly) partly, \(f.notReally) not really." }
        return "I can answer from the numbers about your best day, when you focus, how much you did, what pulled you away, your tags, your streak and how the blocks went."
    }

    /// The numbers as plain lines, for a model to answer from.
    public static func factsText(_ f: WeekFacts, title: String) -> String {
        var l = ["Period: \(title)", "Focused: \(Insights.hours(f.minutes)) over \(f.blocks) blocks", "Cats came home: \(f.home), wandered off: \(f.wandered)",
                 "Outcomes: \(f.done) done, \(f.partly) partly, \(f.notReally) not really", "Streak: \(f.streak.days) days in a row"]
        if let d = f.bestDay { l.append("Best day: \(d.name), \(Insights.hours(d.minutes))") }
        if let t = f.topTag { l.append("Most time went to: \(t)") }
        if let h = f.bestHour { l.append("Focus most around: \(hourName(h))") }
        if !f.reasons.isEmpty { l.append("What pulled the person away: " + f.reasons.map { "\(Reason.label($0.id)) \($0.times)" }.joined(separator: ", ")) }
        if f.goalMinutes > 0 { l.append("Daily goal: \(Insights.hours(f.goalMinutes))") }
        return l.joined(separator: "\n")
    }

    public static func hourName(_ h: Int) -> String { h == 0 ? "12 AM" : h < 12 ? "\(h) AM" : h == 12 ? "12 PM" : "\(h - 12) PM" }
}

// ---------------------------------------------------------------- export

public enum Export {
    /// One CSV field: quoted when it has a comma, a quote or a line break.
    public static func field(_ s: String) -> String {
        guard s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r") else { return s }
        return "\"" + s.replacingOccurrences(of: "\"", with: "\"\"") + "\""
    }
    public static func csv(_ rows: [[String]]) -> String { rows.map { $0.map(field).joined(separator: ",") }.joined(separator: "\n") + "\n" }

    public static func blocksCSV(_ blocks: [Hist], catName: (String) -> String, calendar: Calendar = .current) -> String {
        let f = ISO8601DateFormatter()
        f.timeZone = calendar.timeZone
        var rows = [["start", "minutes", "title", "tag", "how it went", "drifts", "what pulled away", "cat", "the kitten", "note"]]
        for b in blocks.sorted(by: { $0.start < $1.start }) {
            rows.append([f.string(from: Date(timeIntervalSince1970: Double(b.start) / 1000)), "\(b.actual)", b.title, b.tag ?? "",
                         b.stopped ? "stopped early" : (b.outcome ?? ""), "\(b.drifts)", b.whys.map(Reason.label).joined(separator: "; "),
                         catName(b.cat), b.kitten == "home" ? "came home" : b.kitten == "gone" ? "wandered off" : "", b.note])
        }
        return csv(rows)
    }

    public static func wentCSV(_ rows: [WentRow]) -> String {
        csv([["app", "minutes", "share"]] + rows.map { [$0.label, "\($0.minutes)", String(format: "%.0f%%", $0.share * 100)] })
    }

    public static func reviewMarkdown(title: String, range: String, summary: String, f: WeekFacts, went: [WentRow]) -> String {
        var l = ["# \(title)", "", "_\(range)_", "", summary, "", "- Focused: \(Insights.hours(f.minutes)) over \(f.blocks) blocks",
                 "- Cats came home: \(f.home), wandered off: \(f.wandered)", "- How the blocks went: \(f.done) done, \(f.partly) partly, \(f.notReally) not really",
                 "- Days in a row: \(f.streak.days)" + (f.streak.pausedDays.isEmpty ? "" : " (a pause carried it over a day)")]
        if !f.reasons.isEmpty { l += ["", "## What pulled you away", ""] + f.reasons.map { "- \(Reason.label($0.id)): \($0.times)" } }
        if !went.isEmpty { l += ["", "## Where the blocks went", "", "_Kept only on this Mac._", ""] + went.map { "- \($0.label): \($0.minutes) min (\(Int(($0.share * 100).rounded()))%)" } }
        return l.joined(separator: "\n") + "\n"
    }
}
