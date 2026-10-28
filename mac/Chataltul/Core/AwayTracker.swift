import Foundation

/// The Mac was left alone from `from` to `to` in the middle of a block.
public struct AwayReturn: Equatable {
    public var from: Int
    public var to: Int
    public var ms: Int { to - from }
    public var minutes: Int { max(1, Int((Double(ms) / 60000).rounded())) }
}

/// "You were away from the Mac": more than two minutes without a key or a click in the middle of a block,
/// or the Mac asleep or locked that long. Being away is not drifting: nothing happens to the kitten, the
/// person is only asked whether those minutes count. Pure: the app feeds it the idle time and the
/// sleep, wake, lock and unlock moments.
public struct AwayTracker {
    public var thresholdMs: Int
    private var since: Int?
    private var offline = false

    public init(thresholdMs: Int = 120_000) { self.thresholdMs = thresholdMs }

    public var isAway: Bool { since != nil }

    /// The Mac went to sleep or the screen locked.
    public mutating func offlineNow(now: Int, idleMs: Int) {
        if since == nil { since = now - max(0, idleMs) }
        offline = true
    }

    /// It woke or unlocked. The return is noticed with the next sample that shows activity.
    public mutating func onlineNow() { offline = false }

    /// A look at the idle time (seconds since the last key or click, in ms). `running`: a block is running
    /// and not paused. Returns the away span when the person is back and it counts.
    public mutating func sample(idleMs: Int, now: Int, running: Bool) -> AwayReturn? {
        if offline { return nil }
        if idleMs >= thresholdMs {
            if since == nil { since = now - idleMs }
            return nil
        }
        guard let s = since else { return nil }
        since = nil
        let to = now - max(0, idleMs)
        guard running, to - s >= thresholdMs else { return nil }
        return AwayReturn(from: s, to: to)
    }
}
