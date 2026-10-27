import AppKit
import Carbon.HIToolbox

/// What macOS says about reading a browser's address.
enum BrowserAccess: String {
    case unknown, allowed, denied, notRunning
}

/// Which app is in front, and for a browser Chataltul can read, which page. The address comes from
/// AppleScript, which needs the person's OK in System Settings → Privacy → Automation, once for each
/// browser. A "no" is remembered and the browser is simply not read: the app list still works.
final class Watcher {
    let store: Store
    /// A look at the front: the listed thing it matches (or nil), and the front app's bundle id.
    var onFront: (GuardHit?, String?) -> Void = { _, _ in }
    /// A readable browser is in front during a block with sites on the list, and the person hasn't been asked yet.
    var onNeedBrowserAccess: (ReadableBrowser) -> Void = { _ in }

    private var access: [String: BrowserAccess] = [:]
    private let scripts = DispatchQueue(label: "xyz.tiresia.chataltul.applescript")
    private var reading = false
    private var lastURL: [String: String] = [:]
    private var lastDomain: [String: (domain: String, at: Int)] = [:]
    private var asked = Set<String>()
    private var observer: NSObjectProtocol?

    init(store: Store) {
        self.store = store
        observer = NSWorkspace.shared.notificationCenter.addObserver(forName: NSWorkspace.didActivateApplicationNotification, object: nil, queue: .main) { [weak self] _ in
            self?.look()
        }
    }

    deinit { if let o = observer { NSWorkspace.shared.notificationCenter.removeObserver(o) } }

    func state(of browser: ReadableBrowser) -> BrowserAccess { access[browser.bundleID] ?? .unknown }

    /// Looks at what is in front now. Called when the front app changes and about once a second while a
    /// browser is in front.
    /// Tests don't read the real front app or any browser (which would ask for permissions on someone's screen).
    static let disabled = ProcessInfo.processInfo.environment["CHATALTUL_NO_WATCH"] != nil

    func look() {
        guard !Self.disabled else { return }
        guard let app = NSWorkspace.shared.frontmostApplication, let bid = app.bundleIdentifier else { return }
        // Chataltul's own windows in front (its popover, settings) change nothing
        if bid == Bundle.main.bundleIdentifier { return }
        if let h = store.settings.blocklist.hit(bundleID: bid) {
            onFront(h, bid)
            return
        }
        // a browser is read for the list's sites, and for the sites a block went to when that is being remembered
        guard let browser = ReadableBrowser.with(bundleID: bid), (store.settings.blocklist.watchesSites || (store.settings.recordTiming && store.running)) else {
            onFront(nil, bid)
            return
        }
        switch state(of: browser) {
        case .denied: onFront(nil, bid)
        case .unknown:
            onFront(nil, bid)
            if asked.insert(bid).inserted { onNeedBrowserAccess(browser) }
        default: read(browser)
        }
    }

    /// Asks macOS for permission to read this browser, which shows its own prompt. `completion`: the answer.
    func requestAccess(_ browser: ReadableBrowser, completion: @escaping (BrowserAccess) -> Void = { _ in }) {
        if Self.disabled { completion(.unknown); return }
        // The prompt names the browser, so it has to be running.
        guard NSRunningApplication.runningApplications(withBundleIdentifier: browser.bundleID).first != nil else {
            access[browser.bundleID] = .notRunning
            completion(.notRunning)
            return
        }
        scripts.async { [weak self] in
            let status = Self.permission(for: browser.bundleID, ask: true)
            DispatchQueue.main.async {
                self?.access[browser.bundleID] = status
                completion(status)
            }
        }
    }

    /// What macOS has on record for this browser, without asking.
    func refreshAccess(_ browser: ReadableBrowser, completion: @escaping (BrowserAccess) -> Void = { _ in }) {
        if Self.disabled { completion(.unknown); return }
        guard NSRunningApplication.runningApplications(withBundleIdentifier: browser.bundleID).first != nil else {
            completion(access[browser.bundleID] ?? .notRunning)
            return
        }
        scripts.async { [weak self] in
            let status = Self.permission(for: browser.bundleID, ask: false)
            DispatchQueue.main.async {
                if status != .unknown || self?.access[browser.bundleID] == nil { self?.access[browser.bundleID] = status }
                completion(self?.access[browser.bundleID] ?? status)
            }
        }
    }

    private static func permission(for bundleID: String, ask: Bool) -> BrowserAccess {
        let target = NSAppleEventDescriptor(bundleIdentifier: bundleID)
        let status = AEDeterminePermissionToAutomateTarget(target.aeDesc, typeWildCard, typeWildCard, ask)
        switch Int(status) {
        case Int(noErr): return .allowed
        case Int(errAEEventNotPermitted): return .denied
        case Int(errAEEventWouldRequireUserConsent): return .unknown
        case Int(procNotFound): return .notRunning
        default: return .unknown
        }
    }

    private func read(_ browser: ReadableBrowser) {
        if reading { return }
        reading = true
        scripts.async { [weak self] in
            var error: NSDictionary?
            let result = NSAppleScript(source: browser.script)?.executeAndReturnError(&error)
            let url = result?.stringValue
            let code = (error?[NSAppleScript.errorNumber] as? Int) ?? 0
            DispatchQueue.main.async {
                guard let self else { return }
                self.reading = false
                if code == -1743 { self.access[browser.bundleID] = .denied }
                else if code == 0 { self.access[browser.bundleID] = .allowed }
                // only act if the browser is still the one in front
                guard NSWorkspace.shared.frontmostApplication?.bundleIdentifier == browser.bundleID else { return }
                if let url, let d = Blocklist.host(of: url) { self.lastDomain[browser.bundleID] = (d, nowMs()) }
                if let url, let hit = self.store.settings.blocklist.hit(url: url, browser: browser.name, matcher: self.store.categories) {
                    self.lastURL[browser.bundleID] = url
                    self.onFront(hit, browser.bundleID)
                } else {
                    self.onFront(nil, browser.bundleID)
                }
            }
        }
    }

    /// The domain of the page last seen in this browser, if it was seen a moment ago. Only the domain is kept, never the address.
    func recentDomain(for bundleID: String, within ms: Int) -> String? {
        guard let l = lastDomain[bundleID], nowMs() - l.at <= ms else { return nil }
        return l.domain
    }

    /// Whether the front app is a readable browser (so the app should look again soon).
    var browserInFront: Bool {
        guard let bid = NSWorkspace.shared.frontmostApplication?.bundleIdentifier else { return false }
        return ReadableBrowser.with(bundleID: bid) != nil
    }

    /// Brings an app back to the front.
    static func activate(_ bundleID: String?) {
        guard let id = bundleID, let app = NSRunningApplication.runningApplications(withBundleIdentifier: id).first else { return }
        app.activate(options: [])
    }
}
