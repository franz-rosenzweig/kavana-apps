import AppKit
import UserNotifications

/// Check-ins and the end of a block as ordinary notifications: the way they reach the person when the cat
/// isn't on screen (the cat is off, or the Mac was asleep). Each ping the engine lists is scheduled; when
/// the cat shows one instead, the notification is taken back.
final class Notifier: NSObject, UNUserNotificationCenterDelegate {
    let store: Store
    private var center: UNUserNotificationCenter? { Self.available ? UNUserNotificationCenter.current() : nil }
    private var scheduled: Set<String> = []
    /// Yes or Drifted tapped on a notification.
    var onAnswer: (String, Int, String) -> Void = { _, _, _ in }
    var onOpen: () -> Void = {}

    /// Notifications need an app bundle (not a bare binary).
    static var available: Bool { Bundle.main.bundleURL.pathExtension == "app" && ProcessInfo.processInfo.environment["CHATALTUL_NO_NOTIFY"] == nil }

    init(store: Store) {
        self.store = store
        super.init()
        guard let c = center else { return }
        c.delegate = self
        let yes = UNNotificationAction(identifier: "yes", title: "Yes")
        let drifted = UNNotificationAction(identifier: "drifted", title: "Drifted")
        c.setNotificationCategories([UNNotificationCategory(identifier: "checkin", actions: [yes, drifted], intentIdentifiers: [], options: [])])
    }

    /// Asks once, the first time a block starts.
    func askIfNeeded() {
        guard let c = center else { return }
        c.getNotificationSettings { s in
            if s.authorizationStatus == .notDetermined { c.requestAuthorization(options: [.alert, .sound]) { _, _ in } }
        }
    }

    func authorization(_ done: @escaping (UNAuthorizationStatus) -> Void) {
        guard let c = center else { done(.denied); return }
        c.getNotificationSettings { s in DispatchQueue.main.async { done(s.authorizationStatus) } }
    }

    /// Keeps the scheduled notifications equal to the engine's list of pings.
    func sync(_ pings: [Ping]) {
        guard let c = center else { return }
        let now = nowMs()
        // With the cat in the corner a notification is the second chance, so it waits a few seconds
        let lag = store.settings.catInCorner && store.state.catsOn ? 4_000 : 0
        let want = Dictionary(pings.map { ($0.key, $0) }, uniquingKeysWith: { a, _ in a })
        // one that left the list before its time was cancelled (Stop, an answer); one whose time came is left to fire
        let gone = scheduled.subtracting(want.keys).filter { key in
            guard let at = Int(key.split(separator: ":").last ?? "") else { return true }
            return at > now + 5_000
        }
        if !gone.isEmpty { c.removePendingNotificationRequests(withIdentifiers: Array(gone)) }
        for (key, p) in want where !scheduled.contains(key) {
            let content = UNMutableNotificationContent()
            content.title = p.title
            content.body = p.kind == "checkin" ? p.body.replacingOccurrences(of: " Tap to answer.", with: "") : p.body
            content.sound = p.kind == "nudge" ? nil : .default
            content.threadIdentifier = p.tag
            content.userInfo = ["block": p.block ?? "", "n": p.n ?? 0, "kind": p.kind]
            if p.kind == "checkin" { content.categoryIdentifier = "checkin" }
            let seconds = max(1, Double(p.at + lag - now) / 1000)
            c.add(UNNotificationRequest(identifier: key, content: content, trigger: UNTimeIntervalNotificationTrigger(timeInterval: seconds, repeats: false)))
        }
        scheduled = Set(want.keys)
    }

    /// The cat showed this ping, so its notification isn't needed.
    func shown(_ p: Ping) {
        guard let c = center else { return }
        c.removePendingNotificationRequests(withIdentifiers: [p.key])
        c.removeDeliveredNotifications(withIdentifiers: [p.key])
    }

    /// A notification now (the cat is off, or nobody answered it).
    func postNow(title: String, body: String) {
        guard let c = center else { return }
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        c.add(UNNotificationRequest(identifier: "now-\(nowMs())", content: content, trigger: nil))
    }

    func clearAll() {
        center?.removeAllPendingNotificationRequests()
        center?.removeAllDeliveredNotifications()
        scheduled = []
    }

    /// A notification that wasn't answered by the cat or the popover goes away once the check-in has been.
    func clearDelivered() { center?.removeAllDeliveredNotifications() }

    // while Chataltul is the front app, notifications still show
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification, withCompletionHandler done: @escaping (UNNotificationPresentationOptions) -> Void) {
        done([.banner, .sound])
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse, withCompletionHandler done: @escaping () -> Void) {
        let info = response.notification.request.content.userInfo
        let id = info["block"] as? String ?? ""
        let n = info["n"] as? Int ?? 0
        switch response.actionIdentifier {
        case "yes", "drifted": DispatchQueue.main.async { self.onAnswer(id, n, response.actionIdentifier) }
        default: DispatchQueue.main.async { self.onOpen() }
        }
        done()
    }
}
