import AppKit
import CoreGraphics

/// Watches for the Mac being left alone in the middle of a block: no key or click for the set time, or the
/// Mac asleep or locked. The rules are AwayTracker's; this feeds it.
final class AwayMonitor {
    let store: Store
    private var tracker: AwayTracker
    private var observers: [(NotificationCenter, NSObjectProtocol)] = []
    /// A way to fake the idle time, for tests.
    var idleOverride: (() -> Int)?

    init(store: Store) {
        self.store = store
        tracker = AwayTracker(thresholdMs: store.settings.awayAfterMin * 60_000)
        func add(_ center: NotificationCenter, _ name: Notification.Name, _ body: @escaping () -> Void) {
            observers.append((center, center.addObserver(forName: name, object: nil, queue: .main) { _ in body() }))
        }
        let ws = NSWorkspace.shared.notificationCenter
        let dist = DistributedNotificationCenter.default()
        add(ws, NSWorkspace.willSleepNotification) { [weak self] in self?.offline() }
        add(ws, NSWorkspace.screensDidSleepNotification) { [weak self] in self?.offline() }
        add(dist, Notification.Name("com.apple.screenIsLocked")) { [weak self] in self?.offline() }
        add(ws, NSWorkspace.didWakeNotification) { [weak self] in self?.tracker.onlineNow() }
        add(ws, NSWorkspace.screensDidWakeNotification) { [weak self] in self?.tracker.onlineNow() }
        add(dist, Notification.Name("com.apple.screenIsUnlocked")) { [weak self] in self?.tracker.onlineNow() }
    }

    deinit { for (c, o) in observers { c.removeObserver(o) } }

    /// Milliseconds since the last key press or click.
    static func idleMs() -> Int {
        let anyInput = CGEventType(rawValue: ~0)!
        return Int(CGEventSource.secondsSinceLastEventType(.combinedSessionState, eventType: anyInput) * 1000)
    }

    /// Milliseconds since the last key or click (or what a test says).
    func currentIdleMs() -> Int { idleOverride?() ?? Self.idleMs() }

    private func offline() {
        guard store.running else { return }
        tracker.offlineNow(now: nowMs(), idleMs: idleOverride?() ?? Self.idleMs())
    }

    /// Every couple of seconds while a block runs.
    func sample(now: Int) {
        tracker.thresholdMs = max(1, store.settings.awayAfterMin) * 60_000
        let idle = idleOverride?() ?? Self.idleMs()
        guard let back = tracker.sample(idleMs: idle, now: now, running: store.running) else { return }
        switch store.settings.awayAction {
        case .count: break
        case .takeOff:
            if store.state.canTakeOff { store.act("takeOff", ["from": back.from, "to": back.to]) } else { store.awayPrompt = back }
        case .ask: store.awayPrompt = back
        }
    }
}
