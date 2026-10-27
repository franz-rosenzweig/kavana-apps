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
    var onFront: (Target?, String?) -> Void = { _, _ in }
    /// A readable browser is in front during a block with sites on the list, and the person hasn't been asked yet.
    var onNeedBrowserAccess: (ReadableBrowser) -> Void = { _ in }

    private var access: [String: BrowserAccess] = [:]
    private let scripts = DispatchQueue(label: "xyz.tiresia.chataltul.applescript")
    private var reading = false
    private var lastURL: [String: String] = [:]
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
        if let a = store.settings.blocklist.app(bid) {
            onFront(.app(a), bid)
            return
        }
        guard let browser = ReadableBrowser.with(bundleID: bid), !store.settings.blocklist.sites.isEmpty else {
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
                if let url, let domain = self.store.settings.blocklist.site(forURL: url) {
                    self.lastURL[browser.bundleID] = url
                    self.onFront(.site(domain: domain, browser: browser.name), browser.bundleID)
                } else {
                    self.onFront(nil, browser.bundleID)
                }
            }
        }
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
