import Foundation

/// A listed app: found by its bundle id, shown by its name.
public struct ListedApp: Codable, Equatable, Hashable {
    public var bundleID: String
    public var name: String
    public init(bundleID: String, name: String) { self.bundleID = bundleID; self.name = name }
}

/// What the cat walks over: apps, and sites in a browser the app can read.
public struct Blocklist: Codable, Equatable {
    public var apps: [ListedApp]
    public var sites: [String]

    public init(apps: [ListedApp] = [], sites: [String] = []) { self.apps = apps; self.sites = sites }

    /// What a new install starts with: the sites most people mean, and the two chatty built-in apps.
    /// Nothing the person might need for work (Mail, Slack) is listed for them.
    public static let starter = Blocklist(
        apps: [ListedApp(bundleID: "com.apple.MobileSMS", name: "Messages"), ListedApp(bundleID: "com.apple.news", name: "News")],
        sites: ["youtube.com", "reddit.com", "x.com", "instagram.com", "facebook.com", "tiktok.com", "news.ycombinator.com"])

    public var count: Int { apps.count + sites.count }
    public var isEmpty: Bool { apps.isEmpty && sites.isEmpty }

    /// The listed app with this bundle id.
    public func app(_ bundleID: String) -> ListedApp? { apps.first { $0.bundleID == bundleID } }

    /// The listed domain a page address falls under (a listed domain covers its subdomains), or nil.
    public func site(forURL url: String) -> String? {
        guard let host = Blocklist.host(of: url) else { return nil }
        return sites.first { host == $0 || host.hasSuffix("." + $0) }
    }

    /// "https://www.Reddit.com/r/cats?x=1" and "reddit.com/r/cats" both give "reddit.com".
    public static func host(of address: String) -> String? {
        let t = address.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.isEmpty { return nil }
        let withScheme = t.contains("://") ? t : "https://" + t
        guard let h = URLComponents(string: withScheme)?.host?.lowercased(), !h.isEmpty else { return nil }
        var host = h
        while host.hasSuffix(".") { host.removeLast() }
        if host.hasPrefix("www.") { host.removeFirst(4) }
        return host.isEmpty ? nil : host
    }

    /// What goes in the list when someone types a site: the bare domain, or nil if it isn't one.
    public static func normalizeDomain(_ typed: String) -> String? {
        guard let h = host(of: typed), h.contains("."), !h.contains(" ") else { return nil }
        return h
    }

    @discardableResult
    public mutating func add(site typed: String) -> Bool {
        guard let d = Blocklist.normalizeDomain(typed), !sites.contains(d) else { return false }
        sites.append(d)
        return true
    }

    @discardableResult
    public mutating func add(app: ListedApp) -> Bool {
        guard !apps.contains(where: { $0.bundleID == app.bundleID }) else { return false }
        apps.append(app)
        return true
    }
}

/// What the person is looking at, as far as the list cares.
public enum Target: Equatable {
    case app(ListedApp)
    case site(domain: String, browser: String)

    /// For the card: "youtube.com" or "Messages".
    public var label: String {
        switch self { case .app(let a): return a.name; case .site(let d, _): return d }
    }
}

/// Browsers whose address the app can read with AppleScript, and how. Firefox and Zen don't have the
/// scripting needed, so they can't be read.
public struct ReadableBrowser: Equatable {
    public let bundleID: String
    public let name: String
    /// The AppleScript that gives the front window's current address (run in the browser).
    public let script: String

    public static let all: [ReadableBrowser] = [
        ReadableBrowser(bundleID: "com.apple.Safari", name: "Safari", script: "tell application id \"com.apple.Safari\" to if (count of windows) > 0 then return URL of current tab of front window"),
        ReadableBrowser(bundleID: "com.google.Chrome", name: "Chrome", script: "tell application id \"com.google.Chrome\" to if (count of windows) > 0 then return URL of active tab of front window"),
        ReadableBrowser(bundleID: "com.brave.Browser", name: "Brave", script: "tell application id \"com.brave.Browser\" to if (count of windows) > 0 then return URL of active tab of front window"),
        ReadableBrowser(bundleID: "company.thebrowser.Browser", name: "Arc", script: "tell application id \"company.thebrowser.Browser\" to if (count of windows) > 0 then return URL of active tab of front window"),
        ReadableBrowser(bundleID: "com.microsoft.edgemac", name: "Edge", script: "tell application id \"com.microsoft.edgemac\" to if (count of windows) > 0 then return URL of active tab of front window"),
    ]

    public static func with(bundleID: String) -> ReadableBrowser? { all.first { $0.bundleID == bundleID } }

    /// Browsers that can't be read, so the list says so.
    public static let unreadable: [String: String] = ["org.mozilla.firefox": "Firefox", "app.zen-browser.zen": "Zen"]
}
