import Foundation

// The speed bump: while a block runs and the person opens an app (or a site) on their list, the cat
// walks over it. This file is the rules, with no screen in it, so the self-test can run them.
//
//  - Only while a block runs, isn't paused, and its kitten is still growing. A kitten that wandered off
//    doesn't come up again for that block.
//  - The card counts down 15 seconds. "Back to work" takes them back to the app they were in before.
//  - "Leave anyway" has to be held (HoldTracker). Held, the kitten wanders off right away.
//  - No pause, no snooze, no close. If the 15 seconds run out, the kitten wanders off and the block goes on.

/// What the bump needs to know of the running block.
public struct BumpBlock: Equatable {
    public var id: String
    public var paused: Bool
    public var kitten: String      // "growing", "home" or "gone"
    public var cat: String
    public var catName: String
    public var title: String
    public var end: Int
    public init(id: String, paused: Bool = false, kitten: String = "growing", cat: String = "miso", catName: String = "Miso", title: String = "Focus", end: Int = 0) {
        self.id = id; self.paused = paused; self.kitten = kitten; self.cat = cat; self.catName = catName; self.title = title; self.end = end
    }
    /// A block the card may come up in.
    var open: Bool { kitten == "growing" && !paused }
}

/// The card on the screen.
public struct BumpCard: Equatable {
    public var target: Target
    public var shownAt: Int
    public var blockID: String
    public var cat: String
    public var catName: String
    public var title: String
    public var blockEnd: Int

    /// Whole seconds left of the grace, 15 down to 0.
    public func secondsLeft(now: Int, graceMs: Int) -> Int { max(0, (graceMs - (now - shownAt) + 999) / 1000) }
    /// 1 at the start, 0 at the end, for the ring.
    public func fraction(now: Int, graceMs: Int) -> Double { max(0, min(1, Double(graceMs - (now - shownAt)) / Double(graceMs))) }
    public func line(now: Int, graceMs: Int) -> String {
        let s = secondsLeft(now: now, graceMs: graceMs)
        return "Come back within \(s) \(s == 1 ? "second" : "seconds"), or \(catName) wanders off."
    }
}

public enum BumpAction: Equatable {
    case show(BumpCard)
    case update(BumpCard)
    case hide
    /// Send the engine's away op: the kitten left `at`, after `grace` milliseconds.
    case away(at: Int, grace: Int)
    /// Say "<name> wandered off. Your block keeps going." for a moment.
    case wandered(catName: String)
    /// Bring this app back to the front.
    case activate(String?)
}

public final class SpeedBump {
    public static let defaultGraceMs = 15_000
    public let graceMs: Int
    public private(set) var card: BumpCard?
    /// The block whose kitten already left because of the bump.
    private var suppressed: String?
    /// After "Back to work": the same target doesn't bump again until it has been left.
    private var dismissed: Target?
    /// The last app that wasn't on the list: where "Back to work" goes.
    public private(set) var previous: String?

    public init(graceMs: Int = SpeedBump.defaultGraceMs) { self.graceMs = graceMs }

    /// The front app changed (or was looked at again). `target` is what's on the list that it matches, if
    /// anything; `bundleID` is the front app, which becomes "where they were" when it isn't on the list.
    public func front(target: Target?, bundleID: String?, block: BumpBlock?, now: Int) -> [BumpAction] {
        guard let target else {
            dismissed = nil
            if let b = bundleID { previous = b }
            return hideCard()
        }
        guard let b = block, b.open, b.id != suppressed else { return hideCard() }
        if let d = dismissed, d == target { return [] }
        if var c = card {
            if c.target == target { return [] }
            c.target = target
            card = c
            return [.update(c)]
        }
        let c = BumpCard(target: target, shownAt: now, blockID: b.id, cat: b.cat, catName: b.catName, title: b.title, blockEnd: b.end)
        card = c
        return [.show(c)]
    }

    /// Time passes: the grace may run out.
    public func tick(block: BumpBlock?, now: Int) -> [BumpAction] {
        guard let c = card else { return [] }
        guard let b = block, b.open, b.id == c.blockID else { return hideCard() }
        guard now - c.shownAt >= graceMs else { return [] }
        card = nil
        suppressed = b.id
        return [.hide, .away(at: c.shownAt, grace: graceMs), .wandered(catName: c.catName)]
    }

    /// "Back to work".
    public func backToWork() -> [BumpAction] {
        guard let c = card else { return [] }
        card = nil
        dismissed = c.target
        return [.hide, .activate(previous)]
    }

    /// "Leave anyway", held long enough: the kitten wanders off now.
    public func leaveHeld(now: Int) -> [BumpAction] {
        guard let c = card else { return [] }
        card = nil
        suppressed = c.blockID
        return [.hide, .away(at: now, grace: 0), .wandered(catName: c.catName)]
    }

    private func hideCard() -> [BumpAction] {
        guard card != nil else { return [] }
        card = nil
        return [.hide]
    }
}

/// "Leave anyway" counts only when held for `holdMs`. A plain tap only says "Hold to leave".
public struct HoldTracker {
    public static let defaultHoldMs = 2_000
    public static let quickTapMs = 400
    public let holdMs: Int
    private var downAt: Int?
    public private(set) var done = false
    /// A plain tap was made: the button says "Hold to leave" from then on.
    public private(set) var hint = false

    public init(holdMs: Int = HoldTracker.defaultHoldMs) { self.holdMs = holdMs }

    public var holding: Bool { downAt != nil }

    public mutating func down(now: Int) { if !done { downAt = now } }

    public mutating func up(now: Int) {
        defer { downAt = nil }
        guard let d = downAt, !done else { return }
        if now - d < HoldTracker.quickTapMs { hint = true }
    }

    /// 0 to 1: how far the button has filled.
    public func progress(now: Int) -> Double {
        guard let d = downAt else { return 0 }
        return max(0, min(1, Double(now - d) / Double(holdMs)))
    }

    /// True once, when the hold has lasted long enough.
    public mutating func check(now: Int) -> Bool {
        guard let d = downAt, !done, now - d >= holdMs else { return false }
        done = true
        downAt = nil
        return true
    }
}
