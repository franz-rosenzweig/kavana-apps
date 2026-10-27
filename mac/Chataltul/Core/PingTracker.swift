import Foundation

/// The engine lists the pings still to come (E.pings). A ping whose time came leaves the list, and it's
/// then due: this finds those between one look and the next, the way the Android app's Repo.after() does.
public struct PingTracker {
    private var before: [Ping] = []

    public init() {}

    /// The next list of pings; returns the ones that came due since the last, oldest first. Several of a
    /// kind at once (the Mac was asleep) become the last of them, and an old check-in or nudge is no
    /// use any more. The end of a block is always told.
    public mutating func update(_ now: [Ping], at time: Int) -> [Ping] {
        let still = Set(now.map { $0.key })
        let due = before.filter { $0.at <= time + 1500 && !still.contains($0.key) }
        before = now
        var lastOfKind: [String: Ping] = [:]
        for p in due { lastOfKind[p.kind] = p }
        return lastOfKind.values
            .filter { $0.kind == "end" || time - $0.at < 10 * 60_000 }
            .sorted { $0.at < $1.at }
    }

    /// The soonest ping still to come, for the timer that wakes the app.
    public var next: Int? { before.map { $0.at }.min() }
}
