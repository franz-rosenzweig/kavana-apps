import Foundation

/// The cycle presets of the Breaks settings: how long to focus and how long to rest.
public enum CyclePreset: String, Codable, CaseIterable {
    case pomodoro, fiftyTwo, deep, flow

    public var title: String {
        switch self { case .pomodoro: return "Pomodoro"; case .fiftyTwo: return "52 / 17"; case .deep: return "Deep work"; case .flow: return "Flowtime" }
    }
    public var summary: String {
        switch self {
        case .pomodoro: return "25 on, 5 off"
        case .fiftyTwo: return "52 on, 17 off"
        case .deep: return "90 on, 20 off"
        case .flow: return "a stopwatch, break by feel"
        }
    }
    /// Minutes to focus and to break; Flowtime has no set length.
    public var minutes: (focus: Int, rest: Int)? {
        switch self { case .pomodoro: return (25, 5); case .fiftyTwo: return (52, 17); case .deep: return (90, 20); case .flow: return nil }
    }
    public var stopwatch: Bool { self == .flow }

    /// Flowtime: the break after a stretch is a fifth of it, between 5 and 25 minutes.
    public static func flowBreak(focusedMinutes: Int) -> Int { max(5, min(25, Int((Double(focusedMinutes) / 5).rounded()))) }
}

/// How breaks are started and how firm they are.
public enum BreakStart: String, Codable, CaseIterable {
    /// The popover offers the break when a block is filed.
    case offer
    /// It starts when a block ends and the kitten is home.
    case auto
}

public struct BreakSettings: Codable, Equatable {
    public static let defaultIdeas = ["Stand up and stretch", "Get some water", "Look out of a window", "Ten slow breaths", "A mindful minute", "Walk to the kitchen and back"]

    public var fullScreen = true
    /// No Postpone and no Skip.
    public var strict = false
    public var eyeBreaks = true
    /// Eye breaks and check-ins hold during calls and full-screen video.
    public var waitDuringCalls = true
    public var start = BreakStart.offer
    public var postponeMin = 5
    public var eyeEveryMin = 20
    public var eyeSeconds = 20
    public var ideas = BreakSettings.defaultIdeas

    public init() {}
    public init(from decoder: Decoder) throws {
        let c = try decoder.keyed()
        fullScreen = c.v("fullScreen", true); strict = c.v("strict", false); eyeBreaks = c.v("eyeBreaks", true)
        waitDuringCalls = c.v("waitDuringCalls", true); start = c.v("start", BreakStart.offer)
        postponeMin = c.v("postponeMin", 5); eyeEveryMin = c.v("eyeEveryMin", 20); eyeSeconds = c.v("eyeSeconds", 20)
        ideas = c.v("ideas", BreakSettings.defaultIdeas)
    }

    /// The idea for a break (the first), and the others as ideas to turn to.
    public func ideaFor(_ n: Int) -> (main: String, others: [String]) {
        guard !ideas.isEmpty else { return ("Take a breath.", []) }
        let i = ((n % ideas.count) + ideas.count) % ideas.count
        var rest = ideas
        let main = rest.remove(at: i)
        return (main, Array(rest.prefix(3)))
    }
}

/// "Look at something far away for 20 seconds, every 20 minutes of a block." It waits while the Mac is busy
/// with a call or full-screen video, and being away from the Mac for 20 seconds counts as one.
public struct EyeBreakTimer {
    public var everyMs: Int
    public var seconds: Int
    /// When the last eye break (or the start of the block) was.
    public private(set) var since: Int?
    public private(set) var showing: Int?

    public init(everyMin: Int = 20, seconds: Int = 20) { everyMs = everyMin * 60_000; self.seconds = seconds }

    /// A block started: the first one comes after a full interval.
    public mutating func blockStarted(at now: Int) { since = now; showing = nil }
    public mutating func blockEnded() { since = nil; showing = nil }

    public var nextAt: Int? { since.map { $0 + everyMs } }

    /// Is it time, and may it show? Returns true once, then it counts the card as showing until `finish`.
    public mutating func check(now: Int, running: Bool, busy: Bool) -> Bool {
        guard running, showing == nil, let s = since, now - s >= everyMs, !busy else { return false }
        showing = now
        return true
    }

    /// The card went (its 20 seconds, or a Skip): the next one is a full interval away.
    public mutating func finish(at now: Int) { showing = nil; since = now }

    /// The person was away from the Mac this long: that was an eye break.
    public mutating func awayFor(ms: Int, now: Int) -> Bool {
        guard since != nil, ms >= seconds * 1000 else { return false }
        since = now
        return true
    }
}
