import Foundation

// What a list does at a given moment, and the cards for "Wait it out" and "Locked". No screen in here, so the
// self-test can run it. The speed bump itself (the cat, the 15 seconds, the hold) is in SpeedBump.swift.

/// What a list asks for right now.
public struct Enforcement: Equatable {
    public enum Reason: Equatable {
        /// A block is running.
        case block
        /// Outside a block, inside the list's window.
        case schedule
        /// Outside a block, and the day's allowance is used up.
        case allowanceUsed
    }
    public var mode: GuardMode
    public var reason: Reason
    public var listName: String
    /// When it stops: the block's end, or the end of the window today. Nil when there's no end to name.
    public var until: Int?
}

public enum GuardPolicy {
    /// `blockEnd`: the running block's end, or nil when none runs. `usedMs`: what the list's allowance has
    /// seen today. A speed bump is the cat's and its kitten's: outside a block it has neither, so it asks
    /// for the wait instead.
    public static func enforcement(for list: GuardList, now: Date, blockEnd: Int?, usedMs: Int, calendar: Calendar = .current) -> Enforcement? {
        if let end = blockEnd {
            return Enforcement(mode: list.mode, reason: .block, listName: list.name, until: end)
        }
        guard list.when.covers(now, calendar: calendar) else { return nil }
        let allowed = list.allowanceMin * 60_000
        if allowed > 0, usedMs < allowed { return nil }   // still inside the allowance: free, and it is counted
        let mode: GuardMode = list.mode == .speedBump ? .waitItOut : list.mode
        let end = list.when.end(on: now, calendar: calendar).map { Int($0.timeIntervalSince1970 * 1000) }
        return Enforcement(mode: mode, reason: allowed > 0 ? .allowanceUsed : .schedule, listName: list.name, until: end)
    }

    /// Does this list count the time spent on it today (outside a block, inside its window, inside its allowance)?
    public static func countsUsage(for list: GuardList, now: Date, blockEnd: Int?, usedMs: Int, calendar: Calendar = .current) -> Bool {
        blockEnd == nil && list.allowanceMin > 0 && list.when.covers(now, calendar: calendar) && usedMs < list.allowanceMin * 60_000
    }

    /// A locked list during a block can't be got out of by stopping the block.
    public static func locksTheBlock(_ b: Blocklist) -> Bool { b.lists.contains { $0.mode == .locked && $0.count > 0 } }
}

/// How much of its allowance each list has used today (usage.json). A new day starts at zero.
public struct DayUsage: Codable, Equatable {
    public var day: String
    public var ms: [String: Int]

    public init(day: String = DayUsage.key(Date()), ms: [String: Int] = [:]) { self.day = day; self.ms = ms }
    public init(from decoder: Decoder) throws {
        let c = try decoder.keyed()
        day = c.v("day", DayUsage.key(Date())); ms = c.v("ms", [:])
    }

    public static func key(_ d: Date, calendar: Calendar = .current) -> String {
        let c = calendar.dateComponents([.year, .month, .day], from: d)
        return String(format: "%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0)
    }

    public mutating func add(_ listID: String, ms add: Int, at date: Date = Date()) {
        roll(date)
        ms[listID, default: 0] += add
    }
    public mutating func used(_ listID: String, at date: Date = Date()) -> Int { roll(date); return ms[listID] ?? 0 }
    private mutating func roll(_ date: Date) {
        let k = DayUsage.key(date)
        if k != day { day = k; ms = [:] }
    }
}

public struct UsageStore {
    public let file: URL
    public init(folder: URL) { file = folder.appendingPathComponent("usage.json") }
    public func read() -> DayUsage {
        guard let d = try? Data(contentsOf: file), let u = try? JSONDecoder().decode(DayUsage.self, from: d) else { return DayUsage() }
        return u
    }
    public func write(_ u: DayUsage) { if let d = try? JSONEncoder().encode(u) { try? d.write(to: file, options: .atomic) } }
}

// ---------------------------------------------------------------- the cards

public enum OtherKind: Equatable { case waitItOut, locked, usedUp }

/// "Wait it out", "Locked" and "Used up": the cat over something on a list, with no way to hurry it.
public struct OtherCard: Equatable {
    public var kind: OtherKind
    public var target: Target
    public var listName: String
    public var shownAt: Int
    /// Wait it out: how long the wait is.
    public var waitMs: Int
    /// Locked and used up: when it opens again (nil if not known).
    public var until: Int?
    public var cat: String
    public var catName: String

    public func secondsLeft(now: Int) -> Int { max(0, (waitMs - (now - shownAt) + 999) / 1000) }
    public func fraction(now: Int) -> Double { max(0, min(1, Double(waitMs - (now - shownAt)) / Double(max(1, waitMs)))) }

    public var title: String {
        switch kind {
        case .waitItOut: return "Wait it out."
        case .locked: return until.map { "Locked until \(clockTime($0))." } ?? "Locked."
        case .usedUp: return "That's today's time."
        }
    }
    public func line(now: Int) -> String {
        switch kind {
        case .waitItOut:
            let s = secondsLeft(now: now)
            return "\(target.label) opens in \(s) \(s == 1 ? "second" : "seconds"). Most urges pass by then."
        case .locked: return "\(target.label) is on your \(listName) list, and it won't open until your block ends."
        case .usedUp: return "You've used the time \(target.label) gets outside your blocks." + (until.map { " It opens again after \(clockTime($0))." } ?? "")
        }
    }
}

public enum OtherAction: Equatable {
    case show(OtherCard)
    case update(OtherCard)
    case hide
    case activate(String?)
}

/// Wait it out, locked and used up. A visit that waited its turn is let through until it is left for a few
/// seconds; a locked or used-up one stays covered for as long as it is in front.
public final class OtherGuard {
    public static let waitMs = 30_000
    public static let rejoinMs = 5_000
    public private(set) var card: OtherCard?
    /// The visit that has waited and may stay.
    private var openFor: Target?
    private var leftAt: Int?
    private var dismissed: Target?
    public private(set) var previous: String?
    private let waitMs: Int

    public init(waitMs: Int = OtherGuard.waitMs) { self.waitMs = waitMs }

    /// The front changed. `hit` is the listed thing in front, `enforcement` what its list asks for now.
    public func front(hit: GuardHit?, enforcement: Enforcement?, bundleID: String?, cat: String, catName: String, now: Int) -> [OtherAction] {
        guard let hit, let e = enforcement, e.mode != .speedBump else {
            dismissed = nil
            if hit == nil, let b = bundleID { previous = b }
            if openFor != nil, leftAt == nil { leftAt = now }
            return hideCard()
        }
        let target = hit.target
        if let l = leftAt, now - l >= OtherGuard.rejoinMs { openFor = nil }
        leftAt = nil
        if openFor == target { return hideCard() }
        if let d = dismissed, d == target { return [] }
        // an allowance that is used up is a hard stop for the day, whatever the mode
        let kind: OtherKind = e.reason == .allowanceUsed ? .usedUp : (e.mode == .waitItOut ? .waitItOut : .locked)
        if var c = card {
            if c.target == target && c.kind == kind { return [] }
            c.target = target; c.kind = kind; c.until = e.until; c.listName = e.listName
            card = c
            return [.update(c)]
        }
        let c = OtherCard(kind: kind, target: target, listName: e.listName, shownAt: now, waitMs: waitMs, until: e.until, cat: cat, catName: catName)
        card = c
        return [.show(c)]
    }

    /// Time passes: a wait may be over, and a visit that was left may be forgotten.
    public func tick(now: Int, stillGuarded: Bool = true) -> [OtherAction] {
        if let l = leftAt, now - l >= OtherGuard.rejoinMs { openFor = nil; leftAt = nil }
        guard let c = card else { return [] }
        if !stillGuarded { return hideCard() }
        if c.kind == .waitItOut, now - c.shownAt >= c.waitMs {
            card = nil
            openFor = c.target
            return [.hide]
        }
        return []
    }

    public func backToWork() -> [OtherAction] {
        guard let c = card else { return [] }
        card = nil
        dismissed = c.target
        return [.hide, .activate(previous)]
    }

    private func hideCard() -> [OtherAction] {
        guard card != nil else { return [] }
        card = nil
        return [.hide]
    }
}
